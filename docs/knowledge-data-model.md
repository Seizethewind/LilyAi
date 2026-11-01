# Phase 3: knowledge data model

Still nothing new on screen. This phase adds the tables and the code that reads and writes them, so phases 4 to 6 (chunks, search, ask with citations) have something to stand on.

## What is stored

| Thing | What it is |
|-------|------------|
| **Notebook** | A named place for a line of thought. Has a description and can be archived (hidden from the default list, never lost). |
| **Source** | One piece of text in a shared **library**: either pasted text (`TEXT`) or a saved web page (`WEB`). |
| **Link** | Says that a notebook uses a source. One source can be linked to many notebooks; unlinking keeps it in the library. |
| **Note** | Text inside one notebook, written by the person (`HUMAN`), by the AI (`AI`) or brought in from elsewhere (`IMPORTED`). |
| **Citation** | Says that a note cites a library source. |

The tables arrive as two schema versions on top of version 1: **v2** adds notebooks, sources and links; **v3** adds notes and citations. `KnowledgeRepository` runs the migrator itself on first use, so there is no separate set-up step.

## Rules it keeps

- **The proven facts database is never touched.** Everything here lives in `knowledgeDb`. A note only becomes a proven fact when the person promotes it, with a citation as the evidence.
- **An AI note is never evidence on its own.** `Note.isEvidenceOnItsOwn` is false for `AI` notes and true for the other two; an AI note counts only through the sources it cites. Editing an AI note does not change its origin.
- **A web page is saved once.** Addresses are normalised first (lower-case scheme and host, no `#fragment`, no trailing slash on a bare address), and a unique index on the address means saving the same page again updates the same source: same id, same creation time, new text, hash and fetch time. Addresses that contain a username or password are refused, so credentials are never stored.
- **Pasted text is not de-duplicated.** The same text can be added twice; the content hash is stored so a later phase can notice.
- **Several writes are one unit.** Adding a note with its citations, and every delete that has to clean up other tables, runs through `SqlDatabase.transaction`: all of it or none of it.
- **Cited sources must exist.** Adding a note with an unknown notebook or source is refused before anything is written.
- **Lists stay small.** Listing sources returns `SourceInfo` (title, address, hash, length in characters, dates), not the text. `getSource` returns the text.

## What deleting does

| Delete | Also removes | Keeps |
|--------|--------------|-------|
| Notebook | Its notes, their citations, its links | The sources in the library |
| Source | Its links to every notebook, every citation of it | The notes that cited it |
| Note | Its citations | The sources |

The database is not asked to enforce foreign keys. Every request to Turso over HTTP may be a new connection, so a setting like `PRAGMA foreign_keys` could not be relied on. The repository does the clean-up itself, inside one transaction.

## Ids and time

Ids are text (UUIDs made by the app), not auto-numbered, so a note and its citations can be written in one transaction without waiting to learn the note's number. Times are milliseconds since 1970, as in the other databases.

## Where the code is

- `memory/KnowledgeSchema.kt`: migrations v2 and v3 (v1 is from phase 2).
- `memory/KnowledgeModels.kt`: `Notebook`, `Source`, `SourceInfo`, `Note`, the two enums, the evidence rule, address normalisation and the content hash.
- `memory/KnowledgeRepository.kt`: everything above, on top of `SqlDatabase`.
- Tests in `app/src/test/kotlin/ai/lily/app/memory/`: `KnowledgeRepositoryTest`, `KnowledgeUrlTest`, and the test helper `JdbcSqlDatabase`.

## How it is tested

`KnowledgeRepositoryTest` runs the real migrations and queries against an in-memory SQLite through `JdbcSqlDatabase` (a test-only dependency, `org.xerial:sqlite-jdbc`), not against a fake that ignores the SQL. The same SQL was also checked statement by statement against SQLite when this phase was written.

## Not known yet

- **Turso itself has not been asked.** The SQL uses `CHECK` constraints, a partial unique index (`WHERE url IS NOT NULL`), `ON CONFLICT ... DO UPDATE` and `INSERT OR IGNORE ... SELECT`. These are all ordinary SQLite, and Turso's HTTP API is SQLite-compatible, but the **Check knowledge database** button from phase 2 does not test them. The first time the app opens a real knowledge database, the migration will fail with Turso's own message if any of them is not supported.
- **Build and test results were not seen** when this was written; run the unit tests (`./gradlew :app:testDebugUnitTest`) before merging.
- Searching inside notes and sources is phase 4 (keyword) and phase 5 (meaning).
