# 验证记录 · 2026-10-14

0.1.5 / versionCode 6 / dev.nfish.biliaudio / Android 8.0+，仅 arm64-v8a。

## 已验证

- Release 构建产物生成，关闭调试，启用 R8 代码优化、混淆及资源裁剪。
- 48 项 Release 单元测试通过，0 失败、0 错误、0 跳过。测试记录时间为 UTC 05:55。
- Release Lint：0 错误、15 非阻断警告。
- APK：2,282,617 字节，约 2.28 MB；相比 0.1.4 减少约 89.3%。
- ZIP CRC、apksigner v2 签名及 zipalign -c -P 16 4 检查通过。
- APK 中仅有 arm64-v8a 原生库，application-debuggable 标记不存在。
- 沿用原个人测试证书，可以覆盖安装 0.1.1～0.1.4。

APK SHA256：`7b0d54c3731f0092982fa4ca852fff3dea88eb413f4df6331036c04257087336`。
证书 SHA256：`4e860e2cede57f8d6d9991509787e71d50be2e896408cf929ccab0bf4d0dea2c`。

## Actions 与 Git

工作流 YAML、Python 语法及全部 shell 段通过静态检查。main 推送、PR、手动触发
运行测试、Lint、arm64 构建与 APK 校验；版本标签发布要求签名 Secret，校验版本号
与 SHA256 后创建 GitHub Release。动作固定到已核对的官方提交 SHA。

签名密钥从 Secrets 注入；无密钥时普通构建输出 -unsigned 文件，版本发布会失败。
无密钥 Release 路径此前已在同一签名配置下本地验证，本次不重复执行。
GitHub App 已安装到 douxc，仓库授权已恢复。工作流将随源码提交，
真实 CI 执行状态以 https://github.com/douxc/bili-player/actions 为准。
Git 提交排除私钥、APK、mapping 和构建缓存；私有源码 ZIP 保留原密钥和对应 mapping。

## 未验证

未连接真机或模拟器。R8 优化后的实际搜索、扫码登录、图片加载、音频解码、
分 P 连播和后台服务需真机验收。本次未重新验证真实 B站接口/CDN。
