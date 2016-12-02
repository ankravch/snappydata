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

package io.snappydata.hydra.ct

import java.io.{File, PrintWriter}

import scala.io.Source

import org.apache.spark.sql.{DataFrame, Row, SQLContext, SnappyContext}
import org.apache.spark.sql.catalyst.encoders.RowEncoder

object CTTestUtil {
  var validateFullResultSet: Boolean = false;
  var tableType: String = null

  def getCurrentDirectory = new java.io.File(".").getCanonicalPath

  def assertJoin(sqlString: String, numRows: Int, queryNum: String,snc: SnappyContext,sqlContext: SQLContext, pw: PrintWriter): Boolean = {
    var hasValidationFailed = false
    snc.sql("set spark.sql.crossJoin.enabled = true")
    pw.println(s"\n**Validating Query : ${queryNum}**")
    val df = snc.sql(sqlString)
    if (queryNum == "Q23")
      pw.println(s"No. rows in resultset for join query ${queryNum} is : ${df.show} for ${CTTestUtil.tableType} table")
    else {
      pw.println(s"No. rows in resultset for join query ${queryNum} is : ${df.count} for ${CTTestUtil.tableType} table")
      if (df.count() != numRows) {
        pw.println(s"Result mismatch for join query ${queryNum} : found ${df.count} rows but " +
            s"expected ${numRows} rows. Query is :${sqlString} for ${CTTestUtil.tableType} table.")
        hasValidationFailed = true
        pw.flush()
      }
      if (CTTestUtil.validateFullResultSet)
        assertValidateFullResultSet(sqlString, queryNum, CTTestUtil.tableType,snc,sqlContext,pw)
    }
    return hasValidationFailed
  }

  def assertQuery(sqlString: String, numRows: Int, queryNum: String,snc: SnappyContext, sqlContext: SQLContext, pw: PrintWriter): Boolean = {
    var hasValidationFailed = false
    val df = snc.sql(sqlString)
    pw.println(s"No. rows in resultset for query ${queryNum} is : ${df.count} for ${CTTestUtil.tableType} table")
    if (df.count() != numRows) {
      pw.println(s"Result mismatch for query ${queryNum} : found ${df.count} rows but " +
          s"expected ${numRows} rows. Query is :${sqlString} for ${CTTestUtil.tableType} table.")
      hasValidationFailed = true
      pw.flush()
    }
    if (CTTestUtil.validateFullResultSet)
      hasValidationFailed = assertValidateFullResultSet(sqlString, queryNum, CTTestUtil.tableType,snc,sqlContext,pw)
    return hasValidationFailed
  }

