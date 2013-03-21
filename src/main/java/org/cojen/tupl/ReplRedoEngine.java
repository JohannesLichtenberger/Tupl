/*
 *  Copyright 2012-2013 Brian S O'Neill
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

import java.io.IOException;

import java.lang.ref.SoftReference;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import java.util.concurrent.atomic.AtomicInteger;

import static org.cojen.tupl.Utils.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class ReplRedoEngine implements RedoVisitor {
    // FIXME: support encryption

    final ReplicationManager mManager;
    final Database mDb;

    private final ReplRedoWriter mWriter;

    // Maintain soft references to indexes, allowing them to get closed if not
    // used for awhile. Without the soft references, Database maintains only
    // weak references to indexes. They'd get closed too soon.
    private final LHashTable.Obj<SoftReference<Index>> mIndexes;

    private final Latch[] mLatches;
    private final int mLatchesMask;

    private final TxnTable mTransactions;

    private final int mMaxThreads;
    private final AtomicInteger mTotalThreads;
    private final AtomicInteger mIdleThreads;
    private final ConcurrentMap<DecodeTask, Object> mTaskThreadSet;

    // Latch must be held exclusively while reading from decoder.
    private final Latch mDecodeLatch;

    private ReplRedoDecoder mDecoder;

    // Shared latch held when applying operations. Checkpoint suspends all tasks by acquiring
    // an exclusive latch. If any operation fails to be applied, shared latch is still held,
    // preventing checkpoints.
    final Latch mOpLatch;

    // Updated by ReplRedoDecoder with exclusive decode latch and shared op latch. Values can
    // be read with op latch exclusively held, when engine is suspended.
    long mDecodePosition;
    long mDecodeTransactionId;

    /**
     * @param txns recovered transactions; can be null; cleared as a side-effect
     */
    ReplRedoEngine(ReplicationManager manager, int maxThreads,
                   Database db, LHashTable.Obj<Transaction> txns)
    {
        if (maxThreads <= 0) {
            int procs = Runtime.getRuntime().availableProcessors();
            maxThreads = maxThreads == 0 ? procs : (-maxThreads * procs);
            if (maxThreads <= 0) {
                maxThreads = Integer.MAX_VALUE;
            }
        }

        mManager = manager;
        mDb = db;

        mWriter = new ReplRedoWriter(this);

        mIndexes = new LHashTable.Obj<SoftReference<Index>>(16);

        mDecodeLatch = new Latch();
        mOpLatch = new Latch();

        mMaxThreads = maxThreads;
        mTotalThreads = new AtomicInteger();
        mIdleThreads = new AtomicInteger();
        mTaskThreadSet = new ConcurrentHashMap<DecodeTask, Object>(16, 0.75f, 1);

        int latchCount = roundUpPower2(maxThreads * 2);
        if (latchCount <= 0) {
            latchCount = 1 << 30;
        }

        mLatches = new Latch[latchCount];
        mLatchesMask = mLatches.length - 1;
        for (int i=0; i<mLatches.length; i++) {
            mLatches[i] = new Latch();
        }

        final TxnTable txnTable;
        if (txns == null) {
            txnTable = new TxnTable(16);
        } else {
            txnTable = new TxnTable(txns.size());

            txns.traverse(new LHashTable.Visitor
                          <LHashTable.ObjEntry<Transaction>, RuntimeException>()
            {
                public boolean visit(LHashTable.ObjEntry<Transaction> entry) {
                    // Reduce hash collisions.
                    long scrambledTxnId = scramble(entry.key);
                    Latch latch = selectLatch(scrambledTxnId);
                    txnTable.insert(scrambledTxnId).init(entry.value, latch);
                    // Delete entry.
                    return true;
                }
            });
        }

        mTransactions = txnTable;

        // Initialize the decode position early.
        mDecodeLatch.acquireExclusive();
        mDecodePosition = manager.position();
        mDecodeLatch.releaseExclusive();
    }

    public RedoWriter getWriter() {
        return mWriter;
    }

    public void startReceiving(long initialTxnId) {
        mDecodeLatch.acquireExclusive();
        if (mDecoder == null) {
            try {
                mDecoder = new ReplRedoDecoder(this, initialTxnId);
            } catch (Throwable e) {
                mDecodeLatch.releaseExclusive();
                throw rethrow(e);
            }
            nextTask();
        } else {
            mDecodeLatch.releaseExclusive();
        }
    }

    @Override
    public boolean reset() throws IOException {
        // Acquire latch before performing operations wth side-effects.
        mOpLatch.acquireShared();

        // Reset and discard all transactions.
        mTransactions.traverse(new LHashTable.Visitor<TxnEntry, IOException>() {
            public boolean visit(TxnEntry entry) throws IOException {
                Latch latch = entry.latch();
                try {
                    entry.mTxn.reset();
                } finally {
                    latch.releaseExclusive();
                }
                return true;
            }
        });

        // Only release if no exception.
        mOpLatch.releaseShared();

        // Return true and allow RedoDecoder to loop back.
        return true;
    }

    @Override
    public boolean timestamp(long timestamp) {
        return true;
    }

    @Override
    public boolean shutdown(long timestamp) {
        return true;
    }

    @Override
    public boolean close(long timestamp) {
        return true;
    }

    @Override
    public boolean endFile(long timestamp) {
        return true;
    }

    @Override
    public boolean store(long indexId, byte[] key, byte[] value) throws IOException {
        Index ix = getIndex(indexId);

        // Allow side-effect free operations to be performed before acquiring latch.
        mOpLatch.acquireShared();

        // Locks must be acquired in their original order to avoid
        // deadlock, so don't allow another task thread to run yet.
        Locker locker = mDb.mLockManager.localLocker();
        locker.lockExclusive(indexId, key, -1);

        // Allow another task thread to run while operation completes.
        nextTask();

        try {
            ix.store(Transaction.BOGUS, key, value);
        } finally {
            locker.scopeUnlockAll();
        }

        // Only release if no exception.
        mOpLatch.releaseShared();

        // Return false to prevent RedoDecoder from looping back.
        return false;
    }

    @Override
    public boolean storeNoLock(long indexId, byte[] key, byte[] value) throws IOException {
        // A no-lock change is created when using the UNSAFE lock mode. If the
        // application has performed its own locking, consistency can be
        // preserved by locking the index entry. Otherwise, the outcome is
        // unpredictable.

        return store(indexId, key, value);
    }

    @Override
    public boolean dropIndex(long indexId) throws IOException {
        // FIXME: 
        throw null;
    }

    @Override
    public boolean txnEnter(long txnId) throws IOException {
        // Reduce hash collisions.
        long scrambledTxnId = scramble(txnId);
        TxnEntry e = mTransactions.get(scrambledTxnId);

        // Allow side-effect free operations to be performed before acquiring latch.
        mOpLatch.acquireShared();

        if (e == null) {
            Latch latch = selectLatch(scrambledTxnId);
            mTransactions.insert(scrambledTxnId)
                .init(new Transaction(mDb, txnId, LockMode.UPGRADABLE_READ, -1), latch);

            // Only release if no exception.
            mOpLatch.releaseShared();

            return true;
        }

        Latch latch = e.latch();
        try {
            // Cheap operation, so don't let another task thread run.
            e.mTxn.enter();
        } finally {
            latch.releaseExclusive();
        }

        // Only release if no exception.
        mOpLatch.releaseShared();

        // Return true and allow RedoDecoder to loop back.
        return true;
    }

    @Override
    public boolean txnRollback(long txnId) throws IOException {
        TxnEntry e = getTxnEntry(txnId);

        // Allow side-effect free operations to be performed before acquiring latch.
        mOpLatch.acquireShared();

        Latch latch = e.latch();
        try {
            // Allow another task thread to run while operation completes.
            nextTask();

            e.mTxn.exit();
        } finally {
            latch.releaseExclusive();
        }

        // Only release if no exception.
        mOpLatch.releaseShared();

        // Return false to prevent RedoDecoder from looping back.
        return false;
    }

    @Override
    public boolean txnRollbackFinal(long txnId) throws IOException {
        // Acquire latch before performing operations wth side-effects.
        mOpLatch.acquireShared();

        TxnEntry e = removeTxnEntry(txnId);

        Latch latch = e.latch();
        try {
            // Allow another task thread to run while operation completes.
            nextTask();

            e.mTxn.reset();
        } finally {
            latch.releaseExclusive();
        }

        // Only release if no exception.
        mOpLatch.releaseShared();

        // Return false to prevent RedoDecoder from looping back.
        return false;
    }

    @Override
    public boolean txnCommit(long txnId) throws IOException {
        TxnEntry e = getTxnEntry(txnId);

        // Allow side-effect free operations to be performed before acquiring latch.
        mOpLatch.acquireShared();

        Latch latch = e.latch();
        try {
            // Commit is expected to complete quickly, so don't let another
            // task thread run.

            Transaction txn = e.mTxn;
            try {
                txn.commit();
            } finally {
                txn.exit();
            }
        } finally {
            latch.releaseExclusive();
        }

        // Only release if no exception.
        mOpLatch.releaseShared();

        // Return true and allow RedoDecoder to loop back.
        return true;
    }

    @Override
    public boolean txnCommitFinal(long txnId) throws IOException {
        // Acquire latch before performing operations wth side-effects.
        mOpLatch.acquireShared();

        TxnEntry e = removeTxnEntry(txnId);

        Latch latch = e.latch();
        try {
            // Commit is expected to complete quickly, so don't let another
            // task thread run.

            Transaction txn = e.mTxn;
            try {
                txn.commit();
            } finally {
                txn.reset();
            }
        } finally {
            latch.releaseExclusive();
        }

        // Only release if no exception.
        mOpLatch.releaseShared();

        // Return true and allow RedoDecoder to loop back.
        return true;
    }

    @Override
    public boolean txnStore(long txnId, long indexId, byte[] key, byte[] value)
        throws IOException
    {
        Index ix = getIndex(indexId);
        TxnEntry e = getTxnEntry(txnId);

        // Allow side-effect free operations to be performed before acquiring latch.
        mOpLatch.acquireShared();

        Latch latch = e.latch();
        try {
            Transaction txn = e.mTxn;

            // Locks must be acquired in their original order to avoid
            // deadlock, so don't allow another task thread to run yet.
            txn.lockUpgradable(indexId, key, -1);

            // Allow another task thread to run while operation completes.
            nextTask();

            ix.store(txn, key, value);
        } finally {
            latch.releaseExclusive();
        }

        // Only release if no exception.
        mOpLatch.releaseShared();

        // Return false to prevent RedoDecoder from looping back.
        return false;
    }

    @Override
    public boolean txnStoreCommitFinal(long txnId, long indexId, byte[] key, byte[] value)
        throws IOException
    {
        Index ix = getIndex(indexId);
        TxnEntry e = getTxnEntry(txnId);

        // Allow side-effect free operations to be performed before acquiring latch.
        mOpLatch.acquireShared();

        Latch latch = e.latch();
        try {
            Transaction txn = e.mTxn;

            // Locks must be acquired in their original order to avoid
            // deadlock, so don't allow another task thread to run yet.
            txn.lockUpgradable(indexId, key, -1);

            // Allow another task thread to run while operation completes.
            nextTask();

            try {
                ix.store(txn, key, value);
                txn.commit();
            } finally {
                txn.exit();
            }
        } finally {
            latch.releaseExclusive();
        }

        // Only release if no exception.
        mOpLatch.releaseShared();

        // Return false to prevent RedoDecoder from looping back.
        return false;
    }

    /**
     * Launch a task thread to continue processing more redo entries
     * concurrently. Caller must return false from the visitor method, to
     * prevent multiple threads from trying to decode the redo input stream. If
     * thread limit is reached, the remaining task threads continue working.
     *
     * Caller must hold exclusive decode latch, which is released by this method.
     */
    private void nextTask() {
        if (mIdleThreads.get() == 0) {
            int total = mTotalThreads.get();
            if (total < mMaxThreads && mTotalThreads.compareAndSet(total, total + 1)) {
                DecodeTask task;
                try {
                    task = new DecodeTask();
                    task.start();
                } catch (Throwable e) {
                    mDecodeLatch.releaseExclusive();
                    mTotalThreads.decrementAndGet();
                    throw rethrow(e);
                }
                mTaskThreadSet.put(task, this);
            }
        }

        // Allow task thread to proceed.
        mDecodeLatch.releaseExclusive();
    }

    /**
     * Waits for all incoming replication operations to finish and prevents new ones from
     * starting.
     */
    void suspend() {
        mOpLatch.acquireExclusive();
    }

    void resume() {
        mOpLatch.releaseExclusive();
    }

    /**
     * @return TxnEntry with scrambled transaction id
     */
    private TxnEntry getTxnEntry(long txnId) throws IOException {
        long scrambledTxnId = scramble(txnId);
        TxnEntry e = mTransactions.get(scrambledTxnId);
        if (e == null) {
            // TODO: Throw a better exception.
            throw new DatabaseException("Transaction not found: " + txnId);
        }
        return e;
    }

    /**
     * @return TxnEntry with scrambled transaction id
     */
    private TxnEntry removeTxnEntry(long txnId) throws IOException {
        long scrambledTxnId = scramble(txnId);
        TxnEntry e = mTransactions.remove(scrambledTxnId);
        if (e == null) {
            // TODO: Throw a better exception.
            throw new DatabaseException("Transaction not found: " + txnId);
        }
        return e;
    }

    private Index getIndex(long indexId) throws IOException {
        LHashTable.ObjEntry<SoftReference<Index>> entry = mIndexes.get(indexId);
        if (entry != null) {
            Index ix = entry.value.get();
            if (ix != null) {
                return ix;
            }
        }

        Index ix = mDb.anyIndexById(indexId);
        if (ix == null) {
            // TODO: Throw a better exception.
            throw new DatabaseException("Index not found: " + indexId);
        }

        SoftReference<Index> ref = new SoftReference<Index>(ix);
        if (entry == null) {
            mIndexes.insert(indexId).value = ref;
        } else {
            entry.value = ref;
        }

        if (entry != null) {
            // Remove entries for all other cleared references, freeing up memory.
            mIndexes.traverse(new LHashTable.Visitor<
                              LHashTable.ObjEntry<SoftReference<Index>>, RuntimeException>()
            {
                public boolean visit(LHashTable.ObjEntry<SoftReference<Index>> entry) {
                    return entry.value.get() == null;
                }
            });
        }

        return ix;
    }

    private Latch selectLatch(long scrambledTxnId) {
        return mLatches[((int) scrambledTxnId) & mLatchesMask];
    }

    private static final long IDLE_TIMEOUT_NANOS = 5 * 1000000000L;

    /**
     * @return false if thread should exit
     */
    boolean decode() {
        mIdleThreads.incrementAndGet();
        try {
            while (true) {
                try {
                    if (mDecodeLatch.tryAcquireExclusiveNanos(IDLE_TIMEOUT_NANOS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    // Treat as timeout.
                    Thread.interrupted();
                }

                int total = mTotalThreads.get();
                if (total > 1 && mTotalThreads.compareAndSet(total, total - 1)) {
                    return false;
                }
            }
        } finally {
            mIdleThreads.decrementAndGet();
        }

        // At this point, decode latch is held exclusively.

        RedoDecoder decoder = mDecoder;
        if (decoder == null) {
            mDecodeLatch.releaseExclusive();
            return false;
        }

        try {
            if (!decoder.run(this)) {
                return true;
            }
            // End of stream reached, and so local instance is now leader.
            reset();
        } catch (Throwable e) {
            mDecodeLatch.releaseExclusive();
            // FIXME: panic
            e.printStackTrace(System.out);
            return false;
        }

        mDecoder = null;
        mDecodeLatch.releaseExclusive();

        try {
            mWriter.leaderNotify();
        } catch (UnmodifiableReplicaException e) {
            // Should already be receiving.
        } catch (IOException e) {
            // FIXME: log it?
            e.printStackTrace(System.out);
            // A reset op is expected, and so the initial transaction id can be zero.
            startReceiving(0);
        }

        return false;
    }

    private static int cTaskNumber;

    static synchronized long taskNumber() {
        return (++cTaskNumber) & 0xffffffffL;
    }

    class DecodeTask extends Thread {
        DecodeTask() {
            super("ReplicationReceiver-" + taskNumber());
            setDaemon(true);
        }

        public void run() {
            while (ReplRedoEngine.this.decode());
            mTaskThreadSet.remove(this);
        }
    }

    static final class TxnEntry extends LHashTable.Entry<TxnEntry> {
        Transaction mTxn;
        Latch mLatch;

        void init(Transaction txn, Latch latch) {
            mTxn = txn;
            mLatch = latch;
        }

        Latch latch() {
            Latch latch = mLatch;
            latch.acquireExclusive();
            return latch;
        }
    }

    static final class TxnTable extends LHashTable<TxnEntry> {
        TxnTable(int capacity) {
            super(capacity);
        }

        protected TxnEntry newEntry() {
            return new TxnEntry();
        }
    }
}
