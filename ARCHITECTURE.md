# LilyAI Architecture

LilyAI is an AI agent system built around a self-hosted AI router.

## Core

- **Lily** — main manager/orchestrator.
- **Planner** — breaks user requests into tasks.
- **Context** — manages current task/project context.
- **Memory** — stores useful long-term information.
- **Worker Manager** — assigns tasks to workers.
- **Tool System** — provides capabilities to workers.
- **Model Gateway** — communicates with the self-hosted router endpoint.

## Workers

- **CodingWorker** — code analysis, editing, debugging.
- **ResearchWorker** — web research and information gathering.
- **BuildWorker** — builds, tests, and analyzes errors.
- **GitWorker** — Git/GitHub operations.
- **FileWorker** — file and project operations.
- **GeneralWorker** — miscellaneous tasks.

## Architecture

User → Lily → Planner → Worker → Tools/Model Gateway → Result

Workers must NOT directly manage model APIs. They use LilyAI's Model Gateway, which connects to the self-hosted router.

The router handles model/provider selection. LilyAI handles agents, workers, tools, memory, context, and orchestration.

Workers should be modular so new workers can be added without changing the core system.

## Memory (Turso, two stores)

Configured in the app (Settings), never in Git.

- **Journal** — raw log of requests, decisions and results.
- **Proven facts** — only verified statements, each with evidence. A fact is promoted only after a check passes (sandbox run, cited web source, or user confirmation).

See `docs/memory.md`.

## Plugins

Extensions are a JSON manifest only; the app never runs plugin code. A plugin is off and has nothing allowed until the person turns it on and allows each permission, and every tool call is re-checked and shown to the person first. Plugin tools join the Tool System after the app's own tools and can never replace one. See `docs/plugins.md`.

## Workspace folder and AGENT.md

The person can choose, in Settings, a folder for their project files. The app makes `<folder>/lilyai/` with an `AGENT.md` for all projects and, per project, a `README.md` and an `AGENT.md`, and the editor opens `<folder>/lilyai`, which Termux can also see. Without a usable folder the private workspace is used. Secrets, settings, plugins and drafts always stay private.

Lily and her workers read the AGENT.md files at each call (`workspace/AgentInstructions`) and get them through the Model Gateway, after Lily's own rules: sections named for a role go only to that role, the rest to everyone, the project's file wins over the root's. They are advice only: they cannot grant permissions, switch off approvals or change which tools run. See `docs/workspace.md`.

## External services

- **Router (FreeLLMAPI)** — models and web search, via the Model Gateway. See `docs/router.md`.
- **Pandastack** — sandboxed test runs, via `SandboxTool`. See `docs/pandastack.md`.
- **Termux** — external terminal, as an optional plugin (`assets/plugins/termux.json`), not built in. See `docs/plugins.md`.
- **Turso** — cloud memory over its HTTP API.
- **GitHub Actions** — builds and releases (`.github/workflows`).
