/*
 *  Copyright 2011-2012 Brian S O'Neill
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

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Random;

import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;

import java.security.GeneralSecurityException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class RedoLog extends CauseCloseable implements Checkpointer.Shutdown {
    private static final long MAGIC_NUMBER = 431399725605778814L;
    private static final int ENCODING_VERSION = 20120801;

    private static final byte
        /** timestamp: long */
        OP_TIMESTAMP = 1,

        /** timestamp: long */
        OP_SHUTDOWN = 2,

        /** timestamp: long */
        OP_CLOSE = 3,

        /** timestamp: long */
        OP_END_FILE = 4,

        /** txnId: long */
        //OP_TXN_BEGIN = 5,

        /** txnId: long, parentTxnId: long */
        //OP_TXN_BEGIN_CHILD = 6,

        /** txnId: long */
        //OP_TXN_CONTINUE = 7,

        /** txnId: long, parentTxnId: long */
        //OP_TXN_CONTINUE_CHILD = 8,

        /** txnId: long */
        OP_TXN_ROLLBACK = 9,

        /** txnId: long, parentTxnId: long */
        OP_TXN_ROLLBACK_CHILD = 10,

        /** txnId: long */
        OP_TXN_COMMIT = 11,

        /** txnId: long, parentTxnId: long */
        OP_TXN_COMMIT_CHILD = 12,

        /** indexId: long, keyLength: varInt, key: bytes, valueLength: varInt, value: bytes */
        OP_STORE = 16,

        /** indexId: long, keyLength: varInt, key: bytes */
        OP_DELETE = 17,

        /** indexId: long */
        //OP_CLEAR = 18,

        /** txnId: long, indexId: long, keyLength: varInt, key: bytes,
            valueLength: varInt, value: bytes */
        OP_TXN_STORE = 19,

        /** txnId: long, indexId: long, keyLength: varInt, key: bytes,
            valueLength: varInt, value: bytes */
        OP_TXN_STORE_COMMIT = 20,

        /** txnId: long, parentTxnId: long, indexId: long, keyLength: varInt, key: bytes,
            valueLength: varInt, value: bytes */
        OP_TXN_STORE_COMMIT_CHILD = 21,

        /** txnId: long, indexId: long, keyLength: varInt, key: bytes */
        OP_TXN_DELETE = 22,

        /** txnId: long, indexId: long, keyLength: varInt, key: bytes */
        OP_TXN_DELETE_COMMIT = 23,

        /** txnId: long, parentTxnId: long, indexId: long, keyLength: varInt, key: bytes */
        OP_TXN_DELETE_COMMIT_CHILD = 24;

        /** txnId: long, indexId: long */
        //OP_TXN_CLEAR = 25,

        /** txnId: long, indexId: long */
        //OP_TXN_CLEAR_COMMIT = 26,

        /** txnId: long, parentTxnId: long, indexId: long */
        //OP_TXN_CLEAR_COMMIT_CHILD = 27,

        /** length: varInt, data: bytes */
        //OP_CUSTOM = (byte) 128,

        /** txnId: long, length: varInt, data: bytes */
        //OP_TXN_CUSTOM = (byte) 129;

    private static int randomInt() {
        Random rnd = new Random();
        int x;
        // Cannot return zero, since it breaks Xorshift RNG.
        while ((x = rnd.nextInt()) == 0);
        return x;
    }

    private final Crypto mCrypto;
    private final File mBaseFile;

    private final byte[] mBuffer;
    private int mBufferPos;

    private final boolean mReplayMode;

    private long mLogId;
    private OutputStream mOut;
    private volatile FileChannel mChannel;

    private boolean mAlwaysFlush;

    private int mTermRndSeed;

    private volatile Throwable mCause;

    /**
     * @oaram logId first log id to open
     */
    RedoLog(Crypto crypto, File baseFile, long logId, boolean replay) throws IOException {
        mCrypto = crypto;
        mBaseFile = baseFile;
        mBuffer = new byte[4096];
        mReplayMode = replay;

        synchronized (this) {
            mLogId = logId;
            if (!replay) {
                openNewFile(logId);
            }
        }
    }

    synchronized long logId() {
        return mLogId;
    }

    /**
     * @param scanned files scanned in previous replay
     * @return all the files which were replayed
     */
    synchronized Set<File> replay(RedoLogVisitor visitor, Set<File> scanned,
                                  EventListener listener, EventType type, String message)
        throws IOException
    {
        if (!mReplayMode || mBaseFile == null) {
            throw new IllegalStateException();
        }

        try {
            Set<File> files = new LinkedHashSet<File>(2);

            while (true) {
                File file = fileFor(mBaseFile, mLogId);

                if (scanned != null && !scanned.contains(file)) {
                    break;
                }

                InputStream in;
                try {
                    in = new FileInputStream(file);
                } catch (FileNotFoundException e) {
                    break;
                }

                try {
                    if (mCrypto != null) {
                        try {
                            in = mCrypto.newDecryptingStream(mLogId, in);
                        } catch (IOException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new DatabaseException(e);
                        }
                    }

                    if (listener != null) {
                        listener.notify(type, message, mLogId);
                    }

                    files.add(file);

                    replay(new DataIn(in), visitor);
                } catch (EOFException e) {
                    // End of log didn't get completely flushed.
                } finally {
                    Utils.closeQuietly(null, in);
                }

                mLogId++;
            }

            return files;
        } catch (IOException e) {
            throw Utils.rethrow(e, mCause);
        }
    }

    static void deleteOldFile(File baseFile, long logId) {
        fileFor(baseFile, logId).delete();
    }

    void deleteOldFile(long logId) {
        deleteOldFile(mBaseFile, logId);
    }

    /**
     * @return old log file id, which is one less than new one
     */
    long openNewFile() throws IOException {
        if (mReplayMode) {
            throw new IllegalStateException();
        }
        final long oldLogId;
        synchronized (this) {
            oldLogId = mLogId;
        }
        openNewFile(oldLogId + 1);
        return oldLogId;
    }

    private void openNewFile(long logId) throws IOException {
        final File file = fileFor(mBaseFile, logId);
        if (file.exists()) {
            throw new FileNotFoundException("Log file already exists: " + file.getPath());
        }

        final OutputStream out;
        final FileChannel channel;
        final int termRndSeed;
        {
            FileOutputStream fout = new FileOutputStream(file);
            channel = fout.getChannel();
            if (mCrypto == null) {
                out = fout;
            } else {
                try {
                    out = mCrypto.newEncryptingStream(logId, fout);
                } catch (GeneralSecurityException e) {
                    throw new DatabaseException(e);
                }
            }

            byte[] buf = new byte[8 + 4 + 8 + 4];
            int offset = 0;
            Utils.writeLongLE(buf, offset, MAGIC_NUMBER); offset += 8;
            Utils.writeIntLE(buf, offset, ENCODING_VERSION); offset += 4;
            Utils.writeLongLE(buf, offset, logId); offset += 8;
            Utils.writeIntLE(buf, offset, termRndSeed = randomInt()); offset += 4;
            if (offset != buf.length) {
                throw new AssertionError();
            }

            try {
                out.write(buf);
            } catch (IOException e) {
                Utils.closeQuietly(null, out);
                file.delete();
                throw e;
            }
        }

        final OutputStream oldOut;
        final FileChannel oldChannel;
        synchronized (this) {
            oldOut = mOut;
            oldChannel = mChannel;

            if (oldOut != null) {
                writeOp(OP_END_FILE, System.currentTimeMillis());
                writeTerminator();
                doFlush();
            }

            mOut = out;
            mChannel = channel;
            mTermRndSeed = termRndSeed;
            mLogId = logId;

            timestamp();
        }

        if (oldChannel != null) {
            // Make sure any exception thrown by this call is not caught here,
            // because a checkpoint cannot complete successfully if the redo
            // log has not been durably written.
            oldChannel.force(true);
        }

        Utils.closeQuietly(null, oldOut);
    }

    /**
     * @return null if non-durable
     */
    private static File fileFor(File base, long logId) {
        return base == null ? null : new File(base.getPath() + ".redo." + logId);
    }

    public long size() throws IOException {
        FileChannel channel = mChannel;
        return channel == null ? 0 : channel.size();
    }

    public synchronized void flush() throws IOException {
        doFlush();
    }

    public void sync() throws IOException {
        flush();
        force(false);
    }

    private void force(boolean metadata) throws IOException {
        FileChannel channel = mChannel;
        if (channel != null) {
            try {
                channel.force(metadata);
            } catch (ClosedChannelException e) {
            } catch (IOException e) {
                throw Utils.rethrow(e, mCause);
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        close(null);
    }

    @Override
    public synchronized void close(Throwable cause) throws IOException {
        if (cause != null) {
            mCause = cause;
        }
        shutdown(OP_CLOSE);
    }

    @Override
    public void shutdown() {
        try {
            shutdown(OP_SHUTDOWN);
        } catch (IOException e) {
            // Ignore.
        }
    }

    void shutdown(byte op) throws IOException {
        synchronized (this) {
            mAlwaysFlush = true;

            if (mChannel == null || !mChannel.isOpen()) {
                return;
            }

            writeOp(op, System.currentTimeMillis());
            writeTerminator();
            doFlush();

            if (op == OP_CLOSE) {
                mChannel.force(true);
                mChannel.close();
                return;
            }
        }

        force(true);
    }

    public void store(long indexId, byte[] key, byte[] value, DurabilityMode mode)
        throws IOException
    {
        if (key == null) {
            throw new NullPointerException("Key is null");
        }

        boolean sync;
        synchronized (this) {
            if (value == null) {
                writeOp(OP_DELETE, indexId);
                writeUnsignedVarInt(key.length);
                writeBytes(key);
            } else {
                writeOp(OP_STORE, indexId);
                writeUnsignedVarInt(key.length);
                writeBytes(key);
                writeUnsignedVarInt(value.length);
                writeBytes(value);
            }
            writeTerminator();

            sync = conditionalFlush(mode);
        }

        if (sync) {
            force(false);
        }
    }

    public synchronized void txnRollback(long txnId, long parentTxnId) throws IOException {
        if (parentTxnId == 0) {
            writeOp(OP_TXN_ROLLBACK, txnId);
        } else {
            writeOp(OP_TXN_ROLLBACK_CHILD, txnId);
            writeLongLE(parentTxnId);
        }
        writeTerminator();
    }

    /**
     * @return true if caller should call txnCommitSync
     */
    public synchronized boolean txnCommitFull(long txnId, DurabilityMode mode) throws IOException {
        writeOp(OP_TXN_COMMIT, txnId);
        writeTerminator();
        return conditionalFlush(mode);
    }

    /**
     * Called after txnCommitFull.
     */
    public void txnCommitSync() throws IOException {
        force(false);
    }

    public synchronized void txnCommitScope(long txnId, long parentTxnId) throws IOException {
        writeOp(OP_TXN_COMMIT_CHILD, txnId);
        writeLongLE(parentTxnId);
        writeTerminator();
    }

    public synchronized void txnStore(long txnId, long indexId, byte[] key, byte[] value)
        throws IOException
    {
        if (key == null) {
            throw new NullPointerException("Key is null");
        }

        if (value == null) {
            writeOp(OP_TXN_DELETE, txnId);
            writeLongLE(indexId);
            writeUnsignedVarInt(key.length);
            writeBytes(key);
        } else {
            writeOp(OP_TXN_STORE, txnId);
            writeLongLE(indexId);
            writeUnsignedVarInt(key.length);
            writeBytes(key);
            writeUnsignedVarInt(value.length);
            writeBytes(value);
        }

        writeTerminator();
    }

    /*
    public synchronized void txnStoreCommit(long txnId, long parentTxnId,
                                            long indexId, byte[] key, byte[] value)
        throws IOException
    {
        if (key == null) {
            throw new NullPointerException("Key is null");
        }

        if (value == null) {
            if (parentTxnId == 0) {
                writeOp(OP_TXN_DELETE_COMMIT, txnId);
            } else {
                writeOp(OP_TXN_DELETE_COMMIT_CHILD, txnId);
                writeLongLE(parentTxnId);
            }
            writeLongLE(indexId);
            writeUnsignedVarInt(key.length);
            writeBytes(key);
        } else {
            if (parentTxnId == 0) {
                writeOp(OP_TXN_STORE_COMMIT, txnId);
            } else {
                writeOp(OP_TXN_STORE_COMMIT_CHILD, txnId);
                writeLongLE(parentTxnId);
            }
            writeLongLE(indexId);
            writeUnsignedVarInt(key.length);
            writeBytes(key);
            writeUnsignedVarInt(value.length);
            writeBytes(value);
        }

        writeTerminator();
    }
    */

    synchronized void timestamp() throws IOException {
        writeOp(OP_TIMESTAMP, System.currentTimeMillis());
        writeTerminator();
    }

    // Caller must be synchronized.
    private void writeIntLE(int v) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mBufferPos;
        if (pos > buffer.length - 4) {
            doFlush(buffer, pos);
            pos = 0;
        }
        Utils.writeIntLE(buffer, pos, v);
        mBufferPos = pos + 4;
    }

    // Caller must be synchronized.
    private void writeLongLE(long v) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mBufferPos;
        if (pos > buffer.length - 8) {
            doFlush(buffer, pos);
            pos = 0;
        }
        Utils.writeLongLE(buffer, pos, v);
        mBufferPos = pos + 8;
    }

    // Caller must be synchronized.
    private void writeOp(byte op, long operand) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mBufferPos;
        if (pos >= buffer.length - 9) {
            doFlush(buffer, pos);
            pos = 0;
        }
        buffer[pos] = op;
        Utils.writeLongLE(buffer, pos + 1, operand);
        mBufferPos = pos + 9;
    }

    // Caller must be synchronized.
    private void writeTerminator() throws IOException {
        writeIntLE(nextTermRnd());
    }

    // Caller must be synchronized (replay is exempt)
    private int nextTermRnd() throws IOException {
        // Xorshift RNG by George Marsaglia.
        int x = mTermRndSeed;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        mTermRndSeed = x;
        return x;
    }

    // Caller must be synchronized.
    private void writeUnsignedVarInt(int v) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mBufferPos;
        if (pos > buffer.length - 5) {
            doFlush(buffer, pos);
            pos = 0;
        }
        mBufferPos = Utils.writeUnsignedVarInt(buffer, pos, v);
    }

    // Caller must be synchronized.
    private void writeBytes(byte[] bytes) throws IOException {
        writeBytes(bytes, 0, bytes.length);
    }

    // Caller must be synchronized.
    private void writeBytes(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) {
            return;
        }
        byte[] buffer = mBuffer;
        int pos = mBufferPos;
        while (true) {
            if (pos <= buffer.length - length) {
                System.arraycopy(bytes, offset, buffer, pos, length);
                mBufferPos = pos + length;
                return;
            }
            int remaining = buffer.length - pos;
            System.arraycopy(bytes, offset, buffer, pos, remaining);
            doFlush(buffer, buffer.length);
            pos = 0;
            offset += remaining;
            length -= remaining;
        }
    }

    // Caller must be synchronized. Returns true if caller should sync.
    private boolean conditionalFlush(DurabilityMode mode) throws IOException {
        switch (mode) {
        default:
            return false;
        case NO_FLUSH:
            if (mAlwaysFlush) {
                doFlush();
            }
            return false;
        case SYNC:
            doFlush();
            return true;
        case NO_SYNC:
            doFlush();
            return false;
        }
    }

    // Caller must be synchronized.
    private void doFlush() throws IOException {
        doFlush(mBuffer, mBufferPos);
    }

    // Caller must be synchronized.
    private void doFlush(byte[] buffer, int pos) throws IOException {
        try {
            mOut.write(buffer, 0, pos);
            mBufferPos = 0;
        } catch (IOException e) {
            throw Utils.rethrow(e, mCause);
        }
    }

    private void replay(DataIn in, RedoLogVisitor visitor) throws IOException {
        long magic = in.readLongLE();
        if (magic != MAGIC_NUMBER) {
            if (magic == 0) {
                // Assume file was flushed improperly and discard it.
                return;
            }
            throw new DatabaseException("Incorrect magic number in redo log file");
        }

        int version = in.readIntLE();
        if (version != ENCODING_VERSION) {
            throw new DatabaseException("Unsupported redo log encoding version: " + version);
        }

        long id = in.readLongLE();
        if (id != mLogId) {
            throw new DatabaseException
                ("Expected redo log identifier of " + mLogId + ", but actual is: " + id);
        }

        mTermRndSeed = in.readIntLE();

        int op;
        while ((op = in.read()) >= 0) {
            long operand = in.readLongLE();

            switch (op &= 0xff) {
            default:
                throw new DatabaseException("Unknown redo log operation: " + op);

            case 0:
                // Assume redo log did not flush completely.
                return;

            case OP_TIMESTAMP:
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.timestamp(operand);
                break;

            case OP_SHUTDOWN:
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.shutdown(operand);
                break;

            case OP_CLOSE:
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.close(operand);
                break;

            case OP_END_FILE:
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.endFile(operand);
                break;

            case OP_TXN_ROLLBACK:
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.txnRollback(operand, 0);
                break;

            case OP_TXN_ROLLBACK_CHILD:
                long parentTxnId = in.readLongLE();
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.txnRollback(operand, parentTxnId);
                break;

            case OP_TXN_COMMIT:
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.txnCommit(operand, 0);
                break;

            case OP_TXN_COMMIT_CHILD:
                parentTxnId = in.readLongLE();
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.txnCommit(operand, parentTxnId);
                break;

            case OP_STORE:
                byte[] key = in.readBytes();
                byte[] value = in.readBytes();
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.store(operand, key, value);
                break;

            case OP_DELETE:
                key = in.readBytes();
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.store(operand, key, null);
                break;

            case OP_TXN_STORE:
                long indexId = in.readLongLE();
                key = in.readBytes();
                value = in.readBytes();
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.txnStore(operand, indexId, key, value);
                break;

            case OP_TXN_STORE_COMMIT:
                indexId = in.readLongLE();
                key = in.readBytes();
                value = in.readBytes();
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.txnStore(operand, indexId, key, value);
                visitor.txnCommit(operand, 0);
                break;

            case OP_TXN_STORE_COMMIT_CHILD:
                parentTxnId = in.readLongLE();
                indexId = in.readLongLE();
                key = in.readBytes();
                value = in.readBytes();
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.txnStore(operand, indexId, key, value);
                visitor.txnCommit(operand, parentTxnId);
                break;

            case OP_TXN_DELETE:
                indexId = in.readLongLE();
                key = in.readBytes();
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.txnStore(operand, indexId, key, null);
                break;

            case OP_TXN_DELETE_COMMIT:
                indexId = in.readLongLE();
                key = in.readBytes();
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.txnStore(operand, indexId, key, null);
                visitor.txnCommit(operand, 0);
                break;

            case OP_TXN_DELETE_COMMIT_CHILD:
                parentTxnId = in.readLongLE();
                indexId = in.readLongLE();
                key = in.readBytes();
                if (!verifyTerminator(in)) {
                    return;
                }
                visitor.txnStore(operand, indexId, key, null);
                visitor.txnCommit(operand, parentTxnId);
                break;
            }
        }
    }

    /**
     * If false is returned, assume rest of log file is corrupt.
     */
    private boolean verifyTerminator(DataIn in) throws IOException {
        try {
            return in.readIntLE() == nextTermRnd();
        } catch (EOFException e) {
            return false;
        }
    }
}