  def assertValidateFullResultSet(sqlString: String, queryNum: String, tableType: String, snc: SnappyContext, sqlContext: SQLContext, pw: PrintWriter): Boolean = {
    var hasValidationFailed = false
    val snappyDF = snc.sql(sqlString)
    val snappyQueryFileName = s"Snappy_${queryNum}.out"
    val sparkQueryFileName = s"Spark_${queryNum}.out"

    val sparkDest: String = getTempDir("sparkQueryFiles") + File.separator + sparkQueryFileName
    val snappyDest = getTempDir("snappyQueryFiles") + File.separator + snappyQueryFileName

    pw.println(sparkDest)
    pw.println(snappyDest)

    val sparkFile: File = new java.io.File(sparkDest)
    val snappyFile: File = new java.io.File(snappyDest)

    val sparkDF = sqlContext.sql(sqlString)
    val col1 = sparkDF.schema.fieldNames(0)
    val col = sparkDF.schema.fieldNames.filter(!_.equals(col1)).toSeq

    if (snappyFile.listFiles() == null) {
      snappyDF.coalesce(1).orderBy(col1, col: _*)
          .map(row => {
            val md = row.toSeq.map {
              case d: Double => "%18.1f".format(d).trim().toDouble
              case de: BigDecimal => de.setScale(2, BigDecimal.RoundingMode.HALF_UP)
              case i: Integer => i
              case v => v
            }
            Row.fromSeq(md)
          })(RowEncoder(snappyDF.schema))
          .write.option("header", false).csv(snappyDest)
      pw.println(s"${queryNum} Result Collected in file $snappyQueryFileName")
    }
    if (sparkFile.listFiles() == null) {
      sparkDF.coalesce(1).orderBy(col1, col: _*)
          .map(row => {
            val md = row.toSeq.map {
              case d: Double => "%18.1f".format(d).trim().toDouble
              case de: BigDecimal => de.setScale(2, BigDecimal.RoundingMode.HALF_UP)
              case i: Integer => i
              case v => v
            }
            Row.fromSeq(md)
          })(RowEncoder(snappyDF.schema))
          .write.option("header", false).csv(sparkDest)
      pw.println(s"${queryNum} Result Collected in file $sparkQueryFileName")
    }
    val expectedFile = sparkFile.listFiles.filter(_.getName.endsWith(".csv"))
    if(!(expectedFile.length > 0)) {
      pw.println(s"File not found for csv")
    }
    val actualFile = snappyFile.listFiles.filter(_.getName.endsWith(".csv"))
    if(!(actualFile.length > 0)) {
      pw.println(s"File not found for csv")
    }
    //expectedFile.diff(actualFile)
    val expectedLineSet = Source.fromFile(expectedFile.iterator.next()).getLines()
    val actualLineSet = Source.fromFile(actualFile.iterator.next()).getLines
    while (expectedLineSet.hasNext && actualLineSet.hasNext) {
      val expectedLine = expectedLineSet.next()
      val actualLine = actualLineSet.next()
      if (!actualLine.equals(expectedLine)) {
        hasValidationFailed = true
        pw.println(s"Expected Result \n: $expectedLine")
        pw.println(s"Actual Result   \n: $actualLine")
      }
    }
    if (actualLineSet.hasNext || expectedLineSet.hasNext) {
      hasValidationFailed = true
      if(actualLineSet.hasNext)
        pw.println("Following rows unexpected in Snappy:")
      while (actualLineSet.hasNext)
        pw.println(actualLineSet.next())
      if(expectedLineSet.hasNext)
      pw.println("Following rows missing in Snappy:")
      while (expectedLineSet.hasNext)
        pw.println(expectedLineSet.next())
    }

    if (hasValidationFailed) {
      pw.println(s"\n For ${queryNum} result mismatch observed")
      pw.println(s"Failed Query =" + sqlString + " Table Type : " + tableType + "\n")
    }
    pw.flush()
    return hasValidationFailed
  }

  def dataTypeConverter(row: Row): Row = {
    val md = row.toSeq.map {
      case d: Double => "%18.1f".format(d).trim().toDouble
      case de: BigDecimal => {
        de.setScale(2, BigDecimal.RoundingMode.HALF_UP)
      }
      case i: Integer => {
        i
      }
      case v => v
    }
    Row.fromSeq(md)
  }

  protected def getTempDir(dirName: String): String = {
    val log: File = new File(".")
    var dest: String = null
    val dirString = log.getCanonicalPath;
    if (dirName.equals("sparkQueryFiles")) {
      val logDir = log.listFiles.filter(_.getName.equals("snappyleader.log"))
      if (!logDir.isEmpty) {
        val leaderLogFile: File = logDir.iterator.next()
        if (leaderLogFile.exists()) dest = dirString + File.separator + ".." + File.separator + ".." + File.separator + dirName
      }
      else dest = dirString + File.separator + ".." + File.separator + dirName
    }
    else dest = log.getCanonicalPath + File.separator + dirName
    val tempDir: File = new File(dest)
    if (!tempDir.exists ) tempDir.mkdir()
    return tempDir.getAbsolutePath
  }

  def assertQuery(snc: SnappyContext, sqlString: String,queryNum: String, tableType: String, pw: PrintWriter):
  Any = {
    pw.println(s"Query execution for $queryNum")
    val df = snc.sql(sqlString)
    pw.println("Number of Rows for  : " + sqlString +" is :" +  df.count())
  }

  def createReplicatedRowTables(snc: SnappyContext): Unit = {
    snc.sql(CTQueries.order_details_create_ddl)
    snc.sql(CTQueries.exec_details_create_ddl)
  }

  def createAndLoadSparkTables(sqlContext: SQLContext): Unit = {
    CTQueries.order_details_data(sqlContext).createOrReplaceTempView("order_details")
    println(s"order_details Table created successfully in spark")
    CTQueries.exec_details_data(sqlContext).createOrReplaceTempView("exec_details")
    println(s"exec_details Table created successfully in spark")
  }

  def createPersistReplicatedRowTables(snc: SnappyContext,persistenceMode: String): Unit = {
    snc.sql(CTQueries.order_details_create_ddl + " persistent")
    snc.sql(CTQueries.exec_details_create_ddl + " persistent")
  }

