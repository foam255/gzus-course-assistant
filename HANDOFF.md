# 交接文档（给 WorkBuddy AI）

> 使用者：把这台机器上的「课表轻助」安卓项目交给另一个 agent 软件继续做。
> 接手方：**先完整读本文件，再按「待办」顺序执行**；所有路径均为本机绝对路径或
> 相对仓库根 `G:\weixingkebiao` 的路径。

## 0. 一句话现状

M1（成绩/GPA 分析 + 考试倒计时提醒，v1.3.3）、M2（桌面「下一节课」小组件，v1.3.4）、
M3（空闲教室查询，v1.3.5）代码全部写完并分别提交（`f1c3632`、`8358323`、本次）；
`testDebugUnitTest` 70 个用例、`lintDebug`、`assembleRelease` 全绿。
**v1.3.5 的 release 包已构建但还没装机走查**（验证时手机 USB 掉线），
需要插上手机后复验「空闲教室」页与登录页验证码尺寸。

## 1. 接手前必读的环境事实

| 项 | 值 |
| --- | --- |
| 仓库根 | `G:\weixingkebiao` |
| Android 工程 | `G:\weixingkebiao\android` |
| 包名 / 应用 | `com.gzus.courseassistant`（课表轻助） |
| 当前版本 | `versionCode 8`、`versionName "1.3.4"`（`android/app/build.gradle.kts`） |
| JDK | 17（`JAVA_HOME=E:\java\jdk-17.0.2`，已是用户级环境变量） |
| Android SDK | `C:\Users\255\AppData\Local\Android\Sdk`（`platforms\android-36`、`build-tools\36.0.0`、`platform-tools` 37.0.1）— 曾整个被删，已重新安装，**不要再删** |
| adb | `C:\Users\255\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| 真机 | `xgnv65kzb6cix4pr`（Redmi M2004J7AC，product:atom，Android 12 / MIUI 13），已授权、在线 |
| 发布签名 | `android/keystore/release.jks` + `android/keystore.properties`（均 gitignore） |
| Gradle | wrapper 8.13，AGP 8.13.2，Kotlin 2.2.21，无 version catalog |
| 构建命令 | `export JAVA_HOME=/e/java/jdk-17.0.2; cd /g/weixingkebiao/android && ./gradlew.bat testDebugUnitTest --console=plain` |

**坑（务必先看）**

1. 本机 `pwsh` 工具实际由 **Windows PowerShell 5.1** 承载：写 `pwsh x.ps1` 会 CommandNotFound，
   直接 `& script.ps1`；`Get-Content` 会把 UTF-8 中文读成乱码，**判断文件内容一律用 read 工具**。
2. 本会话用 **bash 工具**跑 adb 更省事：`adb exec-out screencap -p > x.png` 在 bash 下是二进制安全的；
   但 **不能从 bash 调 `cmd.exe`**（会被安全策略拦截）。`adb shell ... /sdcard/...` 这类路径
   必须先 `export MSYS_NO_PATHCONV=1`，否则会被 MSYS 改写成 `E:/workbuddy/...`。
3. **debug APK 不能覆盖安装在 release 包上**（签名不同会 INSTALL_FAILED_UPDATE_INCOMPATIBLE，
   卸载会丢用户数据）。真机验证一律 `./gradlew.bat assembleRelease` 后
   `adb install -r android/app/build/outputs/apk/release/app-release.apk`。
4. `lint { abortOnError = true }`：lint 有 error 就会构建失败；`strings.xml` 里 name 重复会直接
   `Resource merger: Found item ... more than one time` 失败。
5. Material `AlertDialog` 里 `setMessage` 与 `setSingleChoiceItems` **不能同时用**。
6. 本项目**不引第三方图表库、不引 Compose/Glance**；折线图自绘 `Canvas`，小组件用 `RemoteViews`。
7. 单元测试全部平铺在 `android/app/src/test/java/com/gzus/courseassistant/`，
   `package com.gzus.courseassistant`，JUnit4。
8. **MIUI 桌面不允许第三方应用一键添加小组件**：即使声明并授予了
   `com.miui.home.launcher.permission.INSTALL_WIDGET`，小米桌面仍打
   `E AddItemActivity-PinShortcutRequestUtils: add widget failed, ... has no permission`
   并立即关闭确认页。`requestPinAppWidget` 在 MIUI 上必然失败，UI 已降级为提示手动添加。
9. MIUI 自带「添加小部件」面板依赖小米服务器，本机网络下恒显示「网络异常」，
   **无法用它做真机小组件验收**；当时是临时加了一个 `WidgetPreviewActivity`
   把 `RemoteViews` 用 `apply()` 渲染出来截图，验收后已删除（不要把它留在代码里）。
10. 走查点击用 `adb shell input tap`，坐标要用 `uiautomator dump` 里的真实 bounds，
    别按缩放后的截图目测（`input tap` 用的是 1080×2400 物理像素）。
11. **教务会话空闲超时只有几分钟**：网页端登录后要立刻抓包，否则 `data/session.cookies.json`
    很快失效；退出登录时该文件会被直接删掉。用 `qa/probe_empty_classroom.py --wait 900`
    等会话出现最省事。
12. `:app:minifyReleaseWithR8` 偶发无故失败（重跑即成功），先 `> /tmp/build.log 2>&1`
    再 `grep -a`，否则 grep 会把输出当二进制。

## 2. 已完成的工作

### 2.1 M1：成绩/GPA 分析 + 考试倒计时（v1.3.3，提交 `f1c3632`）

- `data/model/GradeStats.kt`：`TermStat` / `GradeDistribution` / `TypeStat` / `GradeStats`
  + `GradeStatsCalculator.compute`（绩点与加权分只统计可解析且 credit>0 的课程）。
- `data/model/ExamSchedule.kt`：`Upcoming`、`parseStart`、`upcoming`、`next`、`daysUntilNext`、
  `parseDate`（中文日期按学年推断年份）、`parseTime`。
- `notifications/ExamReminderPlanner.kt`（纯逻辑，`DAY_ONLY_LEAD = -1` 表示只能按天提醒）、
  `notifications/ExamReminderWorker.kt`（每小时）。
- `ui/academics/TrendChartView.kt`（自绘折线，首尾标签已内缩不裁切）、`ExamDetailDialog.kt`。
- 学业页 ToggleGroup 扩为「成绩|分析|考试」；今日页新增考试倒计时卡；更多页新增考试提醒设置。
- `AppSettings` 新增 `examReminderEnabled` / `examReminderLeadHours` / `examRemindedKeys`。

### 2.2 M2：桌面小组件（v1.3.4，提交 `8358323`）

新增：

- `widget/WidgetCoursePlanner.kt`：`WidgetKind{CURRENT,NEXT_TODAY,NEXT_UPCOMING,EMPTY}`、
  `WidgetPlan(kind, course, period, start, end, dayOffset, scheduleAvailable)`；
  `plan(state, now)` 依次判断「正在上 → 今天下一节 → 未来 7 天最近一节 → 空」。
- `widget/NextCourseWidgetProvider.kt`：`AppWidgetProvider` + `RemoteViews`；
  `refresh(context)` 供后台任务调用；点击 `PendingIntent` 带 `EXTRA_OPEN_TODAY` 打开今日页。
  文案：标签（正在上课 / 下一节课 / 下一节课·明天）、课名、`17:00-18:20 · 广州校区 T105`。
  地点拼接已处理「room 本身已含 campus」的重复问题。
- `res/layout/widget_next_course.xml`（3 个 TextView）、`res/drawable/bg_widget.xml`
  （圆角 18dp，用 `@color/surface`/`@color/line`，深色模式自动跟随 values-night）、
  `res/xml/widget_next_course_info.xml`（180×110dp、`updatePeriodMillis=1800000`、resizeMode 双向）。
- `AndroidManifest.xml` 注册 receiver（`exported="false"`）+ 声明
  `com.miui.home.launcher.permission.INSTALL_WIDGET`（MIUI 用，非 MIUI 设备忽略）。
- `WidgetCoursePlannerTest.kt`（7 个用例）。

修改：

- 刷新时机：`CourseReminderWorker`（每 15 分钟，`try/finally` 保证早退也刷新）、
  `SyncWorker`（同步后）、`MainActivity.onResume` 与 `maybeRefresh` 成功后、`TodayFragment` 手动同步后。
- `MoreFragment` + `fragment_more.xml`：新增「添加桌面小组件」按钮，调
  `AppWidgetManager.requestPinAppWidget`，无论成功与否都提示「若桌面未弹出确认，请长按桌面空白处手动添加」。
- `strings.xml` 新增 `widget_*`、`more_add_widget`、`widget_pin_*`（含 `widget_weekdays` 数组）。

### 2.3 M3：空闲教室（v1.3.5）

**接口已抓包确认**（`qa/empty-classroom-page.html`、`qa/kxcdlb.js` 为证据）：

```
POST /jwglxt/cdjy/cdjy_cxKxcdlb.html?doType=query&gnmkdm=N2155
form: xnm=2026  xqm=3  xqh_id=<校区id，空=全部>
      cdlb_id= cdejlb_id= cdmc= cd_id= lh= qszws= jszws=   (空=不过滤)
      jyfs=0                       # 按周次借用
      zcd=<周次位掩码>              # 第 9 周 = 1<<8 = 256
      xqj=1,2,3,4,5                # 星期列表
      jcd=<节次位掩码>              # 第 1-2 节 = 1|2 = 3
      queryModel.showCount=500     # 不传则每页 10 条，且 page/rows 无效
