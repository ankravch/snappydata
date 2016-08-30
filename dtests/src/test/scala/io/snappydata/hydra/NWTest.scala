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
package io.snappydata.hydra

import org.apache.spark.sql.SnappyContext
import org.apache.spark.{SparkConf, SparkContext}


object NWTest {
  val conf = new SparkConf().
    setAppName("NW Application")
  val sc = new SparkContext(conf)
  val snc = SnappyContext(sc)

  def main(args: Array[String]) {
    snc.sql("set spark.sql.shuffle.partitions=6")
    dropTables(snc)
    println("Test replicated row tables queries started")
    createAndLoadReplicatedTables(snc)
    validateReplicatedTableQueries(snc)
    println("Test replicated row tables queries completed successfully")
    println("Test partitioned row tables queries started")
    createAndLoadPartitionedTables(snc)
    validatePartitionedRowTableQueries(snc)
    println("Test partitioned row tables queries completed successfully")
    println("Test column tables queries started")
    createAndLoadColumnTables(snc)
    validatePartitionedColumnTableQueries(snc)
    println("Test column tables queries completed successfully")
    createAndLoadColocatedTables(snc)
    validateColocatedTableQueries(snc)
  }

  private def assertJoin(snc: SnappyContext, sqlString: String, numRows: Int, queryNum: String): Any = {
    snc.sql("set spark.sql.crossJoin.enabled = true")
    val df = snc.sql(sqlString)
    println(s"Query ${queryNum} \n df.count for join query is : ${df.count} \nExpected numRows : ${numRows} \n df.explain() : ${df.explain()}")
    assert(df.count() == numRows,
      s"Mismatch got for query ${queryNum} : df.count ->" + df.count() + " but expected numRows ->" + numRows
        + " for query =" + sqlString + " df.explain() =" + df.explain())
  }

  private def assertQuery(snc: SnappyContext, sqlString: String, numRows: Int, queryNum: String): Any = {
    val df = snc.sql(sqlString)
    println(s"Query ${queryNum} \n df.count is : ${df.count} \nExpected numRows : ${numRows} \n df.explain() : ${df.explain()}")
    assert(df.count() == numRows,
      s"Mismatch got for query ${queryNum} : df.count ->" + df.count() + " but expected numRows ->" + numRows
        + " for query =" + sqlString + " df.explain() =" + df.explain())
  }

  private def createAndLoadReplicatedTables(snc: SnappyContext): Unit = {
    snc.sql(NWQueries.regions_table)
    NWQueries.regions.write.insertInto("regions")

    snc.sql(NWQueries.categories_table)
    NWQueries.categories.write.insertInto("categories")

    snc.sql(NWQueries.shippers_table)
    NWQueries.shippers.write.insertInto("shippers")

    snc.sql(NWQueries.employees_table)
    NWQueries.employees.write.insertInto("employees")

    snc.sql(NWQueries.customers_table)
    NWQueries.customers.write.insertInto("customers")

    snc.sql(NWQueries.orders_table)
    NWQueries.orders.write.insertInto("orders")

    snc.sql(NWQueries.order_details_table)
    NWQueries.order_details.write.insertInto("order_details")

    snc.sql(NWQueries.products_table)
    NWQueries.products.write.insertInto("products")

    snc.sql(NWQueries.suppliers_table)
    NWQueries.suppliers.write.insertInto("suppliers")

    snc.sql(NWQueries.territories_table)
    NWQueries.territories.write.insertInto("territories")

    snc.sql(NWQueries.employee_territories_table)
    NWQueries.employee_territories.write.insertInto("employee_territories")
  }

