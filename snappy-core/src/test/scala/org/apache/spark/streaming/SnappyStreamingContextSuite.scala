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
package org.apache.spark.streaming

import java.io.File

import scala.reflect.ClassTag

import io.snappydata.SnappyFunSuite
import org.apache.commons.io.FileUtils
import org.scalatest.concurrent.Eventually
import org.scalatest.time.SpanSugar._
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.dstream.{InputDStream, DStream}
import org.apache.spark.streaming.scheduler.StreamInputInfo
import org.apache.spark.util.Utils
import org.apache.spark.{SparkConf, SparkContext}


class SnappyStreamingContextSuite extends SnappyFunSuite with Eventually with BeforeAndAfter with BeforeAndAfterAll{


  def framework: String = this.getClass.getSimpleName

  val master = "local[2]"
  val appName = this.getClass.getSimpleName
  val batchDuration = Milliseconds(500)
  val sparkHome = "someDir"
  val envPair = "key" -> "value"
  val conf = new SparkConf().setMaster(master).setAppName(appName)

  var snsc: SnappyStreamingContext = null

  // context creation is handled by App main
  override def beforeAll(): Unit = {
    super.beforeAll()
    stopAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    stopAll()
  }

  before {
  }

  after {
    val activeSsc = SnappyStreamingContext.getActive()
    activeSsc match {
      case Some(x) => x.stop(stopSparkContext = true, stopGracefully = true)
      case None => //
    }

  }


  test("test simple constructor") {
    val conf = new SparkConf().setMaster(master).setAppName(appName)
    val sc = new SparkContext(conf)
    snsc = new SnappyStreamingContext(sc, batchDuration = batchDuration)
    assert(SnappyStreamingContext.getInstance() != null)

    val input = addInputStream(snsc)
    input.foreachRDD { rdd => rdd.count }
    snsc.start()

    assert(SnappyStreamingContext.getActive() != null)
  }

  test("test getOrCreate") {
    val conf = new SparkConf().setMaster(master).setAppName(appName)

    // Function to create StreamingContext that has a config to identify it to be new context
    var newContextCreated = false
    def creatingFunction(): SnappyStreamingContext = {
      newContextCreated = true
      new SnappyStreamingContext(conf, batchDuration)
    }
    // Call ssc.stop after a body of code
    def testGetOrCreate(body: => Unit): Unit = {
      newContextCreated = false
      try {
        body
      } finally {
        if (snsc != null) {
          snsc.stop()
        }
        snsc = null
      }
    }

    val emptyPath = Utils.createTempDir().getAbsolutePath()

    // getOrCreate should create new context with empty path
    testGetOrCreate {
      snsc = SnappyStreamingContext.getOrCreate(emptyPath, creatingFunction _)
      assert(snsc != null, "no context created")
      assert(newContextCreated, "new context not created")
    }

    val corruptedCheckpointPath = createCorruptedCheckpoint()

    // getOrCreate should throw exception with fake checkpoint file and createOnError = false
    intercept[Exception] {
      snsc = SnappyStreamingContext.getOrCreate(corruptedCheckpointPath, creatingFunction _)
    }

    // getOrCreate should throw exception with fake checkpoint file
    intercept[Exception] {
      snsc = SnappyStreamingContext.getOrCreate(
        corruptedCheckpointPath, creatingFunction _, createOnError = false)
    }

    // getOrCreate should create new context with fake checkpoint file and createOnError = true
    testGetOrCreate {
      snsc = SnappyStreamingContext.getOrCreate(
        corruptedCheckpointPath, creatingFunction _, createOnError = true)
      assert(snsc != null, "no context created")
      assert(newContextCreated, "new context not created")
    }

    val checkpointPath = createValidCheckpoint()

    // getOrCreate should recover context with checkpoint path, and recover old configuration
    testGetOrCreate {
      snsc = SnappyStreamingContext.getOrCreate(checkpointPath, creatingFunction _)
      assert(snsc != null, "no context created")
      assert(!newContextCreated, "old context not recovered")
      assert(snsc.conf.get("someKey") === "someValue", "checkpointed config not recovered")
    }
  }


