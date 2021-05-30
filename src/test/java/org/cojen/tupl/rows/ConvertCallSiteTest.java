/*
 *  Copyright (C) 2021 Cojen.org
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
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.cojen.tupl.rows;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import java.math.*;

import org.cojen.maker.MethodMaker;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ConvertCallSiteTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(ConvertCallSiteTest.class.getName());
    }

    private static MethodHandle make(Class toType) {
        MethodMaker mm = MethodMaker.begin(MethodHandles.lookup(), toType, "test", Object.class);
        mm.return_(ConvertCallSite.make(mm, toType, mm.param(0)));
        return mm.finish();
    }

    @Test
    public void convertToBoolean() throws Throwable {
        MethodHandle mh = make(Boolean.class);

        pass(mh,
             true, true,
             null, null,
             "truE", true,
             "false", false
             );

        fail(mh, (byte) 1, (short) 1, 1, 1L, 1f, 1d, '1', "1", BigInteger.ONE, BigDecimal.ONE);
    }

    @Test
    public void convertToByte() throws Throwable {
        MethodHandle mh = make(Byte.class);

        pass(mh,
             (byte) 10, (byte) 10,
             null, null,
             (short) 100, (byte) 100,
             101, (byte) 101,
             -100L, (byte) -100,
             10f, (byte) 10,
             100d, (byte) 100,
             "10", (byte) 10,
             BigInteger.valueOf(10), (byte) 10,
             BigDecimal.valueOf(10), (byte) 10
             );

        fail(mh, false, 1000, 10.1f, 10.1d, "hello",
             BigInteger.valueOf(1000), BigDecimal.valueOf(10.1));
    }

    @Test
    public void convertToShort() throws Throwable {
        MethodHandle mh = make(Short.class);

        pass(mh,
             (byte) 10, (short) 10,
             (short) 100, (short) 100,
             null, null,
             1000, (short) 1000,
             10000L, (short) 10000,
             10f, (short) 10,
             100d, (short) 100,
             "10", (short) 10,
             BigInteger.valueOf(1234), (short) 1234,
             BigDecimal.valueOf(1234), (short) 1234
             );

        fail(mh, false, 100_000, 10_000_000_000L, 10.1f, 10.1d, "hello",
             BigInteger.valueOf(10_000_000_000L), BigDecimal.valueOf(10.1));
    }

    @Test
    public void convertToInteger() throws Throwable {
        MethodHandle mh = make(Integer.class);

        pass(mh,
             (byte) 10, 10,
             (short) 100, 100,
             1000, 1000,
             null, null,
             10000L, 10000,
             10f, 10,
             100d, 100,
             "10", 10,
             BigInteger.valueOf(1234), 1234,
             BigDecimal.valueOf(1234), 1234
             );

        fail(mh, false, 10_000_000_000L, 10.1f, 10.1d, "hello",
             BigInteger.valueOf(10_000_000_000L), BigDecimal.valueOf(10.1));
    }

    @Test
    public void convertToLong() throws Throwable {
        MethodHandle mh = make(Long.class);

        pass(mh,
             (byte) 10, 10L,
             (short) 100, 100L,
             1000, 1000L,
             10000L, 10000L,
             null, null,
             10f, 10L,
             100d, 100L,
             "10000000000", 10_000_000_000L,
             BigInteger.valueOf(10_000_000_000L), 10_000_000_000L,
             BigDecimal.valueOf(10_000_000_000L), 10_000_000_000L
             );

        fail(mh, true, 10.1f, 10.1d, "hello",
             new BigInteger("999999999999999999999999"), BigDecimal.valueOf(10.1));
    }

    @Test
    public void convertToFloat() throws Throwable {
        MethodHandle mh = make(Float.class);

        pass(mh,
             (byte) 10, 10f,
             (short) 100, 100f,
             1000, 1000f,
             10000L, 10000f,
             10f, 10f,
             null, null,
             100d, 100f,
             "10.1", 10.1f,
             BigInteger.valueOf(1234), 1234f,
             BigDecimal.valueOf(1234.125), 1234.125f
             );

        fail(mh, false, Integer.MAX_VALUE - 1, 10_000_000_001L, Math.PI, "hello",
             BigInteger.valueOf(10_000_000_000L), BigDecimal.valueOf(1234.1d));
    }

    @Test
    public void convertToDouble() throws Throwable {
        MethodHandle mh = make(Double.class);

        pass(mh,
             (byte) 10, 10d,
             (short) 100, 100d,
             1000, 1000d,
             10000L, 10000d,
             10f, 10d,
             100d, 100d,
             null, null,
             "10.1", 10.1d,
             BigInteger.valueOf(1234), 1234d,
             BigDecimal.valueOf(1234.125), 1234.125d
             );

        fail(mh, true, Long.MAX_VALUE - 1, "hello",
             BigInteger.valueOf(Long.MAX_VALUE - 1),
             new BigDecimal("1234.1111111111111111111111"));
    }

    @Test
    public void convertToCharacter() throws Throwable {
        MethodHandle mh = make(Character.class);

        pass(mh,
             'a', 'a',
             null, null,
             "a", 'a'
             );

        fail(mh, true, 10, 1, 10.1, "hello");
    }

    @Test
    public void convertToString() throws Throwable {
        MethodHandle mh = make(String.class);

        pass(mh,
             true, "true",
             (byte) 10, "10",
             (short) 100, "100",
             1000, "1000",
             10_000_000_000L, "10000000000",
             12.34f, "12.34",
             12.3456789, "12.3456789",
             'a', "a",
             "hello", "hello",
             null, null,
             BigInteger.valueOf(1234), "1234",
             BigDecimal.valueOf(12.34), "12.34"
             );
    }

    @Test
    public void convertToBigInteger() throws Throwable {
        MethodHandle mh = make(BigInteger.class);

        pass(mh,
             (byte) 10, BigInteger.valueOf(10),
             (short) 100, BigInteger.valueOf(100),
             1000, BigInteger.valueOf(1000),
             10000L, BigInteger.valueOf(10000),
             10f, BigInteger.valueOf(10),
             100d, BigInteger.valueOf(100),
             "10000000000", BigInteger.valueOf(10_000_000_000L),
             BigInteger.valueOf(10_000_000_000L), BigInteger.valueOf(10_000_000_000L),
             null, null,
             BigDecimal.valueOf(10_000_000_000L), BigInteger.valueOf(10_000_000_000L)
             );

        fail(mh, true, 10.1f, 10.1d, "hello", 'a', BigDecimal.valueOf(10.1));
    }

    @Test
    public void convertToBigDecimal() throws Throwable {
        MethodHandle mh = make(BigDecimal.class);

        pass(mh,
             (byte) 10, BigDecimal.valueOf(10),
             (short) 100, BigDecimal.valueOf(100),
             1000, BigDecimal.valueOf(1000),
             10000L, BigDecimal.valueOf(10000),
             10f, BigDecimal.valueOf(10.0),
             100d, BigDecimal.valueOf(100.0),
             "10.1", new BigDecimal("10.1"),
             BigInteger.valueOf(1234), BigDecimal.valueOf(1234),
             new BigDecimal("1234.125"), new BigDecimal("1234.125"),
             null, null
             );

        fail(mh, true, "hello", 'a');
    }

    private void pass(MethodHandle mh, Object... cases) throws Throwable {
        for (int i=0; i<cases.length; i+=2) {
            Object result = mh.invoke(cases[i]);
            assertEquals(cases[i + 1], result);
        }
    }

    private void fail(MethodHandle mh, Object... cases) throws Throwable {
        for (int i=0; i<cases.length; i++) {
            try {
                mh.invoke(cases[i]);
                Assert.fail("" + cases[i]);
            } catch (IllegalArgumentException | ArithmeticException e) {
            }
        }
    }
}