  private def validateReplicatedTableQueries(snc: SnappyContext): Unit = {
    for (q <- NWQueries.queries) {
      q._1 match {
        case "Q1" => assertQuery(snc, NWQueries.Q1, 8, "Q1")
        case "Q2" => assertQuery(snc, NWQueries.Q2, 91, "Q2")
        case "Q3" => assertQuery(snc, NWQueries.Q3, 830, "Q3")
        case "Q4" => assertQuery(snc, NWQueries.Q4, 8, "Q4")
        case "Q5" => assertQuery(snc, NWQueries.Q5, 8, "Q5")
        case "Q6" => assertQuery(snc, NWQueries.Q6, 8, "Q6")
        case "Q7" => assertQuery(snc, NWQueries.Q7, 8, "Q7")
        case "Q8" => assertQuery(snc, NWQueries.Q8, 5, "Q8")
        case "Q9" => assertQuery(snc, NWQueries.Q9, 3, "Q9")
        case "Q10" => assertQuery(snc, NWQueries.Q10, 2, "Q10")
        case "Q11" => assertQuery(snc, NWQueries.Q11, 0, "Q11")
        case "Q12" => assertQuery(snc, NWQueries.Q12, 2, "Q12")
        case "Q13" => assertQuery(snc, NWQueries.Q13, 0, "Q13")
        case "Q14" => assertQuery(snc, NWQueries.Q14, 91, "Q14")
        case "Q15" => assertQuery(snc, NWQueries.Q15, 5, "Q15")
        case "Q16" => assertQuery(snc, NWQueries.Q16, 7, "Q16")
        case "Q17" => assertQuery(snc, NWQueries.Q17, 3, "Q17")
        case "Q18" => assertQuery(snc, NWQueries.Q18, 8, "Q18")
        case "Q19" => assertQuery(snc, NWQueries.Q19, 13, "Q19")
        case "Q20" => assertQuery(snc, NWQueries.Q20, 1, "Q20")
        case "Q21" => assertQuery(snc, NWQueries.Q21, 1, "Q21")
        case "Q22" => assertQuery(snc, NWQueries.Q22, 1, "Q22")
        case "Q23" => assertQuery(snc, NWQueries.Q23, 1, "Q23")
        case "Q24" => assertQuery(snc, NWQueries.Q24, 4, "Q24")
        case "Q25" => assertJoin(snc, NWQueries.Q25, 1, "Q25")
        case "Q26" => assertJoin(snc, NWQueries.Q26, 89, "Q26")
        case "Q27" => assertJoin(snc, NWQueries.Q27, 9, "Q27")
        case "Q28" => assertJoin(snc, NWQueries.Q28, 12, "Q28")
        case "Q29" => assertJoin(snc, NWQueries.Q29, 8, "Q29")
        case "Q30" => assertJoin(snc, NWQueries.Q30, 8, "Q30")
        case "Q31" => assertJoin(snc, NWQueries.Q31, 758, "Q31")
        case "Q32" => assertJoin(snc, NWQueries.Q32, 51, "Q32")
        case "Q33" => //assertJoin(snc, NWQueries.Q33, 51, "Q33")
        case "Q34" => assertJoin(snc, NWQueries.Q34, 5, "Q34")
        case "Q35" => assertJoin(snc, NWQueries.Q35, 3, "Q35")
        case "Q36" => assertJoin(snc, NWQueries.Q36, 5, "Q36")
        case "Q37" => assertJoin(snc, NWQueries.Q37, 69, "Q37")
        case "Q38" => assertJoin(snc, NWQueries.Q38, 71, "Q38")
        case "Q39" => assertJoin(snc, NWQueries.Q39, 9, "Q39")
        case "Q40" => assertJoin(snc, NWQueries.Q40, 830, "Q40")
        case "Q41" => assertJoin(snc, NWQueries.Q41, 2155, "Q41")
        case "Q42" => assertJoin(snc, NWQueries.Q42, 22, "Q42")
        case "Q43" => assertJoin(snc, NWQueries.Q43, 830, "Q43")
        case "Q44" => assertJoin(snc, NWQueries.Q44, 830, "Q44") //LeftSemiJoinHash
        case "Q45" => assertJoin(snc, NWQueries.Q45, 1788650, "Q45")
        case "Q46" => assertJoin(snc, NWQueries.Q46, 1788650, "Q46")
        case "Q47" => assertJoin(snc, NWQueries.Q47, 1788650, "Q47")
        case "Q48" => assertJoin(snc, NWQueries.Q48, 1788650, "Q48")
        case "Q49" => assertJoin(snc, NWQueries.Q49, 1788650, "Q49")
        case "Q50" => assertJoin(snc, NWQueries.Q50, 2155, "Q50")
        case "Q51" => assertJoin(snc, NWQueries.Q51, 2155, "Q51")
        case "Q52" => assertJoin(snc, NWQueries.Q52, 2155, "Q52")
        case "Q53" => assertJoin(snc, NWQueries.Q53, 2155, "Q53")
        case "Q54" => assertJoin(snc, NWQueries.Q54, 2155, "Q54")
      }
    }
  }

