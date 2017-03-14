/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql.execution.columnar

import java.sql.Connection
import java.util.concurrent.locks.ReentrantReadWriteLock

import scala.collection.mutable

import _root_.io.snappydata.SnappyTableStatsProviderService

import org.apache.spark.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.SortDirection
import org.apache.spark.sql.collection.Utils
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.datasources.jdbc.JdbcUtils
import org.apache.spark.sql.execution.datasources.{DataSource, LogicalRelation}
import org.apache.spark.sql.hive.{QualifiedTableName, SnappyStoreHiveCatalog}
import org.apache.spark.sql.jdbc.{JdbcDialect, JdbcDialects}
import org.apache.spark.sql.row.GemFireXDBaseDialect
import org.apache.spark.sql.snappy._
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.{StructField, StructType}


/**
 * A LogicalPlan implementation for an external column table whose contents
 * are retrieved using a JDBC URL or DataSource.
 */
case class JDBCAppendableRelation(
    table: String,
    provider: String,
    mode: SaveMode,
    override val schema: StructType,
    origOptions: Map[String, String],
    externalStore: ExternalStore,
    @transient override val sqlContext: SQLContext) extends BaseRelation
    with PrunedUnsafeFilteredScan
    with InsertableRelation
    with PlanInsertableRelation
    with DestroyRelation
    with IndexableRelation
    with Logging
    with Serializable {

  self =>

  override val needConversion: Boolean = false

  var tableExists: Boolean = _

  protected final val connProperties: ConnectionProperties =
    externalStore.connProperties

  protected final val connFactory: () => Connection = JdbcUtils
      .createConnectionFactory(connProperties.url, connProperties.connProps)

  val resolvedName: String = externalStore.tryExecute(table, conn => {
    ExternalStoreUtils.lookupName(table, conn.getSchema)
  })

  def numBuckets: Int = -1

  override def sizeInBytes: Long = {
    SnappyTableStatsProviderService.getTableStatsFromService(table) match {
      case Some(s) => s.getTotalSize
      case None => super.sizeInBytes
    }
  }


  protected final def dialect: JdbcDialect = connProperties.dialect

  val schemaFields: Map[String, StructField] = Utils.getSchemaFields(schema)

  private val bufferLock = new ReentrantReadWriteLock()

  /** Acquires a read lock on the cache for the duration of `f`. */
  private[sql] def readLock[A](f: => A): A = {
    val lock = bufferLock.readLock()
    lock.lock()
    try f finally {
      lock.unlock()
    }
  }

  /** Acquires a write lock on the cache for the duration of `f`. */
  private[sql] def writeLock[A](f: => A): A = {
    val lock = bufferLock.writeLock()
    lock.lock()
    try f finally {
      lock.unlock()
    }
  }

  override def buildUnsafeScan(requiredColumns: Array[String],
      filters: Array[Filter]): (RDD[Any], Seq[RDD[InternalRow]]) = {
    val rdd = scanTable(table, requiredColumns, filters)
    (rdd.mapPartitionsPreserve(Iterator[Any](Iterator.empty, _)), Nil)
  }

  def scanTable(tableName: String, requiredColumns: Array[String],
      filters: Array[Filter]): RDD[ColumnBatch] = {

    val requestedColumns = if (requiredColumns.isEmpty) {
      val narrowField =
        schema.fields.minBy { a =>
          ColumnType(a.dataType).defaultSize
        }

      Array(narrowField.name)
    } else {
      requiredColumns
    }

    readLock {
      externalStore.getColumnBatchRDD(tableName,
        requestedColumns.map(column => externalStore.columnPrefix + column),
        sqlContext.sparkSession)
    }
  }

  override def getInsertPlan(relation: LogicalRelation,
      child: SparkPlan): SparkPlan = {
    new ColumnInsertExec(child, Seq.empty, Seq.empty, this, table)
  }

  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    // use the normal DataFrameWriter which will create an InsertIntoTable plan
    // that will use the getInsertPlan above (in StoreStrategy)
    data.write.mode(if (overwrite) SaveMode.Overwrite else SaveMode.Append)
        .insertInto(table)
  }

  def getColumnBatchParams: (Int, Int, String) = {
    val session = sqlContext.sparkSession
    val columnBatchSize = origOptions.get(
        ExternalStoreUtils.COLUMN_BATCH_SIZE) match {
      case Some(cb) => Integer.parseInt(cb)
      case None => ExternalStoreUtils.defaultColumnBatchSize(session)
    }
    val columnMaxDeltaRows = origOptions.get(
        ExternalStoreUtils.COLUMN_MAX_DELTA_ROWS) match {
      case Some(cd) => Integer.parseInt(cd)
      case None => ExternalStoreUtils.defaultColumnMaxDeltaRows(session)
    }
    val compressionCodec = origOptions.get(
        ExternalStoreUtils.COMPRESSION_CODEC) match {
      case Some(codec) => codec
      case None => ExternalStoreUtils.defaultCompressionCodec(session)
    }
    (columnBatchSize, columnMaxDeltaRows, compressionCodec)
  }

  // truncate both actual and shadow table
  def truncate(): Unit = writeLock {
    externalStore.tryExecute(table, conn => {
      JdbcExtendedUtils.truncateTable(conn, table, dialect)
    })
  }

  def createTable(mode: SaveMode): Unit = {
    val conn = connFactory()
    try {
      tableExists = JdbcExtendedUtils.tableExists(table, conn,
        dialect, sqlContext)
      if (mode == SaveMode.Ignore && tableExists) {
        dialect match {
          case d: JdbcExtendedDialect =>
            d.initializeTable(table,
              sqlContext.conf.caseSensitiveAnalysis, conn)
          case _ => // do nothing
        }
      }
      else if (mode == SaveMode.ErrorIfExists && tableExists) {
        sys.error(s"Table $table already exists.")
      }
    } finally {
      conn.close()
    }
    createExternalTableForColumnBatches(table, externalStore)
  }

  protected def createExternalTableForColumnBatches(tableName: String,
      externalStore: ExternalStore): Unit = {
    require(tableName != null && tableName.length > 0,
      "createExternalTableForColumnBatches: expected non-empty table name")

    val (primarykey, partitionStrategy) = dialect match {
      // The driver if not a loner should be an accesor only
      case d: JdbcExtendedDialect =>
        (s"constraint ${tableName}_partitionCheck check (partitionId != -1), " +
            "primary key (uuid, partitionId)",
            d.getPartitionByClause("partitionId"))
      case _ => ("primary key (uuid)", "")
    }

    createTable(externalStore, s"create table $tableName (uuid varchar(36) " +
        "not null, partitionId integer not null, numRows integer not null, " +
        "stats blob, " + schema.fields.map(structField =>
      externalStore.columnPrefix + structField.name + " blob")
        .mkString(", ") + s", $primarykey) $partitionStrategy",
      tableName, dropIfExists = false) // for test make it false
  }

  def createTable(externalStore: ExternalStore, tableStr: String,
      tableName: String, dropIfExists: Boolean): Unit = {

    externalStore.tryExecute(tableName,
      conn => {
        if (dropIfExists) {
          JdbcExtendedUtils.dropTable(conn, tableName, dialect, sqlContext,
            ifExists = true)
        }
        val tableExists = JdbcExtendedUtils.tableExists(tableName, conn,
          dialect, sqlContext)
        if (!tableExists) {
          logInfo(s"Applying DDL (url=${connProperties.url}; " +
              s"props=${connProperties.connProps}): $tableStr")
          JdbcExtendedUtils.executeUpdate(tableStr, conn)
          dialect match {
            case d: JdbcExtendedDialect => d.initializeTable(tableName,
              sqlContext.conf.caseSensitiveAnalysis, conn)
            case _ => // do nothing
          }
        }
      })
  }

  /**
   * Destroy and cleanup this relation. It may include, but not limited to,
   * dropping the external table that this relation represents.
   */
  override def destroy(ifExists: Boolean): Unit = {
    // drop the external table using a non-pool connection
    val conn = connFactory()
    try {
      // clean up the connection pool and caches
      ExternalStoreUtils.removeCachedObjects(sqlContext, table)
    } finally {
      try {
        JdbcExtendedUtils.dropTable(conn, table, dialect, sqlContext, ifExists)
      } finally {
        conn.close()
      }
    }
  }

  def flushRowBuffer(): Unit = {
    // nothing by default
  }

  override def createIndex(indexIdent: QualifiedTableName,
      tableIdent: QualifiedTableName,
      indexColumns: Map[String, Option[SortDirection]],
      options: Map[String, String]): Unit = {
    throw new UnsupportedOperationException("Indexes are not supported")
  }

  override def dropIndex(indexIdent: QualifiedTableName,
      tableIdent: QualifiedTableName,
      ifExists: Boolean): Unit = {
    throw new UnsupportedOperationException("Indexes are not supported")
  }
}

