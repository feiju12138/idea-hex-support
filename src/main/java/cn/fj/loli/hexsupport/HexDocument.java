package cn.fj.loli.hexsupport;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class HexDocument implements Closeable {
    private static final long SMALL_FILE_LIMIT = 64L * 1024 * 1024;
    private static final int PAGE_SIZE = 1024 * 1024;
    private static final int PAGE_CACHE_SIZE = 3;
    private static final int OPERATION_PREVIEW_BYTES = 16;

    private final Path sourcePath;
    private FileChannel sourceChannel;
    private Path tempPath;
    private FileChannel tempChannel;
    private byte[] memoryData;
    private final List<Piece> pieces = new ArrayList<>();
    private final List<OperationRecord> operations = new ArrayList<>();
    private final LinkedHashMap<Long, byte[]> originalPageCache = new LinkedHashMap<>(PAGE_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            return size() > PAGE_CACHE_SIZE;
        }
    };
    private boolean largeMode;
    private long length;
    private long revision;
    private long operationSequence;

    HexDocument(Path sourcePath) {
        try {
            this.sourcePath = sourcePath;
            this.sourceChannel = FileChannel.open(sourcePath, StandardOpenOption.READ);
            this.length = sourceChannel.size();
            if (length <= SMALL_FILE_LIMIT) {
                this.memoryData = readSourceIntoMemory();
                this.largeMode = false;
            } else {
                this.largeMode = true;
                if (length > 0) {
                    pieces.add(new Piece(Source.ORIGINAL, 0, length, 0));
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    synchronized long length() {
        return length;
    }

    synchronized long revision() {
        return revision;
    }

    synchronized boolean isLargeMode() {
        return largeMode;
    }

    synchronized State snapshot() {
        return new State(
                largeMode,
                memoryData == null ? null : Arrays.copyOf(memoryData, memoryData.length),
                new ArrayList<>(pieces),
                new ArrayList<>(operations),
                length,
                revision,
                operationSequence);
    }

    synchronized void restore(State state) {
        largeMode = state.largeMode();
        memoryData = state.memoryData() == null ? null : Arrays.copyOf(state.memoryData(), state.memoryData().length);
        pieces.clear();
        pieces.addAll(state.pieces());
        operations.clear();
        operations.addAll(state.operations());
        length = state.length();
        operationSequence = state.operationSequence();
        originalPageCache.clear();
        revision++;
    }

    synchronized List<OperationRecord> operationRecords() {
        return new ArrayList<>(operations);
    }

    synchronized int unsignedAt(long offset) {
        if (offset < 0 || offset >= length) {
            return 0;
        }
        if (!largeMode) {
            return memoryData[(int) offset] & 0xFF;
        }
        byte[] one = read(offset, 1);
        return one.length == 0 ? 0 : one[0] & 0xFF;
    }

    synchronized byte[] read(long offset, int requestedLength) {
        if (offset < 0 || requestedLength <= 0 || offset >= length) {
            return new byte[0];
        }
        int actualLength = (int) Math.min(requestedLength, length - offset);
        byte[] result = new byte[actualLength];
        if (largeMode) {
            readLargeInto(offset, result, 0, actualLength);
        } else {
            System.arraycopy(memoryData, (int) offset, result, 0, actualLength);
        }
        return result;
    }

    synchronized void writeRangeTo(long offset, long requestedLength, OutputStream output, ProgressReporter progressReporter) throws IOException {
        if (offset < 0 || requestedLength <= 0 || offset >= length) {
            return;
        }
        long remaining = Math.min(requestedLength, length - offset);
        long total = remaining;
        long cursor = offset;
        byte[] buffer = new byte[(int) Math.min(PAGE_SIZE, remaining)];
        reportProgress(progressReporter, 0, total);
        while (remaining > 0) {
            int count = (int) Math.min(buffer.length, remaining);
            if (largeMode) {
                readLargeInto(cursor, buffer, 0, count);
            } else {
                System.arraycopy(memoryData, (int) cursor, buffer, 0, count);
            }
            output.write(buffer, 0, count);
            cursor += count;
            remaining -= count;
            reportProgress(progressReporter, cursor - offset, total);
        }
    }

    synchronized void overwrite(long offset, byte[] values) {
        Objects.requireNonNull(values, "values");
        if (values.length == 0 || offset < 0) {
            return;
        }
        long requiredLength = Math.max(length, offset + values.length);
        ensureModeForLength(requiredLength);
        long beforeLength = Math.max(0, Math.min(values.length, length - offset));
        byte[] before = previewBytes(offset, beforeLength);
        if (largeMode) {
            if (offset > length) {
                insertFillLarge(length, offset - length, 0);
            }
            replaceRange(offset, Math.min(values.length, length - offset), addPiece(values));
        } else {
            ensureMemoryLength(requiredLength);
            System.arraycopy(values, 0, memoryData, (int) offset, values.length);
            length = memoryData.length;
            revision++;
        }
        recordOperation(OperationType.OVERWRITE, offset, beforeLength, values.length, before, preview(values),
                Arrays.copyOf(values, values.length), null, null);
    }

    synchronized void fill(long offset, long requestedLength, int value) {
        if (offset < 0 || requestedLength <= 0) {
            return;
        }
        long requiredLength = Math.max(length, offset + requestedLength);
        ensureModeForLength(requiredLength);
        long removeLength = Math.max(0, Math.min(requestedLength, length - offset));
        byte[] before = previewBytes(offset, removeLength);
        if (largeMode) {
            if (offset > length) {
                insertFillLarge(length, offset - length, 0);
            }
            replaceRange(offset, Math.min(requestedLength, length - offset),
                    new Piece(Source.FILL, value & 0xFFL, requestedLength, value & 0xFF));
        } else {
            ensureMemoryLength(requiredLength);
            Arrays.fill(memoryData, (int) offset, (int) (offset + requestedLength), (byte) value);
            length = memoryData.length;
            revision++;
        }
        recordOperation(OperationType.FILL, offset, removeLength, requestedLength, before, fillPreview(value, requestedLength),
                null, value & 0xFF, null);
    }

    synchronized void insert(long offset, byte[] values) {
        Objects.requireNonNull(values, "values");
        if (values.length == 0) {
            return;
        }
        ensureModeForLength(length + values.length);
        long insertion = Math.max(0, Math.min(offset, length));
        if (largeMode) {
            insertPiece(insertion, addPiece(values));
        } else {
            byte[] updated = new byte[Math.toIntExact(length + values.length)];
            System.arraycopy(memoryData, 0, updated, 0, (int) insertion);
            System.arraycopy(values, 0, updated, (int) insertion, values.length);
            System.arraycopy(memoryData, (int) insertion, updated, (int) insertion + values.length, (int) (length - insertion));
            memoryData = updated;
            length = updated.length;
            revision++;
        }
        recordOperation(OperationType.INSERT, insertion, 0, values.length, new byte[0], preview(values),
                Arrays.copyOf(values, values.length), null, null);
    }

    synchronized void insertFile(long offset, Path path) {
        insertFile(offset, path, null);
    }

    synchronized void insertFile(long offset, Path path, ProgressReporter progressReporter) {
        try {
            long fileLength = Files.size(path);
            if (fileLength <= 0) {
                return;
            }
            reportProgress(progressReporter, 0, fileLength);
            ensureModeForLength(length + fileLength);
            long insertion = Math.max(0, Math.min(offset, length));
            byte[] afterPreview = readFilePreview(path);
            if (largeMode) {
                ensureTempChannel();
                long start = tempChannel.size();
                long copied = copyFileToTemp(path, fileLength, progressReporter);
                if (copied > 0) {
                    insertPiece(insertion, new Piece(Source.ADD, start, copied, 0));
                }
            } else {
                byte[] values = Files.readAllBytes(path);
                reportProgress(progressReporter, values.length, fileLength);
                insert(insertion, values);
                operations.remove(operations.size() - 1);
            }
            recordOperation(OperationType.IMPORT_FILE, insertion, 0, fileLength, new byte[0], afterPreview,
                    null, null, path.toAbsolutePath().toString());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    synchronized void insertFill(long offset, long count, int value) {
        if (count <= 0) {
            return;
        }
        ensureModeForLength(length + count);
        long insertion = Math.max(0, Math.min(offset, length));
        if (largeMode) {
            insertFillLarge(insertion, count, value);
        } else {
            byte[] updated = new byte[Math.toIntExact(length + count)];
            System.arraycopy(memoryData, 0, updated, 0, (int) insertion);
            Arrays.fill(updated, (int) insertion, (int) (insertion + count), (byte) value);
            System.arraycopy(memoryData, (int) insertion, updated, (int) (insertion + count), (int) (length - insertion));
            memoryData = updated;
            length = updated.length;
            revision++;
        }
        recordOperation(OperationType.INSERT_FILL, insertion, 0, count, new byte[0], fillPreview(value, count),
                null, value & 0xFF, null);
    }

    synchronized void delete(long offset, long requestedLength) {
        if (offset < 0 || requestedLength <= 0 || offset >= length) {
            return;
        }
        long removeLength = Math.min(requestedLength, length - offset);
        byte[] before = previewBytes(offset, removeLength);
        if (largeMode) {
            replaceRange(offset, removeLength, null);
        } else {
            byte[] updated = new byte[Math.toIntExact(length - removeLength)];
            System.arraycopy(memoryData, 0, updated, 0, (int) offset);
            System.arraycopy(memoryData, (int) (offset + removeLength), updated, (int) offset, (int) (length - offset - removeLength));
            memoryData = updated;
            length = updated.length;
            revision++;
        }
        recordOperation(OperationType.DELETE, offset, removeLength, 0, before, new byte[0],
                null, null, null);
    }

    synchronized FindResult findAll(byte[] pattern, long fromOffset, int limit, BooleanSupplier shouldContinue, ProgressReporter progressReporter) {
        if (pattern.length == 0 || pattern.length > length || limit <= 0) {
            return new FindResult(new ArrayList<>(), false);
        }
        if (!largeMode) {
            return findAllInMemory(pattern, fromOffset, limit, shouldContinue, progressReporter);
        }
        return findAllStreaming(pattern, fromOffset, limit, shouldContinue, progressReporter);
    }

    synchronized void saveTo(Path target, ProgressReporter progressReporter) throws IOException {
        Path absoluteTarget = target.toAbsolutePath();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            parent = Path.of(".");
        }
        Path writePath = Files.createTempFile(parent, absoluteTarget.getFileName().toString(), ".hexsave");
        boolean moved = false;
        try {
            try (OutputStream output = Files.newOutputStream(writePath, StandardOpenOption.WRITE)) {
                writeRangeTo(0, length, output, progressReporter);
            }
            boolean savingSource = absoluteTarget.equals(sourcePath.toAbsolutePath());
            if (savingSource) {
                sourceChannel.close();
            }
            try {
                try {
                    Files.move(writePath, absoluteTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(writePath, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
                }
                moved = true;
            } finally {
                if (savingSource) {
                    sourceChannel = FileChannel.open(sourcePath, StandardOpenOption.READ);
                }
            }
            if (savingSource) {
                operations.clear();
                operationSequence = 0;
                reloadFromOpenSource();
            }
        } finally {
            if (!moved) {
                Files.deleteIfExists(writePath);
            }
        }
    }

    synchronized void reload() {
        try {
            reopenSource();
            operations.clear();
            operationSequence = 0;
            reloadFromOpenSource();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    synchronized void applyHistoryRecord(OperationRecord record) {
        int operationCount = operations.size();
        switch (record.type()) {
            case OVERWRITE -> {
                byte[] afterBytes = record.afterBytes();
                if (afterBytes == null || afterBytes.length != record.afterLength()) {
                    throw new IllegalArgumentException("Operation history overwrite payload is incomplete.");
                }
                overwrite(record.offset(), afterBytes);
            }
            case FILL -> {
                Integer fillValue = record.fillValue();
                if (fillValue == null) {
                    throw new IllegalArgumentException("Operation history fill value is missing.");
                }
                fill(record.offset(), record.afterLength(), fillValue);
            }
            case INSERT -> {
                byte[] afterBytes = record.afterBytes();
                if (afterBytes == null || afterBytes.length != record.afterLength()) {
                    throw new IllegalArgumentException("Operation history insert payload is incomplete.");
                }
                insert(record.offset(), afterBytes);
            }
            case INSERT_FILL -> {
                Integer fillValue = record.fillValue();
                if (fillValue == null) {
                    throw new IllegalArgumentException("Operation history insert-fill value is missing.");
                }
                insertFill(record.offset(), record.afterLength(), fillValue);
            }
            case IMPORT_FILE -> {
                byte[] afterBytes = record.afterBytes();
                if (afterBytes != null && afterBytes.length == record.afterLength()) {
                    insert(record.offset(), afterBytes);
                } else if (record.importPath() != null) {
                    Path importPath = Path.of(record.importPath());
                    if (!Files.isRegularFile(importPath)) {
                        throw new IllegalArgumentException("Imported file from operation history is missing: " + importPath);
                    }
                    insertFile(record.offset(), importPath);
                } else {
                    throw new IllegalArgumentException("Operation history import payload is incomplete.");
                }
            }
            case DELETE -> delete(record.offset(), record.beforeLength());
        }
        if (operations.size() > operationCount) {
            operations.remove(operations.size() - 1);
        }
        operations.add(record);
        operationSequence = Math.max(operationSequence, record.sequence());
        revision++;
    }

    @Override
    public synchronized void close() throws IOException {
        sourceChannel.close();
        if (tempChannel != null) {
            tempChannel.close();
        }
        if (tempPath != null) {
            Files.deleteIfExists(tempPath);
        }
    }

    private byte[] readSourceIntoMemory() throws IOException {
        byte[] data = new byte[Math.toIntExact(length)];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        long position = 0;
        while (buffer.hasRemaining()) {
            int read = sourceChannel.read(buffer, position + buffer.position());
            if (read < 0) {
                break;
            }
        }
        return data;
    }

    private void ensureModeForLength(long targetLength) {
        if (!largeMode && targetLength > SMALL_FILE_LIMIT) {
            convertMemoryToLarge();
        }
    }

    private void convertMemoryToLarge() {
        try {
            ensureTempChannel();
            long start = tempChannel.size();
            ByteBuffer buffer = ByteBuffer.wrap(memoryData);
            while (buffer.hasRemaining()) {
                tempChannel.write(buffer, start + buffer.position());
            }
            pieces.clear();
            if (memoryData.length > 0) {
                pieces.add(new Piece(Source.ADD, start, memoryData.length, 0));
            }
            memoryData = null;
            largeMode = true;
            originalPageCache.clear();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void ensureMemoryLength(long requiredLength) {
        int required = Math.toIntExact(requiredLength);
        if (memoryData.length < required) {
            memoryData = Arrays.copyOf(memoryData, required);
        }
    }

    private void ensureTempChannel() throws IOException {
        if (tempChannel != null) {
            return;
        }
        tempPath = Files.createTempFile("hex-support-", ".tmp");
        tempChannel = FileChannel.open(tempPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    private Piece addPiece(byte[] values) {
        try {
            ensureTempChannel();
            long start = tempChannel.size();
            ByteBuffer buffer = ByteBuffer.wrap(values);
            while (buffer.hasRemaining()) {
                tempChannel.write(buffer, start + buffer.position());
            }
            return new Piece(Source.ADD, start, values.length, 0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private long copyFileToTemp(Path path, long total, ProgressReporter progressReporter) throws IOException {
        long start = tempChannel.size();
        long copied = 0;
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(PAGE_SIZE);
            while (input.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    copied += tempChannel.write(buffer, start + copied);
                }
                buffer.clear();
                reportProgress(progressReporter, copied, total);
            }
        }
        return copied;
    }

    private byte[] readFilePreview(Path path) throws IOException {
        byte[] preview = new byte[(int) Math.min(OPERATION_PREVIEW_BYTES, Files.size(path))];
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(preview);
            while (buffer.hasRemaining() && input.read(buffer, buffer.position()) >= 0) {
                // Positional read into preview.
            }
        }
        return preview;
    }

    private void insertFillLarge(long offset, long count, int value) {
        insertPiece(offset, new Piece(Source.FILL, value & 0xFFL, count, value & 0xFF));
    }

    private void replaceRange(long offset, long removeLength, Piece insertion) {
        List<Piece> replacement = new ArrayList<>();
        long removeEnd = offset + removeLength;
        long cursor = 0;
        boolean inserted = false;
        for (Piece piece : pieces) {
            long pieceStart = cursor;
            long pieceEnd = cursor + piece.length();
            if (pieceEnd <= offset || pieceStart >= removeEnd) {
                if (!inserted && pieceStart >= offset) {
                    addIfPresent(replacement, insertion);
                    inserted = true;
                }
                replacement.add(piece);
            } else {
                long keepBefore = Math.max(0, offset - pieceStart);
                long keepAfter = Math.max(0, pieceEnd - removeEnd);
                if (keepBefore > 0) {
                    replacement.add(piece.slice(0, keepBefore));
                }
                if (!inserted) {
                    addIfPresent(replacement, insertion);
                    inserted = true;
                }
                if (keepAfter > 0) {
                    replacement.add(piece.slice(piece.length() - keepAfter, keepAfter));
                }
            }
            cursor = pieceEnd;
        }
        if (!inserted) {
            addIfPresent(replacement, insertion);
        }
        pieces.clear();
        pieces.addAll(coalesce(replacement));
        length = length - removeLength + (insertion == null ? 0 : insertion.length());
        revision++;
    }

    private void insertPiece(long offset, Piece insertion) {
        replaceRange(offset, 0, insertion);
    }

    private static void addIfPresent(List<Piece> pieces, Piece piece) {
        if (piece != null && piece.length() > 0) {
            pieces.add(piece);
        }
    }

    private static List<Piece> coalesce(List<Piece> source) {
        List<Piece> result = new ArrayList<>();
        for (Piece piece : source) {
            if (piece.length() <= 0) {
                continue;
            }
            if (!result.isEmpty()) {
                Piece previous = result.get(result.size() - 1);
                Piece merged = previous.merge(piece);
                if (merged != null) {
                    result.set(result.size() - 1, merged);
                    continue;
                }
            }
            result.add(piece);
        }
        return result;
    }

    private void readLargeInto(long offset, byte[] target, int targetOffset, int requestedLength) {
        try {
            long cursor = 0;
            int written = 0;
            for (Piece piece : pieces) {
                long pieceEnd = cursor + piece.length();
                if (pieceEnd <= offset) {
                    cursor = pieceEnd;
                    continue;
                }
                if (cursor >= offset + requestedLength) {
                    break;
                }
                long pieceOffset = Math.max(0, offset - cursor);
                int count = (int) Math.min(piece.length() - pieceOffset, requestedLength - written);
                if (count > 0) {
                    piece.read(pieceOffset, target, targetOffset + written, count, this);
                    written += count;
                }
                cursor = pieceEnd;
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void readOriginal(long position, byte[] target, int targetOffset, int count) throws IOException {
        int copied = 0;
        while (copied < count) {
            long absolute = position + copied;
            long page = absolute / PAGE_SIZE;
            int pageOffset = (int) (absolute % PAGE_SIZE);
            warmOriginalPages(page);
            byte[] pageBytes = originalPageCache.get(page);
            if (pageBytes == null) {
                pageBytes = loadOriginalPage(page);
                originalPageCache.put(page, pageBytes);
            }
            int chunk = Math.min(count - copied, pageBytes.length - pageOffset);
            if (chunk <= 0) {
                break;
            }
            System.arraycopy(pageBytes, pageOffset, target, targetOffset + copied, chunk);
            copied += chunk;
        }
    }

    private void warmOriginalPages(long page) throws IOException {
        for (long candidate = Math.max(0, page - 1); candidate <= page + 1; candidate++) {
            if (!originalPageCache.containsKey(candidate)) {
                originalPageCache.put(candidate, loadOriginalPage(candidate));
            }
        }
    }

    private byte[] loadOriginalPage(long page) throws IOException {
        long position = page * PAGE_SIZE;
        if (position >= sourceChannel.size()) {
            return new byte[0];
        }
        int length = (int) Math.min(PAGE_SIZE, sourceChannel.size() - position);
        byte[] data = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
            int read = sourceChannel.read(buffer, position + buffer.position());
            if (read < 0) {
                break;
            }
        }
        return data;
    }

    private FindResult findAllInMemory(byte[] pattern, long fromOffset, int limit, BooleanSupplier shouldContinue, ProgressReporter progressReporter) {
        List<Long> offsets = new ArrayList<>();
        int start = (int) Math.max(0, fromOffset);
        int[] failure = kmpFailure(pattern);
        int matched = 0;
        int total = Math.max(0, memoryData.length - start);
        reportProgress(progressReporter, 0, total);
        for (int i = start; i < memoryData.length && shouldContinue.getAsBoolean(); i++) {
            while (matched > 0 && memoryData[i] != pattern[matched]) {
                matched = failure[matched - 1];
            }
            if (memoryData[i] == pattern[matched]) {
                matched++;
                if (matched == pattern.length) {
                    offsets.add((long) i - pattern.length + 1);
                    if (offsets.size() >= limit) {
                        return new FindResult(offsets, true);
                    }
                    matched = failure[matched - 1];
                }
            }
            if ((i - start) % PAGE_SIZE == 0) {
                reportProgress(progressReporter, i - start, total);
            }
        }
        reportProgress(progressReporter, total, total);
        return new FindResult(offsets, false);
    }

    private FindResult findAllStreaming(byte[] pattern, long fromOffset, int limit, BooleanSupplier shouldContinue, ProgressReporter progressReporter) {
        List<Long> offsets = new ArrayList<>();
        long start = Math.max(0, fromOffset);
        if (start > length - pattern.length) {
            return new FindResult(offsets, false);
        }
        int[] failure = kmpFailure(pattern);
        byte[] buffer = new byte[PAGE_SIZE];
        long cursor = start;
        int matched = 0;
        long total = length - start;
        reportProgress(progressReporter, 0, total);
        while (cursor < length && shouldContinue.getAsBoolean()) {
            int count = (int) Math.min(buffer.length, length - cursor);
            readLargeInto(cursor, buffer, 0, count);
            for (int i = 0; i < count; i++) {
                while (matched > 0 && buffer[i] != pattern[matched]) {
                    matched = failure[matched - 1];
                }
                if (buffer[i] == pattern[matched]) {
                    matched++;
                    if (matched == pattern.length) {
                        offsets.add(cursor + i - pattern.length + 1);
                        if (offsets.size() >= limit) {
                            return new FindResult(offsets, true);
                        }
                        matched = failure[matched - 1];
                    }
                }
            }
            cursor += count;
            reportProgress(progressReporter, cursor - start, total);
        }
        return new FindResult(offsets, false);
    }

    private byte[] previewBytes(long offset, long requestedLength) {
        if (requestedLength <= 0 || offset < 0 || offset >= length) {
            return new byte[0];
        }
        return read(offset, (int) Math.min(OPERATION_PREVIEW_BYTES, requestedLength));
    }

    private static byte[] preview(byte[] values) {
        return Arrays.copyOf(values, Math.min(values.length, OPERATION_PREVIEW_BYTES));
    }

    private static byte[] fillPreview(int value, long length) {
        byte[] preview = new byte[(int) Math.min(length, OPERATION_PREVIEW_BYTES)];
        Arrays.fill(preview, (byte) value);
        return preview;
    }

    private void recordOperation(OperationType type, long offset, long beforeLength, long afterLength, byte[] before, byte[] after,
                                 byte[] afterBytes, Integer fillValue, String importPath) {
        operations.add(new OperationRecord(++operationSequence, System.currentTimeMillis(), type, offset, beforeLength, afterLength,
                before, after, afterBytes, fillValue, importPath));
        revision++;
    }

    private static void reportProgress(ProgressReporter progressReporter, long processed, long total) {
        if (progressReporter != null) {
            progressReporter.report(processed, total);
        }
    }

    private void reloadFromOpenSource() throws IOException {
        length = sourceChannel.size();
        pieces.clear();
        memoryData = null;
        largeMode = length > SMALL_FILE_LIMIT;
        if (largeMode) {
            if (length > 0) {
                pieces.add(new Piece(Source.ORIGINAL, 0, length, 0));
            }
        } else {
            memoryData = readSourceIntoMemory();
        }
        originalPageCache.clear();
        revision++;
    }

    private void reopenSource() throws IOException {
        sourceChannel.close();
        sourceChannel = FileChannel.open(sourcePath, StandardOpenOption.READ);
    }

    private static int[] kmpFailure(byte[] pattern) {
        int[] failure = new int[pattern.length];
        int matched = 0;
        for (int i = 1; i < pattern.length; i++) {
            while (matched > 0 && pattern[i] != pattern[matched]) {
                matched = failure[matched - 1];
            }
            if (pattern[i] == pattern[matched]) {
                failure[i] = ++matched;
            }
        }
        return failure;
    }

    private enum Source {
        ORIGINAL,
        ADD,
        FILL
    }

    enum OperationType {
        OVERWRITE,
        FILL,
        INSERT,
        INSERT_FILL,
        IMPORT_FILE,
        DELETE
    }

    record State(boolean largeMode, byte[] memoryData, List<Piece> pieces, List<OperationRecord> operations,
                 long length, long revision, long operationSequence) {
    }

    record FindResult(List<Long> offsets, boolean capped) {
    }

    @FunctionalInterface
    interface ProgressReporter {
        void report(long processed, long total);
    }

    record OperationRecord(long sequence, long createdAtMillis, OperationType type, long offset, long beforeLength, long afterLength,
                           byte[] beforePreview, byte[] afterPreview, byte[] afterBytes, Integer fillValue, String importPath) {
        OperationRecord {
            beforePreview = beforePreview == null ? new byte[0] : Arrays.copyOf(beforePreview, beforePreview.length);
            afterPreview = afterPreview == null ? new byte[0] : Arrays.copyOf(afterPreview, afterPreview.length);
            afterBytes = afterBytes == null ? null : Arrays.copyOf(afterBytes, afterBytes.length);
        }
    }

    private record Piece(Source source, long start, long length, int fillValue) {
        Piece slice(long relativeStart, long sliceLength) {
            return source == Source.FILL
                    ? new Piece(source, start, sliceLength, fillValue)
                    : new Piece(source, start + relativeStart, sliceLength, fillValue);
        }

        Piece merge(Piece other) {
            if (source != other.source || fillValue != other.fillValue) {
                return null;
            }
            if (source == Source.FILL) {
                return new Piece(source, start, length + other.length, fillValue);
            }
            if (start + length == other.start) {
                return new Piece(source, start, length + other.length, fillValue);
            }
            return null;
        }

        void read(long relativeOffset, byte[] target, int targetOffset, int count, HexDocument document) throws IOException {
            if (source == Source.FILL) {
                Arrays.fill(target, targetOffset, targetOffset + count, (byte) fillValue);
                return;
            }
            if (source == Source.ORIGINAL) {
                document.readOriginal(start + relativeOffset, target, targetOffset, count);
                return;
            }
            ByteBuffer buffer = ByteBuffer.wrap(target, targetOffset, count);
            int initialPosition = buffer.position();
            long position = start + relativeOffset;
            while (buffer.hasRemaining()) {
                int read = document.tempChannel.read(buffer, position + buffer.position() - initialPosition);
                if (read < 0) {
                    break;
                }
            }
        }
    }
}
