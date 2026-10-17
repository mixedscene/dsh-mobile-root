# DSH Root (experimental Android fork)

DSH Root is a root-only Android shell for the Harness Web interface. It has a separate application ID (`io.github.mixedscene.dshroot`, with `.debug` appended for the testing APK), target SDK 36, and no compatibility with the old proot installer or its private data. Install it alongside the original application and export ordinary sessions/files from the old application yourself. Do not copy credential databases or old signing keys.

## Current behavior

- Setup requires a user action before `su` probing and installation of the bundled verified runtime. Ordinary launches do not install packages, run npm, or compile native modules on the phone.
- The native supervisor launches a chroot runtime. SELinux stays Enforcing; root capability, chroot, network access, KernelSU policy and page-size behavior require device verification. The runtime is not a security sandbox for untrusted plugins.
- Tap **Start** to launch one foreground-service-owned runtime. Exit/failure requires a manual restart. The notification and Settings provide an explicit Stop action. No periodic health check or hidden repair installs run.
- The WebView accepts exactly `http://127.0.0.1:3080`. It consumes the supported `dsh web` token URL printed by the server, without reading signing secrets or minting cookies. The URL/token are memory-only and excluded from diagnostics. External navigation and browser permission requests are blocked.
- Settings exports at most 120 lifecycle messages, without runtime output, account tokens, provider keys or user conversation content. Native/Node process output is consumed rather than retained.
- Runtime workspace files are managed through an unprivileged bundled Node helper, not direct Java reads of root-owned data. Select an existing workspace under `/home/dsh`, browse sorted pages of 100 entries, view/edit UTF-8 text up to 1 MiB, create files/folders, rename/move files or links without overwrite, and confirm deletion of entries themselves. Directory deletion requires an empty directory. SAF import/export copies actual content through bounded private staging files and runtime pipes (32 MiB limit), never by guessing URI paths. Imports are not automatically attached to conversations.
- Screenshot captures one user-consented MediaProjection frame, displays a preview, and requires confirmation before SAF export. The image is never automatically uploaded. Attach the exported image manually through the Web file picker. Protected content may be blank.
- The old pipe/TextView terminal, SSH server, broad storage permissions, injected DOM polling, public release key, self-signed browser cookies and overlay repair chain are removed. This app does not add a native PTY emulator. Only a working upstream Harness Web PTY can satisfy terminal use.

## Important incomplete work

Authoritative Node/Agent/tool activity reporting is not yet connected. The service therefore reports activity as unknown and **never automatically freezes** the runtime. It holds no wake lock; OEM background completion is not guaranteed. Signal-group freeze functions in the supervisor are weaker than a dedicated cgroup freezer and are not represented as completed idle freezing.

The Git screen supports local status/branch, working/staged diff, one-file stage/unstage and an explicit reviewed commit using argv. It disables hooks, external diffs, signing, prompts, automatic maintenance and lazy fetch; global/system Git credentials are not loaded. Every operation refuses repositories with custom clean/smudge filters: even status/diff can invoke a clean filter when comparing worktree contents. No clone/fetch/push, automatic merge or worktree management is provided.

Directory moves, cross-filesystem moves, recursive deletion, large/binary preview, safe host-root approval UI, native Antigravity broker/multiple accounts, mobile resource limits and full selected feature matrix remain incomplete. Build success is not a claim that these features work. Android 16, Android 17, 4KB/16KB, KernelSU and real API tests have not been completed on a phone.

## Cloud build

The GitHub Actions workflow builds the runtime and ARM64 supervisor and copies its PIE executable to `app/src/main/jniLibs/arm64-v8a/libmobile_supervisor.so` before APK assembly. `extractNativeLibs` is enabled. Gradle packages the prepared native executable without rebuilding it.

Cloud signing reads `DSH_KEYSTORE_PATH`, `DSH_KEYSTORE_PASSWORD`, `DSH_KEY_ALIAS` and `DSH_KEY_PASSWORD` from the build environment. Without them, release output is unsigned. A CI-generated test key must not be described as a permanent user release key, and reinstall/upgrade requires the same key or explicit data export and uninstall. The repository's old public keystore is never used.

The testing workflow runs `:app:testDebugUnitTest` and `:app:assembleDebug`, not release lint or device instrumentation. `WebOriginTest` rejects external origins, userinfo, alternate loopback names and ports. `GitCommandsTest` checks argv handling and path rejection. Linux Node tests run `node --test dsh-mobile/tools/workspace-ops.test.mjs` for workspace pagination, stale writes, links/traversal, no-overwrite transfers and an ancestor-directory replacement race. These `/proc/self/fd` tests deliberately skip on non-Linux hosts. Installer tests cover archive policy separately. No Android environment or dependency downloads are needed on the local computer.

## Device smoke (user performed)

1. Install the test APK, grant real KernelSU root only after reviewing setup, and verify installation fails clearly without root.
2. Tap Start and confirm the authenticated Web page opens. Configure a provider in the Harness UI; never paste credentials into build logs.
3. Run a small text conversation and an explicit file/tool request. Treat a model/network error or missing selected feature as a failed test, not a successful APK check.
4. Try a file import/export, and capture a screenshot, preview it, export it, then manually attach the exported image.
5. Stop the runtime from the notification and check for leftover processes/mounts. Do not assume the signal-group fallback catches escaped children.
6. Record Android build, kernel, KernelSU configuration and page size. Background completion and automatic idle freezing are not accepted until their missing activity integration is implemented and tested.

## Workspace concurrency limits

Every workspace request opens and holds each ancestor using `O_DIRECTORY | O_NOFOLLOW`, then addresses children through its `/proc/self/fd` directory handle. Final file reads also use `O_NOFOLLOW`, regular-file checks, a bounded read and before/after stat checks. A replaced directory name cannot redirect an already-held directory handle into a symlink target. This is not a host-root bridge or a sandbox against other code already running with the runtime UID.

Text writes use a preview fingerprint (device/inode/size/nanosecond timestamps/mode and SHA-256), a cooperative exclusive lock, a same-directory temporary file, fsync, a repeated version check and atomic rename. External writers that ignore the helper lock can still race between the final check and rename; Node's APIs do not supply an atomic conditional inode/version replacement. Do not represent this as a race-free compare-and-swap editor. Reopen after a reported conflict and avoid simultaneous terminal edits of the same file.

File/link moves use no-overwrite hard-link publication followed by source removal, so they require the same filesystem and are not an atomic two-name rename. An interruption can leave both names; inspect the directory before retrying. Directory moves are refused rather than using a potentially overwriting rename. Entries are not recursively deleted, directory listings are limited to 10000 entries, and protected helper lock/temp names are hidden. No operation automatically retries after an unknown outcome.
