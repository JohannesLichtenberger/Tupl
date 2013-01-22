/*
 *  Copyright 2011-2013 Brian S O'Neill
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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.io.Writer;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;

import java.math.BigInteger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import java.util.concurrent.locks.Lock;

import static org.cojen.tupl.Node.*;
import static org.cojen.tupl.Utils.*;

/**
 * Main database class, containing a collection of transactional indexes. Call
 * {@link #open open} to obtain a Database instance. Examples:
 *
 * <p>Open a non-durable database, limited to a max size of 100MB:
 *
 * <pre>
 * DatabaseConfig config = new DatabaseConfig().maxCacheSize(100_000_000);
 * Database db = Database.open(config);
 * </pre>
 *
 * <p>Open a regular database, setting the minimum cache size to ensure enough
 * memory is initially available. A weak {@link DurabilityMode durability mode}
 * offers the best transactional commit performance.
 *
 * <pre>
 * DatabaseConfig config = new DatabaseConfig()
 *    .baseFilePath("/var/lib/tupl")
 *    .minCacheSize(100_000_000)
 *    .durabilityMode(DurabilityMode.NO_FLUSH);
 *
 * Database db = Database.open(config);
 * </pre>
 *
 * <p>The following files are created by the above example:
 *
 * <ul>
 * <li><code>/var/lib/tupl.db</code> &ndash; primary data file
 * <li><code>/var/lib/tupl.info</code> &ndash; text file describing the database configuration
 * <li><code>/var/lib/tupl.lock</code> &ndash; lock file to ensure that at most one process can have the database open
 * <li><code>/var/lib/tupl.redo.0</code> &ndash; first transaction redo log file
 * </ul>
 *
 * <p>New redo log files are created by {@link #checkpoint checkpoints}, which
 * also delete the old files. When {@link #beginSnapshot snapshots} are in
 * progress, one or more numbered temporary files are created. For example:
 * <code>/var/lib/tupl.temp.123</code>.
 *
 * @author Brian S O'Neill
 * @see DatabaseConfig
 */
public final class Database extends CauseCloseable {
    private static final int DEFAULT_CACHED_NODES = 1000;
    // +2 for registry and key map root nodes, +1 for one user index, and +2
    // for usage list to function correctly. It always assumes that the least
    // recently used node points to a valid, more recently used node.
    private static final int MIN_CACHED_NODES = 5;

    // Approximate byte overhead per node. Influenced by many factors,
    // including pointer size and child node references. This estimate assumes
    // 32-bit pointers.
    private static final int NODE_OVERHEAD = 100;

    private static int nodeCountFromBytes(long bytes, int pageSize) {
        if (bytes <= 0) {
            return 0;
        }
        pageSize += NODE_OVERHEAD;
        bytes += pageSize - 1;
        if (bytes <= 0) {
            // Overflow.
            return Integer.MAX_VALUE;
        }
        long count = bytes / pageSize;
        return count <= Integer.MAX_VALUE ? (int) count : Integer.MAX_VALUE;
    }

    private static long byteCountFromNodes(int nodes, int pageSize) {
        return nodes * (long) (pageSize + NODE_OVERHEAD);
    }

    private static final int ENCODING_VERSION = 20130112;

    private static final int I_ENCODING_VERSION        = 0;
    private static final int I_ROOT_PAGE_ID            = I_ENCODING_VERSION + 4;
    private static final int I_MASTER_UNDO_LOG_PAGE_ID = I_ROOT_PAGE_ID + 8;
    private static final int I_TRANSACTION_ID          = I_MASTER_UNDO_LOG_PAGE_ID + 8;
    private static final int I_REDO_POSITION           = I_TRANSACTION_ID + 8;
    private static final int I_REDO_TXN_ID             = I_REDO_POSITION + 8;
    private static final int HEADER_SIZE               = I_REDO_TXN_ID + 8;

    private static final int DEFAULT_PAGE_SIZE = 4096;
    private static final int MINIMUM_PAGE_SIZE = 512;
    private static final int MAXIMUM_PAGE_SIZE = 65536;

    private static final int OPEN_REGULAR = 0, OPEN_DESTROY = 1, OPEN_TEMP = 2;

    private final EventListener mEventListener;

    private final LockedFile mLockFile;

    final DurabilityMode mDurabilityMode;
    final long mDefaultLockTimeoutNanos;
    final LockManager mLockManager;
    final RedoWriter mRedoWriter;
    final PageDb mPageDb;

    private final BufferPool mSpareBufferPool;

    private final Latch mUsageLatch;
    private int mMaxNodeCount;
    private int mNodeCount;
    private Node mMostRecentlyUsed;
    private Node mLeastRecentlyUsed;

    private final Lock mSharedCommitLock;

    // Is either CACHED_DIRTY_0 or CACHED_DIRTY_1. Access is guarded by commit lock.
    private byte mCommitState;

    // Is false for empty databases which have never checkpointed.
    private volatile boolean mHasCheckpointed = true;

    // Typically opposite of mCommitState, or negative if checkpoint is not in
    // progress. Indicates which nodes are being flushed by the checkpoint.
    private volatile int mCheckpointFlushState = CHECKPOINT_NOT_FLUSHING;

    private static final int CHECKPOINT_FLUSH_PREPARE = -2, CHECKPOINT_NOT_FLUSHING = -1;

    // The root tree, which maps tree ids to other tree root node ids.
    private final Tree mRegistry;

    static final byte KEY_TYPE_INDEX_NAME   = 0; // prefix for name to id mapping
    static final byte KEY_TYPE_INDEX_ID     = 1; // prefix for id to name mapping
    static final byte KEY_TYPE_TREE_ID_MASK = 2; // full key for random tree id mask
    static final byte KEY_TYPE_NEXT_TREE_ID = 3; // full key for tree id sequence

    // Various mappings, defined by KEY_TYPE_ fields.
    private final Tree mRegistryKeyMap;

    private final Latch mOpenTreesLatch;
    // Maps tree names to open trees.
    private final Map<byte[], TreeRef> mOpenTrees;
    private final LHashTable.Obj<TreeRef> mOpenTreesById;
    private final ReferenceQueue<Tree> mOpenTreesRefQueue;

    // Strong references to all trees opened during recovery. Not critical, but
    // it keeps the trees from being closed and re-opened automatically by
    // garbage collection. Access to this collection is thread-safe.
    private List<Tree> mRecoveredTrees;

    private final PageAllocator mAllocator;

    private final FragmentCache mFragmentCache;
    final int mMaxFragmentedEntrySize;

    // Fragmented values which are transactionally deleted go here.
    private volatile FragmentedTrash mFragmentedTrash;

    private final Object mTxnIdLock = new Object();
    // The following fields are guarded by mTxnIdLock.
    private long mTxnId;
    private UndoLog mTopUndoLog;
    private int mUndoLogCount;

    private final Object mCheckpointLock = new Object();

    private long mLastCheckpointNanos;

    private volatile Checkpointer mCheckpointer;

    private final TempFileManager mTempFileManager;

    volatile boolean mClosed;
    volatile Throwable mClosedCause;

    private static final AtomicReferenceFieldUpdater<Database, Throwable> cClosedCauseUpdater =
        AtomicReferenceFieldUpdater.newUpdater(Database.class, Throwable.class, "mClosedCause");

    /**
     * Open a database, creating it if necessary.
     */
    public static Database open(DatabaseConfig config) throws IOException {
        config = config.clone();
        Database db = new Database(config, OPEN_REGULAR);
        db.startCheckpointer(config);
        return db;
    }

    /**
     * Delete the contents of an existing database, and replace it with an
     * empty one. When using a raw block device for the data file, this method
     * must be used to format it.
     */
    public static Database destroy(DatabaseConfig config) throws IOException {
        config = config.clone();
        if (config.mReadOnly) {
            throw new IllegalArgumentException("Cannot destroy read-only database");
        }
        Database db = new Database(config, OPEN_DESTROY);
        db.startCheckpointer(config);
        return db;
    }

    /**
     * @param config base file is set as a side-effect
     */
    static Tree openTemp(TempFileManager tfm, DatabaseConfig config) throws IOException {
        File file = tfm.createTempFile();
        config.baseFile(file);
        config.dataFile(file);
        config.createFilePath(false);
        config.durabilityMode(DurabilityMode.NO_FLUSH);
        Database db = new Database(config, OPEN_TEMP);
        tfm.register(file, db);
        db.mCheckpointer = new Checkpointer(db, config);
        db.mCheckpointer.start();
        return db.mRegistry;
    }

