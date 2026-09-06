# LuMing Tray

Android 通知栏 API 用量托盘。项目不依赖 Android Studio，本仓库通过 GitHub Actions 自动编译 APK。

## M0.3

- 常驻通知显示余额、今日消费、请求数和 Token
- 展开通知显示输入 / 输出 Token、平均响应时间、最后更新时间
- Quick Settings 磁贴点按刷新
- 新增 App 内网页登录：复用 LuMing 控制台登录 Cookie 读取统计
- App 不读取或保存网页登录账号密码
- API Key / 控制台 Access Token 继续保留为兼容接口兜底
- 修复 Android 15/16 edge-to-edge 导致标题与状态栏重叠
- 30 分钟后台刷新
- GitHub Actions 自动生成 debug APK

默认 API 基地址：

```text
https://lmyanyu.com/v1
```

### 推荐使用方式

1. 安装 APK 并打开。
2. 保留默认 API 基地址。
3. 点“网页登录并授权统计”。
4. 在内置网页中正常登录 LuMing。
5. 看到控制台后点“完成登录并刷新”。
6. App 保存本站登录 Cookie，并用它读取 `/api/user/self`、`/api/user/dashboard`、`/api/log/self/` 等统计接口。

Cookie、API Key 和 Access Token 都只写入 Android 应用私有 `SharedPreferences`；应用关闭 Android 备份，不会把凭据提交到 GitHub。

如果登录会话失效，重新走一次“网页登录并授权统计”即可。

## 编译

推送到 `main` 后 GitHub Actions 自动执行：

```text
Android SDK 35
JDK 17
Gradle 8.9
assembleDebug
```

构建完成后在 **Actions → 对应任务 → Artifacts** 下载 APK。
