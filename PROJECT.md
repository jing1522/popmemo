# 工作区交接单（新对话先读这一份，不要重新探索）

> 读这份就能直接干活：不要重新 ls / grep 全仓，不要为了「确认环境」重跑编译，不要重新翻代码确认某个功能是否已实现。
> 只有真要修改某个类时，再按需打开那个文件。
> 最后核对时间：2026-11-19，HEAD `254ec02`。

## 1. 这是什么

`d:\Try` 是用户私人的「小工具 / 工具箱」仓库，不是商业项目。仓库：`https://github.com/jing1522/popmemo.git`，分支 `main`。

| 模块 | 位置 | 是什么 | 规模 | 当前版本 |
|---|---|---|---|---|
| **coursebox（课匣 CourseBox）** | `coursebox/` | 课程文件管理器：课程 → 格子两级收纳，手机文件真删/归档，批量整理 | 15 个类 3134 行 | **0.4**（versionCode 4），tag `coursebox-v0.4` |
| **PopMemo** | 仓库根 `src/` | 解锁手机弹重要事项（前台服务 + 悬浮窗） | 14 个文件 3633 行 | 1.0.0（versionCode 4），tag `popmemo-v1.0.0` |

- 两条线并行：**课匣**（`coursebox/`）+ **PopMemo**（根模块 `src/`）。PopMemo 最近改了设置页和悬浮球，**详细交接见第 9 节**。
- 根模块 `src/` 现在有未提交改动，归属 PopMemo 对话；做课匣时**只 add 你改的文件，绝不 `git add -A`**。
- PopMemo 的课表（`Course.java`/`CourseStore.java`/`WeekActivity.java`/`activity_week.xml`/`dialog_add_course.xml`）由另一条线在做，**永远不碰**。
- 用户口径：叫「小工具 / 工具箱」，不要叫「项目 / 软件」。

## 2. 环境（已配置好，直接用，不要验证）

```powershell
# cwd = d:\Try；必须关沙箱，否则写盘失败
& "d:\qingting\tools\gradle-8.11.1\bin\gradle.bat" :coursebox:assembleDebug --console=plain -q
```

- 工具链：JDK 17、Gradle 8.11.1、AGP 8.7.3，无第三方依赖，不依赖 Android Studio。
- 产物：`coursebox/build/outputs/apk/debug/*.apk`，构建任务会自动留存一份到 `D:\popmemo-releases\coursebox-<版本>-<时间>.apk`。
- 验证流程：我编译 → 把 APK 路径给用户 → 用户真机试 → 反馈后再改/提交。没有模拟器。
- UI 全部是 Java 代码动态构建，**没有 layout xml**。改界面去对应 Activity 的 `build*()` 方法里改。

## 3. 课匣类地图（不用再读源码确认）

| 文件 | 职责 |
|---|---|
| `MainActivity` | 首页课程墙、全局搜索、新建课程、⚙ 设置（回收站 / 换背景 / 恢复默认 / 使用指南） |
| `CourseActivity` | 单课程页：格子 chips（点「⋯」改名/排序/删）、格子内搜索、「选择」按钮 + 长按进圈选、移动/导出/删除、换课程颜色 |
| `ImportActivity` | 批量整理：SAF 选文件夹 → 扫描识别 → 逐条改归属 → 执行（复制校验后删原文件） |
| `BrowseActivity` | 手机文件浏览：授权文件夹、下钻、圈选、收进匣子（校验后删原）/ 真删 |
| `CaptureActivity` | 分享入库（ACTION_SEND），一步选「课程·格子」 |
| `TrashActivity` | 回收站独立页：放回原处 / 彻底删除 / 清空，留 7 天 |
| `HelpActivity` | 内置使用指南（文案与实际入口保持一致，改了入口要同步改这里） |
| `Pick` | 通用一步选择器：一列「课程 · 格子」，入库/移动/收进/改归属四处复用 |
| `Box` | 文件层：私有匣子目录、`importStream`（临时文件 + 边写边算 SHA-256 + 字节校验 + rename 失败回退 copy）、`move`、`export`、回收站、`displayName`（按 MIME 补扩展名） |
| `Store` | JSON 持久化：courses + inbox + trash（超 7 天清理），主线程全量重写 |
| `Models` | `Course{id,name,color,cats}`、`Cat{id,name,icon,items}`、`Item{name,rel,size,at,sha}`、`TrashItem{...,deletedAt,courseId,catId,courseName,catName}`；`id="inbox"` 是待整理 |
| `Classifier` | 按扩展名/文件名关键词猜课程与格子 |
| `Thumb` | 缩略图：内存 LRU + `cacheDir/thumb` 磁盘缓存 + 固定 2 线程池；`load(...)` 后台回填 ImageView，`init(ctx)` 开磁盘缓存 |
| `Ui` | 配色、圆角、dp、`typeOf` / `isImg`、`sizeText`、`dateText`、`tv` |
| `FileServer` | ContentProvider（`com.coursebox.files`），把匣内文件临时授权给外部应用预览 |

