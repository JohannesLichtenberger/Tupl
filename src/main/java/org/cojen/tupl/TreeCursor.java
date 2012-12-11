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

import java.io.IOException;

import java.util.Arrays;
import java.util.Random;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.locks.Lock;

/**
 * Internal cursor implementation, which can be used by one thread at a time.
 *
 * @author Brian S O'Neill
 */
final class TreeCursor extends CauseCloseable implements Cursor {
    // Sign is important because values are passed to Node.retrieveKeyCmp
    // method. Bit 0 is set for inclusive variants and clear for exclusive.
    private static final int LIMIT_LE = 1, LIMIT_LT = 2, LIMIT_GE = -1, LIMIT_GT = -2;

    final Tree mTree;
    private Transaction mTxn;

    // Top stack frame for cursor, always a leaf.
    private TreeCursorFrame mLeaf;

    byte[] mKey;
    byte[] mValue;

    boolean mKeyOnly;

    // Hashcode is defined by LockManager.
    private int mKeyHash;

    TreeCursor(Tree tree, Transaction txn) {
        tree.check(txn);
        mTree = tree;
        mTxn = txn;
    }

    @Override
    public Transaction link(Transaction txn) {
        mTree.check(txn);
        Transaction old = mTxn;
        mTxn = txn;
        return old;
    }

    @Override
    public byte[] key() {
        return mKey;
    }

    @Override
    public byte[] value() {
        return mValue;
    }

    @Override
    public boolean autoload(boolean mode) {
        boolean old = mKeyOnly;
        mKeyOnly = !mode;
        return !old;
    }

    @Override
    public int compareKeyTo(byte[] rkey) {
        byte[] lkey = mKey;
        return Utils.compareKeys(lkey, 0, lkey.length, rkey, 0, rkey.length);
    }

    @Override
    public int compareKeyTo(byte[] rkey, int offset, int length) {
        byte[] lkey = mKey;
        return Utils.compareKeys(lkey, 0, lkey.length, rkey, offset, length);
    }

    private int keyHash() {
        int hash = mKeyHash;
        if (hash == 0) {
            mKeyHash = hash = LockManager.hash(mTree.mId, mKey);
        }
        return hash;
    }

    @Override
    public LockResult first() throws IOException {
        Node root = mTree.mRoot;
        TreeCursorFrame frame = reset(root);

        if (!toFirst(root, frame)) {
            return LockResult.UNOWNED;
        }

        Transaction txn = mTxn;
        LockResult result = tryCopyCurrent(txn);

        if (result != null) {
            // Extra check for filtering ghosts.
            if (mKey == null || mValue != null) {
                return result;
            }
        } else if ((result = lockAndCopyIfExists(txn)) != null) {
            return result;
        }

        // If this point is reached, then entry was deleted after latch was
        // released. Move to next entry, which is consistent with findGe.
        // First means, "find greater than or equal to lowest possible key".
        return next();
    }

    //@Override
    public LockResult first(long maxWait, TimeUnit unit) throws IOException {
        Node root = mTree.mRoot;
        TreeCursorFrame frame = reset(root);

        if (!toFirst(root, frame)) {
            return LockResult.UNOWNED;
        }

        Transaction txn = mTxn;
        LockResult result = tryCopyCurrent(txn);

        if (result != null) {
            // Extra check for filtering ghosts.
            if (mKey == null || mValue != null) {
                return result;
            }
        } else if ((result = lockAndCopyIfExists(txn, maxWait, unit)) != null) {
            return result;
        }

        // If this point is reached, then entry was deleted after latch was
        // released. Move to next entry, which is consistent with findGe.
        // First means, "find greater than or equal to lowest possible key".
        return next(maxWait, unit);
    }

    /**
     * Moves the cursor to the first subtree entry. Leaf frame remains latched
     * when method returns normally.
     *
     * @param node latched node; can have no keys
     * @param frame frame to bind node to
     * @return false if nothing left
     */
    private boolean toFirst(Node node, TreeCursorFrame frame) throws IOException {
        try {
            while (true) {
                frame.bind(node, 0);
                if (node.isLeaf()) {
                    mLeaf = frame;
                    return node.hasKeys() ? true : toNext(frame);
                }
                if (node.mSplit != null) {
                    node = node.mSplit.latchLeft(node);
                }
                node = latchChild(node, 0, true);
                frame = new TreeCursorFrame(frame);
            }
        } catch (Throwable e) {
            throw cleanup(e, frame);
        }
    }

    @Override
    public LockResult last() throws IOException {
        Node root = mTree.mRoot;
        TreeCursorFrame frame = reset(root);

        if (!toLast(root, frame)) {
            return LockResult.UNOWNED;
        }

        Transaction txn = mTxn;
        LockResult result = tryCopyCurrent(txn);

        if (result != null) {
            // Extra check for filtering ghosts.
            if (mKey == null || mValue != null) {
                return result;
            }
        } else if ((result = lockAndCopyIfExists(txn)) != null) {
            return result;
        }

        // If this point is reached, then entry was deleted after latch was
        // released. Move to previous entry, which is consistent with findLe.
        // Last means, "find less than or equal to highest possible key".
        return previous();
    }

    //@Override
    public LockResult last(long maxWait, TimeUnit unit) throws IOException {
        Node root = mTree.mRoot;
        TreeCursorFrame frame = reset(root);

        if (!toLast(root, frame)) {
            return LockResult.UNOWNED;
        }

        Transaction txn = mTxn;
        LockResult result = tryCopyCurrent(txn);

        if (result != null) {
            // Extra check for filtering ghosts.
            if (mKey == null || mValue != null) {
                return result;
            }
        } else if ((result = lockAndCopyIfExists(txn, maxWait, unit)) != null) {
            return result;
        }

        // If this point is reached, then entry was deleted after latch was
        // released. Move to previous entry, which is consistent with findLe.
        // Last means, "find less than or equal to highest possible key".
        return previous(maxWait, unit);
    }

    /**
     * Moves the cursor to the last subtree entry. Leaf frame remains latched
     * when method returns normally.
     *
     * @param node latched node; can have no keys
     * @param frame frame to bind node to
     * @return false if nothing left
     */
    private boolean toLast(Node node, TreeCursorFrame frame) throws IOException {
        try {
            while (true) {
                if (node.isLeaf()) {
                    // Note: Highest pos is -2 if leaf node has no keys.
                    int pos;
                    if (node.mSplit == null) {
                        pos = node.highestLeafPos();
                    } else {
                        pos = node.mSplit.highestLeafPos(node);
                    }
                    mLeaf = frame;
                    if (pos < 0) {
                        frame.bind(node, 0);
                        return toPrevious(frame);
                    } else {
                        frame.bind(node, pos);
                        return true;
                    }
                }

                Split split = node.mSplit;
                if (split == null) {
                    // Note: Highest pos is 0 if internal node has no keys.
                    int childPos = node.highestInternalPos();
                    frame.bind(node, childPos);
                    node = latchChild(node, childPos, true);
                } else {
                    // Follow highest position of split, binding this frame to the
                    // unsplit node as if it had not split. The binding will be
                    // corrected when split is finished.

                    final Node sibling = split.latchSibling();

                    final Node left, right;
                    if (split.mSplitRight) {
                        left = node;
                        right = sibling;
                    } else {
                        left = sibling;
                        right = node;
                    }

                    int highestRightPos = right.highestInternalPos();
                    frame.bind(node, left.highestInternalPos() + 2 + highestRightPos);
                    left.releaseExclusive();

                    node = latchChild(right, highestRightPos, true);
                }

                frame = new TreeCursorFrame(frame);
            }
        } catch (Throwable e) {
            throw cleanup(e, frame);
        }
    }

    @Override
    public LockResult skip(long amount) throws IOException {
        if (amount == 0) {
            Transaction txn = mTxn;
            if (txn != null && txn != Transaction.BOGUS) {
                byte[] key = mKey;
                if (key != null) {
                    return txn.mManager.check(txn, mTree.mId, key, keyHash());
                }
            }
            return LockResult.UNOWNED;
        }

        try {
            TreeCursorFrame frame = leafExclusiveNotSplit();
            if (amount > 0) {
                if (amount > 1 && (frame = skipNextGap(frame, amount - 1)) == null) {
                    return LockResult.UNOWNED;
                }
                return next(mTxn, frame);
            } else {
                if (amount < -1 && (frame = skipPreviousGap(frame, -1 - amount)) == null) {
                    return LockResult.UNOWNED;
                }
                return previous(mTxn, frame);
            }
        } catch (Throwable e) {
            throw handleException(e);
        }
    }

    @Override
    public LockResult next() throws IOException {
        return next(mTxn, leafExclusiveNotSplit());
    }

    //@Override
    public LockResult next(long maxWait, TimeUnit unit) throws IOException {
        return next(mTxn, leafExclusiveNotSplit(), maxWait, unit);
    }

    @Override
    public LockResult nextLe(byte[] limitKey) throws IOException {
        return nextCmp(limitKey, LIMIT_LE);
    }

    //@Override
    public LockResult nextLe(byte[] limitKey, long maxWait, TimeUnit unit) throws IOException {
        return nextCmp(limitKey, LIMIT_LE, maxWait, unit);
    }

    @Override
    public LockResult nextLt(byte[] limitKey) throws IOException {
        return nextCmp(limitKey, LIMIT_LT);
    }

    //@Override
    public LockResult nextLt(byte[] limitKey, long maxWait, TimeUnit unit) throws IOException {
        return nextCmp(limitKey, LIMIT_LT, maxWait, unit);
    }

    private LockResult nextCmp(byte[] limitKey, int limitMode) throws IOException {
        Transaction txn = mTxn;
        TreeCursorFrame frame = leafExclusiveNotSplit();

        while (true) {
            if (!toNext(frame)) {
                return LockResult.UNOWNED;
            }
            LockResult result = tryCopyCurrentCmp(txn, limitKey, limitMode);
            if (result != null) {
                // Extra check for filtering ghosts.
                if (mKey == null || mValue != null) {
                    return result;
                }
            } else if ((result = lockAndCopyIfExists(txn)) != null) {
                return result;
            }
            frame = leafExclusiveNotSplit();
        }
    }

    private LockResult nextCmp(byte[] limitKey, int limitMode, long maxWait, TimeUnit unit)
        throws IOException
    {
        Transaction txn = mTxn;
        TreeCursorFrame frame = leafExclusiveNotSplit();

        while (true) {
            if (!toNext(frame)) {
                return LockResult.UNOWNED;
            }
            LockResult result = tryCopyCurrentCmp(txn, limitKey, limitMode);
            if (result != null) {
                // Extra check for filtering ghosts.
                if (mKey == null || mValue != null) {
                    return result;
                }
            } else if ((result = lockAndCopyIfExists(txn, maxWait, unit)) != null) {
                return result;
            }
            frame = leafExclusiveNotSplit();
        }
    }

    /**
     * Note: When method returns, frame is unlatched and may no longer be valid.
     *
     * @param frame leaf frame, not split, with exclusive latch
     */
    private LockResult next(Transaction txn, TreeCursorFrame frame) throws IOException {
        while (true) {
            if (!toNext(frame)) {
                return LockResult.UNOWNED;
            }
            LockResult result = tryCopyCurrent(txn);
            if (result != null) {
                // Extra check for filtering ghosts.
                if (mKey == null || mValue != null) {
                    return result;
                }
            } else if ((result = lockAndCopyIfExists(txn)) != null) {
                return result;
            }
            frame = leafExclusiveNotSplit();
        }
    }

    /**
     * Note: When method returns, frame is unlatched and may no longer be valid.
     *
     * @param frame leaf frame, not split, with exclusive latch
     */
    private LockResult next(Transaction txn, TreeCursorFrame frame,
                            long maxWait, TimeUnit unit)
        throws IOException
    {
        while (true) {
            if (!toNext(frame)) {
                return LockResult.UNOWNED;
            }
            LockResult result = tryCopyCurrent(txn);
            if (result != null) {
                // Extra check for filtering ghosts.
                if (mKey == null || mValue != null) {
                    return result;
                }
            } else if ((result = lockAndCopyIfExists(txn, maxWait, unit)) != null) {
                return result;
            }
            frame = leafExclusiveNotSplit();
        }
    }