    /**
     * @param config unshared config
     */
    private Database(DatabaseConfig config, int openMode) throws IOException {
        config.mEventListener = mEventListener = SafeEventListener.makeSafe(config.mEventListener);

        final File baseFile = config.mBaseFile;
        final File[] dataFiles = config.dataFiles();

        int pageSize = config.mPageSize;
        if (pageSize <= 0) {
            config.pageSize(pageSize = DEFAULT_PAGE_SIZE);
        } else if (pageSize < MINIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException
                ("Page size is too small: " + pageSize + " < " + MINIMUM_PAGE_SIZE);
        } else if (pageSize > MAXIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException
                ("Page size is too large: " + pageSize + " > " + MAXIMUM_PAGE_SIZE);
        }

        int minCache, maxCache;
        cacheSize: {
            long minCachedBytes = Math.max(0, config.mMinCachedBytes);
            long maxCachedBytes = Math.max(0, config.mMaxCachedBytes);

            if (maxCachedBytes == 0) {
                maxCachedBytes = minCachedBytes;
                if (maxCachedBytes == 0) {
                    minCache = maxCache = DEFAULT_CACHED_NODES;
                    break cacheSize;
                }
            }

            if (minCachedBytes > maxCachedBytes) {
                throw new IllegalArgumentException
                    ("Minimum cache size exceeds maximum: " +
                     minCachedBytes + " > " + maxCachedBytes);
            }

            minCache = nodeCountFromBytes(minCachedBytes, pageSize);
            maxCache = nodeCountFromBytes(maxCachedBytes, pageSize);

            minCache = Math.max(MIN_CACHED_NODES, minCache);
            maxCache = Math.max(MIN_CACHED_NODES, maxCache);
        }

        // Update config such that info file is correct.
        config.mMinCachedBytes = byteCountFromNodes(minCache, pageSize);
        config.mMaxCachedBytes = byteCountFromNodes(maxCache, pageSize);

        mUsageLatch = new Latch();
        mMaxNodeCount = maxCache;

        mDurabilityMode = config.mDurabilityMode;
        mDefaultLockTimeoutNanos = config.mLockTimeoutNanos;
        mLockManager = new LockManager(mDefaultLockTimeoutNanos);

        if (baseFile != null && !config.mReadOnly && config.mMkdirs) {
            baseFile.getParentFile().mkdirs();
            for (File f : dataFiles) {
                f.getParentFile().mkdirs();
            }
        }

        // Create lock file and write info file of properties.
        if (baseFile == null || openMode == OPEN_TEMP) {
            mLockFile = null;
        } else {
            mLockFile = new LockedFile(new File(baseFile.getPath() + ".lock"), config.mReadOnly);
            if (!config.mReadOnly) {
                File infoFile = new File(baseFile.getPath() + ".info");
                Writer w = new BufferedWriter
                    (new OutputStreamWriter(new FileOutputStream(infoFile), "UTF-8"));
                try {
                    config.writeInfo(w);
                } finally {
                    w.close();
                }
            }
        }

        EnumSet<OpenOption> options = config.createOpenOptions();
        if (baseFile != null && openMode == OPEN_DESTROY) {
            // Delete old redo log files.
            deleteNumberedFiles(baseFile, ".redo.");
        }

        if (dataFiles == null) {
            mPageDb = new NonPageDb(pageSize);
        } else {
            mPageDb = new DurablePageDb
                (pageSize, dataFiles, options, config.mCrypto, openMode == OPEN_DESTROY);
        }

        mSharedCommitLock = mPageDb.sharedCommitLock();

        try {
            // Pre-allocate nodes. They are automatically added to the usage
            // list, and so nothing special needs to be done to allow them to
            // get used. Since the initial state is clean, evicting these
            // nodes does nothing.

            long cacheInitStart = 0;
            if (mEventListener != null) {
                mEventListener.notify(EventType.CACHE_INIT_BEGIN,
                                      "Initializing %1$d cached nodes", minCache);
                cacheInitStart = System.nanoTime();
            }

            try {
                for (int i=minCache; --i>=0; ) {
                    allocLatchedNode(true).releaseExclusive();
                }
            } catch (OutOfMemoryError e) {
                mMostRecentlyUsed = null;
                mLeastRecentlyUsed = null;

                throw new OutOfMemoryError
                    ("Unable to allocate the minimum required number of cached nodes: " +
                     minCache);
            }

            if (mEventListener != null) {
                double duration = (System.nanoTime() - cacheInitStart) / 1000000000.0;
                mEventListener.notify(EventType.CACHE_INIT_COMPLETE,
                                      "Cache initialization completed in %1$1.3f seconds",
                                      duration, TimeUnit.SECONDS);
            }

            int spareBufferCount = Runtime.getRuntime().availableProcessors();
            mSpareBufferPool = new BufferPool(mPageDb.pageSize(), spareBufferCount);

            mSharedCommitLock.lock();
            try {
                mCommitState = CACHED_DIRTY_0;
            } finally {
                mSharedCommitLock.unlock();
            }

            byte[] header = new byte[HEADER_SIZE];
            mPageDb.readExtraCommitData(header);

            mRegistry = new Tree(this, Tree.REGISTRY_ID, null, null, loadRegistryRoot(header));

            mOpenTreesLatch = new Latch();
            if (openMode == OPEN_TEMP) {
                mOpenTrees = Collections.emptyMap();
                mOpenTreesById = new LHashTable.Obj<TreeRef>(0);
                mOpenTreesRefQueue = null;
            } else {
                mOpenTrees = new TreeMap<byte[], TreeRef>(KeyComparator.THE);
                mOpenTreesById = new LHashTable.Obj<TreeRef>(16);
                mOpenTreesRefQueue = new ReferenceQueue<Tree>();
            }

            synchronized (mTxnIdLock) {
                mTxnId = readLongLE(header, I_TRANSACTION_ID);
            }

            long redoPos = readLongLE(header, I_REDO_POSITION);
            long redoTxnId = readLongLE(header, I_REDO_TXN_ID);

            if (openMode == OPEN_TEMP) {
                mRegistryKeyMap = null;
            } else {
                mRegistryKeyMap = openInternalTree(Tree.REGISTRY_KEY_MAP_ID, true);
            }

            mAllocator = new PageAllocator(mPageDb);

            if (baseFile == null) {
                // Non-durable database never evicts anything.
                mFragmentCache = new FragmentMap();
            } else {
                // Regular database evicts automatically.
                mFragmentCache = new FragmentCache(this, mMaxNodeCount);
            }

            if (openMode != OPEN_TEMP) {
                Tree tree = openInternalTree(Tree.FRAGMENTED_TRASH_ID, false);
                if (tree != null) {
                    mFragmentedTrash = new FragmentedTrash(tree);
                }
            }

            // Limit maximum fragmented entry size to guarantee that 2 entries
            // fit. Each also requires 2 bytes for pointer and up to 3 bytes
            // for value length field.
            mMaxFragmentedEntrySize = (pageSize - Node.TN_HEADER_SIZE - (2 + 3 + 2 + 3)) >> 1;

            long recoveryStart = 0;
            if (baseFile == null || openMode == OPEN_TEMP) {
                mRedoWriter = null;
            } else {
                // Perform recovery by examining redo and undo logs.

                if (mEventListener != null) {
                    mEventListener.notify(EventType.RECOVERY_BEGIN, "Database recovery begin");
                    recoveryStart = System.nanoTime();
                }

                // Keep all the trees open during recovery.
                // FIXME: Only when non-replicated.
                mRecoveredTrees = Collections.synchronizedList(new ArrayList<Tree>());

                LHashTable.Obj<Transaction> txns = new LHashTable.Obj<Transaction>(16);

                {
                    long masterNodeId = readLongLE(header, I_MASTER_UNDO_LOG_PAGE_ID);
                    if (masterNodeId != 0) {
                        if (mEventListener != null) {
                            mEventListener.notify
                                (EventType.RECOVERY_LOAD_UNDO_LOGS, "Loading undo logs");
                        }
                        UndoLog.recoverMasterUndoLog(this, masterNodeId)
                            .recoverTransactions(txns, LockMode.UPGRADABLE_READ, 0);
                    }
                }

                RedoRecovery recovery;
                {
                    ReplicationManager rm = config.mReplManager;
                    if (rm == null) {
                        recovery = new RedoLogRecovery();
                    } else {
                        recovery = new ReplRedoRecovery(rm);
                    }
                }

                boolean doCheckpoint = recovery.recover(this, config, redoPos, redoTxnId, txns);

                // Avoid re-using transaction ids used by recovery.
                redoTxnId = recovery.highestTxnId();
                if (redoTxnId != 0) {
                    synchronized (mTxnIdLock) {
                        // Subtract for modulo comparison.
                        if (mTxnId == 0 || (redoTxnId - mTxnId) > 0) {
                            mTxnId = redoTxnId;
                        }
                    }
                }

                if (txns.size() > 0) {
                    // Rollback or truncate all remaining transactions. They
                    // were never explicitly rolled back, or they were
                    // committed but not cleaned up.

                    if (mEventListener != null) {
                        mEventListener.notify
                            (EventType.RECOVERY_PROCESS_REMAINING,
                             "Processing remaining transactions");
                    }

                    txns.traverse(new LHashTable.Visitor
                                  <LHashTable.ObjEntry<Transaction>, IOException>()
                    {
                        public boolean visit(LHashTable.ObjEntry<Transaction> entry)
                            throws IOException
                        {
                            entry.value.recoveryCleanup();
                            return false;
                        }
                    });

                    doCheckpoint = true;
                }

                mRedoWriter = recovery.newWriter();

                if (doCheckpoint) {
                    checkpoint(true, 0, 0);
                    // Only cleanup after successful checkpoint.
                    recovery.cleanup();
                }

                mRecoveredTrees = null;
            }

            // Delete lingering fragmented values after undo logs have been
            // processed, ensuring deletes were committed.
            // FIXME: Only when non-replicated. Empty trash after replica reset.
            if (mFragmentedTrash != null) {
                if (mEventListener != null) {
                    mEventListener.notify(EventType.RECOVERY_DELETE_FRAGMENTS,
                                          "Deleting unused large fragments");
                }
                
                if (mFragmentedTrash.emptyAllTrash()) {
                    checkpoint(false, 0, 0);
                }
            }

            if (baseFile == null || openMode == OPEN_TEMP) {
                mTempFileManager = null;
            } else {
                mTempFileManager = new TempFileManager(baseFile);
            }

            if (mRedoWriter != null && mEventListener != null) {
                double duration = (System.nanoTime() - recoveryStart) / 1000000000.0;
                mEventListener.notify(EventType.RECOVERY_COMPLETE,
                                      "Recovery completed in %1$1.3f seconds",
                                      duration, TimeUnit.SECONDS);
            }
        } catch (Throwable e) {
            closeQuietly(null, this, e);
            throw rethrow(e);
        }
    }

    private void startCheckpointer(DatabaseConfig config) {
        if (mRedoWriter == null && mTempFileManager == null) {
            // Nothing is durable and nothing to ever clean up 
            return;
        }

        mCheckpointer = new Checkpointer(this, config);

        // Register objects to automatically shutdown.
        mCheckpointer.register(mRedoWriter);
        mCheckpointer.register(mTempFileManager);

        mCheckpointer.start();
    }

    /*
    void trace() throws IOException {
        int[] inBuckets = new int[16];
        int[] leafBuckets = new int[16];

        java.util.BitSet pages = mPageDb.tracePages();
        mRegistry.mRoot.tracePages(this, pages, inBuckets, leafBuckets);
        mRegistryKeyMap.mRoot.tracePages(this, pages, inBuckets, leafBuckets);

        Cursor all = allIndexes();
        for (all.first(); all.key() != null; all.next()) {
            Index ix = indexById(all.value());
            System.out.println(ix.getNameString());

            Cursor c = ix.newCursor(Transaction.BOGUS);
            c.first();
            System.out.println("height: " + ((TreeCursor) c).height());
            c.reset();

            ((Tree) ix).mRoot.tracePages(this, pages, inBuckets, leafBuckets);
            System.out.println("unaccounted: " + pages.cardinality());
            System.out.println("internal avail: " + Arrays.toString(inBuckets));
            System.out.println("leaf avail: " + Arrays.toString(leafBuckets));
        }
        all.reset();

        System.out.println("unaccounted: " + pages.cardinality());
        System.out.println(pages);
    }
    */

    /**
     * Returns the given named index, returning null if not found.
     *
     * @return shared Index instance; null if not found
     */
    public Index findIndex(byte[] name) throws IOException {
        return openIndex(name.clone(), false);
    }

    /**
     * Returns the given named index, returning null if not found. Name is UTF-8
     * encoded.
     *
     * @return shared Index instance; null if not found
     */
    public Index findIndex(String name) throws IOException {
        return openIndex(name.getBytes("UTF-8"), false);
    }

    /**
     * Returns the given named index, creating it if necessary.
     *
     * @return shared Index instance
     */
    public Index openIndex(byte[] name) throws IOException {
        return openIndex(name.clone(), true);
    }

    /**
     * Returns the given named index, creating it if necessary. Name is UTF-8
     * encoded.
     *
     * @return shared Index instance
     */
    public Index openIndex(String name) throws IOException {
        return openIndex(name.getBytes("UTF-8"), true);
    }

    /**
     * Returns an index by its identifier, returning null if not found.
     *
     * @throws IllegalArgumentException if id is reserved
     */
    public Index indexById(long id) throws IOException {
        if (Tree.isInternal(id)) {
            throw new IllegalArgumentException("Invalid id: " + id);
        }

        Index index;

        final Lock commitLock = sharedCommitLock();
        commitLock.lock();
        try {
            if ((index = lookupIndexById(id)) != null) {
                return index;
            }

            byte[] idKey = new byte[9];
            idKey[0] = KEY_TYPE_INDEX_ID;
            writeLongBE(idKey, 1, id);

            byte[] name = mRegistryKeyMap.load(null, idKey);

            if (name == null) {
                return null;
            }

            index = openIndex(name, false);
        } catch (Throwable e) {
            if (e instanceof DatabaseException && ((DatabaseException) e).isRecoverable()) {
                throw (DatabaseException) e;
            }
            throw closeOnFailure(this, e);
        } finally {
            commitLock.unlock();
        }

        if (index == null) {
            // Registry needs to be repaired to fix this.
            throw new DatabaseException("Unable to find index in registry");
        }

        return index;
    }

