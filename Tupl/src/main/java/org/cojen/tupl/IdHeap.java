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

import java.util.NoSuchElementException;

/**
 * Allows long ids to be added any order, but removed in sorted order.
 *
 * @author Brian S O'Neill
 */
final class IdHeap {
    private final long[] mIds;
    private int mSize;

    public IdHeap(int maxSize) {
        // Pad one more id to account for delete requiring an extra alloc if
        // free list node is deleted.
        mIds = new long[maxSize + 1];
    }

    public int size() {
        return mSize;
    }

    public void add(long id) {
        long[] ids = mIds;
        int pos = mSize;
        while (pos > 0) {
            int parentPos = (pos - 1) >>> 1;
            long parentId = ids[parentPos];
            if (id >= parentId) {
                break;
            }
            ids[pos] = parentId;
            pos = parentPos;
        }
        ids[pos] = id;
        // Increment after possible array bounds exception.
        mSize++;
    }

    public long peek() {
        if (mSize <= 0) {
            throw new NoSuchElementException();
        }
        return mIds[0];
    }

    public long remove() {
        final int size = mSize;
        int pos = size - 1;
        if (pos < 0) {
            throw new NoSuchElementException();
        }
        long[] ids = mIds;
        long result = ids[0];
        if (pos != 0) {
            long id = ids[pos];
            pos = 0;
            int half = size >>> 1;
            while (pos < half) {
                int childPos = (pos << 1) + 1;
                long child = ids[childPos];
                int rightPos = childPos + 1;
                if (rightPos < size && child > ids[rightPos]) {
                    child = ids[childPos = rightPos];
                }
                if (id <= child) {
                    break;
                }
                ids[pos] = child;
                pos = childPos;
            }
            ids[pos] = id;
        }
        mSize = size - 1;
        return result;
    }

    public boolean shouldDrain() {
        // Compare and ignore padding added by constructor.
        return mSize >= mIds.length - 1;
    }

    /**
     * Remove and encode all remaining ids, up to the maximum possible. Each id
     * is encoded as a difference from the previous.
     *
     * @return new offset
     */
    public int drain(long prevId, byte[] buffer, int offset, int length) {
        int end = offset + length;
        while (mSize > 0 && offset < end) {
            if (offset > (end - 9)) {
                long id = mIds[0];
                if (offset + DataIO.calcUnsignedVarLongLength(id - prevId) > end) {
                    break;
                }
            }
            long id = remove();
            offset = DataIO.writeUnsignedVarLong(buffer, offset, id - prevId);
            prevId = id;
        }
        return offset;
    }

    /**
     * Remove and encode at least one id into the given buffer, up to the
     * maximum possible. Except for the first, each id is encoded as a
     * difference from the previous.
     *
     * @return new offset
     */
    public int drain(byte[] buffer, int offset, int length) {
        long prevId = remove();
        offset = DataIO.writeUnsignedVarLong(buffer, offset, prevId);
        return drain(prevId, buffer, offset, length);
    }
}