    /**
     * Note: When method returns, frame is unlatched and may no longer be
     * valid. Leaf frame remains latched when method returns true.
     *
     * @param frame leaf frame, not split, with exclusive latch
     * @return false if nothing left
     */
    private boolean toNext(TreeCursorFrame frame) throws IOException {
        Node node = frame.mNode;

        quick: {
            int pos = frame.mNodePos;
            if (pos < 0) {
                pos = ~2 - pos; // eq: (~pos) - 2;
                if (pos >= node.highestLeafPos()) {
                    break quick;
                }
                frame.mNotFoundKey = null;
            } else if (pos >= node.highestLeafPos()) {
                break quick;
            }
            frame.mNodePos = pos + 2;
            return true;
        }

        while (true) {
            TreeCursorFrame parentFrame = frame.peek();

            if (parentFrame == null) {
                frame.popv();
                node.releaseExclusive();
                mLeaf = null;
                mKey = null;
                mKeyHash = 0;
                mValue = null;
                return false;
            }

            Node parentNode;
            int parentPos;

            latchParent: {
                splitCheck: {
                    // Latch coupling up the tree usually works, so give it a
                    // try. If it works, then there's no need to worry about a
                    // node merge.
                    parentNode = parentFrame.tryAcquireExclusive();

                    if (parentNode == null) {
                        // Latch coupling failed, and so acquire parent latch
                        // without holding child latch. The child might have
                        // changed, and so it must be checked again.
                        node.releaseExclusive();
                        parentNode = parentFrame.acquireExclusive();
                        if (parentNode.mSplit == null) {
                            break splitCheck;
                        }
                    } else {
                        if (parentNode.mSplit == null) {
                            frame.popv();
                            node.releaseExclusive();
                            parentPos = parentFrame.mNodePos;
                            break latchParent;
                        }
                        node.releaseExclusive();
                    }

                    // When this point is reached, parent node must be split.
                    // Parent latch is held, child latch is not held, but the
                    // frame is still valid.

                    parentNode = finishSplit(parentFrame, parentNode);
                }

                // When this point is reached, child must be relatched. Parent
                // latch is held, and the child frame is still valid.

                parentPos = parentFrame.mNodePos;
                node = latchChild(parentNode, parentPos, false);

                // Quick check again, in case node got bigger due to merging.
                // Unlike the earlier quick check, this one must handle
                // internal nodes too.
                quick: {
                    int pos = frame.mNodePos;

                    if (pos < 0) {
                        pos = ~2 - pos; // eq: (~pos) - 2;
                        if (pos >= node.highestLeafPos()) {
                            break quick;
                        }
                        frame.mNotFoundKey = null;
                    } else if (pos >= node.highestPos()) {
                        break quick;
                    }

                    parentNode.releaseExclusive();
                    frame.mNodePos = (pos += 2);

                    if (frame != mLeaf) {
                        return toFirst(latchChild(node, pos, true), new TreeCursorFrame(frame));
                    }

                    return true;
                }

                frame.popv();
                node.releaseExclusive();
            }

            // When this point is reached, only the parent latch is held. Child
            // frame is no longer valid.

            if (parentPos < parentNode.highestInternalPos()) {
                parentFrame.mNodePos = (parentPos += 2);
                return toFirst(latchChild(parentNode, parentPos, true),
                               new TreeCursorFrame(parentFrame));
            }

            frame = parentFrame;
            node = parentNode;
        }
    }

    /**
     * @param frame leaf frame, not split, with exclusive latch
     * @return latched leaf frame or null if reached end
     */
    private TreeCursorFrame skipNextGap(TreeCursorFrame frame, long amount) throws IOException {
        outer: while (true) {
            Node node = frame.mNode;

            quick: {
                int pos = frame.mNodePos;

                int highest;
                if (pos < 0) {
                    pos = ~2 - pos; // eq: (~pos) - 2;
                    if (pos >= (highest = node.highestLeafPos())) {
                        break quick;
                    }
                    frame.mNotFoundKey = null;
                } else if (pos >= (highest = node.highestLeafPos())) {
                    break quick;
                }

                int avail = (highest - pos) >> 1;
                if (avail >= amount) {
                    frame.mNodePos = pos + (((int) amount) << 1);
                    return frame;
                } else {
                    frame.mNodePos = highest;
                    amount -= avail;
                }
            }

            while (true) {
                TreeCursorFrame parentFrame = frame.peek();

                if (parentFrame == null) {
                    frame.popv();
                    node.releaseExclusive();
                    mLeaf = null;
                    mKey = null;
                    mKeyHash = 0;
                    mValue = null;
                    return null;
                }

                Node parentNode;
                int parentPos;

                latchParent: {
                    splitCheck: {
                        // Latch coupling up the tree usually works, so give it a
                        // try. If it works, then there's no need to worry about a
                        // node merge.
                        parentNode = parentFrame.tryAcquireExclusive();

                        if (parentNode == null) {
                            // Latch coupling failed, and so acquire parent latch
                            // without holding child latch. The child might have
                            // changed, and so it must be checked again.
                            node.releaseExclusive();
                            parentNode = parentFrame.acquireExclusive();
                            if (parentNode.mSplit == null) {
                                break splitCheck;
                            }
                        } else {
                            if (parentNode.mSplit == null) {
                                frame.popv();
                                node.releaseExclusive();
                                parentPos = parentFrame.mNodePos;
                                break latchParent;
                            }
                            node.releaseExclusive();
                        }

                        // When this point is reached, parent node must be split.
                        // Parent latch is held, child latch is not held, but the
                        // frame is still valid.

                        parentNode = finishSplit(parentFrame, parentNode);
                    }

                    // When this point is reached, child must be relatched. Parent
                    // latch is held, and the child frame is still valid.

                    parentPos = parentFrame.mNodePos;
                    node = latchChild(parentNode, parentPos, false);

                    // Quick check again, in case node got bigger due to merging.
                    // Unlike the earlier quick check, this one must handle
                    // internal nodes too.
                    quick: {
                        int pos = frame.mNodePos;

                        int highest;
                        if (pos < 0) {
                            pos = ~2 - pos; // eq: (~pos) - 2;
                            if (pos >= (highest = node.highestLeafPos())) {
                                break quick;
                            }
                            frame.mNotFoundKey = null;
                        } else if (pos >= (highest = node.highestPos())) {
                            break quick;
                        }

                        parentNode.releaseExclusive();

                        if (frame == mLeaf) {
                            int avail = (highest - pos) >> 1;
                            if (avail >= amount) {
                                frame.mNodePos = pos + (((int) amount) << 1);
                                return frame;
                            } else {
                                frame.mNodePos = highest;
                                amount -= avail;
                            }
                        }

                        // Increment position of internal node.
                        frame.mNodePos = (pos += 2);

                        if (!toFirst(latchChild(node, pos, true), new TreeCursorFrame(frame))) {
                            return null;
                        }
                        frame = mLeaf;
                        if (--amount <= 0) {
                            return frame;
                        }
                        continue outer;
                    }

                    frame.popv();
                    node.releaseExclusive();
                }

                // When this point is reached, only the parent latch is held. Child
                // frame is no longer valid.

                if (parentPos < parentNode.highestInternalPos()) {
                    parentFrame.mNodePos = (parentPos += 2);
                    if (!toFirst(latchChild(parentNode, parentPos, true),
                                 new TreeCursorFrame(parentFrame)))
                    {
                        return null;
                    }
                    frame = mLeaf;
                    if (--amount <= 0) {
                        return frame;
                    }
                    continue outer;
                }

                frame = parentFrame;
                node = parentNode;
            }
        }
    }

    @Override
    public LockResult previous() throws IOException {
        return previous(mTxn, leafExclusiveNotSplit());
    }

    //@Override
    public LockResult previous(long maxWait, TimeUnit unit) throws IOException {
        return previous(mTxn, leafExclusiveNotSplit(), maxWait, unit);
    }

    @Override
    public LockResult previousGe(byte[] limitKey) throws IOException {
        return previousCmp(limitKey, LIMIT_GE);
    }

    //@Override
    public LockResult previousGe(byte[] limitKey, long maxWait, TimeUnit unit) throws IOException {
        return previousCmp(limitKey, LIMIT_GE, maxWait, unit);
    }

    @Override
    public LockResult previousGt(byte[] limitKey) throws IOException {
        return previousCmp(limitKey, LIMIT_GT);
    }

    //@Override
    public LockResult previousGt(byte[] limitKey, long maxWait, TimeUnit unit) throws IOException {
        return previousCmp(limitKey, LIMIT_GT, maxWait, unit);
    }

    private LockResult previousCmp(byte[] limitKey, int limitMode) throws IOException {
        Transaction txn = mTxn;
        TreeCursorFrame frame = leafExclusiveNotSplit();

        while (true) {
            if (!toPrevious(frame)) {
                return LockResult.UNOWNED;
            }
            LockResult result = tryCopyCurrentCmp(txn, limitKey, limitMode);
            if (result != null) {
                // Extra check for filtering ghosts.
                if (mKey == null || mValue != null) {
                    return result;
                }
            } else if ((result = lockAndCopyIfExists(txn)) != null) {
                return result;
            }
            frame = leafExclusiveNotSplit();
        }
    }

    private LockResult previousCmp(byte[] limitKey, int limitMode, long maxWait, TimeUnit unit)
        throws IOException
    {
        Transaction txn = mTxn;
        TreeCursorFrame frame = leafExclusiveNotSplit();

        while (true) {
            if (!toPrevious(frame)) {
                return LockResult.UNOWNED;
            }
            LockResult result = tryCopyCurrentCmp(txn, limitKey, limitMode);
            if (result != null) {
                // Extra check for filtering ghosts.
                if (mKey == null || mValue != null) {
                    return result;
                }
            } else if ((result = lockAndCopyIfExists(txn, maxWait, unit)) != null) {
                return result;
            }
            frame = leafExclusiveNotSplit();
        }
    }

    /**
     * Note: When method returns, frame is unlatched and may no longer be valid.
     *
     * @param frame leaf frame, not split, with exclusive latch
     */
    private LockResult previous(Transaction txn, TreeCursorFrame frame) throws IOException {
        while (true) {
            if (!toPrevious(frame)) {
                return LockResult.UNOWNED;
            }
            LockResult result = tryCopyCurrent(txn);
            if (result != null) {
                // Extra check for filtering ghosts.
                if (mKey == null || mValue != null) {
                    return result;
                }
            } else if ((result = lockAndCopyIfExists(txn)) != null) {
                return result;
            }
            frame = leafExclusiveNotSplit();
        }
    }

    /**
     * Note: When method returns, frame is unlatched and may no longer be valid.
     *
     * @param frame leaf frame, not split, with exclusive latch
     */
    private LockResult previous(Transaction txn, TreeCursorFrame frame,
                                long maxWait, TimeUnit unit)
        throws IOException
    {
        while (true) {
            if (!toPrevious(frame)) {
                return LockResult.UNOWNED;
            }
            LockResult result = tryCopyCurrent(txn);
            if (result != null) {
                // Extra check for filtering ghosts.
                if (mKey == null || mValue != null) {
                    return result;
                }
            } else if ((result = lockAndCopyIfExists(txn, maxWait, unit)) != null) {
                return result;
            }
            frame = leafExclusiveNotSplit();
        }
    }

    /**
     * Note: When method returns, frame is unlatched and may no longer be
     * valid. Leaf frame remains latched when method returns true.
     *
     * @param frame leaf frame, not split, with exclusive latch
     * @return false if nothing left
     */
    private boolean toPrevious(TreeCursorFrame frame) throws IOException {
        Node node = frame.mNode;

        quick: {
            int pos = frame.mNodePos;
            if (pos < 0) {
                pos = ~pos;
                if (pos == 0) {
                    break quick;
                }
                frame.mNotFoundKey = null;
            } else if (pos == 0) {
                break quick;
            }
            frame.mNodePos = pos - 2;
            return true;
        }

        while (true) {
            TreeCursorFrame parentFrame = frame.peek();

            if (parentFrame == null) {
                frame.popv();
                node.releaseExclusive();
                mLeaf = null;
                mKey = null;
                mKeyHash = 0;
                mValue = null;
                return false;
            }

            Node parentNode;
            int parentPos;

            latchParent: {
                splitCheck: {
                    // Latch coupling up the tree usually works, so give it a
                    // try. If it works, then there's no need to worry about a
                    // node merge.
                    parentNode = parentFrame.tryAcquireExclusive();

                    if (parentNode == null) {
                        // Latch coupling failed, and so acquire parent latch
                        // without holding child latch. The child might have
                        // changed, and so it must be checked again.
                        node.releaseExclusive();
                        parentNode = parentFrame.acquireExclusive();
                        if (parentNode.mSplit == null) {
                            break splitCheck;
                        }
                    } else {
                        if (parentNode.mSplit == null) {
                            frame.popv();
                            node.releaseExclusive();
                            parentPos = parentFrame.mNodePos;
                            break latchParent;
                        }
                        node.releaseExclusive();
                    }

                    // When this point is reached, parent node must be split.
                    // Parent latch is held, child latch is not held, but the
                    // frame is still valid.

                    parentNode = finishSplit(parentFrame, parentNode);
                }

                // When this point is reached, child must be relatched. Parent
                // latch is held, and the child frame is still valid.

                parentPos = parentFrame.mNodePos;
                node = latchChild(parentNode, parentPos, false);

                // Quick check again, in case node got bigger due to merging.
                // Unlike the earlier quick check, this one must handle
                // internal nodes too.
                quick: {
                    int pos = frame.mNodePos;

                    if (pos < 0) {
                        pos = ~pos;
                        if (pos == 0) {
                            break quick;
                        }
                        frame.mNotFoundKey = null;
                    } else if (pos == 0) {
                        break quick;
                    }

                    parentNode.releaseExclusive();
                    frame.mNodePos = (pos -= 2);

                    if (frame != mLeaf) {
                        return toLast(latchChild(node, pos, true), new TreeCursorFrame(frame));
                    }

                    return true;
                }

                frame.popv();
                node.releaseExclusive();
            }

            // When this point is reached, only the parent latch is held. Child
            // frame is no longer valid.

            if (parentPos > 0) {
                parentFrame.mNodePos = (parentPos -= 2);
                return toLast(latchChild(parentNode, parentPos, true),
                              new TreeCursorFrame(parentFrame));
            }

            frame = parentFrame;
            node = parentNode;
        }
    }

