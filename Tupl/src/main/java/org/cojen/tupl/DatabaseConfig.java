/*
 *  Copyright 2011 Brian S O'Neill
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

import java.io.File;

import java.util.concurrent.TimeUnit;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class DatabaseConfig {
    File mBaseFile;
    int mMinCache;
    int mMaxCache;
    DurabilityMode mDurabilityMode;
    long mLockTimeoutNanos;
    boolean mReadOnly;
    int mPageSize;
    UndoLog.RollbackHandler mRollbackHandler;

    public static DatabaseConfig newConfig() {
        return new DatabaseConfig();
    }

    public DatabaseConfig() {
        setDurabilityMode(null);
        setLockTimeout(1, TimeUnit.SECONDS);
    }

    /**
     * Set the base file name for the database, which is required.
     */
    public DatabaseConfig setBaseFile(File file) {
        mBaseFile = file;
        return this;
    }

    /**
     * Set the minimum number of cached nodes, overriding the default.
     */
    public DatabaseConfig setMinCachedNodes(int min) {
        mMinCache = min;
        return this;
    }

    /**
     * Set the maximum number of cached nodes, overriding the default.
     */
    public DatabaseConfig setMaxCachedNodes(int max) {
        mMaxCache = max;
        return this;
    }

    /**
     * Set the default transaction durability mode, which is {@link
     * DurabilityMode#SYNC SYNC} if not set.
     */
    public DatabaseConfig setDurabilityMode(DurabilityMode durabilityMode) {
        if (durabilityMode == null) {
            durabilityMode = DurabilityMode.SYNC;
        }
        mDurabilityMode = durabilityMode;
        return this;
    }

    /**
     * Set the default lock acquisition timeout, which is 1 second if not
     * set. A negative timeout is infinite.
     */
    public DatabaseConfig setLockTimeoutMillis(long timeoutMillis) {
        return setLockTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Set the default lock acquisition timeout, which is 1 second if not
     * set. A negative timeout is infinite.
     *
     * @param unit required unit if timeout is more than zero
     */
    public DatabaseConfig setLockTimeout(long timeout, TimeUnit unit) {
        if (timeout < 0) {
            mLockTimeoutNanos = -1;
        } else if (timeout == 0) {
            mLockTimeoutNanos = 0;
        } else {
            if ((timeout = unit.toNanos(timeout)) < 0) {
                timeout = 0;
            }
            mLockTimeoutNanos = timeout;
        }
        return this;
    }

    public DatabaseConfig setReadOnly(boolean readOnly) {
        mReadOnly = readOnly;
        return this;
    }

    public DatabaseConfig setPageSize(int size) {
        mPageSize = size;
        return this;
    }

    public DatabaseConfig setRollbackHandler(UndoLog.RollbackHandler handler) {
        mRollbackHandler = handler;
        return this;
    }
}
