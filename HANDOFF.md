# 交接文档（给 WorkBuddy AI）

> 使用者：把这台机器上的「课表轻助」安卓项目交给另一个 agent 软件继续做。
> 接手方：**先完整读本文件，再按「待办」顺序执行**；所有路径均为本机绝对路径或
> 相对仓库根 `G:\weixingkebiao` 的路径。

## 0. 一句话现状

功能 M1（成绩/GPA 分析看板 + 考试倒计时与考前提醒）代码**已全部写完**，
`testDebugUnitTest`（59 个用例）、`lintDebug`、`assembleDebug`、`assembleRelease` 全部通过，
已用 release 包覆盖安装到真机（v1.3.3 / versionCode 7）并完成大部分界面走查；
**尚未 git 提交**，最后一轮两处 UI 修复（趋势图标签裁切、考试提醒弹窗列表）还没重装验证。
M2（桌面小组件）、M3（空闲教室）尚未开工。

## 1. 接手前必读的环境事实

| 项 | 值 |
| --- | --- |
| 仓库根 | `G:\weixingkebiao` |
| Android 工程 | `G:\weixingkebiao\android` |
| 包名 / 应用 | `com.gzus.courseassistant`（课表轻助） |
| 当前版本 | `versionCode 7`、`versionName "1.3.3"`（`android/app/build.gradle.kts`，基线是 6 / 1.3.2） |
| JDK | 17（`JAVA_HOME=E:\java\jdk-17.0.2`，已是用户级环境变量） |
| Android SDK | `C:\Users\255\AppData\Local\Android\Sdk`（`platforms\android-36`、`build-tools\36.0.0`、`platform-tools` 37.0.1）— 曾整个被删，已重新安装，**不要再删** |
| adb | `C:\Users\255\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| 真机 | `xgnv65kzb6cix4pr`（Redmi M2004J7AC，product:atom），已授权、在线 |
| 发布签名 | `android/keystore/release.jks` + `android/keystore.properties`（均 gitignore） |
| Gradle | wrapper 8.13，AGP 8.13.2，Kotlin 2.2.21，无 version catalog |
| 构建命令 | `cd G:\weixingkebiao\android; .\gradlew.bat testDebugUnitTest --console=plain` 等 |

**坑（务必先看）**

1. 本机 `pwsh` 工具实际由 **Windows PowerShell 5.1** 承载：写 `pwsh x.ps1` 会 CommandNotFound，
   直接 `& script.ps1`；`Get-Content` 会把 UTF-8 中文读成乱码，**判断文件内容一律用 read 工具**。
2. `adb exec-out screencap -p > file.png` 在 PowerShell 下会破坏二进制；
   必须用 `cmd.exe /c "\"<adb>\" exec-out screencap -p > out.png"`。
3. **debug APK 不能覆盖安装在 release 包上**（签名不同会 INSTALL_FAILED_UPDATE_INCOMPATIBLE，
   卸载会丢用户数据）。真机验证一律 `.\gradlew.bat assembleRelease` 后
   `& $adb install -r android\app\build\outputs\apk\release\app-release.apk`，签名一致可保留数据。
4. `lint { abortOnError = true }`：lint 有 error 就会构建失败；`strings.xml` 里 name 重复会直接
   `Resource merger: Found item ... more than one time` 失败（已在 M1 中踩过一次）。
5. Material `AlertDialog`（MaterialAlertDialogBuilder）里 `setMessage` 与 `setSingleChoiceItems`
   **不能同时用**，同时设置时列表不显示；弹窗提示文案请改用布局内的静态 TextView。
6. 本项目**不引第三方图表库、不引 Compose/Glance**；折线图是自绘 `Canvas`（`TrendChartView`）。
7. 单元测试全部平铺在 `android/app/src/test/java/com/gzus/courseassistant/`，
   `package com.gzus.courseassistant`，JUnit4（`org.junit.Test` / `org.junit.Assert.*`）。

## 2. 已完成的工作（M1，代码全部在盘上）

### 2.1 功能 A：成绩 / GPA 分析看板

新增：

- `android/app/src/main/java/com/gzus/courseassistant/data/model/GradeStats.kt`
  - `TermStat(term, courseCount, credits, gpa, weightedScore)`；
  - `GradeDistribution(excellent/good/medium/pass/fail/unknown)` + `total`；
  - `TypeStat(courseType, courseCount, credits, gpa)`；
  - `GradeStats(totalCourses, totalCredits, cumulativeGpa, weightedScore, failedCourses, terms, distribution, byType)` + `hasData`；
  - `object GradeStatsCalculator { fun compute(grades: List<Grade>): GradeStats }`
    规则：绩点加权仅 `gpa` 可解析且 `credit > 0`；加权平均分仅 `score` 在 0..100 且 `credit > 0`；
    分布桶 90/80/70/60；`failedCourses` 统计 `!passed`；`terms` 按 (year desc, number desc)；
    `byType` 按学分降序。
- `android/app/src/main/java/com/gzus/courseassistant/ui/academics/TrendChartView.kt`
  自绘折线图（网格 + 折线 + 面积 + 数据点 + 数值/学期标签），`setData(terms, emptyMessage)`，
  最多 10 个点，空态画提示文案。**刚修过首尾标签贴边被裁的问题**（按标签宽度内缩 `inset`
  并对数值/标签 x 做 `coerceIn`）。
- `android/app/src/main/res/layout/view_stat_bar_row.xml`、`view_stat_text_row.xml`（分布条 / 文本行）
- `android/app/src/test/java/com/gzus/courseassistant/GradeStatsTest.kt`（8 个用例）

修改：

- `android/app/src/main/res/layout/fragment_academics.xml`：ToggleGroup 由「成绩|考试」扩为
  **「成绩|分析|考试」**（`analysisTab`）；`FrameLayout` 内新增 `NestedScrollView(id=analysisScroll)`
  （与 `academicList` 互斥显隐），内部为概览卡（`analysisGpaValue` 等 5 个数值）、
  趋势卡（`trendChart`）、分布卡（`distributionContainer`）、考试性质卡（`typeContainer`）、
  学期明细卡（`termContainer`）、空态 `analysisEmpty`。
- `android/app/src/main/java/com/gzus/courseassistant/ui/academics/AcademicsFragment.kt`：
  `mode`（`MODE_GRADES=0 / MODE_ANALYSIS=1 / MODE_EXAMS=2`）取代原 `showingGrades`；
  新增 `renderAnalysis` / `renderDistribution` / `renderTypeStats` / `renderTermStats` / `summaryText`；
  「分析」页固定展示全部学期累计，与学期 Spinner 解耦（该页隐藏 Spinner 与摘要条）。
- `android/app/src/main/res/values/strings.xml`：新增全部 `analysis_*` 文案（含
  `analysis_distribution_*`、`analysis_by_type`＝「考试性质」、`analysis_term_detail` 等）。

### 2.2 功能 B：考试倒计时与考前提醒

新增：

- `android/app/src/main/java/com/gzus/courseassistant/data/model/ExamSchedule.kt`
  - `data class Upcoming(exam, start: LocalDateTime?, daysLeft: Long?, minutesLeft: Long?, hasTime: Boolean)` + `isToday`；
  - `object ExamSchedule`：`parseStart`、`hasTime`、`upcoming(exams, now)`（解析失败的排最后）、
    `next(exams, now)`、`daysUntilNext`、`parseStartDetail`（`ParsedStart(time, hasTime)`）、
    `parseDate(value, term)`（`uuuu-M-d` / `uuuu/M/d` / `uuuu.M.d` / `uuuuMMdd`，STRICT；
    中文 `M月D日` 按学年语义推断年份：月份 >= 8 用 `term.year`，否则 `term.year + 1`）、
    `parseTime`（取时间段里第一个 `HH:mm`）、`minutesBetween`。
    `next()` 对未公布时刻的考试按自然日判断（当天整天算将要到来）。
- `android/app/src/main/java/com/gzus/courseassistant/notifications/ExamReminderPlanner.kt`
  纯逻辑：`ExamReminder(exam, key, leadHours, onExamDay, minutesLeft)`、`reminderKey`、
  `activeKeys`（用于清理过期键）、`plan(exams, now, leadHours, reminded)`；
  `DAY_ONLY_LEAD = -1` 表示时间未公布、只能按天提醒（当天 / 前一天）。
- `android/app/src/main/java/com/gzus/courseassistant/notifications/ExamReminderWorker.kt`
  `CoroutineWorker`，每小时跑一次；读完设置→`canPostNotifications`→`ExamReminderPlanner.plan`
  →逐条 `NotificationHelper.notifyExam`→把仍有效的键写回 `settings.examRemindedKeys`。
- `android/app/src/main/java/com/gzus/courseassistant/ui/academics/ExamDetailDialog.kt`（考试详情弹窗）
- `android/app/src/test/java/com/gzus/courseassistant/ExamScheduleTest.kt`（10 个用例）、
  `ExamReminderPlannerTest.kt`（7 个用例）

修改：

- `data/local/AppSettings.kt`：新增 `examReminderEnabled`（默认 true）、`examReminderLeadHours`
  （StringSet，默认 `{24, 3}`）、`examRemindedKeys`，key 前缀 `exam_reminder_*` / `exam_reminded_keys`。
- `notifications/NotificationHelper.kt`：新增 `EXAM_CHANNEL = "exam_reminders"` 与
  `notifyExam(context, exam, leadLabel, onExamDay)`（点击带 `EXTRA_OPEN_ACADEMICS`，
  通知 id 由课程名+日期+时间哈希生成）；`notifyUpdate` 曾误删后已按原逻辑补回（勿再动）。
- `CourseApplication.kt`：新增 `EXAM_WORK = "exam-reminders"`，`PeriodicWorkRequest` 1 小时。
- `ui/main/MainActivity.kt`：新增 `EXTRA_OPEN_ACADEMICS = "open_academics"` 与 `openAcademics()`，
  onCreate / onNewIntent 都会切到 `nav_academics`。
- `ui/academics/ExamAdapter.kt`：`submit(items: List<Upcoming>)`，右侧显示「距今天数」
  （`exam_days_left` / `exam_days_left_today`），整项点击回调 `(Upcoming) -> Unit`。
- `ui/academics/AcademicsFragment.kt`：考试 tab 用 `ExamSchedule.upcoming(...)`，
  摘要栏显示「考试 N 场」+ 最近一场倒计时（`countdownLabel`）。
- `res/layout/item_exam.xml`：首行新增 `examDaysLeft`。
- `res/layout/fragment_today.xml` + `ui/today/TodayFragment.kt`：`todayState` 下方新增
  `examCountdownCard`（最近一场考试倒计时，无考试隐藏，点击切到学业 tab）。
- `res/layout/fragment_more.xml` + `ui/more/MoreFragment.kt`：新增「考试提醒」按钮
  （文案 `more_exam_reminder_status` 形如「考试提醒 · 提前 1 天和 3 小时」）+
  下方静态提示 TextView（`examReminderHint`）；点击弹 4 选项单选（未开启 / 提前 1 天 /
  提前 3 小时 / 提前 1 天和 3 小时），确认后写设置并清空 `examRemindedKeys`。
- `README.md`、`android/README.md`：功能列表与「已知边界」已同步 M1 内容。

### 2.3 已修的 bug（不要回退）

- `ExamSchedule` 里 `ParsedStart` 误用 `.first/.second` → 已改 `.time/.hasTime`。
- `TrendChartView` 引用 `R.string.gpa_trend_empty` → 实为 `analysis_gpa_trend_empty`。
- `strings.xml` 里 `exam_notification_lead_day` / `exam_notification_lead_hours` 重复定义已删重。
- 「课程性质」标签 → 数据实际是 `ksxz`（考试性质），已改「考试性质」。
- 考试提醒弹窗原先 `setMessage` + `setSingleChoiceItems` 同时用导致列表不显示 →
  已去掉 `setMessage`，提示文案移到「更多」页按钮下方。

## 3. 验证记录（截至交接时）

| 检查 | 结果 |
| --- | --- |
| `.\gradlew.bat testDebugUnitTest --console=plain` | ✅ BUILD SUCCESSFUL，**59 个用例全过**（GradeStats 8 + ExamSchedule 10 + ExamReminderPlanner 7 + 原有 34） |
| `.\gradlew.bat lintDebug --console=plain` | ✅ BUILD SUCCESSFUL（`abortOnError=true`，零 error） |
| `.\gradlew.bat assembleDebug` | ✅ 产出 `android/app/build/outputs/apk/debug/app-debug.apk`（7.6 MB） |
| `.\gradlew.bat assembleRelease` | ✅ 产出 `android/app/build/outputs/apk/release/app-release.apk`（2.0 MB，release 签名；最近一次 14:22 构建，含最后两处 UI 修复） |
| 真机安装 | ✅ `adb install -r` 成功保留数据，已升级到 v1.3.3（versionCode 7） |
| 真机界面走查 | ✅ 今日页、学业页（成绩/分析/考试三 tab）、更多页、考试提醒设置按钮与弹窗标题都正常；分析页用真实缓存数据渲染出累计绩点 2.53 / 加权平均 76.3 / 61.5 学分 / 27 门 / 1 门不及格、绩点分布条、考试性质、学期明细 |
| 未验证 | ⚠️ 最后一轮 UI 修复（趋势图首尾标签、考试提醒弹窗列表）**代码已改、release 包已重编，但还没重装到真机走查**；⚠️ 考试倒计时卡片和提醒通知需要真实考试数据，而机器上的教务会话**已过期**（更多页显示「未登录」），需要用户重新登录后同步才能验证 |

走查截图在 `G:\weixingkebiao\qa\dev\`（`01-main.png` ~ `08-exam-reminder-dialog.png`，
该目录被 `.gitignore` 忽略，仅作证据保留）。

## 4. 待办（按顺序）

### P0 收尾 M1（约 10 分钟）

1. 重装最新 release 包并复验两处修复：
   ```powershell
   $adb='C:\Users\255\AppData\Local\Android\Sdk\platform-tools\adb.exe'
   cd G:\weixingkebiao\android; .\gradlew.bat assembleRelease --console=plain
   & $adb install -r app\build\outputs\apk\release\app-release.apk
   & $adb shell am start -W -n com.gzus.courseassistant/.ui.main.MainActivity
   ```
   进「学业 → 分析」看趋势图首尾标签是否完整、右上角数值是否不被裁；进「更多 → 考试提醒」
   看单选列表是否出现。
2. 重新跑一遍 `testDebugUnitTest` 与 `lintDebug`（确认最后几处改动没引入问题）。
3. **git 提交**（当前有 16 个修改文件 + 11 个新文件未提交；仓库规范是中文提交说明，
   见 `git-commit-zh` 技能/既有 4 次提交风格）。顺手处理未跟踪噪音：
   把 `.gradle-test.log` 加入 `.gitignore`（或删除），`android/.kotlin/` 也建议忽略。
   建议一次提交，说明示例：`新增成绩绩点分析与考试倒计时提醒（v1.3.3）`。

### M2 桌面小组件 + 下一节课（目标 v1.3.4）

需求：桌面 App Widget 展示「正在上课 / 下一节课」，点击打开今日页；不引 Glance/Compose，
用 `RemoteViews` + `AppWidgetProvider`。

建议实现（尚未写任何代码，可调整）：

- 新增 `ui/widget/` 或 `widget/` 包：
  - `WidgetCoursePlanner.kt`：**纯逻辑，可 JVM 单测**。入参 `AppState` + `LocalDateTime`，
    用 `ScheduleLogic.currentOccurrences` / `nextOccurrences`（签名见
    `android/app/src/main/java/com/gzus/courseassistant/data/model/ScheduleLogic.kt`），
    返回 `WidgetPlan(kind, course, period, dayOffset)`；`kind` 建议
    `CURRENT / NEXT_TODAY / NEXT_UPCOMING / EMPTY`；今天没课时往后看 7 天找最近一天有课的。
  - `NextCourseWidgetProvider.kt`：`AppWidgetProvider`，`onUpdate`/`onEnabled` 时
    `AppWidgetManager.updateAppWidget`；`refresh(context)` 静态方法供同步/提醒后调用。
    PendingIntent → `MainActivity` + `EXTRA_OPEN_TODAY`。
- `res/layout/widget_next_course.xml`（LinearLayout + 3 个 TextView：头标 / 课名 / 时间地点）、
  `res/drawable/bg_widget.xml`（圆角背景，注意 `values-night` 深色）、
  `res/xml/widget_next_course_info.xml`（`minWidth/minHeight`、`targetCellWidth/Height`、
  `updatePeriodMillis=1800000`、`initialLayout`、`resizeMode`、`description`）。
- `AndroidManifest.xml` 增加 `<receiver android:name=".widget.NextCourseWidgetProvider"
  android:exported="false">` + `APPWIDGET_UPDATE` intent-filter + `android.appwidget.provider` meta-data。
- 刷新时机：`onUpdate`、`onEnabled`；`CourseReminderWorker`（每 15 分钟）结束前调用
  `NextCourseWidgetProvider.refresh(context)`；同步完成后也刷新（`SyncWorker` 或
  `MainActivity.maybeRefresh` 成功回调）。
- strings 用 `widget_*` 前缀；新增 `WidgetCoursePlannerTest.kt`。
- 验收：三项 gradle 任务 + release 安装后长按桌面添加小组件截图验证；版本升到
  `versionCode 8` / `versionName "1.3.4"`。

### M3 空闲教室查询（目标 v1.3.5）

- 入口已确证：`qa/jwxt-index-menu.html` 原文
  `clickMenu('N2155','/cdjy/cdjy_cxKxcdlb.html','查询空闲教室','null')` → 页面
  `https://jwxt.gzus.edu.cn/jwglxt/cdjy/cdjy_cxKxcdlb.html`，`gnmkdm=N2155`。
