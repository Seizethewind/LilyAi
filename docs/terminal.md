# Built-in terminal

Goal: a real Linux shell inside the app (bash or sh, git, python, node, a package manager). You can type in it, and later Lily's workers can run commands in it, with your approval each time.

**Status: Phases 0, 1 and 2 are written: the environment manager, proot (built by CI), and a Terminal section in the app (tabs, extra keys, setup pane). All of it compiles in CI and the unit tests pass; none of it has been run on a phone yet.** Everything below says which parts are proven and which are not.

## Using it (Phase 2)

Open the **Terminal** tab. The first time it shows a setup pane: **Download Linux files** fetches Alpine Linux (a few MB), checks it and unpacks it. Then a shell opens on its own. Tabs along the top (`+` for a new one, `⋮` for paste, close, safe mode and removing the Linux files); an extra-keys row along the bottom (Esc, Tab, sticky Ctrl and Alt, arrows, common symbols, paste). Inside, `apk add git python3 nodejs` installs tools.

**Safe mode** (`⋮` menu) turns off proot's seccomp speed-up. Use it if the shell hangs or prints errors at start; it is slower. Turning it on closes the open tabs.

Not done yet: sessions end when the app process is stopped (Phase 3 keeps them alive in a foreground service); Lily's workers cannot run commands in it (Phase 4); the project folder is not mounted into the shell (Phase 4); there is no terminal colour or font setting.

## How it works

Since Android 10 an app may not run programs it unpacked into its own storage. It may run programs shipped as **native libraries**. So:

