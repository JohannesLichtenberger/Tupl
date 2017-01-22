/*
 *  Copyright 2012-2015 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.tupl;

import java.io.Closeable;
import java.io.IOException;

import org.cojen.tupl.util.Latch;

/**
 * Abstract class for active transactions to write into. Redo operations are encoded and
 * buffered by TransactionContext.
 *
 * @author Brian S O'Neill
 * @see RedoDecoder
 */
/*P*/
abstract class RedoWriter extends Latch implements Closeable {
    // Only access while latched. Is accessed by TransactionContext and ReplRedoWriter.
    long mLastTxnId;

    volatile Throwable mCloseCause;

    RedoWriter() {
    }

    final void closeCause(Throwable cause) {
        if (cause != null) {
            acquireExclusive();
            if (mCloseCause == null) {
                mCloseCause = cause;
            }
            releaseExclusive();
        }
    }

    /**
     * Called after redoCommitFinal.
     *
     * @param txn transaction committed
     * @param commitPos highest position to sync (exclusive)
     */
    abstract void txnCommitSync(LocalTransaction txn, long commitPos) throws IOException;

    /**
     * Called after redoCommitFinal.
     *
     * @param pending pending transaction committed
     */
    abstract void txnCommitPending(PendingTxn pending) throws IOException;

    abstract long encoding();

    /**
     * Return a new or existing RedoWriter for a new transaction.
     */
    abstract RedoWriter txnRedoWriter();

    /**
     * Returns true if uncheckpointed redo size is at least the given threshold
     */
    abstract boolean shouldCheckpoint(long sizeThreshold);

    /**
     * Called before checkpointSwitch, to perform any expensive operations like opening a new
     * file. Method must not perform any checkpoint state transition.
     */
    abstract void checkpointPrepare() throws IOException;

    /**
     * With exclusive commit lock held, switch to the previously prepared state, also capturing
     * the checkpoint position and transaction id.
     *
     * @param contexts all contexts which flush into this
     */
    abstract void checkpointSwitch(TransactionContext[] contexts) throws IOException;

    /**
     * Returns the checkpoint number for the first change after the checkpoint switch.
     */
    abstract long checkpointNumber() throws IOException;

    /**
     * Returns the redo position for the first change after the checkpoint switch.
     */
    abstract long checkpointPosition() throws IOException;

    /**
     * Returns the transaction id for the first change after the checkpoint switch, which is
     * later used by recovery. If not needed by recovery, simply return 0.
     */
    abstract long checkpointTransactionId() throws IOException;

    /**
     * Called after checkpointPrepare and exclusive commit lock is released, but checkpoint is
     * aborted due to an exception.
     */
    abstract void checkpointAborted();

    /**
     * Called after exclusive commit lock is released. Dirty pages start flushing as soon as
     * this method returns.
     */
    abstract void checkpointStarted() throws IOException;

    /**
     * Called after all dirty pages have flushed.
     */
    abstract void checkpointFlushed() throws IOException;

    /**
     * Writer can discard all redo data lower than the checkpointed position, which was
     * captured earlier.
     */
    abstract void checkpointFinished() throws IOException;

    /**
     * Negate the identifier if a replica, but leave alone otherwise.
     *
     * @param id new transaction identifier; greater than zero
     */
    long adjustTransactionId(long txnId) {
        // Non-replica by default.
        return txnId;
    }

    /**
     * @param mode requested mode; can be null if not applicable
     * @return actual mode to use
     * @throws UnmodifiableReplicaException if a replica
     */
    abstract DurabilityMode opWriteCheck(DurabilityMode mode) throws IOException;

    /**
     * @return true if all redo operations end with a terminator
     */
    abstract boolean shouldWriteTerminators();

    /**
     * Write to the physical log.
     *
     * @param length never 0
     * @param commit highest commit offset; -1 if none
     * @return highest log position afterwards
     */
    // Caller must hold exclusive latch.
    abstract long write(byte[] bytes, int offset, int length, int commit) throws IOException;

    /**
     * @param enable when enabled, a flush is also performed immediately
     */
    abstract void alwaysFlush(boolean enable) throws IOException;

    /**
     * Durably writes all flushed data.
     *
     * @param metadata true to durably write applicable file system metadata too
     */
    abstract void force(boolean metadata) throws IOException;
}