  private def createAndLoadPartitionedTables(snc: SnappyContext): Unit = {

    snc.sql(NWQueries.regions_table)
    NWQueries.regions.write.insertInto("regions")

    snc.sql(NWQueries.categories_table)
    NWQueries.categories.write.insertInto("categories")

    snc.sql(NWQueries.shippers_table)
    NWQueries.shippers.write.insertInto("shippers")

    snc.sql(NWQueries.employees_table)
    NWQueries.employees.write.insertInto("employees")

    snc.sql(NWQueries.customers_table)
    NWQueries.customers.write.insertInto("customers")

    snc.sql(NWQueries.orders_table + " using row options (partition_by 'OrderId', buckets '13')")
    NWQueries.orders.write.insertInto("orders")

    snc.sql(NWQueries.order_details_table +
      " using row options (partition_by 'OrderId', buckets '13', COLOCATE_WITH 'orders')")
    NWQueries.order_details.write.insertInto("order_details")

    snc.sql(NWQueries.products_table +
      " using row options ( partition_by 'ProductID', buckets '17')")
    NWQueries.products.write.insertInto("products")

    snc.sql(NWQueries.suppliers_table +
      " USING row options (PARTITION_BY 'SupplierID', buckets '123' )")
    NWQueries.suppliers.write.insertInto("suppliers")

    snc.sql(NWQueries.territories_table +
      " using row options (partition_by 'TerritoryID', buckets '3')")
    NWQueries.territories.write.insertInto("territories")

    snc.sql(NWQueries.employee_territories_table +
      " using row options(partition_by 'EmployeeID', buckets '1')")
    NWQueries.employee_territories.write.insertInto("employee_territories")

  }