## 4. 版本里已经有什么

- **0.2**：课程/格子收纳、分享入库、SAF 手机文件浏览与真删、批量整理、SHA-256 去重、全局+格子内搜索、回收站 7 天、自定义背景、内置指南、格子顺序可调。
- **0.3**：批量整理清单图片缩略图、跨课程移动（待整理 → 具体课程）、MIME 补扩展名、缩略图后台解码 + 磁盘缓存。
- **0.4**：可见入口（「选择」按钮、格子「⋯」）、一步「课程·格子」选择器、回收站独立成页、批量整理去掉二次确认。

## 5. 已知技术债 / 候选下一步

1. 无单元测试、无 lint 基线；改 `Box` 的文件操作最容易回归。
2. `CourseActivity` 仍是最长的单文件，再加功能前建议先抽「文件操作层」。
3. `Store` 每次操作全量重写 JSON（主线程），当前数据量没问题，大了再优化。
4. 只有 debug APK，没签 release；要给别人装得先签 key。
5. 缩略图磁盘缓存无容量上限（系统会清 cacheDir，暂可接受）。
6. 圈选只能逐个勾，没有范围选择；选择器在课程×格子很多时列表会变长。
7. 内置查看器没做（用户决定先不做）：图片/PDF 可行，Office 不现实；现在一律跳系统应用。

## 6. 规则（来自 AGENTS.md，必须遵守）

- 一个提交 = 一个可独立回滚的功能点，不攒批。
- 提交信息：`feat: / fix: / docs: / style: / chore: <中文一句话>`，一行。
- 提交前 `assembleDebug` 必须通过；提交后 `git status` 要干净。
- 不提交：`build/`、`.gradle/`、`local.properties`、`*.apk`、`.idea/`、`*.iml`、`kexia-prototype/`。
- 发版：改 `coursebox/build.gradle` 的 versionCode/versionName → 编译 → CHANGELOG.md 记一笔 → 打 tag `coursebox-vX.Y`。
- 不 `--force`、不改写历史、不动别人的提交；要顺手重构先问。
- AGENTS.md 写「push 由用户执行」，但用户常直接说「推」，以用户当次指令为准。
- 提交时只 `git add` 指定文件，**绝不 `git add -A`**（根模块有别人正在写的半成品）。

## 7. 已知的坑

- Shell 写 `d:\Try` 需要 `dangerouslyDisableSandbox: true`，否则报权限错误。
- `Edit` 对不存在的新路径会报 `PathNotFound`，新建文件用 `Write`（绝对路径）。
- `git push` 偶发 `Recv failure: Connection was reset`，过一会原样重试，不要改 remote。
- 后台命令返回 `shell_command_id` 后不要重复跑，等通知。
- 改动了可见入口，记得同步 `HelpActivity.SECTIONS` 文案。

## 8. 用户偏好

- 中文、直接、别客套；不要为了「稳妥」加多余的确认弹窗和兼容层。
- 讨厌功能藏在长按里，入口要看得见、路径要短。
- 喜欢版本号 / CHANGELOG / tag 体现历程。
- 真机验证只在他手上，报 APK 绝对路径即可。

## 9. PopMemo 交接（2026-11-19）

> 迭代过程与参赛叙事见 `POPMEMO-迭代记录.md`（含 git 时间线、三次关键取舍、赛道 9 材料对照）。

### 9.1 一句话状态

主界面 + 解锁弹窗 + 悬浮球都能日常用。最近一版重做了**悬浮球**、新增了**设置页**，已编译通过，**等用户真机验证，尚未 commit**。

### 9.2 构建（根模块就是 PopMemo）

```powershell
# cwd = d:\Try；必须 dangerouslyDisableSandbox: true
& "d:\qingting\tools\gradle-8.11.1\bin\gradle.bat" assembleDebug --console=plain
```

- 构建成功会自动留存 `D:\popmemo-releases\popmemo-<版本>-<时间戳>.apk`，不用去翻 `build/outputs`。
- 最新包：`D:\popmemo-releases\popmemo-1.0.0-20261115-0911.apk`。
- 别为了"确认环境"重跑一遍编译，第一次真要出包再跑。

### 9.3 类地图（`src/main/java/com/popmemo/`）