final class DefaultSource extends ColumnarRelationProvider

class ColumnarRelationProvider extends SchemaRelationProvider
    with CreatableRelationProvider {

  def createRelation(sqlContext: SQLContext, mode: SaveMode,
      options: Map[String, String], schema: StructType): JDBCAppendableRelation = {
    val parameters = new mutable.HashMap[String, String]
    parameters ++= options
    val table = ExternalStoreUtils.removeInternalProps(parameters)
    val sc = sqlContext.sparkContext

    val connectionProperties = ExternalStoreUtils.validateAndGetAllProps(
      Some(sqlContext.sparkSession), parameters)

    val partitions = ExternalStoreUtils.getAndSetTotalPartitions(
      Some(sc), parameters, forManagedTable = false)

    val externalStore = getExternalSource(sqlContext, connectionProperties,
      partitions)
    val tableName = SnappyStoreHiveCatalog
        .processTableIdentifier(table, sqlContext.conf)
    var success = false
    val relation = JDBCAppendableRelation(tableName,
      getClass.getCanonicalName, mode, schema, options,
      externalStore, sqlContext)
    try {
      relation.createTable(mode)
      val catalog = sqlContext.sparkSession.asInstanceOf[SnappySession].sessionCatalog
      catalog.registerDataSourceTable(
        catalog.newQualifiedTableName(tableName), Some(relation.schema),
        Array.empty[String], classOf[execution.columnar.DefaultSource].getCanonicalName,
        options, relation)
      success = true
      relation
    } finally {
      if (!success && !relation.tableExists) {
        // destroy the relation
        relation.destroy(ifExists = true)
      }
    }
  }

  override def createRelation(sqlContext: SQLContext,
      options: Map[String, String], schema: StructType): JDBCAppendableRelation = {

    val allowExisting = options.get(JdbcExtendedUtils
        .ALLOW_EXISTING_PROPERTY).exists(_.toBoolean)
    val mode = if (allowExisting) SaveMode.Ignore else SaveMode.ErrorIfExists

    val rel = getRelation(sqlContext, options)
    rel.createRelation(sqlContext, mode, options, schema)
  }

  override def createRelation(sqlContext: SQLContext, mode: SaveMode,
      options: Map[String, String], data: DataFrame): JDBCAppendableRelation = {
    val rel = getRelation(sqlContext, options)
    val catalog = sqlContext.sparkSession.asInstanceOf[SnappySession].sessionCatalog
    val relation = rel.createRelation(sqlContext, mode, options,
      catalog.normalizeSchema(data.schema))
    var success = false
    try {
      relation.insert(data, mode == SaveMode.Overwrite)
      success = true
      relation
    } finally {
      if (!success && !relation.tableExists) {
        val catalog = sqlContext.sparkSession.asInstanceOf[SnappySession].sessionCatalog
        catalog.unregisterDataSourceTable(catalog.newQualifiedTableName(relation.table),
          Some(relation))
        // destroy the relation
        relation.destroy(ifExists = true)
      }
    }
  }

  def getRelation(sqlContext: SQLContext,
      options: Map[String, String]): ColumnarRelationProvider = {

    val url = options.getOrElse("url",
      ExternalStoreUtils.defaultStoreURL(Some(sqlContext.sparkContext)))
    val clazz = JdbcDialects.get(url) match {
      case _: GemFireXDBaseDialect =>
        DataSource(sqlContext.sparkSession, classOf[impl.DefaultSource]
            .getCanonicalName).providingClass

      case _ => classOf[org.apache.spark.sql.execution.columnar.DefaultSource]
    }
    clazz.newInstance().asInstanceOf[ColumnarRelationProvider]
  }

  def getExternalSource(sqlContext: SQLContext,
      connProperties: ConnectionProperties,
      numPartitions: Int): ExternalStore = {
    new JDBCSourceAsStore(connProperties, numPartitions)
  }
}
