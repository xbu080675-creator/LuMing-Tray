# LuMing Tray

Android 手机端 LuMing API 用量托盘。

## 当前版本

M0.1：先验证 Android APK、常驻通知和 Quick Settings 快捷磁贴链路。

已完成：

- 原生 Android APK
- 常驻通知栏卡片
- Android 13+ 通知权限申请
- Quick Settings「LuMing API」磁贴
- 开机后恢复托盘通知
- GitHub Actions 自动编译 APK

下一步：

- 接入 LuMing 后台真实统计接口
- 展示余额、今日消费、请求数、输入/输出 Token、平均响应
- 15 分钟后台刷新 + 手动刷新
- 根据实际返回 JSON 做字段映射

## 编译

每次推送到 `main` 都会触发 GitHub Actions。

编译成功后进入：

`Actions -> Build Android APK -> 对应运行 -> Artifacts -> LuMing-Tray-debug`

无需本地 Android Studio。

## 安全

仓库中不要提交 API Key。后续接入认证时，密钥只保存在 App 私有存储中。
