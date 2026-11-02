# Phase 8: fonts and large files

Two things for the code editor: a choice of font, and files over 1 MB that open without being loaded into memory.

## Fonts

**Settings → Font** now offers:

- **Monospace** (the default), **Sans-serif** and **Serif**: the phone's own fonts.
- **Fonts you add.** *Add a font file…* opens the phone's file picker. The file is checked, copied into the app's own `fonts` folder (`filesDir/fonts`) and selected straight away. A ✕ next to a font removes it; if it was in use the editor goes back to Monospace.
- A sample line in the chosen font, so the choice can be judged before closing the dialog.

**No fonts are shipped with the app.** The tooling used to build this phase can only write text files, so it could not add a font binary. Add your own `.ttf` or `.otf` (JetBrains Mono, Fira Code, Cascadia and so on; check each font's licence).

### What a font file must be

`FontStore` accepts a file only if all of these hold:

- It is named `*.ttf` or `*.otf`.
- It starts with the header of a TrueType font (`00 01 00 00` or `true`) or an OpenType font (`OTTO`), and is at least a header long. The name alone is not trusted.
- It is at most **10 MB**, and fewer than **20** fonts are already added.

The name it is saved under is cleaned: folders in the name are dropped (`../../evil.ttf` becomes `evil.ttf`), anything other than letters, digits, `.`, `_`, space, `(`, `)` and `-` becomes `_`, and a name already taken gets `-2`, `-3`. The file is checked in memory before anything is written, so a rejected file leaves nothing behind. `.ttc` collections are not accepted.

### How a choice is saved and used

`EditorSettings.font` holds an id: `builtin:monospace`, `builtin:sans-serif`, `builtin:serif` or `file:<name>`. A missing, unknown or unsafe id means the default (Monospace). When the chosen font file is later missing or no longer looks like a font, the editor draws in Monospace and the settings dialog says why; it never crashes on a bad font. Code, line numbers and the large-file viewer all use the same family.

Proportional fonts make code columns uneven, so the dialog says that code lines up best in a monospace font.

## Large files

A file over **1,000,000 bytes** used to be refused. It now opens in a **read-only viewer**, up to **512 MB**:

- The file is read **once, in 64 KB pieces**, to count lines and remember where every 1,000th line starts. That is a few kilobytes of numbers even for millions of lines. The whole file is never held in memory. Progress shows as a message; leaving the screen stops it.
- The viewer shows **200 lines at a time**. Controls: **Find** with previous and next, **Go to line**, **Top**, **Page up**, **Page down**, **End**, and a **slider** that jumps anywhere. The line you land on is marked. **Back** or **Close** returns to the editor.
- **Find** searches the whole file line by line, in plain text and ignoring case, going on from the start (or the end) when it reaches the end. It can be cancelled by leaving the screen.
- Search all files that finds a hit in a large file opens the viewer at that line.
- Files over 512 MB are refused with a message that says how big they are.

### Rules the reader follows

- Lines end at a newline byte; a `\r` before it is dropped. UTF-8 text of any alphabet is shown correctly. Bytes that are not valid UTF-8 show as replacement characters.
- A line longer than **20,000 bytes** is cut there and ends with ` … (+N bytes)`. Find only sees the part that is shown. (Minified code is the usual cause.)
- A file that ends with a newline has no extra empty last line; an empty file has no lines.
- The file is not locked. If something changes it after it was indexed, lines may be off until it is opened again.

### Why it is read-only

Editing needs the whole text in a text field, which is what stops a file this size working. Making large files editable would need a different editing component and an undo history that stores changes instead of copies. That is a separate piece of work, not done here. Files between 150,000 characters and 1 MB still open in the normal editor without syntax colours, as before.

## What changed in the code

- `lib/editor/FontStore.kt`: `BuiltinFont`, `FontChoice`, `FontImport`, `FontStore`.
- `lib/editor/LargeFile.kt`: `LargeFileReader`, `LargeFileWindow`, `formatBytes`.
- `lib/editor/EditorSettings.kt`: new `font` setting. `editor/EditorPrefs.kt` saves it.
- `editor/EditorFonts.kt` (choice → Compose font family), `editor/LargeFileSession.kt`, `editor/LargeFileViewer.kt`.
- `editor/EditorState.kt`: `fontFamily`, `fontProblem`, `installFont`, `removeFont`, `largeFile`; `open` sends big files to the viewer and `openAt` jumps inside it.
- `editor/SettingsDialog.kt`: the Font section. `editor/EditorScreen.kt`: the code area uses the chosen family. `editor/EditorActivity.kt`: the file picker for fonts and the viewer on top of the editor.

Tests: `FontChoiceTest`, `FontStoreTest`, `LargeFileReaderTest` (with `LargeFileWindowTest` and `FormatBytesTest` in the same file).

## How it was checked

**Not compiled and not run**: there is no Kotlin compiler or network in the environment this was written in. The logic and tests were read through against the code and against the real `EditorState` (the rewritten file was compared with the original on `main`; only the intended parts differ). One test that could not have failed (cancelling a search) was rewritten so it can. The screens (`LargeFileViewer`, the settings Font section, the font picker) have **no tests at all** and are the most likely place for compile or layout mistakes. Run `./gradlew testDebugUnitTest`, then try it on a phone.

## Not known yet

- How long indexing takes on a phone for a 100 MB or 500 MB file (it is one sequential read, so roughly the time to read the file).
- Whether `FontFamily(Typeface)` and `Typeface.createFromFile` behave the same on every Android version for odd fonts (variable fonts, fonts with unusual tables). An unreadable font falls back to Monospace, but a font that loads and draws badly would not be caught.
- The picker offers every file type (fonts have no single MIME type). Picking something that is not a font gives the message "That does not look like a TrueType or OpenType font."
- The slider and page buttons have not been tried on a very small screen.
