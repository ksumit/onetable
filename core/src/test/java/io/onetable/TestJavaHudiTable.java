/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package io.onetable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.Assertions;

import org.apache.hudi.avro.HoodieAvroUtils;
import org.apache.hudi.avro.model.HoodieCleanMetadata;
import org.apache.hudi.client.HoodieJavaWriteClient;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.client.common.HoodieJavaEngineContext;
import org.apache.hudi.common.config.TypedProperties;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.model.HoodieAvroPayload;
import org.apache.hudi.common.model.HoodieAvroRecord;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieTableType;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.keygen.CustomKeyGenerator;
import org.apache.hudi.keygen.NonpartitionedKeyGenerator;

public class TestJavaHudiTable extends TestAbstractHudiTable {
  private final HoodieJavaWriteClient<HoodieAvroPayload> javaWriteClient;
  private final Configuration conf;
  /**
   * Create a test table instance for general testing. The table is created with the schema defined
   * in basic_schema.avsc which contains many data types to ensure they are handled correctly.
   *
   * @param tableName name of the table used in the test, should be unique per test within a shared
   *     directory
   * @param tempDir directory where table will be written, typically a temporary directory that will
   *     be cleaned up after the tests.
   * @param partitionConfig sets the property `hoodie.datasource.write.partitionpath.field` for the
   *     {@link CustomKeyGenerator}. If null, {@link NonpartitionedKeyGenerator} will be used.
   * @param tableType the table type to use (MoR or CoW)
   * @return an instance of the class with this configuration
   */
  public static TestJavaHudiTable forStandardSchema(
      String tableName, Path tempDir, String partitionConfig, HoodieTableType tableType) {
    return new TestJavaHudiTable(tableName, BASIC_SCHEMA, tempDir, partitionConfig, tableType);
  }

  /**
   * Create a test table instance with a schema that has more fields than an instance returned by
   * {@link #forStandardSchema(String, Path, String, HoodieTableType)}. Specifically this instance
   * will add a top level field, nested field, field within a list, and field within a map to ensure
   * schema evolution is properly handled.
   *
   * @param tableName name of the table used in the test, should be unique per test within a shared
   *     directory
   * @param tempDir directory where table will be written, typically a temporary directory that will
   *     be cleaned up after the tests.
   * @param partitionConfig sets the property `hoodie.datasource.write.partitionpath.field` for the
   *     {@link CustomKeyGenerator}. If null, {@link NonpartitionedKeyGenerator} will be used.
   * @param tableType the table type to use (MoR or CoW)
   * @return an instance of the class with this configuration
   */
  public static TestJavaHudiTable withAdditionalColumns(
      String tableName, Path tempDir, String partitionConfig, HoodieTableType tableType) {
    return new TestJavaHudiTable(
        tableName,
        addSchemaEvolutionFieldsToBase(BASIC_SCHEMA),
        tempDir,
        partitionConfig,
        tableType);
  }

  public static TestJavaHudiTable withAdditionalTopLevelField(
      String tableName,
      Path tempDir,
      String partitionConfig,
      HoodieTableType tableType,
      Schema previousSchema) {
    return new TestJavaHudiTable(
        tableName, addTopLevelField(previousSchema), tempDir, partitionConfig, tableType);
  }

  private TestJavaHudiTable(
      String name,
      Schema schema,
      Path tempDir,
      String partitionConfig,
      HoodieTableType hoodieTableType) {
    super(name, schema, tempDir, partitionConfig);
    this.conf = new Configuration();
    this.conf.set("parquet.avro.write-old-list-structure", "false");
    try {
      this.metaClient = initMetaClient(hoodieTableType, typedProperties);
    } catch (IOException ex) {
      throw new UncheckedIOException("Unable to initialize metaclient for TestJavaHudiTable", ex);
    }
    this.javaWriteClient = initJavaWriteClient(schema.toString(), typedProperties);
  }

  public List<HoodieRecord<HoodieAvroPayload>> insertRecordsWithCommitAlreadyStarted(
      List<HoodieRecord<HoodieAvroPayload>> inserts,
      String commitInstant,
      boolean checkForNoErrors) {
    List<WriteStatus> result = javaWriteClient.bulkInsert(copyRecords(inserts), commitInstant);
    if (checkForNoErrors) {
      assertNoWriteErrors(result);
    }
    return inserts;
  }

  public List<HoodieRecord<HoodieAvroPayload>> upsertRecordsWithCommitAlreadyStarted(
      List<HoodieRecord<HoodieAvroPayload>> records,
      String commitInstant,
      boolean checkForNoErrors) {
    List<HoodieRecord<HoodieAvroPayload>> updates = generateUpdatesForRecords(records);
    List<WriteStatus> result = javaWriteClient.upsert(copyRecords(updates), commitInstant);
    if (checkForNoErrors) {
      assertNoWriteErrors(result);
    }
    return updates;
  }

  public List<HoodieKey> deleteRecords(
      List<HoodieRecord<HoodieAvroPayload>> records, boolean checkForNoErrors) {
    List<HoodieKey> deletes =
        records.stream().map(HoodieRecord::getKey).collect(Collectors.toList());
    String instant = getStartCommitInstant();
    List<WriteStatus> result = javaWriteClient.delete(deletes, instant);
    if (checkForNoErrors) {
      assertNoWriteErrors(result);
    }
    return deletes;
  }

