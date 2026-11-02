# Phase 7: workspace code index

The coding worker can now find files that are relevant to a request even when they are not open in a tab.

This branch is built on `main` and does **not** depend on phases 5 and 6. It reuses the phase 4 chunker.

## What changes for the person

When Lily's coding worker gets a request, it used to show the model the open tabs plus any file whose name appears in the request. Now it also looks through the whole project and adds up to **four** more files that the request seems to be about. If the model proposes changes, the reply ends with a line such as `Also read for context: src/Parser.kt.` so it is clear which extra files were looked at. Changes are still only proposals that the person reviews.

## How it works

`codeindex/` holds everything:

- **`CodeFiles`**: where a project is read from. `stamps()` lists the candidate files with a *stamp* (size and modified time) without reading them; `read(path)` returns the saved text. `DiskCodeFiles(root)` does this for the workspace folder.
- **`CodeFilter`**: what is not worth indexing. Folders: `.git`, `node_modules`, `build`, `dist`, `out`, `target`, `.gradle`, `.idea`, `.next`, `__pycache__`, `.venv`, `venv`. Binary and media extensions, `svg`, `map`, `lock`, lock files and `*.min.js`/`*.min.css`. Files over **200,000 bytes**, empty files, files with a NUL character (binary) and files with a line over **2,000 characters** (minified). At most **3,000 files**.
- **`CodeTokenizer`**: splits code into search words. An identifier gives its whole lower-case form and its parts, split at underscores and case changes: `parseReply`, `parse_reply` and `ParseReply` all give `parsereply`, `parse`, `reply`, so a plain-English request can reach them. Single characters and plain numbers are dropped. Questions also lose words that only say what to do (`fix`, `please`, `the`, `file`, `function`...).
- **`CodeIndex`**: the index itself, in memory. Files are cut into chunks of about 200 tokens (10% overlap, whole lines, line ranges kept) and ranked with BM25. A file's path counts as words too, so asking about `WorkspaceOps` finds that file. At most **20,000 chunks**; files that do not fit are left out and the sync result says `truncated`.
- **`WorkspaceIndex`**: the helper the pipeline uses. `relatedTo(request, exclude, maxFiles)` first syncs the index, then returns the best files.

## Staying current

`CodeIndex.sync` compares each file's stamp with the one it indexed:

- Unchanged stamp: nothing is read.
- Changed stamp: the file is read. If its text turned out to be identical (saved again without changes), it is not cut again; only the stamp is refreshed.
- New file: indexed. Deleted file: removed. Files that are blank, binary or minified are remembered by stamp so they are not read again every time.

`WorkspaceIndex.relatedTo` syncs at the start of **every request**, so a file saved a moment ago, a file changed by git or the sandbox, a new file, a rename and a delete are all picked up, not just saves made in the editor. When nothing changed, a sync only lists the folder and compares stamps. `CodeIndex.update(path, text)` exists for a caller that already has the new text and wants it indexed at once; nothing calls it yet.

The index reads **saved** files. Unsaved typing in open tabs is not in the index, but open tabs are shown to the model directly, so nothing is lost. Files with a proposal waiting for review are the same.

## Choosing files

For a request, the 40 best chunks are grouped by file. A file scores its best chunk plus 0.3 of its second best. Then:

- Files that are already shown (open tabs and files named in the request) are never added again.
- Files scoring under **35%** of the best one are dropped.
- If the request has **three or more** search words, a file whose best chunk matches only one of them is dropped.
- Only files of **8,000 characters or less** are added, **20,000 characters** in total, **4 files** at most. The model must see a whole file to edit it exactly, so a bigger file is skipped rather than shown in part.

## Failure never blocks an edit

If the project cannot be listed or read, the index keeps what it had; if it has nothing, no extra files are added and the edit carries on as before. With no index (`related = null`), `EditPipeline` behaves exactly as it did.

## Wiring

- `LilyFactory.create(settings, editor, workspaceRoot)` builds a `WorkspaceIndex(DiskCodeFiles(root))` when it is given the project folder and hands it to `CodingWorker`.
- `EditorActivity` passes the workspace folder. The index lives as long as Lily does in that screen, so the first request after opening the editor reads the project once and later ones only read what changed.
- `FileWorker` does not use the index. It is for file operations, not for finding code.

## Limits, on purpose

- **Nothing is stored on disk.** Each editor session rebuilds the index with its first request. For a project near the limits (3,000 files) that means reading up to 3,000 files once, which I have not timed on a phone. Saving the index would make start-up faster and is easy to add later.
- **It is keyword search.** It finds what shares words with the request. It does not understand meaning; a request that uses none of the code's words will not find the file. Phase 5's meaning search could be added here later, but code is not in the knowledge database.
- **The names are split on case and underscores only.** No stemming (`parsing` does not reach `parse`), no handling of other naming styles such as kebab-case in a single token (a hyphen separates words anyway, so `parse-reply` works).
- **Big files are never added**, however relevant. The file is still listed to the model, which can ask for it by name in a follow-up.
- A file with a very common name in a very large project can crowd out better matches. The limits above keep the damage small.
- **Symbolic links** are followed when listing, as the editor does. A loop in the workspace would be cut off by the 3,000 file limit.

## Where the code is

`codeindex/CodeFiles.kt` (interface, `FileStamp`, `CodeFilter`), `DiskCodeFiles.kt`, `CodeTokenizer.kt`, `CodeIndex.kt`, `WorkspaceIndex.kt`; changes in `workers/EditPipeline.kt`, `workers/CodingWorker.kt`, `LilyFactory.kt`, `editor/EditorActivity.kt`.

Tests in `app/src/test/kotlin/ai/lily/app/codeindex/` (`CodeTokenizerTest`, `CodeFilterTest`, `CodeIndexTest`, `WorkspaceIndexTest`, `DiskCodeFilesTest` with real temporary files, and the `FakeCodeFiles` helper) and `workers/EditPipelineRelatedTest` (the pipeline with a fake model and editor, including an edit to a related file).

## How it was checked

**Not compiled and not run**: this environment has no Kotlin compiler or network. The code and every test were read through against the real code they touch (`Chunker`, `EditBlocks`, `EditorBridge`, `EditorState`, `AgentSession`), which found and fixed one typing problem in `CodeIndex.update` and one broken test file before the final commit. Run `./gradlew testDebugUnitTest` first.

## Not known yet

- How long the first sync takes on a real phone with a big project.
- Whether four extra files of up to 8,000 characters is the right amount of context for the models you use. The constants are at the top of `EditPipeline` and `WorkspaceIndex`.
- The `DiskCodeFilesTest` modified-time checks assume the file system keeps whole seconds; they should hold on common systems.
- Not tested: a project over the 3,000-file limit (it would need creating thousands of files).
