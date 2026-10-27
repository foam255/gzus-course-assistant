# gzus-course-assistant

课表轻助：广州软件学院课表、成绩、考试、校历与课程提醒 Android 应用。

项目同时保留一个本地网页课程表服务，用于协议验证和桌面端查看；手机端完全
脱离电脑运行，通过 CAS 登录后直接访问学校教务系统。

## 项目组成

- `android/`：原生 Kotlin + XML 安卓应用，支持课表、今日课程、成绩、考试、
  校历、提醒、首周设置和课表图片导出。
- `app.py`、`jwglxt.py`：本地 Flask 网页服务和 CAS/JWXT 协议实现。
- `tests/`：使用模拟教务服务的协议与网页测试。

Android 构建、安装和隐私说明见 [android/README.md](android/README.md)。

## 本地网页服务

在 PowerShell 中运行：

```powershell
.\run.ps1
```

首次运行会创建 `.venv` 并安装依赖。浏览器打开：

```text
http://127.0.0.1:8765
```

首次使用时在页面中输入学校账号、密码和页面显示的算术验证码。密码只提交
给 `cas.gzus.edu.cn`，不会写入本地文件；勾选“保留登录会话”时只会保存
认证与教务 Cookie，以便后续同步。

## 数据位置

- `data/schedule.json`：最近一次同步的课程数据
- `data/session.cookies.json`：可选的本地教务会话
- Android 数据：应用私有目录中的 Android Keystore 加密文件

这些文件包含个人数据，已加入 `.gitignore`。

## 实现说明

- 登录时复现 CAS 前端的原始 RSA 加密和票据回调流程。
- 验证码由用户手工填写，不进行自动破解。
- 按教务真实周次字段过滤课程，并合并重复教学班记录。
- 安卓课表使用大节时间轴，只展示周一至周五。
- 网页支持周次切换、桌面周视图、移动端日视图和 30 分钟自动刷新。
- 登录失败不会自动重试，避免触发教务系统的连续失败锁定。

## 验证

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

Android 验证命令见 [android/README.md](android/README.md)。测试使用本地模拟
教务服务，不需要真实学号和密码。

## 范围

仅用于本人账号已有权限内的课程查询。不要批量扫描、共享账号或高频请求
学校系统。本项目不是学校官方客户端。

## 许可证

项目使用 MIT License，见 [LICENSE](LICENSE)。
