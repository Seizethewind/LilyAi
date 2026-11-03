# Remote workspaces (phase 12)

A workspace can live on a server. The app keeps a local copy of the remote folder; the editor, search and the code index work on that copy exactly as on any other workspace, and saved files are sent back to the server.

This phase is the logic and its tests. It has no screen and no real network client yet (see "Not done").

## Protocol order

1. **SFTP** (over SSH) is always tried first.
2. **FTPS** (FTP over TLS) is tried next, unless the profile turns it off.
3. **Plain FTP** is the last resort and is used only when all of these hold:
   - the profile has `allowFtpFallback` on (it is off by default),
   - SFTP and FTPS both said the server does not offer them,
   - the person is shown the warning below and says yes. This is asked every time; there is no "remember my answer".

The warning (`FtpFallbackPrompt.message()`) names the server, says why SFTP and FTPS failed, says plain FTP is not encrypted and that the password, files and every saved change can be read on the network, and says the password will not be saved. While an FTP connection is open, `RemoteSession.banner` is not null and the screen must keep it visible.

**Only "the server does not offer this protocol" moves on to the next protocol.** A refused password, a changed host key, a rejected host key, or a timeout stops everything. Falling back after those would send the password somewhere less safe or would hide an attack.

`ConnectionPlanner` also checks that the connection it gets back really is the protocol it asked for, so the banner cannot lie.

## Passwords

- A profile (`RemoteProfile`) holds no password.
- `CredentialPolicy.mayStorePassword` is true for SFTP and FTPS and false for FTP. A plain FTP password is typed each time and never stored.
- Stored passwords go in the existing encrypted store when the screens are added.

## Host keys

`HostKeyPolicy` is trust on first use with the person deciding:

- A new key is shown (host, port, key type, fingerprint) and must be accepted. It is remembered per host, port and key type.
- A key that differs from the one accepted before always fails with `HOST_KEY_CHANGED`. It is not offered for acceptance, because it may be someone in the middle. The person can forget the host explicitly (`HostKeyStore.forget`) after being told why.
- Checking is never switched off. Connectors must call `HostKeyPolicy.verify` before any password is sent. For FTPS the same applies to the certificate: no "trust all" mode.

## Local copy (`RemoteMirror`)

`pull()` walks the server and brings changes down:

- Skips dependency and build folders (the same list the code index uses), links (never followed), names a server should not use (`..`, slashes, control characters), files over 1 MB, and anything past 3,000 files, 24 levels or 100 MB. Every skip is reported with its reason.
- Never overwrites a file the person changed on the phone. Such files are reported in `localKept`.
- Removes a local file only when the server deleted it, the listing was complete, and the local copy is untouched. An incomplete listing deletes nothing.
- A file deleted on the phone is fetched again on the next pull (nothing is lost; deleting on the server from the phone is not part of this phase).

`push(path)` sends one saved file:

- It first looks at the server's copy. If that is not what the phone last saw, it returns a `Conflict` (`REMOTE_CHANGED`, `REMOTE_DELETED` or `REMOTE_APPEARED`) and sends nothing.
- `push(path, force = true)` overwrites after the person chose "keep mine". `takeRemote(path)` replaces the local copy after the person chose "take theirs".
- Missing server folders are created. After the upload the size on the server is checked.

The record of what the phone last saw (size, server time, SHA-256) is kept in a manifest file next to the cache folder. Times always come from the server, never the phone's clock. A damaged manifest is read as empty, which makes the next pull more careful, not less.

Server-supplied paths pass `RemotePaths` before they touch the disk, and local paths are resolved to their canonical form and must stay inside the cache folder.

## Not done

- **No real SFTP, FTPS or FTP client.** `RemoteConnector` is the seam. Adding one needs a new Gradle dependency (an SSH library for SFTP, an FTP library with TLS for FTPS and FTP). I have not picked one because versions cannot be checked from here: please choose or approve the libraries, then the adapter is one small class per protocol. Each adapter must map a refused connection or missing SSH greeting to `UNSUPPORTED`, a timeout or name failure to `UNREACHABLE`, and must never follow links.
- No screens: profile form, password prompt, host key dialog, FTP warning dialog, banner, conflict dialog, pull and push buttons.
- No saved host keys on disk yet (`HostKeyStore` has only the in-memory version) and no saved profiles.
- Save in the editor does not push yet; the screens phase wires "push on save".
- Rename, new folder and delete on the phone are not sent to the server.
- The Turso, code index and research code are not touched.

## Tests

`app/src/test/kotlin/ai/lily/app/remote/`: path safety and hashing, host key policy, protocol order and the FTP warning rules, profile checks, and the mirror (pull, conflicts, push, hostile names, limits, damaged manifest) against an in-memory fake server and real temporary folders. They have not been run by me (no build in the sandbox): run `./gradlew testDebugUnitTest` on the branch.
