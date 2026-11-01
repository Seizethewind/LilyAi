# Phase 2: knowledge groundwork

Nothing here is visible to the person yet except a new settings section. It is the base that phases 3 to 6 stand on.

## A third database

Settings has a new section, **Turso: Knowledge**, with its own URL and token (stored encrypted, like the others). It must be a different
database from **Proven facts** and from the **Journal**. Saving is refused if the address matches either one (trailing slash, upper case
and `libsql://` versus `https://` are ignored when comparing). The knowledge layer never reads or writes the proven facts database. A note
only becomes a proven fact when the person promotes it, with a citation as evidence.

## Several statements as one unit

`SqlDatabase.transaction(statements)` runs a list of statements so that either all are applied or none are. Over Turso's HTTP API this is
one `batch` request: `BEGIN`, each statement (each only if the previous one worked), `COMMIT`, and a `ROLLBACK` that only runs if the
commit did not succeed. If anything fails the call throws with the database's own message.

`TursoClient` implements `SqlDatabase`. The JSON it sends and reads is in `TursoWire`, which is tested without a network.

## Schema versions

`SchemaMigrator` keeps a database's tables at the version the app expects. The version of each part is stored in a `schema_version` table.

- Each migration runs in one transaction together with the version bump, so a failure leaves the database at the old version.
- Running it again does nothing.
- A database that is **newer** than the app is refused with "Update the app", never guessed at.
- Migration numbers must count up from 1 with no gaps. A migration that has shipped is never edited; add a new one.

`KnowledgeSchema` holds the knowledge database's migrations. Version 1 is only a small `knowledge_meta` table; phase 3 adds notebooks,
sources and notes as versions 2 and up.

## Does Turso do vector and full-text search?

**Check knowledge database** in Settings asks the database and shows the answer:

| Check | How it is tested | If it fails |
|-------|------------------|-------------|
| Transactions | two `SELECT`s in one transaction | phase 3 cannot rely on atomic writes; stop and rethink |
| Vector search | `vector_distance_cos(vector32(...), vector32(...))` | meaning search is computed in the app (phase 5) |
| Full-text search | a temporary FTS5 table, one row, a `MATCH`, then dropped, all in one transaction | keyword search is scored in the app (phase 4) |

It leaves nothing behind in the database. `CapabilityReport.vectorSearch` and `keywordSearch` say `IN_DATABASE` or `IN_APP`, which
phases 4 and 5 will read.

**The answer for the person's real Turso database is not known until they press the button.** Please record it in the roadmap's open
questions.

## Where the code is

- `memory/SqlDatabase.kt`, `TursoClient.kt`, `TursoWire.kt`: the database interface, the real client and the JSON.
- `memory/SchemaMigrator.kt`, `KnowledgeSchema.kt`: versions and the knowledge database's schema, plus the same-database check.
- `memory/CapabilityProbe.kt`: the vector, full-text and transaction checks.
- `settings/AppSettings.kt`, `SettingsStore.kt`, `SettingsActivity.kt`, `res/layout/activity_settings.xml`: the new setting.
- Tests in `app/src/test/kotlin/ai/lily/app/memory/`: `SchemaMigratorTest` (with the config check), `TursoWireTest`, `CapabilityProbeTest`.
  Unit tests now use the real `org.json` library, because Android's version is only a stub on a plain JVM.