    /**
     * @param frame leaf frame, not split, with exclusive latch
     * @return latched leaf frame or null if reached end
     */
    private TreeCursorFrame skipPreviousGap(TreeCursorFrame frame, long amount)
        throws IOException
    {
        outer: while (true) {
            Node node = frame.mNode;

            quick: {
                int pos = frame.mNodePos;

                if (pos < 0) {
                    pos = ~pos;
                    if (pos == 0) {
                        break quick;
                    }
                    frame.mNotFoundKey = null;
                } else if (pos == 0) {
                    break quick;
                }

                int avail = pos >> 1;
                if (avail >= amount) {
                    frame.mNodePos = pos - (((int) amount) << 1);
                    return frame;
                } else {
                    frame.mNodePos = 0;
                    amount -= avail;
                }
            }

            while (true) {
                TreeCursorFrame parentFrame = frame.peek();

                if (parentFrame == null) {
                    frame.popv();
                    node.releaseExclusive();
                    mLeaf = null;
                    mKey = null;
                    mKeyHash = 0;
                    mValue = null;
                    return null;
                }

                Node parentNode;
                int parentPos;

                latchParent: {
                    splitCheck: {
                        // Latch coupling up the tree usually works, so give it a
                        // try. If it works, then there's no need to worry about a
                        // node merge.
                        parentNode = parentFrame.tryAcquireExclusive();

                        if (parentNode == null) {
                            // Latch coupling failed, and so acquire parent latch
                            // without holding child latch. The child might have
                            // changed, and so it must be checked again.
                            node.releaseExclusive();
                            parentNode = parentFrame.acquireExclusive();
                            if (parentNode.mSplit == null) {
                                break splitCheck;
                            }
                        } else {
                            if (parentNode.mSplit == null) {
                                frame.popv();
                                node.releaseExclusive();
                                parentPos = parentFrame.mNodePos;
                                break latchParent;
                            }
                            node.releaseExclusive();
                        }

                        // When this point is reached, parent node must be split.
                        // Parent latch is held, child latch is not held, but the
                        // frame is still valid.

                        parentNode = finishSplit(parentFrame, parentNode);
                    }

                    // When this point is reached, child must be relatched. Parent
                    // latch is held, and the child frame is still valid.

                    parentPos = parentFrame.mNodePos;
                    node = latchChild(parentNode, parentPos, false);

                    // Quick check again, in case node got bigger due to merging.
                    // Unlike the earlier quick check, this one must handle
                    // internal nodes too.
                    quick: {
                        int pos = frame.mNodePos;

                        if (pos < 0) {
                            pos = ~pos;
                            if (pos == 0) {
                                break quick;
                            }
                            frame.mNotFoundKey = null;
                        } else if (pos == 0) {
                            break quick;
                        }

                        parentNode.releaseExclusive();

                        if (frame == mLeaf) {
                            int avail = pos >> 1;
                            if (avail >= amount) {
                                frame.mNodePos = pos - (((int) amount) << 1);
                                return frame;
                            } else {
                                frame.mNodePos = 0;
                                amount -= avail;
                            }
                        }

                        // Decrement position of internal node.
                        frame.mNodePos = (pos -= 2);

                        if (!toLast(latchChild(node, pos, true), new TreeCursorFrame(frame))) {
                            return null;
                        }
                        frame = mLeaf;
                        if (--amount <= 0) {
                            return frame;
                        }
                        continue outer;
                    }

                    frame.popv();
                    node.releaseExclusive();
                }

                // When this point is reached, only the parent latch is held. Child
                // frame is no longer valid.

                if (parentPos > 0) {
                    parentFrame.mNodePos = (parentPos -= 2);
                    if (!toLast(latchChild(parentNode, parentPos, true),
                                new TreeCursorFrame(parentFrame)))
                    {
                        return null;
                    }
                    frame = mLeaf;
                    if (--amount <= 0) {
                        return frame;
                    }
                    continue outer;
                }

                frame = parentFrame;
                node = parentNode;
            }
        }
    }

    /**
     * Try to copy the current entry, locking it if required. Null is returned
     * if lock is not immediately available and only the key was copied. Node
     * latch is always released by this method, even if an exception is thrown.
     *
     * @return null, UNOWNED, INTERRUPTED, TIMED_OUT_LOCK, ACQUIRED,
     * OWNED_SHARED, OWNED_UPGRADABLE, or OWNED_EXCLUSIVE
     * @param txn optional
     */
    private LockResult tryCopyCurrent(Transaction txn) throws IOException {
        final Node node;
        final int pos;
        {
            TreeCursorFrame leaf = mLeaf;
            node = leaf.mNode;
            pos = leaf.mNodePos;
        }

        try {
            mKeyHash = 0;

            final LockMode mode;
            if (txn == null) {
                mode = LockMode.READ_COMMITTED;
            } else if ((mode = txn.lockMode()).noReadLock) {
                if (mKeyOnly) {
                    mKey = node.retrieveKey(pos);
                    mValue = node.hasLeafValue(pos);
                } else {
                    node.retrieveLeafEntry(pos, this);
                }
                return LockResult.UNOWNED;
            }

            // Copy key for now, because lock might not be available. Value
            // might change after latch is released. Assign NOT_LOADED, in case
            // lock cannot be granted at all. This prevents uncommited value
            // from being exposed.
            mKey = node.retrieveKey(pos);
            mValue = NOT_LOADED;

            try {
                LockResult result;

                switch (mode) {
                default:
                    if (mTree.isLockAvailable(txn, mKey, keyHash())) {
                        // No need to acquire full lock.
                        mValue = mKeyOnly ? node.hasLeafValue(pos)
                            : node.retrieveLeafValue(mTree, pos);
                        return LockResult.UNOWNED;
                    } else {
                        return null;
                    }

                case REPEATABLE_READ:
                    result = txn.tryLockShared(mTree.mId, mKey, keyHash(), 0);
                    break;

                case UPGRADABLE_READ:
                    result = txn.tryLockUpgradable(mTree.mId, mKey, keyHash(), 0);
                    break;
                }

                if (result.isHeld()) {
                    mValue = mKeyOnly ? node.hasLeafValue(pos)
                        : node.retrieveLeafValue(mTree, pos);
                    return result;
                } else {
                    return null;
                }
            } catch (DeadlockException e) {
                // Not expected with timeout of zero anyhow.
                return null;
            }
        } finally {
            node.releaseExclusive();
        }
    }

    /**
     * Variant of tryCopyCurrent used by iteration methods which have a
     * limit. If limit is reached, cursor is reset and UNOWNED is returned.
     */
    private LockResult tryCopyCurrentCmp(Transaction txn, byte[] limitKey, int limitMode)
        throws IOException
    {
        final Node node;
        final int pos;
        {
            TreeCursorFrame leaf = mLeaf;
            node = leaf.mNode;
            pos = leaf.mNodePos;
        }

        byte[] key = node.retrieveKeyCmp(pos, limitKey, limitMode);

        check: {
            if (key != null) {
                if (key != limitKey) {
                    mKey = key;
                    break check;
                } else if ((limitMode & 1) != 0) {
                    // Cursor contract does not claim ownership of limitKey instance.
                    mKey = key.clone();
                    break check;
                }
            }

            // Limit has been reached.
            node.releaseExclusive();
            reset();
            return LockResult.UNOWNED;
        }

        mKeyHash = 0;

        try {
            final LockMode mode;
            if (txn == null) {
                mode = LockMode.READ_COMMITTED;
            } else if ((mode = txn.lockMode()).noReadLock) {
                mValue = mKeyOnly ? node.hasLeafValue(pos) : node.retrieveLeafValue(mTree, pos);
                return LockResult.UNOWNED;
            }

            mValue = NOT_LOADED;

            try {
                LockResult result;

                switch (mode) {
                default:
                    if (mTree.isLockAvailable(txn, mKey, keyHash())) {
                        // No need to acquire full lock.
                        mValue = mKeyOnly ? node.hasLeafValue(pos)
                            : node.retrieveLeafValue(mTree, pos);
                        return LockResult.UNOWNED;
                    } else {
                        return null;
                    }

                case REPEATABLE_READ:
                    result = txn.tryLockShared(mTree.mId, mKey, keyHash(), 0);
                    break;

                case UPGRADABLE_READ:
                    result = txn.tryLockUpgradable(mTree.mId, mKey, keyHash(), 0);
                    break;
                }

                if (result.isHeld()) {
                    mValue = mKeyOnly ? node.hasLeafValue(pos)
                        : node.retrieveLeafValue(mTree, pos);
                    return result;
                } else {
                    return null;
                }
            } catch (DeadlockException e) {
                // Not expected with timeout of zero anyhow.
                return null;
            }
        } finally {
            node.releaseExclusive();
        }
    }

    /**
     * With node latch not held, lock the current key. Returns the lock result
     * if entry exists, null otherwise. Method is intended to be called for
     * operations which move the position, and so it should not retain locks
     * for entries which were concurrently deleted. The find operation is
     * required to lock entries which don't exist.
     *
     * @param txn optional
     * @return null if current entry has been deleted
     */
    private LockResult lockAndCopyIfExists(Transaction txn) throws IOException {
        if (txn == null) {
            Locker locker = mTree.lockSharedLocal(mKey, keyHash());
            try {
                if (copyIfExists()) {
                    return LockResult.UNOWNED;
                }
            } finally {
                locker.unlock();
            }
        } else {
            LockResult result;

            switch (txn.lockMode()) {
                // Default case should only capture READ_COMMITTED, since the
                // no-lock modes were already handled.
            default:
                if ((result = txn.lockShared(mTree.mId, mKey, keyHash())) == LockResult.ACQUIRED) {
                    result = LockResult.UNOWNED;
                }
                break;

            case REPEATABLE_READ:
                result = txn.lockShared(mTree.mId, mKey, keyHash());
                break;

            case UPGRADABLE_READ:
                result = txn.lockUpgradable(mTree.mId, mKey, keyHash());
                break;
            }

            if (copyIfExists()) {
                if (result == LockResult.UNOWNED) {
                    txn.unlock();
                }
                return result;
            }

            if (result == LockResult.UNOWNED || result == LockResult.ACQUIRED) {
                txn.unlock();
            }
        }

        // Entry does not exist, and lock has been released if was just acquired.
        return null;
    }