  private def validatePartitionedRowTableQueries(snc: SnappyContext): Unit = {
    for (q <- NWQueries.queries) {
      q._1 match {
        case "Q1" => assertQuery(snc, NWQueries.Q1, 8, "Q1")
        case "Q2" => assertQuery(snc, NWQueries.Q2, 91, "Q2")
        case "Q3" => assertQuery(snc, NWQueries.Q3, 830, "Q3")
        case "Q4" => assertQuery(snc, NWQueries.Q4, 8, "Q4")
        case "Q5" => assertQuery(snc, NWQueries.Q5, 8, "Q5")
        case "Q6" => assertQuery(snc, NWQueries.Q6, 8, "Q6")
        case "Q7" => assertQuery(snc, NWQueries.Q7, 8, "Q7")
        case "Q8" => assertQuery(snc, NWQueries.Q8, 5, "Q8")
        case "Q9" => assertQuery(snc, NWQueries.Q9, 3, "Q9")
        case "Q10" => assertQuery(snc, NWQueries.Q10, 2, "Q10")
        case "Q11" => assertQuery(snc, NWQueries.Q11, 0, "Q11")
        case "Q12" => assertQuery(snc, NWQueries.Q12, 2, "Q12")
        case "Q13" => assertQuery(snc, NWQueries.Q13, 0, "Q13")
        case "Q14" => assertQuery(snc, NWQueries.Q14, 91, "Q14")
        case "Q15" => assertQuery(snc, NWQueries.Q15, 5, "Q15")
        case "Q16" => assertQuery(snc, NWQueries.Q16, 7, "Q16")
        case "Q17" => assertQuery(snc, NWQueries.Q17, 3, "Q17")
        case "Q18" => assertQuery(snc, NWQueries.Q18, 8, "Q18")
        case "Q19" => assertQuery(snc, NWQueries.Q19, 13, "Q19")
        case "Q20" => assertQuery(snc, NWQueries.Q20, 1, "Q20")
        case "Q21" => assertQuery(snc, NWQueries.Q21, 1, "Q21")
        case "Q22" => assertQuery(snc, NWQueries.Q22, 1, "Q22")
        case "Q23" => assertQuery(snc, NWQueries.Q23, 1, "Q23")
        case "Q24" => assertQuery(snc, NWQueries.Q24, 4, "Q24")
        case "Q25" => assertJoin(snc, NWQueries.Q25, 1, "Q25") //BroadcastLeftHashJOin
        case "Q26" => assertJoin(snc, NWQueries.Q26, 89, "Q26") //BroadcastLeftSemiJoinHash
        case "Q27" => assertJoin(snc, NWQueries.Q27, 9, "Q27") //BroadcastLeftSemiJoinHash
        case "Q28" => assertJoin(snc, NWQueries.Q28, 12, "Q28")
        case "Q29" => assertJoin(snc, NWQueries.Q29, 8, "Q29") //BroadcastLeftSemiJoinHash
        case "Q30" => assertJoin(snc, NWQueries.Q30, 8, "Q30")
        case "Q31" => assertJoin(snc, NWQueries.Q31, 758, "Q31")
        case "Q32" => assertJoin(snc, NWQueries.Q32, 51, "Q32")
        case "Q33" => //assertJoin(snc, NWQueries.Q33, 51, "Q33")
        case "Q34" => assertJoin(snc, NWQueries.Q34, 5, "Q34") //BroadcastHashJoin
        case "Q35" => assertJoin(snc, NWQueries.Q35, 3, "Q35")
        case "Q36" => assertJoin(snc, NWQueries.Q36, 5, "Q36")
        case "Q37" => assertJoin(snc, NWQueries.Q37, 69, "Q37")
        case "Q38" => assertJoin(snc, NWQueries.Q38, 71, "Q38")
        case "Q39" => assertJoin(snc, NWQueries.Q39, 9, "Q39")
        case "Q40" => assertJoin(snc, NWQueries.Q40, 830, "Q40")
        case "Q41" => assertJoin(snc, NWQueries.Q41, 2155, "Q41")
        case "Q42" => assertJoin(snc, NWQueries.Q42, 22, "Q42")
        case "Q43" => assertJoin(snc, NWQueries.Q43, 830, "Q43")
        case "Q44" => assertJoin(snc, NWQueries.Q44, 830, "Q44") //LeftSemiJoinHash
        case "Q45" => assertJoin(snc, NWQueries.Q45, 1788650, "Q45") //BroadcastLeftSemiJoinHash
        case "Q46" => assertJoin(snc, NWQueries.Q46, 1788650, "Q46") //BroadcastLeftSemiJoinHash
        case "Q47" => assertJoin(snc, NWQueries.Q47, 1788650, "Q47") //BroadcastLeftSemiJoinHash
        case "Q48" => assertJoin(snc, NWQueries.Q48, 1788650, "Q48") //BroadcastLeftSemiJoinHash
        case "Q49" => assertJoin(snc, NWQueries.Q49, 1788650, "Q49") //BroadcastLeftSemiJoinHash
        case "Q50" => assertJoin(snc, NWQueries.Q50, 2155, "Q50") //BroadcastHashJoin
        case "Q51" => assertJoin(snc, NWQueries.Q51, 2155, "Q51") //BroadcastHashOuterJoin
        case "Q52" => assertJoin(snc, NWQueries.Q52, 2155, "Q52") //BroadcastHashOuterJoin
        case "Q53" => assertJoin(snc, NWQueries.Q53, 2155, "Q53")
        case "Q54" => assertJoin(snc, NWQueries.Q54, 2155, "Q54")
      }
    }
  }

