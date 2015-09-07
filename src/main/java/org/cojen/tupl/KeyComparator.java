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

import java.util.Comparator;

/**
 * Comparator is used for special in-memory mappings.
 *
 * @author Brian S O'Neill
 */
final class KeyComparator implements Comparator<byte[]> {
    static final KeyComparator THE = new KeyComparator();

    private KeyComparator() {
    }

    @Override
    public int compare(byte[] a, byte[] b) {
        return Utils.compareUnsigned(a, 0, a.length, b, 0, b.length);
    }
}
