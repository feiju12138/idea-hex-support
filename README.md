# Hex Support

An IntelliJ IDEA plugin that opens and edits files in a hexadecimal view.

## Features

- Hex byte table with offset column, editable byte cells, and a raw (ASCII) preview column.
- All editing actions live on the top toolbar: Save, Save As, Reload, Undo/Redo, Copy/Cut/Paste Before/Paste After, Insert 1/N Zeros Before/After, Fragment Export, and Fragment Import at File Head/Tail/After Selection.
- Configurable bytes per row (4–32).
- Go To Offset (Ctrl+G) accepting hex (`0x...`) or decimal.
- Find / Replace bar (Ctrl+F / Ctrl+R) with a paired ASCII string field that converts to hex bytes via ISO-8859-1 1:1 mapping. Replace, Replace All, Delete, Delete All, Zero, and Zero All operate on the active match or every match.
- Multi-selection editing: Ctrl+click adds non-contiguous selections, Shift+arrows/click extends, Esc clears. Typing a hex char broadcasts to all selected cells. Ctrl+C/Ctrl+V copy and paste per same-row group. Ctrl+Shift+I inverts the selection.
- Search results highlight every match (orange) with the active match in blue; Up/Down arrows move the active match.
- Undo/Redo (Ctrl+Z / Ctrl+Shift+Z) with full history.
- Save writes back to the original file via VFS; Save As writes to a chosen path; Fragment Export writes only the selected bytes; Fragment Import inserts another file's content at the head, tail, or after the current selection.
- Native IntelliJ look: uses IDE UI colors, borders, fonts, and selection styling.
- Localized in English and Simplified Chinese (follows the IDE language setting).

## Keyboard Shortcuts

| Action | Shortcut |
| --- | --- |
| Save | Ctrl+S |
| Undo / Redo | Ctrl+Z / Ctrl+Shift+Z |
| Copy / Cut / Paste | Ctrl+C / Ctrl+X / Ctrl+V |
| Select All | Ctrl+A |
| Invert Selection | Ctrl+Shift+I |
| Go To Offset | Ctrl+G |
| Find / Replace | Ctrl+F / Ctrl+R |
| Next / Previous match (in find field) | Enter / Shift+Enter |
| Toggle Replace row | Ctrl+R (in find/replace field) |
| Clear selection / close find bar | Esc |
| Start editing a byte | Enter, Space, or type 0–9 / a–f |
| Commit edit and move to next cell | Tab |

## Build

Requires JDK 21 and Gradle. Run the `buildPlugin` task:

```
gradle buildPlugin
```

The plugin zip is written to `build/distributions/`.

## Compatibility

- IntelliJ IDEA 2023.2+ (build 232) through 2026.x.
- JDK 21.
- Verified locally on IntelliJ IDEA 2026.1 with Gradle 9.6.0 and JDK 21.0.2.