  private def createAndLoadColumnTables(snc: SnappyContext): Unit = {

    snc.sql(NWQueries.regions_table)
    NWQueries.regions.write.insertInto("regions")

    snc.sql(NWQueries.categories_table)
    NWQueries.categories.write.insertInto("categories")

    snc.sql(NWQueries.shippers_table)
    NWQueries.shippers.write.insertInto("shippers")

    snc.sql(NWQueries.employees_table + " using column options()")
    NWQueries.employees.write.insertInto("employees")

    snc.sql(NWQueries.customers_table)
    NWQueries.customers.write.insertInto("customers")

    snc.sql(NWQueries.orders_table + " using column options (partition_by 'OrderId', buckets '13')")
    NWQueries.orders.write.insertInto("orders")

    snc.sql(NWQueries.order_details_table +
      " using column options (partition_by 'OrderId', buckets '13', COLOCATE_WITH 'orders')")
    NWQueries.order_details.write.insertInto("order_details")

    snc.sql(NWQueries.products_table +
      " USING column options ( partition_by 'ProductID,SupplierID', buckets '17')")
    NWQueries.products.write.insertInto("products")

    snc.sql(NWQueries.suppliers_table +
      " USING column options (PARTITION_BY 'SupplierID', buckets '123' )")
    NWQueries.suppliers.write.insertInto("suppliers")

    snc.sql(NWQueries.territories_table +
      " using column options (partition_by 'TerritoryID', buckets '3')")
    NWQueries.territories.write.insertInto("territories")

    snc.sql(NWQueries.employee_territories_table +
      " using row options(partition_by 'EmployeeID', buckets '1')")
    NWQueries.employee_territories.write.insertInto("employee_territories")
  }