- **查询 POST 表单字段未知，禁止按猜测写死**。两步走：
  1. 让用户先在网页端或 App 重新登录一次教务（当前 Cookie 已过期），然后写取证脚本
     `qa/inspect-empty-classroom.mjs`（照抄 `qa/inspect-jwxt-timetable.mjs` 的 Playwright
     抓包结构：`page.goto` → 监听 `request`/`response` → 落盘 `qa/`）或 Python 版
     `qa/probe_empty_classroom.py`（复用 `jwglxt.py` 的 `JwglxtClient.import_cookies`）。
     拿到真实 POST 路径 + form 字段后再实现解析。
  2. 网络层照现有风格：`JwxtClient.fetchEmptyClassrooms(...)`、
     `JwxtParsers.parseEmptyClassrooms(payload)`、`Models.kt` 加 `Classroom(...)`；
     `AppRepository` 不写 state.json、内存缓存 5 分钟（不打扰教务）。
- 零风险降级（**没抓到包也必须交付**）：「我的空闲时段」——纯本地用现有课表推算
  本周每天的空课区间，放在同一页面里展示。
- 入口放 `MoreFragment`（不动底部 tab）；新增 `ui/classroom/EmptyClassroomActivity.kt`
  + `activity_empty_classroom.xml` + `item_classroom.xml`。