    /**
     * @return null if index is not open
     */
    private Tree lookupIndexById(long id) {
        mOpenTreesLatch.acquireShared();
        try {
            LHashTable.ObjEntry<TreeRef> entry = mOpenTreesById.get(id);
            return entry == null ? null : entry.value.get();
        } finally {
            mOpenTreesLatch.releaseShared();
        }
    }

    /**
     * Returns an index by its identifier, returning null if not found.
     *
     * @param id big-endian encoded long integer
     * @throws IllegalArgumentException if id is malformed or reserved
     */
    public Index indexById(byte[] id) throws IOException {
        if (id.length != 8) {
            throw new IllegalArgumentException("Expected an 8 byte identifier: " + id.length);
        }
        return indexById(readLongBE(id, 0));
    }

    /**
     * Allows access to internal indexes which can use the redo log.
     */
    Index anyIndexById(long id) throws IOException {
        if (id == Tree.REGISTRY_KEY_MAP_ID) {
            return mRegistryKeyMap;
        } else if (id == Tree.FRAGMENTED_TRASH_ID) {
            return fragmentedTrash().mTrash;
        }
        return indexById(id);
    }

    /**
     * Returns a Cursor which maps all available index names to
     * identifiers. Identifiers are long integers, big-endian encoded.
     * Attempting to store anything into the Cursor causes an {@link
     * UnmodifiableViewException} to be thrown.
     */
    public Cursor allIndexes() throws IOException {
        return new IndexesCursor(mRegistryKeyMap.newCursor(null));
    }

    /**
     * Returns a new Transaction with the {@link DatabaseConfig#durabilityMode default}
     * durability mode.
     */
    public Transaction newTransaction() {
        return doNewTransaction(mDurabilityMode);
    }

    /**
     * Returns a new Transaction with the given durability mode. If null, the
     * {@link DatabaseConfig#durabilityMode default} is used.
     */
    public Transaction newTransaction(DurabilityMode durabilityMode) {
        return doNewTransaction(durabilityMode == null ? mDurabilityMode : durabilityMode);
    }

    private Transaction doNewTransaction(DurabilityMode durabilityMode) {
        return new Transaction
            (this, durabilityMode, LockMode.UPGRADABLE_READ, mDefaultLockTimeoutNanos);
    }

    /**
     * Convenience method which returns a transaction intended for locking, and
     * not for making modifications.
     */
    Transaction newLockTransaction() {
        return new Transaction(this, DurabilityMode.NO_REDO, LockMode.UPGRADABLE_READ, -1);
    }

    /**
     * Caller must hold commit lock. This ensures that highest transaction id
     * is persisted correctly by checkpoint.
     */
    void register(UndoLog undo) {
        synchronized (mTxnIdLock) {
            UndoLog top = mTopUndoLog;
            if (top != null) {
                undo.mPrev = top;
                top.mNext = undo;
            }
            mTopUndoLog = undo;
            mUndoLogCount++;
        }
    }

    /**
     * Caller must hold commit lock. This ensures that highest transaction id
     * is persisted correctly by checkpoint.
     *
     * @return non-zero transaction id
     */
    long nextTransactionId() throws IOException {
        long txnId;
        do {
            synchronized (mTxnIdLock) {
                txnId = ++mTxnId;
            }
        } while (txnId == 0);
        return txnId;
    }

    /**
     * Called only by UndoLog.
     */
    void unregister(UndoLog log) {
        synchronized (mTxnIdLock) {
            UndoLog prev = log.mPrev;
            UndoLog next = log.mNext;
            if (prev != null) {
                prev.mNext = next;
                log.mPrev = null;
            }
            if (next != null) {
                next.mPrev = prev;
                log.mNext = null;
            } else if (log == mTopUndoLog) {
                mTopUndoLog = prev;
            }
            mUndoLogCount--;
        }
    }

    /**
     * Preallocates pages for immediate use. The actual amount allocated
     * varies, depending on the amount of free pages already available.
     *
     * @return actual amount allocated
     */
    public long preallocate(long bytes) throws IOException {
        if (!mClosed && mPageDb instanceof DurablePageDb) {
            int pageSize = pageSize();
            long pageCount = (bytes + pageSize - 1) / pageSize;
            if (pageCount > 0) {
                pageCount = mPageDb.allocatePages(pageCount);
                if (pageCount > 0) {
                    try {
                        checkpoint(true, 0, 0);
                    } catch (Throwable e) {
                        closeQuietly(null, this, e);
                        throw rethrow(e);
                    }
                }
                return pageCount * pageSize;
            }
        }
        return 0;
    }

    /**
     * Support for capturing a snapshot (hot backup) of the database, while
     * still allowing concurrent modifications. The snapshot contains all data
     * up to the last checkpoint. Call the {@link #checkpoint checkpoint}
     * method immediately before to ensure that an up-to-date snapshot is
     * captured.
     *
     * <p>To restore from a snapshot, store it in the primary data file, which
     * is the base file with a ".db" extension. Make sure no redo log files
     * exist and then open the database. Alternatively, call {@link
     * #restoreFromSnapshot restoreFromSnapshot}, which also supports restoring
     * into separate data files.
     *
     * <p>During the snapshot, temporary files are created to hold pre-modified
     * copies of pages. If the snapshot destination stream blocks for too long,
     * these files keep growing. File growth rate increases too if the database
     * is being heavily modified. In the worst case, the temporary files can
     * become larger than the primary database files.
     *
     * @return a snapshot control object, which must be closed when no longer needed
     */
    public Snapshot beginSnapshot() throws IOException {
        if (!(mPageDb instanceof DurablePageDb)) {
            throw new UnsupportedOperationException("Snapshot only allowed for durable databases");
        }
        checkClosed();
        DurablePageDb pageDb = (DurablePageDb) mPageDb;
        return pageDb.beginSnapshot(mTempFileManager);
    }

    /**
     * Restore from a {@link #beginSnapshot snapshot}, into the data files
     * defined by the given configuration.
     *
     * @param in snapshot source; does not require extra buffering; auto-closed
     */
    public static Database restoreFromSnapshot(DatabaseConfig config, InputStream in)
        throws IOException
    {
        File[] dataFiles = config.dataFiles();
        if (dataFiles == null) {
            throw new UnsupportedOperationException("Restore only allowed for durable databases");
        }
        if (!config.mReadOnly && config.mMkdirs) {
            for (File f : dataFiles) {
                f.getParentFile().mkdirs();
            }
        }
        EnumSet<OpenOption> options = config.createOpenOptions();
        // Delete old redo log files.
        deleteNumberedFiles(config.mBaseFile, ".redo.");
        DurablePageDb.restoreFromSnapshot
            (config.mPageSize, dataFiles, options, config.mCrypto, in).close();
        return Database.open(config);
    }

    /**
     * Returns an immutable copy of database statistics.
     */
    public Stats stats() {
        Stats stats = new Stats();

        stats.mPageSize = pageSize();

        mSharedCommitLock.lock();
        try {
            long cursorCount = 0;
            mOpenTreesLatch.acquireShared();
            try {
                stats.mOpenIndexes = mOpenTrees.size();
                for (TreeRef treeRef : mOpenTrees.values()) {
                    Tree tree = treeRef.get();
                    if (tree != null) {
                        cursorCount += tree.mRoot.countCursors(); 
                    }
                }
            } finally {
                mOpenTreesLatch.releaseShared();
            }

            stats.mCursorCount = cursorCount;

            PageDb.Stats pstats = mPageDb.stats();
            stats.mFreePages = pstats.freePages;
            stats.mTotalPages = pstats.totalPages;

            stats.mLockCount = mLockManager.numLocksHeld();

            synchronized (mTxnIdLock) {
                stats.mTxnCount = mUndoLogCount;
                stats.mTxnsCreated = mTxnId;
            }
        } finally {
            mSharedCommitLock.unlock();
        }

        return stats;
    }

    /**
     * Immutable copy of database {@link Database#stats statistics}.
     */
    public static class Stats implements Serializable {
        private static final long serialVersionUID = 2L;

        int mPageSize;
        long mFreePages;
        long mTotalPages;
        int mOpenIndexes;
        long mLockCount;
        long mCursorCount;
        long mTxnCount;
        long mTxnsCreated;

        Stats() {
        }

        /**
         * Returns the allocation page size.
         */
        public int pageSize() {
            return mPageSize;
        }

        /**
         * Returns the amount of unused pages in the database.
         */
        public long freePages() {
            return mFreePages;
        }

        /**
         * Returns the total amount of pages in the database.
         */
        public long totalPages() {
            return mTotalPages;
        }

        /**
         * Returns the amount of indexes currently open.
         */
        public int openIndexes() {
            return mOpenIndexes;
        }

        /**
         * Returns the amount of locks currently allocated. Locks are created
         * as transactions access or modify records, and they are destroyed
         * when transactions exit or reset. An accumulation of locks can
         * indicate that transactions are not being reset properly.
         */
        public long lockCount() {
            return mLockCount;
        }

        /**
         * Returns the amount of cursors which are in a non-reset state. An
         * accumulation of cursors can indicate that cursors are not being
         * reset properly.
         */
        public long cursorCount() {
            return mCursorCount;
        }

        /**
         * Returns the amount of transactions which are in a non-reset
         * state. An accumulation of transactions can indicate that
         * transactions are not being reset properly.
         */
        public long transactionCount() {
            return mTxnCount;
        }

        /**
         * Returns the total amount of transactions explicitly created over the
         * life of the database.
         */
        public long transactionsCreated() {
            return mTxnsCreated;
        }

        @Override
        public String toString() {
            return "Database.Stats {pageSize=" + mPageSize
                + ", freePages=" + mFreePages
                + ", totalPages=" + mTotalPages
                + ", openIndexes=" + mOpenIndexes
                + ", lockCount=" + mLockCount
                + ", cursorCount=" + mCursorCount
                + ", transactionCount=" + mTxnCount
                + ", transactionsCreated=" + mTxnsCreated
                + '}';
        }
    }

    /**
     * Flushes, but does not sync, all non-flushed transactions. Transactions
     * committed with {@link DurabilityMode#NO_FLUSH no-flush} effectively
     * become {@link DurabilityMode#NO_SYNC no-sync} durable.
     */
    public void flush() throws IOException {
        if (!mClosed && mRedoWriter != null) {
            mRedoWriter.flush();
        }
    }

    /**
     * Persists all non-flushed and non-sync'd transactions. Transactions
     * committed with {@link DurabilityMode#NO_FLUSH no-flush} and {@link
     * DurabilityMode#NO_SYNC no-sync} effectively become {@link
     * DurabilityMode#SYNC sync} durable.
     */
    public void sync() throws IOException {
        if (!mClosed && mRedoWriter != null) {
            mRedoWriter.sync();
        }
    }

