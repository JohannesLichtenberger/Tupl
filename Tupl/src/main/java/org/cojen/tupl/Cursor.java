/*
 *  Copyright 2011 Brian S O'Neill
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

import java.util.ArrayDeque;

import java.util.concurrent.locks.Lock;

/**
 * Maintains a fixed logical position in the tree. Cursors must be {@link
 * #reset reset} when no longer needed to free up memory.
 *
 * @author Brian S O'Neill
 */
public final class Cursor {
    private final TreeNodeStore mStore;

    // Top stack frame for cursor, always a leaf.
    private CursorFrame mLeaf;

    Cursor(TreeNodeStore store) {
        mStore = store;
    }

    /**
     * Returns a copy of the key at the cursor's position, never null.
     *
     * @throws IllegalStateException if position is undefined at invocation time
     */
    public synchronized byte[] getKey() throws IOException {
        CursorFrame leaf = leafSharedNotSplit();
        TreeNode node = leaf.mNode;
        int pos = leaf.mNodePos;
        byte[] key = pos < 0 ? (leaf.mNotFoundKey.clone()) : node.retrieveLeafKey(pos);
        node.releaseShared();
        return key;
    }

    /**
     * Returns a copy of the value at the cursor's position. Null is returned
     * if entry doesn't exist.
     *
     * @throws IllegalStateException if position is undefined at invocation time
     */
    public synchronized byte[] getValue() throws IOException {
        CursorFrame leaf = leafSharedNotSplit();
        TreeNode node = leaf.mNode;
        int pos = leaf.mNodePos;
        byte[] value = pos < 0 ? null : node.retrieveLeafValue(pos);
        node.releaseShared();
        return value;
    }

    /**
     * Returns a copy of the key and value at the cursor's position. False is
     * returned if entry doesn't exist.
     *
     * @param entry entry to fill in; pass null to just check if entry exists
     * @throws IllegalStateException if position is undefined at invocation time
     */
    public synchronized boolean getEntry(Entry entry) throws IOException {
        CursorFrame leaf = leafSharedNotSplit();
        TreeNode node = leaf.mNode;
        int pos = leaf.mNodePos;
        if (pos < 0) {
            if (entry != null) {
                entry.key = leaf.mNotFoundKey.clone();
                entry.value = null;
            }
            node.releaseShared();
            return false;
        } else {
            if (entry != null) {
                node.retrieveLeafEntry(pos, entry);
            }
            node.releaseShared();
            return true;
        }
    }

    /**
     * Move the cursor to find the first available entry, unless none exists.
     *
     * @return false if no entries exist and position is now undefined
     */
    public synchronized boolean first() throws IOException {
        TreeNode node = mStore.root();
        CursorFrame frame = resetForFind(node);

        if (!node.hasKeys()) {
            node.releaseExclusive();
            return false;
        }

        return toFirst(node, frame);
    }

    /**
     * Move the cursor to the first subtree entry. Caller must be synchronized.
     *
     * @param node latched node
     * @param frame frame to bind node to
     */
    private boolean toFirst(TreeNode node, CursorFrame frame) throws IOException {
        while (true) {
            frame.bind(node, 0);

            if (node.isLeaf()) {
                node.releaseExclusive();
                mLeaf = frame;
                return true;
            }

            if (node.mSplit != null) {
                node = node.mSplit.latchLeft(mStore, node);
            }

            node = latchChild(node, 0);
            frame = new CursorFrame(frame);
        }
    }

    /**
     * Move the cursor to find the last available entry, unless none exists.
     *
     * @return false if no entries exist and position is now undefined
     */
    public synchronized boolean last() throws IOException {
        TreeNode node = mStore.root();
        CursorFrame frame = resetForFind(node);

        if (!node.hasKeys()) {
            node.releaseExclusive();
            return false;
        }

        return toLast(node, frame);
    }

