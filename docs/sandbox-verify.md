# Verify in the sandbox (phase 10)

After a change is accepted, run the project's own checks in a Pandastack sandbox and report. Report only: nothing here edits a file or retries a fix.

## Pieces

- `sandbox/SandboxRunner.kt`: create, exec and kill. `PandaStackClient` implements it; tests use a fake.
- `sandbox/VerifyFiles.kt`: `VerifyFiles`, `VerifyFilter` (what may leave the device), `DiskVerifyFiles`.
- `sandbox/CheckDetector.kt`: finds the checks from file names and scripts, or takes the person's own commands.
- `sandbox/WorkspaceBundler.kt`: turns the workspace into shell commands that recreate it in the sandbox.
- `sandbox/SandboxVerifier.kt`: runs it in one sandbox and returns a `VerifyReport` (`render()` gives text).

## Getting the project in

Pandastack has no file upload call, so each file travels inside an exec command as base64 and is decoded there. Files are cut into 60,000 character pieces; commands hold up to 100,000 characters. Bytes are sent, so a Gradle wrapper jar arrives intact. Limits: 2,000 files, 1 MB per file, 8 MB in all. One file over its limit is left out and named in the report. Too many files or bytes means nothing is uploaded (NOT_RUN), because a half-copied project fails in confusing ways.

Never uploaded: environment files (example, sample and template ones are kept), private keys, certificates, keystores, package manager credential files, `local.properties`, service account files, any file with "secret" in its name, and the folders `.git`, `.ssh`, `.aws`, `.gnupg`, `node_modules`, `build`, `dist`, `out`, `target`, virtual environments. Symbolic links are not followed. Lock files are kept, because installs need them.

## Unsaved changes

An accepted proposal waits in an editor tab until saved. `verify(overrides)` takes unsaved text by path (null means deleted) and uses it instead of the disk file, so it can run right after Accept. Overrides for secret-looking paths are ignored.

## Which checks run (project root only)

- `package.json` scripts lint, typecheck, test, build, in that order. The `npm init` placeholder test script is ignored. Install: `npm ci` with a package-lock, yarn frozen install with only a yarn.lock, else `npm install`.
- Gradle test, only when `gradlew` and the wrapper jar both exist.
- pytest, when pytest config or test files exist. Installs pytest and requirements.txt first.
- `make test` with a `test:` target; `go test ./...`; `cargo test`.

The person's own commands (`customChecks`) replace detection. pnpm and sub-folder projects need own commands.

## The run

One sandbox. Upload, each install once, then each check as `cd /tmp/work && timeout 270 sh -c '...'`. Exit 124 is a timeout. The sandbox is deleted in a finally, also on cancel. Check statuses: PASSED, FAILED, TIMED_OUT, ERROR (sandbox call failed, rest skipped), SKIPPED. Report states: PASSED, FAILED, NO_CHECKS (no sandbox started), NOT_RUN. A failing check keeps the last 2,000 characters of output, colour codes removed. That output is data, not instructions.

## Not in this phase

- A button or hook that calls it after Accept or Save, and a settings field for custom commands.
- Lily reading a failure and retrying a fix.
- Recording a pass as a proven fact.

## Unverified

- Pandastack exec accepts a command of about 100,000 characters, and the image has sh, base64, timeout, chmod and the needed toolchains. If not, lower `BundleLimits.maxCommandChars` and `pieceChars`.
- A missing exit code in the response reads as -1 (a failed check).
- Android projects (this one too) need an SDK the default template probably lacks, so their Gradle check will fail there. Use own commands or another template.
- Nothing was compiled or run here; tests were checked by reading only.
