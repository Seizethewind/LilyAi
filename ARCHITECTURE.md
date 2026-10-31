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

## External services

- **Router (FreeLLMAPI)** — models and web search, via the Model Gateway. See `docs/router.md`.
- **Pandastack** — sandboxed test runs, via `SandboxTool`. See `docs/pandastack.md`.
- **Termux** — external terminal, via `TerminalTool`.
- **Turso** — cloud memory over its HTTP API.
- **GitHub Actions** — builds and releases (`.github/workflows`).