  def createPartitionedRowTables(snc: SnappyContext,redundancy: String): Unit = {
    snc.sql(CTQueries.order_details_create_ddl + " partition by (SINGLE_ORDER_DID) buckets '11' redundancy '" + redundancy + "'")
    snc.sql(CTQueries.exec_details_create_ddl + " partition by (EXEC_DID) buckets '11' redundancy '" + redundancy + "'")
  }

  def createPersistPartitionedRowTables(snc: SnappyContext,persistenceMode: String,redundancy: String): Unit = {
    snc.sql(CTQueries.order_details_create_ddl + " partition by (SINGLE_ORDER_DID) buckets '11' redundancy '" + redundancy + "' PERSISTENT")
    snc.sql(CTQueries.exec_details_create_ddl + " partition by (EXEC_DID) buckets '11' redundancy '" + redundancy + "' PERSISTENT")
  }

  def createColocatedRowTables(snc: SnappyContext,redundancy: String): Unit = {
    snc.sql(CTQueries.order_details_create_ddl +" partition by (SINGLE_ORDER_DID) redundancy '" + redundancy + "' buckets '11'")
    snc.sql(CTQueries.exec_details_create_ddl + " partition by (EXEC_DID) colocate with (order_details) redundancy '" + redundancy + "' buckets '11'")
  }

  def createPersistColocatedTables(snc: SnappyContext,redundancy: String,persistenceMode: String): Unit = {
    snc.sql(CTQueries.order_details_create_ddl + " partition by (SINGLE_ORDER_DID) redundancy '" + redundancy + "' buckets '11' persistent")
    snc.sql(CTQueries.exec_details_create_ddl + " partition by (EXEC_DID) colocate with (order_details) redundancy '" + redundancy + "' buckets '11' persistent")
  }

  // to add evition attributes
  def createRowTablesWithEviction(snc: SnappyContext,redundancy: String): Unit = {
    snc.sql(CTQueries.order_details_create_ddl + " partition by (SINGLE_ORDER_DID) buckets '11' redundancy '" + redundancy + "'")
    snc.sql(CTQueries.exec_details_create_ddl + " partition by (EXEC_DID) buckets '11' redundancy '" + redundancy + "'")
  }

  //to add eviction attributes
  def createColocatedRowTablesWithEviction(snc: SnappyContext,redundancy: String,persistenceMode: String): Unit = {
    snc.sql(CTQueries.order_details_create_ddl +" partition by (SINGLE_ORDER_DID) redundancy '" + redundancy + "' buckets '11' persistent")
    snc.sql(CTQueries.exec_details_create_ddl + " partition by (EXEC_DID) colocate with (order_details) redundancy '" + redundancy + "' buckets '11'")
  }

  def createColumnTables(snc: SnappyContext,redundancy: String): Unit = {
    snc.sql(CTQueries.order_details_create_ddl + " using column options(redundancy '" + redundancy + "')")
    snc.sql(CTQueries.exec_details_create_ddl + " using column options(redundancy '" + redundancy + "')")
  }

  def createPersistColumnTables(snc: SnappyContext,persistenceMode: String): Unit = {
    snc.sql(CTQueries.order_details_create_ddl + " using column options(PERSISTENT '" + persistenceMode + "')")
    snc.sql(CTQueries.exec_details_create_ddl + " using column options(PERSISTENT '" + persistenceMode + "')")
  }

  def createColocatedColumnTables(snc: SnappyContext,redundancy: String): Unit = {
    snc.sql(CTQueries.order_details_create_ddl + " USING column OPTIONS (partition_by 'SINGLE_ORDER_DID', buckets '11', redundancy '" + redundancy + "')")
    snc.sql(CTQueries.exec_details_create_ddl + " USING column OPTIONS (partition_by 'EXEC_DID', buckets '11', redundancy '" + redundancy + "', COLOCATE_WITH 'ORDER_DETAILS')")
  }

  def createPersistColocatedColumnTables(snc: SnappyContext,redundancy: String,persistenceMode: String): Unit = {
    snc.sql(CTQueries.order_details_create_ddl + " USING column OPTIONS (partition_by 'SINGLE_ORDER_DID', buckets '11', PERSISTENT '" + persistenceMode + "', redundancy '" + redundancy + "') ")
    snc.sql(CTQueries.exec_details_create_ddl + " USING column OPTIONS (partition_by 'EXEC_DID', buckets '11', PERSISTENT '" + persistenceMode + "', redundancy '" + redundancy + "',  COLOCATE_WITH 'ORDER_DETAILS')")
  }

