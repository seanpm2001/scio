/*
 * Copyright 2020 Spotify AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.spotify.scio.coders.instances

import java.io.{InputStream, OutputStream}
import com.google.common.hash.{BloomFilter, Funnel}
import com.spotify.scio.coders.{Coder, CoderGrammar}
import org.apache.beam.sdk.coders.CustomCoder

class GuavaBloomFilterCoder[T](implicit val funnel: Funnel[_ >: T])
    extends CustomCoder[BloomFilter[T]] {
  override def encode(value: BloomFilter[T], outStream: OutputStream): Unit =
    value.writeTo(outStream)
  override def decode(inStream: InputStream): BloomFilter[T] =
    BloomFilter.readFrom[T](inStream, funnel)
  override def verifyDeterministic(): Unit = {}
}

trait GuavaCoders extends CoderGrammar {
  implicit def guavaBFCoder[T](implicit x: Funnel[_ >: T]): Coder[BloomFilter[T]] =
    beam(new GuavaBloomFilterCoder[T])
}

private[coders] object GuavaCoders extends GuavaCoders