  test("getActiveOrCreate with checkpoint") {
    // Function to create StreamingContext that has a config to identify it to be new context
    var newContextCreated = false
    def creatingFunction(): SnappyStreamingContext = {
      newContextCreated = true
      new SnappyStreamingContext(sc, batchDuration)
    }

    // Call ssc.stop after a body of code
    def testGetActiveOrCreate(body: => Unit): Unit = {
      require(SnappyStreamingContext.getActive().isEmpty) // no active context
      newContextCreated = false
      try {
        body
      } finally {
        if (snsc != null) {
          snsc.stop()
        }
        snsc = null
      }
    }

    val emptyPath = Utils.createTempDir().getAbsolutePath()
    val corruptedCheckpointPath = createCorruptedCheckpoint()
    val checkpointPath = createValidCheckpoint()

    // getActiveOrCreate should return the current active context if there is one
    testGetActiveOrCreate {
      snsc = new SnappyStreamingContext(
        conf.clone.set("spark.streaming.clock", "org.apache.spark.util.ManualClock"), batchDuration)
      addInputStream(snsc).register()
      snsc.start()
      val returnedSsc = SnappyStreamingContext.getActiveOrCreate(checkpointPath, creatingFunction _)
      assert(!newContextCreated, "new context created instead of returning")
      assert(returnedSsc.eq(snsc), "returned context is not the activated context")
    }

    // getActiveOrCreate should create new context with empty path
    testGetActiveOrCreate {
      snsc = SnappyStreamingContext.getActiveOrCreate(emptyPath, creatingFunction _)
      assert(snsc != null, "no context created")
      assert(newContextCreated, "new context not created")
    }

    // getActiveOrCreate should throw exception with fake checkpoint file and createOnError = false
    intercept[Exception] {
      snsc = SnappyStreamingContext.getOrCreate(corruptedCheckpointPath, creatingFunction _)
    }

    // getActiveOrCreate should throw exception with fake checkpoint file
    intercept[Exception] {
      snsc = SnappyStreamingContext.getActiveOrCreate(
        corruptedCheckpointPath, creatingFunction _, createOnError = false)
    }

    // getActiveOrCreate should create new context with fake
    // checkpoint file and createOnError = true
    testGetActiveOrCreate {
      snsc = SnappyStreamingContext.getActiveOrCreate(
        corruptedCheckpointPath, creatingFunction _, createOnError = true)
      assert(snsc != null, "no context created")
      assert(newContextCreated, "new context not created")
    }

    // getActiveOrCreate should recover context with checkpoint path, and recover old configuration
    testGetActiveOrCreate {
      snsc = SnappyStreamingContext.getActiveOrCreate(checkpointPath, creatingFunction _)
      assert(snsc != null, "no context created")
      assert(!newContextCreated, "old context not recovered")
      assert(snsc.conf.get("someKey") === "someValue")
    }
  }

  def addInputStream(s: StreamingContext): DStream[Int] = {
    val input = (1 to 100).map(i => 1 to i)
    val inputStream = new TestInputStream(s, input, 1)
    inputStream
  }

  def createValidCheckpoint(): String = {
    val testDirectory = Utils.createTempDir().getAbsolutePath()
    val checkpointDirectory = Utils.createTempDir().getAbsolutePath()
    val ssc = new StreamingContext(conf.clone.set("someKey", "someValue"), batchDuration)
    ssc.checkpoint(checkpointDirectory)
    ssc.textFileStream(testDirectory).foreachRDD { rdd => rdd.count() }
    ssc.start()
    eventually(timeout(10000 millis)) {
      assert(Checkpoint.getCheckpointFiles(checkpointDirectory).size > 1)
    }
    ssc.stop()
    checkpointDirectory
  }

  def createCorruptedCheckpoint(): String = {
    val checkpointDirectory = Utils.createTempDir().getAbsolutePath()
    val fakeCheckpointFile = Checkpoint.checkpointFile(checkpointDirectory, Time(1000))
    FileUtils.write(new File(fakeCheckpointFile.toString()), "blablabla")
    assert(Checkpoint.getCheckpointFiles(checkpointDirectory).nonEmpty)
    checkpointDirectory
  }

}

class TestInputStream[T: ClassTag](ssc_ : StreamingContext, input: Seq[Seq[T]], numPartitions: Int)
    extends InputDStream[T](ssc_) {

  def start() {}

  def stop() {}

  def compute(validTime: Time): Option[RDD[T]] = {
    logInfo("Computing RDD for time " + validTime)
    val index = ((validTime - zeroTime) / slideDuration - 1).toInt
    val selectedInput = if (index < input.size) input(index) else Seq[T]()

    // lets us test cases where RDDs are not created
    if (selectedInput == null) {
      return None
    }

    // Report the input data's information to InputInfoTracker for testing
    val inputInfo = StreamInputInfo(id, selectedInput.length.toLong)
    ssc.scheduler.inputInfoTracker.reportInfo(validTime, inputInfo)

    val rdd = ssc.sc.makeRDD(selectedInput, numPartitions)
    logInfo("Created RDD " + rdd.id + " with " + selectedInput)
    Some(rdd)
  }
}
