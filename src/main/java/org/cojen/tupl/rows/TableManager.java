/*
 *  Copyright (C) 2021 Cojen.org
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
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.cojen.tupl.rows;

import java.io.IOException;

import java.lang.ref.WeakReference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

import java.util.concurrent.TimeUnit;

import org.cojen.tupl.CorruptDatabaseException;
import org.cojen.tupl.Cursor;
import org.cojen.tupl.Index;
import org.cojen.tupl.Table;
import org.cojen.tupl.Transaction;
import org.cojen.tupl.View;

import org.cojen.tupl.util.Worker;

import static org.cojen.tupl.rows.RowUtils.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TableManager<R> {
    final Index mPrimaryIndex;

    final WeakCache<Class<R>, AbstractTable<R>> mTables;

    private final TreeMap<byte[], WeakReference<SecondaryInfo>> mIndexInfos;

    private TreeMap<byte[], IndexBackfill<R>> mIndexBackfills;

    private WeakReference<Worker> mWorkerRef;

    private volatile WeakCache<Object, AbstractTable<R>> mIndexTables;

    TableManager(Index primaryIndex) {
        mPrimaryIndex = primaryIndex;
        mTables = new WeakCache<>();
        mIndexInfos = new TreeMap<>(Arrays::compareUnsigned);
    }

    Table<R> asTable(RowStore rs, Index ix, Class<R> type) throws IOException {
        var table = tryFindTable(type);

        if (table != null) {
            return table;
        }

        table = rs.makeTable(this, ix, type, () -> tryFindTable(type), t -> {
            synchronized (mTables) {
                mTables.put(type, t);
            }
        });

        // Must be called after the table is added to the cache. No harm if called redundantly.
        rs.examineSecondaries(this);

        Worker worker;
        if (mWorkerRef != null && (worker = mWorkerRef.get()) != null) {
            if (ix.isEmpty()) {
                // Backfill of nothing is fast, so wait for it before returning Table to caller.
                worker.join(false);
            }
        }

        return table;
    }

    private AbstractTable<R> tryFindTable(Class<R> type) {
        var table = mTables.get(type);
        if (table == null) {
            synchronized (mTables) {
                table = mTables.get(type);
            }
        }
        return table;
    }

    /**
     * Returns a cache of secondary indexes, as tables. See the RowStore.indexTable method.
     */
    WeakCache<Object, AbstractTable<R>> indexTables() {
        WeakCache<Object, AbstractTable<R>> indexTables = mIndexTables;
        if (indexTables == null) {
            synchronized (this) {
                indexTables = mIndexTables;
                if (indexTables == null) {
                    mIndexTables = indexTables = new WeakCache<>();
                }
            }
        }
        return indexTables;
    }

    /**
     * Update the set of indexes, based on what is found in the given View. If anything
     * changed, a new trigger is installed on all tables. Caller is expected to hold a lock
     * which prevents concurrent calls to this method, which isn't thread-safe.
     *
     * @param rs used to open secondary indexes
     * @param txn holds the lock
     * @param secondaries maps index descriptor to index id and state
     */
    void update(RowStore rs, Transaction txn, View secondaries) throws IOException {
        List<AbstractTable<R>> tables = mTables.copyValues();
        if (tables != null) {
            for (var table : tables) {
                Class<R> rowType = table.rowType();
                RowInfo primaryInfo = RowInfo.find(rowType);
                update(table, rowType, primaryInfo, rs, txn, secondaries);
            }
        } else {
            RowInfo primaryInfo = rs.decodeExisting(txn, null, mPrimaryIndex.id());
            if (primaryInfo != null) {
                update(null, null, primaryInfo, rs, txn, secondaries);
            }
        }
    }

    /**
     * @param table can be null if no table instances exist
     * @param rowType can be null if table is null
     * @param primaryInfo required
     */
    private void update(AbstractTable<R> table, Class<R> rowType, RowInfo primaryInfo,
                        RowStore rs, Transaction txn, View secondaries)
        throws IOException
    {
        int numIndexes = 0;
        try (Cursor c = secondaries.newCursor(txn)) {
            c.autoload(false);
            for (c.first(); c.key() != null; c.next()) {
                numIndexes++;
            }
        }

        // Remove stale entries.
        var it = mIndexInfos.keySet().iterator();
        while (it.hasNext()) {
            byte[] desc = it.next();
            if (!secondaries.exists(txn, desc)) {
                it.remove();
                removeIndexBackfill(desc);
            }
        }

        ArrayList<IndexBackfill> newBackfills = null;

        IndexTriggerMaker<R> maker = null;
        if (numIndexes > 0) {
            maker = new IndexTriggerMaker<R>(rowType, primaryInfo, numIndexes);
        }

        try (Cursor c = secondaries.newCursor(txn)) {
            int i = 0;
            byte[] desc;
            for (c.first(); (desc = c.key()) != null; c.next(), i++) {
                maker.mSecondaryDescriptors[i] = desc;

                WeakReference<SecondaryInfo> infoRef = mIndexInfos.get(desc);
                SecondaryInfo info;
                if (infoRef == null || (info = infoRef.get()) == null) {
                    info = RowStore.indexRowInfo(primaryInfo, desc);
                    mIndexInfos.put(desc, new WeakReference<>(info));
                }
                maker.mSecondaryInfos[i] = info;

                byte[] value = c.value();

                long indexId = decodeLongLE(value, 0);
                Index index = rs.mDatabase.indexById(indexId);
                if (index == null) {
                    throw new CorruptDatabaseException("Secondary index is missing: " + indexId);
                }
                maker.mSecondaryIndexes[i] = index;

                byte state = value[8];
                if (state != 'B') { // not "backfill" state
                    removeIndexBackfill(desc);
                } else {
                    if (mIndexBackfills == null) {
                        mIndexBackfills = new TreeMap<>(Arrays::compareUnsigned);
                    }

                    IndexBackfill<R> backfill = mIndexBackfills.get(desc);

                    if (backfill == null) {
                        backfill = maker.makeBackfill(rs, this, i);
                        mIndexBackfills.put(desc, backfill);
                        if (newBackfills == null) {
                            newBackfills = new ArrayList<>();
                        }
                        newBackfills.add(backfill);
                    }

                    maker.mBackfills[i] = backfill;
                }
            }
        }

        if (mIndexBackfills != null && mIndexBackfills.isEmpty()) {
            mIndexBackfills = null;
        }

        if (table != null && table.supportsSecondaries()) {
            Trigger<R> trigger = null;
            if (maker != null) {
                trigger = maker.makeTrigger(rs, mPrimaryIndex.id());
            }
            table.setTrigger(trigger);
        }

        // Can only safely start new backfills after the new trigger has been installed.

        if (newBackfills != null && !newBackfills.isEmpty()) {
            Worker worker;
            if (mWorkerRef == null || (worker = mWorkerRef.get()) == null) {
                worker = Worker.make(Integer.MAX_VALUE, 10, TimeUnit.SECONDS, r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    return t;
                });
                mWorkerRef = new WeakReference<>(worker);
            }

            for (var backfill : newBackfills) {
                // The backfill must also be installed as a listener before it starts.
                rs.mDatabase.addRedoListener(backfill);

                worker.enqueue(backfill);
            }
        }
    }

    private void removeIndexBackfill(byte[] desc) {
        if (mIndexBackfills != null) {
            // When an IndexBackfill is removed, it doesn't need to be immediately closed. When
            // the old trigger is disabled, it will call the IndexBackfill.unused method.
            mIndexBackfills.remove(desc);
        }
    }
}
