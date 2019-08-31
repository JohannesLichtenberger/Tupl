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

package org.cojen.tupl;

import java.io.IOException;

import static java.lang.System.arraycopy;

import static org.cojen.tupl.PageOps.*;
import static org.cojen.tupl.Utils.*;

import static java.util.Arrays.compareUnsigned;

/**
 * Manages a persisted collection of fragmented values which should be deleted. Trash is
 * emptied after transactions commit.
 *
 * @author Brian S O'Neill
 */
final class FragmentedTrash {
    private FragmentedTrash() {
    }

    /**
     * Copies a fragmented value to the trash and pushes an entry to the undo
     * log. Caller must hold commit lock.
     *
     * @param entry Node page; starts with variable length key
     * @param keyStart inclusive index into entry for key; includes key header
     * @param keyLen length of key
     * @param valueStart inclusive index into entry for fragmented value; excludes value header
     * @param valueLen length of value
     */
    static void add(BTree trash, LocalTransaction txn, long indexId,
                    /*P*/ byte[] entry, int keyStart, int keyLen, int valueStart, int valueLen)
        throws IOException
    {
        byte[] payload = new byte[valueLen];
        p_copyToArray(entry, valueStart, payload, 0, valueLen);

        BTreeCursor cursor = prepareEntry(trash, txn.txnId());
        byte[] key = cursor.key();
        try {
            // Write trash entry first, ensuring that the undo log entry will refer to
            // something valid. Cursor is bound to a bogus transaction, and so it won't acquire
            // locks or attempt to write to the redo log. A failure here is pretty severe,
            // since it implies that the main database file cannot be written to. One possible
            // "recoverable" cause is a disk full, but this can still cause a database panic if
            // it occurs during critical operations like internal node splits.
            txn.setHasTrash();
            cursor.store(payload);
            cursor.reset();
        } catch (Throwable e) {
            try {
                // Always expected to rethrow an exception, not necessarily the original.
                txn.borked(e, false, true);
            } catch (Throwable e2) {
                e = e2;
            }
            throw closeOnFailure(cursor, e);
        }

        // Now write the undo log entry.

        int tidLen = key.length - 8;
        int payloadLen = keyLen + tidLen;
        if (payloadLen > payload.length) {
            // Cannot re-use existing temporary array.
            payload = new byte[payloadLen];
        }
        p_copyToArray(entry, keyStart, payload, 0, keyLen);
        arraycopy(key, 8, payload, keyLen, tidLen);

        txn.pushUndeleteFragmented(indexId, payload, 0, payloadLen);
    }

    /**
     * Returns a cursor ready to store a new trash entry. Caller must reset or
     * close the cursor when done.
     */
    private static BTreeCursor prepareEntry(BTree trash, long txnId) throws IOException {
        // Key entry format is transaction id prefix, followed by a variable
        // length integer. Integer is reverse encoded, and newer entries within
        // the transaction have lower integer values.

        byte[] prefix = new byte[8];
        encodeLongBE(prefix, 0, txnId);

        BTreeCursor cursor = new BTreeCursor(trash, Transaction.BOGUS);
        try {
            cursor.autoload(false);
            cursor.findGt(prefix);
            byte[] key = cursor.key();
            if (key == null || compareUnsigned(key, 0, 8, prefix, 0, 8) != 0) {
                // Create first entry for this transaction.
                key = new byte[8 + 1];
                arraycopy(prefix, 0, key, 0, 8);
                key[8] = (byte) 0xff;
                cursor.findNearby(key);
            } else {
                // Decrement from previously created entry. Although key will
                // be modified, it doesn't need to be cloned because no
                // transaction was used by the search. The key instance is not
                // shared with the lock manager.
                cursor.findNearby(decrementReverseUnsignedVar(key, 8));
            }
            return cursor;
        } catch (Throwable e) {
            throw closeOnFailure(cursor, e);
        }
    }

    /**
     * Remove an entry from the trash, as an undo operation. Original entry is
     * stored back into index.
     *
     * @param index index to store entry into; pass null to fully delete it instead
     */
    static void remove(BTree trash, long txnId, BTree index, byte[] undoEntry) throws IOException {
        // Extract the index and trash keys.

        /*P*/ byte[] undo = p_transfer(undoEntry);

        byte[] indexKey, trashKey;
        try {
            DatabaseAccess dbAccess = trash.mRoot;
            indexKey = Node.retrieveKeyAtLoc(dbAccess, undo, 0);

            int tidLoc = Node.keyLengthAtLoc(undo, 0);
            int tidLen = undoEntry.length - tidLoc;
            trashKey = new byte[8 + tidLen];
            encodeLongBE(trashKey, 0, txnId);
            p_copyToArray(undo, tidLoc, trashKey, 8, tidLen);
        } finally {
            p_delete(undo);
        }

        remove(trash, index, indexKey, trashKey);
    }

    /**
     * Remove an entry from the trash, as an undo operation. Original entry is
     * stored back into index.
     *
     * @param index index to store entry into; pass null to fully delete it instead
     */
    static void remove(BTree trash, BTree index, byte[] indexKey, byte[] trashKey)
        throws IOException
    {
        BTreeCursor trashCursor = new BTreeCursor(trash, Transaction.BOGUS);
        try {
            trashCursor.find(trashKey);

            if (index == null) {
                deleteFragmented(trash.mDatabase, trashCursor);
            } else {
                byte[] fragmented = trashCursor.value();
                if (fragmented != null) {
                    BTreeCursor ixCursor = new BTreeCursor(index, Transaction.BOGUS);
                    try {
                        ixCursor.find(indexKey);
                        ixCursor.storeFragmented(fragmented);
                        ixCursor.reset();
                    } catch (Throwable e) {
                        throw closeOnFailure(ixCursor, e);
                    }
                    trashCursor.store(null);
                }
            }

            trashCursor.reset();
        } catch (Throwable e) {
            throw closeOnFailure(trashCursor, e);
        }
    }

    /**
     * Non-transactionally deletes all fragmented values for the given
     * top-level transaction.
     */
    static void emptyTrash(BTree trash, long txnId) throws IOException {
        byte[] prefix = new byte[8];
        encodeLongBE(prefix, 0, txnId);

        LocalDatabase db = trash.mDatabase;
        final CommitLock commitLock = db.commitLock();

        BTreeCursor cursor = new BTreeCursor(trash, Transaction.BOGUS);
        try {
            cursor.autoload(false);
            cursor.findGt(prefix);

            while (true) {
                byte[] key = cursor.key();
                if (key == null || compareUnsigned(key, 0, 8, prefix, 0, 8) != 0) {
                    break;
                }

                CommitLock.Shared shared = commitLock.acquireShared();
                try {
                    deleteFragmented(db, cursor);
                } finally {
                    shared.release();
                }

                cursor.next();
            }

            cursor.reset();
        } catch (Throwable e) {
            throw closeOnFailure(cursor, e);
        }
    }

    private static boolean deleteFragmented(LocalDatabase db, Cursor cursor) throws IOException {
        cursor.load();
        byte[] value = cursor.value();
        if (value == null) {
            return false;
        } else {
            /*P*/ byte[] fragmented = p_transfer(value);
            try {
                db.deleteFragments(fragmented, 0, value.length);
                cursor.store(null);
            } finally {
                p_delete(fragmented);
            }
            return true;
        }
    }
}
