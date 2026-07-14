# Hex Support

Hex Support is an IntelliJ IDEA plugin for viewing, editing, and comparing files as hexadecimal data.

## Highlights

### Hex editor

- Displays offsets, editable hexadecimal byte cells, and a raw ASCII preview in a native IntelliJ editor-style table.
- Supports direct byte overwrite, multi-selection, copy, cut, paste, zero-fill, insertion, deletion, and selection inversion.
- Provides Save, Save As, Reload, fragment import, and selected-fragment export from the editor toolbar.
- Uses a large-file editing model with 64-bit offsets, paged reads, and streaming save/import/export operations.
- Allows the number of bytes per row to be configured. For very large files, the minimum is adjusted automatically so the table can reach the actual end of the file.
- Follows the active IDE color scheme, editor font, font size, line spacing, selection colors, search colors, and line-number gutter style.

### Search and navigation

- Go to an offset in hexadecimal (`0x...`) or decimal form.
- Find hexadecimal byte patterns and replace, delete, or zero the current match or all matches.
- Search through the paired text field using the file charset detected from the IDE File Encoding setting, including multibyte text such as CJK characters.
- Runs large searches in the background to keep the UI responsive.

### Undo and operation history

- Integrates Undo/Redo with IntelliJ's undo system.
- Shows edit operations in the **Hex History** tool window and supports undoing or redoing directly to a selected history entry.
- Can export operation history manually or automatically, with an option to remove the history file after saving.

### Hex diff

- Adds selectable side-by-side and unified Hex viewers to the IntelliJ Diff window.
- Aligns bytes and highlights inserted, deleted, and modified data with native IDE diff colors.
- Supports synchronized scrolling, previous/next difference navigation, configurable bytes per row, and native editor fonts and gutter styling.
- **Jump to Source** opens the corresponding file in the editable Hex editor and restores the exact active byte. Side-by-side mode follows the focused side; unified mode follows the selected old or new row, including insert/delete gap fallback.

### Localization

- Includes English and Simplified Chinese interfaces and follows the IDE language setting.

## Keyboard shortcuts

Shortcuts use the platform menu modifier: `Ctrl` on Windows/Linux and `Command` on macOS.

| Action | Shortcut |
| --- | --- |
| Save | Ctrl/Command+S |
| Undo / Redo | Ctrl/Command+Z / Ctrl/Command+Shift+Z |
| Copy / Cut / Paste | Ctrl/Command+C / Ctrl/Command+X / Ctrl/Command+V |
| Select all | Ctrl/Command+A |
| Invert selection | Ctrl/Command+Shift+I |
| Go to offset | Ctrl/Command+G |
| Find / Replace | Ctrl/Command+F / Ctrl/Command+R |
| Next / previous match | Enter / Shift+Enter |
| Clear selection or close the find bar | Esc |
| Zero selected bytes | Backspace |
| Delete selected bytes | Delete |
| Start editing a byte | Enter, Space, or `0`-`9` / `A`-`F` |
| Commit the byte and move to the next cell | Tab |
| Next / previous difference | F7 / Shift+F7 |

## Release 2.1.0

Version `2.1.0` is the consolidated release of Hex Support. It includes the complete editable Hex workflow, large-file support, charset-aware text search, operation history, and both side-by-side and unified Hex Diff viewers. Hex Diff also includes byte-level alignment and navigation, synchronized scrolling, insert/delete/modify highlighting, and exact-byte **Jump to Source** behavior.

## Build

The project requires JDK 21 and Gradle. Build the plugin distribution with:

```shell
gradle buildPlugin
```

The resulting ZIP archive is written to `build/distributions/`.

## Compatibility

- IntelliJ IDEA 2025.1 or later (build 251+)
- JDK 21 for building from source