- 注意 `data/session.cookies.json` 已是过期状态，网页服务也未启动（8765 端口）。

## 5. 明确不要做的事

- 不要改 Flask 网页端（`app.py` / `jwglxt.py` / `static/` / `templates/`）——本轮只做 Android。
- 不要引入第三方图表库、Compose 或 Glance。
- 不要为了「看起来对齐」而删掉用户已有缓存数据或改存储格式（`AppState.schemaVersion` 可不变，
  新增字段都有默认值）。
- 不要自行发起「重新登录」流程：账号密码与验证码只能由用户输入。
- 不要请求 sandbox 提权（本会话 approval 已关闭，提权会被直接拒绝）。

## 6. 关键文件锚点

| 用途 | 文件 |
| --- | --- |
| 数据模型 | `android/app/src/main/java/com/gzus/courseassistant/data/model/Models.kt` |
| 课表/今日逻辑 | `.../data/model/ScheduleLogic.kt` |
| 课程归一 | `.../data/model/CourseNormalizer.kt` |
| 教务网络层 | `.../data/network/JwxtClient.kt`（端点集中在此） |
| 解析层 | `.../data/network/JwxtParsers.kt` |
| 仓储/状态 | `.../data/sync/AppRepository.kt`（`state: StateFlow<AppState>`） |
| 后台任务注册 | `.../CourseApplication.kt`（`scheduleWorkers()`） |
| 通知 | `.../notifications/NotificationHelper.kt`、`CourseReminderWorker.kt`、`ExamReminderWorker.kt` |
| 学业页 | `.../ui/academics/{AcademicsFragment,GradeAdapter,ExamAdapter,TrendChartView,ExamDetailDialog}.kt` |
| 今日页 | `.../ui/today/TodayFragment.kt` |
| 更多页 | `.../ui/more/MoreFragment.kt` |
| 字符串 | `android/app/src/main/res/values/strings.xml`（约 215 条；只改 values/ 与 values-night/，无 values-zh） |
