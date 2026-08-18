package cn.fj.loli.hexsupport;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongToIntFunction;

/**
 * Maps IDEA document offsets (UTF-16 with normalized '\n' line endings) to
 * offsets in the encoded file and back again.
 */
final class TextByteOffsetMapper {
    private final CharSequence text;
    private final Charset charset;
    private final int bomLength;
    private final int[] lineTextStarts;
    private final long[] lineByteStarts;
    private final long[] lineContentByteEnds;
    private final long totalByteLength;

    TextByteOffsetMapper(CharSequence text, Charset charset, byte[] bom, String lineSeparator) {
        this(text, charset, bom, lineSeparator, -1, null);
    }

    TextByteOffsetMapper(CharSequence text, Charset charset, byte[] bom, String lineSeparator,
                         long sourceByteLength, LongToIntFunction sourceByteAt) {
        this.text = text;
        this.charset = effectiveCharset(charset, bom);
        this.bomLength = bom == null ? 0 : bom.length;
        String separator = lineSeparator == null || lineSeparator.isEmpty() ? "\n" : lineSeparator;
        byte[] fallbackSeparator = encodedBytes(separator, 0, separator.length());
        List<byte[]> separatorCandidates = separatorCandidates(fallbackSeparator);

        List<Integer> textStarts = new ArrayList<>();
        List<Long> byteStarts = new ArrayList<>();
        List<Long> contentEnds = new ArrayList<>();
        int lineStart = 0;
        long byteStart = bomLength;
        textStarts.add(lineStart);
        byteStarts.add(byteStart);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\n') {
                continue;
            }
            byteStart += encodedLength(text, lineStart, i);
            contentEnds.add(byteStart);
            byteStart += actualSeparatorByteLength(byteStart, fallbackSeparator, separatorCandidates,
                    sourceByteLength, sourceByteAt);
            lineStart = i + 1;
            textStarts.add(lineStart);
            byteStarts.add(byteStart);
        }
        byteStart += encodedLength(text, lineStart, text.length());
        contentEnds.add(byteStart);
        totalByteLength = byteStart;

        lineTextStarts = textStarts.stream().mapToInt(Integer::intValue).toArray();
        lineByteStarts = byteStarts.stream().mapToLong(Long::longValue).toArray();
        lineContentByteEnds = contentEnds.stream().mapToLong(Long::longValue).toArray();
    }

    long textStartToByte(int textOffset) {
        return textBoundaryToByte(adjustSurrogateBoundary(textOffset, false));
    }

    long textEndToByte(int textOffset) {
        return textBoundaryToByte(adjustSurrogateBoundary(textOffset, true));
    }

    int byteToTextStart(long byteOffset) {
        long offset = Math.max(bomLength, Math.min(byteOffset, totalByteLength));
        int line = lineForByte(offset);
        int lineStart = lineTextStarts[line];
        int lineEnd = lineTextEnd(line);
        long lineByteStart = lineByteStarts[line];
        long contentByteEnd = lineContentByteEnds[line];
        if (offset <= lineByteStart) {
            return lineStart;
        }
        if (offset >= contentByteEnd) {
            return lineEnd;
        }

        long cursor = lineByteStart;
        for (int i = lineStart; i < lineEnd; ) {
            int next = nextCodePointOffset(i, lineEnd);
            long nextCursor = cursor + encodedLength(text, i, next);
            if (offset < nextCursor) {
                return i;
            }
            if (offset == nextCursor) {
                return next;
            }
            cursor = nextCursor;
            i = next;
        }
        return lineEnd;
    }

    int byteToTextEnd(long byteOffset) {
        long offset = Math.max(bomLength, Math.min(byteOffset, totalByteLength));
        int line = lineForByte(offset);
        int lineStart = lineTextStarts[line];
        int lineEnd = lineTextEnd(line);
        long lineByteStart = lineByteStarts[line];
        long contentByteEnd = lineContentByteEnds[line];
        if (offset <= lineByteStart) {
            return lineStart;
        }
        if (offset > contentByteEnd && line + 1 < lineTextStarts.length) {
            return lineTextStarts[line + 1];
        }
        if (offset == contentByteEnd) {
            return lineEnd;
        }

        long cursor = lineByteStart;
        for (int i = lineStart; i < lineEnd; ) {
            int next = nextCodePointOffset(i, lineEnd);
            long nextCursor = cursor + encodedLength(text, i, next);
            if (offset <= nextCursor) {
                return offset == cursor ? i : next;
            }
            cursor = nextCursor;
            i = next;
        }
        return lineEnd;
    }

    long totalByteLength() {
        return totalByteLength;
    }

    private long textBoundaryToByte(int textOffset) {
        int offset = Math.max(0, Math.min(textOffset, text.length()));
        int line = lineForText(offset);
        int lineEnd = lineTextEnd(line);
        int contentOffset = Math.min(offset, lineEnd);
        return lineByteStarts[line] + encodedLength(text, lineTextStarts[line], contentOffset);
    }

    private int adjustSurrogateBoundary(int offset, boolean moveForward) {
        int clamped = Math.max(0, Math.min(offset, text.length()));
        if (clamped > 0 && clamped < text.length()
                && Character.isHighSurrogate(text.charAt(clamped - 1))
                && Character.isLowSurrogate(text.charAt(clamped))) {
            return moveForward ? clamped + 1 : clamped - 1;
        }
        return clamped;
    }

    private int lineForText(int textOffset) {
        int low = 0;
        int high = lineTextStarts.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (lineTextStarts[mid] <= textOffset) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return Math.max(0, low - 1);
    }

    private int lineForByte(long byteOffset) {
        int low = 0;
        int high = lineByteStarts.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (lineByteStarts[mid] <= byteOffset) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return Math.max(0, low - 1);
    }

    private int lineTextEnd(int line) {
        return line + 1 < lineTextStarts.length ? lineTextStarts[line + 1] - 1 : text.length();
    }

    private int nextCodePointOffset(int offset, int limit) {
        if (offset + 1 < limit
                && Character.isHighSurrogate(text.charAt(offset))
                && Character.isLowSurrogate(text.charAt(offset + 1))) {
            return offset + 2;
        }
        return offset + 1;
    }

    private long encodedLength(CharSequence value, int start, int end) {
        if (start >= end) {
            return 0;
        }
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(value, start, end));
            return encoded.remaining();
        } catch (CharacterCodingException ignored) {
            return value.subSequence(start, end).toString().getBytes(charset).length;
        }
    }

    private byte[] encodedBytes(CharSequence value, int start, int end) {
        if (start >= end) {
            return new byte[0];
        }
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(value, start, end));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException ignored) {
            return value.subSequence(start, end).toString().getBytes(charset);
        }
    }

    private List<byte[]> separatorCandidates(byte[] fallbackSeparator) {
        List<byte[]> candidates = new ArrayList<>();
        addSeparatorCandidate(candidates, fallbackSeparator);
        addSeparatorCandidate(candidates, encodedBytes("\r\n", 0, 2));
        addSeparatorCandidate(candidates, encodedBytes("\n", 0, 1));
        addSeparatorCandidate(candidates, encodedBytes("\r", 0, 1));
        candidates.sort((left, right) -> Integer.compare(right.length, left.length));
        return candidates;
    }

    private static void addSeparatorCandidate(List<byte[]> candidates, byte[] candidate) {
        for (byte[] existing : candidates) {
            if (Arrays.equals(existing, candidate)) {
                return;
            }
        }
        candidates.add(candidate);
    }

    private static int actualSeparatorByteLength(long byteOffset, byte[] fallbackSeparator,
                                                  List<byte[]> candidates, long sourceByteLength,
                                                  LongToIntFunction sourceByteAt) {
        if (sourceByteAt == null || sourceByteLength < 0) {
            return fallbackSeparator.length;
        }
        for (byte[] candidate : candidates) {
            if (byteOffset < 0 || candidate.length > sourceByteLength - byteOffset) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < candidate.length; i++) {
                if (sourceByteAt.applyAsInt(byteOffset + i) != (candidate[i] & 0xFF)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return candidate.length;
            }
        }
        return fallbackSeparator.length;
    }

    private static Charset effectiveCharset(Charset charset, byte[] bom) {
        if (!StandardCharsets.UTF_16.equals(charset)) {
            return charset;
        }
        if (bom != null && bom.length >= 2 && (bom[0] & 0xFF) == 0xFF && (bom[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        return StandardCharsets.UTF_16BE;
    }
}
