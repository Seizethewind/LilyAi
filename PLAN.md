# LilyAI Build Plan

Router: self-hosted FreeLLMAPI (OpenAI-compatible). Memory: Turso (Journal + Proven facts). Sandbox: Pandastack. Terminal: external Termux. Builds/releases: GitHub Actions.

## Phase 1: Foundation
- [x] Android/Kotlin layout mirroring Acode
- [x] Core, workers, tools, gateway skeleton
- [x] Settings screen (router, Turso x2, Pandastack) in encrypted storage
- [x] RouterModelGateway (chat completions)
- [ ] Streaming responses

## Phase 2: Core loop
- [x] LlmPlanner (JSON task list, falls back to one GeneralWorker task)
- [x] Journal logging in Lily
- [ ] Context persistence per project
- [ ] Recall: feed recent Journal + relevant Proven facts into prompts

## Phase 3: Tools
- [x] WebSearchTool (via router)
- [x] SandboxTool (Pandastack)
- [x] FactVerifier: sandbox run -> Proven fact
- [ ] TerminalTool via Termux RUN_COMMAND (allowlist + confirm-before-run)
- [ ] FileTool, GitTool

## Phase 4: Workers
- [x] All six workers wired to gateway/tools (ResearchWorker uses web search)
- [ ] CodingWorker: edit files, verify in sandbox
- [ ] BuildWorker: read GitHub Actions runs, summarize errors

## Phase 5: UI
- [ ] Chat screen with streaming
- [ ] Task/plan view
- [ ] Approval prompts for risky tool calls

## Phase 6: Release
- [x] build.yml (debug build + unit tests)
- [x] release.yml (tag v* -> signed APK on GitHub Releases)
- [ ] Add signing secrets, fastlane screenshots

## Rules
- Workers never call model APIs directly; only via ModelGateway.
- The router picks provider/model; LilyAI sends selectors (auto, auto:fast, auto:smart, auto:<profile>).
- Only verified statements enter Proven facts.
- New workers register with WorkerManager; core stays unchanged.