  private def validatePartitionedColumnTableQueries(snc: SnappyContext): Unit = {

    for (q <- NWQueries.queries) {
      q._1 match {
        case "Q1" => assertQuery(snc, NWQueries.Q1, 8, "Q1")
        case "Q2" => assertQuery(snc, NWQueries.Q2, 91, "Q2")
        case "Q3" => assertQuery(snc, NWQueries.Q3, 830, "Q3")
        case "Q4" => assertQuery(snc, NWQueries.Q4, 8, "Q4")
        case "Q5" => assertQuery(snc, NWQueries.Q5, 8, "Q5")
        case "Q6" => assertQuery(snc, NWQueries.Q6, 8, "Q6")
        case "Q7" => assertQuery(snc, NWQueries.Q7, 8, "Q7")
        case "Q8" => assertQuery(snc, NWQueries.Q8, 5, "Q8")
        case "Q9" => assertQuery(snc, NWQueries.Q9, 3, "Q9")
        case "Q10" => assertQuery(snc, NWQueries.Q10, 2, "Q10")
        case "Q11" => assertQuery(snc, NWQueries.Q11, 0, "Q11")
        case "Q12" => assertQuery(snc, NWQueries.Q12, 2, "Q12")
        case "Q13" => assertQuery(snc, NWQueries.Q13, 0, "Q13")
        case "Q14" => assertQuery(snc, NWQueries.Q14, 91, "Q14")
        case "Q15" => assertQuery(snc, NWQueries.Q15, 5, "Q15")
        case "Q16" => assertQuery(snc, NWQueries.Q16, 7, "Q16")
        case "Q17" => assertQuery(snc, NWQueries.Q17, 3, "Q17")
        case "Q18" => assertQuery(snc, NWQueries.Q18, 8, "Q18")
        case "Q19" => assertQuery(snc, NWQueries.Q19, 13, "Q19")
        case "Q20" => assertQuery(snc, NWQueries.Q20, 1, "Q20")
        case "Q21" => assertQuery(snc, NWQueries.Q21, 1, "Q21")
        case "Q22" => assertQuery(snc, NWQueries.Q22, 1, "Q22")
        case "Q23" => assertQuery(snc, NWQueries.Q23, 1, "Q23")
        case "Q24" => assertQuery(snc, NWQueries.Q24, 4, "Q24")
        case "Q25" => assertJoin(snc, NWQueries.Q25, 1, "Q25") //BroadcastLeftHashJoin
        case "Q26" => assertJoin(snc, NWQueries.Q26, 89, "Q26") //BroadcastLeftSemiJoinHash
        case "Q27" => assertJoin(snc, NWQueries.Q27, 9, "Q27") //BroadcastLeftSemiJoinHash
        case "Q28" => assertJoin(snc, NWQueries.Q28, 12, "Q28")
        case "Q29" => assertJoin(snc, NWQueries.Q29, 8, "Q29") //BroadcastLeftSemiJoinHash
        case "Q30" => assertJoin(snc, NWQueries.Q30, 8, "Q30")
        case "Q31" => assertJoin(snc, NWQueries.Q31, 758, "Q31") //BroadcastHashJoin
        case "Q32" => assertJoin(snc, NWQueries.Q32, 51, "Q32")
        case "Q33" => //assertJoin(snc, NWQueries.Q33, 51, "Q33")
        case "Q34" => assertJoin(snc, NWQueries.Q34, 5, "Q34") //BroadcastHashJoin
        case "Q35" => assertJoin(snc, NWQueries.Q35, 3, "Q35")
        case "Q36" => assertJoin(snc, NWQueries.Q36, 5, "Q36")
        case "Q37" => assertJoin(snc, NWQueries.Q37, 69, "Q37") //SortMergeOuterJoin
        case "Q38" => assertJoin(snc, NWQueries.Q38, 71, "Q38")
        case "Q39" => assertJoin(snc, NWQueries.Q39, 9, "Q39")
        case "Q40" => assertJoin(snc, NWQueries.Q40, 830, "Q40")
        case "Q41" => assertJoin(snc, NWQueries.Q41, 2155, "Q41") //SortMergeJoin
        case "Q42" => assertJoin(snc, NWQueries.Q42, 22, "Q42") // BroadcastHashJoin
        case "Q43" => assertJoin(snc, NWQueries.Q43, 830, "Q43")
        case "Q44" => assertJoin(snc, NWQueries.Q44, 830, "Q44")
        case "Q45" => assertJoin(snc, NWQueries.Q45, 1788650, "Q45") //BroadcastLeftSemiJoinHash
        case "Q46" => assertJoin(snc, NWQueries.Q46, 1788650, "Q46") //BroadcastLeftSemiJoinHash
        case "Q47" => assertJoin(snc, NWQueries.Q47, 1788650, "Q47") // //BroadcastLeftSemiJoinHash
        case "Q48" => assertJoin(snc, NWQueries.Q48, 1788650, "Q48") //BroadcastLeftSemiJoinHash //BroadcastNestedLoopJoin
        case "Q49" => assertJoin(snc, NWQueries.Q49, 1788650, "Q49") //BroadcastLeftSemiJoinHash //BroadcastNestedLoopJoin
        case "Q50" => assertJoin(snc, NWQueries.Q50, 2155, "Q50") //BroadcastHashJoin
        case "Q51" => assertJoin(snc, NWQueries.Q51, 2155, "Q51") //BroadcastHashOuterJoin
        case "Q52" => assertJoin(snc, NWQueries.Q52, 2155, "Q52") //BroadcastHashOuterJoin
        case "Q53" => assertJoin(snc, NWQueries.Q53, 2155, "Q53")
        case "Q54" => assertJoin(snc, NWQueries.Q54, 2155, "Q54")
      }
    }
  }

