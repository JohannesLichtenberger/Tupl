/*
 *  Copyright 2011-2015 Cojen.org
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

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;

import org.cojen.tupl.util.Latch;
import org.cojen.tupl.util.LatchCondition;

import static org.cojen.tupl.LockResult.*;

/**
 * 
 *
 * @author Generated by PageAccessTransformer from LockManager.java
 */
/*P*/
final class _LockManager {
    // Parameter passed to LockHT.tryLock. For new _Lock instances, value will be stored as-is
    // into _Lock.mLockCount field, which is why the numbers seem a bit weird.
    static final int TYPE_SHARED = 1, TYPE_UPGRADABLE = 0x80000000, TYPE_EXCLUSIVE = ~0;

    private final WeakReference<Database> mDatabaseRef;

    final LockUpgradeRule mDefaultLockUpgradeRule;
    final long mDefaultTimeoutNanos;

    private final LockHT[] mHashTables;
    private final int mHashTableShift;

    private final ThreadLocal<SoftReference<_Locker>> mLocalLockerRef;

    /**
     * @param db optional; used by _DeadlockDetector to resolve index names
     */
    _LockManager(Database db, LockUpgradeRule lockUpgradeRule, long timeoutNanos) {
        this(db, lockUpgradeRule, timeoutNanos, Runtime.getRuntime().availableProcessors() * 16);
    }

    private _LockManager(Database db, LockUpgradeRule lockUpgradeRule, long timeoutNanos,
                        int numHashTables)
    {
        mDatabaseRef = db == null ? null : new WeakReference<>(db);

        if (lockUpgradeRule == null) {
            lockUpgradeRule = LockUpgradeRule.STRICT;
        }
        mDefaultLockUpgradeRule = lockUpgradeRule;
        mDefaultTimeoutNanos = timeoutNanos;

        numHashTables = Utils.roundUpPower2(Math.max(2, numHashTables));
        mHashTables = new LockHT[numHashTables];
        for (int i=0; i<numHashTables; i++) {
            mHashTables[i] = new LockHT();
        }
        mHashTableShift = Integer.numberOfLeadingZeros(numHashTables - 1);

        mLocalLockerRef = new ThreadLocal<>();
    }

    final Index indexById(long id) {
        if (mDatabaseRef != null) {
            Database db = mDatabaseRef.get();
            if (db != null) {
                try {
                    return db.indexById(id);
                } catch (Exception e) {
                }
            }
        }

        return null;
    }

    /**
     * @return total number of locks actively held, of any type
     */
    public long numLocksHeld() {
        long count = 0;
        for (LockHT ht : mHashTables) {
            count += ht.size();
        }
        return count;
    }

    /**
     * Returns true if a shared lock can be granted for the given key. Caller must hold the
     * node latch which contains the key.
     *
     * @param locker optional locker
     */
    final boolean isAvailable(_LockOwner locker, long indexId, byte[] key, int hash) {
        // Note that no LockHT latch is acquired. The current thread is not required to
        // immediately observe the activity of other threads acting upon the same lock. If
        // another thread has just acquired an exclusive lock, it must still acquire the node
        // latch before any changes can be made.
        _Lock lock = getLockHT(hash).lockFor(indexId, key, hash);
        return lock == null ? true : lock.isAvailable(locker);
    }

    final LockResult check(_LockOwner locker, long indexId, byte[] key, int hash) {
        LockHT ht = getLockHT(hash);
        ht.acquireShared();
        try {
            _Lock lock = ht.lockFor(indexId, key, hash);
            return lock == null ? LockResult.UNOWNED : lock.check(locker);
        } finally {
            ht.releaseShared();
        }
    }

    final void unlock(_LockOwner locker, _Lock lock) {
        LockHT ht = getLockHT(lock.mHashCode);
        ht.acquireExclusive();
        try {
            if (lock.unlock(locker, ht)) {
                ht.remove(lock);
            }
        } finally {
            ht.releaseExclusive();
        }
    }

    final void unlockToShared(_LockOwner locker, _Lock lock) {
        LockHT ht = getLockHT(lock.mHashCode);
        ht.acquireExclusive();
        try {
            lock.unlockToShared(locker, ht);
        } finally {
            ht.releaseExclusive();
        }
    }

    final void unlockToUpgradable(_LockOwner locker, _Lock lock) {
        LockHT ht = getLockHT(lock.mHashCode);
        ht.acquireExclusive();
        try {
            lock.unlockToUpgradable(locker, ht);
        } finally {
            ht.releaseExclusive();
        }
    }

    final _PendingTxn transferExclusive(_LockOwner locker, _Lock lock, _PendingTxn pending) {
        LockHT ht = getLockHT(lock.mHashCode);
        ht.acquireExclusive();
        try {
            return lock.transferExclusive(locker, pending);
        } finally {
            ht.releaseExclusive();
        }
    }

    /**
     * Mark a lock as referencing a ghosted entry. Caller must ensure that lock
     * is already exclusively held.
     */
    final void ghosted(_Tree tree, byte[] key, int hash) {
        LockHT ht = getLockHT(hash);
        ht.acquireExclusive();
        try {
            ht.lockFor(tree.mId, key, hash).mSharedLockOwnersObj = tree;
        } finally {
            ht.releaseExclusive();
        }
    }

