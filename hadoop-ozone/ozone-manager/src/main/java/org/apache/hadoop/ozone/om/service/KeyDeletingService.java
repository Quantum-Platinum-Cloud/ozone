/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.ozone.om.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.protobuf.ServiceException;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.scm.protocol.ScmBlockLocationProtocol;
import org.apache.hadoop.ozone.common.BlockGroup;
import org.apache.hadoop.ozone.common.DeleteBlockGroupResult;
import org.apache.hadoop.ozone.om.KeyManager;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.helpers.OMRatisHelper;
import org.apache.hadoop.ozone.om.helpers.RepeatedOmKeyInfo;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.DeletedKeys;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.PurgeKeysRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Type;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.hdds.utils.BackgroundService;
import org.apache.hadoop.hdds.utils.BackgroundTask;
import org.apache.hadoop.hdds.utils.BackgroundTaskQueue;
import org.apache.hadoop.hdds.utils.BackgroundTaskResult;
import org.apache.hadoop.hdds.utils.BackgroundTaskResult.EmptyTaskResult;

import com.google.common.annotations.VisibleForTesting;

import static org.apache.hadoop.ozone.OzoneConsts.OM_KEY_PREFIX;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_KEY_DELETING_LIMIT_PER_TASK;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_KEY_DELETING_LIMIT_PER_TASK_DEFAULT;

import org.apache.hadoop.hdds.utils.db.BatchOperation;
import org.apache.hadoop.hdds.utils.db.DBStore;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the background service to delete keys. Scan the metadata of om
 * periodically to get the keys from DeletedTable and ask scm to delete
 * metadata accordingly, if scm returns success for keys, then clean up those
 * keys.
 */
public class KeyDeletingService extends BackgroundService {
  private static final Logger LOG =
      LoggerFactory.getLogger(KeyDeletingService.class);

  // Use only a single thread for KeyDeletion. Multiple threads would read
  // from the same table and can send deletion requests for same key multiple
  // times.
  private static final int KEY_DELETING_CORE_POOL_SIZE = 1;

  private final OzoneManager ozoneManager;
  private final ScmBlockLocationProtocol scmClient;
  private final KeyManager manager;
  private static ClientId clientId = ClientId.randomId();
  private final int keyLimitPerTask;
  private final AtomicLong deletedKeyCount;
  private final AtomicLong runCount;

  public KeyDeletingService(OzoneManager ozoneManager,
      ScmBlockLocationProtocol scmClient,
      KeyManager manager, long serviceInterval,
      long serviceTimeout, ConfigurationSource conf) {
    super("KeyDeletingService", serviceInterval, TimeUnit.MILLISECONDS,
        KEY_DELETING_CORE_POOL_SIZE, serviceTimeout);
    this.ozoneManager = ozoneManager;
    this.scmClient = scmClient;
    this.manager = manager;
    this.keyLimitPerTask = conf.getInt(OZONE_KEY_DELETING_LIMIT_PER_TASK,
        OZONE_KEY_DELETING_LIMIT_PER_TASK_DEFAULT);
    this.deletedKeyCount = new AtomicLong(0);
    this.runCount = new AtomicLong(0);
  }

  /**
   * Returns the number of times this Background service has run.
   *
   * @return Long, run count.
   */
  @VisibleForTesting
  public AtomicLong getRunCount() {
    return runCount;
  }

  /**
   * Returns the number of keys deleted by the background service.
   *
   * @return Long count.
   */
  @VisibleForTesting
  public AtomicLong getDeletedKeyCount() {
    return deletedKeyCount;
  }

  @Override
  public BackgroundTaskQueue getTasks() {
    BackgroundTaskQueue queue = new BackgroundTaskQueue();
    queue.add(new KeyDeletingTask());
    return queue;
  }

  private boolean shouldRun() {
    if (ozoneManager == null) {
      // OzoneManager can be null for testing
      return true;
    }
    return ozoneManager.isLeaderReady();
  }

  private boolean isRatisEnabled() {
    if (ozoneManager == null) {
      return false;
    }
    return ozoneManager.isRatisEnabled();
  }

  /**
   * A key deleting task scans OM DB and looking for a certain number of
   * pending-deletion keys, sends these keys along with their associated blocks
   * to SCM for deletion. Once SCM confirms keys are deleted (once SCM persisted
   * the blocks info in its deletedBlockLog), it removes these keys from the
   * DB.
   */
  private class KeyDeletingTask implements BackgroundTask {

    @Override
    public int getPriority() {
      return 0;
    }

    @Override
    public BackgroundTaskResult call() throws Exception {
      // Check if this is the Leader OM. If not leader, no need to execute this
      // task.
      if (shouldRun()) {
        runCount.incrementAndGet();
        try {
          long startTime = Time.monotonicNow();
          // TODO: [SNAPSHOT] HDDS-7968. Reclaim eligible key blocks in
          //  snapshot's deletedTable when active DB's deletedTable
          //  doesn't have enough entries left.
          //  OM would have to keep track of which snapshot the key is coming
          //  from. And PurgeKeysRequest would have to be adjusted to be able
          //  to operate on snapshot checkpoints.
          List<BlockGroup> keyBlocksList = manager
              .getPendingDeletionKeys(keyLimitPerTask);
          if (keyBlocksList != null && !keyBlocksList.isEmpty()) {
            List<DeleteBlockGroupResult> results =
                scmClient.deleteKeyBlocks(keyBlocksList);
            if (results != null) {
              int delCount;
              if (isRatisEnabled()) {
                delCount = submitPurgeKeysRequest(results);
              } else {
                // TODO: Once HA and non-HA paths are merged, we should have
                //  only one code path here. Purge keys should go through an
                //  OMRequest model.
                delCount = deleteAllKeys(results);
              }
              if (LOG.isDebugEnabled()) {
                LOG.debug("Number of keys deleted: {}, elapsed time: {}ms",
                    delCount, Time.monotonicNow() - startTime);
              }
              deletedKeyCount.addAndGet(delCount);
            }
          }
        } catch (IOException e) {
          LOG.error("Error while running delete keys background task. Will " +
              "retry at next run.", e);
        }
      }
      // By design, no one cares about the results of this call back.
      return EmptyTaskResult.newResult();
    }