    /**
     * Durably sync and checkpoint all changes to the database. In addition to
     * ensuring that all transactions are durable, checkpointing ensures that
     * non-transactional modifications are durable. Checkpoints are performed
     * automatically by a background thread, at a {@link
     * DatabaseConfig#checkpointRate configurable} rate.
     */
    public void checkpoint() throws IOException {
        if (!mClosed && mPageDb instanceof DurablePageDb) {
            try {
                checkpoint(false, 0, 0);
            } catch (Throwable e) {
                closeQuietly(null, this, e);
                throw rethrow(e);
            }
        }
    }

    /**
     * Verifies the integrity of the database and all indexes.
     *
     * @param observer optional observer; pass null for default
     * @return true if verification passed
     */
    public boolean verify(VerificationObserver observer) throws IOException {
        // TODO: Verify free lists.

        if (observer == null) {
            observer = new VerificationObserver();
        }

        boolean[] passedRef = {true};

        indexes: {
            if (!verify(passedRef, mRegistry, observer)) {
                break indexes;
            }
            if (!verify(passedRef, mRegistryKeyMap, observer)) {
                break indexes;
            }

            FragmentedTrash trash = mFragmentedTrash;
            if (trash != null) {
                if (!verify(passedRef, trash.mTrash, observer)) {
                    break indexes;
                }
            }

            Cursor all = allIndexes();

            for (all.first(); all.key() != null; all.next()) {
                long id = readLongBE(all.value(), 0);

                Tree index = lookupIndexById(id);
                if (index != null) {
                    if (!verify(passedRef, index, observer)) {
                        break indexes;
                    }
                } else {
                    // Open the index.
                    index = (Tree) indexById(id);
                    boolean keepGoing = verify(passedRef, index, observer);
                    try {
                        index.close();
                    } catch (IllegalStateException e) {
                        // Leave open if in use now.
                    }
                    if (!keepGoing) {
                        break indexes;
                    }
                }
            }

            all.reset();
        }

        return passedRef[0];
    }

    /**
     * @return false if should stop
     */
    private boolean verify(boolean[] passedRef, Tree tree,
                           VerificationObserver observer)
        throws IOException
    {
        observer.failed = false;
        boolean keepGoing = tree.verifyTree(observer);
        passedRef[0] &= !observer.failed;
        if (keepGoing) {
            keepGoing = observer.indexComplete(tree, !observer.failed, null);
        }
        return keepGoing;
    }

    /**
     * Closes the database, ensuring durability of committed transactions. No
     * checkpoint is performed by this method, and so non-transactional
     * modifications can be lost.
     */
    @Override
    public void close() throws IOException {
        close(null);
    }

    @Override
    void close(Throwable cause) throws IOException {
        if (cause != null) {
            if (cClosedCauseUpdater.compareAndSet(this, null, cause) && mEventListener != null) {
                mEventListener.notify(EventType.PANIC_UNHANDLED_EXCEPTION,
                                      "Closing database due to unhandled exception: %1$s",
                                      rootCause(cause));
            }
        }

        mClosed = true;

        Checkpointer c = mCheckpointer;
        if (c != null) {
            c.close();
            c = null;
        }

        // Synchronize to wait for any in-progress checkpoint to complete.
        synchronized (mCheckpointLock) {
            // Nothing really needs to be done in the synchronized block, but
            // do something just in case a "smart" compiler thinks an empty
            // synchronized block can be eliminated.
            mClosed = true;
        }

        if (mOpenTrees != null) {
            mOpenTreesLatch.acquireExclusive();
            try {
                mOpenTrees.clear();
                mOpenTreesById.clear(0);
            } finally {
                mOpenTreesLatch.releaseExclusive();
            }
        }

        mSharedCommitLock.lock();
        try {
            closeNodeCache();

            if (mAllocator != null) {
                mAllocator.clearDirtyNodes();
            }

            IOException ex = null;

            ex = closeQuietly(ex, mRedoWriter, cause);
            ex = closeQuietly(ex, mPageDb, cause);
            ex = closeQuietly(ex, mLockFile, cause);

            mLockManager.close();

            if (ex != null) {
                throw ex;
            }
        } finally {
            mSharedCommitLock.unlock();
        }
    }

    void checkClosed() throws DatabaseException {
        if (mClosed) {
            String message = "Closed";
            Throwable cause = mClosedCause;
            if (cause != null) {
                message += "; " + rootCause(cause);
            }
            throw new DatabaseException(message, cause);
        }
    }

    void treeClosed(Tree tree) {
        mOpenTreesLatch.acquireExclusive();
        try {
            TreeRef ref = mOpenTreesById.getValue(tree.mId);
            if (ref != null && ref.get() == tree) {
                ref.clear();
                mOpenTrees.remove(tree.mName);
                mOpenTreesById.remove(tree.mId);
            }
        } finally {
            mOpenTreesLatch.releaseExclusive();
        }
    }

    /**
     * @return new tree or null if given tree was not the currently open one
     */
    Tree replaceClosedTree(Tree tree, Node newRoot) {
        mOpenTreesLatch.acquireExclusive();
        try {
            TreeRef ref = mOpenTreesById.getValue(tree.mId);
            if (ref != null && ref.get() == tree) {
                ref.clear();
                tree = new Tree(this, tree.mId, tree.mIdBytes, tree.mName, newRoot);
                ref = new TreeRef(tree, mOpenTreesRefQueue);
                mOpenTrees.put(tree.mName, ref);
                mOpenTreesById.insert(tree.mId).value = ref;
                return tree;
            } else {
                return null;
            }
        } finally {
            mOpenTreesLatch.releaseExclusive();
        }
    }

    void dropClosedTree(Tree tree) throws IOException {
        Transaction txn;
        mOpenTreesLatch.acquireExclusive();
        try {
            TreeRef ref = mOpenTreesById.getValue(tree.mId);
            if (ref == null || ref.get() != tree) {
                return;
            }

            ref.clear();
            mOpenTrees.remove(tree.mName);
            mOpenTreesById.remove(tree.mId);

            DurabilityMode mode = mDurabilityMode;
            if (mode == DurabilityMode.NO_REDO) {
                mode = DurabilityMode.NO_FLUSH;
            }

            txn = newTransaction(mode);

            try {
                // Lock to prevent tree from being re-opened.
                mRegistry.delete(txn, tree.mIdBytes);
            } catch (Throwable e) {
                txn.reset();
                throw rethrow(e);
            }
        } finally {
            mOpenTreesLatch.releaseExclusive();
        }

        // Complete the drop operation without preventing other indexes from being opened or
        // dropped concurrently.

        try {
            byte[] nameKey = newKey(KEY_TYPE_INDEX_NAME, tree.mName);
            mRegistryKeyMap.delete(txn, nameKey);

            byte[] idKey = newKey(KEY_TYPE_INDEX_ID, tree.mIdBytes);
            mRegistryKeyMap.delete(txn, idKey);

            txn.commit();
        } finally {
            txn.reset();
        }
    }

    /**
     * @param rootId pass zero to create
     * @return unlatched and unevictable root node
     */
    private Node loadTreeRoot(long rootId) throws IOException {
        Node rootNode = allocLatchedNode(false);
        try {
            if (rootId == 0) {
                rootNode.asEmptyRoot();
            } else {
                try {
                    rootNode.read(this, rootId);
                } catch (IOException e) {
                    makeEvictableNow(rootNode);
                    throw e;
                }
            }
        } finally {
            rootNode.releaseExclusive();
        }
        return rootNode;
    }

    /**
     * Loads the root registry node, or creates one if store is new. Root node
     * is not eligible for eviction.
     */
    private Node loadRegistryRoot(byte[] header) throws IOException {
        int version = readIntLE(header, I_ENCODING_VERSION);

        long rootId;
        if (version == 0) {
            rootId = 0;
            // No registry; clearly nothing has been checkpointed.
            mHasCheckpointed = false;
        } else {
            if (version != ENCODING_VERSION) {
                throw new CorruptDatabaseException("Unknown encoding version: " + version);
            }
            rootId = readLongLE(header, I_ROOT_PAGE_ID);
        }

        return loadTreeRoot(rootId);
    }

    private Tree openInternalTree(long treeId, boolean create) throws IOException {
        final Lock commitLock = sharedCommitLock();
        commitLock.lock();
        try {
            byte[] treeIdBytes = new byte[8];
            writeLongBE(treeIdBytes, 0, treeId);
            byte[] rootIdBytes = mRegistry.load(Transaction.BOGUS, treeIdBytes);
            long rootId;
            if (rootIdBytes != null) {
                rootId = readLongLE(rootIdBytes, 0);
            } else {
                if (!create) {
                    return null;
                }
                rootId = 0;
            }
            return new Tree(this, treeId, treeIdBytes, null, loadTreeRoot(rootId));
        } finally {
            commitLock.unlock();
        }
    }

    private Index openIndex(byte[] name, boolean create) throws IOException {
        checkClosed();

        Tree tree = quickFindIndex(null, name);
        if (tree != null) {
            return tree;
        }

        final Lock commitLock = sharedCommitLock();
        commitLock.lock();
        try {
            // Cleaup before opening more indexes.
            cleanupUnreferencedTrees(null);

            byte[] nameKey = newKey(KEY_TYPE_INDEX_NAME, name);
            byte[] treeIdBytes = mRegistryKeyMap.load(null, nameKey);
            long treeId;
            // Is non-null if index was created.
            byte[] idKey;

            if (treeIdBytes != null) {
                idKey = null;
                treeId = readLongBE(treeIdBytes, 0);
            } else if (!create) {
                return null;
            } else {
                try {
                    mOpenTreesLatch.acquireExclusive();
                    try {
                        treeIdBytes = mRegistryKeyMap.load(null, nameKey);
                        if (treeIdBytes != null) {
                            idKey = null;
                            treeId = readLongBE(treeIdBytes, 0);
                        } else {
                            treeIdBytes = new byte[8];

                            do {
                                treeId = nextTreeId();
                                writeLongBE(treeIdBytes, 0, treeId);
                            } while (!mRegistry.insert
                                     (Transaction.BOGUS, treeIdBytes, EMPTY_BYTES));

                            if (!mRegistryKeyMap.insert(null, nameKey, treeIdBytes)) {
                                mRegistry.delete(Transaction.BOGUS, treeIdBytes);
                                throw new DatabaseException("Unable to insert index name");
                            }

                            idKey = newKey(KEY_TYPE_INDEX_ID, treeIdBytes);

                            if (!mRegistryKeyMap.insert(null, idKey, name)) {
                                mRegistryKeyMap.delete(null, nameKey);
                                mRegistry.delete(Transaction.BOGUS, treeIdBytes);
                                throw new DatabaseException("Unable to insert index id");
                            }
                        }
                    } finally {
                        mOpenTreesLatch.releaseExclusive();
                    }
                } catch (IOException e) {
                    if (e instanceof DatabaseException
                        && ((DatabaseException) e).isRecoverable())
                    {
                        throw e;
                    }
                    throw closeOnFailure(this, e);
                }
            }

            // Use a transaction to ensure that only one thread loads the
            // requested index. Nothing is written into it.
            Transaction txn = newLockTransaction();
            try {
                // Pass the transaction to acquire the lock.
                byte[] rootIdBytes = mRegistry.load(txn, treeIdBytes);

                tree = quickFindIndex(txn, name);
                if (tree != null) {
                    // Another thread got the lock first and loaded the index.
                    return tree;
                }

                long rootId = (rootIdBytes == null || rootIdBytes.length == 0) ? 0
                    : readLongLE(rootIdBytes, 0);
                tree = new Tree(this, treeId, treeIdBytes, name, loadTreeRoot(rootId));

                TreeRef treeRef = new TreeRef(tree, mOpenTreesRefQueue);

                mOpenTreesLatch.acquireExclusive();
                try {
                    mOpenTrees.put(name, treeRef);
                    mOpenTreesById.insert(treeId).value = treeRef;
                } finally {
                    mOpenTreesLatch.releaseExclusive();
                }

                List<Tree> recovered = mRecoveredTrees;
                if (recovered != null) {
                    recovered.add(tree);
                }

                return tree;
            } catch (Throwable e) {
                if (idKey != null) {
                    // Rollback create of new index.
                    try {
                        mRegistryKeyMap.delete(null, idKey);
                        mRegistryKeyMap.delete(null, nameKey);
                        mRegistry.delete(Transaction.BOGUS, treeIdBytes);
                    } catch (Throwable e2) {
                        // Ignore.
                    }
                }
                throw rethrow(e);
            } finally {
                txn.reset();
            }
        } finally {
            commitLock.unlock();
        }
    }

