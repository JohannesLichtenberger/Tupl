/*
 *  Copyright 2021 Cojen.org
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

package org.cojen.tupl.rows;

import org.cojen.maker.Field;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import static org.cojen.tupl.rows.ColumnInfo.*;
import static org.cojen.tupl.rows.RowUtils.*;

/**
 * Makes code for encoding and decoding primitive type columns.
 *
 * @author Brian S O'Neill
 */
class PrimitiveColumnCodec extends ColumnCodec {
    private final boolean mForKey;
    private final int mSize;

    /**
     * @param info non-null
     * @param mm is null for stateless instance
     * @param forKey true to use key encoding (lexicographical order)
     * @param size byte count
     */
    PrimitiveColumnCodec(ColumnInfo info, MethodMaker mm, boolean forKey, int size) {
        super(info, mm);
        mForKey = forKey;
        mSize = size;
    }

    @Override
    ColumnCodec bind(MethodMaker mm) {
        return new PrimitiveColumnCodec(mInfo, mm, mForKey, mSize);
    }

    @Override
    int minSize() {
        // Return just the header size if nullable.
        return mInfo.isNullable() ? 1 : mSize;
    }

    @Override
    boolean isLast() {
        return false;
    }

    @Override
    void encodePrepare() {
    }

    @Override
    void encodeSkip() {
    }

    @Override
    Variable encodeSize(Variable srcVar, Variable totalVar) {
        if (mInfo.isNullable() && mInfo.plainTypeCode() != TYPE_BOOLEAN) {
            if (totalVar == null) {
                totalVar = mMaker.var(int.class).set(0);
            }
            Label isNull = mMaker.label();
            srcVar.ifEq(null, isNull);
            totalVar.inc(mSize);
            isNull.here();
        }

        return totalVar;
    }

    @Override
    void encode(Variable srcVar, Variable dstVar, Variable offsetVar) {
        Label end = null;

        int plain = mInfo.plainTypeCode();

        if (mInfo.isNullable() && plain != TYPE_BOOLEAN) {
            end = mMaker.label();
            encodeNullHeader(end, srcVar, dstVar, offsetVar);
        }

        doEncode: {
            String methodType;

            switch (plain) {
            case TYPE_BOOLEAN: case TYPE_BYTE: case TYPE_UBYTE: {
                if (plain == TYPE_BOOLEAN) {
                    byte f, t, n;
                    n = NULL_BYTE_HIGH;
                    if (!mForKey) {
                        f = 0;
                        t = 1;
                    } else {
                        f = (byte) 0x80;
                        t = (byte) 0x81;
                        if (mInfo.isDescending()) {
                            f = (byte) ~f;
                            t = (byte) ~t;
                            n = (byte) ~n;
                        }
                    }

                    var byteVar = mMaker.var(byte.class);
                    Label cont = mMaker.label();

                    if (mInfo.isNullable()) {
                        Label notNull = mMaker.label();
                        srcVar.ifNe(null, notNull);
                        byteVar.set(n);
                        mMaker.goto_(cont);
                        notNull.here();
                    }

                    Label trueCase = mMaker.label();
                    srcVar.ifTrue(trueCase);
                    byteVar.set(f);
                    mMaker.goto_(cont);
                    trueCase.here();
                    byteVar.set(t);
                    srcVar = byteVar;

                    cont.here();
                } else if (plain == TYPE_BYTE && mForKey) {
                    byte mask = (byte) (mInfo.isDescending() ? 0x7f : 0x80);
                    srcVar = srcVar.unbox().xor(mask);
                }

                dstVar.aset(offsetVar, srcVar);
                offsetVar.inc(1);

                break doEncode;
            }

            case TYPE_SHORT: case TYPE_USHORT: case TYPE_CHAR:
                methodType = "Short";
                break;

            case TYPE_FLOAT:
                srcVar = mMaker.var(Float.class).invoke("floatToRawIntBits", srcVar);
            case TYPE_INT: case TYPE_UINT:
                methodType = "Int";
                break;

            case TYPE_DOUBLE:
                srcVar = mMaker.var(Double.class).invoke("doubleToRawLongBits", srcVar);
            case TYPE_LONG: case TYPE_ULONG:
                methodType = "Long";
                break;

            default:
                throw new AssertionError();
            }

            String format;
            if (!mForKey) {
                format = "LE";
            } else {
                format = "BE";
                if (!mInfo.isUnsigned()) {
                    srcVar = srcVar.unbox().xor(signMask());
                }
            }

            String methodName = "encode" + methodType + format;
            mMaker.var(RowUtils.class).invoke(methodName, dstVar, offsetVar, srcVar);
            offsetVar.inc(mSize);
        }

        if (end != null) {
            end.here();
        }
    }

    @Override
    void decode(Variable dstVar, Variable srcVar, Variable offsetVar, Variable endVar) {
        decode(dstVar, srcVar, offsetVar, false);
    }

    /**
     * @param raw true to leave floating point values in their raw integer form
     */
    private void decode(Variable dstVar, Variable srcVar, Variable offsetVar, boolean raw) {
        decode(dstVar, srcVar, offsetVar, raw, mInfo.isNullable());
    }

