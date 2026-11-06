# Workspace folder

The editor's files normally live in the app's private `workspace` folder, which no other app can see. In Settings, under
**Workspace folder**, the person can choose a shared folder instead, so Termux and a file manager see the same files.

## Setup

1. Type the folder (the suggestion is `Documents`) and a project name.
2. Allow file access if Android asks. On Android 11 and later this is All files access.
3. Tap **Create lilyai folder**. The app writes, without overwriting anything that exists:

```
<folder>/lilyai/
  AGENT.md              rules for every project
  <project>/
    README.md           what the project is
    AGENT.md            rules for this project
```

The editor then opens `<folder>/lilyai`. If the folder cannot be written to, the private workspace is used and Settings says so.
Settings, keys, plugins, drafts and the remote-workspace cache always stay in private storage.

## AGENT.md

Lily and her workers read `AGENT.md` (or `AGENTS.md`) from two places, each time they are asked, so an edit shows in the next answer:

- `lilyai/AGENT.md`: rules for all projects.
- `lilyai/<project>/AGENT.md`: rules for the active project. It wins where it differs. The active project is the first folder of
  the open file; with no file open and exactly one project folder, that one.

Sections that name a role go only to that role: `## Lily (manager)` to Lily's planner, and `## CodingWorker`, `## ResearchWorker`,
`## BuildWorker`, `## GitWorker`, `## FileWorker`, `## GeneralWorker` to the matching worker. Every other section, and any text before the
first heading, goes to everyone. Each file is read up to 4096 characters.

The text is added to the worker's call after Lily's own rules (Settings of the AI section). It is advice about layout, style and
workflow. It cannot grant permissions, turn off approvals, enable plugins or change which tools may run: those checks are in the
app's code and never read from a file. This matters because other apps, Termux included, can write to a shared folder.
