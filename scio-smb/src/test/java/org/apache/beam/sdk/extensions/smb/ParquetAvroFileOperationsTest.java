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

package org.apache.beam.sdk.extensions.smb;

import static org.apache.beam.sdk.extensions.smb.TestUtils.fromFolder;
import static org.apache.beam.sdk.transforms.display.DisplayDataMatchers.hasDisplayItem;

import com.spotify.scio.smb.TestLogicalTypes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.beam.sdk.extensions.avro.io.AvroGeneratedUser;
import org.apache.beam.sdk.io.Compression;
import org.apache.beam.sdk.io.fs.ResolveOptions;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.util.MimeTypes;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Lists;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroDataSupplier;
import org.apache.parquet.avro.AvroReadSupport;
import org.apache.parquet.avro.AvroWriteSupport;
import org.apache.parquet.filter2.predicate.FilterApi;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.hamcrest.MatcherAssert;
import org.joda.time.DateTime;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import scala.math.BigDecimal;

/** Unit tests for {@link ParquetAvroFileOperations}. */
public class ParquetAvroFileOperationsTest {
  @Rule public final TemporaryFolder output = new TemporaryFolder();

  private static final Schema USER_SCHEMA =
      Schema.createRecord(
          "User",
          "",
          "org.apache.beam.sdk.extensions.smb.ParquetAvroFileOperationsTest$",
          false,
          Lists.newArrayList(
              new Schema.Field("name", Schema.create(Schema.Type.STRING), "", ""),
              new Schema.Field("age", Schema.create(Schema.Type.INT), "", 0)));

  private static final List<GenericRecord> USER_RECORDS =
      IntStream.range(0, 10)
          .mapToObj(
              i ->
                  new GenericRecordBuilder(USER_SCHEMA)
                      .set("name", String.format("user%02d", i))
                      .set("age", i)
                      .build())
          .collect(Collectors.toList());

  // Class has no no-arg constructor
  static class User {
    User(String str) {
    }
  }
  
  @Test
  public void testGenericRecord() throws Exception {
    final ResourceId file =
        fromFolder(output)
            .resolve("file.parquet", ResolveOptions.StandardResolveOptions.RESOLVE_FILE);
    writeFile(file);

    final ParquetAvroFileOperations<GenericRecord> fileOperations =
        ParquetAvroFileOperations.of(USER_SCHEMA);

    final List<GenericRecord> actual = new ArrayList<>();
    /*
      Reads will now fail with:
      
      Caused by: java.lang.RuntimeException: java.lang.NoSuchMethodException: org.apache.beam.sdk.extensions.smb.ParquetAvroFileOperationsTest$User.<init>()
	at org.apache.avro.specific.SpecificData.newInstance(SpecificData.java:353)
	at org.apache.avro.specific.SpecificData.newRecord(SpecificData.java:369)
	at org.apache.parquet.avro.AvroRecordConverter.start(AvroRecordConverter.java:407)
	at org.apache.parquet.io.RecordReaderImplementation.read(RecordReaderImplementation.java:392)
	at org.apache.parquet.hadoop.InternalParquetRecordReader.nextKeyValue(InternalParquetRecordReader.java:234)
	... 37 more
    */
    fileOperations.iterator(file).forEachRemaining(actual::add);

    Assert.assertEquals(USER_RECORDS, actual);
  }

  @Test
  public void testSpecificRecord() throws Exception {
    final ParquetAvroFileOperations<AvroGeneratedUser> fileOperations =
        ParquetAvroFileOperations.of(AvroGeneratedUser.getClassSchema());
    final ResourceId file =
        fromFolder(output)
            .resolve("file.parquet", ResolveOptions.StandardResolveOptions.RESOLVE_FILE);

    final List<AvroGeneratedUser> records =
        IntStream.range(0, 10)
            .mapToObj(
                i ->
                    AvroGeneratedUser.newBuilder()
                        .setName(String.format("user%02d", i))
                        .setFavoriteColor(String.format("color%02d", i))
                        .setFavoriteNumber(i)
                        .build())
            .collect(Collectors.toList());
    final FileOperations.Writer<AvroGeneratedUser> writer = fileOperations.createWriter(file);
    for (AvroGeneratedUser record : records) {
      writer.write(record);
    }
    writer.close();

    final List<AvroGeneratedUser> actual = new ArrayList<>();
    fileOperations.iterator(file).forEachRemaining(actual::add);

    Assert.assertEquals(records, actual);
  }

  @Test
  public void testLogicalTypes() throws Exception {
    final Configuration conf = new Configuration();
    conf.setClass(
        AvroWriteSupport.AVRO_DATA_SUPPLIER, AvroLogicalTypeSupplier.class, AvroDataSupplier.class);
    conf.setClass(
        AvroReadSupport.AVRO_DATA_SUPPLIER, AvroLogicalTypeSupplier.class, AvroDataSupplier.class);

    final ParquetAvroFileOperations<TestLogicalTypes> fileOperations =
        ParquetAvroFileOperations.of(
            TestLogicalTypes.getClassSchema(), CompressionCodecName.UNCOMPRESSED, conf);
    final ResourceId file =
        fromFolder(output)
            .resolve("file.parquet", ResolveOptions.StandardResolveOptions.RESOLVE_FILE);

    final List<TestLogicalTypes> records =
        IntStream.range(0, 10)
            .mapToObj(
                i ->
                    TestLogicalTypes.newBuilder()
                        .setTimestamp(DateTime.now())
                        .setDecimal(BigDecimal.decimal(1.0).setScale(2).bigDecimal())
                        .build())
            .collect(Collectors.toList());
    final FileOperations.Writer<TestLogicalTypes> writer = fileOperations.createWriter(file);
    for (TestLogicalTypes record : records) {
      writer.write(record);
    }
    writer.close();

    final List<TestLogicalTypes> actual = new ArrayList<>();
    fileOperations.iterator(file).forEachRemaining(actual::add);

    Assert.assertEquals(records, actual);
  }