    private long nextTreeId() throws IOException {
        // By generating identifiers from a 64-bit sequence, it's effectively
        // impossible for them to get re-used after trees are deleted.

        DurabilityMode mode = mDurabilityMode;
        if (mode == DurabilityMode.NO_REDO) {
            mode = DurabilityMode.NO_FLUSH;
        }

        Transaction txn = newTransaction(mode);
        try {
            // Tree id mask, to make the identifiers less predictable and
            // non-compatible with other database instances.
            long treeIdMask;
            {
                byte[] key = {KEY_TYPE_TREE_ID_MASK};
                byte[] treeIdMaskBytes = mRegistryKeyMap.load(txn, key);

                if (treeIdMaskBytes == null) {
                    treeIdMaskBytes = new byte[8];
                    random().nextBytes(treeIdMaskBytes);
                    mRegistryKeyMap.store(txn, key, treeIdMaskBytes);
                }

                treeIdMask = readLongLE(treeIdMaskBytes, 0);
            }

            byte[] key = {KEY_TYPE_NEXT_TREE_ID};
            byte[] nextTreeIdBytes = mRegistryKeyMap.load(txn, key);

            if (nextTreeIdBytes == null) {
                nextTreeIdBytes = new byte[8];
            }
            long nextTreeId = readLongLE(nextTreeIdBytes, 0);

            long treeId;
            do {
                treeId = scramble((nextTreeId++) ^ treeIdMask);
            } while (Tree.isInternal(treeId));

            writeLongLE(nextTreeIdBytes, 0, nextTreeId);
            mRegistryKeyMap.store(txn, key, nextTreeIdBytes);
            txn.commit();

            return treeId;
        } finally {
            txn.reset();
        }
    }

    /**
     * @return null if not found
     */
    private Tree quickFindIndex(Transaction txn, byte[] name) throws IOException {
        TreeRef treeRef;
        mOpenTreesLatch.acquireShared();
        try {
            treeRef = mOpenTrees.get(name);
            if (treeRef == null) {
                return null;
            }
            Tree tree = treeRef.get();
            if (tree != null) {
                return tree;
            }
        } finally {
            mOpenTreesLatch.releaseShared();
        }

        // Ensure that all nodes of cleared tree reference are evicted before
        // potentially replacing them. Weak references are cleared before they
        // are enqueued, and so polling the queue does not guarantee node
        // eviction. Process the tree directly.
        cleanupUnreferencedTree(txn, treeRef);

        return null;
    }

    /**
     * Trees retain a references to an unevictable root node. If tree is no
     * longer in use, evict everything, including the root node. Method cannot
     * be called while a checkpoint is in progress.
     */
    private void cleanupUnreferencedTrees(Transaction txn) throws IOException {
        final ReferenceQueue queue = mOpenTreesRefQueue;
        if (queue == null) {
            return;
        }
        try {
            while (true) {
                Reference ref = queue.poll();
                if (ref == null) {
                    break;
                }
                if (ref instanceof TreeRef) {
                    cleanupUnreferencedTree(txn, (TreeRef) ref);
                }
            }
        } catch (Exception e) {
            if (!mClosed) {
                throw rethrow(e);
            }
        }
    }

    private void cleanupUnreferencedTree(Transaction txn, TreeRef ref) throws IOException {
        // Acquire lock to prevent tree from being reloaded too soon.

        byte[] treeIdBytes = new byte[8];
        writeLongBE(treeIdBytes, 0, ref.mId);

        if (txn == null) {
            txn = newLockTransaction();
        } else {
            txn.enter();
        }

        try {
            // Pass the transaction to acquire the lock.
            mRegistry.load(txn, treeIdBytes);

            mOpenTreesLatch.acquireShared();
            try {
                LHashTable.ObjEntry<TreeRef> entry = mOpenTreesById.get(ref.mId);
                if (entry == null || entry.value != ref) {
                    return;
                }
            } finally {
                mOpenTreesLatch.releaseShared();
            }

            Node root = ref.mRoot;
            root.acquireExclusive();
            root.forceEvictTree(mPageDb);
            root.releaseExclusive();

            mOpenTreesLatch.acquireExclusive();
            try {
                mOpenTreesById.remove(ref.mId);
                mOpenTrees.remove(ref.mName);
            } finally {
                mOpenTreesLatch.releaseExclusive();
            }
        } finally {
            txn.exit();
        }

        // Move root node into usage list, allowing it to be re-used.
        makeEvictableNow(ref.mRoot);
    }

    private static byte[] newKey(byte type, byte[] payload) {
        byte[] key = new byte[1 + payload.length];
        key[0] = type;
        System.arraycopy(payload, 0, key, 1, payload.length);
        return key;
    }

    /**
     * Returns the fixed size of all pages in the store, in bytes.
     */
    int pageSize() {
        return mPageDb.pageSize();
    }

    /**
     * Access the shared commit lock, which prevents commits while held.
     */
    Lock sharedCommitLock() {
        return mSharedCommitLock;
    }

    /**
     * Returns a new or recycled Node instance, latched exclusively, with an id
     * of zero and a clean state.
     */
    Node allocLatchedNode() throws IOException {
        return allocLatchedNode(true);
    }

    /**
     * Returns a new or recycled Node instance, latched exclusively, with an id
     * of zero and a clean state.
     *
     * @param evictable true if allocated node can be automatically evicted
     */
    Node allocLatchedNode(boolean evictable) throws IOException {
        final Latch usageLatch = mUsageLatch;
        for (int trial = 1; trial <= 3; trial++) {
            usageLatch.acquireExclusive();
            alloc: try {
                int max = mMaxNodeCount;

                if (max == 0) {
                    break alloc;
                }

                if (mNodeCount < max) {
                    checkClosed();
                    Node node = new Node(pageSize());
                    node.acquireExclusive();
                    mNodeCount++;
                    if (evictable) {
                        if ((node.mLessUsed = mMostRecentlyUsed) == null) {
                            mLeastRecentlyUsed = node;
                        } else {
                            mMostRecentlyUsed.mMoreUsed = node;
                        }
                        mMostRecentlyUsed = node;
                    }
                    return node;
                }

                if (!evictable && mLeastRecentlyUsed.mMoreUsed == mMostRecentlyUsed) {
                    // Cannot allow list to shrink to less than two elements.
                    break alloc;
                }

                do {
                    Node node = mLeastRecentlyUsed;
                    (mLeastRecentlyUsed = node.mMoreUsed).mLessUsed = null;
                    node.mMoreUsed = null;
                    (node.mLessUsed = mMostRecentlyUsed).mMoreUsed = node;
                    mMostRecentlyUsed = node;

                    if (node.tryAcquireExclusive() && (node = Node.evict(node, mPageDb)) != null) {
                        if (!evictable) {
                            // Detach from linked list.
                            (mMostRecentlyUsed = node.mLessUsed).mMoreUsed = null;
                            node.mLessUsed = null;
                        }
                        // Return with latch still held.
                        return node;
                    }
                } while (--max > 0);
            } finally {
                usageLatch.releaseExclusive();
            }

            checkClosed();

            final Lock commitLock = sharedCommitLock();
            commitLock.lock();
            try {
                // Try to free up nodes from unreferenced trees.
                cleanupUnreferencedTrees(null);
            } finally {
                commitLock.unlock();
            }
        }

        throw new CacheExhaustedException();
    }

    /**
     * Unlinks all nodes from each other in usage list, and prevents new nodes
     * from being allocated.
     */
    private void closeNodeCache() {
        final Latch usageLatch = mUsageLatch;
        usageLatch.acquireExclusive();
        try {
            // Prevent new allocations.
            mMaxNodeCount = 0;

            Node node = mLeastRecentlyUsed;
            mLeastRecentlyUsed = null;
            mMostRecentlyUsed = null;

            while (node != null) {
                Node next = node.mMoreUsed;
                node.mLessUsed = null;
                node.mMoreUsed = null;

                // Make node appear to be evicted.
                node.mId = 0;

                // Attempt to unlink child nodes, making them appear to be evicted.
                if (node.tryAcquireExclusive()) {
                    Node[] childNodes = node.mChildNodes;
                    if (childNodes != null) {
                        Arrays.fill(childNodes, null);
                    }
                    node.releaseExclusive();
                }

                node = next;
            }
        } finally {
            usageLatch.releaseExclusive();
        }
    }

    /**
     * Returns a new or recycled Node instance, latched exclusively and marked
     * dirty. Caller must hold commit lock.
     */
    Node allocDirtyNode() throws IOException {
        Node node = allocLatchedNode(true);
        try {
            dirty(node, mAllocator.allocPage(node));
            return node;
        } catch (IOException e) {
            node.releaseExclusive();
            throw e;
        }
    }

    /**
     * Returns a new or recycled Node instance, latched exclusively, marked
     * dirty and unevictable. Caller must hold commit lock.
     */
    Node allocUnevictableNode() throws IOException {
        Node node = allocLatchedNode(false);
        try {
            dirty(node, mAllocator.allocPage(node));
            return node;
        } catch (IOException e) {
            makeEvictableNow(node);
            node.releaseExclusive();
            throw e;
        }
    }

    /**
     * Allow a Node which was allocated as unevictable to be evictable,
     * starting off as the most recently used.
     */
    void makeEvictable(Node node) {
        final Latch usageLatch = mUsageLatch;
        usageLatch.acquireExclusive();
        try {
            if (mMaxNodeCount == 0) {
                // Closed.
                return;
            }
            if (node.mMoreUsed != null || node.mLessUsed != null) {
                throw new IllegalStateException();
            }
            (node.mLessUsed = mMostRecentlyUsed).mMoreUsed = node;
            mMostRecentlyUsed = node;
        } finally {
            usageLatch.releaseExclusive();
        }
    }

    /**
     * Allow a Node which was allocated as unevictable to be evictable, as the
     * least recently used.
     */
    void makeEvictableNow(Node node) {
        final Latch usageLatch = mUsageLatch;
        usageLatch.acquireExclusive();
        try {
            if (mMaxNodeCount == 0) {
                // Closed.
                return;
            }
            if (node.mMoreUsed != null || node.mLessUsed != null) {
                throw new IllegalStateException();
            }
            (node.mMoreUsed = mLeastRecentlyUsed).mLessUsed = node;
            mLeastRecentlyUsed = node;
        } finally {
            usageLatch.releaseExclusive();
        }
    }

