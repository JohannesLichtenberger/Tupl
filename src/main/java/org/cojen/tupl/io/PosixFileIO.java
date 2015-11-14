/*
 *  Copyright 2015 Cojen.org
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

package org.cojen.tupl.io;

import java.io.EOFException;
import java.io.File;
import java.io.InterruptedIOException;
import java.io.IOException;

import java.nio.ByteBuffer;

import java.nio.channels.ClosedChannelException;

import java.util.EnumSet;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

import org.cojen.tupl.util.Latch;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class PosixFileIO extends AbstractFileIO {
    static {
        /*
          From the JNA documentation: Direct mapping supports the same type mappings as
          interface mapping, except for arrays of Pointer/Structure/String/WString/NativeMapped
          as function arguments. In addition, direct mapping does not support NIO Buffers or
          primitive arrays as types returned by type mappers or NativeMapped.
          Also: varargs isn't supported
         */
        Native.register(Platform.C_LIBRARY_NAME);
    }

    private final File mFile;
    private final boolean mSyncMode;

    private final Latch mAccessLatch;
    private final ThreadLocal<BufRef> mBufRef;

    private int mFileDescriptor;

    PosixFileIO(File file, EnumSet<OpenOption> options) throws IOException {
        super(options);

        mFile = file;
        mSyncMode = options.contains(OpenOption.SYNC_IO);

        if (options.contains(OpenOption.CREATE)) {
            new JavaFileIO(file, options, 1, false).close();
        }

        mAccessLatch = new Latch();

        mAccessLatch.acquireExclusive();
        try {
            mFileDescriptor = openFd(file, options);
        } finally {
            mAccessLatch.releaseExclusive();
        }

        mBufRef = new ThreadLocal<>();

        if (options.contains(OpenOption.MAPPED)) {
            map();
        }
    }

    @Override
    protected long doLength() throws IOException {
        mAccessLatch.acquireShared();
        try {
            return lseekFd(fd(), 0, 2); // whence = SEEK_END
        } finally {
            mAccessLatch.releaseShared();
        }
    }

    @Override
    protected void doSetLength(long length) throws IOException {
        mAccessLatch.acquireShared();
        try {
            ftruncateFd(fd(), length);
        } finally {
            mAccessLatch.releaseShared();
        }
    }

    @Override
    protected void doRead(long pos, byte[] buf, int offset, int length) throws IOException {
        BufRef ref = bufRef(length);
        doRead(pos, ref.mPointer, length);
        ByteBuffer bb = ref.mBuffer;
        bb.position(0);
        bb.get(buf, offset, length);
    }

    @Override
    protected void doRead(long pos, long ptr, int length) throws IOException {
        mAccessLatch.acquireShared();
        try {
            preadFd(fd(), ptr, length, pos);
        } finally {
            mAccessLatch.releaseShared();
        }
    }

    @Override
    protected void doWrite(long pos, byte[] buf, int offset, int length) throws IOException {
        BufRef ref = bufRef(length);
        ByteBuffer bb = ref.mBuffer;
        bb.position(0);
        bb.put(buf, offset, length);
        doWrite(pos, ref.mPointer, length);
    }

    @Override
    protected void doWrite(long pos, long ptr, int length) throws IOException {
        mAccessLatch.acquireShared();
        try {
            pwriteFd(fd(), ptr, length, pos);
        } finally {
            mAccessLatch.releaseShared();
        }
    }

    @Override
    protected Mapping openMapping(boolean readOnly, long pos, int size) throws IOException {
        return new PosixMapping(mFileDescriptor, readOnly, pos, size);
    }

    @Override
    protected void reopen() throws IOException {
        mAccessLatch.acquireShared();
        try {
            closeFd(fd());

            EnumSet<OpenOption> options = EnumSet.noneOf(OpenOption.class);
            if (isReadOnly()) {
                options.add(OpenOption.READ_ONLY);
            }
            if (mSyncMode) {
                options.add(OpenOption.SYNC_IO);
            }

            mFileDescriptor = openFd(mFile, options);
        } finally {
            mAccessLatch.releaseShared();
        }
    }

    @Override
    protected void doSync(boolean metadata) throws IOException {
        mAccessLatch.acquireShared();
        try {
            int fd = fd();
            if (metadata) {
                fsyncFd(fd);
            } else {
                fdatasyncFd(fd);
            }
        } finally {
            mAccessLatch.releaseShared();
        }
    }

    @Override
    public void close() throws IOException {
        close(null);
    }

    @Override
    public void close(Throwable cause) throws IOException {
        int fd;

        mAccessLatch.acquireExclusive();
        try {
            fd = mFileDescriptor;
            if (fd == 0) {
                return;
            }
            mCause = cause;
            mFileDescriptor = 0;
        } finally {
            mAccessLatch.releaseExclusive();
        }

        IOException ex = null;
        try {
            unmap(false);
        } catch (IOException e) {
            ex = e;
        }

        try {
            closeFd(fd);
        } catch (IOException e) {
            if (ex != null) {
                e.addSuppressed(ex);
            }
            throw e;
        }
    }

    private BufRef bufRef(int size) {
        BufRef ref = mBufRef.get();
        if (ref == null || ref.mBuffer.capacity() < size) {
            ref = new BufRef(ByteBuffer.allocateDirect(size));
            mBufRef.set(ref);
        }
        return ref;
    }

    // Caller must hold mAccessLatch.
    private int fd() throws IOException {
        int fd = mFileDescriptor;
        if (fd == 0) {
            IOException ex = new ClosedChannelException();
            ex.initCause(mCause);
            throw ex;
        }
        return fd;
    }

    /**
     * @return fd
     */
    static int openFd(File file, EnumSet<OpenOption> options) throws IOException {
        // Select O_RDONLY or O_RDWR.
        int flags = 0;
        if (!options.contains(OpenOption.READ_ONLY)) {
            flags |= 2;
        }

        if (options.contains(OpenOption.SYNC_IO)) {
            flags |= 0x80;
        }

        int fd = open(file.getPath(), flags);

        if (fd == -1) {
            throw lastErrorToException();
        }

        return fd;
    }

    static long lseekFd(int fd, long fileOffset, int whence) throws IOException {
        long result = lseek(fd, fileOffset, whence);
        if (result == -1) {
            throw lastErrorToException();
        }
        return result;
    }

    static void preadFd(int fd, long bufPtr, int length, long fileOffset) throws IOException {
        while (true) {
            int amt = pread(fd, bufPtr, length, fileOffset);
            if (amt <= 0) {
                if (amt < 0) {
                    throw lastErrorToException();
                }
                if (length > 0) {
                    throw new EOFException("Attempt to read past end of file: " + fileOffset);
                }
                return;
            }
            length -= amt;
            if (length <= 0) {
                return;
            }
            bufPtr += amt;
            fileOffset += amt;
        }
    }

    static void pwriteFd(int fd, long bufPtr, int length, long fileOffset) throws IOException {
        while (true) {
            int amt = pwrite(fd, bufPtr, length, fileOffset);
            if (amt < 0) {
                throw lastErrorToException();
            }
            length -= amt;
            if (length <= 0) {
                return;
            }
            bufPtr += amt;
            fileOffset += amt;
        }
    }

    static void ftruncateFd(int fd, long length) throws IOException {
        if (ftruncate(fd, length) == -1) {
            throw lastErrorToException();
        }
    }

    static void fsyncFd(int fd) throws IOException {
        int result;
        if (Platform.isMac()) {
            result = fcntl(fd, 51); // F_FULLFSYNC
        } else {
            result = fsync(fd);
        }
        if (result == -1) {
            throw lastErrorToException();
        }
    }

    static void fdatasyncFd(int fd) throws IOException {
        if (fdatasync(fd) == -1) {
            throw lastErrorToException();
        }
    }

    static void closeFd(int fd) throws IOException {
        if (close(fd) == -1) {
            throw lastErrorToException();
        }
    }

    static long mmapFd(long length, int prot, int flags, int fd, long offset) throws IOException {
        long ptr = mmap(0, length, prot, flags, fd, 0);
        if (ptr == -1) {
            throw lastErrorToException();
        }
        return ptr;
    }

    static void msyncAddr(long addr, long length) throws IOException {
        if (msync(addr, length, 4) == -1) { // flags = MS_SYNC
            throw lastErrorToException();
        }
    }

    static void munmapAddr(long addr, long length) throws IOException {
        if (munmap(addr, length) == -1) {
            throw lastErrorToException();
        }
    }

    static IOException lastErrorToException() {
        return new IOException(strerror_r(Native.getLastError(), null, 0));
    }

    static class BufRef {
        final ByteBuffer mBuffer;
        final long mPointer;

        BufRef(ByteBuffer buffer) {
            mBuffer = buffer;
            mPointer = Pointer.nativeValue(Native.getDirectBufferPointer(buffer));
        }
    }

    static native String strerror_r(int errnum, char[] buf, int buflen);

    static native int open(String path, int oflag);

    static native long lseek(int fd, long fileOffset, int whence);

    static native int pread(int fd, long bufPtr, int length, long fileOffset);

    static native int pwrite(int fd, long bufPtr, int length, long fileOffset);

    static native int ftruncate(int fd, long length);

    static native int fcntl(int fd, int cmd);

    static native int fsync(int fd);

    static native int fdatasync(int fd);

    static native int close(int fd);

    static native long mmap(long addr, long length, int prot, int flags, int fd, long offset);

    static native int msync(long addr, long length, int flags);

    static native int munmap(long addr, long length);
}