  @Test
  public void testGenericProjection() throws Exception {
    final ResourceId file =
        fromFolder(output)
            .resolve("file.parquet", ResolveOptions.StandardResolveOptions.RESOLVE_FILE);
    writeFile(file);

    final Schema projection =
        Schema.createRecord(
            "UserProjection",
            "",
            "org.apache.beam.sdk.extensions.smb.avro",
            false,
            Lists.newArrayList(
                new Schema.Field("name", Schema.create(Schema.Type.STRING), "", "")));

    final Configuration configuration = new Configuration();
    AvroReadSupport.setRequestedProjection(configuration, projection);

    final ParquetAvroFileOperations<GenericRecord> fileOperations =
        ParquetAvroFileOperations.of(USER_SCHEMA, CompressionCodecName.ZSTD, configuration);

    final List<GenericRecord> expected =
        USER_RECORDS.stream()
            .map(r -> new GenericRecordBuilder(USER_SCHEMA).set("name", r.get("name")).build())
            .collect(Collectors.toList());
    final List<GenericRecord> actual = new ArrayList<>();
    fileOperations.iterator(file).forEachRemaining(actual::add);

    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testSpecificProjection() throws Exception {
    final Schema projection =
        Schema.createRecord(
            "AvroGeneratedUserProjection",
            "",
            "org.apache.beam.sdk.extensions.smb",
            false,
            Lists.newArrayList(
                new Schema.Field("name", Schema.create(Schema.Type.STRING), "", "")));
    final Configuration configuration = new Configuration();
    AvroReadSupport.setRequestedProjection(configuration, projection);

    final ParquetAvroFileOperations<AvroGeneratedUser> fileOperations =
        ParquetAvroFileOperations.of(
            AvroGeneratedUser.class, CompressionCodecName.UNCOMPRESSED, configuration);

    final ResourceId file =
        fromFolder(output)
            .resolve("file.parquet", ResolveOptions.StandardResolveOptions.RESOLVE_FILE);

    final List<AvroGeneratedUser> records =
        IntStream.range(0, 10)
            .mapToObj(
                i ->
                    AvroGeneratedUser.newBuilder()
                        .setName(String.format("user%02d", i))
                        .setFavoriteColor(String.format("color%02d", i))
                        .setFavoriteNumber(i)
                        .build())
            .collect(Collectors.toList());
    final FileOperations.Writer<AvroGeneratedUser> writer = fileOperations.createWriter(file);
    for (AvroGeneratedUser record : records) {
      writer.write(record);
    }
    writer.close();

    final List<AvroGeneratedUser> actual = new ArrayList<>();
    fileOperations.iterator(file).forEachRemaining(actual::add);

    final List<AvroGeneratedUser> expected =
        IntStream.range(0, 10)
            .mapToObj(
                i ->
                    AvroGeneratedUser.newBuilder()
                        .setName(String.format("user%02d", i))
                        .setFavoriteColor(null)
                        .setFavoriteNumber(null)
                        .build())
            .collect(Collectors.toList());
    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testPredicate() throws Exception {
    final ResourceId file =
        fromFolder(output)
            .resolve("file.parquet", ResolveOptions.StandardResolveOptions.RESOLVE_FILE);
    writeFile(file);

    final FilterPredicate predicate = FilterApi.ltEq(FilterApi.intColumn("age"), 5);

    final ParquetAvroFileOperations<GenericRecord> fileOperations =
        ParquetAvroFileOperations.of(USER_SCHEMA, predicate);

    final List<GenericRecord> expected =
        USER_RECORDS.stream().filter(r -> (int) r.get("age") <= 5).collect(Collectors.toList());
    final List<GenericRecord> actual = new ArrayList<>();
    fileOperations.iterator(file).forEachRemaining(actual::add);

    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testDisplayData() {
    final ParquetAvroFileOperations<GenericRecord> fileOperations =
        ParquetAvroFileOperations.of(USER_SCHEMA);

    final DisplayData displayData = DisplayData.from(fileOperations);
    MatcherAssert.assertThat(
        displayData, hasDisplayItem("FileOperations", ParquetAvroFileOperations.class));
    MatcherAssert.assertThat(displayData, hasDisplayItem("mimeType", MimeTypes.BINARY));
    MatcherAssert.assertThat(
        displayData, hasDisplayItem("compression", Compression.UNCOMPRESSED.toString()));
    MatcherAssert.assertThat(
        displayData, hasDisplayItem("compressionCodecName", CompressionCodecName.ZSTD.name()));
    MatcherAssert.assertThat(displayData, hasDisplayItem("schema", USER_SCHEMA.getFullName()));
  }

  private void writeFile(ResourceId file) throws IOException {
    final ParquetAvroFileOperations<GenericRecord> fileOperations =
        ParquetAvroFileOperations.of(USER_SCHEMA, CompressionCodecName.ZSTD);
    final FileOperations.Writer<GenericRecord> writer = fileOperations.createWriter(file);
    for (GenericRecord record : USER_RECORDS) {
      writer.write(record);
    }
    writer.close();
  }
}