    /**
     * Deletes all the keys that SCM has acknowledged and queued for delete.
     *
     * @param results DeleteBlockGroups returned by SCM.
     * @throws IOException      on Error
     */
    private int deleteAllKeys(List<DeleteBlockGroupResult> results)
        throws IOException {
      Table<String, RepeatedOmKeyInfo> deletedTable =
          manager.getMetadataManager().getDeletedTable();
      DBStore store = manager.getMetadataManager().getStore();

      // Put all keys to delete in a single transaction and call for delete.
      int deletedCount = 0;
      try (BatchOperation writeBatch = store.initBatchOperation()) {
        for (DeleteBlockGroupResult result : results) {
          if (result.isSuccess()) {
            // Purge key from OM DB.
            deletedTable.deleteWithBatch(writeBatch,
                result.getObjectKey());
            if (LOG.isDebugEnabled()) {
              LOG.debug("Key {} deleted from OM DB", result.getObjectKey());
            }
            deletedCount++;
          }
        }
        // Write a single transaction for delete.
        store.commitBatchOperation(writeBatch);
      }
      return deletedCount;
    }

    /**
     * Submits PurgeKeys request for the keys whose blocks have been deleted
     * by SCM.
     * @param results DeleteBlockGroups returned by SCM.
     */
    public int submitPurgeKeysRequest(List<DeleteBlockGroupResult> results) {
      Map<Pair<String, String>, List<String>> purgeKeysMapPerBucket =
          new HashMap<>();

      // Put all keys to be purged in a list
      int deletedCount = 0;
      for (DeleteBlockGroupResult result : results) {
        if (result.isSuccess()) {
          // Add key to PurgeKeys list.
          String deletedKey = result.getObjectKey();
          // Parse Volume and BucketName
          addToMap(purgeKeysMapPerBucket, deletedKey);
          if (LOG.isDebugEnabled()) {
            LOG.debug("Key {} set to be purged from OM DB", deletedKey);
          }
          deletedCount++;
        }
      }

      PurgeKeysRequest.Builder purgeKeysRequest = PurgeKeysRequest.newBuilder();

      // Add keys to PurgeKeysRequest bucket wise.
      for (Map.Entry<Pair<String, String>, List<String>> entry :
          purgeKeysMapPerBucket.entrySet()) {
        Pair<String, String> volumeBucketPair = entry.getKey();
        DeletedKeys deletedKeysInBucket = DeletedKeys.newBuilder()
            .setVolumeName(volumeBucketPair.getLeft())
            .setBucketName(volumeBucketPair.getRight())
            .addAllKeys(entry.getValue())
            .build();
        purgeKeysRequest.addDeletedKeys(deletedKeysInBucket);
      }

      OMRequest omRequest = OMRequest.newBuilder()
          .setCmdType(Type.PurgeKeys)
          .setPurgeKeysRequest(purgeKeysRequest)
          .setClientId(clientId.toString())
          .build();

      // Submit PurgeKeys request to OM
      try {
        RaftClientRequest raftClientRequest =
            createRaftClientRequestForPurge(omRequest);
        ozoneManager.getOmRatisServer().submitRequest(omRequest,
            raftClientRequest);
      } catch (ServiceException e) {
        LOG.error("PurgeKey request failed. Will retry at next run.");
        return 0;
      }

      return deletedCount;
    }
  }

  private RaftClientRequest createRaftClientRequestForPurge(
      OMRequest omRequest) {
    return RaftClientRequest.newBuilder()
        .setClientId(clientId)
        .setServerId(ozoneManager.getOmRatisServer().getRaftPeerId())
        .setGroupId(ozoneManager.getOmRatisServer().getRaftGroupId())
        .setCallId(runCount.get())
        .setMessage(
            Message.valueOf(
                OMRatisHelper.convertRequestToByteString(omRequest)))
        .setType(RaftClientRequest.writeRequestType())
        .build();
  }

  /**
   * Parse Volume and Bucket Name from ObjectKey and add it to given map of
   * keys to be purged per bucket.
   */
  private void addToMap(Map<Pair<String, String>, List<String>> map,
      String objectKey) {
    // Parse volume and bucket name
    String[] split = objectKey.split(OM_KEY_PREFIX);
    Preconditions.assertTrue(split.length > 3, "Volume and/or Bucket Name " +
        "missing from Key Name.");
    Pair<String, String> volumeBucketPair = Pair.of(split[1], split[2]);
    if (!map.containsKey(volumeBucketPair)) {
      map.put(volumeBucketPair, new ArrayList<>());
    }
    map.get(volumeBucketPair).add(objectKey);
  }
}
