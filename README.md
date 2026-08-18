# Hex Support

English | [简体中文](README.zh-CN.md)

Hex Support is an IntelliJ Platform plugin for viewing, editing, searching, analyzing, and comparing binary files. Version 2.4.0 adds read-only binary structure analysis while keeping all byte changes in the Hex editor's existing Undo/Redo workflow.

## Features

### Hex editor

- Open files assigned to the **Hexadecimal files** type directly, or select Hex as an alternate editor for another file.
- Inspect bytes in a virtualized 64-bit table with a synchronized text preview, configurable bytes per row, character set, font, and color scheme.
- Edit bytes in overwrite mode, select one or more ranges, and use copy, cut, paste, insert, delete, zero-fill, or invert-selection operations.
- Save, Save As, reload, import bytes at the current position, or export selected bytes from the editor toolbar.
- Work with large files through paged storage instead of loading the complete file into a Swing table model.

### Search and batch replacement

- Press `Ctrl/Command+F` to search or `Ctrl/Command+R` to open replacement controls; use `Ctrl/Command+G` to jump to an absolute or relative offset.
- Search for hexadecimal byte sequences or enter text that is converted with the selected character encoding.
- Move between matches, replace the current match or all matches with hexadecimal bytes, and delete or zero-fill the current match or every match.
- Large searches run in the background. Batch changes are recorded as editor operations and can be undone or redone.

### History window, history files, and settings

- Every editing operation participates in IntelliJ Undo/Redo and appears in the **Hex History** tool window.
- Select a history entry to review its offset, before/after bytes, and time; move directly backward or forward to that point in the operation sequence.
- Export history manually from the editor toolbar. The sidecar is stored beside the source file as `<source-name>.hex-history.txt` and a valid matching sidecar is loaded when the source file is opened again.
- Open **Settings | Tools | Hex Support** and enable **Automatically export operation history files** to update the sidecar shortly after history changes.
- Enable **Delete operation history file when saving the hex file** if the sidecar should be removed after the edited source is saved.

### Binary Structure analysis

Open the **Binary Structure** tool window and use **Import .bt File** to select a user-supplied template. The plugin does not bundle, download, or provide template files.

- Analysis is read-only, runs in the background, and reads the current editor revision, including unsaved byte changes.
- Results are displayed as a hierarchy with **Name**, **Value**, **Offset**, **Size**, and **Type** columns. Selecting a row selects the corresponding byte range; selecting bytes reveals the deepest matching row.
- Results refresh automatically after an edit. The toolbar contains only import, clear, expand all, and collapse all actions. Clear removes the active template, results, highlights, and automatic range linkage.
- The interpreter supports common scalar integers and floats, strings, arrays, structures, unions, enumerations, type aliases, expressions, control flow, functions, attributes, endian changes, reads/seeks, and checksums. Unsupported syntax or unsafe operations are reported as diagnostics without modifying the file.
- Execution is sandboxed with limits for runtime, steps, recursion, loops, allocations, reads, and result nodes. File-system access, process execution, networking, native libraries, and external includes are unavailable.
- Supported fixed-size scalar fields may contribute background highlights. Foreground text colors and dynamic or composite field backgrounds are not rendered; row metadata and range navigation remain available.

### Hex diff

- Compare the active Hex editor with another file using block-based diff computation.
- Navigate differences with `F7` and `Shift+F7`, inspect changed ranges, and copy changes between sides.

### Localization

- The editor, dialogs, settings, and tool-window titles are available in English and Simplified Chinese.

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

The project requires JDK 21 and Gradle 9 or later. Build and test with:

```shell
gradle test buildPlugin
```

The resulting plugin ZIP is written to `build/distributions/`.

To validate against an already installed IntelliJ IDEA without downloading another IDE distribution, pass its installation directory:

```shell
gradle test buildPlugin -PlocalIdePath=/path/to/IntelliJ-IDEA
```

For the toolchain included beside this workspace on Windows:

```powershell
G:\ProjectsDemo\tools\gradle-9.6.0\bin\gradle.bat test buildPlugin `
  -PlocalIdePath="C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3"
```

Ensure that Gradle itself is launched with JDK 21. Setting only `org.gradle.java.home` controls the build JVM but does not replace an obsolete Java runtime used by the Gradle launcher.

## Make Hex the default editor for a file extension

1. Open **Settings | Editor | File Types**.
2. Select **Hexadecimal files** under **Recognized File Types**.
3. Add a filename pattern such as `*.exe` under **File Name Patterns** and apply the change.

Files matching that pattern open directly in Hex Support. Files not assigned to this file type still offer Hex as an alternate editor.

## Compatibility

- IntelliJ IDEA 2025.1 or later (build 251+)
- JDK 21 for building from source
- Gradle 9 or later