    /**
     * Allow a Node which was allocated as evictable to be unevictable.
     */
    void makeUnevictable(final Node node) {
        final Latch usageLatch = mUsageLatch;
        usageLatch.acquireExclusive();
        try {
            if (mMaxNodeCount == 0) {
                // Closed.
                return;
            }
            final Node lessUsed = node.mLessUsed;
            final Node moreUsed = node.mMoreUsed;
            if (lessUsed == null) {
                (mLeastRecentlyUsed = moreUsed).mLessUsed = null;
            } else if (moreUsed == null) {
                (mMostRecentlyUsed = lessUsed).mMoreUsed = null;
            } else {
                lessUsed.mMoreUsed = moreUsed;
                moreUsed.mLessUsed = lessUsed;
            }
            node.mMoreUsed = null;
            node.mLessUsed = null;
        } finally {
            usageLatch.releaseExclusive();
        }
    }

    /**
     * Caller must hold commit lock and any latch on node.
     */
    boolean shouldMarkDirty(Node node) {
        return node.mCachedState != mCommitState && node.mId != Node.STUB_ID;
    }

    /**
     * Caller must hold commit lock and exclusive latch on node. Method does
     * nothing if node is already dirty. Latch is never released by this method,
     * even if an exception is thrown.
     *
     * @return true if just made dirty and id changed
     */
    boolean markDirty(Tree tree, Node node) throws IOException {
        if (node.mCachedState == mCommitState || node.mId == Node.STUB_ID) {
            return false;
        } else {
            doMarkDirty(tree, node);
            return true;
        }
    }

    /**
     * Caller must hold commit lock and exclusive latch on node. Method does
     * nothing if node is already dirty. Latch is never released by this method,
     * even if an exception is thrown.
     */
    void markUndoLogDirty(Node node) throws IOException {
        if (node.mCachedState != mCommitState) {
            long oldId = node.mId;
            long newId = mAllocator.allocPage(node);
            mPageDb.deletePage(oldId);
            node.write(mPageDb);
            dirty(node, newId);
        }
    }

    /**
     * Caller must hold commit lock and exclusive latch on node. Method must
     * not be called if node is already dirty. Latch is never released by this
     * method, even if an exception is thrown.
     */
    void doMarkDirty(Tree tree, Node node) throws IOException {
        long oldId = node.mId;
        long newId = mAllocator.allocPage(node);
        if (oldId != 0) {
            mPageDb.deletePage(oldId);
        }
        if (node.mCachedState != CACHED_CLEAN) {
            node.write(mPageDb);
        }
        if (node == tree.mRoot && tree.mIdBytes != null) {
            byte[] newEncodedId = new byte[8];
            writeLongLE(newEncodedId, 0, newId);
            mRegistry.store(Transaction.BOGUS, tree.mIdBytes, newEncodedId);
        }
        dirty(node, newId);
    }

    /**
     * Caller must hold commit lock and exclusive latch on node.
     */
    private void dirty(Node node, long newId) {
        node.mId = newId;
        node.mCachedState = mCommitState;
    }

    /**
     * Caller must hold commit lock and exclusive latch on node. This method
     * should only be called for nodes whose existing data is not needed.
     */
    void redirty(Node node) {
        node.mCachedState = mCommitState;
        mAllocator.dirty(node);
    }

    /**
     * Similar to markDirty method except no new page is reserved, and old page
     * is not immediately deleted. Caller must hold commit lock and exclusive
     * latch on node. Latch is never released by this method, unless an
     * exception is thrown.
     */
    void prepareToDelete(Node node) throws IOException {
        // Hello. My name is Inigo Montoya. You killed my father. Prepare to die. 
        if (node.mCachedState == mCheckpointFlushState) {
            // Node must be committed with the current checkpoint, and so
            // it must be written out before it can be deleted.
            try {
                node.write(mPageDb);
            } catch (Throwable e) {
                node.releaseExclusive();
                throw rethrow(e);
            }
        }
    }

    /**
     * Caller must hold commit lock and exclusive latch on node. The
     * prepareToDelete method must have been called first. Latch is always
     * released by this method, even if an exception is thrown.
     */
    void deleteNode(Node node) throws IOException {
        try {
            deletePage(node.mId, node.mCachedState);

            node.mId = 0;
            // TODO: child node array should be recycled
            node.mChildNodes = null;

            // When node is re-allocated, it will be evicted. Ensure that eviction
            // doesn't write anything.
            node.mCachedState = CACHED_CLEAN;
        } finally {
            node.releaseExclusive();
        }

        // Indicate that node is least recently used, allowing it to be
        // re-allocated immediately without evicting another node. Node must be
        // unlatched at this point, to prevent it from being immediately
        // promoted to most recently used by allocLatchedNode.
        final Latch usageLatch = mUsageLatch;
        usageLatch.acquireExclusive();
        try {
            if (mMaxNodeCount == 0) {
                // Closed.
                return;
            }
            Node lessUsed = node.mLessUsed;
            if (lessUsed == null) {
                // Node might already be least...
                if (node.mMoreUsed != null) {
                    // ...confirmed.
                    return;
                }
                // ...Node isn't in the usage list at all.
            } else {
                Node moreUsed = node.mMoreUsed;
                if ((lessUsed.mMoreUsed = moreUsed) == null) {
                    mMostRecentlyUsed = lessUsed;
                } else {
                    moreUsed.mLessUsed = lessUsed;
                }
                node.mLessUsed = null;
            }
            (node.mMoreUsed = mLeastRecentlyUsed).mLessUsed = node;
            mLeastRecentlyUsed = node;
        } finally {
            usageLatch.releaseExclusive();
        }
    }

    /**
     * Caller must hold commit lock.
     */
    void deletePage(long id, int cachedState) throws IOException {
        if (id != 0) {
            if (cachedState == mCommitState) {
                // Newly reserved page was never used, so recycle it.
                mAllocator.recyclePage(id);
            } else {
                // Old data must survive until after checkpoint.
                mPageDb.deletePage(id);
            }
        }
    }

    /**
     * Indicate that non-root node is most recently used. Root node is not
     * managed in usage list and cannot be evicted. Caller must hold any latch
     * on node. Latch is never released by this method, even if an exception is
     * thrown.
     */
    void used(Node node) {
        // Because this method can be a bottleneck, don't wait for exclusive
        // latch. If node is popular, it will get more chances to be identified
        // as most recently used. This strategy works well enough because cache
        // eviction is always a best-guess approach.
        final Latch usageLatch = mUsageLatch;
        if (usageLatch.tryAcquireExclusive()) {
            Node moreUsed = node.mMoreUsed;
            if (moreUsed != null) {
                Node lessUsed = node.mLessUsed;
                if ((moreUsed.mLessUsed = lessUsed) == null) {
                    mLeastRecentlyUsed = moreUsed;
                } else {
                    lessUsed.mMoreUsed = moreUsed;
                }
                node.mMoreUsed = null;
                (node.mLessUsed = mMostRecentlyUsed).mMoreUsed = node;
                mMostRecentlyUsed = node;
            }
            usageLatch.releaseExclusive();
        }
    }

    /**
     * Breakup a large value into separate pages, returning a new value which
     * encodes the page references. Caller must hold commit lock.
     *
     * Returned value begins with a one byte header:
     *
     * 0b0000_ffip
     *
     * The leading 4 bits define the encoding type, which must be 0. The 'f'
     * bits define the full value length field size: 2, 4, 6, or 8 bytes. The
     * array is limited to a 4 byte length, and so only the 2 and 4 byte forms
     * apply. The 'i' bit defines the inline content length field size: 0 or 2
     * bytes. The 'p' bit is clear if direct pointers are used, and set for
     * indirect pointers. Pointers are always 6 bytes.
     *
     * @param caller optional tree node which is latched and calling this method
     * @param max maximum allowed size for returned byte array; must not be
     * less than 11 (can be 9 if full value length is < 65536)
     * @return null if max is too small
     */
    byte[] fragment(Node caller, byte[] value, int max) throws IOException {
        int pageSize = pageSize();
        int pageCount = value.length / pageSize;
        int remainder = value.length % pageSize;

        if (value.length >= 65536) {
            // Subtract header size, full length field size, and size of one pointer.
            max -= (1 + 4 + 6);
        } else if (pageCount == 0 && remainder <= (max - (1 + 2 + 2))) {
            // Entire value fits inline. It didn't really need to be
            // encoded this way, but do as we're told.
            byte[] newValue = new byte[(1 + 2 + 2) + value.length];
            newValue[0] = 0x02; // ff=0, i=1, p=0
            writeShortLE(newValue, 1, value.length);     // full length
            writeShortLE(newValue, 1 + 2, value.length); // inline length
            System.arraycopy(value, 0, newValue, (1 + 2 + 2), value.length);
            return newValue;
        } else {
            // Subtract header size, full length field size, and size of one pointer.
            max -= (1 + 2 + 6);
        }

        if (max < 0) {
            return null;
        }

        int pointerSpace = pageCount * 6;

        byte[] newValue;
        if (remainder <= max && remainder < 65536
            && (pointerSpace <= (max + (6 - 2) - remainder)))
        {
            // Remainder fits inline, minimizing internal fragmentation. All
            // extra pages will be full. All pointers fit too; encode direct.

            byte header;
            int offset;
            if (value.length >= 65536) {
                header = 0x06; // ff = 1, i=1
                offset = 1 + 4;
            } else {
                header = 0x02; // ff = 0, i=1
                offset = 1 + 2;
            }

            int poffset = offset + 2 + remainder;
            newValue = new byte[poffset + pointerSpace];
            if (pageCount > 0) {
                int voffset = remainder;
                while (true) {
                    Node node = allocDirtyNode();
                    try {
                        mFragmentCache.put(caller, node);
                        writeInt48LE(newValue, poffset, node.mId);
                        System.arraycopy(value, voffset, node.mPage, 0, pageSize);
                        if (pageCount == 1) {
                            break;
                        }
                    } finally {
                        node.releaseExclusive();
                    }
                    pageCount--;
                    poffset += 6;
                    voffset += pageSize;
                }
            }

            newValue[0] = header;
            writeShortLE(newValue, offset, remainder); // inline length
            System.arraycopy(value, 0, newValue, offset + 2, remainder);
        } else {
            // Remainder doesn't fit inline, so don't encode any inline
            // content. Last extra page will not be full.
            pageCount++;
            pointerSpace += 6;

            byte header;
            int offset;
            if (value.length >= 65536) {
                header = 0x04; // ff = 1, i=0
                offset = 1 + 4;
            } else {
                header = 0x00; // ff = 0, i=0
                offset = 1 + 2;
            }

            if (pointerSpace <= (max + 6)) {
                // All pointers fit, so encode as direct.
                newValue = new byte[offset + pointerSpace];
                if (pageCount > 0) {
                    int voffset = 0;
                    while (true) {
                        Node node = allocDirtyNode();
                        try {
                            mFragmentCache.put(caller, node);
                            writeInt48LE(newValue, offset, node.mId);
                            if (pageCount > 1) {
                                System.arraycopy(value, voffset, node.mPage, 0, pageSize);
                            } else {
                                System.arraycopy(value, voffset, node.mPage, 0, remainder);
                                break;
                            }
                        } finally {
                            node.releaseExclusive();
                        }
                        pageCount--;
                        offset += 6;
                        voffset += pageSize;
                    }
                }
            } else {
                // Use indirect pointers.
                header |= 0x01;
                newValue = new byte[offset + 6];
                int levels = calculateInodeLevels(value.length, pageSize);
                Node inode = allocDirtyNode();
                writeInt48LE(newValue, offset, inode.mId);
                writeMultilevelFragments(caller, levels, inode, value, 0, value.length);
            }

            newValue[0] = header;
        }

        // Encode full length field.
        if (value.length >= 65536) {
            writeIntLE(newValue, 1, value.length);
        } else {
            writeShortLE(newValue, 1, value.length);
        }

        return newValue;
    }