| 文件 | 职责 |
|---|---|
| `MainActivity` | 主界面：一行输入记一条、列表、点改字 / 长按设截止日和分类·静音·删、已办完折叠、以后折叠、「⋯」→ `SettingsActivity`。`onResume` 里做 `refresh` + `ensureService` + `applyBackground` + `FloatingBall.hide()` |
| `Memo` | 数据模型：`id/text/tag/dueAt/doneAt/muted` 等；`dueAt=0` = 没设日期，`doneAt>0` = 已办完；有 `isDone()`、`daysLeft(now)`、`dueLabel(now)`、`dayOffset(n)` |
| `MemoStore` | JSON 存 `filesDir`；`load()` / `save(List)`；静态 `setListener` / `notifyChanged` 触发界面刷新 |
| `DueShower` | 解锁弹窗呈现与过滤（`pendingOf`）。`popup_enabled=false` 直接不弹；`popup_every` 控频率；同一条 30 分钟内不重复 |
| `UnlockService` / `UnlockAccessibilityService` | 两条解锁监听路径；无障碍开了就停掉前台服务 |
| `FloatingBall` | 悬浮球，静态入口 `sync/hide/refreshCount` |
| `SettingsActivity` | 设置页（**新文件，未提交**）：外观 / 提醒 / 系统 / 数据 / 关于 五组卡片，UI 全代码拼 |
| `BootReceiver` | 开机自启保活 |

新增条目照抄 `FloatingBall.commitAdd` 里的写法即可：`new Memo(now, text, false, now, 0L, 0L, dueAt)`，最后一个是截止时间。

### 9.4 悬浮球当前交互（改之前先看这段，别按旧版理解）

- **球**：46dp 圆，显示"今天还剩几件"（`dueAt>0 && daysLeft<=0`），没有显示 ✓；可拖，松手吸边，位置存 `ball_right`/`ball_y`。
- **点球**：开面板（全屏半透明 backdrop，点外面关）。
- **两页**：「今天」= 今天到期的 + 已过期的；「以后」= 其余全部待办（明天起的、没设日期的）。
- **翻页**：点顶部两个小圆点，或在**空白处**横滑。落在条目上的横滑不翻页——靠 `hitRow()` 按下位置判断，实现在 `pageHolder` 的 `onInterceptTouchEvent` 匿名子类里。
- **条目**：右滑 = 办结，左滑 = 删除；阈值 `dp(96)`，不够就弹回。实现是 `RowSwipe`。
- **记一条**：底部「＋ 记一条」原地变输入框（不跳主界面），回车/点「存」保存。**今天页存的 → dueAt = 今天；以后页存的 → dueAt = 3 天后**（用户明确要求，别改）。
- **面板高度**：0 条 = 88dp；每条 +38dp；封顶 280dp 后内部滚动（用户要求"占地要小"）。
- **焦点**：面板默认 `FLAG_NOT_FOCUSABLE`，打字时用 `setPanelFocusable(true)` 临时放开，不然键盘起不来。

### 9.5 prefs 与文件

`getSharedPreferences("popmemo")`：

- `character_name` / `character_style` / `character_lines` / `deepseek_key`
- `ball_enabled`（默认 true）/ `ball_right` / `ball_y`
- `popup_enabled`（默认 true）/ `popup_every`（默认 5）/ `snooze_until`

文件：背景 `filesDir/bg.jpg`，角色头像 `filesDir/character.jpg`。

### 9.6 未提交改动（`git status`）

- `M`：`AndroidManifest.xml`（注册 SettingsActivity）、`DueShower.java`（弹窗总开关）、`FloatingBall.java`（重做）、`MainActivity.java`（⋯ 跳设置页 + 删掉搬走的菜单代码）
- `??`：`SettingsActivity.java`
- 这"四改一增"= 两个功能点，验证通过后建议分两次提交：① 设置页 ② 悬浮球（两页 + 滑动 + 原地记）
- **不碰**：`Course.java` / `CourseStore.java` / `WeekActivity.java` / `activity_week.xml` / `dialog_add_course.xml`

### 9.7 待办（按优先级，用户认同方向）

1. **数据能进能出**（导出/导入 JSON 到文件管理器）——最大风险：全本地、清空不可撤销。
2. **「今天/以后」改成顶部 tab**——现在条目一多就没空白，空白处滑翻页会失效，且与条目滑动抢手势。
3. 三个入口（主界面 / 悬浮球 / 弹窗）主次没在界面上体现。
4. 工程债：UI 全代码拼、逻辑散在各 Activity。

### 9.8 用户对 PopMemo 的口味

