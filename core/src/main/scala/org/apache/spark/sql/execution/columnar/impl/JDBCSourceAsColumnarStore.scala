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
package org.apache.spark.sql.execution.columnar.impl

import java.sql.{Connection, ResultSet, Statement}

import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, KryoSerializable}
import com.gemstone.gemfire.internal.cache.{LocalRegion, PartitionedRegion}
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils
import com.pivotal.gemfirexd.internal.engine.{GfxdConstants, Misc}
import io.snappydata.impl.SparkShellRDDHelper

import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.ConnectionPropertiesSerializer
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.collection._
import org.apache.spark.sql.execution.columnar._
import org.apache.spark.sql.execution.row.{ResultSetTraversal, RowFormatScanRDD, RowInsertExec}
import org.apache.spark.sql.execution.{BufferedRowIterator, RDDKryo, WholeStageCodegenExec}
import org.apache.spark.sql.sources.{ConnectionProperties, Filter, JdbcExtendedUtils}
import org.apache.spark.sql.store.{CodeGeneration, StoreUtils}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{SnappySession, SparkSession}
import org.apache.spark.{Partition, TaskContext}

/**
 * Column Store implementation for GemFireXD.
 */
class JDBCSourceAsColumnarStore(_connProperties: ConnectionProperties,
    _numPartitions: Int, val tableName: String, val schema: StructType)
    extends JDBCSourceAsStore(_connProperties, _numPartitions) {

  self =>

  override def getConnectedExternalStore(table: String,
      onExecutor: Boolean): ConnectedExternalStore =
    new JDBCSourceAsColumnarStore(connProperties, numPartitions, tableName,
        schema) with ConnectedExternalStore {
      protected[this] override val connectedInstance: Connection =
        self.getConnection(table, onExecutor)
    }

  override def getColumnBatchRDD(tableName: String, requiredColumns: Array[String],
      session: SparkSession): RDD[ColumnBatch] = {
    val snappySession = session.asInstanceOf[SnappySession]
    connectionType match {
      case ConnectionType.Embedded =>
        new ColumnarStorePartitionedRDD(snappySession,
          tableName, this).asInstanceOf[RDD[ColumnBatch]]
      case _ =>
        // remove the url property from poolProps since that will be
        // partition-specific
        val poolProps = connProperties.poolProps -
            (if (connProperties.hikariCP) "jdbcUrl" else "url")
        new SparkShellColumnBatchRDD(snappySession,
          tableName, requiredColumns, ConnectionProperties(connProperties.url,
            connProperties.driver, connProperties.dialect, poolProps,
            connProperties.connProps, connProperties.executorConnProps,
            connProperties.hikariCP), this)
    }
  }

  override protected def doInsert(columnTableName: String, batch: ColumnBatch,
      batchId: Option[String], partitionId: Int,
      maxDeltaRows: Int): (Connection => Any) = {
    (connection: Connection) => {
      // split the batch and put into row buffer if it is small
      if (maxDeltaRows > 0 && batch.numRows < math.max(maxDeltaRows / 10,
        GfxdConstants.SNAPPY_MIN_COLUMN_DELTA_ROWS)) {
        // the lookup key depends only on schema and not on the table
        // name since the prepared statement specific to the table is
        // passed in separately through the references object
        val gen = CodeGeneration.compileCode(
          "COLUMN_TABLE.DECOMPRESS", schema.fields, () => {
            val schemaAttrs = schema.toAttributes
            val tableScan = ColumnTableScan(schemaAttrs, dataRDD = null,
              otherRDDs = Seq.empty, numBuckets = -1,
              partitionColumns = Seq.empty, partitionColumnAliases = Seq.empty,
              baseRelation = null, schema, allFilters = Seq.empty, schemaAttrs)
            val insertPlan = RowInsertExec(tableScan, upsert = true,
              Seq.empty, Seq.empty, -1, schema, None, onExecutor = true,
              resolvedName = null, connProperties)
            // now generate the code with the help of WholeStageCodegenExec
            // this is only used for local code generation while its RDD
            // semantics and related methods are all ignored
            val (ctx, code) = ExternalStoreUtils.codeGenOnExecutor(
              WholeStageCodegenExec(insertPlan), insertPlan)
            val references = ctx.references
            // also push the index of connection reference at the end which
            // will be used by caller to update connection before execution
            references += insertPlan.statementRef
            (code, references.toArray)
          })
        val refs = gen._2.clone()
        // set the statement object for current execution
        val statementRef = refs(refs.length - 1).asInstanceOf[Int]
        val resolvedName = ExternalStoreUtils.lookupName(tableName,
          connection.getSchema)
        val putSQL = JdbcExtendedUtils.getInsertOrPutString(resolvedName,
          schema, upsert = true)
        val stmt = connection.prepareStatement(putSQL)
        refs(statementRef) = stmt
        // no harm in passing a references array with extra element at end
        val iter = gen._1.generate(refs).asInstanceOf[BufferedRowIterator]
        // put the single ColumnBatch in the iterator read by generated code
        iter.init(partitionId, Array(Iterator[Any](new ResultSetTraversal(
          conn = null, stmt = null, rs = null, context = null),
          Iterator(batch)).asInstanceOf[Iterator[InternalRow]]))
        // ignore the result which is the update count
        while (iter.hasNext) {
          iter.next()
        }
      } else {
        val resolvedColumnTableName = ExternalStoreUtils.lookupName(
          columnTableName, connection.getSchema)
        connectionType match {
          case ConnectionType.Embedded =>
            val region = Misc.getRegionForTable(resolvedColumnTableName, true)
                .asInstanceOf[PartitionedRegion]
            val batchID = Some(batchId.getOrElse(region.newJavaUUID().toString))
            super.doInsert(resolvedColumnTableName, batch, batchID, partitionId,
              maxDeltaRows)(connection)

          case _ =>
            super.doInsert(resolvedColumnTableName, batch, batchId, partitionId,
              maxDeltaRows)(connection)
        }
      }
    }
  }

  override protected def getPartitionID(tableName: String,
      partitionId: Int): Int = {
    val connection = getConnection(tableName, onExecutor = true)
    try {
      connectionType match {
        case ConnectionType.Embedded =>
          val resolvedName = ExternalStoreUtils.lookupName(tableName,
            connection.getSchema)
          val region = Misc.getRegionForTable(resolvedName, true)
              .asInstanceOf[LocalRegion]
          region match {
            case pr: PartitionedRegion =>
              if (partitionId == -1) {
                val primaryBucketIds = pr.getDataStore.
                    getAllLocalPrimaryBucketIdArray
                // TODO: do load-balancing among partitions instead
                // of random selection
                val numPrimaries = primaryBucketIds.size()
                // if no local primary bucket, then select some other
                if (numPrimaries > 0) {
                  primaryBucketIds.getQuick(rand.nextInt(numPrimaries))
                } else {
                  rand.nextInt(pr.getTotalNumberOfBuckets)
                }
              } else {
                partitionId
              }
            case _ => partitionId
          }
        // TODO: SW: for split mode, get connection to one of the
        // local servers and a bucket ID for only one of those
        case _ => super.getPartitionID(tableName, partitionId)
      }
    } finally {
      connection.close()
    }
  }
}


