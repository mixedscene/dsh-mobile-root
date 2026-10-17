# Implementation status

The handoff plan remains the target, not a list of completed features. This branch produces an early root-only testing APK so actual KernelSU/SELinux behavior can be checked before further integration. No real device or model credentials have been used by the developers.

| Milestone | Implemented source | Remaining / not validated |
| --- | --- | --- |
| M0 | Explicit root/SELinux/syscall capability reporting, cloud build gates | OnePlus/KernelSU profile, Android 16/17, page sizes, baseline memory/CPU/power measurements |
| M1 | Native private namespace/chroot, reduced UID/capabilities, embedded prebuilt userspace, bounded process control, path/hash/size-limited archive install | Existing runtime upgrade/rollback is rejected; device DNS/network/exec/PTY and forced-process-death cleanup need phone testing |
| M2 | Non-polling controller, explicit lifecycle, no automatic side-effect retries, partial Agent event evidence | Authoritative all-task leases and automatic idle freezing are NOT implemented; automatic freeze is disabled rather than guessing idle |
| M3 | Formal mobile profile over fixed published Harness, excluded plugin rows removed, normal relay configuration inherited, loopback-only authenticated WebView | Dedicated frontend rebuild/responsive acceptance, native-backed relay key store, real relay streaming/error/cancel tests |
| M4 / B19 | Experimental native account work is developed separately | Antigravity is NOT connected to the Harness provider in this alpha. Do not infer model generation or complete multi-account support from account storage/login code |
| M5 | Runtime file UI, bounded local Git UI, consented one-frame screenshot export, upstream in-process subagent and terminal services | Worktree-to-child automatic allocation/recovery is NOT implemented. Terminal UI and screenshot manual attachment require device tests. Directory/cross-filesystem moves are rejected |
| M6 | Persistent on-demand upstream session index, inherited plugin market/configuration, prebuilt Git/rg/bash/pnpm | Chinese substring/tokenizer behavior, plugin lifecycle-script approval/recovery, optional toolpack transactions and quotas are not accepted as complete |
| M7 | Cloud-only ARM64 userspace and Android builds, basic negative/unit/smoke checks, debug APK signature/alignment/hash evidence | Private stable release signing, performance, complete regression, full license review and real-device acceptance |

## Important distinctions

- The APK embeds the gzip runtime as `assets/runtime/runtime.payload` because Android asset merging expands and renames `.gz` inputs. CI verifies the final APK asset names, unchanged manifest, gzip header, and archive SHA-256 before upload.
- The runtime is an aggregate Linux userspace, not Android/Bionic-native Node and not a security-certified sandbox. UID 65534 is the shared nobody identity, not a guaranteed dedicated Android sandbox.
- A process-group SIGSTOP fallback is not cgroup containment. It is not selected automatically while task coverage is incomplete.
- The fixed published CLI still carries transitive code for some features that the mobile profile does not mount. This release does not claim complete dependency-byte pruning.
- A cloud Linux PTY test does not verify Android devpts, SELinux, or 16 KiB device loading. An HTTP listener does not verify a real model response.
- File edits use stat/hash checks and a cooperative lock, not an atomic compare-and-swap against non-cooperating writers. A hardlink/unlink move may leave two names if interrupted.
- Network changes may require stopping/restarting the runtime to refresh the native-provided resolver configuration.

Builds that do not pass required basic gates must not be published as successful testing APKs. Each release links its exact commit and its actual Actions evidence.
