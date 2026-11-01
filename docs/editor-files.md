# Phase 1: finishing the editor

## Replace across files

**Search all** (the button, or Commands: "Search and replace in all files") now has a **Replace with** box.

1. Search first. Each file in the results has a checkbox, all checked; untick the files you want to leave alone.
2. Press **Replace in N** and confirm.

Every match in the checked files is replaced, not only the ones listed (a file shows at most 100 matches). A file that is open in a tab
changes in the editor as one undo step and is not saved. Any other file is written to disk and **cannot be undone**, so the
confirmation says so. If a search has more than 500 matches it is cut short and replace is switched off until you narrow it.

In plain mode the replacement is literal (`$1` means a dollar sign and a one). With `.*` on, `$1` is a group. Empty matches (a pattern
like `x*` can match nothing) are left alone. Binary files and files over 1 MB are skipped.

## Files and folders (Commands)

- **New folder.**
- **Rename or move file or folder.** Pick it, then edit the path. A tab for something renamed is reopened under the new name. It refuses
  if a tab under it has unsaved typing, so nothing is lost.
- **Delete file or folder.** Pick it, then confirm. The confirmation says what goes (a folder shows its file count) and warns about unsaved
  typing. Deleting cannot be undone. Its tabs are closed.

Everything stays inside the workspace folder: `..` and absolute paths are refused. A folder cannot be moved into itself.

## Ask Lily from Commands

"Ask Lily" is in the palette and opens the same dialog as the button.

## Where the code is

- `lib/editor/ProjectReplace.kt`: the replacement logic (tested in `ProjectReplaceTest`).
- `editor/WorkspaceOps.kt`: folders, rename, delete and replace across files.
- `editor/WorkspaceDialogs.kt`: the dialogs, and `EditorUi`, which lets palette commands open them.
- `editor/NavigationOverlays.kt`: the Search all dialog with the replace part.
- `editor/EditorScreen.kt`: the palette now takes extra commands from the host.
- `editor/AgentOverlay.kt`, `EditorActivity.kt`: the Ask dialog is opened through `EditorUi`.
