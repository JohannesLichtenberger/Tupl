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

import java.io.IOException;

import java.util.*;

import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TreeNodeTest {
    public static void main(String[] args) throws Exception {
        java.io.File file = new java.io.File(args[0]);

        final Database db = new Database
            (DatabaseConfig.newConfig().setBaseFile(file).setMinCachedNodes(100000));
        final View view = db.openView("test1");

        /*
        BitSet pages = pstore.tracePages();
        root.tracePages(store, pages);
        System.out.println(pages);
        System.out.println("lost: " + (pages.cardinality() - 2));
        System.out.println(pstore.stats());

        System.exit(0);
        */

        byte[] value = view.get("hello".getBytes());
        System.out.println(value == null ? null : new String(value));

        Map<String, String> map = new TreeMap<String, String>();
        map = null;

        testInsert(map, true, view, "hello", "world");

        Thread t = new Thread() {
            public void run() {
                try {
                    long lastDuration = 0;

                    while (true) {
                        long delay = 1000L - lastDuration;
                        if (delay > 0) {
                            Thread.sleep(delay);
                        }

                        System.out.println("commit...");
                        long start = System.currentTimeMillis();
                        db.commit();
                        long end = System.currentTimeMillis();
                        //System.out.println("...done: " + pstore.stats());
                        System.out.println("...done");

                        //Thread.sleep(500);
                        //System.exit(0);

                        lastDuration = end - start;
                    }
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                    System.exit(1);
                }
            }
        };

        t.setDaemon(true);
        t.start();

        Random rnd = new Random(89234723);

        for (int i=1; i<100000000; i++) {
            /*
            if (i % 100000 == 0) {
                store.commit();
            }
            */
            if (i % 10000 == 0) {
                //System.out.println("" + i + ": " + pstore.stats());
                System.out.println(i);
            }
            boolean fullTest = i >= 99999999;
            fullTest = false;
            if (fullTest) {
                System.out.println(i);
            }
            long k = rnd.nextLong() & Long.MAX_VALUE;
            testInsert(map, fullTest, view,
                       "key-".concat(String.valueOf(k)), "value-".concat(String.valueOf(i)));
            /*
            if (i % 10 == 0) {
                Thread.sleep(1);
            }
            */
        }

        //root.dump(store, "");

        db.commit();
    }

    private static void testInsert(Map<String, String> map, boolean fullTest,
                                   View view, String key, String value)
        throws IOException
    {
        if (map != null) {
            map.put(key, value);
        }

        byte[] bkey = key.getBytes();
        byte[] bvalue = value.getBytes();

        Cursor c = view.newCursor();
        boolean exists = c.find(bkey);
        if (!exists) {
            c.store(bvalue);
        }
        c.reset();

        byte[] fvalue = view.get(bkey);
        try {
            compareArrays(bvalue, fvalue);
        } catch (AssertionError e) {
            System.out.println(CursorTest.string(bkey));
            System.out.println(CursorTest.string(bvalue));
            System.out.println(CursorTest.string(fvalue));
            //root.dump(store, "");
            throw e;
        }

        if (fullTest && map != null) {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                //System.out.println(entry);
                bkey = entry.getKey().getBytes();
                bvalue = entry.getValue().getBytes();
                fvalue = view.get(bkey);
                try {
                    compareArrays(bvalue, fvalue);
                } catch (AssertionError e) {
                    System.out.println("not equals: " + entry);
                    //root.dump(store, "");
                    throw e;
                }
            }
        }
    }

    private static void compareArrays(byte[] a, byte[] b) {
        // Faster than assertArrayEquals method.
        assertTrue(Arrays.equals(a, b));
    }
}
