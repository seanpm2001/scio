/*
 * Copyright 2021 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// Example: Read and write Parquet in Avro and Typed formats
// Usage:
// `sbt "runMain com.spotify.scio.examples.extra.ParquetExample
// --project=[PROJECT] --runner=DataflowRunner --region=[REGION]
// --input=[INPUT]/*.parquet --output=[OUTPUT] --method=[METHOD]"`
package com.spotify.scio.examples.extra

import com.google.protobuf.ByteString
import com.spotify.scio._
import com.spotify.scio.avro._
import com.spotify.scio.parquet.avro._
import com.spotify.scio.parquet.types._
import com.spotify.scio.parquet.tensorflow._
import com.spotify.scio.coders.Coder
import com.spotify.scio.io.ClosedTap
import com.spotify.scio.parquet.ParquetConfiguration
import org.apache.avro.generic.GenericRecord
import org.tensorflow.proto.example.{BytesList, Example, Feature, Features, FloatList}
import org.tensorflow.metadata.{v0 => tfmd}

object ParquetExample {

  /**
   * These case classes represent both full and projected field mappings from the [[Account]] Avro
   * record.
   */
  case class AccountFull(
    id: Int,
    `type`: String,
    name: Option[String],
    amount: Double,
    accountStatus: Option[AccountStatus]
  )
  case class AccountProjection(id: Int, name: Option[String])

  /**
   * A Hadoop [[Configuration]] can optionally be passed for Parquet reads and writes to improve
   * performance.
   *
   * See more here: https://spotify.github.io/scio/io/Parquet.html#performance-tuning
   */
  private val fineTunedParquetWriterConfig = ParquetConfiguration.of(
    "parquet.block.size" -> 1073741824, // 1 * 1024 * 1024 * 1024 = 1 GiB
    "fs.gs.inputstream.fadvise" -> "RANDOM"
  )

  private[extra] val fakeData: Iterable[Account] =
    (1 to 100)
      .map(i =>
        Account
          .newBuilder()
          .setId(i)
          .setType(if (i % 3 == 0) "current" else "checking")
          .setName(s"account $i")
          .setAmount(i.toDouble)
          .setAccountStatus(if (i % 2 == 0) AccountStatus.Active else AccountStatus.Inactive)
          .build()
      )

  def pipeline(cmdlineArgs: Array[String]): ScioContext = {
    val (sc, args) = ContextAndArgs(cmdlineArgs)

    val m = args("method")
    m match {
      // Read Parquet files as Specific Avro Records
      case "avroSpecificIn" => avroSpecificIn(sc, args)

      // Read Parquet files as Generic Avro Records
      case "avroGenericIn" => avroGenericIn(sc, args)

      // Read Parquet files as Scala Case Classes
      case "typedIn" => typedIn(sc, args)

      // Write dummy Parquet Avro records
      case "avroOut" => avroOut(sc, args)

      // Write dummy Parquet records using case classes
      case "typedOut" => typedOut(sc, args)

      // Write dummy Parquet Avro records
      case "exampleOut" => exampleOut(sc, args)

      // Write dummy Parquet records using case classes
      case "exampleIn" => exampleIn(sc, args)

      case _ => throw new RuntimeException(s"Invalid method $m")
    }

    sc
  }

  def main(cmdlineArgs: Array[String]): Unit = {
    val sc = pipeline(cmdlineArgs)
    sc.run().waitUntilDone()
    ()
  }

  private def avroSpecificIn(sc: ScioContext, args: Args): ClosedTap[String] = {
    // Macros for generating column projections and row predicates
    // account_status is the only field with default value that can be left out the projection
    val projection = Projection[Account](_.getId, _.getType, _.getName, _.getAmount)
    val predicate = Predicate[Account](x => x.getAmount > 0)

    sc.parquetAvroFile[Account](args("input"), projection, predicate)
      .saveAsTextFile(args("output"))
  }

  private def avroGenericIn(sc: ScioContext, args: Args): ClosedTap[String] = {
    val schema = Account.getClassSchema
    implicit val genericRecordCoder: Coder[GenericRecord] = avroGenericRecordCoder(schema)

    val parquetIn = sc.parquetAvroGenericRecordFile(args("input"), schema)

    // Catches a specific bug with encoding GenericRecords read by parquet-avro
    parquetIn
      .map(identity)
      .count

    // We can also pass an Avro schema directly to project into Avro GenericRecords.
    parquetIn
      // Map out projected fields into something type safe
      .map(r => AccountProjection(r.get("id").asInstanceOf[Int], Some(r.get("name").toString)))
      .saveAsTextFile(args("output"))
  }

  private def typedIn(sc: ScioContext, args: Args): ClosedTap[String] =
    sc.typedParquetFile[AccountProjection](args("input"))
      .saveAsTextFile(args("output"))

  private def avroOut(sc: ScioContext, args: Args): ClosedTap[Account] =
    sc.parallelize(fakeData)
      // numShards should be explicitly set so that the size of each output file is smaller than
      // but close to `parquet.block.size`, i.e. 1 GiB. This guarantees that each file contains 1 row group only and reduces seeks.
      .saveAsParquetAvroFile(args("output"), numShards = 1, conf = fineTunedParquetWriterConfig)

  private[extra] def toScalaFull(account: Account): AccountFull =
    AccountFull(
      account.getId,
      account.getType.toString,
      Some(account.getName.toString),
      account.getAmount,
      Some(account.getAccountStatus)
    )

  private def typedOut(sc: ScioContext, args: Args): ClosedTap[AccountFull] =
    sc.parallelize(fakeData)
      .map(toScalaFull)
      .saveAsTypedParquetFile(args("output"))

  private[extra] def toExample(account: Account): Example = {
    val amount = Feature
      .newBuilder()
      .setFloatList(FloatList.newBuilder().addValue(account.getAmount.toFloat))
      .build()
    val `type` = Feature
      .newBuilder()
      .setBytesList(
        BytesList.newBuilder().addValue(ByteString.copyFromUtf8(account.getType.toString))
      )
      .build()
    val features = Features
      .newBuilder()
      .putFeature("amount", amount)
      .putFeature("type", `type`)
      .build()
    Example.newBuilder().setFeatures(features).build()
  }

  private def exampleIn(sc: ScioContext, args: Args): ClosedTap[String] = {
    val projection = tfmd.Schema
      .newBuilder()
      .addFeature(tfmd.Feature.newBuilder().setName("amount").setType(tfmd.FeatureType.FLOAT))
      .build()

    sc.parquetExampleFile(args("input"), projection)
      .saveAsTextFile(args("output"))
  }

  private def exampleOut(sc: ScioContext, args: Args): ClosedTap[Example] = {
    val schema = tfmd.Schema
      .newBuilder()
      .addFeature(tfmd.Feature.newBuilder().setName("amount").setType(tfmd.FeatureType.FLOAT))
      .addFeature(tfmd.Feature.newBuilder().setName("type").setType(tfmd.FeatureType.BYTES))
      .build()

    sc.parallelize(fakeData.map(toExample))
      .saveAsParquetExampleFile(args("output"), schema)
  }
}
