# Memory: Turso

Two separate Turso databases, both set in the app (Settings). URL like `libsql://name.turso.io` (converted to https) plus an auth token.

## Journal (`journal` table)
Raw history: requests, results, decisions. Written by Lily after every task. Failures to write never break a task.

| column | type |
|---|---|
| id | integer pk |
| ts | integer (ms) |
| project | text |
| kind | text (request, result, ...) |
| content | text |

## Proven facts (`proven_facts` table)
Only verified statements. `promote()` rejects empty evidence.

| column | type |
|---|---|
| id | integer pk |
| ts | integer (ms) |
| statement | text |
| evidence | text |
| source | text (SANDBOX_RUN, WEB_SOURCE, USER_CONFIRMED) |
| journal_id | integer, optional link to the Journal entry |

`FactVerifier.verifyAndPromote(statement, command)` runs the command in a Pandastack sandbox and promotes the fact only if the exit code is 0.

## Transport
Turso HTTP API: `POST <url>/v2/pipeline` with `Authorization: Bearer <token>`. No native libSQL dependency.
