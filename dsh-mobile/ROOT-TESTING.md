# DSH Root testing alpha

这是首轮 root/chroot 架构测试版，不是交接文档全部功能的最终成品。需 ARM64、真实 su 授权并保持 SELinux Enforcing。本地没有安装 Android SDK/NDK 或构建依赖；APK 与运行包均由 GitHub Actions 构建。

**主要缺项：自动空闲冻结尚未接通，当前明确禁用；Antigravity 尚未接入 Harness 生成路由；子 Agent 自动独立 worktree 分配尚未完成。** 不要仅凭安装成功把这些功能当作可用。截图需预览导出后手动附加。首轮使用测试签名，不建议存入重要数据。

请先测试：授权 root → 查看能力报告 → 安装内置运行包 → 启动 → 配置中转并做一条简单文本任务 → 测试一次文件/Git 操作 → 停止。Android 16/17、KernelSU、实际后台任务、4KB/16KB 设备、性能和模型调用仍需要你在手机上验证。完整状态见仓库 `dsh-mobile/IMPLEMENTATION.md`。

This is a development APK for user testing, not a completed implementation of every item in the handoff plan. It uses a new application identity and must not overwrite the original DSH app.

## Safety and scope

- ARM64 and real `su` root are required. Keep SELinux Enforcing. No proot, SSH service, on-device compiler installation, boot autostart, or remote access is provided.
- The APK embeds a cloud-prebuilt Linux userspace and fixed Node/Harness versions. Installation requires free space for the compressed asset, staging, and installed runtime.
- The runtime is not an independently audited security sandbox. Only install trusted plugins and only grant access to disposable test projects initially.
- Read the device capability report before starting. Permission, namespace, mount, execution, and freezing failures must be reported, not treated as successful compatibility checks.
- The first alpha is signed with a CI-generated debug/testing key. Later builds may require uninstall/reinstall. Export important data first; this is not a private production signing identity.

## Evidence

GitHub Actions checks the runtime archive hash, runs keyless ARM64 userspace checks, compiles the Android application/native helper, and checks APK signature and ZIP alignment. Build details and SHA-256 are published next to the APK. Compilation does not validate KernelSU, Android SELinux execution policy, chroot on the phone, background freezing, or actual model responses.

## Not yet device-validated

Android 16 and Android 17, 4 KiB and 16 KiB device page sizes, OnePlus/KernelSU profiles, real Google OAuth and Antigravity requests, relay credentials, OEM background behavior, process cleanup after forced termination, and battery/memory performance require real-device testing. A cloud ARM64 Linux test is not an Android test.

The milestone implementation status is recorded in the repository. Missing features are not implied by a successful APK build. Antigravity must remain text-only until actual protocol support is implemented and validated; no Google password or token should be entered into chat or shared logs.

## First test

Install the APK, grant the app root in KernelSU, open the capability report, and install the embedded runtime. Stop immediately if the report requests disabling SELinux. Start the runtime and configure a relay in the Harness settings without putting credentials in logs. Test a disposable text session, a simple file read/write, and Stop. Report the visible error and redacted diagnostics if a step fails; do not repeatedly rerun operations with unknown effects.
