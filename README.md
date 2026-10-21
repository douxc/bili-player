# 听见

Android B站音频播放器，个人使用。Kotlin · Jetpack Compose · Media3，无后端。

## 功能

- 关键词搜索，支持 BV 号和包含 BV 号的视频链接。
- 在线播放独立音频，支持后台、锁屏和通知栏控制。
- 多分 P 选择、上一 P / 下一 P、默认顺序续播；锁屏和通知栏支持切 P。
- 暂停、拖动进度、单曲循环；独立音乐音量支持 -60～0 dB 连续调整。
- 最近 3 条视频 / 分 P 历史，保存播放进度，点击续播；搜索结果为空时展示。
- 可选 B站二维码登录，凭据加密保存在本机。

普通选曲和手动切 P 从头播放；历史续播恢复对应 P 的进度，已播完的 P 从头播放。
单曲循环优先于正常续播；播放失败自动重试或跳过后续 P，尝试至最后一 P 后停止。

## 安装

支持 Android 8.0+、arm64-v8a。正式包启用代码与资源裁剪。

从 [Actions](https://github.com/douxc/bili-player/actions) 下载构建产物，
或从 [Releases](https://github.com/douxc/bili-player/releases) 下载已发布 APK。
首次从旧测试签名切换到正式签名需卸载旧版；之后使用同一密钥覆盖升级。

## 构建

需要 JDK 17、Android SDK 35、Build Tools 35.0.0。

```bash
./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

APK 位于 `app/build/outputs/apk/release/`。
本地签名通过 `SIGNING_KEYSTORE_PATH`、`SIGNING_STORE_PASSWORD`、
`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD` 环境变量配置；无密钥时生成未签名包。

## 自动打包

main 推送和手动运行构建 arm64 Release；PR 运行测试与未签名构建。
版本标签（如 `v0.1.6`）需与 `versionName` 一致，构建成功后发布 GitHub Release。
APK、校验和、混淆映射及验证报告保留 30 天。

签名配置位于仓库 **Environment：`Release`**：

| 类型 | 名称 | 内容 |
| --- | --- | --- |
| Secret | `SIGNING_KEYSTORE_BASE64` | 签名密钥文件的 Base64 |
| Secret | `SIGNING_STORE_PASSWORD` | 密钥库密码 |
| Secret | `SIGNING_KEY_PASSWORD` | 私钥密码 |
| Variable | `SIGNING_KEY_ALIAS` | `tingjian` |

密钥与密码不提交到仓库。别名也兼容同名 Secret。

## 限制

- 依赖 B站网页接口及内容权限，部分内容需要登录或可能无法播放。
- 无歌单、下载、歌词、跨视频连播和 `b23.tv` 短链接解析。
- 分 P 切换需重新解析音源，可能短暂加载。
- 进度每 5 秒保存，暂停、拖动、切换和停止时立即保存；系统强杀可能丢失最近几秒。
- 厂商省电策略可能影响后台播放，需在目标设备验证。