    /**
     * Move the cursor to the last subtree entry. Caller must be synchronized.
     *
     * @param node latched node
     * @param frame frame to bind node to
     */
    private boolean toLast(TreeNode node, CursorFrame frame) throws IOException {
        while (true) {
            if (node.isLeaf()) {
                int pos;
                if (node.mSplit == null) {
                    pos = node.highestLeafPos();
                } else {
                    pos = node.mSplit.highestLeafPos(mStore, node);
                }
                frame.bind(node, pos);
                node.releaseExclusive();
                mLeaf = frame;
                return true;
            }

            Split split = node.mSplit;
            if (split == null) {
                int childPos = node.highestInternalPos();
                frame.bind(node, childPos);
                node = latchChild(node, childPos);
            } else {
                // Follow highest position of split, binding this frame to the
                // unsplit node as if it had not split. The binding will be
                // corrected when split is finished.

                final TreeNode sibling = split.latchSibling(mStore);

                final TreeNode left, right;
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

                node = latchChild(right, highestRightPos);
            }

            frame = new CursorFrame(frame);
        }
    }

    /**
     * Move the cursor by a relative amount of entries. Pass a positive amount
     * for forward movement, and pass a negative amount for reverse
     * movement. The actual movement amount can be less than the requested
     * amount if the start or end is reached. After this happens, the position
     * is undefined.
     *
     * @return actual amount moved; if less, position is now undefined
     * @throws IllegalStateException if position is undefined at invocation time
     */
    public synchronized long move(long amount) throws IOException {
        // FIXME
        throw null;
    }

    /**
     * Advances to the cursor to the next available entry, unless none
     * exists. Equivalent to:
     *
     * <pre>
     * return cursor.move(1) != 0;</pre>
     *
     * @return false if no next entry and position is now undefined
     * @throws IllegalStateException if position is undefined at invocation time
     */
    public synchronized boolean next() throws IOException {
        // TODO: call move, and no extra synchronization: return move(1) != 0;
        return next(leafExclusiveNotSplit());
    }

    /**
     * @param frame leaf frame, not split, with exclusive latch
     */
    // Caller must be synchronized.
    private boolean next(CursorFrame frame) throws IOException {
        TreeNode node = frame.mNode;
        int pos = frame.mNodePos;
        if (pos < 0) {
            frame.mNotFoundKey = null;
            pos = (~pos) - 2;
        }

        if (pos < node.highestLeafPos()) {
            frame.mNodePos = pos + 2;
            node.releaseExclusive();
            return true;
        }

        while (true) {
            frame = frame.pop();
            node.releaseExclusive();
            if (frame == null) {
                mLeaf = null;
                return false;
            }

            node = frame.acquireExclusiveUnfair();

            if (node.mSplit != null) {
                node = finishSplit(frame, node, mStore, true);
            }

            pos = frame.mNodePos;

            if (pos < node.highestInternalPos()) {
                pos += 2;
                frame.mNodePos = pos;
                return toFirst(latchChild(node, pos), new CursorFrame(frame));
            }
        }
    }

    /**
     * Advances to the cursor to the previous available entry, unless none
     * exists. Equivalent to:
     *
     * <pre>
     * return cursor.move(-1) != 0;</pre>
     *
     * @return false if no previous entry and position is now undefined
     * @throws IllegalStateException if position is undefined at invocation time
     */
    public synchronized boolean previous() throws IOException {
        // TODO: call move, and no extra synchronization: return move(-1) != 0;
        return previous(leafExclusiveNotSplit());
    }

    /**
     * @param frame leaf frame, not split, with exclusive latch
     */
    // Caller must be synchronized.
    private boolean previous(CursorFrame frame) throws IOException {
        TreeNode node = frame.mNode;
        int pos = frame.mNodePos;
        if (pos < 0) {
            frame.mNotFoundKey = null;
            pos = ~pos;
        }

        if ((pos -= 2) >= 0) {
            frame.mNodePos = pos;
            node.releaseExclusive();
            return true;
        }

        while (true) {
            frame = frame.pop();
            node.releaseExclusive();
            if (frame == null) {
                mLeaf = null;
                return false;
            }

            node = frame.acquireExclusiveUnfair();

            if (node.mSplit != null) {
                node = finishSplit(frame, node, mStore, true);
            }

            pos = frame.mNodePos;

            if (pos > 0) {
                pos -= 2;
                frame.mNodePos = pos;
                return toLast(latchChild(node, pos), new CursorFrame(frame));
            }
        }
    }

