# Code editor (Acode style)

Bringing Acode's editor features into LilyAi. The editing logic is ported from LilyAiCodeSpace (plain Kotlin, tested there)
and the screen is Jetpack Compose. It is a native reimplementation, not a port of Acode's WebView code.

## Step 1: editor core

- **Tabs.** Open files from the workspace, or create new ones. A dot marks unsaved typing. Closing a tab with unsaved typing
  needs a second tap.
- **Syntax colours while you type** for Kotlin, Java, JavaScript, TypeScript, Python, C, C++, C#, Go, Rust, Swift, Dart, PHP,
  Ruby, Lua, Shell, SQL, HTML (with script and style), XML, CSS, JSON, YAML and Markdown. Files over 150,000 characters open
  without colours so typing stays smooth.
- **Undo and redo** per file. A run of typing is one step; paste, replace, duplicate line and similar are their own steps.
- **Find and replace** with case and regex options. In regex mode `$1` in the replacement is a group; in plain mode it is literal.
- **Quick tools rows** above the keyboard: undo, redo, go to line, duplicate line, delete line, toggle comment, Tab, unindent
  and the symbols that are slow to reach on a phone keyboard.
- **Smart typing.** Enter keeps the indent (and opens a block after `{`, `(`, `[`, or a colon in Python and YAML), brackets and
  quotes get their partner, and typing a closer that is already there steps over it.
- **Line numbers** and a status line with language, line and column.

Files live in the app's own `workspace` folder (`filesDir/workspace`). Files over 1 MB are not opened. Windows line endings
(`\r\n`) are saved back as `\n`.

## Step 2: navigation

- **Files** (quick open): type a few letters of a file name, in order but not touching ("mnact" finds `MainActivity.kt`).
  Files that are already open are listed first, and the keyboard's Done key opens the top result.
- **Commands**: the command palette. It lists the editor actions (save, find, go to line, duplicate line, switch tab and so on)
  and narrows them as you type.
- **Search all**: searches every file in the workspace, with case and regex options. Results are grouped by file with line
  numbers and the match highlighted; tap one to open the file with the match selected. Files with unsaved typing are searched
  as they are on screen. `.git`, `node_modules`, `build`, `.gradle` and `.idea`, files over 1 MB and binary files are skipped,
  and a search stops at 500 matches.
- **Hardware keyboard:** Ctrl+P quick open, Ctrl+Shift+P commands, Ctrl+Shift+F search all, Ctrl+F find, Ctrl+S save,
  Ctrl+G go to line.

## Where the code is

- `lib/editor/SyntaxHighlighter.kt`: the lexers and `languageFor`.
- `lib/editor/EditorText.kt`: auto-indent, bracket pairing and the line commands.
- `lib/editor/TextSearch.kt`: find and replace in a file.
- `lib/editor/UndoHistory.kt`: undo and redo.
- `lib/editor/FuzzyMatch.kt`: fuzzy scoring for quick open and the palette.
- `lib/editor/ProjectSearch.kt`: search across files.
- `editor/EditorState.kt`: tabs, files, find state and editing commands.
- `editor/EditorScreen.kt`, `editor/NavigationOverlays.kt`, `editor/EditorActivity.kt`: the screens and dialogs.
- `app/src/test/kotlin/.../EditorLogicTest.kt` and `NavigationLogicTest.kt`: unit tests for the pure logic.

## Not done yet

Preview and console, themes, settings, recovery of unsaved typing after the app is killed (step 3); connecting the editor to
CodingWorker and FileWorker (step 4); replace across files, fonts, FTP/SFTP and plugins (step 5). Open tabs are not remembered
when the app restarts yet.