    private static int calculateInodeLevels(long valueLength, int pageSize) {
        int levels = 0;

        if (valueLength >= 0 && valueLength < (Long.MAX_VALUE / 2)) {
            long len = (valueLength + (pageSize - 1)) / pageSize;
            if (len > 1) {
                int ptrCount = pageSize / 6;
                do {
                    levels++;
                } while ((len = (len + (ptrCount - 1)) / ptrCount) > 1);
            }
        } else {
            BigInteger bPageSize = BigInteger.valueOf(pageSize);
            BigInteger bLen = (valueOfUnsigned(valueLength)
                               .add(bPageSize.subtract(BigInteger.ONE))).divide(bPageSize);
            if (bLen.compareTo(BigInteger.ONE) > 0) {
                BigInteger bPtrCount = bPageSize.divide(BigInteger.valueOf(6));
                BigInteger bPtrCountM1 = bPtrCount.subtract(BigInteger.ONE);
                do {
                    levels++;
                } while ((bLen = (bLen.add(bPtrCountM1)).divide(bPtrCount))
                         .compareTo(BigInteger.ONE) > 0);
            }
        }

        return levels;
    }

    /**
     * @param level inode level; at least 1
     * @param inode exclusive latched parent inode; always released by this method
     * @param value slice of complete value being fragmented
     */
    private void writeMultilevelFragments(Node caller,
                                          int level, Node inode,
                                          byte[] value, int voffset, int vlength)
        throws IOException
    {
        long levelCap;
        long[] childNodeIds;
        Node[] childNodes;
        try {
            byte[] page = inode.mPage;
            level--;
            levelCap = levelCap(page.length, level);

            // Pre-allocate and reference the required child nodes in order for
            // parent node latch to be released early. FragmentCache can then
            // safely evict the parent node if necessary.

            int childNodeCount = (int) ((vlength + (levelCap - 1)) / levelCap);
            childNodeIds = new long[childNodeCount];
            childNodes = new Node[childNodeCount];
            try {
                for (int poffset = 0, i=0; i<childNodeCount; poffset += 6, i++) {
                    Node childNode = allocDirtyNode();
                    writeInt48LE(page, poffset, childNodeIds[i] = childNode.mId);
                    childNodes[i] = childNode;
                    // Allow node to be evicted, but don't write anything yet.
                    childNode.mCachedState = CACHED_CLEAN;
                    childNode.releaseExclusive();
                }
            } catch (Throwable e) {
                for (Node childNode : childNodes) {
                    if (childNode != null) {
                        childNode.acquireExclusive();
                        deleteNode(childNode);
                    }
                }
                throw rethrow(e);
            }

            mFragmentCache.put(caller, inode);
        } finally {
            inode.releaseExclusive();
        }

        for (int i=0; i<childNodeIds.length; i++) {
            long childNodeId = childNodeIds[i];
            Node childNode = childNodes[i];

            latchChild: {
                if (childNodeId == childNode.mId) {
                    childNode.acquireExclusive();
                    if (childNodeId == childNode.mId) {
                        // Since commit lock is held, only need to switch the
                        // state. Calling redirty is unnecessary and it would
                        // screw up the dirty list order for no good reason.
                        childNode.mCachedState = mCommitState;
                        break latchChild;
                    }
                }
                // Child node was evicted, although it was clean.
                childNode = allocLatchedNode();
                childNode.mId = childNodeId;
                redirty(childNode);
            }

            int len = (int) Math.min(levelCap, vlength);
            if (level <= 0) {
                System.arraycopy(value, voffset, childNode.mPage, 0, len);
                mFragmentCache.put(caller, childNode);
                childNode.releaseExclusive();
            } else {
                writeMultilevelFragments(caller, level, childNode, value, voffset, len);
            }

            vlength -= len;
            voffset += len;
        }
    }

    /**
     * Reconstruct a fragmented value.
     *
     * @param caller optional tree node which is latched and calling this method
     */
    byte[] reconstruct(Node caller, byte[] fragmented, int off, int len) throws IOException {
        int header = fragmented[off++];
        len--;

        int vLen;
        switch ((header >> 2) & 0x03) {
        default:
            vLen = readUnsignedShortLE(fragmented, off);
            break;

        case 1:
            vLen = readIntLE(fragmented, off);
            if (vLen < 0) {
                throw new LargeValueException(vLen & 0xffffffffL);
            }
            break;

        case 2:
            long vLenL = readUnsignedInt48LE(fragmented, off);
            if (vLenL > Integer.MAX_VALUE) {
                throw new LargeValueException(vLenL);
            }
            vLen = (int) vLenL;
            break;

        case 3:
            vLenL = readLongLE(fragmented, off);
            if (vLenL < 0 || vLenL > Integer.MAX_VALUE) {
                throw new LargeValueException(vLenL);
            }
            vLen = (int) vLenL;
            break;
        }

        {
            int vLenFieldSize = 2 + ((header >> 1) & 0x06);
            off += vLenFieldSize;
            len -= vLenFieldSize;
        }

        byte[] value;
        try {
            value = new byte[vLen];
        } catch (OutOfMemoryError e) {
            throw new LargeValueException(vLen, e);
        }

        int vOff = 0;
        if ((header & 0x02) != 0) {
            // Inline content.
            int inLen = readUnsignedShortLE(fragmented, off);
            off += 2;
            len -= 2;
            System.arraycopy(fragmented, off, value, vOff, inLen);
            off += inLen;
            len -= inLen;
            vOff += inLen;
            vLen -= inLen;
        }

        if ((header & 0x01) == 0) {
            // Direct pointers.
            while (len >= 6) {
                long nodeId = readUnsignedInt48LE(fragmented, off);
                off += 6;
                len -= 6;
                Node node = mFragmentCache.get(caller, nodeId);
                try {
                    byte[] page = node.mPage;
                    int pLen = Math.min(vLen, page.length);
                    System.arraycopy(page, 0, value, vOff, pLen);
                    vOff += pLen;
                    vLen -= pLen;
                } finally {
                    node.releaseShared();
                }
            }
        } else {
            // Indirect pointers.
            int levels = calculateInodeLevels(vLen, pageSize());
            long nodeId = readUnsignedInt48LE(fragmented, off);
            Node inode = mFragmentCache.get(caller, nodeId);
            readMultilevelFragments(caller, levels, inode, value, 0, vLen);
        }

        return value;
    }

    /**
     * Trace the pages of fragmented value.
     *
     * @param caller optional tree node which is latched and calling this method
     */
    /*
    void traceFragmented(java.util.BitSet bits,
                         Node caller, byte[] fragmented, int off, int len)
        throws IOException
    {
        int header = fragmented[off++];
        len--;

        // TODO: code duplication with reconstruct
        // TODO: no LargeValueException
        int vLen;
        switch ((header >> 2) & 0x03) {
        default:
            vLen = readUnsignedShortLE(fragmented, off);
            break;

        case 1:
            vLen = readIntLE(fragmented, off);
            if (vLen < 0) {
                throw new LargeValueException(vLen & 0xffffffffL);
            }
            break;

        case 2:
            long vLenL = readUnsignedInt48LE(fragmented, off);
            if (vLenL > Integer.MAX_VALUE) {
                throw new LargeValueException(vLenL);
            }
            vLen = (int) vLenL;
            break;

        case 3:
            vLenL = readLongLE(fragmented, off);
            if (vLenL < 0 || vLenL > Integer.MAX_VALUE) {
                throw new LargeValueException(vLenL);
            }
            vLen = (int) vLenL;
            break;
        }

        {
            int vLenFieldSize = 2 + ((header >> 1) & 0x06);
            off += vLenFieldSize;
            len -= vLenFieldSize;
        }

        if ((header & 0x02) != 0) {
            // Inline content.
            int inLen = readUnsignedShortLE(fragmented, off);
            off += 2 + inLen;
            len -= 2 + inLen;
        }

        if ((header & 0x01) == 0) {
            // Direct pointers.
            while (len >= 6) {
                long nodeId = readUnsignedInt48LE(fragmented, off);
                off += 6;
                len -= 6;
                bits.clear((int) nodeId);
            }
        } else {
            // Indirect pointers.
            // TODO
            throw new DatabaseException("TODO");
            /*
            int levels = calculateInodeLevels(vLen, pageSize());
            long nodeId = readUnsignedInt48LE(fragmented, off);
            Node inode = mFragmentCache.get(caller, nodeId);
            readMultilevelFragments(caller, levels, inode, value, 0, vLen);
            * /
        }
    }
    */

    /**
     * @param level inode level; at least 1
     * @param inode shared latched parent inode; always released by this method
     * @param value slice of complete value being reconstructed
     */
    private void readMultilevelFragments(Node caller,
                                         int level, Node inode,
                                         byte[] value, int voffset, int vlength)
        throws IOException
    {
        byte[] page = inode.mPage;
        level--;
        long levelCap = levelCap(page.length, level);

        // Copy all child node ids and release parent latch early.
        // FragmentCache can then safely evict the parent node if necessary.
        int childNodeCount = (int) ((vlength + (levelCap - 1)) / levelCap);
        long[] childNodeIds = new long[childNodeCount];
        for (int poffset = 0, i=0; i<childNodeCount; poffset += 6, i++) {
            childNodeIds[i] = readUnsignedInt48LE(page, poffset);
        }
        inode.releaseShared();

        for (long childNodeId : childNodeIds) {
            Node childNode = mFragmentCache.get(caller, childNodeId);
            int len = (int) Math.min(levelCap, vlength);
            if (level <= 0) {
                System.arraycopy(childNode.mPage, 0, value, voffset, len);
                childNode.releaseShared();
            } else {
                readMultilevelFragments(caller, level, childNode, value, voffset, len);
            }
            vlength -= len;
            voffset += len;
        }
    }