    /**
     * Move the cursor to find the given key, returning true if a matching
     * entry exists. If false is returned, a reference to the key (uncopied) is
     * retained. The key reference is released when the cursor position changes
     * or a matching entry is created.
     *
     * @return false if entry not found
     * @throws NullPointerException if key is null
     */
    public synchronized boolean find(byte[] key) throws IOException {
        // Prevent commits due to avoid deadlocks. If a child node needs to be
        // loaded, it may evict another node. Eviction acquires the commit lock
        // while parent latch is held, which is the opposite order used by the
        // commit method.
        // FIXME: Does the evict method really need the lock?
        //final Lock sharedCommitLock = mStore.sharedCommitLock();
        //sharedCommitLock.lock();
        //try {
            return find(key, false);
        //} finally {
            //sharedCommitLock.unlock();
        //}
    }

    // Caller must be synchronized.
    private boolean find(byte[] key, boolean retainLatch) throws IOException {
        TreeNode node = mStore.root();
        CursorFrame frame = resetForFind(node);

        while (true) {
            if (node.isLeaf()) {
                int pos;
                if (node.mSplit == null) {
                    pos = node.binarySearchLeaf(key);
                } else {
                    pos = node.mSplit.binarySearchLeaf(mStore, node, key);
                }
                frame.bind(node, pos);
                if (pos < 0) {
                    frame.mNotFoundKey = key;
                }
                if (!retainLatch) {
                    node.releaseExclusive();
                }
                mLeaf = frame;
                return pos >= 0;
            }

            Split split = node.mSplit;
            if (split == null) {
                int childPos = TreeNode.internalPos(node.binarySearchInternal(key));
                frame.bind(node, childPos);
                node = latchChild(node, childPos);
            } else {
                // Follow search into split, binding this frame to the unsplit
                // node as if it had not split. The binding will be corrected
                // when split is finished.

                final TreeNode sibling = split.latchSibling(mStore);

                final TreeNode left, right;
                if (split.mSplitRight) {
                    left = node;
                    right = sibling;
                } else {
                    left = sibling;
                    right = node;
                }

                final TreeNode selected;
                final int selectedPos;

                if (split.compare(key) < 0) {
                    selected = left;
                    selectedPos = TreeNode.internalPos(left.binarySearchInternal(key));
                    frame.bind(node, selectedPos);
                    right.releaseExclusive();
                } else {
                    selected = right;
                    selectedPos = TreeNode.internalPos(right.binarySearchInternal(key));
                    frame.bind(node, left.highestInternalPos() + 2 + selectedPos);
                    left.releaseExclusive();
                }

                node = latchChild(selected, selectedPos);
            }

            frame = new CursorFrame(frame);
        }
    }

    /**
     * Move the cursor to find the first available entry greater than or equal
     * to the given key. Equivalent to:
     *
     * <pre>
     * return cursor.find(key) ? true : cursor.next();</pre>
     *
     * @return false if entry not found and position is now undefined
     * @throws NullPointerException if key is null
     */
    public synchronized boolean findGe(byte[] key) throws IOException {
        if (find(key, true)) {
            mLeaf.mNode.releaseExclusive();
            return true;
        } else {
            return next(mLeaf);
        }
    }

    /**
     * Move the cursor to find the first available entry greater than the given
     * key. Equivalent to:
     *
     * <pre>
     * cursor.find(key); return cursor.next();</pre>
     *
     * @return false if entry not found and position is now undefined
     * @throws NullPointerException if key is null
     */
    public synchronized boolean findGt(byte[] key) throws IOException {
        find(key, true);
        return next(mLeaf);
    }

    /**
     * Move the cursor to find the first available entry less than or equal to
     * the given key. Equivalent to:
     *
     * <pre>
     * return cursor.find(key) ? true : cursor.previous();</pre>
     *
     * @return false if entry not found and position is now undefined
     * @throws NullPointerException if key is null
     */
    public synchronized boolean findLe(byte[] key) throws IOException {
        if (find(key, true)) {
            mLeaf.mNode.releaseExclusive();
            return true;
        } else {
            return previous(mLeaf);
        }
    }

    /**
     * Move the cursor to find the first available entry less than the given
     * key. Equivalent to:
     *
     * <pre>
     * cursor.find(key); return cursor.previous();</pre>
     *
     * @return false if entry not found and position is now undefined
     * @throws NullPointerException if key is null
     */
    public synchronized boolean findLt(byte[] key) throws IOException {
        find(key, true);
        return previous(mLeaf);
    }

    /**
     * Optimized version of the regular find method, useful for operating over
     * a range of contiguous keys. Find the next expected key, returning true
     * if a matching entry exists anywhere. If false is returned, a reference
     * to the key (uncopied) is retained. The key reference is released when
     * the cursor position changes or a matching entry is created.
     *
     * @return false if entry not found
     * @throws NullPointerException if key is null
     */
    public synchronized boolean findNext(byte[] key) throws IOException {
        // FIXME
        throw null;
    }