- **悬浮球是核心，弹窗是辅助**（弹窗不能删，但要能关，已加总开关）。
- 喜欢它占地面积小，面板要随内容伸缩。
- 讨厌点小圆点翻页，想直接滑动。
- 哪页记的归哪页——这是他主动提的需求。
- 三条入口做同一件事让他有"从哪进"的负担，他自己也意识到了。

## 10. 多 AI 分工（2026-11-20 定）

用户会用多个 AI 并行推进。**开工前先对号入座，别越界。**

### 铁律三条

1. **一个模块同一时间只让一个 AI 动手。** 想并行就分到不同模块或不同文件，不要两个 AI 同时改同一批 .java。
2. **`git add` 只写具体文件名，永远不要 `git add -A`**（根模块随时有别人未提交的半成品）。
3. **开工先 `git status`。** 看到不认识的 `M` / `??`，说明别人正在做，先问用户，别覆盖。

### 角色与领地

| 角色 | 可改 | 只读 | 绝对不碰 |
|---|---|---|---|
| **PopMemo 主开发** | 根 `src/main/java/com/popmemo/`（课表 5 文件除外）、`src/main/res/layout/activity_main.xml`、`src/main/AndroidManifest.xml` | AGENTS.md、PROJECT.md 第 9 节、POPMEMO-迭代记录.md | `coursebox/`、课表 5 文件 |
| **课匣开发** | `coursebox/` 整个模块 | PROJECT.md 第 1~8 节 | 根 `src/` |
| **文档 / 参赛材料** | 根 `*.md`、`docs/` | 全部 | 任何 `src/`、`coursebox/`（只读也不行改） |
| **构建 / 发版** | 只跑 gradle、打 tag | 全部 | 任何源码改动 |

课表 5 文件（谁都不碰）：`Course.java` / `CourseStore.java` / `WeekActivity.java` / `activity_week.xml` / `dialog_add_course.xml`。

### 开工 / 收工清单

- **开工**：读 AGENTS.md → 读自己角色的文档 → 按 AGENTS.md「工作留痕」节读 `D:\AI留痕\Try\` 最后 3 条并原文引用最后一条 → `git status` 确认工作区里没有自己要改的文件正被改。
- **收工**：`assembleDebug` 通过 → 在 `POPMEMO-迭代记录.md` 第 11 节滚动日志追加一行 → 按 AGENTS.md 字段写一条留痕 → 覆盖刷新本文件的「当前进度」「下一步」 → 只 `git add` 自己改的文件 → 把提交哈希和一句话交给用户。
- **不替代用户 push**，除非用户当次明确说"推"。

### 文档权威顺序

1. `PROJECT.md` 第 9 节 + `POPMEMO-迭代记录.md` —— PopMemo 现状与路线
2. `PROJECT.md` 第 1~8 节 —— 课匣现状
3. `AGENTS.md` —— 规则
4. `docs/设计与决策.md`、`README.md` —— **只当历史存档，不当事实**（顶部已标警告）

### 并行前的唯一前置条件

工作区现在压着 P5 的未提交改动（设置页 + 悬浮球重做）。**先让用户真机验证并提交掉，再开第二个 AI 并行**；否则两个 AI 都在脏工作区上，谁都分不清哪行是谁的。

## 11. 课匣改动日志

- 2026-11-21 | 批量整理重做（`ImportActivity` + `Classifier.Guess` 加 `matched` 标记） | 扫描：递归深度上限 24、捕 `Throwable`（原来只捕 `Exception`，`StackOverflowError` 会让线程静默死亡）、实时进度"已找到 N 份"、结果在后台收完再一次性交主线程；列表：按「课程·格子」分组、靠猜组才自动展开、每组最多渲染 40 行（超出点开）、底部常驻批量条与份数按钮、空状态引导 | 已编译 `D:\popmemo-releases\coursebox-0.4-20261121-1043.apk`，**等真机验证，未提交**
- 2026-11-21 | 课程卡片排序（`MainActivity`） | 长按首页课程卡 → 置顶 / 往前挪 / 往后挪，顺序即 `store.courses` 顺序并落盘；`wallSv` 提升为字段，重排后原地刷新不重建 Activity | 已编译 `D:\popmemo-releases\coursebox-0.4-20261121-1046.apk`，**等真机验证，未提交**
- 2026-11-21 | 拖拽排序 + 扫描中新建格子 | 课程卡改为长按直接拖、松手插到目标位置（`MainActivity.setupCourseDrag`）；课程页格子包成 `chipWrap` 后长按横向拖（`CourseActivity.setupChipDrag`），选中态跟随原格子；`Pick.target` 末尾加「＋ 新建格子」「＋ 新建课程」，扫描结果里就地建，不必退出重扫 | 已编译 `D:\popmemo-releases\coursebox-0.4-20261121-1051.apk`，**等真机验证，未提交**
