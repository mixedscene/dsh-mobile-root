# DSH Root (experimental Android fork)

DSH Root is a root-only Android shell for the Harness Web interface. It has a separate application ID (`io.github.mixedscene.dshroot`), target SDK 36, and no compatibility with the old proot installer or its private data. Install it alongside the original application and export ordinary sessions/files from the old application yourself. Do not copy credential databases or old signing keys.

## Current behavior

- Setup requires a user action before `su` probing and installation of the bundled verified runtime. Ordinary launches do not install packages, run npm, or compile native modules on the phone.
- The native supervisor launches a chroot runtime. SELinux stays Enforcing; root capability, chroot, network access, KernelSU policy and page-size behavior require device verification. The runtime is not a security sandbox for untrusted plugins.
- Tap **Start** to launch one foreground-service-owned runtime. Exit/failure requires a manual restart. The notification and Settings provide an explicit Stop action. No periodic health check or hidden repair installs run.
- The WebView accepts exactly `http://127.0.0.1:3080`. It consumes the supported `dsh web` token URL printed by the server, without reading signing secrets or minting cookies. The URL/token are memory-only and excluded from diagnostics. External navigation and browser permission requests are blocked.
- Settings exports at most 120 lifecycle messages, without runtime output, account tokens, provider keys or user conversation content. Native/Node process output is consumed rather than retained.
- The private file exchange supports bounded SAF import/export and explicit deletion. It is not a complete root filesystem manager. Harness workspace files remain managed by the Web UI. Imported exchange files are not automatically attached to a conversation.
- Screenshot captures one user-consented MediaProjection frame, displays a preview, and requires confirmation before SAF export. The image is never automatically uploaded. Attach the exported image manually through the Web file picker. Protected content may be blank.
- The old pipe/TextView terminal, SSH server, broad storage permissions, injected DOM polling, public release key, self-signed browser cookies and overlay repair chain are removed. This app does not add a native PTY emulator. Only a working upstream Harness Web PTY can satisfy terminal use.

## Important incomplete work

Authoritative Node/Agent/tool activity reporting is not yet connected. The service therefore reports activity as unknown and **never automatically freezes** the runtime. It holds no wake lock; OEM background completion is not guaranteed. Signal-group freeze functions in the supervisor are weaker than a dedicated cgroup freezer and are not represented as completed idle freezing.

The Git screen supports local status/branch, working/staged diff, one-file stage/unstage and an explicit reviewed commit using argv. It disables hooks, external diffs, signing, prompts, automatic maintenance and lazy fetch; global/system Git credentials are not loaded. Mutations refuse repositories with custom clean/smudge filters. No clone/fetch/push, automatic merge or worktree management is provided.

The complete workspace file manager, safe host-root approval UI, native Antigravity broker/multiple accounts, mobile resource limits and full selected feature matrix remain incomplete. Build success is not a claim that these features work. Android 16, Android 17, 4KB/16KB, KernelSU and real API tests have not been completed on a phone.

## Cloud build

The GitHub Actions workflow builds the runtime and ARM64 supervisor and copies its PIE executable to `app/src/main/jniLibs/arm64-v8a/libmobile_supervisor.so` before APK assembly. `extractNativeLibs` is enabled. Gradle packages the prepared native executable without rebuilding it.

Cloud signing reads `DSH_KEYSTORE_PATH`, `DSH_KEYSTORE_PASSWORD`, `DSH_KEY_ALIAS` and `DSH_KEY_PASSWORD` from the build environment. Without them, release output is unsigned. A CI-generated test key must not be described as a permanent user release key, and reinstall/upgrade requires the same key or explicit data export and uninstall. The repository's old public keystore is never used.

Cloud checks include `:app:testReleaseUnitTest`, `:app:lintRelease` and `:app:assembleRelease` as selected by the workflow. `WebOriginTest` rejects external origins, userinfo, alternate loopback names and ports. `GitCommandsTest` checks argv handling and path rejection. Installer tests cover archive policy separately. No Android environment or dependency downloads are needed on the local computer.

## Device smoke (user performed)

1. Install the test APK, grant real KernelSU root only after reviewing setup, and verify installation fails clearly without root.
2. Tap Start and confirm the authenticated Web page opens. Configure a provider in the Harness UI; never paste credentials into build logs.
3. Run a small text conversation and an explicit file/tool request. Treat a model/network error or missing selected feature as a failed test, not a successful APK check.
4. Try a file import/export, and capture a screenshot, preview it, export it, then manually attach the exported image.
5. Stop the runtime from the notification and check for leftover processes/mounts. Do not assume the signal-group fallback catches escaped children.
6. Record Android build, kernel, KernelSU configuration and page size. Background completion and automatic idle freezing are not accepted until their missing activity integration is implemented and tested.
