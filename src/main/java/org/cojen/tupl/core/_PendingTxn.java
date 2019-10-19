/*
 *  Copyright (C) 2011-2017 Cojen.org
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.cojen.tupl.core;

import java.io.IOException;

/**
 * References an _UndoLog and a set of exclusive locks from a transaction ready to be committed.
 *
 * @author Generated by PageAccessTransformer from PendingTxn.java
 */
/*P*/
final class _PendingTxn extends _LockOwner {
    private final _Lock mFirst;
    private _Lock[] mRest;
    private int mRestSize;

    _TransactionContext mContext;
    long mTxnId;
    long mCommitPos;
    _UndoLog mUndoLog;
    int mHasState;
    private Object mAttachment;

    _PendingTxn mPrev;

    _PendingTxn(_Lock first) {
        mFirst = first;
    }

    @Override
    public final _LocalDatabase getDatabase() {
        _UndoLog undo = mUndoLog;
        return undo == null ? null : undo.getDatabase();
    }

    @Override
    public void attach(Object obj) {
        mAttachment = obj;
    }

    @Override
    public Object attachment() {
        return mAttachment;
    }

    /**
     * Add an exclusive lock into the set, retaining FIFO (queue) order.
     */
    void add(_Lock lock) {
        _Lock first = mFirst;
        if (first == null) {
            throw new IllegalStateException("cannot add lock");
        }
        _Lock[] rest = mRest;
        if (rest == null) {
            rest = new _Lock[8];
            mRest = rest;
            mRestSize = 1;
            rest[0] = lock;
        } else {
            int size = mRestSize;
            if (size >= rest.length) {
                _Lock[] newRest = new _Lock[rest.length << 1];
                System.arraycopy(rest, 0, newRest, 0, rest.length);
                mRest = rest = newRest;
            }
            rest[size] = lock;
            mRestSize = size + 1;
        }
    }

    /**
     * Releases all the locks and then discards the undo log. This object must be discarded
     * afterwards.
     */
    void commit(_LocalDatabase db) throws IOException {
        // See Transaction.commit for more info.

        unlockAll(db);

        _UndoLog undo = mUndoLog;
        if (undo != null) {
            undo.truncate();
            mContext.unregister(undo);
        }

        if ((mHasState & _LocalTransaction.HAS_TRASH) != 0) {
            _FragmentedTrash.emptyTrash(db.fragmentedTrash(), mTxnId);
        }
    }

    /**
     * Applies the undo log, releases all the locks, and then discards the undo log. This
     * object must be discarded afterwards.
     */
    void rollback(_LocalDatabase db) throws IOException {
        // See Transaction.exit for more info.

        _UndoLog undo = mUndoLog;
        if (undo != null) {
            undo.rollback();
        }

        unlockAll(db);

        if (undo != null) {
            mContext.unregister(undo);
        }
    }

    private void unlockAll(_LocalDatabase db) {
        _Lock first = mFirst;
        if (first != null) {
            _LockManager manager = db.mLockManager;
            manager.doUnlock(this, first);
            _Lock[] rest = mRest;
            if (rest != null) {
                for (_Lock lock : rest) {
                    if (lock == null) {
                        return;
                    }
                    manager.doUnlock(this, lock);
                }
            }
        }
    }
}