final class ColumnarStorePartitionedRDD(
    @transient private val session: SnappySession,
    private var tableName: String,
    @transient private val store: JDBCSourceAsColumnarStore)
    extends RDDKryo[Any](session.sparkContext, Nil) with KryoSerializable {

  override def compute(part: Partition, context: TaskContext): Iterator[Any] = {
    val container = GemFireXDUtils.getGemFireContainer(tableName, true)
    val bucketIds = part match {
      case p: MultiBucketExecutorPartition => p.buckets
      case _ => java.util.Collections.singleton(Int.box(part.index))
    }
    new ColumnBatchIterator(container, bucketIds)
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    split.asInstanceOf[MultiBucketExecutorPartition].hostExecutorIds
  }

  override protected def getPartitions: Array[Partition] = {
    store.tryExecute(tableName, conn => {
      val resolvedName = ExternalStoreUtils.lookupName(tableName,
        conn.getSchema)
      val region = Misc.getRegionForTable(resolvedName, true)
      session.sessionState.getTablePartitions(
        region.asInstanceOf[PartitionedRegion])
    })
  }

  override def write(kryo: Kryo, output: Output): Unit = {
    super.write(kryo, output)
    output.writeString(tableName)
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    super.read(kryo, input)
    tableName = input.readString()
  }
}