  public void deletePartition(String partition, HoodieTableType tableType) {
    throw new UnsupportedOperationException(
        "Hoodie java client does not support delete partitions");
  }

  public void compact() {
    String instant = javaWriteClient.scheduleCompaction(Option.empty()).get();
    javaWriteClient.compact(instant);
  }

  public String onlyScheduleCompaction() {
    return javaWriteClient.scheduleCompaction(Option.empty()).get();
  }

  public void completeScheduledCompaction(String instant) {
    javaWriteClient.compact(instant);
  }

  public void cluster() {
    String instant = javaWriteClient.scheduleClustering(Option.empty()).get();
    javaWriteClient.cluster(instant, true);
  }

  public void rollback(String commitInstant) {
    javaWriteClient.rollback(commitInstant);
  }

  public void savepointRestoreForPreviousInstant() {
    List<HoodieInstant> commitInstants =
        metaClient.getActiveTimeline().reload().getCommitsTimeline().getInstants();
    HoodieInstant instantToRestore = commitInstants.get(commitInstants.size() - 2);
    javaWriteClient.savepoint(instantToRestore.getTimestamp(), "user", "savepoint-test");
    javaWriteClient.restoreToSavepoint(instantToRestore.getTimestamp());
  }

  public void clean() {
    HoodieCleanMetadata metadata = javaWriteClient.clean();
    // Assert that files are deleted to ensure test is realistic
    Assertions.assertTrue(metadata.getTotalFilesDeleted() > 0);
  }

  private String getStartCommitInstant() {
    return javaWriteClient.startCommit(metaClient.getCommitActionType(), metaClient);
  }

  private String getStartCommitOfActionType(String actionType) {
    return javaWriteClient.startCommit(actionType, metaClient);
  }

  public String startCommit() {
    return getStartCommitInstant();
  }

  public List<HoodieRecord<HoodieAvroPayload>> upsertRecords(
      List<HoodieRecord<HoodieAvroPayload>> records, boolean checkForNoErrors) {
    String instant = getStartCommitInstant();
    return upsertRecordsWithCommitAlreadyStarted(records, instant, checkForNoErrors);
  }

  private List<HoodieRecord<HoodieAvroPayload>> insertRecords(
      boolean checkForNoErrors, List<HoodieRecord<HoodieAvroPayload>> inserts) {
    String instant = getStartCommitInstant();
    return insertRecordsWithCommitAlreadyStarted(inserts, instant, checkForNoErrors);
  }

  public List<HoodieRecord<HoodieAvroPayload>> insertRecords(
      int numRecords, boolean checkForNoErrors) {
    List<HoodieRecord<HoodieAvroPayload>> inserts = generateRecords(numRecords);
    return insertRecords(checkForNoErrors, inserts);
  }

  public List<HoodieRecord<HoodieAvroPayload>> insertRecords(
      int numRecords, Object partitionValue, boolean checkForNoErrors) {
    Instant startTimeWindow = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(1, ChronoUnit.DAYS);
    Instant endTimeWindow = Instant.now().truncatedTo(ChronoUnit.DAYS);
    List<HoodieRecord<HoodieAvroPayload>> inserts =
        IntStream.range(0, numRecords)
            .mapToObj(
                index ->
                    getRecord(
                        schema,
                        UUID.randomUUID().toString(),
                        startTimeWindow,
                        endTimeWindow,
                        null,
                        partitionValue))
            .collect(Collectors.toList());
    return insertRecords(checkForNoErrors, inserts);
  }

  @Override
  public void close() {
    if (javaWriteClient != null) {
      javaWriteClient.close();
    }
  }

  // Existing records are need to create updates etc... so create a copy of the records and operate
  // on the copy.
  private List<HoodieRecord<HoodieAvroPayload>> copyRecords(
      List<HoodieRecord<HoodieAvroPayload>> records) {
    return records.stream()
        .map(
            hoodieRecord -> {
              HoodieKey key = hoodieRecord.getKey();
              byte[] payloadBytes = hoodieRecord.getData().getRecordBytes();
              byte[] payloadBytesCopy = Arrays.copyOf(payloadBytes, payloadBytes.length);
              try {
                GenericRecord recordCopy = HoodieAvroUtils.bytesToAvro(payloadBytesCopy, schema);
                return new HoodieAvroRecord<>(key, new HoodieAvroPayload(Option.of(recordCopy)));
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            })
        .collect(Collectors.toList());
  }

  private HoodieTableMetaClient initMetaClient(
      HoodieTableType hoodieTableType, TypedProperties keyGenProperties) throws IOException {
    return getMetaClient(keyGenProperties, hoodieTableType, conf);
  }

  private HoodieJavaWriteClient<HoodieAvroPayload> initJavaWriteClient(
      String schema, TypedProperties keyGenProperties) {
    HoodieWriteConfig writeConfig = generateWriteConfig(schema, keyGenProperties);
    HoodieEngineContext context = new HoodieJavaEngineContext(conf);
    return new HoodieJavaWriteClient<>(context, writeConfig);
  }
}