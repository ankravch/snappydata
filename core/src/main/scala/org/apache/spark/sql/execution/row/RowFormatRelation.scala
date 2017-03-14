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
package org.apache.spark.sql.execution.row

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import com.gemstone.gemfire.internal.cache.{LocalRegion, PartitionedRegion}
import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer

import org.apache.spark.Partition
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Ascending, Descending, SortDirection}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.columnar.ExternalStoreUtils.CaseInsensitiveMutableHashMap
import org.apache.spark.sql.execution.columnar.impl.SparkShellRowRDD
import org.apache.spark.sql.execution.columnar.{ConnectionType, ExternalStoreUtils}
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.execution.datasources.jdbc.JDBCPartition
import org.apache.spark.sql.execution.{ConnectionPool, PartitionedDataSourceScan, SparkPlan}
import org.apache.spark.sql.hive.SnappyStoreHiveCatalog
import org.apache.spark.sql.row.JDBCMutableRelation
import org.apache.spark.sql.sources._
import org.apache.spark.sql.store.{CodeGeneration, StoreUtils}

/**
 * A LogicalPlan implementation for an Snappy row table whose contents
 * are retrieved using a JDBC URL or DataSource.
 */
class RowFormatRelation(
    _connProperties: ConnectionProperties,
    _table: String,
    _provider: String,
    preservePartitions: Boolean,
    _mode: SaveMode,
    _userSpecifiedString: String,
    _parts: Array[Partition],
    _origOptions: Map[String, String],
    _context: SQLContext)
    extends JDBCMutableRelation(_connProperties,
      _table,
      _provider,
      _mode,
      _userSpecifiedString,
      _parts,
      _origOptions,
      _context)
    with PartitionedDataSourceScan
    with RowPutRelation with ParentRelation with DependentRelation {

  private val tableOptions = new CaseInsensitiveMutableHashMap(_origOptions)

  override def toString: String = s"RowFormatRelation[$table]"

  override val connectionType: ConnectionType.Value =
    ExternalStoreUtils.getConnectionType(dialect)

  private final lazy val putStr = JdbcExtendedUtils.getInsertOrPutString(
    table, schema, upsert = true)

  private[sql] lazy val resolvedName = ExternalStoreUtils.lookupName(table,
    tableSchema)

  @transient override lazy val region: LocalRegion =
    Misc.getRegionForTable(resolvedName, true).asInstanceOf[LocalRegion]

  private[this] def indexedColumns: mutable.HashSet[String] = {
    val cols = new mutable.HashSet[String]()
    val container = region.getUserAttribute.asInstanceOf[GemFireContainer]
    val td = container.getTableDescriptor
    if (td ne null) {
      val baseColumns = td.getColumnNamesArray
      val im = container.getIndexManager
      if ((im ne null) && (im.getIndexConglomerateDescriptors ne null)) {
        val itr = im.getIndexConglomerateDescriptors.iterator()
        while (itr.hasNext) {
          // first column of index has to be present in filter to be usable
          val indexCols = itr.next().getIndexDescriptor.baseColumnPositions()
          cols += baseColumns(indexCols(0) - 1)
        }
      }
      // also add primary key
      val primaryKey = td.getPrimaryKey
      if (primaryKey ne null) {
        // first column of primary key has to be present in filter to be usable
        val pkCols = primaryKey.getKeyColumns
        if (pkCols.nonEmpty) {
          cols += baseColumns(pkCols(0) - 1)
        }
      }
    }
    cols
  }

  private[this] def pushdownPKColumns(filters: Array[Filter]): Array[String] = {
    def getEqualToColumns(filters: Array[Filter]): ArrayBuffer[String] = {
      val list = new ArrayBuffer[String](4)
      filters.foreach {
        case EqualTo(col, _) => list += col
        case In(col, _) => list += col
        case And(left, right) => list ++= getEqualToColumns(Array(left, right))
        case _ =>
      }
      list
    }

    val container = region.getUserAttribute.asInstanceOf[GemFireContainer]
    val td = container.getTableDescriptor
    if (td ne null) {
      val primaryKey = td.getPrimaryKey
      if (primaryKey ne null) {
        // all columns of primary key have to be present in filter to be usable
        val equalToColumns = getEqualToColumns(filters)
        val cols = primaryKey.getKeyColumns
        val baseColumns = td.getColumnNamesArray
        val pkCols = cols.map(c => baseColumns(c - 1))
        if (pkCols.forall(equalToColumns.contains)) return pkCols
      }
    }
    Array.empty[String]
  }

  override def unhandledFilters(filters: Array[Filter]): Array[Filter] = {
    filters.filter(ExternalStoreUtils.unhandledFilter(_,
      indexedColumns ++ pushdownPKColumns(filters)))
  }

  override def buildUnsafeScan(requiredColumns: Array[String],
      filters: Array[Filter]): (RDD[Any], Seq[RDD[InternalRow]]) = {
    val handledFilters = filters.filter(ExternalStoreUtils
        .handledFilter(_, indexedColumns ++ pushdownPKColumns(filters))
        eq ExternalStoreUtils.SOME_TRUE)
    val isPartitioned = region.getPartitionAttributes != null
    val session = sqlContext.sparkSession.asInstanceOf[SnappySession]
    val rdd = connectionType match {
      case ConnectionType.Embedded =>
        new RowFormatScanRDD(
          session,
          resolvedName,
          isPartitioned,
          requiredColumns,
          pushProjections = false,
          useResultSet = false,
          connProperties,
          handledFilters
        )

      case _ =>
        new SparkShellRowRDD(
          session,
          resolvedName,
          isPartitioned,
          requiredColumns,
          connProperties,
          handledFilters
        )
    }
    (rdd, Nil)
  }

  override lazy val numBuckets: Int = {
    region match {
      case pr: PartitionedRegion => pr.getTotalNumberOfBuckets
      case _ => 1
    }
  }

  override def partitionColumns: Seq[String] = {
    region match {
      case pr: PartitionedRegion =>
        val resolver = pr.getPartitionResolver
            .asInstanceOf[GfxdPartitionByExpressionResolver]
        val parColumn = resolver.getColumnNames
        parColumn.toSeq
      case _ => Seq.empty[String]
    }
  }

  override def getInsertPlan(relation: LogicalRelation,
      child: SparkPlan): SparkPlan = {
    val partitionExpressions = partitionColumns.map(colName =>
      relation.resolveQuoted(colName, sqlContext.sessionState.analyzer.resolver)
          .getOrElse(throw new AnalysisException(
            s"""Cannot resolve column "$colName" among (${relation.output})""")))
    RowInsertExec(child, upsert = false, partitionColumns,
      partitionExpressions, numBuckets, schema, Some(this), onExecutor = false,
      resolvedName, connProperties)
  }

  override def getPutPlan(relation: LogicalRelation,
      child: SparkPlan): SparkPlan = {
    val partitionExpressions = partitionColumns.map(colName =>
      relation.resolveQuoted(colName, sqlContext.sessionState.analyzer.resolver)
          .getOrElse(throw new AnalysisException(
            s"""Cannot resolve column "$colName" among (${relation.output})""")))
    RowInsertExec(child, upsert = true, partitionColumns,
      partitionExpressions, numBuckets, schema, Some(this),
      onExecutor = false, resolvedName, connProperties)
  }

  /**
   * If the row is already present, it gets updated otherwise it gets
   * inserted into the table represented by this relation
   *
   * @param rows the rows to be upserted
   * @return number of rows upserted
   */
  override def put(rows: Seq[Row]): Int = {
    val numRows = rows.length
    if (numRows == 0) {
      throw new IllegalArgumentException(
        "RowFormatRelation.put: no rows provided")
    }
    val connProps = connProperties.connProps
    val batchSize = connProps.getProperty("batchsize", "1000").toInt
    // use bulk insert using put plan for large number of rows
    if (numRows > (batchSize * 4)) {
      JdbcExtendedUtils.bulkInsertOrPut(rows, sqlContext.sparkSession, schema,
        table, upsert = true)
    } else {
      val connection = ConnectionPool.getPoolConnection(table, dialect,
        connProperties.poolProps, connProps, connProperties.hikariCP)
      try {
        val stmt = connection.prepareStatement(putStr)
        val result = CodeGeneration.executeUpdate(table, stmt,
          rows, numRows > 1, batchSize, schema.fields, dialect)
        stmt.close()
        result
      } finally {
        connection.close()
      }
    }
  }

  private def getColumnStr(colWithDirection: (String, Option[SortDirection])): String = {
    colWithDirection._1 + " " + (colWithDirection._2 match {
      case Some(Ascending) => "ASC"
      case Some(Descending) => "DESC"
      case None => ""
    })

  }

  override protected def constructSQL(indexName: String,
      baseTable: String,
      indexColumns: Map[String, Option[SortDirection]],
      options: Map[String, String]): String = {

    val parameters = new CaseInsensitiveMutableHashMap(options)
    val columns = indexColumns.tail.foldLeft[String](
      getColumnStr(indexColumns.head))((cumulative, colsWithDirection) =>
      cumulative + "," + getColumnStr(colsWithDirection))


    val indexType = parameters.get(ExternalStoreUtils.INDEX_TYPE) match {
      case Some(x) => x
      case None => ""
    }
    s"CREATE $indexType INDEX $indexName ON $baseTable ($columns)"
  }

  /** Base table of this relation. */
  override def baseTable: Option[String] = tableOptions.get(StoreUtils.COLOCATE_WITH)

  /** Name of this relation in the catalog. */
  override def name: String = table

  override def addDependent(dependent: DependentRelation,
      catalog: SnappyStoreHiveCatalog): Boolean =
    DependencyCatalog.addDependent(table, dependent.name)

  override def removeDependent(dependent: DependentRelation,
      catalog: SnappyStoreHiveCatalog): Boolean =
    DependencyCatalog.removeDependent(table, dependent.name)


  override def getDependents(
      catalog: SnappyStoreHiveCatalog): Seq[String] =
    DependencyCatalog.getDependents(table)

  /**
   * Recover/Re-create the dependent child relations. This callback
   * is to recreate Dependent relations when the ParentRelation is
   * being created.
   */
  override def recoverDependentRelations(properties: Map[String, String]): Unit = {

    val snappySession = sqlContext.sparkSession.asInstanceOf[SnappySession]
    val sncCatalog = snappySession.sessionState.catalog

    var dependentRelations: Array[String] = Array()
    if (properties.get(ExternalStoreUtils.DEPENDENT_RELATIONS).isDefined) {
      dependentRelations = properties(ExternalStoreUtils.DEPENDENT_RELATIONS).split(",")
    }
    dependentRelations.foreach(rel => {
      val dr = sncCatalog.lookupRelation(sncCatalog.newQualifiedTableName(rel)) match {
        case LogicalRelation(r: DependentRelation, _, _) => r
      }
      addDependent(dr, sncCatalog)
    })

  }
}

