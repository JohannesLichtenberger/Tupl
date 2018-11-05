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

package org.cojen.tupl.io;

import java.lang.reflect.Field;

/**
 * Utility for accessing the unsupported Unsafe class.
 *
 * @author Brian S O'Neill
 */
@SuppressWarnings("restriction")
public class UnsafeAccess {
    private static final sun.misc.Unsafe UNSAFE;
    private static final Throwable UNSUPPORTED;

    static {
        sun.misc.Unsafe unsafe = null;
        Throwable unsupported = null;

        try {
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        } catch (Throwable e) {
            unsupported = e;
        }

        UNSAFE = unsafe;
        UNSUPPORTED = unsupported;
    }

    private UnsafeAccess() {
    }

    /**
     * @return null if not supported
     */
    public static sun.misc.Unsafe tryObtain() {
        return UNSAFE;
    }

    /**
     * @throws UnsupportedOperationException if not supported
     */
    public static sun.misc.Unsafe obtain() throws UnsupportedOperationException {
        if (UNSAFE == null) {
            throw new UnsupportedOperationException(UNSUPPORTED);
        }
        return UNSAFE;
    }

    /**
     * @return object to pass to enableIllegalAccessLogger
     */
    static Object disableIllegalAccessLogger() {
        try {
            Class clazz = Class.forName("jdk.internal.module.IllegalAccessLogger");
            Field field = clazz.getDeclaredField("logger");
            long offset = UNSAFE.staticFieldOffset(field);
            Object original = UNSAFE.getObjectVolatile(clazz, offset);
            UNSAFE.putObjectVolatile(clazz, offset, null);
            return original;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * @param logger provided by disableIllegalAccessLogger
     */
    static void enableIllegalAccessLogger(Object logger) {
        if (logger != null) {
            try {
                Class clazz = Class.forName("jdk.internal.module.IllegalAccessLogger");
                Field field = clazz.getDeclaredField("logger");
                long offset = UNSAFE.staticFieldOffset(field);
                UNSAFE.putObjectVolatile(clazz, offset, logger);
            } catch (Throwable e) {
            }
        }
    }
}
