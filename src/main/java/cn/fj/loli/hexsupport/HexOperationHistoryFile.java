package cn.fj.loli.hexsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HexOperationHistoryFile {
    private static final String DATA_START = "Hex Support Operation History Data v1";
    private static final String DATA_END = "End Hex Support Operation History Data";
    private HexOperationHistoryFile() {
    }

    static String machineSection(Path sourcePath, List<PersistedOperation> operations) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append('\n').append(DATA_START).append('\n');
        builder.append("base")
                .append('\t').append("size=").append(Files.size(sourcePath))
                .append('\t').append("modified=").append(Files.getLastModifiedTime(sourcePath).toMillis())
                .append('\n');
        List<PersistedOperation> sorted = new ArrayList<>(operations);
        sorted.sort(Comparator.comparingLong(operation -> operation.record().sequence()));
        for (PersistedOperation operation : sorted) {
            HexDocument.OperationRecord record = operation.record();
            builder.append("op")
                    .append('\t').append("sequence=").append(record.sequence())
                    .append('\t').append("created=").append(record.createdAtMillis())
                    .append('\t').append("undone=").append(operation.undone())
                    .append('\t').append("type=").append(record.type().name())
                    .append('\t').append("offset=").append(record.offset())
                    .append('\t').append("beforeLength=").append(record.beforeLength())
                    .append('\t').append("afterLength=").append(record.afterLength())
                    .append('\t').append("beforePreview=").append(encodeBytes(record.beforePreview()))
                    .append('\t').append("afterPreview=").append(encodeBytes(record.afterPreview()));
            if (record.afterBytes() != null) {
                builder.append('\t').append("after=").append(encodeBytes(record.afterBytes()));
            }
            if (record.fillValue() != null) {
                builder.append('\t').append("fillValue=").append(record.fillValue());
            }
            if (record.importPath() != null) {
                builder.append('\t').append("importPath=").append(encodeString(record.importPath()));
            }
            builder.append('\n');
        }
        builder.append(DATA_END).append('\n');
        return builder.toString();
    }

    static LoadedHistory read(Path historyPath, Path sourcePath) throws IOException {
        List<String> lines = Files.readAllLines(historyPath, StandardCharsets.UTF_8);
        int dataStart = lines.indexOf(DATA_START);
        if (dataStart >= 0) {
            return readMachineSection(lines, dataStart, sourcePath);
        }
        LoadedHistory legacy = readLegacy(lines);
        boolean historyIsNewerThanSource = Files.getLastModifiedTime(historyPath).toMillis()
                >= Files.getLastModifiedTime(sourcePath).toMillis();
        return new LoadedHistory(legacy.baseMatches() && historyIsNewerThanSource, legacy.operations());
    }

    private static LoadedHistory readMachineSection(List<String> lines, int dataStart, Path sourcePath) throws IOException {
        long baseSize = -1;
        long baseModified = -1;
        List<PersistedOperation> operations = new ArrayList<>();
        for (int i = dataStart + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (DATA_END.equals(line)) {
                break;
            }
            Map<String, String> values = parseFields(line);
            if (line.startsWith("base\t")) {
                baseSize = parseLong(values, "size", -1);
                baseModified = parseLong(values, "modified", -1);
            } else if (line.startsWith("op\t")) {
                operations.add(readMachineOperation(values));
            }
        }
        boolean baseMatches = baseSize == Files.size(sourcePath)
                && baseModified == Files.getLastModifiedTime(sourcePath).toMillis();
        operations = mergeDisplayOperations(lines.subList(0, dataStart), operations);
        operations.sort(Comparator.comparingLong(operation -> operation.record().sequence()));
        return new LoadedHistory(baseMatches, operations);
    }

    private static PersistedOperation readMachineOperation(Map<String, String> values) {
        HexDocument.OperationType type = HexDocument.OperationType.valueOf(required(values, "type"));
        HexDocument.OperationRecord record = new HexDocument.OperationRecord(
                parseLong(values, "sequence", 0),
                parseLong(values, "created", System.currentTimeMillis()),
                type,
                parseLong(values, "offset", 0),
                parseLong(values, "beforeLength", 0),
                parseLong(values, "afterLength", 0),
                decodeBytes(values.get("beforePreview")),
                decodeBytes(values.get("afterPreview")),
                values.containsKey("after") ? decodeBytes(values.get("after")) : null,
                values.containsKey("fillValue") ? (int) parseLong(values, "fillValue", 0) : null,
                values.containsKey("importPath") ? decodeString(values.get("importPath")) : null);
        return new PersistedOperation(record, Boolean.parseBoolean(values.get("undone")));
    }

    private static LoadedHistory readLegacy(List<String> lines) {
        List<PersistedOperation> operations = new ArrayList<>();
        boolean sawOperation = false;
        for (String line : lines) {
            if (!line.contains("  #") || !line.contains("  @0x")) {
                continue;
            }
            if (isUndoneLine(line)) {
                continue;
            }
            sawOperation = true;
            HexDocument.OperationRecord record = parseLegacyRecord(line);
            if (record == null) {
                return LoadedHistory.empty();
            }
            operations.add(new PersistedOperation(record, false));
        }
        if (!sawOperation) {
            return LoadedHistory.empty();
        }
        operations.sort(Comparator.comparingLong(operation -> operation.record().sequence()));
        return new LoadedHistory(true, operations);
    }

    private static List<PersistedOperation> mergeDisplayOperations(List<String> lines, List<PersistedOperation> machineOperations) {
        Map<Long, PersistedOperation> bySequence = new LinkedHashMap<>();
        for (PersistedOperation operation : machineOperations) {
            bySequence.put(operation.record().sequence(), operation);
        }
        for (String line : lines) {
            if (!line.contains("  #") || !line.contains("  @0x")) {
                continue;
            }
            boolean undone = isUndoneLine(line);
            HexDocument.OperationRecord displayRecord = parseLegacyRecord(line);
            if (displayRecord == null) {
                continue;
            }
            PersistedOperation existing = bySequence.get(displayRecord.sequence());
            if (existing == null) {
                bySequence.put(displayRecord.sequence(), new PersistedOperation(displayRecord, undone));
            } else if (undone && !existing.undone()) {
                bySequence.put(displayRecord.sequence(), new PersistedOperation(existing.record(), true));
            }
        }
        return new ArrayList<>(bySequence.values());
    }

    private static boolean isUndoneLine(String line) {
        return line.contains("  Undone  ") || line.contains("  已撤销  ");
    }

    private static HexDocument.OperationRecord parseLegacyRecord(String line) {
        int sequenceMarker = line.indexOf("  #");
        int locationMarker = line.indexOf("  @0x", sequenceMarker);
        if (sequenceMarker < 0 || locationMarker < 0) {
            return null;
        }
        long createdAt = parseLegacyTime(line);
        int sequenceStart = sequenceMarker + 3;
        int sequenceEnd = line.indexOf("  ", sequenceStart);
        if (sequenceEnd < 0) {
            return null;
        }
        long sequence = parseLong(line.substring(sequenceStart, sequenceEnd), -1);
        if (sequence < 0) {
            return null;
        }
        String typeText = line.substring(sequenceEnd + 2, locationMarker).trim();
        if (typeText.startsWith("Undone  ")) {
            typeText = typeText.substring("Undone  ".length()).trim();
        } else if (typeText.startsWith("已撤销  ")) {
            typeText = typeText.substring("已撤销  ".length()).trim();
        }
        HexDocument.OperationType type = legacyType(typeText);
        if (type == null) {
            return null;
        }
        int offsetStart = locationMarker + 5;
        int offsetEnd = line.indexOf("  ", offsetStart);
        if (offsetEnd < 0) {
            return null;
        }
        long offset = parseHexLong(line.substring(offsetStart, offsetEnd), -1);
        int arrow = line.indexOf(" -> ", offsetEnd);
        if (offset < 0 || arrow < 0) {
            return null;
        }
        long beforeLength = parseLong(line.substring(offsetEnd + 2, arrow).trim(), -1);
        int afterEnd = nextMetadataIndex(line, arrow + 4);
        long afterLength = parseLong(line.substring(arrow + 4, afterEnd).trim(), -1);
        if (beforeLength < 0 || afterLength < 0) {
            return null;
        }
        byte[] beforePreview = legacyPreview(line, "  before: ", "  after: ");
        byte[] afterPreview = legacyPreview(line, "  after: ", null);
        byte[] afterBytes = null;
        Integer fillValue = null;
        if (type == HexDocument.OperationType.OVERWRITE || type == HexDocument.OperationType.INSERT || type == HexDocument.OperationType.IMPORT_FILE) {
            if (afterPreview.length != afterLength) {
                return null;
            }
            afterBytes = afterPreview;
        } else if (type == HexDocument.OperationType.FILL || type == HexDocument.OperationType.INSERT_FILL) {
            if (afterLength <= 0 || afterPreview.length == 0) {
                return null;
            }
            fillValue = afterPreview[0] & 0xFF;
        }
        return new HexDocument.OperationRecord(sequence, createdAt, type, offset, beforeLength, afterLength,
                beforePreview, afterPreview, afterBytes, fillValue, null);
    }

    private static int nextMetadataIndex(String line, int start) {
        int before = line.indexOf("  before: ", start);
        int after = line.indexOf("  after: ", start);
        if (before < 0) {
            return after < 0 ? line.length() : after;
        }
        if (after < 0) {
            return before;
        }
        return Math.min(before, after);
    }

    private static byte[] legacyPreview(String line, String key, String stopKey) {
        int start = line.indexOf(key);
        if (start < 0) {
            return new byte[0];
        }
        start += key.length();
        int end = stopKey == null ? line.length() : line.indexOf(stopKey, start);
        if (end < 0) {
            end = line.length();
        }
        return parseHexBytes(line.substring(start, end));
    }

    private static long parseLegacyTime(String line) {
        if (line.length() < 19) {
            return System.currentTimeMillis();
        }
        try {
            LocalDateTime local = LocalDateTime.parse(line.substring(0, 19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return Instant.now().toEpochMilli();
        }
    }

    private static HexDocument.OperationType legacyType(String value) {
        return switch (value) {
            case "Overwrite", "覆盖" -> HexDocument.OperationType.OVERWRITE;
            case "Fill", "填充" -> HexDocument.OperationType.FILL;
            case "Insert", "插入" -> HexDocument.OperationType.INSERT;
            case "Insert Fill", "插入填充" -> HexDocument.OperationType.INSERT_FILL;
            case "Import File", "导入文件" -> HexDocument.OperationType.IMPORT_FILE;
            case "Delete", "删除" -> HexDocument.OperationType.DELETE;
            default -> null;
        };
    }

    private static Map<String, String> parseFields(String line) {
        Map<String, String> values = new HashMap<>();
        String[] parts = line.split("\t");
        for (int i = 1; i < parts.length; i++) {
            int separator = parts[i].indexOf('=');
            if (separator > 0) {
                values.put(parts[i].substring(0, separator), parts[i].substring(separator + 1));
            }
        }
        return values;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing operation history field: " + key);
        }
        return value;
    }

    private static long parseLong(Map<String, String> values, String key, long fallback) {
        return parseLong(values.get(key), fallback);
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseHexLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseUnsignedLong(value.trim(), 16);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static byte[] parseHexBytes(String value) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.digit(ch, 16) >= 0) {
                hex.append(ch);
            }
        }
        if (hex.length() % 2 != 0) {
            return new byte[0];
        }
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static String encodeBytes(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes == null ? new byte[0] : bytes);
    }

    private static byte[] decodeBytes(String value) {
        if (value == null || value.isEmpty()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(value);
    }

    private static String encodeString(String value) {
        return encodeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeString(String value) {
        return new String(decodeBytes(value), StandardCharsets.UTF_8);
    }

    record PersistedOperation(HexDocument.OperationRecord record, boolean undone) {
    }

    record LoadedHistory(boolean baseMatches, List<PersistedOperation> operations) {
        static LoadedHistory empty() {
            return new LoadedHistory(false, List.of());
        }
    }
}
