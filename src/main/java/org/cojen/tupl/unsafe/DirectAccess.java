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

package org.cojen.tupl.unsafe;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import java.nio.Buffer;
import java.nio.ByteBuffer;

import org.cojen.tupl.unsafe.UnsafeAccess;

/**
 * Backdoor access to DirectByteBuffer.
 *
 * @author Brian S O'Neill
 */
public class DirectAccess {
    private static final sun.misc.Unsafe UNSAFE = UnsafeAccess.tryObtain();

    private static final Class<?> cDirectByteBufferClass;
    private static final long cDirectAddressOffset;
    private static final long cDirectCapacityOffset;
    private static final ThreadLocal<ByteBuffer> cLocalBuffer;
    private static final ThreadLocal<ByteBuffer> cLocalBuffer2;

    private static volatile int cDeleteSupport;

    static {
        Class<?> clazz;
        long addrOffset, capOffset;
        ThreadLocal<ByteBuffer> local;
        ThreadLocal<ByteBuffer> local2;

        try {
            clazz = Class.forName("java.nio.DirectByteBuffer");

            addrOffset = UNSAFE.objectFieldOffset(Buffer.class.getDeclaredField("address"));
            capOffset = UNSAFE.objectFieldOffset(Buffer.class.getDeclaredField("capacity"));

            local = new ThreadLocal<>();
            local2 = new ThreadLocal<>();
        } catch (Throwable e) {
            clazz = null;
            addrOffset = 0;
            capOffset = 0;
            local = null;
            local2 = null;
        }

        cDirectByteBufferClass = clazz;
        cDirectAddressOffset = addrOffset;
        cDirectCapacityOffset = capOffset;
        cLocalBuffer = local;
        cLocalBuffer2 = local2;
    }

    private final ThreadLocal<ByteBuffer> mLocalBuffer;

    /**
     * @throws UnsupportedOperationException if not supported
     */
    public DirectAccess() {
        if (!isSupported()) {
            throw new UnsupportedOperationException();
        }
        mLocalBuffer = new ThreadLocal<>();
    }

    /**
     * Returns an instance-specific thread-local ByteBuffer which references any memory
     * address. The position is set to zero, the limit and capacity are set to the given
     * length.
     *
     * @throws UnsupportedOperationException if not supported
     */
    public ByteBuffer prepare(long ptr, int length) {
        return ref(mLocalBuffer, ptr, length);
    }

    public static boolean isSupported() {
        return cLocalBuffer2 != null;
    }

    /**
     * Returns a thread-local ByteBuffer which references any memory address. The position is
     * set to zero, the limit and capacity are set to the given length.
     *
     * @throws UnsupportedOperationException if not supported
     */
    public static ByteBuffer ref(long ptr, int length) {
        return ref(cLocalBuffer, ptr, length);
    }

    /**
     * Returns a second independent thread-local ByteBuffer.
     *
     * @throws UnsupportedOperationException if not supported
     */
    public static ByteBuffer ref2(long ptr, int length) {
        return ref(cLocalBuffer2, ptr, length);
    }

    public static long getAddress(Buffer buf) {
        if (!buf.isDirect()) {
            throw new IllegalArgumentException("Not a direct buffer");
        }
        try {
            return UNSAFE.getLong(buf, cDirectAddressOffset);
        } catch (Exception e) {
            throw new UnsupportedOperationException(e);
        }
    }

    private static ByteBuffer ref(ThreadLocal<ByteBuffer> local, long ptr, int length) {
        if (local == null) {
            throw new UnsupportedOperationException();
        }
        ByteBuffer bb = local.get();
        if (bb == null) {
            bb = allocDirect();
            local.set(bb);
        }
        ref(bb, ptr, length);
        return bb;
    }

    /**
     * Optionally unreference a buffer. The garbage collector does not attempt to free memory
     * referenced by a ByteBuffer created by this class.
     */
    public static void unref(ByteBuffer bb) {
        bb.position(0).limit(0);
        try {
            UNSAFE.putInt(bb, cDirectCapacityOffset, 0);
            UNSAFE.putLong(bb, cDirectAddressOffset, 0);
        } catch (Exception e) {
            throw new UnsupportedOperationException(e);
        }
    }

    public static ByteBuffer allocDirect() {
        try {
            var bb = (ByteBuffer) UNSAFE.allocateInstance(cDirectByteBufferClass);
            bb.clear();
            return bb;
        } catch (Exception e) {
            throw new UnsupportedOperationException(e);
        }
    }

    public static void ref(ByteBuffer bb, long ptr, int length) {
        UNSAFE.putLong(bb, cDirectAddressOffset, ptr);
        UNSAFE.putInt(bb, cDirectCapacityOffset, length);
        bb.position(0).limit(length);
    }

    /**
     * Attempt to delete the given direct or mapped byte buffer.
     */
    public static boolean delete(ByteBuffer bb) {
        if (!bb.isDirect()) {
            return false;
        }

        // https://bugs.openjdk.org/browse/JDK-4724038

        int deleteSupport = cDeleteSupport;

        if (deleteSupport < 0) {
            return false;
        }

        try {
            var u = UnsafeAccess.obtain();
            Method m = u.getClass().getMethod("invokeCleaner", ByteBuffer.class);
            m.invoke(u, bb);
            return true;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException) {
                // Duplicate or slice.
                return false;
            }
            // Unsupported.
        } catch (Throwable e) {
            // Unsupported.
        }

        cDeleteSupport = -1;
        return false;
    }
}
