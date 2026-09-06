# LuMing Tray

Android 通知栏 API 用量托盘。项目不依赖 Android Studio，本仓库通过 GitHub Actions 自动编译 APK。

## M0.4

- 常驻通知显示余额、今日消费、请求数和 Token
- 展开通知显示输入 / 输出 Token、RPM / TPM、平均响应时间、最后更新时间
- Quick Settings 磁贴点按刷新
- 网页登录后读取站点 `localStorage` 中的网页登录令牌，不再只依赖 Cookie
- 适配 Sub2API 风格仪表盘：`/api/v1/auth/me` + `/api/v1/usage/dashboard/stats`
- 保存 refresh token，并在后台刷新时自动续期 access token
- 如果站点接口再次定制，WebView 可直接读取已经渲染出来的仪表盘文字作为兜底；不做截图 OCR
- API Key / 手动 Access Token / One API-New API 探测继续作为兼容兜底
- Android 15/16 状态栏安全区适配
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
5. 看到仪表盘后点“完成登录并读取数据”。
6. App 获取站点网页登录 access token / refresh token，并直接读取仪表盘统计。
7. 后续 WorkManager 每 30 分钟刷新；access token 临近失效时自动续期。

网页登录账号和密码不会由 App 读取或保存。API Key、网页登录令牌等凭据只写入 Android 应用私有 `SharedPreferences`；应用关闭 Android 备份，不会把凭据提交到 GitHub。

## 为什么 M0.4 改了认证方式

M0.3 已验证 WebView 能正常登录并显示 LuMing 仪表盘，但只复制 Cookie 后访问 One API / New API 风格接口会失败。LuMing 当前界面与 Sub2API 风格前端高度一致，该前端使用 `localStorage.auth_token` 作为 Bearer token，并从 `/api/v1/usage/dashboard/stats` 读取今日请求、Token、消费、RPM / TPM 和平均响应，从 `/api/v1/auth/me` 读取余额。因此 M0.4 直接复用网页已经取得的站点令牌。

## 编译

推送到 `main` 后 GitHub Actions 自动执行：

```text
Android SDK 35
JDK 17
Gradle 8.9
assembleDebug
```

构建完成后在 **Actions → 对应任务 → Artifacts** 下载 APK。