    /**
     * Delete the extra pages of a fragmented value. Caller must hold commit
     * lock.
     *
     * @param caller optional tree node which is latched and calling this method
     */
    void deleteFragments(Node caller, byte[] fragmented, int off, int len)
        throws IOException
    {
        int header = fragmented[off++];
        len--;

        long vLen;
        if ((header & 0x01) == 0) {
            // Don't need to read the value length when deleting direct pointers.
            vLen = 0;
        } else {
            switch ((header >> 2) & 0x03) {
            default:
                vLen = readUnsignedShortLE(fragmented, off);
                break;
            case 1:
                vLen = readIntLE(fragmented, off) & 0xffffffffL;
                break;
            case 2:
                vLen = readUnsignedInt48LE(fragmented, off);
                break;
            case 3:
                vLen = readLongLE(fragmented, off);
                break;
            }
        }

        {
            int vLenFieldSize = 2 + ((header >> 1) & 0x06);
            off += vLenFieldSize;
            len -= vLenFieldSize;
        }

        if ((header & 0x02) != 0) {
            // Skip inline content.
            int inLen = 2 + readUnsignedShortLE(fragmented, off);
            off += inLen;
            len -= inLen;
        }

        if ((header & 0x01) == 0) {
            // Direct pointers.
            while (len >= 6) {
                long nodeId = readUnsignedInt48LE(fragmented, off);
                off += 6;
                len -= 6;
                deleteFragment(caller, nodeId);
            }
        } else {
            // Indirect pointers.
            int levels = calculateInodeLevels(vLen, pageSize());
            long nodeId = readUnsignedInt48LE(fragmented, off);
            Node inode = removeInode(caller, nodeId);
            deleteMultilevelFragments(caller, levels, inode, vLen);
        }
    }

    /**
     * @param level inode level; at least 1
     * @param inode exclusive latched parent inode; always released by this method
     */
    private void deleteMultilevelFragments(Node caller,
                                           int level, Node inode, long vlength)
        throws IOException
    {
        byte[] page = inode.mPage;
        level--;
        long levelCap = levelCap(page.length, level);

        // Copy all child node ids and release parent latch early.
        int childNodeCount = (int) ((vlength + (levelCap - 1)) / levelCap);
        long[] childNodeIds = new long[childNodeCount];
        for (int poffset = 0, i=0; i<childNodeCount; poffset += 6, i++) {
            childNodeIds[i] = readUnsignedInt48LE(page, poffset);
        }
        deleteNode(inode);

        if (level <= 0) for (long childNodeId : childNodeIds) {
            deleteFragment(caller, childNodeId);
        } else for (long childNodeId : childNodeIds) {
            Node childNode = removeInode(caller, childNodeId);
            long len = Math.min(levelCap, vlength);
            deleteMultilevelFragments(caller, level, childNode, len);
            vlength -= len;
        }
    }

    /**
     * @return non-null Node with exclusive latch held
     */
    private Node removeInode(Node caller, long nodeId) throws IOException {
        Node node = mFragmentCache.remove(caller, nodeId);
        if (node == null) {
            node = allocLatchedNode(false);
            node.mId = nodeId;
            node.mType = TYPE_FRAGMENT;
            node.mCachedState = readNodePage(nodeId, node.mPage);
        }
        return node;
    }

    private void deleteFragment(Node caller, long nodeId) throws IOException {
        Node node = mFragmentCache.remove(caller, nodeId);
        if (node != null) {
            deleteNode(node);
        } else if (!mHasCheckpointed) {
            // Page was never used if nothing has ever been checkpointed.
            mAllocator.recyclePage(nodeId);
        } else {
            // Page is clean if not in a Node, and so it must survive until
            // after the next checkpoint.
            mPageDb.deletePage(nodeId);
        }
    }

    private static long levelCap(int pageLength, int level) {
        return pageLength * (long) Math.pow(pageLength / 6, level);
    }

    /**
     * Obtain the trash for transactionally deleting fragmented values.
     */
    FragmentedTrash fragmentedTrash() throws IOException {
        FragmentedTrash trash = mFragmentedTrash;
        if (trash != null) {
            return trash;
        }
        mOpenTreesLatch.acquireExclusive();
        try {
            if ((trash = mFragmentedTrash) != null) {
                return trash;
            }
            Tree tree = openInternalTree(Tree.FRAGMENTED_TRASH_ID, true);
            return mFragmentedTrash = new FragmentedTrash(tree);
        } finally {
            mOpenTreesLatch.releaseExclusive();
        }
    }

    byte[] removeSpareBuffer() {
        return mSpareBufferPool.remove();
    }

    void addSpareBuffer(byte[] buffer) {
        mSpareBufferPool.add(buffer);
    }

    /**
     * @return initial cached state for node
     */
    byte readNodePage(long id, byte[] page) throws IOException {
        mPageDb.readPage(id, page);

        if (!mHasCheckpointed) {
            // Read is reloading an evicted node which is known to be dirty.
            mSharedCommitLock.lock();
            try {
                return mCommitState;
            } finally {
                mSharedCommitLock.unlock();
            }
        }

        // TODO: Keep some sort of cache of ids known to be dirty. If reloaded
        // before commit, then they're still dirty. Without this optimization,
        // too many pages are allocated when: evictions are high, write rate is
        // high, and commits are bogged down. A Bloom filter is not
        // appropriate, because of false positives. A random evicting cache
        // works well -- it has no collision chains. Evict whatever else was
        // there in the slot. An array of longs should suffice.

        return CACHED_CLEAN;
    }

    void checkpoint(boolean force, long sizeThreshold, long delayThresholdNanos)
        throws IOException
    {
        // Checkpoint lock ensures consistent state between page store and logs.
        synchronized (mCheckpointLock) {
            if (mClosed) {
                return;
            }

            // Now's a good time to clean things up.
            cleanupUnreferencedTrees(null);

            final Node root = mRegistry.mRoot;

            long nowNanos = System.nanoTime();

            if (!force) {
                check: {
                    if (delayThresholdNanos == 0) {
                        break check;
                    }

                    if (delayThresholdNanos > 0 &&
                        ((nowNanos - mLastCheckpointNanos) >= delayThresholdNanos))
                    {
                        break check;
                    }

                    if (mRedoWriter == null || mRedoWriter.shouldCheckpoint(sizeThreshold)) {
                        break check;
                    }

                    // Thresholds not met for a full checkpoint, but sync the
                    // redo log for durability.
                    mRedoWriter.sync();

                    return;
                }

                root.acquireShared();
                try {
                    if (root.mCachedState == CACHED_CLEAN) {
                        // Root is clean, so nothing to do.
                        return;
                    }
                } finally {
                    root.releaseShared();
                }
            }

            mLastCheckpointNanos = nowNanos;

            final RedoWriter redo = mRedoWriter;
            if (redo != null) {
                // File-based redo log should begin writing to a new file.
                redo.prepareCheckpoint();
            }

            {
                // If the commit lock cannot be immediately obtained, it's due to a
                // shared lock being held for a long time. While waiting for the
                // exclusive lock, all other shared requests are queued. By waiting
                // a timed amount and giving up, the exclusive lock request is
                // effectively de-prioritized. For each retry, the timeout is
                // doubled, to ensure that the checkpoint request is not starved.
                Lock commitLock = mPageDb.exclusiveCommitLock();
                while (true) {
                    try {
                        long timeoutMillis = 1;
                        while (!commitLock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) {
                            timeoutMillis <<= 1;
                        }
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException();
                    }

                    // Registry root is infrequently modified, and so shared latch
                    // is usually available. If not, cause might be a deadlock. To
                    // be safe, always release commit lock and start over.
                    if (root.tryAcquireShared()) {
                        break;
                    }

                    commitLock.unlock();
                }
            }

            final long redoPos, redoTxnId;
            if (redo == null) {
                redoPos = 0;
                redoTxnId = 0;
            } else {
                // Capture state while commit lock is held.
                redo.captureCheckpointState();
                redoPos = redo.checkpointPosition();
                redoTxnId = redo.checkpointTransactionId();
            }

            if (mEventListener != null) {
                mEventListener.notify(EventType.CHECKPOINT_BEGIN,
                                      "Checkpoint begin: %1$d", redoPos);
            }

            mCheckpointFlushState = CHECKPOINT_FLUSH_PREPARE;

            UndoLog masterUndoLog;
            try {
                // TODO: I don't like all this activity with exclusive commit
                // lock held. UndoLog can be refactored to store into a special
                // Tree, but this requires more features to be added to Tree
                // first. Specifically, large values and appending to them.

                final long masterUndoLogId;
                synchronized (mTxnIdLock) {
                    int count = mUndoLogCount;
                    if (count == 0) {
                        masterUndoLog = null;
                        masterUndoLogId = 0;
                    } else {
                        masterUndoLog = new UndoLog(this, 0);
                        byte[] workspace = null;
                        for (UndoLog log = mTopUndoLog; log != null; log = log.mPrev) {
                            workspace = log.writeToMaster(masterUndoLog, workspace);
                        }
                        masterUndoLogId = masterUndoLog.topNodeId();
                        if (masterUndoLogId == 0) {
                            // Nothing was actually written to the log.
                            masterUndoLog = null;
                        }
                    }
                }

                mPageDb.commit(new PageDb.CommitCallback() {
                    @Override
                    public byte[] prepare() throws IOException {
                        return flush(redoPos, redoTxnId, masterUndoLogId);
                    }
                });
            } catch (IOException e) {
                if (mCheckpointFlushState == CHECKPOINT_FLUSH_PREPARE) {
                    // Exception was thrown with locks still held.
                    mCheckpointFlushState = CHECKPOINT_NOT_FLUSHING;
                    root.releaseShared();
                    mPageDb.exclusiveCommitLock().unlock();
                }
                throw e;
            }

            if (masterUndoLog != null) {
                // Delete the master undo log, which won't take effect until
                // the next checkpoint.
                masterUndoLog.truncate(false);
            }

            // Note: This step is intended to discard old redo data, but it can
            // get skipped if process exits at this point. Data is discarded
            // again when database is re-opened.
            if (mRedoWriter != null) {
                mRedoWriter.checkpointed(redoPos);
            }

            if (mEventListener != null) {
                double duration = (System.nanoTime() - mLastCheckpointNanos) / 1000000000.0;
                mEventListener.notify(EventType.CHECKPOINT_BEGIN,
                                      "Checkpoint completed in %1$1.3f seconds",
                                      duration, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Method is invoked with exclusive commit lock and shared root node latch
     * held. Both are released by this method.
     */
    private byte[] flush(final long redoPos, final long redoTxnId, final long masterUndoLogId)
        throws IOException
    {
        final long txnId;
        synchronized (mTxnIdLock) {
            txnId = mTxnId;
        }
        final Node root = mRegistry.mRoot;
        final long rootId = root.mId;
        final int stateToFlush = mCommitState;
        mHasCheckpointed = true; // Must be set before switching commit state.
        mCheckpointFlushState = stateToFlush;
        mCommitState = (byte) (stateToFlush ^ 1);
        root.releaseShared();
        mPageDb.exclusiveCommitLock().unlock();

        if (mEventListener != null) {
            mEventListener.notify(EventType.CHECKPOINT_FLUSH, "Flushing all dirty nodes");
        }

        try {
            mAllocator.flushDirtyNodes(stateToFlush);
        } finally {
            mCheckpointFlushState = CHECKPOINT_NOT_FLUSHING;
        }

        byte[] header = new byte[HEADER_SIZE];
        writeIntLE(header, I_ENCODING_VERSION, ENCODING_VERSION);
        writeLongLE(header, I_ROOT_PAGE_ID, rootId);
        writeLongLE(header, I_MASTER_UNDO_LOG_PAGE_ID, masterUndoLogId);
        writeLongLE(header, I_TRANSACTION_ID, txnId);
        writeLongLE(header, I_REDO_POSITION, redoPos);
        writeLongLE(header, I_REDO_TXN_ID, redoTxnId);

        return header;
    }
}
