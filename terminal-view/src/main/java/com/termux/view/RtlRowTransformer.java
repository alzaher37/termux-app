package com.termux.view;

import android.icu.text.ArabicShaping;
import android.icu.text.ArabicShapingException;
import android.os.Build;

import com.termux.terminal.TerminalRow;
import com.termux.terminal.WcWidth;

import java.text.Bidi;
import java.util.Arrays;

/**
 * Display-only bidi reordering and Arabic shaping of a terminal row.
 * <p/>
 * The terminal buffer keeps text in logical order. For rows containing right-to-left text this
 * builds a visual copy where Arabic letters are joined (presentation forms) and right-to-left runs
 * are reversed. The paragraph direction is always left-to-right and the number of cells is kept,
 * so the grid layout, cursor and selection still line up with the logical buffer.
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
                mBase[column + 1] = PLACEHOLDER;
            }
            column += width;
        }

        final char[] shaped = Shaper.shape(mBase, columns);

        final Bidi bidi = new Bidi(mBase, 0, null, 0, columns, Bidi.DIRECTION_LEFT_TO_RIGHT);
        for (int i = 0; i < columns; i++) {
            mLevels[i] = (byte) bidi.getLevelAt(i);
            mOrder[i] = i;
        }
        Bidi.reorderVisually(mLevels, 0, mOrder, 0, columns);

        mVisualRow.clear(row.getStyle(0));
        Arrays.fill(mEmitted, false);
        for (int visual = 0; visual < columns; visual++) {
            final int orderedColumn = mOrder[visual];
            final int logical = mCellWidth[orderedColumn] == 0 ? orderedColumn - 1 : orderedColumn;
            mLogicalForVisual[visual] = orderedColumn;
            mVisualForLogical[orderedColumn] = visual;
            if (mEmitted[logical]) continue;
            mEmitted[logical] = true;

            final long style = row.getStyle(logical);
            final int start = mCellStart[logical];
            final boolean high = Character.isHighSurrogate(text[start]);
            int codePoint = high ? Character.toCodePoint(text[start], text[start + 1]) : text[start];
            if (mBase[logical] != PLACEHOLDER) {
                codePoint = shaped[logical];
                if ((mLevels[logical] & 1) != 0) codePoint = mirror(codePoint);
            }
            if (mCellWidth[logical] == 2 && visual + 1 >= columns) codePoint = ' ';
            mVisualRow.setChar(visual, codePoint, style);

            // Re-attach combining marks (e.g. Arabic harakat) to the cell.
            for (int i = start + (high ? 2 : 1); i < mCellEnd[logical]; ) {
                final boolean markHigh = Character.isHighSurrogate(text[i]);
                mVisualRow.setChar(visual, markHigh ? Character.toCodePoint(text[i], text[i + 1]) : text[i], style);
                i += markHigh ? 2 : 1;
            }
        }
        return mVisualRow;
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
    }

    private static int mirror(int c) {
        switch (c) {
            case '(': return ')';
            case ')': return '(';
            case '[': return ']';
            case ']': return '[';
            case '{': return '}';
            case '}': return '{';
            case '<': return '>';
            case '>': return '<';
            case '«': return '»';
            case '»': return '«';
            default: return c;
        }
    }

    /** Arabic letter joining. Kept in its own class so android.icu is only loaded on API 24+. */
    private static final class Shaper {
        private static ArabicShaping sShaping;

        static char[] shape(char[] base, int length) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return base;
            if (sShaping == null)
                sShaping = new ArabicShaping(ArabicShaping.LETTERS_SHAPE | ArabicShaping.LENGTH_FIXED_SPACES_NEAR
                    | ArabicShaping.TEXT_DIRECTION_LOGICAL);
            try {
                final String shaped = sShaping.shape(new String(base, 0, length));
                return shaped.length() == length ? shaped.toCharArray() : base;
            } catch (ArabicShapingException e) {
                return base;
            }
        }
    }
}
