package cn.fj.loli.hexsupport;

import com.intellij.openapi.diff.DiffColors;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/** Keeps the Swing-based hex views visually aligned with the active IDEA editor scheme. */
final class HexEditorStyle {
    private HexEditorStyle() {
    }

    static EditorColorsScheme scheme() {
        return EditorColorsManager.getInstance().getGlobalScheme();
    }

    static Font font() {
        Font font = scheme().getFont(EditorFontType.PLAIN);
        return font == null ? new Font(Font.MONOSPACED, Font.PLAIN, 13) : font;
    }

    static float lineSpacing() {
        return Math.max(1.0f, scheme().getLineSpacing());
    }

    static Color editorBackground() {
        return fallback(scheme().getDefaultBackground(), JBColor.PanelBackground);
    }

    static Color editorForeground() {
        return fallback(scheme().getDefaultForeground(), JBColor.foreground());
    }

    static Color gutterBackground() {
        Color color = scheme().getColor(EditorColors.EDITOR_GUTTER_BACKGROUND);
        if (color == null) color = scheme().getColor(EditorColors.GUTTER_BACKGROUND);
        return fallback(color, editorBackground());
    }

    static Color lineNumberForeground() {
        return fallback(scheme().getColor(EditorColors.LINE_NUMBERS_COLOR), JBColor.GRAY);
    }

    static Color activeLineNumberForeground() {
        return fallback(scheme().getColor(EditorColors.LINE_NUMBER_ON_CARET_ROW_COLOR), editorForeground());
    }

    static Color caretRowBackground() {
        return fallback(scheme().getColor(EditorColors.CARET_ROW_COLOR), gutterBackground());
    }

    static Color selectionBackground() {
        Color fallback = UIManager.getColor("Table.selectionBackground");
        return fallback(scheme().getColor(EditorColors.SELECTION_BACKGROUND_COLOR),
                fallback(fallback, new JBColor(0xA6D2FF, 0x214283)));
    }

    static Color selectionForeground() {
        Color fallback = UIManager.getColor("Table.selectionForeground");
        return fallback(scheme().getColor(EditorColors.SELECTION_FOREGROUND_COLOR),
                fallback(fallback, editorForeground()));
    }

    static Color searchBackground() {
        return attributesBackground(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES,
                new JBColor(0xF5D76E, 0x675F20));
    }

    static Color diffBackground(HexDiffAlignment.Kind kind) {
        return switch (kind) {
            case MODIFIED -> attributesBackground(DiffColors.DIFF_MODIFIED,
                    new JBColor(0xFFF0B3, 0x4D4520));
            case REMOVED -> attributesBackground(DiffColors.DIFF_DELETED,
                    new JBColor(0xFFD7D7, 0x573333));
            case ADDED -> attributesBackground(DiffColors.DIFF_INSERTED,
                    new JBColor(0xD7F2D7, 0x294A2D));
            case EQUAL -> editorBackground();
        };
    }

    static Color diffForeground(HexDiffAlignment.Kind kind) {
        TextAttributesKey key = switch (kind) {
            case MODIFIED -> DiffColors.DIFF_MODIFIED;
            case REMOVED -> DiffColors.DIFF_DELETED;
            case ADDED -> DiffColors.DIFF_INSERTED;
            case EQUAL -> null;
        };
        if (key == null) return editorForeground();
        TextAttributes attributes = scheme().getAttributes(key);
        return attributes == null ? editorForeground() : fallback(attributes.getForegroundColor(), editorForeground());
    }

    private static Color attributesBackground(TextAttributesKey key, Color fallback) {
        TextAttributes attributes = scheme().getAttributes(key);
        return attributes == null ? fallback : fallback(attributes.getBackgroundColor(), fallback);
    }

    private static Color fallback(Color color, Color fallback) {
        return color == null ? fallback : color;
    }
}