    final _Locker lockSharedLocal(long indexId, byte[] key, int hash) throws LockFailureException {
        _Locker locker = localLocker();
        LockResult result = getLockHT(hash)
            .tryLock(TYPE_SHARED, locker, indexId, key, hash, mDefaultTimeoutNanos);
        if (result.isHeld()) {
            return locker;
        }
        throw locker.failed(result, mDefaultTimeoutNanos);
    }

    final _Locker lockExclusiveLocal(long indexId, byte[] key, int hash)
        throws LockFailureException
    {
        _Locker locker = localLocker();
        LockResult result = getLockHT(hash)
            .tryLock(TYPE_EXCLUSIVE, locker, indexId, key, hash, mDefaultTimeoutNanos);
        if (result.isHeld()) {
            return locker;
        }
        throw locker.failed(result, mDefaultTimeoutNanos);
    }

    /**
     * Locks and immediately unlocks the given key, unless the lock is uncontended.
     */
    final void lockUnlockSharedLocal(long indexId, byte[] key, int hash)
        throws LockFailureException
    {
        _Locker locker;
        LockResult result;

        LockHT ht = getLockHT(hash);
        ht.acquireExclusive();
        try {
            _Lock lock = ht.lockFor(indexId, key, hash);
            if (lock == null) {
                // Uncontended. The hashtable latch ensures proper happens-before ordering.
                return;
            }
            locker = localLocker();
            result = lock.tryLockShared(ht, locker, mDefaultTimeoutNanos);
            if (result == LockResult.ACQUIRED) {
                if (lock.unlock(locker, ht)) {
                    ht.remove(lock);
                }
                return;
            }
        } finally {
            ht.releaseExclusive();
        }

        throw locker.failed(result, mDefaultTimeoutNanos);
    }

    final _Locker localLocker() {
        SoftReference<_Locker> lockerRef = mLocalLockerRef.get();
        _Locker locker;
        if (lockerRef == null || (locker = lockerRef.get()) == null) {
            mLocalLockerRef.set(new SoftReference<>(locker = new _Locker(this)));
        }
        return locker;
    }

    /**
     * Interrupts all waiters, and exclusive locks are transferred to hidden
     * locker. This prevents them from being acquired again.
     */
    final void close() {
        _Locker locker = new _Locker(null);
        for (LockHT ht : mHashTables) {
            ht.close(locker);
        }
    }

    final static int hash(long indexId, byte[] key) {
        return (int) Hasher.hash(indexId, key);
    }

    LockHT getLockHT(int hash) {
        return mHashTables[hash >>> mHashTableShift];
    }

    /**
     * Simple hashtable of Locks.
     */
    @SuppressWarnings("serial")
    static final class LockHT extends Latch {
        private static final float LOAD_FACTOR = 0.75f;

        private transient _Lock[] mEntries;
        private int mSize;
        private int mGrowThreshold;

        // Padding to prevent cache line sharing.
        private long a0, a1, a2, a3;

        LockHT() {
            // Initial capacity of must be a power of 2.
            mEntries = new _Lock[16];
            mGrowThreshold = (int) (mEntries.length * LOAD_FACTOR);
        }

        int size() {
            acquireShared();
            int size = mSize;
            releaseShared();
            return size;
        }

        /**
         * Caller must hold latch.
         *
         * @return null if not found
         */
        _Lock lockFor(long indexId, byte[] key, int hash) {
            _Lock[] entries = mEntries;
            int index = hash & (entries.length - 1);
            for (_Lock e = entries[index]; e != null; e = e.mLockManagerNext) {
                if (e.matches(indexId, key, hash)) {
                    return e;
                }
            }
            return null;
        }

