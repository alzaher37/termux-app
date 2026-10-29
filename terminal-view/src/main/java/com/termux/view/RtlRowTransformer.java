package com.termux.view;

import com.termux.terminal.TerminalRow;
import com.termux.terminal.WcWidth;

import java.text.Bidi;
import java.util.Arrays;

/**
 * Display-only bidi layout of a terminal row.
 * <p/>
 * The terminal buffer keeps text in logical order. For rows containing right-to-left text this runs
 * the Unicode bidi algorithm (paragraph direction from the first strong character, so Arabic lines
 * are right aligned) and builds:
 * <ul>
 * <li>a visual copy of the row where left-to-right cells are moved to their visual position and
 * right-to-left cells are blank, used for backgrounds, cursor and selection;</li>
 * <li>right-to-left segments, which the renderer draws as whole strings with the system font so
 * Android does the letter joining.</li>
 * </ul>
 * The number of cells is kept, so the grid layout still lines up with the logical buffer.
 */
final class RtlRowTransformer {

    /** Stand-in for cells that are not a single BMP char (surrogate pairs and wide chars). */
    private static final char PLACEHOLDER = '￼';

    private int mColumns = -1;
    private TerminalRow mVisualRow;
    private char[] mBase;
    private byte[] mLevels;
    private Integer[] mOrder;
    private boolean[] mEmitted;
    /** Per logical column: start/end char index in the row, and display width (0 = second half of a wide char). */
    private int[] mCellStart, mCellEnd, mCellWidth;

    /** Logical column drawn at each visual column. */
    int[] mLogicalForVisual;
    /** Visual column where each logical column is drawn. */
    int[] mVisualForLogical;

    /** Right-to-left segments of the last transformed row: text range in {@link #mSegmentText} and visual cells. */
    int mSegmentCount;
    char[] mSegmentText;
    int[] mSegmentTextStart, mSegmentTextEnd, mSegmentVisualStart, mSegmentColumns;
    long[] mSegmentStyle;

    static boolean containsRtl(char[] text, int used) {
        for (int i = 0; i < used; i++) {
            final char c = text[i];
            if ((c >= 0x0590 && c <= 0x08FF) || (c >= 0xFB1D && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFC))
                return true;
        }
        return false;
    }

    /** Return a visual copy of {@code row}. The returned row is reused by the next call. */
    TerminalRow transform(TerminalRow row, int columns) {
        if (columns != mColumns) allocate(columns);

        final char[] text = row.mText;
        final int used = row.getSpaceUsed();

        // Split the logical row into cells, mirroring the walk done by TerminalRenderer.
        Arrays.fill(mCellWidth, 1);
        Arrays.fill(mBase, ' ');
        int charIndex = 0;
        for (int column = 0; column < columns && charIndex < used; ) {
            final int start = charIndex;
            final char c = text[charIndex];
            final boolean high = Character.isHighSurrogate(c);
            final int codePoint = high ? Character.toCodePoint(c, text[charIndex + 1]) : c;
            final int width = WcWidth.width(codePoint);
            charIndex += high ? 2 : 1;
            if (width <= 0) continue;
            while (charIndex < used && WcWidth.width(text, charIndex) <= 0)
                charIndex += Character.isHighSurrogate(text[charIndex]) ? 2 : 1;

            mCellStart[column] = start;
            mCellEnd[column] = charIndex;
            mBase[column] = (high || width != 1) ? PLACEHOLDER : c;
            if (width == 2 && column + 1 < columns) {
                mCellWidth[column] = 2;
                mCellWidth[column + 1] = 0;
                mCellStart[column + 1] = charIndex;
                mCellEnd[column + 1] = charIndex;
                mBase[column + 1] = PLACEHOLDER;
            }
            column += width;
        }

        final Bidi bidi = new Bidi(mBase, 0, null, 0, columns, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        for (int i = 0; i < columns; i++) {
            mLevels[i] = (byte) bidi.getLevelAt(i);
            mOrder[i] = i;
        }
        Bidi.reorderVisually(mLevels, 0, mOrder, 0, columns);
        for (int visual = 0; visual < columns; visual++) {
            mLogicalForVisual[visual] = mOrder[visual];
            mVisualForLogical[mOrder[visual]] = visual;
        }

        // Left-to-right cells keep their char at the visual position, right-to-left cells are left blank.
        mVisualRow.clear(row.getStyle(0));
        Arrays.fill(mEmitted, false);
        for (int visual = 0; visual < columns; visual++) {
            final int orderedColumn = mOrder[visual];
            final int logical = mCellWidth[orderedColumn] == 0 ? orderedColumn - 1 : orderedColumn;
            final long style = row.getStyle(logical);
            if (mLevels[logical] > 0) {
                mVisualRow.setChar(visual, ' ', style);
                continue;
            }
            if (mEmitted[logical]) continue;
            mEmitted[logical] = true;

            final int start = mCellStart[logical];
            final boolean high = Character.isHighSurrogate(text[start]);
            int codePoint = high ? Character.toCodePoint(text[start], text[start + 1]) : text[start];
            if (mCellWidth[logical] == 2 && visual + 1 >= columns) codePoint = ' ';
            mVisualRow.setChar(visual, codePoint, style);
            for (int i = start + (high ? 2 : 1); i < mCellEnd[logical]; ) {
                final boolean markHigh = Character.isHighSurrogate(text[i]);
                mVisualRow.setChar(visual, markHigh ? Character.toCodePoint(text[i], text[i + 1]) : text[i], style);
                i += markHigh ? 2 : 1;
            }
        }

        // Right-to-left segments: maximal logical ranges with level > 0 and one style, without edge spaces.
        mSegmentText = text;
        mSegmentCount = 0;
        for (int column = 0; column < columns; ) {
            if (mLevels[column] == 0 || mCellWidth[column] == 0) {
                column++;
                continue;
            }
            final long style = row.getStyle(column);
            int end = column + 1;
            while (end < columns && mLevels[end] > 0 && (mCellWidth[end] == 0 || row.getStyle(end) == style)) end++;
            addSegment(column, end, style);
            column = end;
        }
        return mVisualRow;
    }

    private void addSegment(int start, int end, long style) {
        while (start < end && mBase[start] == ' ') start++;
        while (end > start && mBase[end - 1] == ' ') end--;
        if (start == end) return;

        int visualStart = Integer.MAX_VALUE;
        for (int column = start; column < end; column++) visualStart = Math.min(visualStart, mVisualForLogical[column]);
        final int last = mCellWidth[end - 1] == 0 ? end - 2 : end - 1;

        final int index = mSegmentCount++;
        mSegmentTextStart[index] = mCellStart[start];
        mSegmentTextEnd[index] = mCellEnd[last];
        mSegmentVisualStart[index] = visualStart;
        mSegmentColumns[index] = end - start;
        mSegmentStyle[index] = style;
    }

    private void allocate(int columns) {
        mColumns = columns;
        mVisualRow = new TerminalRow(columns, 0);
        mBase = new char[columns];
        mLevels = new byte[columns];
        mOrder = new Integer[columns];
        mEmitted = new boolean[columns];
        mCellStart = new int[columns];
        mCellEnd = new int[columns];
        mCellWidth = new int[columns];
        mLogicalForVisual = new int[columns];
        mVisualForLogical = new int[columns];
        mSegmentTextStart = new int[columns];
        mSegmentTextEnd = new int[columns];
        mSegmentVisualStart = new int[columns];
        mSegmentColumns = new int[columns];
        mSegmentStyle = new long[columns];
    }
}
