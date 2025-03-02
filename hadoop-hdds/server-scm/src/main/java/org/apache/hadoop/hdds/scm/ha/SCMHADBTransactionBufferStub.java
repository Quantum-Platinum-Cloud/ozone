/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.hdds.scm.ha;

import org.apache.hadoop.hdds.utils.TransactionInfo;
import org.apache.hadoop.hdds.utils.db.BatchOperation;
import org.apache.hadoop.hdds.utils.db.DBStore;
import org.apache.hadoop.hdds.utils.db.RDBBatchOperation;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.ratis.statemachine.SnapshotInfo;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// TODO: Move this class to test package after fixing Recon
/**
 * SCMHADBTransactionBuffer implementation for Recon and testing.
 */
public class SCMHADBTransactionBufferStub implements SCMHADBTransactionBuffer {
  private DBStore dbStore;
  private BatchOperation currentBatchOperation;
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

  public SCMHADBTransactionBufferStub() {
  }

  public SCMHADBTransactionBufferStub(DBStore store) {
    this.dbStore = store;
  }

  private BatchOperation getCurrentBatchOperation() {
    if (currentBatchOperation == null) {
      if (dbStore != null) {
        currentBatchOperation = dbStore.initBatchOperation();
      } else {
        currentBatchOperation = new RDBBatchOperation();
      }
    }
    return currentBatchOperation;
  }

  @Override
  public <KEY, VALUE> void addToBuffer(
      Table<KEY, VALUE> table, KEY key, VALUE value) throws IOException {
    rwLock.readLock().lock();
    try {
      table.putWithBatch(getCurrentBatchOperation(), key, value);
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public <KEY, VALUE> void removeFromBuffer(Table<KEY, VALUE> table, KEY key)
      throws IOException {
    rwLock.readLock().lock();
    try {
      table.deleteWithBatch(getCurrentBatchOperation(), key);
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public void updateLatestTrxInfo(TransactionInfo info) {

  }

  @Override
  public TransactionInfo getLatestTrxInfo() {
    return null;
  }

  @Override
  public SnapshotInfo getLatestSnapshot() {
    return null;
  }

  @Override
  public void setLatestSnapshot(SnapshotInfo latestSnapshot) {

  }

  @Override
  public void flush() throws IOException {
    if (dbStore != null) {
      rwLock.writeLock().lock();
      try {
        dbStore.commitBatchOperation(getCurrentBatchOperation());
        currentBatchOperation.close();
        currentBatchOperation = null;
      } finally {
        rwLock.writeLock().unlock();
      }
    }
  }

  @Override
  public void init() throws IOException {

  }

  @Override
  public void close() throws IOException {
    flush();
  }
}