    /**
     * With node latch not held, lock the current key. Returns the lock result
     * if entry exists, null otherwise. Method is intended to be called for
     * operations which move the position, and so it should not retain locks
     * for entries which were concurrently deleted. The find operation is
     * required to lock entries which don't exist.
     *
     * @param txn optional
     * @return null if current entry has been deleted or lock not available in time
     */
    private LockResult lockAndCopyIfExists(Transaction txn, long maxWait, TimeUnit unit)
        throws IOException
    {
        long nanosTimeout = Utils.toNanos(maxWait, unit);

        if (txn == null) {
            Locker locker = mTree.mLockManager.localLocker();
            LockResult result = locker.lockSharedNT(mTree.mId, mKey, keyHash(), nanosTimeout);
            if (!result.isHeld()) {
                return null;
            }
            try {
                if (copyIfExists()) {
                    return LockResult.UNOWNED;
                }
            } finally {
                locker.unlock();
            }
        } else {
            LockResult result;

            switch (txn.lockMode()) {
                // Default case should only capture READ_COMMITTED, since the
                // no-lock modes were already handled.
            default:
                result = txn.lockSharedNT(mTree.mId, mKey, keyHash(), nanosTimeout);
                if (!result.isHeld()) {
                    return null;
                }
                if (result == LockResult.ACQUIRED) {
                    result = LockResult.UNOWNED;
                }
                break;

            case REPEATABLE_READ:
                result = txn.lockSharedNT(mTree.mId, mKey, keyHash(), nanosTimeout);
                if (!result.isHeld()) {
                    return null;
                }
                break;

            case UPGRADABLE_READ:
                result = txn.lockUpgradableNT(mTree.mId, mKey, keyHash(), nanosTimeout);
                if (!result.isHeld()) {
                    return null;
                }
                break;
            }

            if (copyIfExists()) {
                if (result == LockResult.UNOWNED) {
                    txn.unlock();
                }
                return result;
            }

            if (result == LockResult.UNOWNED || result == LockResult.ACQUIRED) {
                txn.unlock();
            }
        }

        // Entry does not exist, and lock has been released if was just acquired.
        return null;
    }

    private boolean copyIfExists() throws IOException {
        TreeCursorFrame frame = leafSharedNotSplit();
        Node node = frame.mNode;
        try {
            int pos = frame.mNodePos;
            if (pos < 0) {
                return false;
            } else if (mKeyOnly) {
                return (mValue = node.hasLeafValue(pos)) != null;
            } else {
                return (mValue = node.retrieveLeafValue(mTree, pos)) != null;
            }
        } finally {
            node.releaseShared();
        }
    }

    /**
     * @return 0 if load operation does not acquire a lock
     */
    private int keyHashForLoad(Transaction txn, byte[] key) {
        if (txn != null) {
            LockMode mode = txn.lockMode();
            if (mode == LockMode.READ_UNCOMMITTED || mode == LockMode.UNSAFE) {
                return 0;
            }
        }
        return LockManager.hash(mTree.mId, key);
    }

    /**
     * @return 0 if load operation does not acquire a lock
     */
    private int keyHashForStore(Transaction txn, byte[] key) {
        return (txn != null && txn.lockMode() == LockMode.UNSAFE) ? 0
            : LockManager.hash(mTree.mId, key);
    }

    private static final int
        VARIANT_REGULAR = 0,
        VARIANT_NEARBY  = 1,
        VARIANT_RETAIN  = 2, // retain node latch only if value is null
        VARIANT_NO_LOCK = 3, // retain node latch always, don't lock entry
        VARIANT_CHECK   = 4; // retain node latch always, don't lock entry, don't load entry

    @Override
    public LockResult find(byte[] key) throws IOException {
        Transaction txn = mTxn;
        return find(txn, key, keyHashForLoad(txn, key), VARIANT_REGULAR);
    }

    @Override
    public LockResult findGe(byte[] key) throws IOException {
        // If isolation level is read committed, then key must be
        // locked. Otherwise, an uncommitted delete could be observed.
        Transaction txn = mTxn;
        LockResult result = find(txn, key, keyHashForLoad(txn, key), VARIANT_RETAIN);
        if (mValue != null) {
            return result;
        } else {
            if (result == LockResult.ACQUIRED) {
                txn.unlock();
            }
            return next(txn, mLeaf);
        }
    }

    //@Override
    public LockResult findGe(byte[] key, long maxWait, TimeUnit unit) throws IOException {
        Transaction txn = mTxn;
        find(txn, key, 0, VARIANT_CHECK);
        if (mValue != null) { // mValue == NOT_LOADED
            mLeaf.mNode.releaseExclusive();
            LockResult result = loadNT(maxWait, unit);
            if (result != LockResult.TIMED_OUT_LOCK) {
                return result;
            }
            return next(maxWait, unit);
        } else {
            return next(txn, mLeaf, maxWait, unit);
        }
    }

    @Override
    public LockResult findGt(byte[] key) throws IOException {
        // Never lock the requested key.
        Transaction txn = mTxn;
        find(txn, key, 0, VARIANT_CHECK);
        return next(txn, mLeaf);
    }

    //@Override
    public LockResult findGt(byte[] key, long maxWait, TimeUnit unit) throws IOException {
        // Never lock the requested key.
        Transaction txn = mTxn;
        find(txn, key, 0, VARIANT_CHECK);
        return next(txn, mLeaf, maxWait, unit);
    }

    @Override
    public LockResult findLe(byte[] key) throws IOException {
        // If isolation level is read committed, then key must be
        // locked. Otherwise, an uncommitted delete could be observed.
        Transaction txn = mTxn;
        LockResult result = find(txn, key, keyHashForLoad(txn, key), VARIANT_RETAIN);
        if (mValue != null) {
            return result;
        } else {
            if (result == LockResult.ACQUIRED) {
                txn.unlock();
            }
            return previous(txn, mLeaf);
        }
    }

    //@Override
    public LockResult findLe(byte[] key, long maxWait, TimeUnit unit) throws IOException {
        Transaction txn = mTxn;
        find(txn, key, 0, VARIANT_CHECK);
        if (mValue != null) { // mValue == NOT_LOADED
            mLeaf.mNode.releaseExclusive();
            LockResult result = loadNT(maxWait, unit);
            if (result != LockResult.TIMED_OUT_LOCK) {
                return result;
            }
            return previous(maxWait, unit);
        } else {
            return previous(txn, mLeaf, maxWait, unit);
        }
    }

    @Override
    public LockResult findLt(byte[] key) throws IOException {
        // Never lock the requested key.
        Transaction txn = mTxn;
        find(txn, key, 0, VARIANT_CHECK);
        return previous(txn, mLeaf);
    }

    //@Override
    public LockResult findLt(byte[] key, long maxWait, TimeUnit unit) throws IOException {
        // Never lock the requested key.
        Transaction txn = mTxn;
        find(txn, key, 0, VARIANT_CHECK);
        return previous(txn, mLeaf, maxWait, unit);
    }

    @Override
    public LockResult findNearby(byte[] key) throws IOException {
        Transaction txn = mTxn;
        return find(txn, key, keyHashForLoad(txn, key), VARIANT_NEARBY);
    }

    /**
     * @param hash can pass 0 if no lock is required
     */
    private LockResult find(Transaction txn, byte[] key, int hash, int variant)
        throws IOException
    {
        if (key == null) {
            throw new NullPointerException("Key is null");
        }

        mKey = key;
        mKeyHash = hash;

        Node node;
        TreeCursorFrame frame;

        nearby: if (variant == VARIANT_NEARBY) {
            frame = mLeaf;
            if (frame == null) {
                // Allocate new frame before latching root -- allocation can block.
                frame = new TreeCursorFrame();
                node = mTree.mRoot;
                node.acquireExclusive();
                break nearby;
            }

            node = frame.acquireExclusive();
            if (node.mSplit != null) {
                node = finishSplit(frame, node);
            }

            int startPos = frame.mNodePos;
            if (startPos < 0) {
                startPos = ~startPos;
            }

            int pos = node.binarySearch(key, startPos);

            if (pos >= 0) {
                frame.mNotFoundKey = null;
                frame.mNodePos = pos;
                try {
                    LockResult result = tryLockKey(txn);
                    if (result == null) {
                        mValue = NOT_LOADED;
                    } else {
                        try {
                            mValue = mKeyOnly ? node.hasLeafValue(pos)
                                : node.retrieveLeafValue(mTree, pos);
                            return result;
                        } catch (Throwable e) {
                            mValue = NOT_LOADED;
                            throw Utils.rethrow(e);
                        }
                    }
                } finally {
                    node.releaseExclusive();
                }
                return doLoad(txn);
            } else if (pos != ~0 && ~pos <= node.highestLeafPos()) {
                // Not found, but insertion pos is in bounds.
                frame.mNotFoundKey = key;
                frame.mNodePos = pos;
                LockResult result = tryLockKey(txn);
                if (result == null) {
                    mValue = NOT_LOADED;
                    node.releaseExclusive();
                } else {
                    mValue = null;
                    node.releaseExclusive();
                    return result;
                }
                return doLoad(txn);
            }

            // Cannot be certain if position is in leaf node, so pop up.

            mLeaf = null;

            while (true) {
                TreeCursorFrame parent = frame.pop();

                if (parent == null) {
                    // Usually the root frame refers to the root node, but it
                    // can be wrong if the tree height is changing.
                    Node root = mTree.mRoot;
                    if (node != root) {
                        node.releaseExclusive();
                        root.acquireExclusive();
                        node = root;
                    }
                    break;
                }

                node.releaseExclusive();
                frame = parent;
                node = frame.acquireExclusive();

                // Only search inside non-split nodes. It's easier to just pop
                // up rather than finish or search the split.
                // TODO: Search will immediately come back to split node,
                // spinning for a bit. Consider finishing the split.
                if (node.mSplit != null) {
                    continue;
                }

                pos = Node.internalPos(node.binarySearch(key, frame.mNodePos));

                if (pos == 0 || pos >= node.highestInternalPos()) {
                    // Cannot be certain if position is in this node, so pop up.
                    continue;
                }

                frame.mNodePos = pos;
                try {
                    node = latchChild(node, pos, true);
                } catch (Throwable e) {
                    throw cleanup(e, frame);
                }
                frame = new TreeCursorFrame(frame);
                break;
            }
        } else {
            // Other variants always discard existing frames.
            node = mTree.mRoot;
            frame = reset(node);
        }

        while (true) {
            if (node.isLeaf()) {
                int pos;
                if (node.mSplit == null) {
                    pos = node.binarySearch(key);
                    frame.bind(node, pos);
                } else {
                    pos = node.mSplit.binarySearch(node, key);
                    frame.bind(node, pos);
                    if (pos < 0) {
                        // The finishSplit method will release the latch, and
                        // so the frame must be completely defined first.
                        frame.mNotFoundKey = key;
                    }
                    node = finishSplit(frame, node);
                    pos = frame.mNodePos;
                }

                mLeaf = frame;

                LockResult result;
                if (variant >= VARIANT_NO_LOCK) {
                    result = LockResult.UNOWNED;
                } else if ((result = tryLockKey(txn)) == null) {
                    // Unable to immediately acquire the lock.
                    if (pos < 0) {
                        frame.mNotFoundKey = key;
                    }
                    mValue = NOT_LOADED;
                    node.releaseExclusive();
                    // This might fail to acquire the lock too, but the cursor
                    // is at the proper position, and with the proper state.
                    return doLoad(txn);
                }

                if (pos < 0) {
                    frame.mNotFoundKey = key;
                    mValue = null;
                    if (variant < VARIANT_RETAIN) {
                        node.releaseExclusive();
                    }
                } else {
                    if (variant == VARIANT_CHECK) {
                        mValue = NOT_LOADED;
                    } else {
                        try {
                            mValue = mKeyOnly ? node.hasLeafValue(pos)
                                : node.retrieveLeafValue(mTree, pos);
                        } catch (Throwable e) {
                            mValue = NOT_LOADED;
                            node.releaseExclusive();
                            throw Utils.rethrow(e);
                        }
                        if (variant < VARIANT_NO_LOCK) {
                            node.releaseExclusive();
                        }
                    }
                }
                return result;
            }

            Split split = node.mSplit;
            if (split == null) {
                int childPos = Node.internalPos(node.binarySearch(key));
                frame.bind(node, childPos);
                try {
                    node = latchChild(node, childPos, true);
                } catch (Throwable e) {
                    throw cleanup(e, frame);
                }
            } else {
                // Follow search into split, binding this frame to the unsplit
                // node as if it had not split. The binding will be corrected
                // when split is finished.

                final Node sibling = split.latchSibling();

                final Node left, right;
                if (split.mSplitRight) {
                    left = node;
                    right = sibling;
                } else {
                    left = sibling;
                    right = node;
                }

                final Node selected;
                final int selectedPos;

                if (split.compare(key) < 0) {
                    selected = left;
                    selectedPos = Node.internalPos(left.binarySearch(key));
                    frame.bind(node, selectedPos);
                    right.releaseExclusive();
                } else {
                    selected = right;
                    selectedPos = Node.internalPos(right.binarySearch(key));
                    frame.bind(node, left.highestInternalPos() + 2 + selectedPos);
                    left.releaseExclusive();
                }

                try {
                    node = latchChild(selected, selectedPos, true);
                } catch (Throwable e) {
                    throw cleanup(e, frame);
                }
            }

            frame = new TreeCursorFrame(frame);
        }
    }