  // to add eviction attributes
  def createColumnTablesWithEviction(snc: SnappyContext,redundancy: String): Unit = {
    snc.sql(CTQueries.order_details_create_ddl + " USING column OPTIONS (partition_by 'SINGLE_ORDER_DID', buckets '11', redundancy '" + redundancy + "')")
    snc.sql(CTQueries.exec_details_create_ddl + " USING column OPTIONS (partition_by 'EXEC_DID', buckets '11', redundancy '" + redundancy + "')")
  }

  //to add eviction attributes
  def createColocatedColumnTablesWithEviction(snc: SnappyContext,redundancy: String): Unit = {
    snc.sql(CTQueries.order_details_create_ddl + " USING column OPTIONS (partition_by 'SINGLE_ORDER_DID', buckets '11', redundancy '" + redundancy + "')")
    snc.sql(CTQueries.exec_details_create_ddl + " USING column OPTIONS (partition_by 'EXEC_DID', buckets '11', redundancy '" + redundancy + "', COLOCATE_WITH 'ORDER_DETAILS')")
  }

  def loadTables(snc: SnappyContext): Unit ={
    CTQueries.order_details_data(snc).write.insertInto("order_details")
    CTQueries.exec_details_data(snc).write.insertInto("exec_details")
  }

  def executeQueries(snc: SnappyContext, tblType: String, pw: PrintWriter,
      fullResultSetValidation: Boolean,sqlContext: SQLContext): Boolean = {
    var hasValidationFailed = false;
    validateFullResultSet = fullResultSetValidation
    tableType = tblType
    if(CTTestUtil.validateFullResultSet)
      CTTestUtil.createAndLoadSparkTables(sqlContext)

    for (q <- CTQueries.queries) {
      q._1 match {
        case "Q1" => assertQuery(CTQueries.query1,1,"Q1",snc,sqlContext,pw)
        case "Q2" => assertQuery(CTQueries.query2,1,"Q2",snc,sqlContext,pw)
        case "Q3" => assertQuery(CTQueries.query3,1,"Q3",snc,sqlContext,pw)
        case "Q4" => assertQuery(CTQueries.query4,1,"Q4",snc,sqlContext,pw)
        case "Q5" => assertQuery(CTQueries.query5,1,"Q5",snc,sqlContext,pw)
        case "Q6" => assertQuery(CTQueries.query6,5,"Q6",snc,sqlContext,pw)
        case "Q7" => assertQuery(CTQueries.query7,5,"Q7",snc,sqlContext,pw)
        case "Q8" => assertQuery(CTQueries.query8,5,"Q8",snc,sqlContext,pw)
        case "Q9" => assertQuery(CTQueries.query9,1,"Q9",snc,sqlContext,pw)
        case "Q10" => assertQuery(CTQueries.query10,1,"Q10",snc,sqlContext,pw)
        case "Q11" => assertQuery(CTQueries.query11,2706,"Q11",snc,sqlContext,pw)
        case "Q12" => assertQuery(CTQueries.query12,150,"Q12",snc,sqlContext,pw)
        case "Q13" => assertQuery(CTQueries.query13,149,"Q13",snc,sqlContext,pw)
        case "Q14" => assertQuery(CTQueries.query14,149,"Q14",snc,sqlContext,pw)
        case "Q15" => assertQuery(CTQueries.query15,2620,"Q15",snc,sqlContext,pw)
        case "Q16" => assertQuery(CTQueries.query16,150,"Q16",snc,sqlContext,pw)
        case "Q17" => assertQuery(CTQueries.query17,2,"Q17",snc,sqlContext,pw)
        case "Q18" => assertQuery(CTQueries.query18,0,"Q18",snc,sqlContext,pw)
        case "Q19" => assertQuery(CTQueries.query19,47,"Q19",snc,sqlContext,pw)
        case "Q20" => assertQuery(CTQueries.query20,100,"Q20",snc,sqlContext,pw)
        case "Q21" => assertQuery(CTQueries.query21,2,"Q21",snc,sqlContext,pw)
        case "Q22" => assertJoin(CTQueries.query22,1,"Q22",snc,sqlContext,pw)
        //case "Q23" => assertJoin(CTQueries.query23,0,"Q23",snc,sqlContext,pw)
        case "Q24" => assertQuery(CTQueries.query24,999,"Q24",snc,sqlContext,pw)
        case _ => pw.println(s"Query not be executed ${q._1}")
      }
    }
    return hasValidationFailed;
  }

  def dropTables(snc: SnappyContext): Unit = {
    snc.sql("drop table if exists exec_details")
    snc.sql("drop table if exists order_details")
  }

}