    private void decode(Variable dstVar, Variable srcVar, Variable offsetVar,
                        boolean raw, boolean isNullable)
    {
        Label end = null;

        int plain = mInfo.plainTypeCode();

        if (isNullable && plain != TYPE_BOOLEAN) {
            end = mMaker.label();
            decodeNullHeader(end, dstVar, srcVar, offsetVar);
        }

        Variable valueVar;

        doDecode: {
            String methodType;

            switch (plain) {
            case TYPE_BOOLEAN: case TYPE_BYTE: case TYPE_UBYTE: {
                var byteVar = srcVar.aget(offsetVar);
                offsetVar.inc(1);

                if (plain == TYPE_BOOLEAN) {
                    Label cont = null;

                    if (!isNullable) {
                        valueVar = mMaker.var(boolean.class);
                    } else {
                        byte n = NULL_BYTE_HIGH;
                        if (mInfo.isDescending()) {
                            n = (byte) ~n;
                        }
                        valueVar = mMaker.var(Boolean.class);
                        Label notNull = mMaker.label();
                        byteVar.ifNe(n, notNull);
                        valueVar.set(null);
                        cont = mMaker.label();
                        mMaker.goto_(cont);
                        notNull.here();
                    }

                    if (mInfo.isDescending()) {
                        byteVar = byteVar.com();
                    }

                    valueVar.set(byteVar.cast(boolean.class));

                    if (cont != null) {
                        cont.here();
                    }
                } else {
                    if (plain == TYPE_BYTE && mForKey) {
                        byte mask = (byte) (mInfo.isDescending() ? 0x7f : 0x80);
                        byteVar = byteVar.xor(mask);
                    }
                    valueVar = byteVar;
                }

                break doDecode;
            }

            case TYPE_SHORT: case TYPE_USHORT: case TYPE_CHAR:
                methodType = "UnsignedShort";
                break;

            case TYPE_INT: case TYPE_FLOAT: case TYPE_UINT:
                methodType = "Int";
                break;

            case TYPE_LONG: case TYPE_DOUBLE: case TYPE_ULONG:
                methodType = "Long";
                break;

            default:
                throw new AssertionError();
            }

            String methodName = "decode" + methodType + (mForKey ? "BE" : "LE");
            valueVar = mMaker.var(RowUtils.class).invoke(methodName, srcVar, offsetVar);
            offsetVar.inc(mSize);

            if (mForKey && !mInfo.isUnsigned()) {
                valueVar = valueVar.xor(signMask());
            }

            switch (plain) {
            case TYPE_SHORT: case TYPE_USHORT:
                valueVar = valueVar.cast(short.class);
                break;
            case TYPE_CHAR:
                valueVar = valueVar.cast(char.class);
                break;
            case TYPE_FLOAT:
                if (!raw) {
                    valueVar = mMaker.var(Float.class).invoke("intBitsToFloat", valueVar);
                }
                break;
            case TYPE_DOUBLE:
                if (!raw) {
                    valueVar = mMaker.var(Double.class).invoke("longBitsToDouble", valueVar);
                }
                break;
            }
        }

        dstVar.set(valueVar);

        if (end != null) {
            end.here();
        }
    }

    @Override
    void decodeSkip(Variable srcVar, Variable offsetVar, Variable endVar) {
        Label end = null;

        if (mInfo.isNullable() && mInfo.plainTypeCode() != TYPE_BOOLEAN) {
            end = mMaker.label();
            decodeNullHeader(end, null, srcVar, offsetVar);
        }

        offsetVar.inc(mSize);

        if (end != null) {
            end.here();
        }
    }

    @Override
    boolean canFilterQuick(ColumnInfo dstInfo) {
        return dstInfo.typeCode == mInfo.typeCode;
    }

    @Override
    Object filterQuickDecode(ColumnInfo dstInfo,
                             Variable srcVar, Variable offsetVar, Variable endVar)
    {
        if (dstInfo.plainTypeCode() == TYPE_BOOLEAN) {
            var columnVar = mMaker.var(dstInfo.type);
            decode(columnVar, srcVar, offsetVar, false);
            return columnVar;
        }

        var columnVar = mMaker.var(dstInfo.unboxedType());

        if (!dstInfo.isNullable()) {
            decode(columnVar, srcVar, offsetVar, false, false);
            return columnVar;
        }

        columnVar.set(0);
        Variable isNullVar = mMaker.var(boolean.class);
        decodeNullHeader(null, isNullVar, srcVar, offsetVar);
        Label isNull = mMaker.label();
        isNullVar.ifTrue(isNull);
        decode(columnVar, srcVar, offsetVar, false, false);
        isNull.here();
        return new Variable[] {columnVar, isNullVar};
    }

    @Override
    void filterQuickCompare(ColumnInfo dstInfo, Variable srcVar, Variable offsetVar,
                            int op, Object decoded, Variable argObjVar, int argNum,
                            Label pass, Label fail)
    {
        Variable columnVar, isNullVar;
        if (decoded instanceof Variable) {
            columnVar = (Variable) decoded;
            isNullVar = null;
        } else {
            var pair = (Variable[]) decoded;
            columnVar = pair[0];
            isNullVar = pair[1];
        }

        var argField = argObjVar.field(argFieldName(argNum));

        if (isNullVar != null) {
            compareNullHeader(isNullVar, null, argField, op, pass, fail);
        } else if (mInfo.isNullable()) {
            CompareUtils.compare(mMaker, dstInfo, columnVar, dstInfo, argField, op, pass, fail);
            return;
        }

        CompareUtils.comparePrimitives(mMaker, dstInfo, columnVar,
                                       dstInfo, argField, op, pass, fail);
    }

    /**
     * Must only be called for size 2, 4, or 8.
     */
    private Object signMask() {
        if (mSize == 8) {
            long lmask = 1L << 63;
            if (mInfo.isDescending()) {
                lmask = ~lmask;
            }
            return lmask;
        } else if (mSize == 4) {
            int imask = 1 << 31;
            if (mInfo.isDescending()) {
                imask = ~imask;
            }
            return imask;
        } else {
            int imask = 1 << 15;
            if (mInfo.isDescending()) {
                imask = ~imask;
            }
            return mInfo.plainTypeCode() == TYPE_CHAR ? (char) imask : (short) imask;
        }
    }
}
