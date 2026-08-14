# Hex Support

Hex Support is an IntelliJ IDEA plugin for viewing, editing, and comparing files as hexadecimal data.

## Highlights

### Hex editor

- Registers **Hexadecimal files** in **Settings | Editor | File Types**. Associate a pattern such as `*.exe` with it to make Hex Support the exclusive default editor for matching files.
- Displays offsets, editable hexadecimal byte cells, and a raw ASCII preview in a native IntelliJ editor-style table.
- Supports direct byte overwrite, multi-selection, copy, cut, paste, zero-fill, insertion, deletion, and selection inversion.
- Provides Save, Save As, Reload, fragment import, and selected-fragment export from the editor toolbar.
- Uses a large-file editing model with 64-bit offsets, paged reads, and streaming save/import/export operations.
- Allows the number of bytes per row to be configured. For very large files, the minimum is adjusted automatically so the table can reach the actual end of the file.
- Follows the active IDE color scheme, editor font, font size, line spacing, selection colors, search colors, and line-number gutter style.
- Synchronizes selections in both directions with every text editor available for the same file, including Text Editor and the source pane of Markdown Split Editor. Multiple selections/carets created with Alt+Shift are preserved, and offsets account for the file charset, BOM, and mixed CRLF/LF/CR line separators.

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
- Shares multi-range selections bidirectionally across all four Diff viewers: Side-by-side, Unified Viewer, Hex-by-Hex, and Unified Hex. Switching from any viewer to any of the other three preserves every selection/caret together with the correct old/new content side and byte offsets.
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

## Build

The project requires JDK 21 and Gradle. Build the plugin distribution with:

```shell
gradle buildPlugin
```

The resulting ZIP archive is written to `build/distributions/`.

## Make Hex the default editor for a file extension

1. Open **Settings | Editor | File Types**.
2. Select **Hexadecimal files** under **Recognized File Types**.
3. Add a filename pattern such as `*.exe` under **File Name Patterns** and apply the change.

Files matching that pattern will open directly in Hex Support. Files not assigned to this file type still offer Hex as an alternate editor.

## Compatibility

- IntelliJ IDEA 2025.1 or later (build 251+)
- JDK 21 for building from source
