/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdds.scm.block;

import org.apache.hadoop.hdds.protocol.proto
    .StorageContainerDatanodeProtocolProtos.ContainerBlocksDeletionACKProto
    .DeleteBlockTransactionResult;
import org.apache.hadoop.hdds.protocol.proto
    .StorageContainerDatanodeProtocolProtos.DeletedBlocksTransaction;
import org.apache.hadoop.hdds.utils.db.Table;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * The DeletedBlockLog is a persisted log in SCM to keep tracking
 * container blocks which are under deletion. It maintains info
 * about under-deletion container blocks that notified by OM,
 * and the state how it is processed.
 */
public interface DeletedBlockLog extends Closeable {

  /**
   * Scan entire log once and returns TXs to DatanodeDeletedBlockTransactions.
   * Once DatanodeDeletedBlockTransactions is full, the scan behavior will
   * stop.
   * @param blockDeletionLimit Maximum number of blocks to fetch
   * @return Mapping from containerId to latest transactionId for the container.
   * @throws IOException
   */
  DatanodeDeletedBlockTransactions getTransactions(int blockDeletionLimit)
      throws IOException, TimeoutException;

  /**
   * Return the failed transactions in the log. A transaction is
   * considered to be failed if it has been sent more than MAX_RETRY limit
   * and its count is reset to -1.
   *
   * @param count Maximum num of returned transactions, if < 0. return all.
   * @param startTxId The least transaction id to start with.
   * @return a list of failed deleted block transactions.
   * @throws IOException
   */
  List<DeletedBlocksTransaction> getFailedTransactions(int count,
      long startTxId) throws IOException;

  /**
   * Increments count for given list of transactions by 1.
   * The log maintains a valid range of counts for each transaction
   * [0, MAX_RETRY]. If exceed this range, resets it to -1 to indicate
   * the transaction is no longer valid.
   *
   * @param txIDs - transaction ID.
   */
  void incrementCount(List<Long> txIDs)
      throws IOException, TimeoutException;


  /**
   * Reset DeletedBlock transaction retry count.
   *
   * @param txIDs transactionId list to be reset
   * @return num of successful reset
   */
  int resetCount(List<Long> txIDs) throws IOException, TimeoutException;

  /**
   * Commits a transaction means to delete all footprints of a transaction
   * from the log. This method doesn't guarantee all transactions can be
   * successfully deleted, it tolerate failures and tries best efforts to.
   *  @param transactionResults - delete block transaction results.
   * @param dnID - ID of datanode which acknowledges the delete block command.
   */
  void commitTransactions(List<DeleteBlockTransactionResult> transactionResults,
      UUID dnID);

  /**
   * Creates block deletion transactions for a set of containers,
   * add into the log and persist them atomically. An object key
   * might be stored in multiple containers and multiple blocks,
   * this API ensures that these updates are done in atomic manner
   * so if any of them fails, the entire operation fails without
   * any updates to the log. Note, this doesn't mean to create only
   * one transaction, it creates multiple transactions (depends on the
   * number of containers) together (on success) or non (on failure).
   *
   * @param containerBlocksMap a map of containerBlocks.
   * @throws IOException
   */
  void addTransactions(Map<Long, List<Long>> containerBlocksMap)
      throws IOException, TimeoutException;

  /**
   * Returns the total number of valid transactions. A transaction is
   * considered to be valid as long as its count is in range [0, MAX_RETRY].
   *
   * @return number of a valid transactions.
   * @throws IOException
   */
  int getNumOfValidTransactions() throws IOException;

  /**
   * Reinitialize the delete log from the db.
   * @param deletedBlocksTXTable delete transaction table
   */
  void reinitialize(Table<Long, DeletedBlocksTransaction> deletedBlocksTXTable);
}
