package com.termux.view;

import android.graphics.Paint;
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

    private final Paint mPaint;
    /** Per presentation form (U+FB50..U+FEFF): 0 = unknown, 1 = font has a glyph, 2 = it does not. */
    private final byte[] mGlyphKnown = new byte[0xFEFF - 0xFB50 + 1];

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

    RtlRowTransformer(Paint paint) {
        mPaint = paint;
    }

    /** Whether the font can draw {@code c}; presentation forms are missing from some fonts. */
    private boolean hasGlyph(char c) {
        if (c < 0xFB50 || c > 0xFEFF || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        final int index = c - 0xFB50;
        if (mGlyphKnown[index] == 0)
            mGlyphKnown[index] = (byte) (mPaint.hasGlyph(String.valueOf(c)) && mPaint.measureText(String.valueOf(c)) > 0 ? 1 : 2);
        return mGlyphKnown[index] == 1;
    }

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
                if (hasGlyph(shaped[logical])) codePoint = shaped[logical];
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

    /**
     * Arabic letter joining: replaces letters with their isolated/final/initial/medial presentation
     * forms. Lam + alef is kept as two joined letters rather than the ligature, which would leave an
     * empty cell in the fixed-width grid.
     */
    private static final class Shaper {
        private static final int NONE = 0, RIGHT = 1, DUAL = 2, CAUSING = 3;

        /** {letter, isolated presentation form, joining type}. Final/initial/medial follow the isolated form. */
        private static final int[][] LETTERS = {
            {0x0621, 0xFE80, NONE}, {0x0622, 0xFE81, RIGHT}, {0x0623, 0xFE83, RIGHT}, {0x0624, 0xFE85, RIGHT},
            {0x0625, 0xFE87, RIGHT}, {0x0626, 0xFE89, DUAL}, {0x0627, 0xFE8D, RIGHT}, {0x0628, 0xFE8F, DUAL},
            {0x0629, 0xFE93, RIGHT}, {0x062A, 0xFE95, DUAL}, {0x062B, 0xFE99, DUAL}, {0x062C, 0xFE9D, DUAL},
            {0x062D, 0xFEA1, DUAL}, {0x062E, 0xFEA5, DUAL}, {0x062F, 0xFEA9, RIGHT}, {0x0630, 0xFEAB, RIGHT},
            {0x0631, 0xFEAD, RIGHT}, {0x0632, 0xFEAF, RIGHT}, {0x0633, 0xFEB1, DUAL}, {0x0634, 0xFEB5, DUAL},
            {0x0635, 0xFEB9, DUAL}, {0x0636, 0xFEBD, DUAL}, {0x0637, 0xFEC1, DUAL}, {0x0638, 0xFEC5, DUAL},
            {0x0639, 0xFEC9, DUAL}, {0x063A, 0xFECD, DUAL}, {0x0640, 0x0640, CAUSING}, {0x0641, 0xFED1, DUAL},
            {0x0642, 0xFED5, DUAL}, {0x0643, 0xFED9, DUAL}, {0x0644, 0xFEDD, DUAL}, {0x0645, 0xFEE1, DUAL},
            {0x0646, 0xFEE5, DUAL}, {0x0647, 0xFEE9, DUAL}, {0x0648, 0xFEED, RIGHT}, {0x0649, 0xFEEF, RIGHT},
            {0x064A, 0xFEF1, DUAL}, {0x0671, 0xFB50, RIGHT}, {0x067E, 0xFB56, DUAL}, {0x0686, 0xFB7A, DUAL},
            {0x0698, 0xFB8A, RIGHT}, {0x06A9, 0xFB8E, DUAL}, {0x06AF, 0xFB92, DUAL}, {0x06CC, 0xFBFC, DUAL},
        };

        private static int find(char c) {
            if (c < 0x0621 || c > 0x06CC) return -1;
            for (int i = 0; i < LETTERS.length; i++) if (LETTERS[i][0] == c) return i;
            return -1;
        }

        private static int type(char c) {
            final int i = find(c);
            return i < 0 ? NONE : LETTERS[i][2];
        }

        static char[] shape(char[] base, int length) {
            final char[] out = new char[length];
            System.arraycopy(base, 0, out, 0, length);
            for (int i = 0; i < length; i++) {
                final int index = find(base[i]);
                if (index < 0) continue;
                final int type = LETTERS[index][2];
                final int previousType = i > 0 ? type(base[i - 1]) : NONE;
                final boolean joinsPrevious = type != NONE && (previousType == DUAL || previousType == CAUSING);
                final int nextType = i + 1 < length ? type(base[i + 1]) : NONE;

                if (type == CAUSING || type == NONE) continue;

                final boolean joinsNext = type == DUAL && nextType != NONE;
                final int isolated = LETTERS[index][1];
                if (joinsPrevious && joinsNext) out[i] = (char) (isolated + 3);
                else if (joinsNext) out[i] = (char) (isolated + 2);
                else if (joinsPrevious) out[i] = (char) (isolated + 1);
                else out[i] = (char) isolated;
            }
            return out;
        }
    }
}
