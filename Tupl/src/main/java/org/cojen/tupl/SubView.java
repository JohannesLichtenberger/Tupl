/*
 *  Copyright 2012 Brian S O'Neill
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

/**
 * 
 *
 * @author Brian S O'Neill
 */
abstract class SubView implements View {
    final View mSource;

    SubView(View source) {
        mSource = source;
    }

    @Override
    public byte[] load(Transaction txn, byte[] key) throws IOException {
        return inRange(key) ? mSource.load(txn, key) : null;
    }

    @Override
    public void store(Transaction txn, byte[] key, byte[] value) throws IOException {
        if (inRange(key)) {
            mSource.store(txn, key, value);
        } else {
            throw new IllegalArgumentException("Key is outside allowed range");
        }
    }

    @Override
    public boolean insert(Transaction txn, byte[] key, byte[] value) throws IOException {
        if (inRange(key)) {
            return mSource.insert(txn, key, value);
        }
        if (value == null) {
            return true;
        }
        throw new IllegalArgumentException("Key is outside allowed range");
    }

    @Override
    public boolean replace(Transaction txn, byte[] key, byte[] value) throws IOException {
        return inRange(key) ? mSource.replace(txn, key, value) : false;
    }

    @Override
    public boolean update(Transaction txn, byte[] key, byte[] oldValue, byte[] newValue)
        throws IOException
    {
        if (inRange(key)) {
            return mSource.update(txn, key, oldValue, newValue);
        }
        if (oldValue == null) {
            if (newValue == null) {
                return true;
            }
            throw new IllegalArgumentException("Key is outside allowed range");
        }
        return false;
    }

    @Override
    public boolean delete(Transaction txn, byte[] key) throws IOException {
        return inRange(key) ? mSource.delete(txn, key) : false;
    }

    @Override
    public boolean remove(Transaction txn, byte[] key, byte[] value) throws IOException {
        return inRange(key) ? mSource.remove(txn, key, value) : (value == null);
    }

    @Override
    public View viewReverse() {
        return new ReverseView(this);
    }

    abstract boolean inRange(byte[] key);
}
