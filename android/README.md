# 课表轻助 Android

原生 Kotlin + XML 的广州软件学院课表应用。手机直接完成 CAS 登录、教务同步、
加密缓存和离线展示，不依赖当前目录下的 Python 服务。

## 首版功能

- CAS 算术验证码登录，登录后通过 SSO 进入 JWXT。
- 学期切换、周次切换、单双周过滤和当前周定位。
- 按教务 `zcd` 周次字段精确过滤，只在课程实际开课周显示。
- 合并同一课程、教师、教室、时间和周次的重复教学班记录。
- 今日课程自动显示当前周，区分待上课、正在上、已上完和空课状态。
- 今日页支持本周一至周五课程切换预览，周末显示无课。
- 课表只显示所选周实际有课课程，单周与双周课程不会同时出现。
- 课表按 `1-2、3-4、5-6…` 大节展示，整周同屏，不再逐小节画时间轴。
- 时间轴固定补齐至 `15-16` 节（20:30-21:50），无课时也保留该行。
- 课表仅显示周一至周五，不再绘制周末列。
- 课程卡使用实色块、居中多行课名和底部教室，版式参考“课不要”小程序。
- 当前学期与周次可导出为完整 PNG 图片并保存到系统相册。
- 每学期可手动设置第一周星期一，未设置时按内置日期或系统日期自动计算周次。
- 成绩、考试、公开校历与离线缓存。
- 每 24 小时尝试同步一次，每 15 分钟检查课程提醒。
- 课程提醒支持关闭或提前 5、10、15、30 分钟。
- 会话失效后保留旧数据并标记为离线，重新登录即可恢复同步。

## 环境

- JDK 17
- Android SDK API 36
- Build Tools 36.x
- Gradle Wrapper 8.13（仓库已包含）

包名为 `com.gzus.courseassistant`，最低 Android 8.0（API 26）。

## 构建与安装

在 `android/` 目录执行：

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug assembleRelease
```

产物：

- 调试包：`app/build/outputs/apk/debug/app-debug.apk`
- 发布包：`app/build/outputs/apk/release/app-release.apk`

连接已开启 USB 调试的手机后安装：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk
```

## 首次使用

1. 确认手机当前网络可以直接访问 `cas.gzus.edu.cn` 与 `jwxt.gzus.edu.cn`。
2. 输入学号、密码和页面中算术验证码的答案。
3. 登录成功后等待首次同步，课程会写入本机加密缓存。
4. Android 13 及以上会请求通知权限，用于上课提醒。

校园网限制、VPN、账号额外验证或学校系统改版需要在手机网络环境或后续版本中
单独处理。验证码始终由用户手动输入。

## 本地数据与隐私

- 密码只在登录过程中驻留内存，不写日志、不落盘。
- Cookie 和课程、成绩、考试等 JSON 缓存使用 Android Keystore AES-GCM 加密。
- 共享偏好只保存提醒时间、主题等非账号数据。
- 应用关闭了系统备份和设备迁移中的敏感文件传输。
- 校历图片是公开只读数据，保存在应用私有目录。
- 清空全部数据会删除会话、课程、成绩、考试、校历图片和提醒设置。

Android 端不会读取项目根目录中的 `data/schedule.json` 或 Cookie，必须在手机
上重新登录。

## 签名

本机发布签名位于 `keystore/release.jks`，配置在已忽略的
`keystore.properties` 中。请妥善备份这两个文件；丢失签名后无法覆盖安装同一
包名的新版本，只能卸载重装。新开发机可参考
`keystore.properties.example` 提供自己的本地签名配置。

未提供 `keystore.properties` 时，Release 构建会回退到调试签名，仍可侧载，
但不适合作为长期升级包发布。

## 测试

不需要手机的 JVM 测试与静态检查：

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug
```

编译设备 UI 测试：

```powershell
.\gradlew.bat --no-daemon assembleDebugAndroidTest
```

连接手机或模拟器后运行：

```powershell
.\gradlew.bat --no-daemon connectedDebugAndroidTest
```

真实账号验收仍需在能访问学校系统的设备上完成：首次登录、23 条左右真实课程、
17 个节次、周次定位、成绩、考试、杀进程后离线打开、会话过期保留缓存和课堂
提醒。

## 已知边界

- 不自动识别验证码，不保存账号密码。
- 不包含课程查询、教师查询、书单、优学榜、云同步和账号共享。
- WorkManager 的提醒时间受系统调度影响，校园省电策略严格时可能延迟。
- 学校接口字段变化时，解析器需要同步更新。