    /**
     * With node latched, try to lock the current key. Method expects mKeyHash
     * to be valid. Returns null if lock is required but not immediately available.
     *
     * @param txn can be null
     */
    private LockResult tryLockKey(Transaction txn) {
        LockMode mode;

        if (txn == null || (mode = txn.lockMode()) == LockMode.READ_COMMITTED) {
            // If lock is available, no need to acquire full lock and
            // immediately release it because node is latched.
            return mTree.isLockAvailable(txn, mKey, mKeyHash) ? LockResult.UNOWNED : null;
        }

        try {
            LockResult result;

            switch (mode) {
            default: // no read lock requested by READ_UNCOMMITTED or UNSAFE
                return LockResult.UNOWNED;

            case REPEATABLE_READ:
                result = txn.tryLockShared(mTree.mId, mKey, mKeyHash, 0);
                break;

            case UPGRADABLE_READ:
                result = txn.tryLockUpgradable(mTree.mId, mKey, mKeyHash, 0);
                break;
            }

            return result.isHeld() ? result : null;
        } catch (DeadlockException e) {
            // Not expected with timeout of zero anyhow.
            return null;
        }
    }

    @Override
    public LockResult random(byte[] lowKey, byte[] highKey) throws IOException {
        Random rnd = Utils.random();

        start: while (true) {
            mKey = null;
            mKeyHash = 0;
            mValue = null;

            Node node = mTree.mRoot;
            TreeCursorFrame frame = reset(node);

            search: while (true) {
                if (node.mSplit != null) {
                    // Bind to anything to finish the split.
                    frame.bind(node, 0);
                    node = finishSplit(frame, node);
                }

                int pos;
                select: {
                    if (highKey == null) {
                        pos = node.highestPos() + 2;
                    } else {
                        pos = node.binarySearch(highKey);
                        if (!node.isLeaf()) {
                            pos = Node.internalPos(pos);
                        } else if (pos < 0) {
                            pos = ~pos;
                        }
                    }

                    if (lowKey == null) {
                        if (pos > 0) {
                            pos = (pos == 2) ? 0 : (rnd.nextInt(pos >> 1) << 1);
                            break select;
                        }
                    } else {
                        int lowPos = node.binarySearch(lowKey);
                        if (!node.isLeaf()) {
                            lowPos = Node.internalPos(lowPos);
                        } else if (lowPos < 0) {
                            lowPos = ~lowPos;
                        }
                        int range = pos - lowPos;
                        if (range > 0) {
                            pos = (range == 2) ? lowPos : lowPos + (rnd.nextInt(range >> 1) << 1);
                            break select;
                        }
                    }

                    // Node is empty or out of bounds, so pop up the tree.
                    TreeCursorFrame parent = frame.mParentFrame;
                    node.releaseExclusive();

                    if (parent == null) {
                        // Usually the root frame refers to the root node, but
                        // it can be wrong if the tree height is changing.
                        Node root = mTree.mRoot;
                        if (node == root) {
                            return LockResult.UNOWNED;
                        }
                        root.acquireExclusive();
                        node = root;
                    } else {
                        frame = parent;
                        node = frame.acquireExclusive();
                    }

                    continue search;
                }

                frame.bind(node, pos);

                if (node.isLeaf()) {
                    mLeaf = frame;
                    Transaction txn = mTxn;
                    mKeyHash = keyHashForLoad(txn, mKey = node.retrieveKey(pos));

                    LockResult result;
                    if ((result = tryLockKey(txn)) == null) {
                        // Unable to immediately acquire the lock.
                        mValue = NOT_LOADED;
                        node.releaseExclusive();
                        // This might fail to acquire the lock too, but the cursor
                        // is at the proper position, and with the proper state.
                        result = doLoad(txn);
                    } else {
                        try {
                            mValue = mKeyOnly ? node.hasLeafValue(pos)
                                : node.retrieveLeafValue(mTree, pos);
                        } catch (Throwable e) {
                            mValue = NOT_LOADED;
                            node.releaseExclusive();
                            throw Utils.rethrow(e);
                        }
                        node.releaseExclusive();
                    }

                    if (mValue == null) {
                        // Skip over ghosts. Attempting to lock ghosts in the
                        // first place is correct behavior, avoiding bias.
                        if (result == LockResult.ACQUIRED) {
                            txn.unlock();
                        }
                        frame = leafExclusiveNotSplit();
                        result = rnd.nextBoolean() ? next(txn, frame) : previous(txn, frame);
                        if (mValue == null) {
                            // Nothing but ghosts in selected direction, so start over.
                            continue start;
                        }
                    }

                    return result;
                } else {
                    try {
                        node = latchChild(node, pos, true);
                    } catch (Throwable e) {
                        throw cleanup(e, frame);
                    }
                }

                frame = new TreeCursorFrame(frame);
            }
        }
    }

    @Override
    public LockResult load() throws IOException {
        // This will always acquire a lock if required to. A try-lock pattern
        // can skip the lock acquisition in certain cases, but the optimization
        // doesn't seem worth the trouble.
        return doLoad(mTxn);
    }

    /**
     * Must be called with node latch not held.
     */
    private LockResult doLoad(Transaction txn) throws IOException {
        byte[] key = mKey;
        if (key == null) {
            throw new IllegalStateException("Cursor position is undefined");
        }

        LockResult result;
        Locker locker;

        if (txn == null) {
            result = LockResult.UNOWNED;
            locker = mTree.lockSharedLocal(key, keyHash());
        } else {
            switch (txn.lockMode()) {
            default: // no read lock requested by READ_UNCOMMITTED or UNSAFE
                result = LockResult.UNOWNED;
                locker = null;
                break;

            case READ_COMMITTED:
                if ((result = txn.lockShared(mTree.mId, key, keyHash())) == LockResult.ACQUIRED) {
                    result = LockResult.UNOWNED;
                    locker = txn;
                } else {
                    locker = null;
                }
                break;

            case REPEATABLE_READ:
                result = txn.lockShared(mTree.mId, key, keyHash());
                locker = null;
                break;

            case UPGRADABLE_READ:
                result = txn.lockUpgradable(mTree.mId, key, keyHash());
                locker = null;
                break;
            }
        }

        try {
            TreeCursorFrame frame = leafSharedNotSplit();
            Node node = frame.mNode;
            try {
                int pos = frame.mNodePos;
                mValue = pos >= 0 ? node.retrieveLeafValue(mTree, pos) : null;
            } finally {
                node.releaseShared();
            }
            return result;
        } finally {
            if (locker != null) {
                locker.unlock();
            }
        }
    }

    /**
     * NT == No Timeout or deadlock exception thrown
     *
     * @return TIMED_OUT_LOCK, UNOWNED, ACQUIRED, OWNED_SHARED, OWNED_UPGRADABLE, or
     * OWNED_EXCLUSIVE
     */
    private LockResult loadNT(long timeout, TimeUnit unit) throws IOException {
        byte[] key = mKey;
        if (key == null) {
            throw new IllegalStateException("Cursor position is undefined");
        }

        long nanosTimeout = Utils.toNanos(timeout, unit);

        LockResult result;
        Locker locker;

        Transaction txn = mTxn;
        if (txn == null) {
            locker = mTree.mLockManager.localLocker();
            result = locker.lockSharedNT(mTree.mId, mKey, keyHash(), nanosTimeout);
            if (!result.isHeld()) {
                return result;
            }
            result = LockResult.UNOWNED;
        } else {
            switch (txn.lockMode()) {
            default: // no read lock requested by READ_UNCOMMITTED or UNSAFE
                result = LockResult.UNOWNED;
                locker = null;
                break;

            case READ_COMMITTED:
                result = txn.lockSharedNT(mTree.mId, mKey, keyHash(), nanosTimeout);
                if (!result.isHeld()) {
                    return result;
                }
                if (result == LockResult.ACQUIRED) {
                    result = LockResult.UNOWNED;
                    locker = txn;
                } else {
                    locker = null;
                }
                break;

            case REPEATABLE_READ:
                result = txn.lockSharedNT(mTree.mId, mKey, keyHash(), nanosTimeout);
                if (!result.isHeld()) {
                    return result;
                }
                locker = null;
                break;

            case UPGRADABLE_READ:
                result = txn.lockUpgradableNT(mTree.mId, mKey, keyHash(), nanosTimeout);
                if (!result.isHeld()) {
                    return result;
                }
                locker = null;
                break;
            }
        }

        try {
            TreeCursorFrame frame = leafSharedNotSplit();
            Node node = frame.mNode;
            try {
                int pos = frame.mNodePos;
                mValue = pos >= 0 ? node.retrieveLeafValue(mTree, pos) : null;
            } finally {
                node.releaseShared();
            }
            return result;
        } finally {
            if (locker != null) {
                locker.unlock();
            }
        }
    }

    @Override
    public void store(byte[] value) throws IOException {
        byte[] key = mKey;
        if (key == null) {
            throw new IllegalStateException("Cursor position is undefined");
        }

        try {
            final Transaction txn = mTxn;
            final Locker locker = mTree.lockExclusive(txn, key, keyHash());
            try {
                TreeCursorFrame leaf = leafExclusive(); 
                final Lock sharedCommitLock = mTree.mDatabase.sharedCommitLock();
                sharedCommitLock.lock();
                try {
                    store(txn, leaf, value);
                } finally {
                    sharedCommitLock.unlock();
                }
            } finally {
                if (locker != null) {
                    locker.unlock();
                }
            }
        } catch (Throwable e) {
            throw handleException(e);
        }
    }

    /**
     * Called by Tree.clear method when using auto-commit transaction. Lock
     * acquisition is lenient. If record cannot be locked, it is skipped.
     */
    /*
    long clearTo(byte[] end, boolean inclusive) throws IOException {
        byte[] key = mKey;
        if (key == null) {
            return 0;
        }

        final Lock sharedCommitLock = mTree.mDatabase.sharedCommitLock();
        final long indexId = mTree.mId;
        final Locker locker = mTree.mLockManager.localLocker();

        long count = 0;

        do {
            int compare;
            if (end == null) {
                compare = -1;
            } else {
                compare = Utils.compareKeys(key, 0, key.length, end, 0, end.length);
                if (compare > 0 || (compare == 0 && !inclusive)) {
                    break;
                }
            }

            sharedCommitLock.lock();
            try {
                if (locker.tryLockExclusive(indexId, key, keyHash(), 0).isHeld()) {
                    try {
                        store(null, leafExclusive(), null);
                        count++;
                    } finally {
                        locker.unlock();
                    }
                }
            } catch (Throwable e) {
                throw handleException(e);
            } finally {
                sharedCommitLock.unlock();
            }

            if (compare >= 0) {
                break;
            }

            next();
        } while ((key = mKey) != null);

        return count;
    }
    */

    /**
     * Atomic find and store operation.
     */
    void findAndStore(byte[] key, byte[] value) throws IOException {
        try {
            final Transaction txn = mTxn;
            final int hash = keyHashForStore(txn, key);
            final Locker locker = mTree.lockExclusive(txn, key, hash);
            try {
                // Find with no lock because it has already been acquired.
                find(null, key, hash, VARIANT_NO_LOCK);
                
                final Lock sharedCommitLock = mTree.mDatabase.sharedCommitLock();
                sharedCommitLock.lock();
                try {
                    store(txn, mLeaf, value);
                } finally {
                    sharedCommitLock.unlock();
                }
            } finally {
                if (locker != null) {
                    locker.unlock();
                }
            }
        } catch (Throwable e) {
            throw handleException(e);
        }
    }

    /**
     * Atomic find and swap operation.
     */
    /*
    byte[] findAndSwap(byte[] key, byte[] newValue) throws IOException {
        try {
            final Transaction txn = mTxn;
            final int hash = keyHashForStore(txn, key);
            final Locker locker = mTree.lockExclusive(txn, key, hash);
            byte[] oldValue;
            try {
                // Find with no lock because it has already been acquired.
                find(null, key, hash, VARIANT_NO_LOCK);
                oldValue = mValue;

                final Lock sharedCommitLock = mTree.mDatabase.sharedCommitLock();
                sharedCommitLock.lock();
                try {
                    store(txn, mLeaf, newValue);
                } finally {
                    sharedCommitLock.unlock();
                }
            } finally {
                if (locker != null) {
                    locker.unlock();
                }
            }

            return oldValue;
        } catch (Throwable e) {
            throw handleException(e);
        }
    }
    */

    static final byte[] MODIFY_INSERT = new byte[0], MODIFY_REPLACE = new byte[0];