final class DefaultSource extends MutableRelationProvider {

  override def createRelation(sqlContext: SQLContext, mode: SaveMode,
      options: Map[String, String], schema: String,
      data: Option[LogicalPlan]): RowFormatRelation = {

    val parameters = new CaseInsensitiveMutableHashMap(options)
    val table = ExternalStoreUtils.removeInternalProps(parameters)
    ExternalStoreUtils.getAndSetTotalPartitions(
      Some(sqlContext.sparkContext), parameters,
      forManagedTable = true, forColumnTable = false)
    val ddlExtension = StoreUtils.ddlExtensionString(parameters,
      isRowTable = true, isShadowTable = false)
    val schemaExtension = s"$schema $ddlExtension"
    val preservePartitions = parameters.remove("preservepartitions")
    // val dependentRelations = parameters.remove(ExternalStoreUtils.DEPENDENT_RELATIONS)
    val connProperties = ExternalStoreUtils.validateAndGetAllProps(
      Some(sqlContext.sparkSession), parameters)

    StoreUtils.validateConnProps(parameters)
    val tableName = SnappyStoreHiveCatalog.processTableIdentifier(table, sqlContext.conf)
    var success = false
    val relation = new RowFormatRelation(connProperties,
      tableName,
      getClass.getCanonicalName,
      preservePartitions.exists(_.toBoolean),
      mode,
      schemaExtension,
      Array[Partition](JDBCPartition(null, 0)),
      options,
      sqlContext)
    try {
      relation.tableSchema = relation.createTable(mode)
      data match {
        case Some(plan) =>
          relation.insert(Dataset.ofRows(sqlContext.sparkSession, plan),
            overwrite = false)
        case None =>
      }

      val catalog = sqlContext.sparkSession.asInstanceOf[SnappySession].sessionCatalog
      catalog.registerDataSourceTable(
        catalog.newQualifiedTableName(tableName), None,
        Array.empty[String], classOf[execution.row.DefaultSource].getCanonicalName,
        options - JdbcExtendedUtils.SCHEMA_PROPERTY, relation)
      success = true
      relation
    } finally {
      if (!success && !relation.tableExists) {
        // destroy the relation
        relation.destroy(ifExists = true)
      }
    }
  }
}
