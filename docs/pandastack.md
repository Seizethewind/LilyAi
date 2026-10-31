# Pandastack (sandboxed test runs)

Docs: https://docs.pandastack.ai/docs/

REST API used (base `https://api.pandastack.ai`, or a self-hosted URL):
- `POST /v1/sandboxes` body `{"template":"code-interpreter","ttl_seconds":600}`
- `POST /v1/sandboxes/<id>/exec` body `{"cmd":"<one command string>"}`
- `DELETE /v1/sandboxes/<id>`
- Auth: `Authorization: Bearer <PANDASTACK_API_KEY>`

`PandaStackClient.runOnce(command)` creates a sandbox, runs one command, and always deletes the sandbox.

Assumptions to verify against a real run: the create response has an `id` field, and exec returns `stdout`, `stderr` and `exit_code`.
Each command is a single string, so multi-step runs should be chained with `&&` or `bash -c`.
