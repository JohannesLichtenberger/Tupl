/*
 *  Copyright 2013 Brian S O'Neill
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

import java.util.Random;

import org.junit.*;
import static org.junit.Assert.*;

import static org.cojen.tupl.TestUtils.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class CompactTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(CompactTest.class.getName());
    }

    @After
    public void teardown() throws Exception {
        deleteTempDatabases();
        mDb = null;
    }

    protected Database mDb;

    @Test
    public void basic() throws Exception {
        mDb = newTempDatabase();

        final Index ix = mDb.openIndex("test");
        final int seed = 98232;
        final int count = 100_000;

        Random rnd = new Random(seed);
        for (int i=0; i<count; i++) {
            int k = rnd.nextInt();
            byte[] key = ("key" + k).getBytes();
            ix.store(Transaction.BOGUS, key, key);
        }

        rnd = new Random(seed);
        for (int i=0; i<count; i++) {
            int k = rnd.nextInt();
            if (i % 4 != 0) {
                byte[] key = ("key" + k).getBytes();
                ix.delete(Transaction.BOGUS, key);
            }
        }

        Database.Stats stats1 = mDb.stats();

        mDb.compact(null, 0.9);

        Database.Stats stats2 = mDb.stats();

        assertTrue(stats2.freePages() < stats1.freePages());
        assertTrue(stats2.totalPages() < stats1.totalPages());

        assertTrue(mDb.verify(null));

        rnd = new Random(seed);
        for (int i=0; i<count; i++) {
            int k = rnd.nextInt();
            if (i % 4 == 0) {
                byte[] key = ("key" + k).getBytes();
                byte[] value = ix.load(Transaction.BOGUS, key);
                fastAssertArrayEquals(key, value);
            }
        }

        // Compact even further.
        for (int i=91; i<=99; i++) {
            boolean result = mDb.compact(null, i / 100.0);
        }

        Database.Stats stats3 = mDb.stats();

        assertTrue(stats3.freePages() < stats2.freePages());
        assertTrue(stats3.totalPages() < stats2.totalPages());

        assertTrue(mDb.verify(null));

        rnd = new Random(seed);
        for (int i=0; i<count; i++) {
            int k = rnd.nextInt();
            if (i % 4 == 0) {
                byte[] key = ("key" + k).getBytes();
                byte[] value = ix.load(Transaction.BOGUS, key);
                fastAssertArrayEquals(key, value);
            }
        }
    }

    @Test
    public void largeValues() throws Exception {
        mDb = newTempDatabase(new DatabaseConfig()
                              .pageSize(512)
                              .minCacheSize(10_000_000)
                              .durabilityMode(DurabilityMode.NO_FLUSH));

        final Index ix = mDb.openIndex("test");
        final int seed = 1234;
        final int count = 1000;

        Random rnd1 = new Random(seed);
        Random rnd2 = new Random(seed);

        for (int i=0; i<count; i++) {
            int k = rnd1.nextInt();
            byte[] key = ("key" + k).getBytes();
            byte[] value = randomStr(rnd2, 4000);
            ix.store(Transaction.BOGUS, key, value);
        }

        rnd1 = new Random(seed);
        for (int i=0; i<count; i++) {
            int k = rnd1.nextInt();
            if (i % 2 != 0) {
                byte[] key = ("key" + k).getBytes();
                ix.delete(Transaction.BOGUS, key);
            }
        }

        Database.Stats stats1 = mDb.stats();

        mDb.compact(null, 0.9);

        Database.Stats stats2 = mDb.stats();

        assertTrue(stats2.freePages() < stats1.freePages());
        assertTrue(stats2.totalPages() < stats1.totalPages());

        assertTrue(mDb.verify(null));

        rnd1 = new Random(seed);
        rnd2 = new Random(seed);
        for (int i=0; i<count; i++) {
            int k = rnd1.nextInt();
            byte[] v = randomStr(rnd2, 4000);
            if (i % 2 == 0) {
                byte[] key = ("key" + k).getBytes();
                byte[] value = ix.load(Transaction.BOGUS, key);
                fastAssertArrayEquals(v, value);
            }
        }

        // Compact even further.
        for (int i=91; i<=99; i++) {
            boolean result = mDb.compact(null, i / 100.0);
        }

        Database.Stats stats3 = mDb.stats();

        assertTrue(stats3.freePages() < stats2.freePages());
        assertTrue(stats3.totalPages() < stats2.totalPages());

        assertTrue(mDb.verify(null));

        rnd1 = new Random(seed);
        rnd2 = new Random(seed);
        for (int i=0; i<count; i++) {
            int k = rnd1.nextInt();
            byte[] v = randomStr(rnd2, 4000);
            if (i % 2 == 0) {
                byte[] key = ("key" + k).getBytes();
                byte[] value = ix.load(Transaction.BOGUS, key);
                fastAssertArrayEquals(v, value);
            }
        }
    }

    @Test
    public void manualAbort() throws Exception {
        mDb = newTempDatabase();

        final Index ix = mDb.openIndex("test");
        final int seed = 98232;
        final int count = 100_000;

        Random rnd = new Random(seed);
        for (int i=0; i<count; i++) {
            int k = rnd.nextInt();
            byte[] key = ("key" + k).getBytes();
            ix.store(Transaction.BOGUS, key, key);
        }

        rnd = new Random(seed);
        for (int i=0; i<count; i++) {
            int k = rnd.nextInt();
            if (i % 4 != 0) {
                byte[] key = ("key" + k).getBytes();
                ix.delete(Transaction.BOGUS, key);
            }
        }

        Database.Stats stats1 = mDb.stats();

        CompactionObserver obs = new CompactionObserver() {
            private int count;

            @Override
            public boolean indexNodeVisited(long id) {
                return ++count < 100;
            }
        };

        boolean result = mDb.compact(obs, 0.5);
        assertFalse(result);

        Database.Stats stats2 = mDb.stats();
        assertEquals(stats1, stats2);

        assertTrue(mDb.verify(null));
    }

    @Test
    public void autoAbort() throws Exception {
        mDb = newTempDatabase();

        final Index ix = mDb.openIndex("test");
        final int seed = 98232;
        final int count = 100_000;

        Random rnd = new Random(seed);
        for (int i=0; i<count; i++) {
            int k = rnd.nextInt();
            byte[] key = ("key" + k).getBytes();
            ix.store(Transaction.BOGUS, key, key);
        }

        rnd = new Random(seed);
        for (int i=0; i<count; i++) {
            int k = rnd.nextInt();
            if (i % 4 != 0) {
                byte[] key = ("key" + k).getBytes();
                ix.delete(Transaction.BOGUS, key);
            }
        }

        class Obs extends CompactionObserver {
            private int count;
            private boolean resume;

            @Override
            public synchronized boolean indexNodeVisited(long id) {
                if (++count > 100) {
                    try {
                        while (!resume) {
                            wait();
                        }
                    } catch (InterruptedException e) {
                        return false;
                    }
                }
                return true;
            }

            synchronized void resume() {
                this.resume = true;
                notify();
            }
        };

        final Obs obs = new Obs();

        class Compactor extends Thread {
            volatile Object result;

            @Override
            public void run() {
                try {
                    result = mDb.compact(obs, 0.5);
                } catch (Exception e) {
                    result = e;
                }
            }
        };

        Compactor comp = new Compactor();
        comp.start();

        // Compaction will abort because of database growth.
        rnd = new Random(seed);
        for (int i=0; i<count; i++) {
            int k = rnd.nextInt();
            if (i % 4 != 0) {
                byte[] key = ("key" + k).getBytes();
                ix.insert(Transaction.BOGUS, key, key);
            }
        }

        obs.resume();
        comp.join();

        assertEquals(Boolean.FALSE, comp.result);

        assertTrue(mDb.verify(null));

        rnd = new Random(seed);
        for (int i=0; i<count; i++) {
            int k = rnd.nextInt();
            byte[] key = ("key" + k).getBytes();
            byte[] value = ix.load(Transaction.BOGUS, key);
            fastAssertArrayEquals(key, value);
        }
    }
}
