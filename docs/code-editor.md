# Code editor (Acode style)

Step 1 of bringing Acode's editor features into LilyAi. The editing logic is ported from LilyAiCodeSpace
(plain Kotlin, tested there) and the screen is Jetpack Compose. It is a native reimplementation, not a port of Acode's WebView code.

## What step 1 does

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

## Where the code is

- `lib/editor/SyntaxHighlighter.kt`: the lexers and `languageFor`.
- `lib/editor/EditorText.kt`: auto-indent, bracket pairing and the line commands.
- `lib/editor/TextSearch.kt`: find and replace in a file.
- `lib/editor/UndoHistory.kt`: undo and redo.
- `editor/EditorState.kt`: tabs, files, find state and editing commands.
- `editor/EditorScreen.kt`, `editor/EditorActivity.kt`: the screen.
- `app/src/test/kotlin/.../EditorLogicTest.kt`: unit tests for the pure logic.

## Not in step 1

Command palette, quick open and search across files (step 2); preview and console, themes, settings, recovery of unsaved
typing after the app is killed (step 3); connecting the editor to CodingWorker and FileWorker (step 4); replace across files,
fonts, FTP/SFTP and plugins (step 5). Open tabs are not remembered when the app restarts yet.