        /**
         * @param type defined in _Lock class
         */
        LockResult tryLock(int type,
                           _Locker locker, long indexId, byte[] key, int hash,
                           long nanosTimeout)
        {
            _Lock lock;
            LockResult result;
            lockEx: {
                lockNonEx: {
                    acquireExclusive();
                    try {
                        _Lock[] entries = mEntries;
                        int index = hash & (entries.length - 1);
                        for (lock = entries[index]; lock != null; lock = lock.mLockManagerNext) {
                            if (lock.matches(indexId, key, hash)) {
                                if (type == TYPE_SHARED) {
                                    result = lock.tryLockShared(this, locker, nanosTimeout);
                                    break lockNonEx;
                                } else if (type == TYPE_UPGRADABLE) {
                                    result = lock.tryLockUpgradable(this, locker, nanosTimeout);
                                    break lockNonEx;
                                } else {
                                    result = lock.tryLockExclusive(this, locker, nanosTimeout);
                                    break lockEx;
                                }
                            }
                        }

                        if (mSize >= mGrowThreshold) {
                            int capacity = entries.length << 1;
                            _Lock[] newEntries = new _Lock[capacity];
                            int newMask = capacity - 1;

                            for (int i=entries.length; --i>=0 ;) {
                                for (_Lock e = entries[i]; e != null; ) {
                                    _Lock next = e.mLockManagerNext;
                                    int ix = e.mHashCode & newMask;
                                    e.mLockManagerNext = newEntries[ix];
                                    newEntries[ix] = e;
                                    e = next;
                                }
                            }

                            mEntries = entries = newEntries;
                            mGrowThreshold = (int) (capacity * LOAD_FACTOR);
                            index = hash & newMask;
                        }

                        lock = new _Lock();

                        lock.mIndexId = indexId;
                        lock.mKey = key;
                        lock.mHashCode = hash;
                        lock.mLockManagerNext = entries[index];

                        lock.mLockCount = type;
                        if (type == TYPE_SHARED) {
                            lock.mSharedLockOwnersObj = locker;
                        } else {
                            lock.mOwner = locker;
                        }

                        entries[index] = lock;
                        mSize++;
                    } finally {
                        releaseExclusive();
                    }

                    locker.push(lock, 0);
                    return LockResult.ACQUIRED;
                }

                // Result of shared/upgradable attempt for existing _Lock.

                if (result == ACQUIRED) {
                    locker.push(lock, 0);
                }

                return result;
            }

            // Result of exclusive attempt for existing _Lock.

            if (result == ACQUIRED) {
                locker.push(lock, 0);
            } else if (result == UPGRADED) {
                locker.push(lock, 1);
            }

            return result;
        }

        /**
         * @param newLock _Lock instance to insert, unless another already exists. The mIndexId,
         * mKey, and mHashCode fields must be set.
         */
        LockResult tryLockExclusive(_Locker locker, _Lock newLock, long nanosTimeout) {
            int hash = newLock.mHashCode;

            _Lock lock;
            LockResult result;
            lockEx: {
                acquireExclusive();
                try {
                    _Lock[] entries = mEntries;
                    int index = hash & (entries.length - 1);
                    for (lock = entries[index]; lock != null; lock = lock.mLockManagerNext) {
                        if (lock.matches(newLock.mIndexId, newLock.mKey, hash)) {
                            result = lock.tryLockExclusive(this, locker, nanosTimeout);
                            break lockEx;
                        }
                    }

                    if (mSize >= mGrowThreshold) {
                        int capacity = entries.length << 1;
                        _Lock[] newEntries = new _Lock[capacity];
                        int newMask = capacity - 1;

                        for (int i=entries.length; --i>=0 ;) {
                            for (_Lock e = entries[i]; e != null; ) {
                                _Lock next = e.mLockManagerNext;
                                int ix = e.mHashCode & newMask;
                                e.mLockManagerNext = newEntries[ix];
                                newEntries[ix] = e;
                                e = next;
                            }
                        }

                        mEntries = entries = newEntries;
                        mGrowThreshold = (int) (capacity * LOAD_FACTOR);
                        index = hash & newMask;
                    }

                    lock = newLock;
                    lock.mLockManagerNext = entries[index];
                    lock.mLockCount = ~0;
                    lock.mOwner = locker;

                    entries[index] = lock;
                    mSize++;
                } finally {
                    releaseExclusive();
                }

                locker.push(lock, 0);
                return LockResult.ACQUIRED;
            }

            if (result == ACQUIRED) {
                locker.push(lock, 0);
            } else if (result == UPGRADED) {
                locker.push(lock, 1);
            }

            return result;
        }

        /**
         * Caller must hold latch and ensure that _Lock is in hashtable.
         *
         * @throws NullPointerException if lock is not in hashtable
         */
        void remove(_Lock lock) {
            _Lock[] entries = mEntries;
            int index = lock.mHashCode & (entries.length - 1);
            _Lock e = entries[index];
            if (e == lock) {
                entries[index] = e.mLockManagerNext;
            } else while (true) {
                _Lock next = e.mLockManagerNext;
                if (next == lock) {
                    e.mLockManagerNext = next.mLockManagerNext;
                    break;
                }
                e = next;
            }
            mSize--;
        }

        void close(_LockOwner locker) {
            acquireExclusive();
            try {
                if (mSize > 0) {
                    _Lock[] entries = mEntries;
                    for (int i=entries.length; --i>=0 ;) {
                        for (_Lock e = entries[i], prev = null; e != null; ) {
                            _Lock next = e.mLockManagerNext;

                            if (e.mLockCount == ~0) {
                                // Transfer exclusive lock.
                                e.mOwner = locker;
                            } else {
                                // Release and remove lock.
                                e.mLockCount = 0;
                                e.mOwner = null;
                                if (prev == null) {
                                    entries[i] = next;
                                } else {
                                    prev.mLockManagerNext = next;
                                }
                                e.mLockManagerNext = null;
                                mSize--;
                            }

                            e.mSharedLockOwnersObj = null;

                            // Interrupt all waiters.

                            LatchCondition q = e.mQueueU;
                            if (q != null) {
                                q.clear();
                                e.mQueueU = null;
                            }

                            q = e.mQueueSX;
                            if (q != null) {
                                q.clear();
                                e.mQueueSX = null;
                            }

                            prev = e;
                            e = next;
                        }
                    }
                }
            } finally {
                releaseExclusive();
            }
        }
    }
}