返回: {"totalResult":"194","items":[{cd_id,cdbh,cdmc,cdjc,jxlmc,cdlbmc,xqmc,zws,...}]}
```

已验证筛选真实生效：周一 1-2 节 194 间 → 同一周 1-12 节 115 间 → 全部 20 周 165 间；
`xqh_id` 空串 = 广州 196 + 江门 160 共 356 间。

新增：

- `data/model/Models.kt`：`Classroom(id, code, name, building, category, campus, seats)`。
- `data/network/JwxtParsers.kt`：`parseClassrooms(payload)`，按 校区→教学楼→教室号 排序。
- `data/network/JwxtClient.kt`：`fetchEmptyClassrooms(term, week, weekdays, periods)`、
  `weekMask` / `periodMask`（位掩码），新增 `lastCampusId`（`fetchSchedule` 时记录）。
- `data/sync/AppRepository.kt`：`emptyClassrooms(...)`，`ConcurrentHashMap` 内存缓存 5 分钟，
  不写 state.json。
- `ui/classroom/EmptyClassroomActivity.kt` + `ClassroomAdapter.kt` +
  `res/layout/activity_empty_classroom.xml` + `item_classroom.xml`：
  顶部「星期」「节次」两个 Spinner（默认定位到今天 / 当前节次），自动查询；
  列表行显示 教室号 + 座位数 + 场地名称 + 校区·教学楼·类别；
  空态「该时段没有空闲教室」，会话失效时提示重新登录。
- `MoreFragment` + `fragment_more.xml`：校历下方新增「查询空闲教室」入口。
- `ClassroomParsingTest.kt`（4 个用例）。
- `qa/probe_empty_classroom.py`：取证脚本，支持 `--wait <秒>` 等待有效会话后自动抓包。

### 2.4 已修的 bug（不要回退）

- `ExamSchedule.ParsedStart` 误用 `.first/.second` → `.time/.hasTime`。
- `TrendChartView` 引用 `R.string.gpa_trend_empty` → `analysis_gpa_trend_empty`。
- `strings.xml` 里 `exam_notification_lead_day` / `_hours` 重复定义已删重。
- 「课程性质」→ 实际数据是 `ksxz`，已改「考试性质」。
- 考试提醒弹窗 `setMessage` + `setSingleChoiceItems` 冲突 → 去掉 `setMessage`。
- 小组件地点重复：`room` 已含校区前缀，改为 `room.contains(campus)` 时不再拼接。
- 登录页验证码「太小、没撑满框」：原图 100×25（4:1），卡片却是 132dp×56dp 且用
  `centerInside`，上下留白 → 卡片改 160dp×40dp（4:1）+ `fitXY` + padding 2dp，
  行内改 `center_vertical`。

## 3. 验证记录

| 检查 | 结果 |
| --- | --- |
| `./gradlew.bat testDebugUnitTest` | ✅ **70 个用例全过**（GradeStats 8 + ExamSchedule 10 + ExamReminderPlanner 7 + WidgetCoursePlanner 7 + ClassroomParsing 4 + 原有 34） |
| `./gradlew.bat lintDebug` | ✅ BUILD SUCCESSFUL（`abortOnError=true`，零 error） |
| `./gradlew.bat assembleRelease` | ✅ `app-release.apk` 2.1 MB，release 签名（v1.3.5 / versionCode 9） |
| 真机安装 | ⚠️ v1.3.4 已装；**v1.3.5 未装机**——构建时手机 USB 掉线，`adb devices` 为空 |
| M1 两处修复复验 | ✅ 趋势图首尾标签 `2.20 / 24-25-2`、`2.82 / 25-26-2` 完整；考试提醒弹窗单选列表正常 |
| 小组件渲染 | ✅ 临时预览页实机渲染：`下一节课 / 大学日语IV / 17:00-18:20 · 广州校区 T105` |
| 小组件上桌面 | ⚠️ **未能完成**：MIUI 面板要联网（恒「网络异常」），`requestPinAppWidget` 被 MIUI 拒绝（见坑 8）。需在原生桌面或换桌面启动器再验 |
| 考试倒计时/提醒 | ⚠️ 仍需真实考试数据；本机教务会话已过期（更多页「未登录」），要用户重新登录后同步 |

走查截图在 `G:\weixingkebiao\qa\dev\`（`01-main.png` … `31-more-toast.png`，
含 `29/30-widget-render*.png` 小组件渲染证据），该目录被 `.gitignore` 忽略。

## 4. 待办

### 1. 装机复验 v1.3.5（必须先做）

```powershell
$adb='C:\Users\255\AppData\Local\Android\Sdk\platform-tools\adb.exe'
cd G:\weixingkebiao\android; .\gradlew.bat assembleRelease --console=plain
& $adb install -r app\build\outputs\apk\release\app-release.apk
& $adb shell am start -W -n com.gzus.courseassistant/.ui.main.MainActivity
```

1. 「更多 → 查询空闲教室」：默认定位到今天与当前节次，列表显示 教室号/座位数/场地名称/
   校区·教学楼·类别；切换星期或节次会重新查询；标题显示「第 N 周 周X 第a-b节 · 共 N 间空闲」。
2. 「更多 → 更新登录会话」：确认验证码图片铺满整张卡片、不再上下留白。

### 2. 发布（用户手动）

- 打 `v1.3.5` tag 并上传 `app-release.apk` 到 GitHub Releases（README 里有流程）。

### 3. 可选后续

- 空闲教室支持按校区 / 教学楼 / 座位数筛选（接口已支持 `xqh_id` / `lh` / `qszws` / `jszws`）。
- 桌面小组件在原生桌面（非 MIUI）上做一次上桌面验收。

## 5. 明确不要做的事

- 不要改 Flask 网页端（`app.py` / `jwglxt.py` / `static/` / `templates/`）——本轮只做 Android。
- 不要引入第三方图表库、Compose 或 Glance。
- 不要为了「看起来对齐」而删掉用户已有缓存数据或改存储格式。
- 不要自行发起「重新登录」流程：账号密码与验证码只能由用户输入。
- 不要请求 sandbox 提权（本会话 approval 已关闭，提权会被直接拒绝）。
- 不要把临时验收用的 `WidgetPreviewActivity` 留在代码里。

## 6. 关键文件锚点

| 用途 | 文件 |
| --- | --- |
| 数据模型 | `android/app/src/main/java/com/gzus/courseassistant/data/model/Models.kt` |
| 课表/今日逻辑 | `.../data/model/ScheduleLogic.kt` |
| 课程归一 | `.../data/model/CourseNormalizer.kt` |
| 成绩统计 | `.../data/model/GradeStats.kt` |
| 考试时间解析 | `.../data/model/ExamSchedule.kt` |
| 教务网络层 | `.../data/network/JwxtClient.kt`（端点集中在此） |
| 解析层 | `.../data/network/JwxtParsers.kt` |
| 仓储/状态 | `.../data/sync/AppRepository.kt`（`state: StateFlow<AppState>`） |
| 后台任务注册 | `.../CourseApplication.kt`（`scheduleWorkers()`） |
| 通知 | `.../notifications/NotificationHelper.kt`、`CourseReminderWorker.kt`、`ExamReminderWorker.kt` |
| 小组件 | `.../widget/{WidgetCoursePlanner,NextCourseWidgetProvider}.kt` + `res/xml/widget_next_course_info.xml` |
| 空闲教室 | `.../ui/classroom/{EmptyClassroomActivity,ClassroomAdapter}.kt` + `.../data/network/JwxtClient.kt#fetchEmptyClassrooms` |
| 取证脚本 | `qa/probe_empty_classroom.py`（`--wait 900` 可等待登录后自动抓包） |
| 学业页 | `.../ui/academics/{AcademicsFragment,GradeAdapter,ExamAdapter,TrendChartView,ExamDetailDialog}.kt` |
| 今日页 | `.../ui/today/TodayFragment.kt` |
| 更多页 | `.../ui/more/MoreFragment.kt` |
| 字符串 | `android/app/src/main/res/values/strings.xml`（约 230 条；只改 values/ 与 values-night/） |
