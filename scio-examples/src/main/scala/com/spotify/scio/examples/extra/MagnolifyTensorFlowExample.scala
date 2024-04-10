/*
 * Copyright 2019 Spotify AB.
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

// Example: Handling TensorFlow Example Types with Magnolify

// TensorFlow `Example` is a Protobuf type and very verbose. By using
// [Magnolify](https://github.com/spotify/magnolify), one can seamlessly
// convert between case classes and TensorFlow `Example`s.
package com.spotify.scio.examples.extra

import com.google.protobuf.ByteString
import com.spotify.scio._
import com.spotify.scio.examples.common.ExampleData
import com.spotify.scio.tensorflow._
import org.tensorflow.proto.example.Example
import magnolify.tensorflow._

object MagnolifyTensorFlowExample {
  // Define case class representation of TensorFlow `Example`
  case class WordCount(word: String, count: Long)
  // `Example` type doesn't support `String` natively, derive one from `ByteString`
  implicit val efString: ExampleField.Primitive[String] =
    ExampleField.from[ByteString](_.toStringUtf8)(ByteString.copyFromUtf8)
}

// ## Magnolify Tensorflow Write Example
// Count words and save result as `TFRecord`s

// Usage:

// `sbt "runMain com.spotify.scio.examples.extra.MagnolifyTensorFlowWriteExample
// --project=[PROJECT] --runner=DataflowRunner --region=[REGION NAME]
// --input=gs://apache-beam-samples/shakespeare/kinglear.txt
// --output=gs://[BUCKET]/[PATH]/wordcount-tf"`
object MagnolifyTensorFlowWriteExample {
  def main(cmdlineArgs: Array[String]): Unit = {
    import MagnolifyTensorFlowExample._

    val (sc, args) = ContextAndArgs(cmdlineArgs)
    sc.textFile(args.getOrElse("input", ExampleData.KING_LEAR))
      .flatMap(_.split("[^a-zA-Z']+").filter(_.nonEmpty))
      .countByValue
      .map { case (word, count) => WordCount(word, count) }
      // converts WordCount to Example with the implicitly-derived ExampleType[WordCount]
      .saveAsTfRecordFile(args("output"))
    sc.run()
    ()
  }
}

// ## Magnolify Tensorflow Read Example
// Read word count result back from `TFRecord`

// Usage:

// `sbt "runMain com.spotify.scio.examples.extra.MagnolifyTensorFlowReadExample
// --project=[PROJECT] --runner=DataflowRunner --region=[REGION NAME]
// --input=gs://[BUCKET]/[PATH]/wordcount-tf
// --output=gs://[BUCKET]/[PATH]/wordcount"`
object MagnolifyTensorFlowReadExample {
  def main(cmdlineArgs: Array[String]): Unit = {
    import MagnolifyTensorFlowExample._

    val (sc, args) = ContextAndArgs(cmdlineArgs)
    // reads TF Examples and converts to WordCount via the implicitly-derived ExampleType[WordCount]
    sc.typedTfRecordFile[WordCount](args("input"))
      .saveAsTextFile(args("output"))
    sc.run()
    ()
  }
}
