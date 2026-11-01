# Lily and the code editor (step 4)

The coding and file workers are connected to the editor. Tap **Ask Lily** (the button floating over the editor), say what to
change, and she works on the files you have open.

## What Lily sees

- Every open tab, with its **current text, including typing you have not saved**. The tab you are looking at is marked.
- The list of files in the workspace, and any file your request names (for example `util/b.kt`), even if it is not open.
- Files are shown up to about 60,000 characters in total. A file too big for what is left is named but left out.

## What Lily does

1. The planner splits your request into tasks. Coding tasks go to `CodingWorker`; creating or changing files goes to `FileWorker`.
2. The worker asks the model for **edit blocks**: a file path, the exact lines to find, and the lines to put there. This is plain
   text, so it works with whichever model the router picks.
3. Each file's blocks are applied **all or nothing** against the text you see. A block has to match exactly one place (a
   difference in trailing spaces is forgiven). If some do not fit, the model gets **one** chance to correct them.
4. What worked becomes a **proposal**. Nothing is written yet.

## What you do

- A **Review** button shows how many changes are waiting. Each file is shown as a diff (green added, red removed, long
  unchanged stretches folded). **Accept** or **Reject** each file, or **Accept all** / **Reject all**.
- Accepting puts the change into the file's tab as **one undo step**, like typing. It is not saved: press Save, or let auto save
  do it. **Undo takes it back.**
- If you change a file after Lily proposed a change to it, that proposal is dropped instead of overwriting your typing, and you
  are told to ask again.
- A second task in the same request builds on the first one's proposed text, so two changes to one file do not cancel each other.

## Safety

- Workers never write files. They only hand proposals to the editor.
- Paths must be relative to the workspace: `..`, absolute paths, drive letters and backslashes are rejected.
- A new file is created only with an empty SEARCH, and an empty SEARCH on a file that has content is refused, so a model cannot
  wipe out a file that way.

## Setup

Lily needs the router address and key from **Settings** on the main screen. She is built the first time you ask, so a missing
key shows up as a message in the reply dialog.

## Not done yet

- Running the project's checks in the sandbox after you accept a change (the `TODO` in `CodingWorker`).
- Deleting, renaming and moving files (`FileWorker` says so when asked).
- Asking Lily from the command palette (for now, use the button).

## Where the code is

- `tools/EditorBridge.kt`: what a worker can see and do in the editor.
- `lib/editor/EditBlocks.kt`: reading and applying edit blocks, the diff and the prompt.
- `workers/EditPipeline.kt`, `CodingWorker.kt`, `FileWorker.kt`: the workers.
- `editor/AgentSession.kt`, `AgentOverlay.kt`, `EditorActivity.kt`: the editor side (proposals, review, the button).
- `LilyFactory.kt`: passes the editor to the two workers.
- Tests: `EditBlocksTest`, `EditPipelineTest`.