  private def createAndLoadColocatedTables(snc: SnappyContext): Unit = {

    snc.sql(NWQueries.regions_table)
    NWQueries.regions.write.insertInto("regions")

    snc.sql(NWQueries.categories_table)
    NWQueries.categories.write.insertInto("categories")

    snc.sql(NWQueries.shippers_table)
    NWQueries.shippers.write.insertInto("shippers")

    snc.sql(NWQueries.employees_table +
      " using row options( partition_by 'EmployeeID', buckets '3')")
    NWQueries.employees.write.insertInto("employees")

    snc.sql(NWQueries.customers_table +
      " using column options( partition_by 'CustomerID', buckets '19')")
    NWQueries.customers.write.insertInto("customers")

    snc.sql(NWQueries.orders_table +
      " using row options (partition_by 'CustomerID', buckets '19', colocate_with 'customers')")
    NWQueries.orders.write.insertInto("orders")

    snc.sql(NWQueries.order_details_table +
      " using row options ( partition_by 'ProductID', buckets '329')")
    NWQueries.order_details.write.insertInto("order_details")

    snc.sql(NWQueries.products_table +
      " USING column options ( partition_by 'ProductID', buckets '329'," +
      " colocate_with 'order_details')")
    NWQueries.products.write.insertInto("products")

    snc.sql(NWQueries.suppliers_table +
      " USING column options (PARTITION_BY 'SupplierID', buckets '123')")
    NWQueries.suppliers.write.insertInto("suppliers")

    snc.sql(NWQueries.territories_table +
      " using column options (partition_by 'TerritoryID', buckets '3')")
    NWQueries.territories.write.insertInto("territories")

    snc.sql(NWQueries.employee_territories_table +
      " using row options(partition_by 'TerritoryID', buckets '3', colocate_with 'territories') ")
    NWQueries.employee_territories.write.insertInto("employee_territories")

  }