final class SparkShellColumnBatchRDD(
    @transient private val session: SnappySession,
    private var tableName: String,
    private var requiredColumns: Array[String],
    private var connProperties: ConnectionProperties,
    @transient private val store: ExternalStore)
    extends RDDKryo[ColumnBatch](session.sparkContext, Nil)
        with KryoSerializable {

  override def compute(split: Partition,
      context: TaskContext): Iterator[ColumnBatch] = {
    val helper = new SparkShellRDDHelper
    val conn: Connection = helper.getConnection(connProperties, split)
    val query: String = helper.getSQLStatement(ExternalStoreUtils.lookupName(
      tableName, conn.getSchema), requiredColumns, split.index)
    val (statement, rs) = helper.executeQuery(conn, tableName, split, query)
    new ColumnBatchIteratorOnRS(conn, requiredColumns, statement, rs, context)
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    split.asInstanceOf[ExecutorMultiBucketLocalShellPartition]
        .hostList.map(_._1.asInstanceOf[String])
  }

  override def getPartitions: Array[Partition] = {
    store.tryExecute(tableName, SparkShellRDDHelper.getPartitions(tableName, _))
  }

  override def write(kryo: Kryo, output: Output): Unit = {
    super.write(kryo, output)

    output.writeString(tableName)
    output.writeVarInt(requiredColumns.length, true)
    for (column <- requiredColumns) {
      output.writeString(column)
    }
    ConnectionPropertiesSerializer.write(kryo, output, connProperties)
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    super.read(kryo, input)

    tableName = input.readString()
    val numColumns = input.readVarInt(true)
    requiredColumns = Array.fill(numColumns)(input.readString())
    connProperties = ConnectionPropertiesSerializer.read(kryo, input)
  }
}

class SparkShellRowRDD(_session: SnappySession,
    _tableName: String,
    _isPartitioned: Boolean,
    _columns: Array[String],
    _connProperties: ConnectionProperties,
    _filters: Array[Filter] = Array.empty[Filter],
    _parts: Array[Partition] = Array.empty[Partition])
    extends RowFormatScanRDD(_session, _tableName, _isPartitioned, _columns,
      pushProjections = true, useResultSet = true, _connProperties,
      _filters, _parts) {

  override def computeResultSet(
      thePart: Partition): (Connection, Statement, ResultSet) = {
    val helper = new SparkShellRDDHelper
    val conn: Connection = helper.getConnection(
      connProperties, thePart)
    val resolvedName = StoreUtils.lookupName(tableName, conn.getSchema)

    if (isPartitioned) {
      val ps = conn.prepareStatement(
        "call sys.SET_BUCKETS_FOR_LOCAL_EXECUTION(?, ?)")
      ps.setString(1, resolvedName)
      val partition = thePart.asInstanceOf[ExecutorMultiBucketLocalShellPartition]
      val bucketString = partition.buckets.mkString(",")
      ps.setString(2, bucketString)
      ps.executeUpdate()
      ps.close()
    }
    val sqlText = s"SELECT $columnList FROM $resolvedName$filterWhereClause"

    val args = filterWhereArgs
    val stmt = conn.prepareStatement(sqlText)
    if (args ne null) {
      ExternalStoreUtils.setStatementParameters(stmt, args)
    }
    val fetchSize = connProperties.executorConnProps.getProperty("fetchSize")
    if (fetchSize ne null) {
      stmt.setFetchSize(fetchSize.toInt)
    }

    val rs = stmt.executeQuery()
    (conn, stmt, rs)
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    split.asInstanceOf[ExecutorMultiBucketLocalShellPartition]
        .hostList.map(_._1.asInstanceOf[String])
  }

  override def getPartitions: Array[Partition] = {
    // use incoming partitions if provided (e.g. for collocated tables)
    if (parts != null && parts.length > 0) {
      return parts
    }
    val conn = ExternalStoreUtils.getConnection(tableName, connProperties,
      forExecutor = true)
    try {
      SparkShellRDDHelper.getPartitions(tableName, conn)
    } finally {
      conn.close()
    }
  }

  def getSQLStatement(resolvedTableName: String,
      requiredColumns: Array[String], partitionId: Int): String = {
    "select " + requiredColumns.mkString(", ") + " from " + resolvedTableName
  }
}
