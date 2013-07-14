/*
 *  Copyright 2013 Brian S O'Neill
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

import java.util.Arrays;

import java.util.concurrent.locks.Lock;

import static java.lang.System.arraycopy;

import static java.util.Arrays.fill;

import static org.cojen.tupl.Utils.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class TreeValueStream extends Stream {
    // Op ordinals are relevant.
    private static final int OP_LENGTH = 0, OP_READ = 1, OP_SET_LENGTH = 2, OP_WRITE = 3;

    private final TreeCursor mCursor;
    private final Database mDb;

    /**
     * @param cursor positioned cursor, not autoloading
     */
    TreeValueStream(TreeCursor cursor) {
        mCursor = cursor;
        mDb = cursor.mTree.mDatabase;
    }

    @Override
    public LockResult open(Transaction txn, byte[] key) throws IOException {
        TreeCursor cursor = mCursor;
        if (cursor.key() != null) {
            close();
        }
        cursor.link(txn);
        try {
            return cursor.find(key);
        } catch (Throwable e) {
            mCursor.reset();
            throw rethrow(e);
        }
    }

    @Override
    public long length() throws IOException {
        return action(OP_LENGTH, 0, null, 0, 0);
    }

    @Override
    public void setLength(long length) throws IOException {
        // FIXME: txn undo/redo
        final Lock sharedCommitLock = mDb.sharedCommitLock();
        sharedCommitLock.lock();
        try {
            action(OP_SET_LENGTH, length, Utils.EMPTY_BYTES, 0, 0);
        } finally {
            sharedCommitLock.unlock();
        }
    }

    @Override
    int doRead(long pos, byte[] buf, int off, int len) throws IOException {
        return (int) action(OP_READ, pos, buf, off, len);
    }

    @Override
    void doWrite(long pos, byte[] buf, int off, int len) throws IOException {
        // FIXME: txn undo/redo
        final Lock sharedCommitLock = mDb.sharedCommitLock();
        sharedCommitLock.lock();
        try {
            action(OP_WRITE, pos, buf, off, len);
        } finally {
            sharedCommitLock.unlock();
        }
    }

    @Override
    int pageSize() {
        return mDb.mPageSize;
    }

    @Override
    void checkOpen() {
        if (mCursor.key() == null) {
            throw new IllegalStateException("Stream closed");
        }
    }

    @Override
    void doClose() {
        mCursor.reset();
    }

    /**
     * Caller must hold shared commit lock when using OP_SET_LENGTH or OP_WRITE.
     *
     * @param b ignored by OP_LENGTH, OP_SET_LENGTH must pass EMPTY_BYTES
     */
    private long action(int op, long pos, byte[] b, int bOff, int bLen) throws IOException {
        TreeCursorFrame frame;
        if (op <= OP_READ) {
            frame = mCursor.leafSharedNotSplit();
        } else {
            frame = mCursor.leafExclusiveNotSplitDirty();
        }

        Node node = frame.mNode;

        int nodePos = frame.mNodePos;
        if (nodePos < 0) {
            // Value doesn't exist.

            if (op <= OP_READ) {
                // Handle OP_LENGTH and OP_READ.
                node.releaseShared();
                return -1;
            }

            // Handle OP_SET_LENGTH and OP_WRITE.

            // Method releases latch if an exception is thrown.
            node = mCursor.insertBlank(frame, node, pos + bLen);

            if (bLen <= 0) {
                node.releaseExclusive();
                return bLen;
            }

            // Fallthrough and complete the write operation.
            nodePos = frame.mNodePos;
        }

        final byte[] page = node.mPage;
        int loc = decodeUnsignedShortLE(page, node.mSearchVecStart + nodePos);
        int header = page[loc++];
        loc += (header >= 0 ? header : (((header & 0x3f) << 8) | (page[loc] & 0xff))) + 1;

        long vLen;

        header = page[loc++];
        if (header >= 0) {
            vLen = header;
        } else {
            int len;
            if ((header & 0x20) == 0) {
                len = 1 + (((header & 0x1f) << 8) | (page[loc++] & 0xff));
            } else if (header != -1) {
                len = 1 + (((header & 0x0f) << 16)
                           | ((page[loc++] & 0xff) << 8) | (page[loc++] & 0xff));
            } else {
                // ghost
                if (op <= OP_READ) {
                    // Handle OP_LENGTH and OP_READ.
                    node.releaseShared();
                    return -1;
                }
                // FIXME: write ops; create the value
                node.releaseExclusive();
                throw null;
            }

            if ((header & Node.VALUE_FRAGMENTED) == 0) {
                vLen = len;
            } else {
                // Operate against a fragmented value. First read the fragment header, as
                // described by the Database.fragment method.
                header = page[loc++];

                switch ((header >> 2) & 0x03) {
                default:
                    vLen = decodeUnsignedShortLE(page, loc);
                    break;
                case 1:
                    vLen = decodeIntLE(page, loc) & 0xffffffffL;
                    break;
                case 2:
                    vLen = decodeUnsignedInt48LE(page, loc);
                    break;
                case 3:
                    vLen = decodeLongLE(page, loc);
                    if (vLen < 0) {
                        if (op <= OP_READ) {
                            node.releaseShared();
                        } else {
                            node.releaseExclusive();
                        }
                        throw new LargeValueException(vLen);
                    }
                    break;
                }

                // Advance past the value length field.
                loc += 2 + ((header >> 1) & 0x06);

                switch (op) {
                case OP_LENGTH: default:
                    node.releaseShared();
                    return vLen;

                case OP_READ: try {
                    if (bLen <= 0 || pos >= vLen) {
                        return 0;
                    }

                    bLen = Math.min((int) (vLen - pos), bLen);
                    final int total = bLen;

                    if ((header & 0x02) != 0) {
                        // Inline content.
                        int inLen = decodeUnsignedShortLE(page, loc);
                        loc += 2;
                        int amt = (int) (inLen - pos);
                        if (amt <= 0) {
                            // Not reading any inline content.
                            pos -= inLen;
                        } else if (bLen <= amt) {
                            arraycopy(page, (int) (loc + pos), b, bOff, bLen);
                            return bLen;
                        } else {
                            arraycopy(page, (int) (loc + pos), b, bOff, amt);
                            bLen -= amt;
                            bOff += amt;
                            pos = 0;
                        }
                        loc += inLen;
                    }

                    final FragmentCache fc = mDb.mFragmentCache;

                    if ((header & 0x01) == 0) {
                        // Direct pointers.
                        int ipos = (int) pos;
                        loc += (ipos / page.length) * 6;
                        int fNodeOff = ipos % page.length;
                        while (true) {
                            int amt = Math.min(bLen, page.length - fNodeOff);
                            long fNodeId = decodeUnsignedInt48LE(page, loc);
                            if (fNodeId == 0) {
                                // Reading a sparse value.
                                fill(b, bOff, bOff + amt, (byte) 0);
                            } else {
                                Node fNode = fc.get(node, fNodeId);
                                arraycopy(fNode.mPage, fNodeOff, b, bOff, amt);
                                fNode.releaseShared();
                            }
                            bLen -= amt;
                            if (bLen <= 0) {
                                return total;
                            }
                            bOff += amt;
                            loc += 6;
                            fNodeOff = 0;
                        }
                    }

                    // Indirect pointers.

                    long inodeId = decodeUnsignedInt48LE(page, loc);
                    if (inodeId == 0) {
                        // Reading a sparse value.
                        fill(b, bOff, bOff + bLen, (byte) 0);
                    } else {
                        Node inode = fc.get(node, inodeId);
                        int levels = Database.calculateInodeLevels(vLen, page.length);
                        readMultilevelFragments(node, pos, levels, inode, b, bOff, bLen);
                    }

                    return total;
                } finally {
                    node.releaseShared();
                }

                case OP_SET_LENGTH:
                    // FIXME: If same return, if shorter truncate, else fall through.
                    node.releaseExclusive();
                    throw null;

                case OP_WRITE: try {
                    if (bLen <= 0) {
                        return 0;
                    }

                    // FIXME: extend check
                    // FIXME: extend length, accounting for length field growth

                    //bLen = Math.min((int) (vLen - pos), bLen);
                    final int total = bLen;

                    if ((header & 0x02) != 0) {
                        // Inline content.
                        int inLen = decodeUnsignedShortLE(page, loc);
                        loc += 2;
                        int amt = (int) (inLen - pos);
                        if (amt <= 0) {
                            // Not writing any inline content.
                            pos -= inLen;
                        } else if (bLen <= amt) {
                            arraycopy(b, bOff, page, (int) (loc + pos), bLen);
                            return bLen;
                        } else {
                            arraycopy(b, bOff, page, (int) (loc + pos), amt);
                            bLen -= amt;
                            bOff += amt;
                            pos = 0;
                        }
                        loc += inLen;
                    }

                    final FragmentCache fc = mDb.mFragmentCache;

                    if ((header & 0x01) == 0) {
                        // Direct pointers.
                        int ipos = (int) pos;
                        loc += (ipos / page.length) * 6;
                        int fNodeOff = ipos % page.length;
                        while (true) {
                            int amt = Math.min(bLen, page.length - fNodeOff);
                            long fNodeId = decodeUnsignedInt48LE(page, loc);
                            if (fNodeId == 0) {
                                // Writing into a sparse value. Allocate a node and point to it.
                                Node fNode = mDb.allocDirtyNode();
                                try {
                                    fc.put(node, fNode);
                                    encodeInt48LE(page, loc, fNode.mId);

                                    // Now write to the new page, zero-filling the gaps.
                                    byte[] fNodePage = fNode.mPage;
                                    fill(fNodePage, 0, fNodeOff, (byte) 0);
                                    arraycopy(b, bOff, fNodePage, fNodeOff, amt);
                                    fill(fNodePage, fNodeOff + amt, fNodePage.length, (byte) 0);
                                } finally {
                                    fNode.releaseExclusive();
                                }
                            } else {
                                // Obtain node from cache, or load it only for partial write.
                                Node fNode = fc.getw(node, fNodeId, amt < page.length);
                                try {
                                    if (mDb.markFragmentDirty(fNode)) {
                                        encodeInt48LE(page, loc, fNode.mId);
                                    }
                                    arraycopy(b, bOff, fNode.mPage, fNodeOff, amt);
                                } finally {
                                    fNode.releaseExclusive();
                                }
                            }
                            bLen -= amt;
                            if (bLen <= 0) {
                                return total;
                            }
                            bOff += amt;
                            loc += 6;
                            fNodeOff = 0;
                        }
                    }

                    // Indirect pointers.

                    Node inode;
                    setPtr: {
                        long inodeId = decodeUnsignedInt48LE(page, loc);

                        if (inodeId == 0) {
                            // Writing into a sparse value. Allocate a node and point to it.
                            inode = mDb.allocDirtyNode();
                            byte[] ipage = inode.mPage;
                            fill(ipage, (byte) 0);
                            try {
                                fc.put(node, inode);
                            } catch (IOException e) {
                                inode.releaseExclusive();
                                throw e;
                            }
                        } else {
                            inode = fc.getw(node, inodeId, true);
                            if (!mDb.markFragmentDirty(inode)) {
                                // Already dirty, so no need to update the pointer.
                                break setPtr;
                            }
                        }

                        encodeInt48LE(page, loc, inode.mId);
                    }

                    int levels = Database.calculateInodeLevels(vLen, page.length);
                    writeMultilevelFragments(node, pos, levels, inode, b, bOff, bLen);

                    return total;
                } finally {
                    node.releaseExclusive();
                }
                } // end switch(op)
            }
        }

        // Operate against a non-fragmented value.

        switch (op) {
        case OP_LENGTH: default:
            node.releaseShared();
            return vLen;

        case OP_READ:
            if (bLen <= 0 || pos >= vLen) {
                bLen = 0;
            } else {
                bLen = Math.min((int) (vLen - pos), bLen);
                arraycopy(page, (int) (loc + pos), b, bOff, bLen);
            }
            node.releaseShared();
            return bLen;

        case OP_SET_LENGTH:
            // FIXME
            node.releaseExclusive();
            throw null;

        case OP_WRITE:
            // FIXME
            node.releaseExclusive();
            throw null;
        }
    }

    /**
     * @param pos value position being read
     * @param level inode level; at least 1
     * @param inode shared latched parent inode; always released by this method
     * @param value slice of complete value being reconstructed
     */
    private void readMultilevelFragments(Node caller,
                                         long pos, int level, Node inode,
                                         byte[] b, int bOff, int bLen)
        throws IOException
    {
        byte[] page = inode.mPage;
        level--;
        long levelCap = mDb.levelCap(level);

        int firstChild = (int) (pos / levelCap);
        int lastChild = (int) ((pos + bLen - 1) / levelCap);

        // Copy all child node ids and release parent latch early.
        // FragmentCache can then safely evict the parent node if necessary.
        long[] childNodeIds = new long[lastChild - firstChild + 1];
        for (int poffset = firstChild * 6, i=0; i<childNodeIds.length; poffset += 6, i++) {
            childNodeIds[i] = decodeUnsignedInt48LE(page, poffset);
        }
        inode.releaseShared();

        final FragmentCache fc = mDb.mFragmentCache;

        // Handle a possible partial read from the first page.
        long ppos = pos % levelCap;

        for (long childNodeId : childNodeIds) {
            int len = (int) Math.min(levelCap - ppos, bLen);
            if (childNodeId == 0) {
                // Reading a sparse value.
                fill(b, bOff, bOff + len, (byte) 0);
            } else {
                Node childNode = fc.get(caller, childNodeId);
                if (level <= 0) {
                    arraycopy(childNode.mPage, (int) ppos, b, bOff, len);
                    childNode.releaseShared();
                } else {
                    readMultilevelFragments(caller, ppos, level, childNode, b, bOff, len);
                }
            }
            bLen -= len;
            if (bLen <= 0) {
                break;
            }
            bOff += len;
            // Remaining reads begin at the start of the page.
            ppos = 0;
        }
    }

    /**
     * @param pos value position being read
     * @param level inode level; at least 1
     * @param inode exclusively latched parent inode; always released by this method
     * @param value slice of complete value being written
     */
    private void writeMultilevelFragments(Node caller,
                                          long pos, int level, Node inode,
                                          byte[] b, int bOff, int bLen)
        throws IOException
    {
        final FragmentCache fc = mDb.mFragmentCache;

        long levelCap;
        long[] childNodeIds;
        Node[] childNodes;
        try {
            byte[] page = inode.mPage;
            level--;
            levelCap = mDb.levelCap(level);

            int firstChild = (int) (pos / levelCap);
            int lastChild = (int) ((pos + bLen - 1) / levelCap);

            // Pre-allocate and reference the required child nodes in order for parent node
            // latch to be released early. FragmentCache can then safely evict the parent node
            // if necessary.

            int childNodeCount = lastChild - firstChild + 1;
            childNodeIds = new long[childNodeCount << 1];
            childNodes = new Node[childNodeCount];
            try {
                int poffset = firstChild * 6;
                for (int i=0; i<childNodeCount; poffset += 6, i++) {
                    long childNodeId = decodeUnsignedInt48LE(page, poffset);

                    Node childNode;
                    setPtr: {
                        prepChild: {
                            if (childNodeId != 0) {
                                // Node exists, but it must be dirtied.
                                if ((childNode = fc.findw(caller, inode, childNodeId)) == null) {
                                    // Don't bother loading it now, but old page must be
                                    // deleted. Old contents can still be read, because no
                                    // checkpoint is in progress.
                                    mDb.forceDeletePage(childNodeId);
                                } else {
                                    // Store negative child id, indicating that it's contents
                                    // are valid.
                                    childNodeIds[childNodeCount + i] = -childNodeId;
                                    try {
                                        if (mDb.markFragmentDirty(childNode)) {
                                            // Dirtied now, so update pointer.
                                            break prepChild;
                                        } else {
                                            // Already dirty.
                                            break setPtr;
                                        }
                                    } catch (Throwable e) {
                                        childNode.releaseExclusive();
                                        throw rethrow(e);
                                    }
                                }
                            }

                            // Remember old child id as-is, indicating that it's not loaded or
                            // doesn't exist. It might need to be zero-filled or loaded for a
                            // partial write.
                            childNodeIds[childNodeCount + i] = childNodeId;

                            childNode = mDb.allocDirtyNode();
                        }

                        childNodeId = childNode.mId;
                        encodeInt48LE(page, poffset, childNodeId);
                    }

                    childNodeIds[i] = childNodeId;
                    childNodes[i] = childNode;

                    // Allow node to be evicted, but don't write anything yet.
                    childNode.mCachedState = Node.CACHED_CLEAN;
                    childNode.releaseExclusive();
                }
            } catch (Throwable e) {
                // Panic.
                mDb.close(e);
                throw rethrow(e);
            }

            fc.put(caller, inode);
        } finally {
            inode.releaseExclusive();
        }

        // Handle a possible partial write to the first page.
        long ppos = pos % levelCap;

        for (int i=0; i<childNodeIds.length; i++) {
            long oldChildNodeId = childNodeIds[childNodes.length + i];
            long childNodeId = childNodeIds[i];
            Node childNode = childNodes[i];

            int len = (int) Math.min(levelCap - ppos, bLen);

            latchChild: {
                if (childNodeId == childNode.mId) {
                    childNode.acquireExclusive();
                    if (childNodeId == childNode.mId) {
                        // Since commit lock is held, only need to switch the state. Calling
                        // redirty is unnecessary and it would screw up the dirty list order
                        // for no good reason. Use the quick variant.
                        mDb.redirtyQ(childNode);
                        break latchChild;
                    }
                    childNode.releaseExclusive();
                }
                // Child node was evicted, although it can be overwritten.
                if (oldChildNodeId < 0) {
                    // Indicate that partial write must load old page contents.
                    oldChildNodeId = -oldChildNodeId;
                }
                childNode = mDb.allocLatchedNode();
                childNode.mId = childNodeId;
                mDb.redirty(childNode);
            }

            if (level <= 0) {
                byte[] childPage = childNode.mPage;
                int off = (int) ppos;
                if (oldChildNodeId >= 0 && (off > 0 || len < childPage.length)) {
                    // Partial write and existing fragment is unknown.
                    if (oldChildNodeId == 0) {
                        // New fragment must be zero-filled first.
                        fill(childPage, (byte) 0);
                    } else {
                        // Load existing fragment first.
                        mDb.readNodePage(oldChildNodeId, childPage);
                    }
                }
                arraycopy(b, bOff, childPage, off, len);
                fc.put(caller, childNode);
                childNode.releaseExclusive();
            } else {
                if (oldChildNodeId >= 0) {
                    byte[] childPage = childNode.mPage;
                    if (oldChildNodeId == 0) {
                        // New inode, which must be zero-filled with child ids.
                        fill(childPage, (byte) 0);
                    } else {
                        // Load inode to obtain proper child ids.
                        mDb.readNodePage(oldChildNodeId, childPage);
                    }
                }
                writeMultilevelFragments(caller, ppos, level, childNode, b, bOff, len);
            }

            bLen -= len;
            if (bLen <= 0) {
                break;
            }
            bOff += len;
            // Remaining writes begin at the start of the page.
            ppos = 0;
        }
    }
}