  private def validateColocatedTableQueries(snc: SnappyContext): Unit = {

    for (q <- NWQueries.queries) {
      q._1 match {
        case "Q1" => assertQuery(snc, NWQueries.Q1, 8, "Q1")
        case "Q2" => assertQuery(snc, NWQueries.Q2, 91, "Q2")
        case "Q3" => assertQuery(snc, NWQueries.Q3, 830, "Q3")
        case "Q4" => assertQuery(snc, NWQueries.Q4, 8, "Q4")
        case "Q5" => assertQuery(snc, NWQueries.Q5, 8, "Q5")
        case "Q6" => assertQuery(snc, NWQueries.Q6, 8, "Q6")
        case "Q7" => assertQuery(snc, NWQueries.Q7, 8, "Q7")
        case "Q8" => assertQuery(snc, NWQueries.Q8, 5, "Q8")
        case "Q9" => assertQuery(snc, NWQueries.Q9, 3, "Q9")
        case "Q10" => assertQuery(snc, NWQueries.Q10, 2, "Q10")
        case "Q11" => assertQuery(snc, NWQueries.Q11, 0, "Q11")
        case "Q12" => assertQuery(snc, NWQueries.Q12, 2, "Q12")
        case "Q13" => assertQuery(snc, NWQueries.Q13, 0, "Q13")
        case "Q14" => assertQuery(snc, NWQueries.Q14, 91, "Q14")
        case "Q15" => assertQuery(snc, NWQueries.Q15, 5, "Q15")
        case "Q16" => assertQuery(snc, NWQueries.Q16, 7, "Q16")
        case "Q17" => assertQuery(snc, NWQueries.Q17, 3, "Q17")
        case "Q18" => assertQuery(snc, NWQueries.Q18, 8, "Q18")
        case "Q19" => assertQuery(snc, NWQueries.Q19, 13, "Q19")
        case "Q20" => assertQuery(snc, NWQueries.Q20, 1, "Q20")
        case "Q21" => assertQuery(snc, NWQueries.Q21, 1, "Q21")
        case "Q22" => assertQuery(snc, NWQueries.Q22, 1, "Q22")
        case "Q23" => assertQuery(snc, NWQueries.Q23, 1, "Q23")
        case "Q24" => assertQuery(snc, NWQueries.Q24, 4, "Q24")
        case "Q25" => assertJoin(snc, NWQueries.Q25, 1, "Q25") //BroadcastLeftHashJOin
        case "Q26" => assertJoin(snc, NWQueries.Q26, 89, "Q26") //BroadcastLeftSemiJoinHash
        case "Q27" => assertJoin(snc, NWQueries.Q27, 9, "Q27") //BroadcastLeftSemiJoinHash
        case "Q28" => assertJoin(snc, NWQueries.Q28, 12, "Q28")
        case "Q29" => assertJoin(snc, NWQueries.Q29, 8, "Q29") //BroadcastLeftSemiJoinHash
        case "Q30" => assertJoin(snc, NWQueries.Q30, 8, "Q30")
        case "Q31" => assertJoin(snc, NWQueries.Q31, 758, "Q31")
        case "Q32" => assertJoin(snc, NWQueries.Q32, 51, "Q32")
        case "Q33" => //assertJoin(snc, NWQueries.Q33, 51, "Q33")
        case "Q34" => assertJoin(snc, NWQueries.Q34, 5, "Q34") //BroadcastHashJoin
        case "Q35" => assertJoin(snc, NWQueries.Q35, 3, "Q35") //SortMergeJoin
        case "Q36" => assertJoin(snc, NWQueries.Q36, 5, "Q36") //BroadcastHashOuterJoin
        case "Q37" => assertJoin(snc, NWQueries.Q37, 69, "Q37") //SortMergeOuterJoin
        case "Q38" => assertJoin(snc, NWQueries.Q38, 71, "Q38")
        case "Q39" => assertJoin(snc, NWQueries.Q39, 9, "Q39")
        case "Q40" => assertJoin(snc, NWQueries.Q40, 830, "Q40")
        case "Q41" => assertJoin(snc, NWQueries.Q41, 2155, "Q41")
        case "Q42" => assertJoin(snc, NWQueries.Q42, 22, "Q42")
        case "Q43" => assertJoin(snc, NWQueries.Q43, 830, "Q43")
        case "Q44" => assertJoin(snc, NWQueries.Q44, 830, "Q44")
        case "Q45" => assertJoin(snc, NWQueries.Q45, 1788650, "Q45")
        case "Q46" => assertJoin(snc, NWQueries.Q46, 1788650, "Q46")
        case "Q47" => assertJoin(snc, NWQueries.Q47, 1788650, "Q47")
        case "Q48" => assertJoin(snc, NWQueries.Q48, 1788650, "Q48")
        case "Q49" => assertJoin(snc, NWQueries.Q49, 1788650, "Q49")
        case "Q50" => assertJoin(snc, NWQueries.Q50, 2155, "Q50") //BroadcastHashJoin
        case "Q51" => assertJoin(snc, NWQueries.Q51, 2155, "Q51") //BroadcastHashOuterJoin
        case "Q52" => assertJoin(snc, NWQueries.Q52, 2155, "Q52") //BroadcastHashOuterJoin
        case "Q53" => assertJoin(snc, NWQueries.Q53, 2155, "Q53")
        case "Q54" => assertJoin(snc, NWQueries.Q54, 2155, "Q51")
      }
    }
  }

  private def dropTables(snc: SnappyContext): Unit = {
    snc.sql("drop table if exists regions")
    println("regions table dropped successfully.");
    snc.sql("drop table if exists categories")
    println("categories table dropped successfully.");
    snc.sql("drop table if exists products")
    println("products table dropped successfully.");
    snc.sql("drop table if exists order_details")
    println("order_details table dropped successfully.");
    snc.sql("drop table if exists orders")
    println("orders table dropped successfully.");
    snc.sql("drop table if exists customers")
    println("customers table dropped successfully.");
    snc.sql("drop table if exists employees")
    println("employees table dropped successfully.");
    snc.sql("drop table if exists employee_territories")
    println("employee_territories table dropped successfully.");
    snc.sql("drop table if exists shippers")
    println("shippers table dropped successfully.");
    snc.sql("drop table if exists suppliers")
    println("suppliers table dropped successfully.");
    snc.sql("drop table if exists territories")
    println("territories table dropped successfully.");
  }

}
