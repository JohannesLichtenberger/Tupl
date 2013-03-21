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
final class RedoLog extends RedoWriter {
    private static final long MAGIC_NUMBER = 431399725605778814L;
    private static final int ENCODING_VERSION = 20130106;

    static int randomInt() {
        Random rnd = new Random();
        int x;
        // Cannot return zero, since it breaks Xorshift RNG.
        while ((x = rnd.nextInt()) == 0);
        return x;
    }

    private final Crypto mCrypto;
    private final File mBaseFile;

    private final boolean mReplayMode;

    private long mLogId;
    private long mPosition;
    private OutputStream mOut;
    private volatile FileChannel mChannel;

    private int mTermRndSeed;

    private long mNextLogId;
    private long mNextPosition;
    private OutputStream mNextOut;
    private FileChannel mNextChannel;
    private int mNextTermRndSeed;

    /**
     * Open for replay.
     *
     * @param logId first log id to open
     */
    RedoLog(DatabaseConfig config, long logId, long redoPos) throws IOException {
        this(config.mCrypto, config.mBaseFile, logId, redoPos, true);
    }

    /**
     * Open after replay.
     *
     * @param logId first log id to open
     */
    RedoLog(DatabaseConfig config, RedoLog replayed) throws IOException {
        this(config.mCrypto, config.mBaseFile, replayed.mLogId, replayed.mPosition, false);
    }

    /**
     * @param logId first log id to open
     */
    RedoLog(Crypto crypto, File baseFile, long logId, long redoPos, boolean replay)
        throws IOException
    {
        super(4096, 0);

        mCrypto = crypto;
        mBaseFile = baseFile;
        mReplayMode = replay;

        synchronized (this) {
            mLogId = logId;
            mPosition = redoPos;
            if (!replay) {
                openNextFile(logId);
                applyNextFile();
            }
        }
    }

    /**
     * @return all the files which were replayed
     */
    synchronized Set<File> replay(RedoVisitor visitor,
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

                    DataIn din = new DataIn.Stream(mPosition, in);
                    replay(din, visitor, listener);
                    mPosition = din.mPos;
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

    private void openNextFile(long logId) throws IOException {
        final File file = fileFor(mBaseFile, logId);
        if (file.exists()) {
            throw new FileNotFoundException("Log file already exists: " + file.getPath());
        }

        mNextLogId = logId;

        FileOutputStream fout = new FileOutputStream(file);
        mNextChannel = fout.getChannel();
        if (mCrypto == null) {
            mNextOut = fout;
        } else {
            try {
                mNextOut = mCrypto.newEncryptingStream(logId, fout);
            } catch (GeneralSecurityException e) {
                throw new DatabaseException(e);
            }
        }

        mNextTermRndSeed = randomInt();

        byte[] buf = new byte[8 + 4 + 8 + 4];
        int offset = 0;
        Utils.writeLongLE(buf, offset, MAGIC_NUMBER); offset += 8;
        Utils.writeIntLE(buf, offset, ENCODING_VERSION); offset += 4;
        Utils.writeLongLE(buf, offset, logId); offset += 8;
        Utils.writeIntLE(buf, offset, mNextTermRndSeed); offset += 4;
        if (offset != buf.length) {
            throw new AssertionError();
        }

        try {
            mNextOut.write(buf);
        } catch (IOException e) {
            Utils.closeQuietly(null, mNextOut);
            file.delete();
            throw e;
        }
    }

    private void applyNextFile() throws IOException {
        final OutputStream oldOut;
        final FileChannel oldChannel;
        synchronized (this) {
            oldOut = mOut;
            oldChannel = mChannel;

            if (oldOut != null) {
                endFile();
            }

            mNextPosition = mPosition;

            mOut = mNextOut;
            mChannel = mNextChannel;
            mTermRndSeed = mNextTermRndSeed;
            mLogId = mNextLogId;

            timestamp();
            reset();
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

    @Override
    boolean isOpen() {
        FileChannel channel = mChannel;
        return channel != null && channel.isOpen();
    }

    @Override
    boolean shouldCheckpoint(long size) {
        try {
            FileChannel channel = mChannel;
            return channel != null && channel.size() >= size;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    void checkpointPrepare() throws IOException {
        if (mReplayMode) {
            throw new IllegalStateException();
        }
        final long logId;
        synchronized (this) {
            logId = mLogId;
        }
        openNextFile(logId + 1);
    }

    @Override
    void checkpointSwitch() throws IOException {
        applyNextFile();
    }

    @Override
    long checkpointNumber() {
        return mNextLogId;
    }

    @Override
    long checkpointPosition() {
        return mNextPosition;
    }

    @Override
    long checkpointTransactionId() {
        // Log file always begins with a reset.
        return 0;
    }

    @Override
    void checkpointStarted() throws IOException {
        // Nothing to do.
    }

    @Override
    void checkpointFinished() throws IOException {
        deleteOldFile(mBaseFile, mNextLogId - 1);
    }

    @Override
    void write(byte[] buffer, int len) throws IOException {
        mPosition += len;
        mOut.write(buffer, 0, len);
    }

    @Override
    void force(boolean metadata) throws IOException {
        FileChannel channel = mChannel;
        if (channel != null) {
            try {
                channel.force(metadata);
            } catch (ClosedChannelException e) {
                // Ignore.
            }
        }
    }

    @Override
    void forceAndClose() throws IOException {
        FileChannel channel = mChannel;
        if (channel != null) {
            try {
                channel.force(true);
                try {
                    channel.close();
                } catch (IOException e) {
                    // Ignore.
                }
            } catch (ClosedChannelException e) {
                // Ignore.
            }
        }
    }

    @Override
    void writeTerminator() throws IOException {
        writeIntLE(nextTermRnd());
    }

    // Caller must be synchronized (replay is exempt)
    int nextTermRnd() throws IOException {
        // Xorshift RNG by George Marsaglia.
        int x = mTermRndSeed;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        mTermRndSeed = x;
        return x;
    }

    private void replay(DataIn in, RedoVisitor visitor, EventListener listener)
        throws IOException
    {
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

        try {
            new RedoLogDecoder(this, in, listener).run(visitor);
        } catch (EOFException e) {
            listener.notify(EventType.RECOVERY_REDO_LOG_CORRUPTION, "Unexpected end of file");
        }
    }
}