    /**
     * Optimized version of the regular find method, useful for operating over
     * a range of contiguous keys. Find the previous expected key, returning
     * true if a matching entry exists anywhere. If false is returned, a
     * reference to the key (uncopied) is retained. The key reference is
     * released when the cursor position changes or a matching entry is
     * created.
     *
     * @return false if entry not found
     * @throws NullPointerException if key is null
     */
    public synchronized boolean findPrevious(byte[] key) throws IOException {
        // FIXME
        throw null;
    }

    /**
     * Store a value into the current entry, leaving the position unchanged. An
     * entry may be inserted, updated or deleted by this method. A null value
     * deletes the entry.
     *
     * @throws IllegalStateException if position is undefined at invocation time
     */
    public synchronized void store(byte[] value) throws IOException {
        final Lock sharedCommitLock = mStore.sharedCommitLock();
        sharedCommitLock.lock();
        try {
            final CursorFrame leaf = leafExclusiveNotSplitDirty();
            TreeNode node = leaf.mNode;
            final int pos = leaf.mNodePos;

            if (pos >= 0) {
                // FIXME
                throw new IOException("Only insert is supported");
            }

            byte[] key = leaf.mNotFoundKey;
            if (key == null) {
                throw new AssertionError();
            }

            // FIXME: Make sure that mNodePos is updated for all bound cursors
            // after entries are deleted.

            // FIXME: If mNodePos switches from positive to negative after
            // delete, create a copy of deleted key.

            int newPos = ~pos;
            node.insertLeafEntry(mStore, newPos, key, value);

            leaf.mNotFoundKey = null;
            leaf.mNodePos = newPos;

            // Fix all cursors in this node.
            CursorFrame frame = node.mLastCursorFrame;
            do {
                if (frame == leaf) {
                    // Don't need to fix self.
                    continue;
                }

                int framePos = frame.mNodePos;

                if (framePos == pos) {
                    // Other cursor is at same not-found position as this one
                    // was. If keys are the same, then other cursor switches to
                    // a found state as well. If key is greater, then position
                    // needs to be updated.

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

            if (node.mSplit == null) {
                node.releaseExclusive();
            } else {
                finishSplit(leaf, node, mStore, false);
            }
        } finally {
            sharedCommitLock.unlock();
        }
    }

    // TODO: Define View as primary interface, not Tree. View supports ranges,
    // count, deleteAll.

    /**
     * Returns a new independent cursor which exactly matches the state of this
     * one. The original and copied cursor can be acted upon without affecting
     * each other's state.
     */
    public Cursor copy() {
        Cursor copy = new Cursor(mStore);

        CursorFrame frame;
        synchronized (this) {
            frame = mLeaf;
        }

        if (frame == null) {
            return copy;
        }

        CursorFrame frameCopy = new CursorFrame();
        frame.copyInto(frameCopy);

        synchronized (copy) {
            copy.mLeaf = frameCopy;
        }

        return copy;
    }

    /**
     * Resets the cursor position to be undefined.
     */
    public void reset() {
        CursorFrame frame;
        synchronized (this) {
            frame = mLeaf;
            if (frame == null) {
                return;
            }
            mLeaf = null;
        }
        CursorFrame.popAll(frame);
    }

    /**
     * Resets all frames and latches root node, exclusively. Caller must be
     * synchronized. Although the normal reset could be called directly, this
     * variant avoids unlatching the root node, since a find operation would
     * immediately relatch it.
     *
     * @return new or recycled frame
     */
    private CursorFrame resetForFind(TreeNode root) {
        CursorFrame frame = mLeaf;
        if (frame == null) {
            root.acquireExclusiveUnfair();
            return new CursorFrame();
        } else {
            mLeaf = null;
            while (true) {
                TreeNode node = frame.acquireExclusiveUnfair();
                CursorFrame parent = frame.pop();
                if (parent != null) {
                    node.releaseExclusive();
                    frame = parent;
                } else {
                    // Usually the root frame refers to the root node, but it
                    // can be wrong if the tree height is changing.
                    if (node != root) {
                        node.releaseExclusive();
                        root.acquireExclusiveUnfair();
                    }
                    return frame;
                }
            }
        }
    }

    /**
     * Verifies that cursor state is correct by performing a find operation.
     *
     * @return false if unable to verify completely at this time
     */
    synchronized boolean verify() throws IOException, IllegalStateException {
        return verify(getKey());
    }

    /**
     * Verifies that cursor state is correct by performing a find operation.
     *
     * @return false if unable to verify completely at this time
     * @throws NullPointerException if key is null
     */
    synchronized boolean verify(byte[] key) throws IllegalStateException {
        ArrayDeque<CursorFrame> frames;
        {
            CursorFrame frame = mLeaf;
            if (frame == null) {
                return true;
            }
            frames = new ArrayDeque<CursorFrame>(10);
            do {
                frames.addFirst(frame);
                frame = frame.mParentFrame;
            } while (frame != null);
        }

        CursorFrame frame = frames.removeFirst();
        TreeNode node = frame.acquireSharedUnfair();

        if (node.mSplit != null) {
            // Cannot verify into split nodes.
            node.releaseShared();
            return false;
        }

        /* This check cannot be reliably performed, because the snapshot of
         * frames can be stale.
        if (node != mStore.root()) {
            node.releaseShared();
            throw new IllegalStateException("Bottom frame is not at root node");
        }
        */

        while (true) {
            if (node.isLeaf()) {
                int pos = node.binarySearchLeaf(key);

                try {
                    if (frame.mNodePos != pos) {
                        throw new IllegalStateException
                            ("Leaf frame position incorrect: " + frame.mNodePos + " != " + pos);
                    }

                    if (pos < 0) {
                        if (frame.mNotFoundKey == null) {
                            throw new IllegalStateException
                                ("Leaf frame key is not set; pos=" + pos);
                        }
                    } else if (frame.mNotFoundKey != null) {
                        throw new IllegalStateException
                            ("Leaf frame key should not be set; pos=" + pos);
                    }
                } finally {
                    node.releaseShared();
                }

                return true;
            }

            int childPos = TreeNode.internalPos(node.binarySearchInternal(key));

            CursorFrame next;
            try {
                if (frame.mNodePos != childPos) {
                    throw new IllegalStateException
                        ("Internal frame position incorrect: " +
                         frame.mNodePos + " != " + childPos + ", split: " + node.mSplit +
                         //", fpos: " + fpos +
                         //", opos: " + frame.mOpos +
                         //", searchKey: " + CursorTest.string(frame.mSearchKey) +
                         ", key: " + CursorTest.string(key));
                }

                if (frame.mNotFoundKey != null) {
                    throw new IllegalStateException("Internal frame key should not be set");
                }

                next = frames.pollFirst();

                if (next == null) {
                    throw new IllegalStateException("Top frame is not a leaf node");
                }

                next.acquireSharedUnfair();
            } finally {
                node.releaseShared();
            }

            frame = next;
            node = frame.mNode;

            if (node.mSplit != null) {
                // Cannot verify into split nodes.
                node.releaseShared();
                return false;
            }
        }
    }

    /**
     * Latches and returns leaf frame, not split. Caller must be synchronized.
     */
    private CursorFrame leafSharedNotSplit() throws IOException {
        CursorFrame leaf = mLeaf;
        if (leaf == null) {
            throw new IllegalStateException("Position is undefined");
        }

        TreeNode node = leaf.acquireSharedUnfair();

        if (node.mSplit == null) {
            return leaf;
        }

        node.releaseShared();
        node = leaf.acquireExclusiveUnfair();

        if (node.mSplit != null) {
            node = finishSplit(leaf, node, mStore, true);
        }

        node.downgrade();
        return leaf;
    }

    /**
     * Latches and returns leaf frame, not split. Caller must be synchronized.
     */
    private CursorFrame leafExclusiveNotSplit() throws IOException {
        CursorFrame leaf = mLeaf;
        if (leaf == null) {
            throw new IllegalStateException("Position is undefined");
        }

        TreeNode node = leaf.acquireExclusiveUnfair();

        if (node.mSplit != null) {
            node = finishSplit(leaf, node, mStore, true);
        }

        return leaf;
    }

    /**
     * Latches and returns leaf frame, not split. Leaf frame and all previous
     * frames are marked as dirty. Caller must be synchronized.
     */
    private CursorFrame leafExclusiveNotSplitDirty() throws IOException {
        CursorFrame leaf = mLeaf;
        if (leaf == null) {
            throw new IllegalStateException("Position is undefined");
        }
        leaf.acquireExclusiveUnfair();
        notSplitDirty(leaf, mStore);
        return leaf;
    }

    /**
     * Called with frame latch held, which is retained.
     */
    private static void notSplitDirty(final CursorFrame frame, TreeNodeStore store)
        throws IOException
    {
        TreeNode node = frame.mNode;

        if (node.mSplit != null) {
            // Already dirty, but finish the split.
            node = finishSplit(frame, node, store, true);
            return;
        }

        if (!store.shouldMarkDirty(node)) {
            return;
        }

        CursorFrame parentFrame = frame.mParentFrame;
        if (parentFrame == null) {
            store.doMarkDirty(node);
            return;
        }

        // Make sure the parent is not split and dirty too.
        node.releaseExclusive();
        parentFrame.acquireExclusiveUnfair();
        notSplitDirty(parentFrame, store);
        node = frame.acquireExclusiveUnfair();
        TreeNode parentNode = parentFrame.mNode;

        if (node.mSplit == null) {
            if (store.markDirty(node)) {
                parentNode.updateChildRefId(parentFrame.mNodePos, node.mId);
            }
            parentNode.releaseExclusive();
            return;
        }

        // Already dirty now, but finish the split. Since parent latch is
        // already held, no need to call into the regular finishSplit
        // method. It would release latches and recheck everything.
        parentNode.insertSplitChildRef(store, parentFrame.mNodePos, node);
        if (parentNode.mSplit == null) {
            parentNode.releaseExclusive();
        } else {
            finishSplit(parentFrame, parentNode, store, false);
        }
        frame.acquireExclusiveUnfair();
    }

    /**
     * Caller must hold exclusive latch and it must verify that node has split.
     *
     * @return replacement node or null if latch was not retained
     */
    private static TreeNode finishSplit(final CursorFrame frame,
                                        TreeNode node,
                                        TreeNodeStore store,
                                        boolean retainLatch)
        throws IOException
    {
        if (node == store.root()) {
            node.finishSplitRoot(store);
            if (retainLatch) {
                return node;
            }
            node.releaseExclusive();
            return null;
        }

        final CursorFrame parentFrame = frame.mParentFrame;
        node.releaseExclusive();
        TreeNode parentNode = parentFrame.acquireExclusiveUnfair();

        if (parentNode.mSplit != null) {
            parentNode = finishSplit(parentFrame, parentNode, store, true);
        }

        node = frame.acquireExclusiveUnfair();

        if (node.mSplit == null) {
            parentNode.releaseExclusive();
            if (retainLatch) {
                return node;
            }
            node.releaseExclusive();
        } else {
            parentNode.insertSplitChildRef(store, parentFrame.mNodePos, node);
            if (parentNode.mSplit == null) {
                parentNode.releaseExclusive();
            } else {
                finishSplit(parentFrame, parentNode, store, false);
            }
            if (retainLatch) {
                return frame.acquireExclusiveUnfair();
            }
        }

        return null;
    }

    /**
     * With parent held exclusively, returns child with exclusive latch held,
     * and parent latch is released.
     */
    private TreeNode latchChild(TreeNode parent, int childPos) throws IOException {
        TreeNode childNode = parent.mChildNodes[childPos >> 1];
        long childId = parent.retrieveChildRefId(childPos);

        check: if (childNode != null && childId == childNode.mId) {
            childNode.acquireExclusiveUnfair();

            // Need to check again in case evict snuck in.
            if (childId != childNode.mId) {
                childNode.releaseExclusive();
                break check;
            }

            parent.releaseExclusive();

            mStore.used(childNode);
            return childNode;
        }

        // If this point is reached, child needs to be loaded.

        childNode = mStore.allocLatchedNode();
        childNode.mId = childId;
        parent.mChildNodes[childPos >> 1] = childNode;

        // Release parent latch before child has been loaded. Any threads
        // which wish to access the same child will block until this thread
        // has finished loading the child and released its exclusive latch.
        parent.releaseExclusive();

        try {
            childNode.read(mStore, childId);
        } catch (IOException e) {
            // Another thread might access child and see that it is invalid because
            // id is zero. It will assume it got evicted and will load child again.
            childNode.mId = 0;
            childNode.releaseExclusive();
            throw e;
        }

        mStore.used(childNode);
        return childNode;
    }
}
