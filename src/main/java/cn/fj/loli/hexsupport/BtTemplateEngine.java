package cn.fj.loli.hexsupport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Sandboxed interpreter for the read-only subset of 010 Editor Binary Templates used by common
 * structure-analysis templates. The runtime deliberately exposes no filesystem, process, network,
 * external-library, or data-writing functions.
 */
final class BtTemplateEngine {
    private static final long MAX_STEPS = 5_000_000L;
    private static final int MAX_CALL_DEPTH = 128;
    private static final int MAX_NODES = 250_000;
    private static final int MAX_STRING_BYTES = 4 * 1024 * 1024;

    BtResult run(Path template, BinaryDataSource input, BooleanSupplier canceled) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(input, "input");
        try {
            String source = readTemplateSource(template);
            return run(template, source, input, canceled);
        } catch (IOException exception) {
            return new BtResult(template, input.revision(), List.of(),
                    List.of(new BtDiagnostic(BtDiagnostic.Severity.ERROR, 0, 0,
                            "Unable to read template: " + exception.getMessage())), List.of());
        }
    }

    static String readTemplateSource(Path template) throws IOException {
        byte[] bytes = Files.readAllBytes(template);
        if (startsWith(bytes, 0xef, 0xbb, 0xbf)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, 0xff, 0xfe)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, 0xfe, 0xff)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ignored) {
            // Older repository submissions commonly use a Windows single-byte code page. The
            // grammar itself is ASCII, so preserving every byte is safer than rejecting the file.
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xff) != prefix[i]) return false;
        }
        return true;
    }

    BtResult run(Path template, String source, BinaryDataSource input, BooleanSupplier canceled) {
        List<BtDiagnostic> diagnostics = new ArrayList<>();
        List<String> output = new ArrayList<>();
        try {
            Parser parser = new Parser(source);
            Program program = parser.parseProgram();
            Runtime runtime = new Runtime(input, canceled == null ? () -> false : canceled, diagnostics, output);
            List<MutableNode> nodes = runtime.execute(program);
            return new BtResult(template, input.revision(), freeze(nodes), diagnostics, output);
        } catch (ParseFailure failure) {
            diagnostics.add(new BtDiagnostic(BtDiagnostic.Severity.ERROR, failure.line, failure.column, failure.getMessage()));
            return new BtResult(template, input.revision(), List.of(), diagnostics, output);
        } catch (CanceledFailure ignored) {
            diagnostics.add(new BtDiagnostic(BtDiagnostic.Severity.INFO, 0, 0, "Template execution canceled."));
            return new BtResult(template, input.revision(), List.of(), diagnostics, output);
        } catch (RuntimeFailure failure) {
            diagnostics.add(new BtDiagnostic(BtDiagnostic.Severity.ERROR, failure.line, failure.column, failure.getMessage()));
            return new BtResult(template, input.revision(), freeze(failure.partialNodes), diagnostics, output);
        } catch (RuntimeException failure) {
            diagnostics.add(new BtDiagnostic(BtDiagnostic.Severity.ERROR, 0, 0,
                    "Template runtime failed: " + rootMessage(failure)));
            return new BtResult(template, input.revision(), List.of(), diagnostics, output);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static List<BtNode> freeze(List<MutableNode> nodes) {
        List<BtNode> result = new ArrayList<>(nodes.size());
        for (MutableNode node : nodes) {
            result.add(new BtNode(node.name, node.type, node.value, node.offset, node.size, node.format,
                    node.foregroundColor, node.backgroundColor, node.comment, freeze(node.children)));
        }
        return result;
    }

    // --- Lexer -------------------------------------------------------------------------------

    private enum TokenKind { IDENTIFIER, NUMBER, STRING, SYMBOL, EOF }

    private record Token(TokenKind kind, String text, int line, int column) {
        boolean is(String expected) {
            return kind != TokenKind.STRING && text.equals(expected);
        }
    }

    private static final class Lexer {
        private static final String[] DOUBLE_SYMBOLS = {
                "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=",
                "&=", "|=", "^=", "<<", ">>"
        };
        private static final String[] TRIPLE_SYMBOLS = { "<<=", ">>=" };

        private final String source;
        private int index;
        private int line = 1;
        private int column = 1;

        private Lexer(String source) {
            this.source = source == null ? "" : source;
        }

        List<Token> lex() {
            List<Token> tokens = new ArrayList<>();
            while (true) {
                skipTrivia();
                if (index >= source.length()) {
                    tokens.add(new Token(TokenKind.EOF, "<eof>", line, column));
                    return tokens;
                }
                char c = source.charAt(index);
                int tokenLine = line;
                int tokenColumn = column;
                if ((c == 'L' || c == 'u' || c == 'U') && index + 1 < source.length()
                        && (source.charAt(index + 1) == '"' || source.charAt(index + 1) == '\'')) {
                    advance();
                    tokens.add(readString(source.charAt(index), tokenLine, tokenColumn));
                } else if (Character.isLetter(c) || c == '_' || c == '$') {
                    int start = index;
                    advance();
                    while (index < source.length()) {
                        char next = source.charAt(index);
                        if (!Character.isLetterOrDigit(next) && next != '_' && next != '$') {
                            break;
                        }
                        advance();
                    }
                    tokens.add(new Token(TokenKind.IDENTIFIER, source.substring(start, index), tokenLine, tokenColumn));
                } else if (Character.isDigit(c)) {
                    int start = index;
                    advance();
                    if (c == '0' && index < source.length() && (source.charAt(index) == 'x' || source.charAt(index) == 'X')) {
                        advance();
                        while (index < source.length() && isHex(source.charAt(index))) {
                            advance();
                        }
                    } else if (c == '0' && index < source.length() && (source.charAt(index) == 'b' || source.charAt(index) == 'B')) {
                        advance();
                        while (index < source.length() && (source.charAt(index) == '0' || source.charAt(index) == '1')) advance();
                    } else {
                        int hexEnd = index;
                        while (hexEnd < source.length() && isHex(source.charAt(hexEnd))) hexEnd++;
                        if (hexEnd < source.length() && (source.charAt(hexEnd) == 'h' || source.charAt(hexEnd) == 'H')) {
                            while (index <= hexEnd) advance();
                        } else {
                            while (index < source.length() && Character.isDigit(source.charAt(index))) advance();
                        }
                        if (index < source.length() && source.charAt(index) == '.') {
                            advance();
                            while (index < source.length() && Character.isDigit(source.charAt(index))) advance();
                        }
                        if (index < source.length() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                            advance();
                            if (index < source.length() && (source.charAt(index) == '+' || source.charAt(index) == '-')) advance();
                            while (index < source.length() && Character.isDigit(source.charAt(index))) advance();
                        }
                    }
                    while (index < source.length() && "uUlLfF".indexOf(source.charAt(index)) >= 0) {
                        advance();
                    }
                    tokens.add(new Token(TokenKind.NUMBER, source.substring(start, index), tokenLine, tokenColumn));
                } else if (c == '"' || c == '\'') {
                    tokens.add(readString(c, tokenLine, tokenColumn));
                } else {
                    String symbol = null;
                    for (String candidate : TRIPLE_SYMBOLS) {
                        if (source.startsWith(candidate, index)) {
                            symbol = candidate;
                            break;
                        }
                    }
                    if (symbol == null) for (String candidate : DOUBLE_SYMBOLS) {
                        if (source.startsWith(candidate, index)) {
                            symbol = candidate;
                            break;
                        }
                    }
                    if (symbol != null) {
                        advance();
                        advance();
                        if (symbol.length() == 3) advance();
                    } else {
                        symbol = String.valueOf(c);
                        advance();
                    }
                    tokens.add(new Token(TokenKind.SYMBOL, symbol, tokenLine, tokenColumn));
                }
            }
        }

        private Token readString(char quote, int tokenLine, int tokenColumn) {
            advance();
            StringBuilder value = new StringBuilder();
            boolean closed = false;
            while (index < source.length()) {
                char c = source.charAt(index);
                advance();
                if (c == quote) {
                    closed = true;
                    break;
                }
                if (c == '\\' && index < source.length()) {
                    char escaped = source.charAt(index);
                    advance();
                    value.append(switch (escaped) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '0' -> '\0';
                        case '\\' -> '\\';
                        case '\'' -> '\'';
                        case '"' -> '"';
                        default -> escaped;
                    });
                } else {
                    value.append(c);
                }
            }
            if (!closed) {
                throw new ParseFailure(tokenLine, tokenColumn, "Unterminated string literal.");
            }
            if (quote == '\'' && value.length() == 1) {
                return new Token(TokenKind.NUMBER, Integer.toString(value.charAt(0)), tokenLine, tokenColumn);
            }
            return new Token(TokenKind.STRING, value.toString(), tokenLine, tokenColumn);
        }

        private void skipTrivia() {
            while (index < source.length()) {
                char c = source.charAt(index);
                if (Character.isWhitespace(c)) {
                    advance();
                    continue;
                }
                if (c == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                    while (index < source.length() && source.charAt(index) != '\n') {
                        advance();
                    }
                    continue;
                }
                if (c == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                    advance();
                    advance();
                    while (index + 1 < source.length() && !(source.charAt(index) == '*' && source.charAt(index + 1) == '/')) {
                        advance();
                    }
                    if (index + 1 < source.length()) {
                        advance();
                        advance();
                    }
                    continue;
                }
                if (c == '#') {
                    while (index < source.length() && source.charAt(index) != '\n') {
                        advance();
                    }
                    continue;
                }
                if (c == '\\' && index + 1 < source.length()
                        && (source.charAt(index + 1) == '\n' || source.charAt(index + 1) == '\r')) {
                    advance();
                    if (index < source.length() && source.charAt(index) == '\r') advance();
                    if (index < source.length() && source.charAt(index) == '\n') advance();
                    continue;
                }
                return;
            }
        }

        private void advance() {
            if (index >= source.length()) {
                return;
            }
            char c = source.charAt(index++);
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }

        private static boolean isHex(char c) {
            return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }
    }

    // --- Syntax tree -------------------------------------------------------------------------

    private record Program(List<Statement> statements) {}

    private sealed interface Statement permits BlockStatement, TypeStatement, FunctionStatement,
            VariableStatement, ExpressionStatement, IfStatement, WhileStatement, ForStatement,
            DoWhileStatement, SwitchStatement, ReturnStatement, BreakStatement, ContinueStatement {}

    private record BlockStatement(List<Statement> statements) implements Statement {}
    private record TypeStatement(TypeDefinition definition) implements Statement {}
    private record FunctionStatement(FunctionDefinition definition) implements Statement {}
    private record VariableStatement(boolean local, String type, String name, Expression arrayLength, Expression bitWidth,
                                     List<Expression> constructorArguments, Attributes attributes,
                                     Expression initializer, int line, int column) implements Statement {}
    private record ExpressionStatement(Expression expression) implements Statement {}
    private record IfStatement(Expression condition, Statement thenBranch, Statement elseBranch) implements Statement {}
    private record WhileStatement(Expression condition, Statement body, int line, int column) implements Statement {}
    private record ForStatement(Statement initializer, Expression condition, Expression update,
                                Statement body, int line, int column) implements Statement {}
    private record DoWhileStatement(Statement body, Expression condition, int line, int column) implements Statement {}
    private record SwitchStatement(Expression expression, List<SwitchSection> sections) implements Statement {}
    private record SwitchSection(List<Expression> labels, boolean defaultSection, List<Statement> statements) {}
    private record ReturnStatement(Expression value) implements Statement {}
    private record BreakStatement() implements Statement {}
    private record ContinueStatement() implements Statement {}

    private sealed interface TypeDefinition permits AggregateDefinition, EnumDefinition, AliasDefinition, ArrayAliasDefinition {}
    private enum AggregateKind { STRUCT, UNION }
    private record AggregateDefinition(String name, AggregateKind kind, List<Parameter> parameters,
                                       List<Statement> body, Attributes attributes) implements TypeDefinition {}
    private record EnumDefinition(String name, String baseType, LinkedHashMap<String, Expression> constants)
            implements TypeDefinition {}
    private record AliasDefinition(String name, String targetType) implements TypeDefinition {}
    private record ArrayAliasDefinition(String name, String targetType, Expression length, Attributes attributes)
            implements TypeDefinition {}
    private record FunctionDefinition(String returnType, String name, List<Parameter> parameters,
                                      BlockStatement body, int line, int column) {}
    private record Parameter(String type, String name, boolean reference) {}
    private record Attributes(Map<String, Expression> values) {
        private static final Attributes EMPTY = new Attributes(Map.of());
        Expression get(String key) { return values.get(key); }
        Attributes merge(Attributes overriding) {
            if (values.isEmpty()) return overriding;
            if (overriding.values.isEmpty()) return this;
            Map<String, Expression> merged = new LinkedHashMap<>(values);
            merged.putAll(overriding.values);
            return new Attributes(merged);
        }
    }

    private sealed interface Expression permits LiteralExpression, InitializerListExpression, SequenceExpression, VariableExpression,
            UnaryExpression, PrefixExpression, CastExpression,
            BinaryExpression, ConditionalExpression, CallExpression, MemberExpression, IndexExpression,
            AssignmentExpression, PostfixExpression {}
    private record LiteralExpression(Object value) implements Expression {}
    private record InitializerListExpression(List<Expression> values) implements Expression {}
    private record SequenceExpression(List<Expression> values) implements Expression {}
    private record VariableExpression(String name, int line, int column) implements Expression {}
    private record UnaryExpression(String operator, Expression expression) implements Expression {}
    private record PrefixExpression(String operator, Expression expression, int line, int column) implements Expression {}
    private record CastExpression(String type, Expression expression) implements Expression {}
    private record BinaryExpression(Expression left, String operator, Expression right) implements Expression {}
    private record ConditionalExpression(Expression condition, Expression whenTrue, Expression whenFalse) implements Expression {}
    private record CallExpression(String name, List<Expression> arguments, int line, int column) implements Expression {}
    private record MemberExpression(Expression target, String member, int line, int column) implements Expression {}
    private record IndexExpression(Expression target, Expression index, int line, int column) implements Expression {}
    private record AssignmentExpression(Expression target, String operator, Expression value, int line, int column) implements Expression {}
    private record PostfixExpression(Expression target, String operator, int line, int column) implements Expression {}

    // --- Parser ------------------------------------------------------------------------------

    private static final class Parser {
        private final List<Token> tokens;
        private final Map<String, Boolean> typeNames = new HashMap<>();
        private int position;
        private int anonymousEnum;
        private int anonymousAggregate;
        private int anonymousField;
        private int functionDepth;

        private Parser(String source) {
            tokens = new Lexer(preprocess(source)).lex();
            for (String primitive : List.of("void", "char", "byte", "uchar", "ubyte", "int8", "uint8",
                    "short", "ushort", "int16", "uint16", "int", "uint", "int32", "uint32",
                    "long", "ulong", "int64", "uint64", "float", "double", "string", "wstring",
                    "wchar", "wchar_t", "quad", "uquad", "CHAR", "BYTE", "UCHAR", "UBYTE",
                    "SHORT", "USHORT", "WORD", "INT", "UINT", "LONG", "ULONG", "DWORD",
                    "INT8", "UINT8", "INT16", "UINT16", "INT32", "UINT32", "INT64", "UINT64",
                    "QUAD", "UQUAD", "QWORD", "bool", "boolean", "BOOL", "signed", "unsigned",
                    "__int8", "__int16", "__int32", "__int64", "int8_t", "uint8_t", "int16_t",
                    "uint16_t", "int32_t", "uint32_t", "int64_t", "uint64_t", "TKeywordList",
                    "TFindResults", "TFileList", "TStringList", "TTemplate", "TDateTime", "hfloat",
                    "DOSDATE", "DOSTIME", "FILETIME", "OLETIME", "time_t", "time64_t", "GUID",
                    "Opcode", "FLOAT", "DOUBLE")) {
                typeNames.put(primitive, true);
            }
        }

        private static String preprocess(String source) {
            if (source == null || source.isEmpty()) return "";
            source = source.replace("\r\n", "\n").replace('\r', '\n')
                    .replace("\u00b4", "").replace("\u00de", "").replace("\u3163", "").replace(";?", ";")
                    .replaceAll("\\\\\\r?\\n\\s*", " ");
            StringBuilder result = new StringBuilder(source.length());
            Pattern define = Pattern.compile("^\\s*#\\s*define\\s+([A-Za-z_]\\w*)\\s+(.+?)\\s*$");
            for (String line : source.split("(?<=\\n)", -1)) {
                String content = line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
                Matcher match = define.matcher(content);
                if (match.matches()) {
                    String value = match.group(2).replaceFirst("\\s*//.*$", "").trim();
                    if (value.matches("(?i)(?:(?:unsigned|signed)\\s+)?(?:char|short|int|long|int64|float|double|hfloat)")) {
                        result.append("typedef ").append(value).append(' ').append(match.group(1)).append(';');
                    } else {
                        result.append("local int ").append(match.group(1)).append(" = ")
                                .append(value.isEmpty() ? "1" : value).append(';');
                    }
                    if (line.endsWith("\n")) result.append('\n');
                } else {
                    result.append(line);
                }
            }
            return result.toString();
        }

        Program parseProgram() {
            List<Statement> statements = new ArrayList<>();
            while (!peek().kind().equals(TokenKind.EOF)) {
                statements.add(parseStatement(true));
            }
            return new Program(statements);
        }

        private Statement parseStatement(boolean topLevel) {
            if (match(";")) return new BlockStatement(List.of());
            if (match("?")) return new BlockStatement(List.of());
            if (match("{")) return parseBlockAfterOpen();
            if (peek().is("<")) {
                parseAttributes();
                match(";");
                return new BlockStatement(List.of());
            }
            if (matchIdentifier("typedef")) return parseTypedef();
            if (peek().is("local") && (peek(1).is("struct") || peek(1).is("union"))
                    && (peek(2).is("{") || peek(2).is("("))) {
                consume();
                return parseAnonymousAggregateVariable(true);
            }
            if ((peek().is("struct") || peek().is("union")) && peek(1).kind() == TokenKind.IDENTIFIER
                    && peek(2).is(";")) {
                Token kind = consume();
                String name = consume().text();
                consume();
                typeNames.put(name, true);
                return new TypeStatement(new AggregateDefinition(name,
                        kind.is("struct") ? AggregateKind.STRUCT : AggregateKind.UNION,
                        List.of(), List.of(), Attributes.EMPTY));
            }
            if (peek().is("struct") && peek(1).kind() == TokenKind.IDENTIFIER
                    && (peek(2).is("{") || peek(2).is("("))) {
                consume();
                return parseNamedAggregate(AggregateKind.STRUCT);
            }
            if (peek().is("union") && peek(1).kind() == TokenKind.IDENTIFIER
                    && (peek(2).is("{") || peek(2).is("("))) {
                consume();
                return parseNamedAggregate(AggregateKind.UNION);
            }
            if ((peek().is("struct") || peek().is("union"))
                    && (peek(1).is("{") || peek(1).is("("))) {
                return parseAnonymousAggregateVariable(false);
            }
            if (matchIdentifier("if")) return parseIf();
            if (matchIdentifier("while")) return parseWhile();
            if (matchIdentifier("for")) return parseFor();
            if (matchIdentifier("do")) return parseDoWhile();
            if (matchIdentifier("switch")) return parseSwitch();
            if (matchIdentifier("return")) {
                Expression value = peek().is(";") ? null : parseExpression();
                expect(";");
                return new ReturnStatement(value);
            }
            if (matchIdentifier("break")) {
                expect(";");
                return new BreakStatement();
            }
            if (matchIdentifier("continue")) {
                expect(";");
                return new ContinueStatement();
            }
            if (peek().is("local") && peek(1).is("enum")) {
                consume();
                return parseEnumStatement();
            }
            if (peek().is("enum") && (peek(1).is("{") || peek(1).is("<")
                    || (peek(1).kind() == TokenKind.IDENTIFIER && peek(2).is("{")))) return parseEnumStatement();
            if (looksLikeDeclaration()) return parseDeclarationOrFunction(topLevel);
            Expression expression = parseSequence();
            expect(";");
            return new ExpressionStatement(expression);
        }

        private boolean hasAggregateBodyAhead() {
            int cursor = position + 2;
            if (cursor < tokens.size() && tokens.get(cursor).is("(")) {
                cursor = matchingClose(cursor, "(", ")") + 1;
            }
            return cursor < tokens.size() && tokens.get(cursor).is("{");
        }

        private Statement parseTypedef() {
            Token kind = consume();
            if (kind.is("struct") || kind.is("union")) {
                AggregateKind aggregateKind = kind.is("struct") ? AggregateKind.STRUCT : AggregateKind.UNION;
                if (peek().kind() == TokenKind.IDENTIFIER && peek(1).is(";")) {
                    String name = consume().text();
                    consume();
                    typeNames.put(name, true);
                    return new TypeStatement(new AggregateDefinition(name, aggregateKind, List.of(), List.of(), Attributes.EMPTY));
                }
                if (peek().kind() == TokenKind.IDENTIFIER && peek(1).kind() == TokenKind.IDENTIFIER) {
                    String target = consume().text();
                    String alias = consume().text();
                    expect(";");
                    typeNames.put(alias, true);
                    return new TypeStatement(new AliasDefinition(alias, target));
                }
                String tagName = null;
                if (peek().kind() == TokenKind.IDENTIFIER && (peek(1).is("{") || peek(1).is("("))) {
                    tagName = consume().text();
                    typeNames.put(tagName, true);
                }
                List<Parameter> parameters = match("(") ? parseParametersAfterOpen() : List.of();
                expect("{");
                List<Statement> body = parseStatementsUntil("}");
                expect("}");
                String name = peek().is(";") && tagName != null ? tagName : expectIdentifier().text();
                Expression aliasLength = null;
                if (match("[")) {
                    aliasLength = parseExpression();
                    expect("]");
                }
                Attributes attributes = parseAttributes();
                expect(";");
                typeNames.put(name, true);
                if (aliasLength == null) {
                    return new TypeStatement(new AggregateDefinition(name, aggregateKind, parameters, body, attributes));
                }
                String elementName = "$aggregate_alias_" + (++anonymousAggregate);
                return new BlockStatement(List.of(
                        new TypeStatement(new AggregateDefinition(elementName, aggregateKind, parameters, body, attributes)),
                        new TypeStatement(new ArrayAliasDefinition(name, elementName, aliasLength, Attributes.EMPTY))));
            }
            if (kind.is("enum")) {
                String base = "int";
                if (match("<")) {
                    Token baseToken = expectIdentifier();
                    base = baseToken.is("unsigned") && peek().kind() == TokenKind.IDENTIFIER
                            ? normalizeUnsigned(consume().text()) : normalizeType(baseToken.text());
                    expect(">");
                }
                if (peek().kind() == TokenKind.IDENTIFIER && peek(1).is("{")) consume();
                expect("{");
                LinkedHashMap<String, Expression> constants = parseEnumConstants();
                expect("}");
                String name = expectIdentifier().text();
                parseAttributes();
                expect(";");
                typeNames.put(name, true);
                return new TypeStatement(new EnumDefinition(name, base, constants));
            }
            if (kind.is("local")) kind = consume();
            String target = kind.text();
            if (kind.is("unsigned") || kind.is("signed")) {
                Token component = expectIdentifier();
                target = kind.is("unsigned") ? normalizeUnsigned(component.text()) : normalizeType(component.text());
            }
            if (typeNames.containsKey(kind.text())) {
                String name = expectIdentifier().text();
                Expression length = null;
                if (match("[")) {
                    length = parseExpression();
                    expect("]");
                }
                Attributes attributes = parseAttributes();
                expect(";");
                typeNames.put(name, true);
                return new TypeStatement(length == null
                        ? new AliasDefinition(name, normalizeType(target))
                        : new ArrayAliasDefinition(name, normalizeType(target), length, attributes));
            }
            throw failure(kind, "Expected a type after typedef.");
        }

        private Statement parseAnonymousAggregateVariable(boolean local) {
            Token kind = consume();
            AggregateKind aggregateKind = kind.is("struct") ? AggregateKind.STRUCT : AggregateKind.UNION;
            List<Parameter> parameters = match("(") ? parseParametersAfterOpen() : List.of();
            expect("{");
            List<Statement> body = parseStatementsUntil("}");
            expect("}");
            String typeName = "$anonymous_aggregate_" + (++anonymousAggregate);
            typeNames.put(typeName, true);
            List<Statement> declarations = new ArrayList<>();
            declarations.add(parseDeclarator(local, typeName, expectIdentifier()));
            while (match(",")) {
                declarations.add(parseDeclarator(local, typeName, expectIdentifier()));
            }
            expect(";");
            List<Statement> result = new ArrayList<>();
            result.add(new TypeStatement(new AggregateDefinition(typeName, aggregateKind, parameters, body, Attributes.EMPTY)));
            result.addAll(declarations);
            return new BlockStatement(result);
        }

        private Statement parseNamedAggregate(AggregateKind kind) {
            String name = expectIdentifier().text();
            typeNames.put(name, true);
            List<Parameter> parameters = match("(") ? parseParametersAfterOpen() : List.of();
            expect("{");
            List<Statement> body = parseStatementsUntil("}");
            expect("}");
            TypeStatement definition = new TypeStatement(new AggregateDefinition(name, kind, parameters, body, Attributes.EMPTY));
            if (peek().kind() == TokenKind.IDENTIFIER) {
                List<Statement> result = new ArrayList<>();
                result.add(definition);
                result.add(parseDeclarator(false, name, expectIdentifier()));
                while (match(",")) result.add(parseDeclarator(false, name, expectIdentifier()));
                expect(";");
                return new BlockStatement(result);
            }
            Attributes attributes = parseAttributes();
            expect(";");
            return attributes.values().isEmpty() ? definition
                    : new TypeStatement(new AggregateDefinition(name, kind, parameters, body, attributes));
        }

        private Statement parseEnumStatement() {
            Token start = expect("enum");
            String base = "int";
            if (match("<")) {
                Token baseToken = expectIdentifier();
                base = baseToken.is("unsigned") && peek().kind() == TokenKind.IDENTIFIER
                        ? normalizeUnsigned(consume().text()) : normalizeType(baseToken.text());
                expect(">");
            }
            String declaredName = null;
            if (peek().kind() == TokenKind.IDENTIFIER && peek(1).is("{")) {
                declaredName = consume().text();
            }
            expect("{");
            LinkedHashMap<String, Expression> constants = parseEnumConstants();
            expect("}");
            if (declaredName != null) {
                String name = declaredName;
                typeNames.put(name, true);
                TypeStatement definition = new TypeStatement(new EnumDefinition(name, base, constants));
                if (peek().kind() == TokenKind.IDENTIFIER) {
                    VariableStatement variable = parseDeclarator(false, name, expectIdentifier());
                    expect(";");
                    return new BlockStatement(List.of(definition, variable));
                }
                expect(";");
                return definition;
            }
            if (match(";")) {
                String typeName = "$anonymous_enum_" + (++anonymousEnum);
                typeNames.put(typeName, true);
                return new TypeStatement(new EnumDefinition(typeName, base, constants));
            }
            Token trailing = expectIdentifier();
            if (peek().is(":") || peek().is(",")) {
                String typeName = "$anonymous_enum_" + (++anonymousEnum);
                typeNames.put(typeName, true);
                List<Statement> result = new ArrayList<>();
                result.add(new TypeStatement(new EnumDefinition(typeName, base, constants)));
                result.add(parseDeclarator(false, typeName, trailing));
                while (match(",")) result.add(parseDeclarator(false, typeName, expectIdentifier()));
                expect(";");
                return new BlockStatement(result);
            }
            Attributes attributes = parseAttributes();
            expect(";");
            typeNames.put(trailing.text(), true);
            return new TypeStatement(new EnumDefinition(trailing.text(), base, constants));
        }

        private LinkedHashMap<String, Expression> parseEnumConstants() {
            LinkedHashMap<String, Expression> constants = new LinkedHashMap<>();
            long next = 0;
            while (!peek().is("}")) {
                String name = expectIdentifier().text();
                Expression value;
                if (match("=")) {
                    value = parseExpression();
                    if (value instanceof LiteralExpression literal && literal.value instanceof Number number) {
                        next = number.longValue() + 1;
                    }
                } else {
                    value = new LiteralExpression(next++);
                }
                constants.put(name, value);
                if (!match(",")) break;
            }
            return constants;
        }

        private Statement parseDeclarationOrFunction(boolean topLevel) {
            boolean local = functionDepth > 0;
            while (peek().is("local") || peek().is("const") || peek().is("static")) {
                local = true;
                consume();
            }
            if (peek().is("struct") || peek().is("enum") || peek().is("union")) consume();
            Token typeToken = expectIdentifier();
            String type = normalizeType(typeToken.text());
            if (typeToken.is("unsigned") && peek().kind() == TokenKind.IDENTIFIER) {
                type = normalizeUnsigned(expectIdentifier().text());
            }
            while (match("[")) {
                if (!match("]")) {
                    parseExpression();
                    expect("]");
                }
            }
            Token nameToken = peek().is(":")
                    ? new Token(TokenKind.IDENTIFIER, "$bitfield_" + (++anonymousField), peek().line(), peek().column())
                    : expectIdentifier();
            if (peek().is("(") && topLevel && looksLikeFunctionDeclaration(position)) {
                consume();
                List<Parameter> parameters = parseParametersAfterOpen();
                BlockStatement body;
                if (match(";")) {
                    body = new BlockStatement(List.of());
                } else {
                    expect("{");
                    functionDepth++;
                    try {
                        body = parseBlockAfterOpen();
                    } finally {
                        functionDepth--;
                    }
                }
                return new FunctionStatement(new FunctionDefinition(type, nameToken.text(), parameters, body,
                        nameToken.line(), nameToken.column()));
            }
            List<Statement> declarations = new ArrayList<>();
            declarations.add(parseDeclarator(local, type, nameToken));
            while (match(",")) {
                declarations.add(parseDeclarator(local, type, expectIdentifier()));
            }
            expect(";");
            return declarations.size() == 1 ? declarations.getFirst() : new BlockStatement(declarations);
        }

        private boolean looksLikeFunctionDeclaration(int openPosition) {
            int close = matchingClose(openPosition, "(", ")");
            if (close + 1 >= tokens.size() || (!tokens.get(close + 1).is("{") && !tokens.get(close + 1).is(";"))) {
                return false;
            }
            if (close == openPosition + 1) return true;
            int cursor = openPosition + 1;
            while (tokens.get(cursor).is("const") || tokens.get(cursor).is("local")) cursor++;
            boolean tagged = tokens.get(cursor).is("struct") || tokens.get(cursor).is("union") || tokens.get(cursor).is("enum");
            if (tagged) cursor++;
            if (tokens.get(cursor).is("void") && cursor + 1 == close) return true;
            return tokens.get(cursor).kind() == TokenKind.IDENTIFIER
                    && (tagged || typeNames.containsKey(tokens.get(cursor).text()));
        }

        private VariableStatement parseDeclarator(boolean local, String type, Token nameToken) {
            List<Expression> constructorArguments = List.of();
            if (match("(")) constructorArguments = parseArgumentsAfterOpen();
            Expression arrayLength = null;
            if (match("[")) {
                arrayLength = peek().is("]") ? new LiteralExpression(-1L) : parseExpression();
                expect("]");
                while (match("[")) {
                    Expression dimension = peek().is("]") ? new LiteralExpression(0L) : parseExpression();
                    expect("]");
                    arrayLength = new BinaryExpression(arrayLength, "*", dimension);
                }
            }
            Expression bitWidth = match(":") ? parseBinary(8) : null; // '<attributes>' has lower precedence.
            Attributes attributes = parseAttributes();
            Expression initializer = match("=") ? parseExpression() : null;
            return new VariableStatement(local, type, nameToken.text(), arrayLength, bitWidth, constructorArguments,
                    attributes, initializer, nameToken.line(), nameToken.column());
        }

        private boolean blockFollowsParentheses(int openPosition) {
            int close = matchingClose(openPosition, "(", ")");
            return close + 1 < tokens.size() && tokens.get(close + 1).is("{");
        }

        private List<Parameter> parseParametersAfterOpen() {
            List<Parameter> parameters = new ArrayList<>();
            if (match(")")) return parameters;
            if (peek().is("void") && peek(1).is(")")) {
                consume();
                consume();
                return parameters;
            }
            do {
                while (peek().is("local") || peek().is("const")) consume();
                if (peek().is("struct") || peek().is("enum") || peek().is("union")) consume();
                Token typeToken = expectIdentifier();
                String type = typeToken.is("unsigned") && peek().kind() == TokenKind.IDENTIFIER
                        ? normalizeUnsigned(consume().text()) : normalizeType(typeToken.text());
                boolean reference = match("&");
                String name = expectIdentifier().text();
                if (match("[")) {
                    if (!match("]")) {
                        parseExpression();
                        expect("]");
                    }
                }
                parameters.add(new Parameter(type, name, reference));
            } while (match(","));
            expect(")");
            return parameters;
        }

        private List<Expression> parseArgumentsAfterOpen() {
            List<Expression> arguments = new ArrayList<>();
            if (match(")")) return arguments;
            do {
                arguments.add(parseExpression());
            } while (match(","));
            expect(")");
            return arguments;
        }

        private Attributes parseAttributes() {
            if (!match("<")) return Attributes.EMPTY;
            Map<String, Expression> values = new LinkedHashMap<>();
            do {
                String key = expectIdentifier().text();
                expect("=");
                Expression value;
                if (peek().kind() == TokenKind.STRING) {
                    value = new LiteralExpression(consume().text());
                } else if ((key.equals("read") || key.equals("write") || key.equals("format") || key.equals("name")
                        || key.equals("comment") || key.equals("edit")) && peek().kind() == TokenKind.IDENTIFIER
                        && (peek(1).is(",") || peek(1).is(">"))) {
                    value = new LiteralExpression(consume().text());
                } else if (match("(")) {
                    value = parseExpression();
                    expect(")");
                } else {
                    value = parseUnary();
                }
                values.put(key, value);
            } while (match(","));
            expect(">");
            return new Attributes(values);
        }

        private Statement parseIf() {
            expect("(");
            Expression condition = parseExpression();
            expect(")");
            Statement thenBranch = parseStatement(false);
            Statement elseBranch = matchIdentifier("else") ? parseStatement(false) : null;
            return new IfStatement(condition, thenBranch, elseBranch);
        }

        private Statement parseWhile() {
            Token token = previous();
            expect("(");
            Expression condition = parseExpression();
            expect(")");
            return new WhileStatement(condition, parseStatement(false), token.line(), token.column());
        }

        private Statement parseFor() {
            Token token = previous();
            expect("(");
            Statement initializer;
            if (match(";")) {
                initializer = new BlockStatement(List.of());
            } else if (looksLikeDeclaration()) {
                initializer = parseDeclarationOrFunction(false);
            } else {
                initializer = new ExpressionStatement(parseSequence());
                expect(";");
            }
            Expression condition = peek().is(";") ? new LiteralExpression(1L) : parseExpression();
            expect(";");
            Expression update = peek().is(")") ? null : parseSequence();
            expect(")");
            return new ForStatement(initializer, condition, update, parseStatement(false), token.line(), token.column());
        }

        private Statement parseDoWhile() {
            Token token = previous();
            Statement body = parseStatement(false);
            expect("while");
            expect("(");
            Expression condition = parseExpression();
            expect(")");
            expect(";");
            return new DoWhileStatement(body, condition, token.line(), token.column());
        }

        private Expression parseSequence() {
            List<Expression> expressions = new ArrayList<>();
            expressions.add(parseExpression());
            while (match(",")) expressions.add(parseExpression());
            return expressions.size() == 1 ? expressions.getFirst() : new SequenceExpression(expressions);
        }

        private Statement parseSwitch() {
            expect("(");
            Expression expression = parseExpression();
            expect(")");
            expect("{");
            List<SwitchSection> sections = new ArrayList<>();
            while (!peek().is("}")) {
                List<Expression> labels = new ArrayList<>();
                boolean defaultSection = false;
                while (peek().is("case") || peek().is("default")) {
                    if (matchIdentifier("case")) {
                        labels.add(parseExpression());
                        expect(":");
                    } else {
                        consume();
                        expect(":");
                        defaultSection = true;
                    }
                }
                List<Statement> statements = new ArrayList<>();
                while (!peek().is("}") && !peek().is("case") && !peek().is("default")) {
                    statements.add(parseStatement(false));
                }
                sections.add(new SwitchSection(labels, defaultSection, statements));
            }
            expect("}");
            return new SwitchStatement(expression, sections);
        }

        private BlockStatement parseBlockAfterOpen() {
            List<Statement> statements = parseStatementsUntil("}");
            expect("}");
            return new BlockStatement(statements);
        }

        private List<Statement> parseStatementsUntil(String terminator) {
            List<Statement> statements = new ArrayList<>();
            while (!peek().is(terminator)) {
                if (peek().kind() == TokenKind.EOF) throw failure(peek(), "Expected '" + terminator + "'.");
                statements.add(parseStatement(false));
            }
            return statements;
        }

        private boolean looksLikeDeclaration() {
            int cursor = position;
            while (tokens.get(cursor).is("local") || tokens.get(cursor).is("const") || tokens.get(cursor).is("static")) cursor++;
            boolean tagged = tokens.get(cursor).is("struct") || tokens.get(cursor).is("enum") || tokens.get(cursor).is("union");
            if (tagged) cursor++;
            if (cursor >= tokens.size() || tokens.get(cursor).kind() != TokenKind.IDENTIFIER) return false;
            String type = tokens.get(cursor).text();
            if (type.equals("unsigned")) cursor++;
            if (!tagged && !typeNames.containsKey(type) && !type.equals("unsigned")) {
                return cursor + 1 < tokens.size() && tokens.get(cursor + 1).kind() == TokenKind.IDENTIFIER;
            }
            cursor++;
            if (cursor < tokens.size() && tokens.get(cursor).is("[")) {
                int close = matchingClose(cursor, "[", "]");
                cursor = close + 1;
            }
            return cursor < tokens.size() && (tokens.get(cursor).kind() == TokenKind.IDENTIFIER || tokens.get(cursor).is(":"));
        }

        private Expression parseExpression() { return parseAssignment(); }

        private Expression parseAssignment() {
            Expression left = parseConditional();
            if (peek().is("=") || peek().is("+=") || peek().is("-=") || peek().is("*=") || peek().is("/=")
                    || peek().is("%=") || peek().is("<<=") || peek().is(">>=") || peek().is("&=")
                    || peek().is("|=") || peek().is("^=")) {
                Token operator = consume();
                return new AssignmentExpression(left, operator.text(), parseAssignment(), operator.line(), operator.column());
            }
            return left;
        }

        private Expression parseConditional() {
            Expression condition = parseBinary(1);
            if (match("?")) {
                Expression whenTrue = parseExpression();
                expect(":");
                return new ConditionalExpression(condition, whenTrue, parseConditional());
            }
            return condition;
        }

        private static final Map<String, Integer> PRECEDENCE = Map.ofEntries(
                Map.entry("||", 1), Map.entry("&&", 2), Map.entry("|", 3), Map.entry("^", 4), Map.entry("&", 5),
                Map.entry("==", 6), Map.entry("!=", 6), Map.entry("<", 7), Map.entry("<=", 7),
                Map.entry(">", 7), Map.entry(">=", 7), Map.entry("<<", 8), Map.entry(">>", 8),
                Map.entry("+", 9), Map.entry("-", 9), Map.entry("*", 10), Map.entry("/", 10), Map.entry("%", 10));

        private Expression parseBinary(int minimum) {
            Expression left = parseUnary();
            while (true) {
                Integer precedence = PRECEDENCE.get(peek().text());
                if (precedence == null || precedence < minimum) return left;
                String operator = consume().text();
                Expression right = parseBinary(precedence + 1);
                left = new BinaryExpression(left, operator, right);
            }
        }

        private Expression parseUnary() {
            if (peek().is("++") || peek().is("--")) {
                Token operator = consume();
                return new PrefixExpression(operator.text(), parseUnary(), operator.line(), operator.column());
            }
            if (peek().is("!") || peek().is("~") || peek().is("-") || peek().is("+")) {
                return new UnaryExpression(consume().text(), parseUnary());
            }
            if (peek().is("(") && peek(1).kind() == TokenKind.IDENTIFIER
                    && typeNames.containsKey(peek(1).text()) && peek(2).is(")")) {
                consume();
                String type = normalizeType(consume().text());
                expect(")");
                return new CastExpression(type, parseUnary());
            }
            if (peek().is("(") && (peek(1).is("unsigned") || peek(1).is("signed"))
                    && peek(2).kind() == TokenKind.IDENTIFIER && peek(3).is(")")) {
                consume();
                boolean unsigned = consume().is("unsigned");
                String component = consume().text();
                expect(")");
                return new CastExpression(unsigned ? normalizeUnsigned(component) : normalizeType(component), parseUnary());
            }
            return parsePostfix();
        }

        private Expression parsePostfix() {
            Expression expression = parsePrimary();
            while (true) {
                if (match(".")) {
                    Token member = expectIdentifier();
                    expression = new MemberExpression(expression, member.text(), member.line(), member.column());
                } else if (match("[")) {
                    Token start = previous();
                    Expression index = parseExpression();
                    expect("]");
                    expression = new IndexExpression(expression, index, start.line(), start.column());
                } else if (match("++") || match("--")) {
                    Token operator = previous();
                    expression = new PostfixExpression(expression, operator.text(), operator.line(), operator.column());
                } else {
                    return expression;
                }
            }
        }

        private Expression parsePrimary() {
            Token token = consume();
            if (token.kind() == TokenKind.NUMBER) {
                String raw = token.text();
                if (raw.matches("(?i)[0-9][0-9a-f]*h")) {
                    return new LiteralExpression(Long.parseUnsignedLong(raw.substring(0, raw.length() - 1), 16));
                }
                String normalized = (raw.startsWith("0x") || raw.startsWith("0X")
                        || raw.startsWith("0b") || raw.startsWith("0B"))
                        ? raw.replaceAll("[uUlL]+$", "") : raw.replaceAll("[uUlLfF]+$", "");
                boolean basedInteger = normalized.startsWith("0x") || normalized.startsWith("0X")
                        || normalized.startsWith("0b") || normalized.startsWith("0B");
                if (!basedInteger && (normalized.contains(".") || normalized.contains("e") || normalized.contains("E"))) {
                    return new LiteralExpression(Double.parseDouble(normalized));
                }
                long value;
                if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
                    value = Long.parseUnsignedLong(normalized.substring(2), 16);
                } else if (normalized.startsWith("0b") || normalized.startsWith("0B")) {
                    value = Long.parseUnsignedLong(normalized.substring(2), 2);
                } else {
                    value = Long.parseLong(normalized);
                }
                return new LiteralExpression(value);
            }
            if (token.kind() == TokenKind.STRING) {
                StringBuilder value = new StringBuilder(token.text());
                while (peek().kind() == TokenKind.STRING) value.append(consume().text());
                return new LiteralExpression(value.toString());
            }
            if (token.is("{")) {
                List<Expression> values = new ArrayList<>();
                if (!match("}")) {
                    do {
                        values.add(parseExpression());
                    } while (match(",") && !peek().is("}"));
                    expect("}");
                }
                return new InitializerListExpression(values);
            }
            if (token.is("(")) {
                Expression expression = parseExpression();
                expect(")");
                return expression;
            }
            if (token.kind() == TokenKind.IDENTIFIER) {
                if (token.is("true") || token.is("TRUE")) return new LiteralExpression(1L);
                if (token.is("false") || token.is("FALSE")) return new LiteralExpression(0L);
                if (token.text().matches("(?i)b[01]+")) {
                    return new LiteralExpression(Long.parseUnsignedLong(token.text().substring(1), 2));
                }
                if (token.text().matches("(?i)[0-9a-f]+h")) {
                    return new LiteralExpression(Long.parseUnsignedLong(token.text().substring(0, token.text().length() - 1), 16));
                }
                if (match("(")) {
                    if (token.is("sizeof") && (peek().is("struct") || peek().is("union") || peek().is("enum"))) {
                        consume();
                        Token type = expectIdentifier();
                        expect(")");
                        return new CallExpression(token.text(), List.of(new VariableExpression(type.text(), type.line(), type.column())),
                                token.line(), token.column());
                    }
                    return new CallExpression(token.text(), parseArgumentsAfterOpen(), token.line(), token.column());
                }
                return new VariableExpression(token.text(), token.line(), token.column());
            }
            throw failure(token, "Expected expression.");
        }

        private static String normalizeType(String type) {
            return switch (type) {
                case "CHAR", "BYTE", "int8", "INT8", "int8_t", "__int8" -> "byte";
                case "UCHAR", "UBYTE", "uint8", "UINT8", "uint8_t" -> "ubyte";
                case "SHORT", "INT16", "int16_t", "__int16" -> "int16";
                case "USHORT", "UINT16", "WORD", "uint16_t" -> "uint16";
                case "INT", "INT32", "LONG", "int32_t", "__int32" -> "int32";
                case "UINT", "UINT32", "ULONG", "DWORD", "uint32_t" -> "uint32";
                case "INT64", "QUAD", "quad", "int64_t", "__int64" -> "int64";
                case "UINT64", "UQUAD", "uquad", "QWORD", "uint64_t" -> "uint64";
                case "wchar", "wchar_t" -> "uint16";
                case "bool", "boolean" -> "ubyte";
                case "BOOL" -> "uint32";
                case "FLOAT" -> "float";
                case "DOUBLE" -> "double";
                case "TKeywordList", "TFindResults", "TFileList", "TStringList", "TTemplate", "TDateTime" -> "uint64";
                default -> type;
            };
        }

        private static String normalizeUnsigned(String type) {
            return switch (type) {
                case "char" -> "ubyte";
                case "short" -> "uint16";
                case "int", "long" -> "uint32";
                default -> type;
            };
        }

        private int matchingClose(int open, String opening, String closing) {
            int depth = 0;
            for (int i = open; i < tokens.size(); i++) {
                if (tokens.get(i).is(opening)) depth++;
                if (tokens.get(i).is(closing) && --depth == 0) return i;
            }
            throw failure(tokens.get(open), "Unclosed '" + opening + "'.");
        }

        private boolean match(String text) {
            if (!peek().is(text)) return false;
            position++;
            return true;
        }

        private boolean matchIdentifier(String text) { return match(text); }
        private Token expect(String text) {
            Token token = consume();
            if (!token.is(text)) throw failure(token, "Expected '" + text + "' but found '" + token.text() + "'.");
            return token;
        }
        private Token expectIdentifier() {
            Token token = consume();
            if (token.kind() != TokenKind.IDENTIFIER) throw failure(token, "Expected identifier.");
            return token;
        }
        private Token consume() { return tokens.get(position++); }
        private Token peek() { return peek(0); }
        private Token peek(int ahead) { return tokens.get(Math.min(position + ahead, tokens.size() - 1)); }
        private Token previous() { return tokens.get(position - 1); }
        private ParseFailure failure(Token token, String message) { return new ParseFailure(token.line(), token.column(), message); }
    }

    // --- Runtime -----------------------------------------------------------------------------

    private sealed interface TemplateType permits PrimitiveType, AggregateType, EnumType, ArrayType {}
    private enum PrimitiveKind { INTEGER, FLOAT, STRING, BYTES }
    private record PrimitiveType(String name, int size, boolean signed, PrimitiveKind kind) implements TemplateType {}
    private record AggregateType(AggregateDefinition definition) implements TemplateType {}
    private record EnumType(String name, PrimitiveType base, LinkedHashMap<Long, String> names) implements TemplateType {}
    private record ArrayType(String name, TemplateType element, long count, Attributes attributes) implements TemplateType {}

    private static final class Runtime {
        private final BinaryDataSource input;
        private final BooleanSupplier canceled;
        private final List<BtDiagnostic> diagnostics;
        private final List<String> output;
        private final Map<String, TemplateType> types = new HashMap<>();
        private final Map<String, FunctionDefinition> functions = new HashMap<>();
        private final Scope globals = new Scope(null);
        private final List<MutableNode> rootNodes = new ArrayList<>();
        private long cursor;
        private boolean bigEndian;
        private boolean bitfieldLeftToRight;
        private long steps;
        private int callDepth;
        private int nodeCount;
        private String currentForeground;
        private String currentBackground;

        private Runtime(BinaryDataSource input, BooleanSupplier canceled, List<BtDiagnostic> diagnostics, List<String> output) {
            this.input = input;
            this.canceled = canceled;
            this.diagnostics = diagnostics;
            this.output = output;
            installPrimitives();
            installConstants();
        }

        List<MutableNode> execute(Program program) {
            Frame frame = new Frame(globals, rootNodes);
            try {
                for (Statement statement : program.statements()) execute(statement, frame);
            } catch (ReturnSignal ignored) {
                // A top-level return intentionally stops the template.
            } catch (RuntimeFailure failure) {
                failure.partialNodes = rootNodes;
                throw failure;
            }
            return rootNodes;
        }

        private void installPrimitives() {
            primitive("char", 1, true, PrimitiveKind.INTEGER, "byte");
            primitive("byte", 1, true, PrimitiveKind.INTEGER, "int8");
            primitive("ubyte", 1, false, PrimitiveKind.INTEGER, "uchar", "uint8");
            primitive("int16", 2, true, PrimitiveKind.INTEGER, "short");
            primitive("uint16", 2, false, PrimitiveKind.INTEGER, "ushort");
            primitive("int32", 4, true, PrimitiveKind.INTEGER, "int", "long");
            primitive("uint32", 4, false, PrimitiveKind.INTEGER, "uint", "ulong");
            primitive("int64", 8, true, PrimitiveKind.INTEGER);
            primitive("uint64", 8, false, PrimitiveKind.INTEGER);
            primitive("float", 4, true, PrimitiveKind.FLOAT);
            primitive("double", 8, true, PrimitiveKind.FLOAT);
            primitive("hfloat", 2, true, PrimitiveKind.FLOAT);
            primitive("string", 0, false, PrimitiveKind.STRING);
            primitive("wstring", 0, false, PrimitiveKind.STRING);
            primitive("DOSDATE", 2, false, PrimitiveKind.INTEGER);
            primitive("DOSTIME", 2, false, PrimitiveKind.INTEGER);
            primitive("FILETIME", 8, false, PrimitiveKind.INTEGER);
            primitive("OLETIME", 8, true, PrimitiveKind.FLOAT);
            primitive("time_t", 4, true, PrimitiveKind.INTEGER);
            primitive("time64_t", 8, true, PrimitiveKind.INTEGER);
            primitive("GUID", 16, false, PrimitiveKind.BYTES);
            primitive("Opcode", 1, false, PrimitiveKind.BYTES);
        }

        private void primitive(String name, int size, boolean signed, PrimitiveKind kind, String... aliases) {
            PrimitiveType type = new PrimitiveType(name, size, signed, kind);
            types.put(name, type);
            for (String alias : aliases) types.put(alias, type);
        }

        private void installConstants() {
            defineConstant("CHECKSUM_CRC32", 1L);
            defineConstant("FINDMETHOD_NORMAL", 0L);
            defineConstant("FINDMETHOD_WILDCARDS", 1L);
            defineConstant("FINDMETHOD_REGEX", 2L);
            defineConstant("HIGHLIGHT_WHOLEWORD", 1L);
            defineConstant("HIGHLIGHT_IGNORECASE", 2L);
            defineConstant("HIGHLIGHT_REGEX", 4L);
            defineConstant("HIGHLIGHT_CSTRING", 8L);
            defineConstant("HIGHLIGHT_XMLSTRING", 16L);
            // Built-in 010 Editor styles can be passed to SetStyle without being declared by a template.
            defineConstant("sNone", 0L);
            defineConstant("sData", 1L);
            defineConstant("cNone", new ColorValue("cNone"));
            for (String color : List.of("cBlack", "cWhite", "cRed", "cGreen", "cBlue", "cPurple", "cDkBlue",
                    "cDkPurple", "cLtPurple", "cLtGray", "cLtRed", "cLtGreen", "cLtBlue", "cYellow",
                    "cLtYellow", "cAqua", "cLtAqua", "cGray", "cDkGray", "cSilver", "cTeal", "cMaroon",
                    "cOlive", "cNavy", "cFuchsia", "cLime", "cDkAqua", "cDkGreen", "cDkRed",
                    "cDkYellow", "cLtBlack", "cOrange")) {
                defineConstant(color, new ColorValue(color));
            }
        }

        private void defineConstant(String name, Object value) { globals.define(name, new Slot(value), false); }

        private void execute(Statement statement, Frame frame) {
            tick(1);
            if (statement instanceof BlockStatement block) {
                // 010 Editor keeps generated template variables visible outside control-flow blocks
                // (notably duplicate variables declared by a while loop).
                for (Statement nested : block.statements()) execute(nested, frame);
            } else if (statement instanceof TypeStatement typeStatement) {
                installType(typeStatement.definition(), frame);
            } else if (statement instanceof FunctionStatement functionStatement) {
                functions.put(functionStatement.definition().name(), functionStatement.definition());
            } else if (statement instanceof VariableStatement variable) {
                declare(variable, frame);
            } else if (statement instanceof ExpressionStatement expression) {
                evaluate(expression.expression(), frame.scope);
            } else if (statement instanceof IfStatement conditional) {
                if (truth(evaluate(conditional.condition(), frame.scope).value)) execute(conditional.thenBranch(), frame);
                else if (conditional.elseBranch() != null) execute(conditional.elseBranch(), frame);
            } else if (statement instanceof WhileStatement loop) {
                while (truth(evaluate(loop.condition(), frame.scope).value)) {
                    tick(1);
                    try {
                        execute(loop.body(), frame);
                    } catch (ContinueSignal ignored) {
                        // Continue with the condition check.
                    } catch (BreakSignal ignored) {
                        break;
                    }
                }
            } else if (statement instanceof ForStatement loop) {
                execute(loop.initializer(), frame);
                while (truth(evaluate(loop.condition(), frame.scope).value)) {
                    tick(1);
                    try {
                        execute(loop.body(), frame);
                    } catch (ContinueSignal ignored) {
                        // The update expression still runs after continue.
                    } catch (BreakSignal ignored) {
                        break;
                    }
                    if (loop.update() != null) evaluate(loop.update(), frame.scope);
                }
            } else if (statement instanceof DoWhileStatement loop) {
                do {
                    tick(1);
                    try {
                        execute(loop.body(), frame);
                    } catch (ContinueSignal ignored) {
                        // Continue with the condition check.
                    } catch (BreakSignal ignored) {
                        break;
                    }
                } while (truth(evaluate(loop.condition(), frame.scope).value));
            } else if (statement instanceof SwitchStatement switchStatement) {
                executeSwitch(switchStatement, frame);
            } else if (statement instanceof ReturnStatement returnStatement) {
                throw new ReturnSignal(returnStatement.value() == null ? 0L : evaluate(returnStatement.value(), frame.scope).value);
            } else if (statement instanceof BreakStatement) {
                throw new BreakSignal();
            } else if (statement instanceof ContinueStatement) {
                throw new ContinueSignal();
            }
        }

        private void installType(TypeDefinition definition, Frame frame) {
            if (definition instanceof AggregateDefinition aggregate) {
                types.put(aggregate.name(), new AggregateType(aggregate));
            } else if (definition instanceof EnumDefinition enumDefinition) {
                TemplateType rawBase = requireType(enumDefinition.baseType(), 0, 0);
                if (!(rawBase instanceof PrimitiveType base) || base.kind() != PrimitiveKind.INTEGER) {
                    fail(0, 0, "Enum base type must be an integer: " + enumDefinition.baseType());
                    return;
                }
                LinkedHashMap<Long, String> names = new LinkedHashMap<>();
                for (Map.Entry<String, Expression> entry : enumDefinition.constants().entrySet()) {
                    long value = number(evaluate(entry.getValue(), frame.scope).value);
                    names.put(value, entry.getKey());
                    frame.scope.define(entry.getKey(), new Slot(value), false);
                }
                types.put(enumDefinition.name(), new EnumType(enumDefinition.name(), base, names));
            } else if (definition instanceof AliasDefinition alias) {
                types.put(alias.name(), requireType(alias.targetType(), 0, 0));
            } else if (definition instanceof ArrayAliasDefinition alias) {
                long count = number(evaluate(alias.length(), frame.scope).value);
                types.put(alias.name(), new ArrayType(alias.name(), requireType(alias.targetType(), 0, 0), count,
                        alias.attributes()));
            }
        }

        private void executeSwitch(SwitchStatement switchStatement, Frame frame) {
            Object switchValue = evaluate(switchStatement.expression(), frame.scope).value;
            int start = -1;
            int defaultIndex = -1;
            for (int i = 0; i < switchStatement.sections().size(); i++) {
                SwitchSection section = switchStatement.sections().get(i);
                if (section.defaultSection()) defaultIndex = i;
                for (Expression label : section.labels()) {
                    if (equal(switchValue, evaluate(label, frame.scope).value)) {
                        start = i;
                        break;
                    }
                }
                if (start >= 0) break;
            }
            if (start < 0) start = defaultIndex;
            if (start < 0) return;
            try {
                for (int i = start; i < switchStatement.sections().size(); i++) {
                    for (Statement statement : switchStatement.sections().get(i).statements()) execute(statement, frame);
                }
            } catch (BreakSignal ignored) {
                // Normal switch termination.
            }
        }

        private void declare(VariableStatement declaration, Frame frame) {
            TemplateType type = requireType(declaration.type(), declaration.line(), declaration.column());
            if (declaration.local()) {
                frame.bitfields.reset();
                Object value;
                if (declaration.arrayLength() != null) {
                    long count = Math.max(0, number(evaluate(declaration.arrayLength(), frame.scope).value));
                    if (count > 1_000_000) fail(declaration.line(), declaration.column(), "Local array is too large: " + count);
                    Object initial = declaration.initializer() == null ? defaultValue(type)
                            : evaluate(declaration.initializer(), frame.scope).value;
                    if (initial instanceof ObjectArray array) {
                        value = array.resized((int) count, defaultValue(type));
                    } else {
                        value = ObjectArray.filled((int) count, initial);
                    }
                } else {
                    value = declaration.initializer() == null ? defaultValue(type)
                            : evaluate(declaration.initializer(), frame.scope).value;
                }
                frame.scope.define(declaration.name(), new Slot(value), true);
                return;
            }
            if (declaration.bitWidth() != null) {
                declareBitfield(declaration, type, frame);
                return;
            }
            frame.bitfields.reset();
            List<Object> constructorArguments = new ArrayList<>();
            for (Expression expression : declaration.constructorArguments()) {
                constructorArguments.add(evaluate(expression, frame.scope).value);
            }
            ParsedValue parsed;
            if (declaration.arrayLength() != null) {
                long count = number(evaluate(declaration.arrayLength(), frame.scope).value);
                parsed = parseArray(type, declaration.name(), count, declaration.attributes(), frame);
            } else if (type instanceof ArrayType arrayType) {
                parsed = parseArray(arrayType.element(), declaration.name(), arrayType.count(),
                        arrayType.attributes().merge(declaration.attributes()), frame);
            } else {
                parsed = parseValue(type, declaration.name(), declaration.attributes(), constructorArguments, frame);
            }
            frame.scope.define(declaration.name(), new Slot(parsed.value, parsed.node.offset, parsed.node.size), false);
            frame.nodes.add(parsed.node);
        }

        private void declareBitfield(VariableStatement declaration, TemplateType type, Frame frame) {
            PrimitiveType primitive = type instanceof PrimitiveType value ? value
                    : type instanceof EnumType enumType ? enumType.base() : null;
            if (primitive == null || primitive.kind() != PrimitiveKind.INTEGER) {
                fail(declaration.line(), declaration.column(), "Bitfield base type must be an integer: " + declaration.type());
            }
            int width = Math.toIntExact(number(evaluate(declaration.bitWidth(), frame.scope).value));
            int storageBits = primitive.size() * 8;
            if (width < 0 || width > storageBits) fail(declaration.line(), declaration.column(), "Invalid bitfield width: " + width);
            if (width == 0) {
                frame.bitfields.reset();
                return;
            }
            BitfieldState state = frame.bitfields;
            if (!state.active || state.type != primitive || state.consumed + width > storageBits) {
                ensureAvailable(cursor, primitive.size(), declaration.name());
                state.active = true;
                state.type = primitive;
                state.offset = cursor;
                state.raw = readInteger(cursor, primitive.size(), false);
                state.consumed = 0;
                cursor += primitive.size();
            }
            int shift = bitfieldLeftToRight ? storageBits - state.consumed - width : state.consumed;
            long mask = width == 64 ? -1L : (1L << width) - 1;
            long value = (state.raw >>> shift) & mask;
            state.consumed += width;
            MutableNode node = node(declaration.name(), primitive.name() + ":" + width, state.offset,
                    declaration.attributes(), frame);
            node.size = primitive.size();
            node.value = Long.toString(value);
            frame.scope.define(declaration.name(), new Slot(value, state.offset, primitive.size()), false);
            frame.nodes.add(node);
            if (state.consumed == storageBits) state.reset();
        }

        private ParsedValue parseArray(TemplateType type, String name, long count, Attributes attributes, Frame frame) {
            if (count == -1) {
                if (type instanceof PrimitiveType primitive && primitive.size() == 1) {
                    long end = cursor;
                    while (end < input.length() && input.readUnsignedByte(end++) != 0) tick(1);
                    count = end - cursor;
                } else if (type instanceof PrimitiveType primitive && primitive.size() > 0) {
                    count = (input.length() - cursor) / primitive.size();
                } else {
                    count = 0;
                }
            }
            if (count < 0) fail(0, 0, "Negative array size for " + name + ": " + count);
            if (count > MAX_NODES && !(type instanceof PrimitiveType)) {
                fail(0, 0, "Array is too large for structured expansion: " + count);
            }
            long start = cursor;
            MutableNode arrayNode = node(name, displayType(type) + "[" + count + "]", start, attributesFor(type, attributes), frame);
            Object value;
            if (type instanceof PrimitiveType primitive && primitive.kind() == PrimitiveKind.INTEGER) {
                long byteSize = multiplyExact(count, primitive.size(), name);
                ensureAvailable(start, byteSize, name);
                PrimitiveArray array = new PrimitiveArray(this, primitive, start, count);
                value = array;
                cursor += byteSize;
                if (primitive.name().equals("char")) {
                    int stringLength = Math.toIntExact(Math.min(count, MAX_STRING_BYTES));
                    byte[] bytes = input.read(start, stringLength);
                    StringBuilder string = new StringBuilder(bytes.length);
                    for (byte aByte : bytes) string.append((char) (aByte & 0xff));
                    if (count > stringLength) string.append('…');
                    value = string.toString();
                    arrayNode.value = (String) value;
                } else {
                    arrayNode.value = arrayDisplay(array, primitive, count);
                }
                if (count <= 256) {
                    for (int i = 0; i < (int) count; i++) {
                        long elementOffset = start + (long) i * primitive.size();
                        Object element = array.get(i);
                        MutableNode child = node("[" + i + "]", primitive.name(), elementOffset,
                                attributesFor(type, attributes), frame);
                        child.size = primitive.size();
                        child.value = formatValue(element, primitive, attributes, null);
                        arrayNode.children.add(child);
                    }
                }
            } else {
                ArrayList<Object> values = new ArrayList<>((int) count);
                for (int i = 0; i < (int) count; i++) {
                    tick(1);
                    ParsedValue element = parseValue(type, "[" + i + "]", attributes, List.of(), frame.withNodes(arrayNode.children));
                    values.add(element.value);
                    arrayNode.children.add(element.node);
                }
                value = ObjectArray.of(values);
                arrayNode.value = "[" + count + "]";
            }
            arrayNode.size = cursor - start;
            applyReadFunction(arrayNode, value, type, attributes, frame.scope);
            return new ParsedValue(value, arrayNode);
        }

        private ParsedValue parseValue(TemplateType type, String name, Attributes attributes,
                                       List<Object> constructorArguments, Frame frame) {
            Attributes effective = attributesFor(type, attributes);
            long start = cursor;
            MutableNode node = node(name, displayType(type), start, effective, frame);
            Object value;
            if (type instanceof PrimitiveType primitive) {
                value = readPrimitive(primitive, name);
                node.size = cursor - start;
                node.value = formatValue(value, primitive, effective, null);
            } else if (type instanceof EnumType enumType) {
                value = readPrimitive(enumType.base(), name);
                node.size = cursor - start;
                node.value = formatValue(value, enumType.base(), effective, enumType.names());
            } else if (type instanceof AggregateType aggregateType) {
                AggregateDefinition definition = aggregateType.definition();
                Scope instanceScope = new Scope(frame.scope);
                Instance parent = frame.scope.resolve("this") != null
                        && frame.scope.resolve("this").value instanceof Instance parentValue ? parentValue : null;
                Instance instance = new Instance(instanceScope, parent);
                Slot thisSlot = new Slot(instance, start, 0);
                instanceScope.define("this", thisSlot, true);
                for (int i = 0; i < definition.parameters().size(); i++) {
                    Object argument = i < constructorArguments.size() ? constructorArguments.get(i) : 0L;
                    instanceScope.define(definition.parameters().get(i).name(), new Slot(argument), true);
                }
                Frame instanceFrame = new Frame(instanceScope, node.children);
                if (definition.kind() == AggregateKind.UNION) {
                    long max = start;
                    try {
                        for (Statement statement : definition.body()) {
                            cursor = start;
                            execute(statement, instanceFrame);
                            max = Math.max(max, cursor);
                        }
                    } catch (ReturnSignal ignored) {
                        max = Math.max(max, cursor);
                    }
                    cursor = max;
                } else {
                    try {
                        for (Statement statement : definition.body()) execute(statement, instanceFrame);
                    } catch (ReturnSignal ignored) {
                        // Return from a parameterized structure stops that structure only.
                    }
                }
                value = instance;
                node.size = cursor - start;
                thisSlot.size = node.size;
                node.value = definition.name();
            } else {
                fail(0, 0, "Unsupported type for " + name);
                return null;
            }
            applyReadFunction(node, value, type, effective, frame.scope);
            return new ParsedValue(value, node);
        }

        private Object readPrimitive(PrimitiveType primitive, String name) {
            if (primitive.kind() == PrimitiveKind.STRING) {
                long start = cursor;
                StringBuilder value = new StringBuilder();
                int read = 0;
                while (cursor < input.length()) {
                    tick(1);
                    int current = input.readUnsignedByte(cursor++);
                    if (current < 0 || current == 0) break;
                    if (read++ >= MAX_STRING_BYTES) fail(0, 0, "String is too large: " + name);
                    value.append((char) current);
                }
                if (cursor == start && cursor >= input.length()) fail(0, 0, "Cannot read string at end of file: " + name);
                return value.toString();
            }
            if (primitive.kind() == PrimitiveKind.BYTES) {
                ensureAvailable(cursor, primitive.size(), name);
                byte[] bytes = input.read(cursor, primitive.size());
                cursor += primitive.size();
                StringBuilder value = new StringBuilder(primitive.size() * 2);
                for (byte current : bytes) value.append(String.format("%02X", current & 0xff));
                return value.toString();
            }
            ensureAvailable(cursor, primitive.size(), name);
            long bits = readInteger(cursor, primitive.size(), primitive.signed());
            cursor += primitive.size();
            if (primitive.kind() == PrimitiveKind.FLOAT) {
                if (primitive.size() == 2) return halfToDouble((int) bits);
                if (primitive.size() == 4) return (double) Float.intBitsToFloat((int) bits);
                return Double.longBitsToDouble(bits);
            }
            return bits;
        }

        private static double halfToDouble(int bits) {
            int sign = (bits >>> 15) & 1;
            int exponent = (bits >>> 10) & 0x1f;
            int fraction = bits & 0x3ff;
            if (exponent == 0) return (sign == 0 ? 1 : -1) * Math.scalb((double) fraction, -24);
            if (exponent == 31) return fraction == 0
                    ? (sign == 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY) : Double.NaN;
            return (sign == 0 ? 1 : -1) * Math.scalb(1.0 + fraction / 1024.0, exponent - 15);
        }

        private long readInteger(long offset, int size, boolean signed) {
            byte[] bytes = input.read(offset, size);
            if (bytes.length != size) fail(0, 0, "Unexpected end of file at 0x" + Long.toHexString(offset).toUpperCase(Locale.ROOT));
            long value = 0;
            if (bigEndian) {
                for (byte aByte : bytes) value = (value << 8) | (aByte & 0xffL);
            } else {
                for (int i = bytes.length - 1; i >= 0; i--) value = (value << 8) | (bytes[i] & 0xffL);
            }
            if (signed && size < 8) {
                long sign = 1L << (size * 8 - 1);
                if ((value & sign) != 0) value |= (-1L) << (size * 8);
            }
            return value;
        }

        private Attributes attributesFor(TemplateType type, Attributes variable) {
            if (type instanceof AggregateType aggregate) return aggregate.definition().attributes().merge(variable);
            return variable;
        }

        private void applyReadFunction(MutableNode node, Object value, TemplateType type, Attributes attributes, Scope scope) {
            Expression read = attributes.get("read");
            if (read == null) return;
            if (read instanceof LiteralExpression literal && literal.value instanceof String functionName) {
                FunctionDefinition function = functions.get(functionName);
                if (function == null) {
                    diagnostics.add(new BtDiagnostic(BtDiagnostic.Severity.WARNING, 0, 0,
                            "Read function is not defined: " + functionName));
                    return;
                }
                Object result = callCustom(function, List.of(new Evaluated(value, null)), scope);
                if (result != null) node.value = text(result);
                return;
            }
            Scope readScope = value instanceof Instance instance ? instance.scope() : new Scope(scope);
            readScope.define("this", new Slot(value), true);
            Object result = evaluate(read, readScope).value;
            if (result != null) node.value = text(result);
        }

        private MutableNode node(String name, String type, long offset, Attributes attributes, Frame frame) {
            if (++nodeCount > MAX_NODES) fail(0, 0, "Template generated too many result nodes.");
            MutableNode node = new MutableNode(name, type, offset);
            node.format = attributeText(attributes, "format", frame.scope);
            node.comment = attributeText(attributes, "comment", frame.scope);
            node.foregroundColor = attributeColor(attributes, "fgcolor", frame.scope, currentForeground);
            node.backgroundColor = attributeColor(attributes, "bgcolor", frame.scope, currentBackground);
            return node;
        }

        private String attributeText(Attributes attributes, String name, Scope scope) {
            Expression expression = attributes.get(name);
            return expression == null ? null : text(evaluate(expression, scope).value);
        }

        private String attributeColor(Attributes attributes, String name, Scope scope, String fallback) {
            Expression expression = attributes.get(name);
            if (expression == null) return fallback;
            Object value = evaluate(expression, scope).value;
            if (value instanceof ColorValue color) return color.name.equals("cNone") ? null : color.name;
            if (value instanceof Number number) return String.format("#%06X", number.longValue() & 0xffffffL);
            return fallback;
        }

        private Evaluated evaluate(Expression expression, Scope scope) {
            tick(1);
            if (expression instanceof LiteralExpression literal) return new Evaluated(literal.value, null);
            if (expression instanceof InitializerListExpression initializer) {
                ArrayList<Object> values = new ArrayList<>(initializer.values().size());
                for (Expression value : initializer.values()) values.add(evaluate(value, scope).value);
                return new Evaluated(ObjectArray.of(values), null);
            }
            if (expression instanceof SequenceExpression sequence) {
                Evaluated value = new Evaluated(0L, null);
                for (Expression nested : sequence.values()) value = evaluate(nested, scope);
                return value;
            }
            if (expression instanceof VariableExpression variable) {
                Slot slot = scope.resolve(variable.name());
                if (slot == null) fail(variable.line(), variable.column(), "Unknown variable: " + variable.name());
                return new Evaluated(slot.value, slot);
            }
            if (expression instanceof UnaryExpression unary) {
                Object value = evaluate(unary.expression(), scope).value;
                return new Evaluated(switch (unary.operator()) {
                    case "!" -> truth(value) ? 0L : 1L;
                    case "~" -> ~number(value);
                    case "-" -> -number(value);
                    case "+" -> number(value);
                    default -> throw new IllegalStateException(unary.operator());
                }, null);
            }
            if (expression instanceof PrefixExpression prefix) {
                Evaluated target = evaluate(prefix.expression(), scope);
                if (target.slot == null) fail(prefix.line(), prefix.column(), "Increment target is not writable.");
                long value = number(target.value) + (prefix.operator().equals("++") ? 1 : -1);
                target.slot.value = value;
                return new Evaluated(value, target.slot);
            }
            if (expression instanceof CastExpression cast) {
                Object value = evaluate(cast.expression(), scope).value;
                TemplateType target = requireType(cast.type(), 0, 0);
                if (target instanceof PrimitiveType primitive && primitive.kind() == PrimitiveKind.FLOAT) {
                    return new Evaluated(value instanceof Number number ? number.doubleValue() : 0.0, null);
                }
                if (target instanceof PrimitiveType || target instanceof EnumType) {
                    return new Evaluated(number(value), null);
                }
                return new Evaluated(value, null);
            }
            if (expression instanceof BinaryExpression binary) {
                Evaluated left = evaluate(binary.left(), scope);
                if (binary.operator().equals("&&") && !truth(left.value)) return new Evaluated(0L, null);
                if (binary.operator().equals("||") && truth(left.value)) return new Evaluated(1L, null);
                Object right = evaluate(binary.right(), scope).value;
                return new Evaluated(binary(left.value, binary.operator(), right), null);
            }
            if (expression instanceof ConditionalExpression conditional) {
                return evaluate(truth(evaluate(conditional.condition(), scope).value)
                        ? conditional.whenTrue() : conditional.whenFalse(), scope);
            }
            if (expression instanceof CallExpression call) {
                if (call.name().equals("sizeof") && call.arguments().size() == 1
                        && call.arguments().getFirst() instanceof VariableExpression typeName
                        && types.containsKey(typeName.name())) {
                    return new Evaluated(typeSize(types.get(typeName.name())), null);
                }
                if (call.name().equals("exists")) {
                    try {
                        evaluate(call.arguments().getFirst(), scope);
                        return new Evaluated(1L, null);
                    } catch (RuntimeFailure ignored) {
                        return new Evaluated(0L, null);
                    }
                }
                List<Evaluated> arguments = new ArrayList<>();
                for (Expression argument : call.arguments()) arguments.add(evaluate(argument, scope));
                return new Evaluated(call(call, arguments, scope), null);
            }
            if (expression instanceof MemberExpression member) {
                Object target = evaluate(member.target(), scope).value;
                if (target instanceof DuplicateValues duplicates && !duplicates.values.isEmpty()) {
                    target = duplicates.values.getLast();
                }
                if (target instanceof IndexedValue indexed && member.member().equals("count")) {
                    return new Evaluated(indexed.size(), null);
                }
                if (!(target instanceof Instance)) fail(member.line(), member.column(), "Value has no members: " + text(target));
                Instance instance = (Instance) target;
                Slot slot = instance.scope.local(member.member());
                if (slot == null) fail(member.line(), member.column(), "Unknown member: " + member.member());
                return new Evaluated(slot.value, slot);
            }
            if (expression instanceof IndexExpression indexExpression) {
                Object target = evaluate(indexExpression.target(), scope).value;
                long index = number(evaluate(indexExpression.index(), scope).value);
                Slot slot = target instanceof ObjectArray array ? array.slot(index) : null;
                return new Evaluated(index(target, index, indexExpression.line(), indexExpression.column()), slot);
            }
            if (expression instanceof AssignmentExpression assignment) {
                Evaluated target = evaluate(assignment.target(), scope);
                if (target.slot == null) fail(assignment.line(), assignment.column(), "Assignment target is not writable.");
                Object right = evaluate(assignment.value(), scope).value;
                Object result = assignment.operator().equals("=") ? right
                        : binary(target.value, assignment.operator().substring(0, assignment.operator().length() - 1), right);
                target.slot.value = result;
                return new Evaluated(result, target.slot);
            }
            if (expression instanceof PostfixExpression postfix) {
                Evaluated target = evaluate(postfix.target(), scope);
                if (target.slot == null) fail(postfix.line(), postfix.column(), "Increment target is not writable.");
                long before = number(target.value);
                target.slot.value = postfix.operator().equals("++") ? before + 1 : before - 1;
                return new Evaluated(before, target.slot);
            }
            throw new IllegalStateException("Unknown expression " + expression);
        }

        private Object binary(Object left, String operator, Object right) {
            return switch (operator) {
                case "+" -> left instanceof String || right instanceof String ? text(left) + text(right)
                        : floating(left, right) ? decimal(left) + decimal(right) : number(left) + number(right);
                case "-" -> floating(left, right) ? decimal(left) - decimal(right) : number(left) - number(right);
                case "*" -> floating(left, right) ? decimal(left) * decimal(right) : number(left) * number(right);
                case "/" -> floating(left, right) ? decimal(left) / decimal(right) : number(left) / number(right);
                case "%" -> floating(left, right) ? decimal(left) % decimal(right) : number(left) % number(right);
                case "<<" -> number(left) << number(right);
                case ">>" -> number(left) >> number(right);
                case "&" -> number(left) & number(right);
                case "|" -> number(left) | number(right);
                case "^" -> number(left) ^ number(right);
                case "==" -> equal(left, right) ? 1L : 0L;
                case "!=" -> equal(left, right) ? 0L : 1L;
                case "<" -> compare(left, right) < 0 ? 1L : 0L;
                case "<=" -> compare(left, right) <= 0 ? 1L : 0L;
                case ">" -> compare(left, right) > 0 ? 1L : 0L;
                case ">=" -> compare(left, right) >= 0 ? 1L : 0L;
                case "&&" -> truth(left) && truth(right) ? 1L : 0L;
                case "||" -> truth(left) || truth(right) ? 1L : 0L;
                default -> throw new IllegalStateException("Unknown operator " + operator);
            };
        }

        private Object call(CallExpression call, List<Evaluated> arguments, Scope scope) {
            return switch (call.name()) {
                case "BigEndian" -> { bigEndian = true; yield 0L; }
                case "LittleEndian" -> { bigEndian = false; yield 0L; }
                case "FTell" -> cursor;
                case "FSeek" -> { cursor = checkedPosition(argumentNumber(arguments, 0), call); yield 0L; }
                case "FSkip" -> { cursor = checkedPosition(cursor + argumentNumber(arguments, 0), call); yield 0L; }
                case "FEof" -> cursor >= input.length() ? 1L : 0L;
                case "FileSize" -> input.length();
                case "Strlen" -> (long) text(argument(arguments, 0)).length();
                case "sizeof" -> arguments.getFirst().slot != null && arguments.getFirst().slot.size >= 0
                        ? arguments.getFirst().slot.size : valueSize(argument(arguments, 0));
                case "startof" -> arguments.getFirst().slot != null && arguments.getFirst().slot.offset >= 0
                        ? arguments.getFirst().slot.offset : 0L;
                case "parentof" -> argument(arguments, 0) instanceof Instance instance && instance.parent() != null
                        ? instance.parent() : argument(arguments, 0);
                case "IsBigEndian" -> bigEndian ? 1L : 0L;
                case "Strcmp" -> (long) text(argument(arguments, 0)).compareTo(text(argument(arguments, 1)));
                case "GetSelSize" -> input.length();
                case "GetSelStart" -> 0L;
                case "GetFileName" -> "";
                case "GetCursorPos" -> cursor;
                case "FindFirst" -> -1L;
                case "FindAll" -> ObjectArray.of(List.of());
                case "Memcmp" -> memcmp(arguments);
                case "TimeTToString", "GUIDToString", "DisplayFormatHex" -> text(argument(arguments, 0));
                case "BitfieldLeftToRight" -> { bitfieldLeftToRight = true; yield 0L; }
                case "BitfieldRightToLeft" -> { bitfieldLeftToRight = false; yield 0L; }
                case "RequiresVersion", "BitfieldDisablePadding", "BitfieldEnablePadding", "SetReadOnly", "SetStyle",
                     "OutputPaneClear", "ThemeAutoScaleColors" -> 0L;
                case "Assert" -> {
                    if (!truth(argument(arguments, 0))) diagnostics.add(new BtDiagnostic(BtDiagnostic.Severity.WARNING,
                            call.line(), call.column(), arguments.size() > 1 ? text(argument(arguments, 1)) : "Assertion failed"));
                    yield 0L;
                }
                case "Exit" -> throw new ReturnSignal(arguments.isEmpty() ? 0L : argument(arguments, 0));
                case "Align" -> {
                    long alignment = Math.max(1, argumentNumber(arguments, 0));
                    long aligned = (cursor + alignment - 1) / alignment * alignment;
                    cursor = checkedPosition(aligned, call);
                    yield cursor;
                }
                case "GetBackColor" -> currentBackground == null ? new ColorValue("cNone") : new ColorValue(currentBackground);
                case "GetForeColor" -> currentForeground == null ? new ColorValue("cNone") : new ColorValue(currentForeground);
                case "Pow" -> (long) Math.pow(argumentNumber(arguments, 0), argumentNumber(arguments, 1));
                case "Ceil" -> Math.ceil(decimal(argument(arguments, 0)));
                case "Floor" -> Math.floor(decimal(argument(arguments, 0)));
                case "Abs" -> Math.abs(argumentNumber(arguments, 0));
                case "Min" -> Math.min(argumentNumber(arguments, 0), argumentNumber(arguments, 1));
                case "Max" -> Math.max(argumentNumber(arguments, 0), argumentNumber(arguments, 1));
                case "Str" -> format(arguments, 0);
                case "EnumToString" -> text(argument(arguments, 0));
                case "ReadString" -> readStringBuiltin(arguments, call, false);
                case "ReadWString" -> readStringBuiltin(arguments, call, true);
                case "ReadBytes" -> readBytes(arguments, call);
                case "SetBackColor" -> { currentBackground = colorName(argument(arguments, 0)); yield 0L; }
                case "SetForeColor" -> { currentForeground = colorName(argument(arguments, 0)); yield 0L; }
                case "SetColor" -> {
                    currentForeground = colorName(argument(arguments, 0));
                    currentBackground = colorName(argument(arguments, 1));
                    yield 0L;
                }
                case "Warning" -> {
                    String message = text(argument(arguments, 0));
                    diagnostics.add(new BtDiagnostic(BtDiagnostic.Severity.WARNING, call.line(), call.column(), message));
                    yield 0L;
                }
                case "Printf" -> {
                    String message = arguments.size() == 1 ? text(argument(arguments, 0)) : format(arguments, 0);
                    output.add(message);
                    yield (long) message.length();
                }
                case "SPrintf" -> {
                    if (arguments.isEmpty() || arguments.get(0).slot == null) fail(call.line(), call.column(), "SPrintf requires a writable first argument.");
                    String message = format(arguments, 1);
                    arguments.get(0).slot.value = message;
                    yield (long) message.length();
                }
                case "Checksum" -> checksum(arguments, call);
                case "ReadByte", "ReadUByte" -> readBuiltin(arguments, call, 1, call.name().equals("ReadByte"));
                case "ReadShort", "ReadUShort" -> readBuiltin(arguments, call, 2, call.name().equals("ReadShort"));
                case "ReadInt", "ReadUInt" -> readBuiltin(arguments, call, 4, call.name().equals("ReadInt"));
                case "ReadInt64", "ReadUInt64", "ReadQuad", "ReadUQuad" -> readBuiltin(arguments, call, 8,
                        call.name().equals("ReadInt64") || call.name().equals("ReadQuad"));
                default -> {
                    FunctionDefinition function = functions.get(call.name());
                    if (function == null && (call.name().startsWith("Highlight") || call.name().startsWith("Theme")
                            || call.name().startsWith("OutputPane"))) yield 0L;
                    if (function == null) fail(call.line(), call.column(), "Unsupported or unknown function: " + call.name());
                    yield callCustom(function, arguments, scope);
                }
            };
        }

        private Object callCustom(FunctionDefinition function, List<Evaluated> arguments, Scope caller) {
            if (++callDepth > MAX_CALL_DEPTH) fail(function.line(), function.column(), "Template call stack limit exceeded.");
            try {
                Scope scope = new Scope(globals);
                for (int i = 0; i < function.parameters().size(); i++) {
                    Parameter parameter = function.parameters().get(i);
                    Evaluated argument = i < arguments.size() ? arguments.get(i) : new Evaluated(0L, null);
                    if (parameter.reference() && argument.slot != null) scope.define(parameter.name(), argument.slot, true);
                    else scope.define(parameter.name(), new Slot(argument.value), true);
                }
                Frame frame = new Frame(scope, new ArrayList<>());
                try {
                    for (Statement statement : function.body().statements()) execute(statement, frame);
                } catch (ReturnSignal signal) {
                    return signal.value;
                }
                return 0L;
            } finally {
                callDepth--;
            }
        }

        private long checksum(List<Evaluated> arguments, CallExpression call) {
            long algorithm = argumentNumber(arguments, 0);
            if (algorithm != 1L) fail(call.line(), call.column(), "Only CHECKSUM_CRC32 is supported.");
            long start = argumentNumber(arguments, 1);
            long length = argumentNumber(arguments, 2);
            ensureAvailable(start, length, "Checksum");
            CRC32 crc = new CRC32();
            long remaining = length;
            long offset = start;
            while (remaining > 0) {
                tick(1);
                int count = (int) Math.min(64 * 1024, remaining);
                byte[] data = input.read(offset, count);
                if (data.length != count) fail(call.line(), call.column(), "Unexpected end of file during checksum.");
                crc.update(data);
                offset += count;
                remaining -= count;
            }
            return crc.getValue();
        }

        private long readBuiltin(List<Evaluated> arguments, CallExpression call, int size, boolean signed) {
            long position = arguments.isEmpty() ? cursor : argumentNumber(arguments, 0);
            ensureAvailable(position, size, call.name());
            return readInteger(position, size, signed);
        }

        private String readStringBuiltin(List<Evaluated> arguments, CallExpression call, boolean wide) {
            long position = arguments.isEmpty() ? cursor : argumentNumber(arguments, 0);
            if (position < 0 || position > input.length()) fail(call.line(), call.column(), "String position is out of range: " + position);
            long maximum = arguments.size() > 1 ? Math.max(0, argumentNumber(arguments, 1)) : MAX_STRING_BYTES;
            StringBuilder value = new StringBuilder();
            long consumed = 0;
            int unit = wide ? 2 : 1;
            while (position + consumed + unit <= input.length() && consumed / unit < maximum) {
                long character = readInteger(position + consumed, unit, false);
                consumed += unit;
                if (character == 0) break;
                value.append((char) character);
            }
            return value.toString();
        }

        private long readBytes(List<Evaluated> arguments, CallExpression call) {
            if (arguments.size() < 3) fail(call.line(), call.column(), "ReadBytes requires destination, offset, and length.");
            long position = argumentNumber(arguments, 1);
            int length = Math.toIntExact(Math.max(0, argumentNumber(arguments, 2)));
            ensureAvailable(position, length, "ReadBytes");
            byte[] bytes = input.read(position, length);
            Object destination = argument(arguments, 0);
            if (destination instanceof ObjectArray array) {
                int count = Math.min(bytes.length, array.values().size());
                for (int i = 0; i < count; i++) array.values().get(i).value = (long) (bytes[i] & 0xff);
            }
            return bytes.length;
        }

        private long memcmp(List<Evaluated> arguments) {
            String left = text(argument(arguments, 0));
            String right = text(argument(arguments, 1));
            int length = arguments.size() > 2 ? (int) Math.max(0, argumentNumber(arguments, 2))
                    : Math.min(left.length(), right.length());
            for (int i = 0; i < length; i++) {
                int a = i < left.length() ? left.charAt(i) & 0xff : 0;
                int b = i < right.length() ? right.charAt(i) & 0xff : 0;
                if (a != b) return a - b;
            }
            return 0L;
        }

        private long valueSize(Object value) {
            if (value instanceof String string) return string.length();
            if (value instanceof PrimitiveArray array) return array.count * array.type.size();
            if (value instanceof ObjectArray array) return array.values().size();
            if (value instanceof Instance instance) {
                Slot slot = instance.scope().local("this");
                return slot == null ? 0 : Math.max(0, slot.size);
            }
            return value instanceof Number ? 8 : 0;
        }

        private long typeSize(TemplateType type) {
            if (type instanceof PrimitiveType primitive) return primitive.size();
            if (type instanceof EnumType enumType) return enumType.base().size();
            if (type instanceof ArrayType array) return Math.max(0, array.count()) * typeSize(array.element());
            return 0L;
        }

        private String format(List<Evaluated> arguments, int formatIndex) {
            if (formatIndex >= arguments.size()) return "";
            String pattern = text(arguments.get(formatIndex).value);
            StringBuilder result = new StringBuilder();
            int argument = formatIndex + 1;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c != '%' || i + 1 >= pattern.length()) {
                    result.append(c);
                    continue;
                }
                if (pattern.charAt(i + 1) == '%') {
                    result.append('%');
                    i++;
                    continue;
                }
                int cursor = i + 1;
                boolean zeroPad = cursor < pattern.length() && pattern.charAt(cursor) == '0';
                if (zeroPad) cursor++;
                int width = 0;
                while (cursor < pattern.length() && Character.isDigit(pattern.charAt(cursor))) {
                    width = width * 10 + (pattern.charAt(cursor++) - '0');
                }
                if (cursor >= pattern.length()) break;
                char specifier = pattern.charAt(cursor);
                Object value = argument < arguments.size() ? arguments.get(argument++).value : 0L;
                String formatted = switch (specifier) {
                    case 's' -> text(value);
                    case 'x', 'X' -> Long.toUnsignedString(number(value), 16);
                    case 'd', 'i', 'u' -> Long.toString(number(value));
                    case 'c' -> Character.toString((char) number(value));
                    default -> "%" + specifier;
                };
                if (specifier == 'X') formatted = formatted.toUpperCase(Locale.ROOT);
                if (width > formatted.length()) formatted = String.valueOf(zeroPad ? '0' : ' ').repeat(width - formatted.length()) + formatted;
                result.append(formatted);
                i = cursor;
            }
            return result.toString();
        }

        private Object index(Object target, long index, int line, int column) {
            if (index < 0 || index > Integer.MAX_VALUE) fail(line, column, "Array index is out of range: " + index);
            if (target instanceof IndexedValue indexed) return indexed.get(index);
            if (target instanceof Instance && index == 0) return target;
            if (target instanceof String string) {
                if (index >= string.length()) fail(line, column, "String index is out of range: " + index);
                return (long) string.charAt((int) index);
            }
            fail(line, column, "Value is not indexable: " + text(target));
            return null;
        }

        private String arrayDisplay(PrimitiveArray array, PrimitiveType type, long count) {
            if (type.name().equals("char") || type.name().equals("byte")) {
                int display = (int) Math.min(count, 4096);
                StringBuilder text = new StringBuilder(display);
                for (int i = 0; i < display; i++) text.append((char) (number(array.get(i)) & 0xff));
                if (count > display) text.append('…');
                return text.toString();
            }
            int display = (int) Math.min(count, 16);
            StringBuilder text = new StringBuilder("[");
            for (int i = 0; i < display; i++) {
                if (i > 0) text.append(", ");
                text.append(formatValue(array.get(i), type, Attributes.EMPTY, null));
            }
            if (count > display) text.append(", …");
            return text.append(']').toString();
        }

        private String formatValue(Object value, PrimitiveType type, Attributes attributes, Map<Long, String> enumNames) {
            if (enumNames != null) {
                String name = enumNames.get(number(value));
                if (name != null) return name + " (" + number(value) + ")";
            }
            Expression format = attributes.get("format");
            String formatName = format instanceof LiteralExpression literal ? text(literal.value) : null;
            if ("hex".equals(formatName)) {
                int width = Math.max(2, type.size() * 2);
                return "0x" + String.format("%0" + width + "X", number(value));
            }
            if (type.kind() == PrimitiveKind.STRING) return text(value);
            if (type.kind() == PrimitiveKind.BYTES) return text(value);
            if (type.kind() == PrimitiveKind.FLOAT) return String.valueOf(value);
            return Long.toString(number(value));
        }

        private Object defaultValue(TemplateType type) {
            if (type instanceof PrimitiveType primitive && primitive.kind() == PrimitiveKind.STRING) return "";
            return 0L;
        }

        private TemplateType requireType(String name, int line, int column) {
            TemplateType type = types.get(name);
            if (type == null) fail(line, column, "Unknown type: " + name);
            return type;
        }

        private String displayType(TemplateType type) {
            if (type instanceof PrimitiveType primitive) return primitive.name();
            if (type instanceof EnumType enumType) return enumType.name();
            if (type instanceof ArrayType arrayType) return arrayType.name();
            return ((AggregateType) type).definition().name();
        }

        private long checkedPosition(long position, CallExpression call) {
            if (position < 0 || position > input.length()) fail(call.line(), call.column(), "File position is out of range: " + position);
            return position;
        }

        private void ensureAvailable(long offset, long length, String operation) {
            if (offset < 0 || length < 0 || offset > input.length() || length > input.length() - offset) {
                fail(0, 0, operation + " reads outside the file at 0x" + Long.toHexString(offset).toUpperCase(Locale.ROOT)
                        + " (size " + length + ").");
            }
        }

        private long multiplyExact(long left, long right, String name) {
            try {
                return Math.multiplyExact(left, right);
            } catch (ArithmeticException exception) {
                fail(0, 0, "Array byte size overflows for " + name + '.');
                return 0;
            }
        }

        private void tick(long count) {
            steps += count;
            if (steps > MAX_STEPS) fail(0, 0, "Template execution step limit exceeded.");
            if ((steps & 0x3ff) == 0 && canceled.getAsBoolean()) throw new CanceledFailure();
        }

        private void fail(int line, int column, String message) { throw new RuntimeFailure(line, column, message, rootNodes); }

        private static Object argument(List<Evaluated> arguments, int index) {
            return index < arguments.size() ? arguments.get(index).value : 0L;
        }
        private static long argumentNumber(List<Evaluated> arguments, int index) { return number(argument(arguments, index)); }
        private static String colorName(Object value) {
            if (value instanceof ColorValue color) return color.name.equals("cNone") ? null : color.name;
            if (value instanceof Number number) return String.format("#%06X", number.longValue() & 0xffffffL);
            return null;
        }
        private static boolean truth(Object value) {
            if (value == null) return false;
            if (value instanceof Number number) return number.longValue() != 0;
            if (value instanceof String string) return !string.isEmpty();
            return true;
        }
        private static long number(Object value) {
            if (value instanceof Number number) return number.longValue();
            if (value instanceof String string) {
                try { return Long.parseLong(string); } catch (NumberFormatException ignored) { return 0L; }
            }
            return 0L;
        }
        private static double decimal(Object value) {
            if (value instanceof Number number) return number.doubleValue();
            if (value instanceof String string) {
                try { return Double.parseDouble(string); } catch (NumberFormatException ignored) { return 0.0; }
            }
            return 0.0;
        }
        private static boolean floating(Object left, Object right) {
            return left instanceof Float || left instanceof Double || right instanceof Float || right instanceof Double;
        }
        private static String text(Object value) {
            if (value == null) return "";
            if (value instanceof String string) return string;
            if (value instanceof ColorValue color) return color.name;
            return String.valueOf(value);
        }
        private static boolean equal(Object left, Object right) {
            if (left instanceof Number && right instanceof Number) return floating(left, right)
                    ? Double.compare(decimal(left), decimal(right)) == 0 : number(left) == number(right);
            return Objects.equals(text(left), text(right));
        }
        private static int compare(Object left, Object right) {
            if (left instanceof Number && right instanceof Number) return floating(left, right)
                    ? Double.compare(decimal(left), decimal(right)) : Long.compare(number(left), number(right));
            return text(left).compareTo(text(right));
        }
    }

    private record Frame(Scope scope, List<MutableNode> nodes, BitfieldState bitfields) {
        private Frame(Scope scope, List<MutableNode> nodes) { this(scope, nodes, new BitfieldState()); }
        Frame withNodes(List<MutableNode> replacement) { return new Frame(scope, replacement, bitfields); }
    }
    private static final class BitfieldState {
        private boolean active;
        private PrimitiveType type;
        private long offset;
        private long raw;
        private int consumed;
        private void reset() {
            active = false;
            type = null;
            consumed = 0;
        }
    }
    private record ParsedValue(Object value, MutableNode node) {}
    private record Evaluated(Object value, Slot slot) {}
    private record ColorValue(String name) {}

    private static final class Scope {
        private final Scope parent;
        private final Map<String, Slot> variables = new LinkedHashMap<>();
        private Scope(Scope parent) { this.parent = parent; }
        private Slot resolve(String name) {
            Slot local = variables.get(name);
            return local != null ? local : parent == null ? null : parent.resolve(name);
        }
        private Slot local(String name) { return variables.get(name); }
        private void define(String name, Slot slot, boolean replace) {
            Slot existing = variables.get(name);
            if (existing == null || replace) {
                variables.put(name, slot);
            } else if (existing.value instanceof DuplicateValues duplicate) {
                duplicate.values.add(slot.value);
            } else {
                ArrayList<Object> values = new ArrayList<>();
                values.add(existing.value);
                values.add(slot.value);
                existing.value = new DuplicateValues(values);
            }
        }
    }

    private static final class Slot {
        private Object value;
        private long offset;
        private long size;
        private Slot(Object value) { this(value, -1, -1); }
        private Slot(Object value, long offset, long size) {
            this.value = value;
            this.offset = offset;
            this.size = size;
        }
    }
    private record Instance(Scope scope, Instance parent) {
        @Override public String toString() { return "struct"; }
    }
    private sealed interface IndexedValue permits PrimitiveArray, ObjectArray, DuplicateValues {
        Object get(long index);
        long size();
    }
    private static final class PrimitiveArray implements IndexedValue {
        private final Runtime runtime;
        private final PrimitiveType type;
        private final long offset;
        private final long count;
        private PrimitiveArray(Runtime runtime, PrimitiveType type, long offset, long count) {
            this.runtime = runtime;
            this.type = type;
            this.offset = offset;
            this.count = count;
        }
        @Override public Object get(long index) {
            if (index < 0 || index >= count) throw new RuntimeFailure(0, 0, "Array index is out of range: " + index, List.of());
            return runtime.readInteger(offset + index * type.size(), type.size(), type.signed());
        }
        @Override public long size() { return count; }
        @Override public String toString() {
            if (!type.name().equals("char") && !type.name().equals("byte")) return "[" + count + "]";
            int length = (int) Math.min(count, MAX_STRING_BYTES);
            byte[] bytes = runtime.input.read(offset, length);
            StringBuilder value = new StringBuilder(bytes.length);
            for (byte current : bytes) value.append((char) (current & 0xff));
            return value.toString();
        }
    }
    private record ObjectArray(ArrayList<Slot> values) implements IndexedValue {
        private static ObjectArray of(List<Object> values) {
            ArrayList<Slot> slots = new ArrayList<>(values.size());
            for (Object value : values) slots.add(new Slot(value));
            return new ObjectArray(slots);
        }
        private static ObjectArray filled(int count, Object value) {
            ArrayList<Slot> slots = new ArrayList<>(count);
            for (int i = 0; i < count; i++) slots.add(new Slot(value));
            return new ObjectArray(slots);
        }
        private ObjectArray resized(int count, Object fallback) {
            ArrayList<Slot> slots = new ArrayList<>(count);
            for (int i = 0; i < count; i++) slots.add(i < values.size() ? values.get(i) : new Slot(fallback));
            return new ObjectArray(slots);
        }
        private Slot slot(long index) {
            if (index < 0 || index >= values.size()) throw new RuntimeFailure(0, 0, "Array index is out of range: " + index, List.of());
            return values.get((int) index);
        }
        @Override public Object get(long index) {
            return slot(index).value;
        }
        @Override public long size() { return values.size(); }
    }
    private record DuplicateValues(ArrayList<Object> values) implements IndexedValue {
        @Override public Object get(long index) {
            if (index < 0 || index >= values.size()) throw new RuntimeFailure(0, 0, "Duplicate index is out of range: " + index, List.of());
            return values.get((int) index);
        }
        @Override public long size() { return values.size(); }
    }

    private static final class MutableNode {
        private final String name;
        private final String type;
        private final long offset;
        private long size;
        private String value = "";
        private String format;
        private String foregroundColor;
        private String backgroundColor;
        private String comment;
        private final List<MutableNode> children = new ArrayList<>();
        private MutableNode(String name, String type, long offset) {
            this.name = name;
            this.type = type;
            this.offset = offset;
        }
    }

    private static final class ParseFailure extends RuntimeException {
        private final int line;
        private final int column;
        private ParseFailure(int line, int column, String message) {
            super(message);
            this.line = line;
            this.column = column;
        }
    }
    private static final class RuntimeFailure extends RuntimeException {
        private final int line;
        private final int column;
        private List<MutableNode> partialNodes;
        private RuntimeFailure(int line, int column, String message, List<MutableNode> partialNodes) {
            super(message);
            this.line = line;
            this.column = column;
            this.partialNodes = partialNodes;
        }
    }
    private static final class CanceledFailure extends RuntimeException {}
    private static final class ReturnSignal extends RuntimeException {
        private final Object value;
        private ReturnSignal(Object value) { this.value = value; }
    }
    private static final class BreakSignal extends RuntimeException {}
    private static final class ContinueSignal extends RuntimeException {}
}