1. **proot** ships in the APK as `libproot.so` (plus its loader as `libproot-loader.so`) in `app/src/main/jniLibs/<abi>/`. `useLegacyPackaging = true` in `app/build.gradle.kts` makes Android leave them as real files on disk, which is the only way to run them.
2. A **Linux root file system** (Alpine's mini root file system) is downloaded on first run into `files/terminal/rootfs`. It is not in the APK, so the APK stays small and the environment can be updated without an app release.
3. proot starts a program inside that root file system by translating system calls, so the guest's `/bin/sh`, `apk`, `git` and so on run without needing permission to execute themselves.

The cost: proot is slower than native code. Heavy work (big builds) can feel it. Option B in the plan, a Termux-style custom prefix at targetSdk 28, is faster but needs a package repository of your own; it is not done here.

## Code

| Where | What | Tested |
|---|---|---|
| `terminal/env/EnvModel.kt` | Release manifest parser (https only, full SHA-256, size limits), CPU mapping, state types | yes |
| `terminal/env/EnvDownloader.kt` | `EnvDownloader` interface; `HttpEnvDownloader` with resume (`Range`) | the interface is faked in tests; the real HTTP class has **not** been run |
| `terminal/env/TarGzExtractor.kt` | Unpacks `.tar.gz` without a library. Refuses `..`, refuses writing through links, skips device files, size and entry limits | yes |
| `terminal/env/EnvManager.kt` | Download, check, unpack into a staging folder, swap into place, write `env.json` last. Repair, reset, disk use | yes |
| `terminal/proot/ProotLauncher.kt` | Builds the proot command line and a clean environment; `GuestSetup` writes `resolv.conf` and `hosts` | yes |
| `src/debug/.../ProotSpikeActivity.kt` | Phase 0 test screen (debug builds only) | **no** |
| `terminal/ui/TerminalHost.kt` | Environment state, open tabs, sticky Ctrl/Alt, the clients Termux's view and session need | compile-checked only |
| `terminal/ui/TerminalScreen.kt` | The Terminal section: setup pane, tabs, terminal view, extra keys | compile-checked only |
| `utils/stage-proot.sh` | Copies a proot build from elsewhere into `jniLibs` under the right names (optional now) | syntax only |
| `.github/workflows/build-proot.yml` | Builds proot and commits it to `jniLibs` | ran once, succeeded |

## Phase 0: run the spike on a phone

Everything can be done from the phone alone; no PC or `adb` is needed.

**1. proot binaries: already in the repository.** `.github/workflows/build-proot.yml` builds proot 0.15 (with talloc linked in) for `arm64-v8a`, `armeabi-v7a` and `x86_64`, and commits the result to `app/src/main/jniLibs/` together with `PROOT_SOURCE.md` (sources, versions and checksums of every file). It runs by hand (Actions, Build proot, Run workflow) or when that workflow file changes. It builds the scripts from [green-green-avk/build-proot-android](https://github.com/green-green-avk/build-proot-android), pinned to one commit, and the two source tarballs are pinned by SHA-256 (first recorded from the upstream download, so trusted on first use).

What is checked: the files exist, are Android executables for the right CPU, and `libproot.so` depends only on `libc` and `libdl` (talloc is inside it). What is **not** checked: that proot actually runs on a device, which is what this spike is for. `utils/stage-proot.sh` is only needed if you want to use a proot build from somewhere else.

**Licence:** proot is GPL-2.0-or-later and talloc is LGPL-3.0-or-later; the repository itself is MIT. Shipping those binaries in the APK carries obligations (at least offering the corresponding source; `PROOT_SOURCE.md` names it). Read them before you publish a release.

**2. The Linux root file system** is found by the app itself: it reads Alpine's `latest-releases.yaml` for your CPU, takes the "mini root file system" entry (file name, size, SHA-256) and downloads that. (`AlpineReleases.kt`. That parser was written without seeing the real file; if it cannot find the entry, the log shows the start of the file so it can be fixed.) The checksum comes from the same server as the file, so it catches a damaged download but not a hacked server; a manifest on your own GitHub releases would be stronger.

**3. A debug build installed.** Download the `lilyai-debug-apk` artifact from the pull request's latest "Build" run (Actions tab, signed in to GitHub, works in a phone browser), unzip it and install the `.apk`. Android will ask you to allow installing from your browser or file manager.

Then open the **Terminal spike** icon (a second icon, debug builds only) and press the buttons in order:

| Button | Passes when |
|---|---|
| 1. Check files | `libproot.so` exists and is executable, the loader exists |
| 2. Install Linux environment | Progress runs, ends with `State: Ready(...)` |
| 3. Shell test | `PASS`, output shows `uname`, `id` as root and `SPIKE_OK` |
| 3b. Shell test, seccomp off | Same, if 3 failed or hung (a few devices handle proot's speed-up badly) |
| 4. Install git, python, node | `PASS`; needs network inside the guest |

If you do have a PC, `adb shell am start -n ai.lily.app/.terminal.spike.ProotSpikeActivity --es url <https address> --es sha256 <64 hex> --el size <bytes>` overrides the automatic lookup.

**Pass for Phase 0** is buttons 1, 2, 3 and 4 all passing on a real phone. If 3 fails, copy the whole log: the failure tells which of the three parts (the proot binary, its loader, the root file system) is wrong. Do not start Phase 2 before this passes.

## Phase 1: the environment manager

### Release manifest

The app reads a small JSON file that says what to download:

```json
{
  "schema": 1,
  "version": "2026.11.1",
  "assets": {
    "aarch64": { "url": "https://.../rootfs-aarch64.tar.gz", "sha256": "<64 lowercase hex>", "size": 3500000 }
  }
}
```

Keys are `aarch64`, `armv7` and `x86_64`. The parser refuses anything else: non-https addresses, a short or upper-case hash, a size of 0 or over 1 GiB, a version with odd characters.

### Install steps and what keeps it safe

1. Check free space (about four times the archive).
2. Download to `downloads/<version>-<cpu>.tar.gz.part`, resuming if a partial file exists.
3. Check size and SHA-256. A wrong file is **deleted**, not resumed.
4. Unpack into `rootfs.new`. Entries that climb out of the folder, or that would be written through a link, abort the whole install.
5. Require `bin/sh` and `etc` to exist in the result.
6. Delete the old `rootfs`, rename `rootfs.new` into place, then write `env.json`.

`env.json` is written last, so a crash leaves either the old environment or none, never a half-unpacked one that looks installed. A failed install keeps the old environment and keeps a partial download for resume.

Deleting uses a walker that never follows links, because an unpacked root file system is full of absolute links and a normal recursive delete could empty a real folder outside it. A test covers this.

### Known limits

- Hard links in an archive are copied, not linked. Not checked: whether Alpine's mini root file system contains any.
- Device files and pipes in an archive are skipped; proot supplies `/dev`.
- Permission bits other than the owner's read, write and execute are not kept.
- The download follows redirects; a redirect to plain http is refused by the platform.
- Nothing is signed beyond the SHA-256 in the manifest, so the manifest itself must come from a place you trust.

## Next phases

2. Terminal screen (terminal view libraries, PTY sessions, extra-keys row).
3. Keep sessions alive in the foreground service.
4. Agent bridge: an in-app shell tool replacing the external Termux plugin's service call. The approval dialog stays.
5. Run project checks and git locally.
6. Remove the external Termux plugin's manifest entries and update the other docs.

## Termux's terminal libraries

The screen uses `terminal-view` and `terminal-emulator` (version `v0.118.0`) from [termux/termux-app](https://github.com/termux/termux-app), fetched from JitPack. `settings.gradle.kts` allows JitPack for that one group only. They draw the terminal and open the pseudo-terminal that proot runs in (`libtermux.so`, in the library). Release builds keep their classes (`app/proguard-rules.pro`), because the native code finds them by name; **that rule is untested, since CI only builds the debug APK.**

**Licence: not verified.** The Termux app is GPLv3, and I could not read the licence file for these two libraries from here. I believe they are Apache-2.0, but check `LICENSE.md` in the Termux repository before you publish a release.
