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
- **Commands**: the command palette. It lists the editor actions and narrows them as you type.
- **Search all**: searches every file in the workspace, with case and regex options. Results are grouped by file with line
  numbers and the match highlighted; tap one to open the file with the match selected. Files with unsaved typing are searched
  as they are on screen. `.git`, `node_modules`, `build`, `.gradle` and `.idea`, files over 1 MB and binary files are skipped,
  and a search stops at 500 matches.
- **Hardware keyboard:** Ctrl+P quick open, Ctrl+Shift+P commands, Ctrl+Shift+F search all, Ctrl+F find, Ctrl+S save,
  Ctrl+G go to line.

## Step 3: preview, settings and recovery

- **Preview.** The Preview button shows HTML, SVG, Markdown and JavaScript files: first in the lower half of the screen, then
  full screen, then off. The page can load the other files of the workspace (stylesheets, scripts, images) by their normal
  relative addresses, and files open in a tab are served with their unsaved text. The page reloads shortly after you type in
  any open tab. It has no access to the app's data and only follows links to your own files.
- **Console** under the preview: `console.log` output (objects and arrays are printed as text), errors, missing files and
  blocked links. It opens by itself on the first error. Tap an error to jump to its line in the file, or type JavaScript in the
  box to run it inside the page.
- **Settings** (the Settings button, or Commands): six colour themes, text size, line spacing, indent (match the file, 2, 4 or
  8 spaces, or a tab), word wrap, line numbers, closing brackets and quotes, and auto save two seconds after typing stops.
  Changes apply at once and are remembered. With word wrap on, line numbers are hidden.
- **Recovery.** A copy of unsaved typing is written to the app's private storage every second and a half and whenever the editor
  leaves the screen. If the app is closed or killed, the typing comes back when the file is opened again, as long as the file on
  disk has not changed since. Open tabs, and which one was active, are remembered too.

## Phase 1: editor finish

Replace across files, new folder, rename and delete, and "Ask Lily" in Commands. See `docs/editor-files.md`.

## Where the code is

- `lib/editor/` (plain Kotlin, unit tested): `SyntaxHighlighter`, `EditorText`, `TextSearch`, `UndoHistory`, `FuzzyMatch`,
  `ProjectSearch`, `ProjectReplace`, `Markdown`, `PreviewPage`, `DraftStore`, `EditorSettings`.
- `editor/EditorState.kt`: tabs, files, settings, drafts, find state and editing commands.
- `editor/EditorScreen.kt`, `NavigationOverlays.kt`, `SettingsDialog.kt`, `PreviewPane.kt`, `PreviewServer.kt`, `EditorTheme.kt`,
  `EditorPrefs.kt`, `EditorActivity.kt`: the screens, dialogs and storage.
- `editor/WorkspaceOps.kt`, `WorkspaceDialogs.kt`: folders, rename, delete and replace across files.
- `app/src/test/kotlin/.../`: `EditorLogicTest`, `NavigationLogicTest`, `PreviewAndDraftsTest` and `ProjectReplaceTest`.

## Not done yet

Fonts and large files (phase 8), FTP/SFTP (phase 12) and plugins (phase 13). See `docs/ROADMAP.md`.
