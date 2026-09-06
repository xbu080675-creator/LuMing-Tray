# LuMing Tray

Android 通知栏 API 用量托盘。项目不依赖 Android Studio，本仓库通过 GitHub Actions 自动编译 APK。

## M0.2

- 常驻通知显示余额、今日消费、请求数和 Token
- 展开通知显示输入 / 输出 Token、平均响应时间、最后更新时间
- Quick Settings 磁贴点按刷新
- App 内本地保存 API 基地址、API Key、控制台 Access Token
- 自动探测常见 One API / New API 统计接口
- 30 分钟后台刷新
- GitHub Actions 自动生成 debug APK

默认 API 基地址：

```text
https://lmyanyu.com/v1
```

### 凭据说明

凭据只写入 Android 应用的私有 `SharedPreferences`，不会提交到 GitHub，应用也已关闭 Android 备份。不要把 API Key 或 Access Token 写进仓库。

在兼容的 One API / New API 部署上：

- `API Key`（通常是 `sk-...`）可用于兼容 billing 接口，主要作为余额兜底。
- `控制台 Access Token` 用于 `/api/user/self`、`/api/user/dashboard` 和 `/api/log/self/`，可以拿到更完整的今日统计。

如果站点做了自定义接口，App 会保留上一次成功数据，并在“接口状态”里显示探测结果。

## 编译

推送到 `main` 后，GitHub Actions 会自动执行：

```text
Android SDK 35
JDK 17
Gradle 8.9
assembleDebug
```

构建完成后在 **Actions → 对应任务 → Artifacts** 下载 APK。