    /**
     * Atomic find and modify operation.
     *
     * @param oldValue MODIFY_INSERT, MODIFY_REPLACE, else update mode
     */
    boolean findAndModify(byte[] key, byte[] oldValue, byte[] newValue) throws IOException {
        final Transaction txn = mTxn;
        final int hash = keyHashForStore(txn, key);

        try {
            // Note: Acquire exclusive lock instead of performing upgrade
            // sequence. The upgrade would need to be performed with the node
            // latch held, which is deadlock prone.

            if (txn == null) {
                Locker locker = mTree.lockExclusiveLocal(key, hash);
                try {
                    return doFindAndModify(null, key, hash, oldValue, newValue);
                } finally {
                    locker.unlock();
                }
            }

            LockResult result;

            LockMode mode = txn.lockMode();
            if (mode == LockMode.UNSAFE) {
                // Indicate that no unlock should be performed.
                result = LockResult.OWNED_EXCLUSIVE;
            } else {
                result = txn.lockExclusive(mTree.mId, key, hash);
                if (result == LockResult.ACQUIRED &&
                    (mode == LockMode.REPEATABLE_READ || mode == LockMode.UPGRADABLE_READ))
                {
                    // Downgrade to upgradable when no modification is made, to
                    // preserve repeatable semantics and allow upgrade later.
                    result = LockResult.UPGRADED;
                }
            }

            try {
                if (doFindAndModify(txn, key, hash, oldValue, newValue)) {
                    // Indicate that no unlock should be performed.
                    result = LockResult.OWNED_EXCLUSIVE;
                    return true;
                }
                return false;
            } finally {
                if (result == LockResult.ACQUIRED) {
                    txn.unlock();
                } else if (result == LockResult.UPGRADED) {
                    txn.unlockToUpgradable();
                }
            }
        } catch (Throwable e) {
            throw handleException(e);
        }
    }

    private boolean doFindAndModify(Transaction txn, byte[] key, int hash,
                                    byte[] oldValue, byte[] newValue)
        throws IOException
    {
        // Find with no lock because caller must already acquire exclusive lock.
        find(null, key, hash, VARIANT_NO_LOCK);

        check: {
            if (oldValue == MODIFY_INSERT) {
                if (mValue == null) {
                    // Insert allowed.
                    break check;
                }
            } else if (oldValue == MODIFY_REPLACE) {
                if (mValue != null) {
                    // Replace allowed.
                    break check;
                }
            } else {
                if (mValue != null) {
                    if (Arrays.equals(oldValue, mValue)) {
                        // Update allowed.
                        break check;
                    }
                } else if (oldValue == null) {
                    if (newValue == null) {
                        // Update allowed, but nothing changed.
                        mLeaf.mNode.releaseExclusive();
                        return true;
                    } else {
                        // Update allowed.
                        break check;
                    }
                }
            }

            mLeaf.mNode.releaseExclusive();
            return false;
        }

        final Lock sharedCommitLock = mTree.mDatabase.sharedCommitLock();
        sharedCommitLock.lock();
        try {
            store(txn, mLeaf, newValue);
            return true;
        } finally {
            sharedCommitLock.unlock();
        }
    }

    /**
     * Non-transactional ghost delete. Caller is expected to hold exclusive key
     * lock. Method does nothing if a value exists.
     *
     * @return false if Tree is closed
     */
    boolean deleteGhost(byte[] key) throws IOException {
        try {
            // Find with no lock because it has already been acquired.
            // TODO: Use nearby optimization when used with transactional Index.clear.
            find(null, key, 0, VARIANT_NO_LOCK);

            TreeCursorFrame leaf = mLeaf;
            if (leaf.mNode.mPage == Utils.EMPTY_BYTES) {
                leaf.mNode.releaseExclusive();
                return false;
            }

            if (mValue == null) {
                final Lock sharedCommitLock = mTree.mDatabase.sharedCommitLock();
                sharedCommitLock.lock();
                try {
                    store(Transaction.BOGUS, leaf, null);
                } finally {
                    sharedCommitLock.unlock();
                }
            } else {
                leaf.mNode.releaseExclusive();
            }

            return true;
        } catch (Throwable e) {
            throw handleException(e);
        }
    }

    /**
     * Caller must hold shared commit lock, to prevent checkpoints from
     * observing in-progress splits.
     *
     * @param leaf leaf frame, latched exclusively, which is released by this method
     */
    private void store(Transaction txn, final TreeCursorFrame leaf, byte[] value)
        throws IOException
    {
        byte[] key = mKey;
        Node node = leaf.mNode;
        try {
            if (value == null) {
                // Delete entry...

                if (leaf.mNodePos < 0) {
                    // Entry doesn't exist, so nothing to do.
                    mValue = null;
                    return;
                }

                node = notSplitDirty(leaf);
                final int pos = leaf.mNodePos;

                if (txn == null) {
                    mTree.redoStore(key, null);
                    node.deleteLeafEntry(mTree, pos);
                } else {
                    if (txn.lockMode() == LockMode.UNSAFE) {
                        node.deleteLeafEntry(mTree, pos);
                        if (txn.mDurabilityMode != DurabilityMode.NO_LOG) {
                            txn.redoStore(mTree.mId, key, null);
                        }
                    } else {
                        node.txnDeleteLeafEntry(txn, mTree, key, keyHash(), pos);
                        // Above operation leaves a ghost, so no cursors to fix.
                        mValue = null;
                        return;
                    }
                }

                int newPos = ~pos;
                leaf.mNodePos = newPos;
                leaf.mNotFoundKey = key;

                // Fix all cursors bound to the node.
                TreeCursorFrame frame = node.mLastCursorFrame;
                do {
                    if (frame == leaf) {
                        // Don't need to fix self.
                        continue;
                    }

                    int framePos = frame.mNodePos;

                    if (framePos == pos) {
                        frame.mNodePos = newPos;
                        frame.mNotFoundKey = key;
                    } else if (framePos > pos) {
                        frame.mNodePos = framePos - 2;
                    } else if (framePos < newPos) {
                        // Position is a complement, so add instead of subtract.
                        frame.mNodePos = framePos + 2;
                    }
                } while ((frame = frame.mPrevCousin) != null);

                if (node.shouldLeafMerge()) {
                    try {
                        mergeLeaf(leaf, node);
                    } finally {
                        // Always released by mergeLeaf.
                        node = null;
                    }
                }

                mValue = null;
                return;
            }

            // Update and insert always dirty the node.
            node = notSplitDirty(leaf);
            final int pos = leaf.mNodePos;

            if (pos >= 0) {
                // Update entry...

                if (txn == null) {
                    mTree.redoStore(key, value);
                } else {
                    if (txn.lockMode() != LockMode.UNSAFE) {
                        node.txnPreUpdateLeafEntry(txn, mTree, key, pos);
                    }
                    if (txn.mDurabilityMode != DurabilityMode.NO_LOG) {
                        txn.redoStore(mTree.mId, key, value);
                    }
                }

                node.updateLeafValue(mTree, pos, 0, value);

                if (node.shouldLeafMerge()) {
                    try {
                        mergeLeaf(leaf, node);
                    } finally {
                        // Always released by mergeLeaf.
                        node = null;
                    }
                } else {
                    if (node.mSplit != null) {
                        node = finishSplit(leaf, node);
                    }
                }

                mValue = value;
                return;
            }

            // Insert entry...

            if (txn == null) {
                mTree.redoStore(key, value);
            } else {
                if (txn.lockMode() != LockMode.UNSAFE) {
                    txn.undoDelete(mTree.mId, key);
                }
                if (txn.mDurabilityMode != DurabilityMode.NO_LOG) {
                    txn.redoStore(mTree.mId, key, value);
                }
            }

            int newPos = ~pos;
            node.insertLeafEntry(mTree, newPos, key, value);

            leaf.mNodePos = newPos;
            leaf.mNotFoundKey = null;

            // Fix all cursors bound to the node.
            // Note: Same code as in insertFragmented method.
            TreeCursorFrame frame = node.mLastCursorFrame;
            do {
                if (frame == leaf) {
                    // Don't need to fix self.
                    continue;
                }

                int framePos = frame.mNodePos;

                if (framePos == pos) {
                    // Other cursor is at same not-found position as this one
                    // was. If keys are the same, then other cursor switches
                    // to a found state as well. If key is greater, then
                    // position needs to be updated.

                    byte[] frameKey = frame.mNotFoundKey;
                    int compare = Utils.compareKeys
                        (frameKey, 0, frameKey.length, key, 0, key.length);
                    if (compare > 0) {
                        // Position is a complement, so subtract instead of add.
                        frame.mNodePos = framePos - 2;
                    } else if (compare == 0) {
                        frame.mNodePos = newPos;
                        frame.mNotFoundKey = null;
                    }
                } else if (framePos >= newPos) {
                    frame.mNodePos = framePos + 2;
                } else if (framePos < pos) {
                    // Position is a complement, so subtract instead of add.
                    frame.mNodePos = framePos - 2;
                }
            } while ((frame = frame.mPrevCousin) != null);

            if (node.mSplit != null) {
                node = finishSplit(leaf, node);
            }

            mValue = value;
        } finally {
            if (node != null) {
                node.releaseExclusive();
            }
        }
    }

    /**
     * Non-transactional insert of a fragmented value. Cursor value is
     * NOT_LOADED as a side-effect.
     *
     * @param leaf leaf frame, latched exclusively, which is released by this method
     */
    boolean insertFragmented(byte[] value) throws IOException {
        byte[] key = mKey;
        if (key == null) {
            throw new IllegalStateException("Cursor position is undefined");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value is null");
        }

        Lock sharedCommitLock = mTree.mDatabase.sharedCommitLock();
        sharedCommitLock.lock();
        try {
            final TreeCursorFrame leaf = leafExclusive();
            Node node = notSplitDirty(leaf);
            try {
                final int pos = leaf.mNodePos;
                if (pos >= 0) {
                    // Entry already exists.
                    if (mValue != null) {
                        return false;
                    }
                    // Replace ghost.
                    node.updateLeafValue(mTree, pos, Node.VALUE_FRAGMENTED, value);
                } else {
                    int newPos = ~pos;
                    node.insertFragmentedLeafEntry(mTree, newPos, key, value);

                    leaf.mNodePos = newPos;
                    leaf.mNotFoundKey = null;

                    // Fix all cursors bound to the node.
                    // Note: Same code as in store method.
                    TreeCursorFrame frame = node.mLastCursorFrame;
                    do {
                        if (frame == leaf) {
                            // Don't need to fix self.
                            continue;
                        }

                        int framePos = frame.mNodePos;

                        if (framePos == pos) {
                            // Other cursor is at same not-found position as this one
                            // was. If keys are the same, then other cursor switches
                            // to a found state as well. If key is greater, then
                            // position needs to be updated.

                            byte[] frameKey = frame.mNotFoundKey;
                            int compare = Utils.compareKeys
                                (frameKey, 0, frameKey.length, key, 0, key.length);
                            if (compare > 0) {
                                // Position is a complement, so subtract instead of add.
                                frame.mNodePos = framePos - 2;
                            } else if (compare == 0) {
                                frame.mNodePos = newPos;
                                frame.mNotFoundKey = null;
                            }
                        } else if (framePos >= newPos) {
                            frame.mNodePos = framePos + 2;
                        } else if (framePos < pos) {
                            // Position is a complement, so subtract instead of add.
                            frame.mNodePos = framePos - 2;
                        }
                    } while ((frame = frame.mPrevCousin) != null);
                }

                if (node.mSplit != null) {
                    node = finishSplit(leaf, node);
                }

                mValue = NOT_LOADED;
            } finally {
                node.releaseExclusive();
            }
        } catch (Throwable e) {
            throw handleException(e);
        } finally {
            sharedCommitLock.unlock();
        }

        return true;
    }

    private IOException handleException(Throwable e) throws IOException {
        // Any unexpected exception can corrupt the internal store state.
        // Closing down protects the persisted state.
        if (mLeaf == null && e instanceof IllegalStateException) {
            // Exception is caused by cursor state; store is safe.
            throw (IllegalStateException) e;
        }

        if (e instanceof DatabaseException) {
            DatabaseException de = (DatabaseException) e;
            if (de.isRecoverable()) {
                throw de;
            }
        }

        try {
            throw Utils.closeOnFailure(mTree.mDatabase, e);
        } finally {
            reset();
        }
    }

