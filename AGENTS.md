# 协作规则（PopMemo：解锁弹窗备忘录）

## 项目
原生安卓小 App，不引第三方库。一句话目标：**解锁手机就弹出重要事项，左右一滑关掉，记一条只用一行。**

- 技术栈：Kotlin/Java + 经典 View（不用 Compose）+ 前台服务 + 系统悬浮窗。
- 关键链路：`ACTION_USER_PRESENT` 解锁广播 → 前台服务 → `TYPE_APPLICATION_OVERLAY` 浮层 → 左/右滑关闭。
- 节流：同一条事项 30 分钟内不重复弹。

## 提交规则
- 一个提交 = 一个可独立回滚的功能点。不攒批，也不按行提交。
- 提交前必须自检：`gradlew.bat assembleDebug` 编译通过。
- 涉及「解锁弹窗 / 悬浮窗 / 滑动关闭」的改动，必须在真机上跑通一次。
  真机在用户手上，所以流程是：我编译出 APK → 用户安装验证 → 通过后我再提交。
- 提交信息：`feat: / fix: / docs: / style: / chore: <中文一句话>`，一行。
- 提交后跑 `git status`，工作区必须干净。

## 边界
- 不提交：`build/`、`.gradle/`、`local.properties`、`*.apk`、`.idea/`、`*.iml`、`.probe.txt`。
- 禁止 `--force`，禁止改写已有历史，禁止动别人的提交。
- **push 由用户执行。** 我只做本地 commit，并把哈希 + 一句话摘要写给用户。
  如果用户明确让我推，失败就原样贴报错，不换姿势重试。
- 要顺手重构无关代码之前，先问用户。

## 环境限制
- `D:\Try` 的 Windows 文件权限目前异常：workspace-write 下任何命令都报
  `SetNamedSecurityInfoW failed (Win32 5)`，命令只能在全权限下跑。
- 本机已有：JDK 17（`C:\Program Files\Java\jdk-17`）、Gradle 缓存 8.11.1 + AGP 8.7.3。
- 安卓 SDK 位置待确认；找到后写进 `local.properties`（该文件不入库）。
