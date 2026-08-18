package com.termux.terminal;

import junit.framework.Assert;

/**
 * 变更台账（dirty-row journal）契约测试：
 * 解析/模型侧的每个变更入口必须准确记录“改了哪些内部行”，渲染侧据此把问题归属到解析还是渲染。
 * 台账是纯记账，不参与渲染行为；这些测试锁定的是“交接规格”本身。
 */
public class DirtyRowJournalTest extends TerminalTestCase {

    private static boolean dirtyBit(long[] bits, int internalRow) {
        return bits != null && (bits[internalRow >> 6] & (1L << (internalRow & 63))) != 0;
    }

    private static void assertDirty(long[] bits, int... rows) {
        for (int r : rows) {
            Assert.assertTrue("expected row " + r + " dirty", dirtyBit(bits, r));
        }
    }

    private static void assertNotDirty(long[] bits, int... rows) {
        for (int r : rows) {
            Assert.assertFalse("expected row " + r + " clean", dirtyBit(bits, r));
        }
    }

    private TerminalBuffer buffer() {
        return mTerminal.getScreen();
    }

    public void testSetCharMarksSingleRow() {
        withTerminalSized(8, 6);
        buffer().getAndClearDirtyRowBits(); // 清掉构造函数预填的台账
        buffer().setChar(0, 2, 'X', TextStyle.NORMAL);
        long[] dirty = buffer().getAndClearDirtyRowBits();
        assertDirty(dirty, 2);
        assertNotDirty(dirty, 0, 1, 3, 4, 5);
        Assert.assertEquals(0, buffer().getDirtyMutationCount()); // 取后清零
    }

    public void testMultipleSetCharsAccumulate() {
        withTerminalSized(8, 6);
        buffer().getAndClearDirtyRowBits();
        buffer().setChar(0, 1, 'A', TextStyle.NORMAL);
        buffer().setChar(0, 3, 'B', TextStyle.NORMAL);
        long[] dirty = buffer().getAndClearDirtyRowBits();
        assertDirty(dirty, 1, 3);
        assertNotDirty(dirty, 0, 2, 4, 5);
        Assert.assertEquals(0, buffer().getDirtyMutationCount());
    }

    public void testBlockCopyDisjointMarksOnlyDestination() {
        withTerminalSized(8, 6);
        buffer().getAndClearDirtyRowBits();
        buffer().setChar(0, 0, 'A', TextStyle.NORMAL);
        buffer().setChar(1, 0, 'B', TextStyle.NORMAL);
        buffer().getAndClearDirtyRowBits();
        buffer().blockCopy(0, 0, 2, 1, 0, 4); // 源行0 -> 目标行4，不重叠
        long[] dirty = buffer().getAndClearDirtyRowBits();
        assertDirty(dirty, 4);
        assertNotDirty(dirty, 0);
    }

    public void testBlockCopyOverlappingMarksUnion() {
        withTerminalSized(8, 6);
        buffer().getAndClearDirtyRowBits();
        buffer().blockCopy(0, 2, 2, 2, 0, 3); // 行2,3 -> 行3,4，重叠
        long[] dirty = buffer().getAndClearDirtyRowBits();
        assertDirty(dirty, 2, 3, 4);
        assertNotDirty(dirty, 0, 1, 5);
    }

    public void testBlockSetMarksRows() {
        withTerminalSized(8, 6);
        buffer().getAndClearDirtyRowBits();
        buffer().blockSet(0, 1, 4, 2, ' ', TextStyle.NORMAL);
        long[] dirty = buffer().getAndClearDirtyRowBits();
        assertDirty(dirty, 1, 2);
        assertNotDirty(dirty, 0, 3);
    }

    public void testScrollMarksEveryVisibleRow() {
        withTerminalSized(5, 3);
        buffer().getAndClearDirtyRowBits();
        enterString("1\n2\n3\n4\n");
        long[] dirty = buffer().getAndClearDirtyRowBits();
        // 滚动后每个可见行的内部索引都旋转了，全部记脏：
        for (int r = 0; r < 3; r++) {
            Assert.assertTrue("expected visible row " + r + " dirty after scroll", dirtyBit(dirty, buffer().externalToInternalRow(r)));
        }
    }

    public void testResizeMarksAllRows() {
        withTerminalSized(6, 4);
        buffer().getAndClearDirtyRowBits();
        buffer().resize(10, 4, 8, new int[]{0, 0}, TextStyle.NORMAL, false);
        long[] dirty = buffer().getAndClearDirtyRowBits();
        for (int r = 0; r < 8; r++) {
            Assert.assertTrue("expected row " + r + " dirty after resize", dirtyBit(dirty, r));
        }
    }

    public void testSetLineWrapMarksRow() {
        withTerminalSized(8, 6);
        buffer().getAndClearDirtyRowBits();
        buffer().setLineWrap(2);
        long[] dirty = buffer().getAndClearDirtyRowBits();
        assertDirty(dirty, buffer().externalToInternalRow(2));
        assertNotDirty(dirty, 0, 1, 3);
    }

    public void testClearLineWrapMarksRow() {
        withTerminalSized(8, 6);
        buffer().setLineWrap(4);
        buffer().getAndClearDirtyRowBits();
        buffer().clearLineWrap(4);
        long[] dirty = buffer().getAndClearDirtyRowBits();
        assertDirty(dirty, buffer().externalToInternalRow(4));
    }

    public void testGetAndClearReturnsSnapshotCopy() {
        withTerminalSized(8, 6);
        buffer().getAndClearDirtyRowBits();
        buffer().setChar(0, 0, 'A', TextStyle.NORMAL);
        long[] first = buffer().getAndClearDirtyRowBits();
        assertDirty(first, 0);
        buffer().setChar(0, 2, 'B', TextStyle.NORMAL);
        long[] second = buffer().getAndClearDirtyRowBits();
        // first 是快照副本，不受后续变更影响；second 只含新变更：
        assertDirty(second, 2);
        assertNotDirty(second, 0);
        Assert.assertEquals(0, buffer().getDirtyMutationCount());
    }

    public void testMutationCountTracksBatches() {
        withTerminalSized(8, 6);
        buffer().getAndClearDirtyRowBits();
        Assert.assertEquals(0, buffer().getDirtyMutationCount());
        buffer().setChar(0, 0, 'A', TextStyle.NORMAL);
        buffer().setChar(0, 1, 'B', TextStyle.NORMAL);
        buffer().setChar(0, 2, 'C', TextStyle.NORMAL);
        Assert.assertEquals(3, buffer().getDirtyMutationCount());
        buffer().getAndClearDirtyRowBits();
        Assert.assertEquals(0, buffer().getDirtyMutationCount());
    }

    public void testSetOrClearEffectMarksRows() {
        withTerminalSized(8, 6);
        buffer().getAndClearDirtyRowBits();
        buffer().setOrClearEffect(TextStyle.CHARACTER_ATTRIBUTE_BOLD, true, false, false,
            0, 8, 1, 0, 3, 5);
        long[] dirty = buffer().getAndClearDirtyRowBits();
        assertDirty(dirty, 1, 2);
        assertNotDirty(dirty, 0, 3);
    }
}