    @Override
    public TreeCursor copy() {
        TreeCursor copy = new TreeCursor(mTree, mTxn);
        TreeCursorFrame frame = mLeaf;
        if (frame != null) {
            TreeCursorFrame frameCopy = new TreeCursorFrame();
            frame.copyInto(frameCopy);
            copy.mLeaf = frameCopy;
        }
        copy.mKey = mKey;
        copy.mKeyHash = mKeyHash;
        if (!(copy.mKeyOnly = mKeyOnly)) {
            byte[] value = mValue;
            copy.mValue = (value == null || value.length == 0) ? value : value.clone();
        }
        return copy;
    }

    @Override
    public void reset() {
        TreeCursorFrame frame = mLeaf;
        mLeaf = null;
        mKey = null;
        mKeyHash = 0;
        mValue = null;
        if (frame != null) {
            TreeCursorFrame.popAll(frame);
        }
    }

    /**
     * Called if an exception is thrown while frames are being constructed.
     * Given frame does not need to be bound, but it must not be latched.
     */
    private RuntimeException cleanup(Throwable e, TreeCursorFrame frame) {
        mLeaf = frame;
        reset();
        return Utils.rethrow(e);
    }

    @Override
    public void close() {
        reset();
    }

    @Override
    public void close(Throwable cause) {
        try {
            if (cause instanceof DatabaseException) {
                DatabaseException de = (DatabaseException) cause;
                if (de.isRecoverable()) {
                    return;
                }
            }
            throw Utils.closeOnFailure(mTree.mDatabase, cause);
        } catch (IOException e) {
            // Ignore.
        } finally {
            reset();
        }
    }

    /**
     * Resets all frames and latches root node, exclusively. Although the
     * normal reset could be called directly, this variant avoids unlatching
     * the root node, since a find operation would immediately relatch it.
     *
     * @return new or recycled frame
     */
    private TreeCursorFrame reset(Node root) {
        TreeCursorFrame frame = mLeaf;
        if (frame == null) {
            // Allocate new frame before latching root -- allocation can block.
            frame = new TreeCursorFrame();
            root.acquireExclusive();
            return frame;
        }

        mLeaf = null;

        while (true) {
            Node node = frame.acquireExclusive();
            TreeCursorFrame parent = frame.pop();

            if (parent == null) {
                // Usually the root frame refers to the root node, but it
                // can be wrong if the tree height is changing.
                if (node != root) {
                    node.releaseExclusive();
                    root.acquireExclusive();
                }
                return frame;
            }

            node.releaseExclusive();
            frame = parent;
        }
    }

    int height() {
        int height = 0;
        TreeCursorFrame frame = mLeaf;
        while (frame != null) {
            height++;
            frame = frame.mParentFrame;
        }
        return height;
    }

    /**
     * Verifies from the current node to the last.
     *
     * @return false if should stop
     */
    boolean verify(final int height, VerificationObserver observer) throws IOException {
        if (height > 0) {
            final Node[] stack = new Node[height];

            while (key() != null) {
                verifyFrames(height, stack, mLeaf, observer);
                // Move to next node by first setting current node position higher
                // than possible.
                mLeaf.mNodePos = Integer.MAX_VALUE - 1;
                next();
            }
        }

        return true;
    }

    private boolean verifyFrames(int level, Node[] stack, TreeCursorFrame frame,
                                 VerificationObserver observer)
    {
        TreeCursorFrame parentFrame = frame.mParentFrame;

        if (parentFrame != null) {
            Node parentNode = parentFrame.mNode;
            int parentLevel = level - 1;
            if (parentLevel > 0 && stack[parentLevel] != parentNode) {
                parentNode = parentFrame.acquireShared();
                parentNode.releaseShared();
                if (stack[parentLevel] != parentNode) {
                    stack[parentLevel] = parentNode;
                    if (!verifyFrames(parentLevel, stack, parentFrame, observer)) {
                        return false;
                    }
                }
            }

            // Verify child node keys are lower/higher than parent node.

            parentNode = parentFrame.acquireShared();
            Node childNode = frame.acquireShared();

            int parentPos = parentFrame.mNodePos;

            int childPos;
            boolean left;
            if (parentPos >= parentNode.highestInternalPos()) {
                // Verify lowest child key is greater than or equal to parent key.
                parentPos = parentNode.highestKeyPos();
                childPos = 0;
                left = false;
            } else {
                // Verify highest child key is lower than parent key.
                childPos = childNode.highestKeyPos();
                left = true;
            }

            byte[] parentKey = parentNode.retrieveKey(parentPos);
            byte[] childKey = childNode.retrieveKey(childPos);
            long childId = childNode.mId;

            childNode.releaseShared();
            parentNode.releaseShared();

            int compare = Utils.compareKeys(childKey, parentKey);

            if (left) {
                if (compare >= 0) {
                    if (!observer.indexNodeFailed
                        (childId, level, "Child keys are not less than parent key"))
                    {
                        return false;
                    }
                }
            } else if (compare < 0) {
                if (!observer.indexNodeFailed
                    (childId, level, "Child keys are not greater than or equal to parent key"))
                {
                    return false;
                }
            }
        }

        return frame.acquireShared().verifyTreeNode(level, observer);
    }

    /**
     * Checks that leaf is defined and returns it.
     */
    private TreeCursorFrame leaf() {
        TreeCursorFrame leaf = mLeaf;
        if (leaf == null) {
            throw new IllegalStateException("Cursor position is undefined");
        }
        return leaf;
    }

    /**
     * Latches and returns leaf frame, which might be split.
     */
    private TreeCursorFrame leafExclusive() {
        TreeCursorFrame leaf = leaf();
        leaf.acquireExclusive();
        return leaf;
    }

    /**
     * Latches and returns leaf frame, not split.
     */
    private TreeCursorFrame leafExclusiveNotSplit() throws IOException {
        TreeCursorFrame leaf = leaf();
        Node node = leaf.acquireExclusive();
        if (node.mSplit != null) {
            finishSplit(leaf, node);
        }
        return leaf;
    }

    /**
     * Latches and returns leaf frame, not split.
     */
    private TreeCursorFrame leafSharedNotSplit() throws IOException {
        TreeCursorFrame leaf = leaf();
        Node node = leaf.acquireShared();
        if (node.mSplit != null) {
            doSplit: {
                if (!node.tryUpgrade()) {
                    node.releaseShared();
                    node = leaf.acquireExclusive();
                    if (node.mSplit == null) {
                        break doSplit;
                    }
                }
                node = finishSplit(leaf, node);
            }
            node.downgrade();
        }
        return leaf;
    }

    /**
     * Called with exclusive frame latch held, which is retained. Leaf frame is
     * dirtied, any split is finished, and the same applies to all parent
     * nodes. Caller must hold shared commit lock, to prevent deadlock. Node
     * latch is released if an exception is thrown.
     *
     * @return replacement node, still latched
     */
    private Node notSplitDirty(final TreeCursorFrame frame) throws IOException {
        Node node = frame.mNode;

        if (node.mSplit != null) {
            // Already dirty, but finish the split.
            return finishSplit(frame, node);
        }

        Database db = mTree.mDatabase;
        if (!db.shouldMarkDirty(node)) {
            return node;
        }

        TreeCursorFrame parentFrame = frame.mParentFrame;
        if (parentFrame == null) {
            try {
                db.doMarkDirty(mTree, node);
                return node;
            } catch (Throwable e) {
                node.releaseExclusive();
                throw Utils.rethrow(e);
            }
        }

        // Make sure the parent is not split and dirty too.
        Node parentNode;
        doParent: {
            parentNode = parentFrame.tryAcquireExclusive();
            if (parentNode == null) {
                node.releaseExclusive();
                parentFrame.acquireExclusive();
            } else if (parentNode.mSplit != null || db.shouldMarkDirty(parentNode)) {
                node.releaseExclusive();
            } else {
                break doParent;
            }
            parentNode = notSplitDirty(parentFrame);
            node = frame.acquireExclusive();
        }

        while (node.mSplit != null) {
            // Already dirty now, but finish the split. Since parent latch is
            // already held, no need to call into the regular finishSplit
            // method. It would release latches and recheck everything.
            try {
                parentNode.insertSplitChildRef(mTree, parentFrame.mNodePos, node);
            } catch (Throwable e) {
                parentNode.releaseExclusive();
                node.releaseExclusive();
                throw Utils.rethrow(e);
            }
            if (parentNode.mSplit != null) {
                parentNode = finishSplit(parentFrame, parentNode);
            }
            node = frame.acquireExclusive();
        }
        
        try {
            if (db.markDirty(mTree, node)) {
                parentNode.updateChildRefId(parentFrame.mNodePos, node.mId);
            }
            return node;
        } catch (Throwable e) {
            node.releaseExclusive();
            throw Utils.rethrow(e);
        } finally {
            parentNode.releaseExclusive();
        }
    }

    /**
     * Caller must hold exclusive latch, which is released by this method.
     */
    private void mergeLeaf(final TreeCursorFrame leaf, Node node) throws IOException {
        final TreeCursorFrame parentFrame = leaf.mParentFrame;
        node.releaseExclusive();

        if (parentFrame == null) {
            // Root node cannot merge into anything.
            return;
        }

        Node parentNode = parentFrame.acquireExclusive();

        Node leftNode, rightNode;
        int nodeAvail;
        while (true) {
            if (parentNode.mSplit != null) {
                parentNode = finishSplit(parentFrame, parentNode);
            }

            if (parentNode.numKeys() <= 0) {
                parentNode.releaseExclusive();
                return;
            }

            // Latch leaf and siblings in a strict left-to-right order to avoid deadlock.
            int pos = parentFrame.mNodePos;
            if (pos == 0) {
                leftNode = null;
            } else {
                leftNode = latchChild(parentNode, pos - 2, false);
                if (leftNode.mSplit != null) {
                    // Finish sibling split.
                    try {
                        parentNode.insertSplitChildRef(mTree, pos - 2, leftNode);
                        continue;
                    } catch (Throwable e) {
                        leftNode.releaseExclusive();
                        parentNode.releaseExclusive();
                        throw Utils.rethrow(e);
                    }
                }
            }

            node = leaf.acquireExclusive();

            // Double check that node should still merge.
            if (!node.shouldMerge(nodeAvail = node.availableLeafBytes())) {
                if (leftNode != null) {
                    leftNode.releaseExclusive();
                }
                node.releaseExclusive();
                parentNode.releaseExclusive();
                return;
            }

            if (pos >= parentNode.highestInternalPos()) {
                rightNode = null;
            } else {
                try {
                    rightNode = latchChild(parentNode, pos + 2, false);
                } catch (Throwable e) {
                    if (leftNode != null) {
                        leftNode.releaseExclusive();
                    }
                    node.releaseExclusive();
                    throw Utils.rethrow(e);
                }

                if (rightNode.mSplit != null) {
                    // Finish sibling split.
                    if (leftNode != null) {
                        leftNode.releaseExclusive();
                    }
                    node.releaseExclusive();
                    try {
                        parentNode.insertSplitChildRef(mTree, pos + 2, rightNode);
                        continue;
                    } catch (Throwable e) {
                        rightNode.releaseExclusive();
                        parentNode.releaseExclusive();
                        throw Utils.rethrow(e);
                    }
                }
            }

            break;
        }

        // Select a left and right pair, and then don't operate directly on the
        // original node and leaf parameters afterwards. The original node ends
        // up being referenced as a left or right member of the pair.

        int leftAvail = leftNode == null ? -1 : leftNode.availableLeafBytes();
        int rightAvail = rightNode == null ? -1 : rightNode.availableLeafBytes();

        // Choose adjacent node pair which has the most available space. If
        // only a rebalance can be performed on the pair, operating on
        // underutilized nodes continues them on a path to deletion.

        int leftPos;
        if (leftAvail < rightAvail) {
            if (leftNode != null) {
                leftNode.releaseExclusive();
            }
            leftPos = parentFrame.mNodePos;
            leftNode = node;
            leftAvail = nodeAvail;
        } else {
            if (rightNode != null) {
                rightNode.releaseExclusive();
            }
            leftPos = parentFrame.mNodePos - 2;
            rightNode = node;
            rightAvail = nodeAvail;
        }

        // Left node must always be marked dirty. Parent is already expected to be dirty.
        try {
            if (mTree.markDirty(leftNode)) {
                parentNode.updateChildRefId(leftPos, leftNode.mId);
            }
        } catch (Throwable e) {
            leftNode.releaseExclusive();
            rightNode.releaseExclusive();
            parentNode.releaseExclusive();
            throw Utils.rethrow(e);
        }

        // Determine if both nodes can fit in one node. If so, migrate and
        // delete the right node.
        int remaining = leftAvail + rightAvail - node.mPage.length + Node.TN_HEADER_SIZE;

        if (remaining >= 0) {
            // Migrate the entire contents of the right node into the left
            // node, and then delete the right node.
            try {
                Node.moveLeafToLeftAndDelete(mTree, leftNode, rightNode);
            } catch (Throwable e) {
                leftNode.releaseExclusive();
                parentNode.releaseExclusive();
                throw Utils.rethrow(e);
            }
            rightNode = null;
            parentNode.deleteChildRef(leftPos + 2);
        } /*else { // TODO: testing
            // Rebalance nodes, but don't delete anything. Right node must be dirtied too.

            // TODO: IOException; release latches
            if (mTree.markDirty(rightNode)) {
                parentNode.updateChildRefId(leftPos + 2, rightNode.mId);
            }

            // TODO: testing
            if (leftNode.numKeys() == 1 || rightNode.numKeys() == 1) {
                System.out.println("left avail: " + leftAvail + ", right avail: " + rightAvail +
                                   ", left pos: " + leftPos);
                throw new Error("MUST REBALANCE: " + leftNode.numKeys() + ", " + 
                                rightNode.numKeys());
            }

            /*
            System.out.println("left avail: " + leftAvail + ", right avail: " + rightAvail +
                               ", left pos: " + leftPos + ", mode: " + migrateMode);
            * /

            if (leftNode == node) {
                // Rebalance towards left node, which is smaller.
                // TODO
            } else {
                // Rebalance towards right node, which is smaller.
                // TODO
            }
            }*/

        mergeInternal(parentFrame, parentNode, leftNode, rightNode);
    }

