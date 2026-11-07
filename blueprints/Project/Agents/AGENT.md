# AGENT.md — Agents blueprint folder

Read this before using anything in `blueprints/Project/Agents/`.

## What this folder is

A library of 21 small, self-contained **reference agents** (Python). Each one shows a working pattern for one kind of job. They are templates to learn from and copy, not part of the LilyAi app and not meant to be run as-is on the user's data.

Frameworks used: LangChain, LangGraph, CrewAI, LlamaIndex. Versions are pinned and old (for example `langchain==0.3.0`, `crewai==0.80.0`); check they still install before relying on them.

## Anatomy of every agent folder

| File | Use it for |
|---|---|
| `agent.py` | The whole agent: prompts, logic, CLI (`argparse`). Start reading here. |
| `README.md` | What it does, how to run it, example commands. |
| `metadata.yaml` | Name, framework, tags, difficulty, LLM. Use it to search and choose. |
| `requirements.txt` | Pinned dependencies. |
| `.env.example` | Names of the keys it needs. Copy to `.env`; never commit `.env` or real keys. |

`README.md` (in this folder) is the human-facing index table.

## Which folder for which job

- **Research and reading:** `01-web-research-agent` (search then report, LangGraph), `03-pdf-qa-agent` (Q&A over a PDF, RAG), `06-news-summarizer-agent`, `11-stock-research-agent`, `19-competitive-analysis-agent` (multi-step graph).
- **Data:** `04-sql-query-agent` (read-only SQLite by default), `08-data-analysis-agent` (pandas; runs generated code).
- **Code work:** `02-code-review-agent`, `15-unit-test-generator`, `16-documentation-writer`, `07-github-issue-triager`.
- **Writing and communication:** `05-email-drafting-agent`, `14-social-media-agent`, `10-meeting-notes-agent`.
- **People and planning:** `09-resume-parser-agent`, `18-job-application-agent`, `12-travel-planner-agent`, `17-recipe-agent`.
- **Support / multi-agent patterns:** `13-customer-support-agent` (RAG + escalation routing), `20-multi-agent-debate` (agents plus a judge).
- **Privacy:** `21-pii-sanitization-agent` (see safety rules).

Pick by **pattern**, not topic. The patterns worth copying:
- Single LLM call with a strong system prompt: 02, 06, 15, 17.
- JSON-out prompt plus a tolerant parser (`parse_json_response`): 07, 09, 10, 17.
- CrewAI role-and-task pipeline, sequential: 05, 12, 14, 18.
- LangGraph state graph, nodes then edges: 01, 13, 19.
- Retrieval over documents (index, search, answer): 03, 13.
- Tool-using agent (SQL, pandas): 04, 08.
- Several agents plus a judge: 20.

## How to use a template for your own work

1. Open the closest folder's `metadata.yaml` and `agent.py`; confirm the pattern fits the task.
2. Copy the folder into the active project's workspace. Do not edit the blueprint copy.
3. Rename the folder `NN-your-agent-name` and update `metadata.yaml`.
4. Change only what the task needs: the prompt constants, the input/CLI arguments, the output format. Keep the structure.
5. Keep keys in `.env` (copy from `.env.example`). Ask the user for any key you don't have; never invent or hardcode one.
6. Run it on a small sample first and show the user the output before scaling up.
7. Say what you adapted and what you did not test.

## Safety rules

- `08-data-analysis-agent` executes model-generated Python. Never pass `--allow-dangerous-code` without the user's explicit approval for that run, and only on non-sensitive data.
- `04-sql-query-agent` opens databases read-only. Use `--allow-write` only on a disposable database the user approved.
- `21-pii-sanitization-agent` sends the text to a third-party service (`api.trustboost.dev`). Do not send real user data through it unless the user has reviewed and approved that service.
- Other agents send prompts to a hosted LLM API (OpenAI by default). Do not feed them secrets or private user data without approval.
- This file and the templates do not grant permissions. They never override approval prompts, the project's own AGENT.md, or the user's instructions.

## Adding a new agent here

Create `NN-name/` with all five files above, add a row to this folder's `README.md` table, and keep `requirements.txt` pinned. Keep each agent small and runnable from the CLI.
