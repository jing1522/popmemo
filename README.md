# PopMemo

解锁手机 → 摆出没办完的事 → 每条左右一划处理掉。记一条：下拉通知栏点「写一条」，回车即存。

只给自己用：没有账号、没有网络、没有广告，APK 约 40 KB。

## 现在能做的

- **记**：打开就一个输入框，回车即存；可语音；可从常驻通知的「写一条」直接进
- **管**：点条目改字，长按设截止日 / 不弹了 / 删掉
- **截止日**：不用设提醒时间，只选"哪天之前"；越近越优先占弹窗卡位
- **弹**：解锁就弹，最多 3 条，没设日期的按最久没露面轮转
- **划**：右滑=办完（消失），左滑=还没，点旁边空白=只看一眼
- **兜底**：常驻通知显示「还有 N 件事」

## 还没验证的（都是真机风险）

- HyperOS 会不会杀后台（需自启动 + 省电无限制）
- 重启后能否自启（Android 15 大概率拒绝，打开一次 App 即可恢复）
- 语音识别（模拟器无麦克风）

## 自己编译

```bat
set JAVA_HOME=C:\Program Files\Java\jdk-17
D:\qingting\tools\gradle-8.11.1\bin\gradle.bat assembleDebug
```

产物：`build\outputs\apk\debug\popmemo-debug.apk`

`local.properties` 里的 `sdk.dir` 指向本机 SDK（`D:\qingting\tools`），该文件不入库。

## 文档

- [设计与决策.md](docs/设计与决策.md) —— 为什么这么做，规则和取舍
- [AGENTS.md](AGENTS.md) —— 协作规则（提交粒度、验证标准、push 归属）