    /**
     * Caller must hold exclusive latch, which is released by this method.
     */
    private void mergeInternal(TreeCursorFrame frame, Node node,
                               Node leftChildNode, Node rightChildNode)
        throws IOException
    {
        up: {
            if (node.shouldInternalMerge()) {
                if (node.numKeys() > 0 || node != mTree.mRoot) {
                    // Continue merging up the tree.
                    break up;
                }

                // Delete the empty root node, eliminating a tree level.

                // Note: By retaining child latches (although right might have
                // been deleted), another thread is prevented from splitting
                // the lone child. The lone child will become the new root.
                // TODO: Investigate if this creates deadlocks.
                try {
                    node.rootDelete(mTree);
                } catch (Throwable e) {
                    if (rightChildNode != null) {
                        rightChildNode.releaseExclusive();
                    }
                    leftChildNode.releaseExclusive();
                    node.releaseExclusive();
                    throw Utils.rethrow(e);
                }
            }

            if (rightChildNode != null) {
                rightChildNode.releaseExclusive();
            }
            leftChildNode.releaseExclusive();
            node.releaseExclusive();
            return;
        }

        if (rightChildNode != null) {
            rightChildNode.releaseExclusive();
        }
        leftChildNode.releaseExclusive();

        // At this point, only one node latch is held, and it should merge with
        // a sibling node. Node is guaranteed to be a internal node.

        TreeCursorFrame parentFrame = frame.mParentFrame;
        node.releaseExclusive();

        if (parentFrame == null) {
            // Root node cannot merge into anything.
            return;
        }

        Node parentNode = parentFrame.acquireExclusive();
        if (parentNode.isLeaf()) {
            throw new AssertionError("Parent node is a leaf");
        }

        Node leftNode, rightNode;
        int nodeAvail;
        while (true) {
            if (parentNode.mSplit != null) {
                parentNode = finishSplit(parentFrame, parentNode);
            }

            if (parentNode.numKeys() <= 0) {
                parentNode.releaseExclusive();
                return;
            }

            // Latch node and siblings in a strict left-to-right order to avoid deadlock.
            int pos = parentFrame.mNodePos;
            if (pos == 0) {
                leftNode = null;
            } else {
                leftNode = latchChild(parentNode, pos - 2, false);
                if (leftNode.mSplit != null) {
                    // Finish sibling split.
                    try {
                        parentNode.insertSplitChildRef(mTree, pos - 2, leftNode);
                        continue;
                    } catch (Throwable e) {
                        leftNode.releaseExclusive();
                        parentNode.releaseExclusive();
                        throw Utils.rethrow(e);
                    }
                }
            }

            node = frame.acquireExclusive();

            // Double check that node should still merge.
            if (!node.shouldMerge(nodeAvail = node.availableInternalBytes())) {
                if (leftNode != null) {
                    leftNode.releaseExclusive();
                }
                node.releaseExclusive();
                parentNode.releaseExclusive();
                return;
            }

            if (pos >= parentNode.highestInternalPos()) {
                rightNode = null;
            } else {
                try {
                    rightNode = latchChild(parentNode, pos + 2, false);
                } catch (Throwable e) {
                    if (leftNode != null) {
                        leftNode.releaseExclusive();
                    }
                    node.releaseExclusive();
                    throw Utils.rethrow(e);
                }

                if (rightNode.mSplit != null) {
                    // Finish sibling split.
                    if (leftNode != null) {
                        leftNode.releaseExclusive();
                    }
                    node.releaseExclusive();
                    try {
                        parentNode.insertSplitChildRef(mTree, pos + 2, rightNode);
                        continue;
                    } catch (Throwable e) {
                        rightNode.releaseExclusive();
                        parentNode.releaseExclusive();
                        throw Utils.rethrow(e);
                    }
                }
            }

            break;
        }

        // Select a left and right pair, and then don't operate directly on the
        // original node and frame parameters afterwards. The original node
        // ends up being referenced as a left or right member of the pair.

        int leftAvail = leftNode == null ? -1 : leftNode.availableInternalBytes();
        int rightAvail = rightNode == null ? -1 : rightNode.availableInternalBytes();

        // Choose adjacent node pair which has the most available space. If
        // only a rebalance can be performed on the pair, operating on
        // underutilized nodes continues them on a path to deletion.

        int leftPos;
        if (leftAvail < rightAvail) {
            if (leftNode != null) {
                leftNode.releaseExclusive();
            }
            leftPos = parentFrame.mNodePos;
            leftNode = node;
            leftAvail = nodeAvail;
        } else {
            if (rightNode != null) {
                rightNode.releaseExclusive();
            }
            leftPos = parentFrame.mNodePos - 2;
            rightNode = node;
            rightAvail = nodeAvail;
        }

        if (leftNode == null || rightNode == null) {
            throw new AssertionError("No sibling node to merge into");
        }

        // Left node must always be marked dirty. Parent is already expected to be dirty.
        try {
            if (mTree.markDirty(leftNode)) {
                parentNode.updateChildRefId(leftPos, leftNode.mId);
            }
        } catch (Throwable e) {
            leftNode.releaseExclusive();
            rightNode.releaseExclusive();
            parentNode.releaseExclusive();
            throw Utils.rethrow(e);
        }

        // Determine if both nodes plus parent key can fit in one node. If so,
        // migrate and delete the right node.
        byte[] parentPage = parentNode.mPage;
        int parentEntryLoc = Utils.readUnsignedShortLE
            (parentPage, parentNode.mSearchVecStart + leftPos);
        int parentEntryLen = Node.internalEntryLengthAtLoc(parentPage, parentEntryLoc);
        int remaining = leftAvail - parentEntryLen
            + rightAvail - parentPage.length + (Node.TN_HEADER_SIZE - 2);

        if (remaining >= 0) {
            // Migrate the entire contents of the right node into the left
            // node, and then delete the right node.
            try {
                Node.moveInternalToLeftAndDelete
                    (mTree, leftNode, rightNode, parentPage, parentEntryLoc, parentEntryLen);
            } catch (Throwable e) {
                leftNode.releaseExclusive();
                parentNode.releaseExclusive();
                throw Utils.rethrow(e);
            }
            rightNode = null;
            parentNode.deleteChildRef(leftPos + 2);
        } /*else { // TODO: testing
            // Rebalance nodes, but don't delete anything. Right node must be dirtied too.

            // TODO: IOException; release latches
            if (mTree.markDirty(rightNode)) {
                parentNode.updateChildRefId(leftPos + 2, rightNode.mId);
            }

            // TODO: testing
            if (leftNode.numKeys() == 1 || rightNode.numKeys() == 1) {
                System.out.println("left avail: " + leftAvail + ", right avail: " + rightAvail +
                                   ", left pos: " + leftPos);
                throw new Error("MUST REBALANCE: " + leftNode.numKeys() + ", " + 
                                rightNode.numKeys());
            }

            /*
            System.out.println("left avail: " + leftAvail + ", right avail: " + rightAvail +
                               ", left pos: " + leftPos + ", mode: " + migrateMode);
            * /

            if (leftNode == node) {
                // Rebalance towards left node, which is smaller.
                // TODO
            } else {
                // Rebalance towards right node, which is smaller.
                // TODO
            }
            }*/

        // Tail call. I could just loop here, but this is simpler.
        mergeInternal(parentFrame, parentNode, leftNode, rightNode);
    }

    /**
     * Caller must hold exclusive latch and it must verify that node has
     * split. Node latch is released if an exception is thrown.
     *
     * @return replacement node, still latched
     */
    private Node finishSplit(final TreeCursorFrame frame, Node node) throws IOException {
        Tree tree = mTree;

        while (node == tree.mRoot) {
            Node stub;
            if (tree.hasStub()) {
                // Don't wait for stub latch, to avoid deadlock. The stub stack
                // is latched up upwards here, but downwards by cursors.
                stub = tree.tryPopStub();
                if (stub == null) {
                    // Latch not immediately available, so release root latch
                    // and try again. This implementation spins, but root
                    // splits are expected to be infrequent.
                    Thread waiter = node.getFirstQueuedThread();
                    node.releaseExclusive();
                    do {
                        Thread.yield();
                    } while (waiter != null && node.getFirstQueuedThread() == waiter);
                    node = frame.acquireExclusive();
                    if (node.mSplit == null) {
                        return node;
                    }
                    continue;
                }
                stub = Tree.validateStub(stub);
            } else {
                stub = null;
            }
            try {
                node.finishSplitRoot(tree, stub);
                return node;
            } catch (Throwable e) {
                node.releaseExclusive();
                throw Utils.rethrow(e);
            }
        }

        final TreeCursorFrame parentFrame = frame.mParentFrame;
        node.releaseExclusive();

        Node parentNode = parentFrame.acquireExclusive();
        while (true) {
            if (parentNode.mSplit != null) {
                parentNode = finishSplit(parentFrame, parentNode);
            }
            node = frame.acquireExclusive();
            if (node.mSplit == null) {
                parentNode.releaseExclusive();
                return node;
            }
            try {
                parentNode.insertSplitChildRef(tree, parentFrame.mNodePos, node);
            } catch (Throwable e) {
                node.releaseExclusive();
                parentNode.releaseExclusive();
                throw Utils.rethrow(e);
            }
        }
    }

    /**
     * With parent held exclusively, returns child with exclusive latch held.
     * If an exception is thrown, parent and child latches are always released.
     *
     * @return child node, possibly split
     */
    private Node latchChild(Node parent, int childPos, boolean releaseParent)
        throws IOException
    {
        Node childNode = parent.mChildNodes[childPos >> 1];
        long childId = parent.retrieveChildRefId(childPos);

        if (childNode != null && childId == childNode.mId) {
            childNode.acquireExclusive();
            // Need to check again in case evict snuck in.
            if (childId != childNode.mId) {
                childNode.releaseExclusive();
            } else {
                if (releaseParent) {
                    parent.releaseExclusive();
                }
                mTree.mDatabase.used(childNode);
                return childNode;
            }
        }

        return parent.loadChild(mTree.mDatabase, childPos, childId, releaseParent);
    }

    /**
     * With parent held exclusively, returns child with exclusive latch held.
     * If an exception is thrown, parent and child latches are always released.
     * If null is returned, child is not loaded and parent latch is still held.
     *
     * Note: Unlike the latchChild method, this method never identifies the
     * child as having been used. It is just as likely to be evicted as before.
     *
     * @return null or child node, possibly split
     */
    /*
    private Node tryLatchChild(Node parent, int childPos, boolean releaseParent) {
        Node childNode = parent.mChildNodes[childPos >> 1];

        if (childNode != null) {
            long childId = parent.retrieveChildRefId(childPos);
            if (childId == childNode.mId) {
                childNode.acquireExclusive();
                // Need to check again in case evict snuck in.
                if (childId != childNode.mId) {
                    childNode.releaseExclusive();
                } else {
                    if (releaseParent) {
                        parent.releaseExclusive();
                    }
                    return childNode;
                }
            }
            // Clear reference to evicted child.
            parent.mChildNodes[childPos >> 1] = null;
        }

        return null;
    }
    */
}
