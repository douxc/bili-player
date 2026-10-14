# 听见 · B站在线播放器

仅 Android，个人使用。Kotlin + Compose + Media3，无后端。

## 当前功能

- 关键词搜索视频，分页加载；也可粘贴完整 BV 号或包含 BV 号的视频链接。
- 多分 P 视频先选择分 P，播放独立 AAC 音频流，不拉取视频画面。
- 同一视频的分 P 默认顺序续播，无关闭开关；上一 P / 下一 P 支持手动切换，首尾禁用对应按钮。
- 播放页显示 P 编号，点击标题或编号打开当前视频的分 P 列表，标记当前 P，选择后关闭列表并立即播放。
- 暂停时切 P 也会播放目标 P，从头开始，保留音量和单曲循环模式。
- 单曲循环优先于正常续播；播放失败按顺序尝试后续 P，下一 P 成功后继续沿用循环模式；失败至最后一 P 则停止并可重试。
- 播放 / 暂停、拖动进度、单曲循环、停止播放。
- 搜索结果区在结果为空且未加载时直接显示最近播放；首页未搜索时也显示。有搜索结果时隐藏历史。最多保留 3 条视频 / 分 P 记录，重启后保留，相同项目去重并移到最前，点击从头重播。
- 仅实际开始播放后记录，解析失败或尚未播放的项目不计入；自动续播的 P 计入，暂停继续和单曲循环不重复新增。
- 进度条下方提供独立音乐音量：扬声器静音按钮、-60～0 dB 连续滑块和当前衰减值，无说明文字。
- 音量约 100 毫秒渐变；后台服务持有音量，切歌、暂停继续、熄屏不重置；重启应用恢复上次设置。
- 前台媒体服务：切换 App、锁屏时继续播放，通知栏和系统媒体面板控制。
- 接管音频焦点，耳机断开时暂停。
- 可选 B站二维码登录；登录凭据使用 Android Keystore + AES-GCM 加密保存在本机，可退出清除。
- 搜索结果和播放进度只在内存中保留；没有歌单、搜索历史和离线下载。
- 每个 P 的网络播放错误最多自动重试两次，重新解析地址并尝试备用 CDN；耗尽重试或不可恢复错误会向后跳过，不回到失败 P。

## 安装

随附的 `tingjian-0.1.5-arm64-release.apk` 是关闭调试并启用 R8 代码、资源裁剪的 arm64-v8a Release 包。Android 8.0+ 的 64 位 ARM 设备可安装，可覆盖安装 0.1.1～0.1.4。
0.1.0 的临时测试签名密钥已丢失，从 0.1.0 升级需卸载旧版后安装，B站登录需要重新操作。
此安装包沿用原个人测试证书以覆盖升级。私有源码 ZIP 保留 `signing/tingjian-debug.keystore`，Git 仓库不包含该密钥。可用 SIGNING_KEYSTORE_PATH 指定其他本地密钥路径；无密钥时 Release 生成未签名 APK。
安装后搜索关键词，点击结果；多分 P 的视频在弹窗中选取一项。
点击账号按钮可登录：使用官方哔哩哔哩 App 扫码；单手机可截图后在扫一扫中通过相册识别。
播放时可锁屏或切到其他 App；底部关闭按钮停止播放。

## 本地开发与构建

使用 Android Studio 打开此目录，安装 Android SDK Platform 35 / Build Tools 35.0.0，选择 JDK 17。
Gradle Wrapper 8.11.1 已附带，包含官方分发包 SHA256 校验。首次构建需要下载依赖。

```bash
./gradlew :app:assembleRelease
./gradlew :app:testReleaseUnitTest :app:lintRelease
```

Windows 使用 `gradlew.bat`。APK 输出：`app/build/outputs/apk/release/app-release.apk`。仅包含 arm64-v8a；不包含 Compose 预览或调试工具。
如通过命令行构建，设置 `ANDROID_HOME`，或在 `local.properties` 中填写 `sdk.dir`。

## 代码结构

| 位置 | 职责 |
| --- | --- |
| `data/BiliRepository.kt` | 搜索、视频详情、WBI 签名、音源解析、扫码登录、可取消 HTTP 请求 |
| `data/Wbi.kt` | WBI 参数排序、过滤、编码与 MD5 签名 |
| `data/PlaybackHistory.kt` | 最近 3 个视频 / 分 P 的去重、排序与元数据持久化 |
| `data/SessionStore.kt` | 仅保存账号凭据；禁止 Android 云备份 |
| `playback/PlaybackService.kt` | ExoPlayer、MediaSession、后台播放、地址刷新、备用 CDN 和重试预算 |
| `playback/PartPlayback.kt` | 当前视频分 P 顺序、按需解析、自动续播、失败向后跳过、切换取消与状态 |
| `playback/PartCommands.kt` | 本应用分 P 控制指令及视频元数据传递，不传递登录凭据或 CDN URL |
| `playback/PartDataSource.kt` | 音源解析期间保持缓冲，切换或停止时取消等待 |
| `playback/MusicVolume.kt` | 分贝增益、静音恢复、取消旧渐变并从实际增益平滑调整 |
| `ui/PlayerViewModel.kt` | 搜索状态、分 P 选择、连接播放器和登录轮询 |
| `ui/PlayerScreen.kt` | 单页面搜索列表、底部播放器、分 P 与登录弹窗 |

## 当前限制

- B站接口属于网页使用的非公开稳定接口，可能变动、要求登录或触发安全验证。应用明确显示这些失败，不绕过账号或内容权限。
- 搜索的是 B站视频：标题和 UP 主按原始信息展示，不推断歌曲名或歌手。
- 暂不解析 `b23.tv` 短链接，不提供歌词或跨视频连播；当前视频最后一 P 结束后停止，开启单曲循环时除外。
- 仅在接口返回独立 AAC 音频时播放；无独立音频或无权限的 P 会向后跳过，最后一 P 仍失败时显示原因。
- 分 P 自动续播需要重新解析下一 P 的音源，切换时可能出现短暂加载；不是无缝播放。
- 上一 P / 下一 P 和列表选择位于应用播放页；通知栏及系统媒体面板提供播放 / 暂停等已有系统控制。
- 系统强行停止进程后不会恢复上次进度。正常关闭页面和划掉最近任务时，正在播放的服务继续运行；厂商额外的省电策略可能影响后台播放，需在目标手机验证。
- 此 Release 包沿用已有个人测试签名；更换该密钥后将无法覆盖安装，需卸载旧版。

## 真机验收

1. 搜索一个关键词，点击结果；多分 P 的视频从选中的 P 开始，默认依次播放后续 P，最后一 P 结束后停止。
2. 拖动进度、暂停继续、开启单曲循环；页面与通知栏状态一致。
3. 锁屏、切换 App、划掉最近任务：播放继续，锁屏按钮可暂停。
4. 蓝牙耳机断开时暂停，其他应用获取音频焦点时按系统规则暂停或降低音量。
5. 断网后不无限重试；恢复网络后点击重试继续播放。
6. 连续快速点选不同视频，最终只播放最后选中的视频。
7. 关闭应用再打开，若服务仍在播放可重新连接；进程被停止后不保留搜索结果和播放进度，最近 3 条播放记录保留。
8. 系统媒体音量保留非静音档，在应用内调至 -20 / -40 / -60 dB，声音应逐渐降低；扬声器图标静音后再次点击恢复原值。
9. 调整滑块、切歌、锁屏、重新打开页面，以及停止应用后重新启动，音量值不应自动回到 0 dB；静音后拖动滑块恢复声音。
10. 暂停后点击下一 P，目标 P 应从头开始播放；首 P 上一按钮、末 P 下一按钮禁用。点击标题或编号可打开列表并看到当前 P 标记。
11. 开启单曲循环后正常结束只重播当前 P；手动切换后循环新 P。失败时跳过后续 P，成功后循环成功的 P。
12. 连续失败应顺序向后尝试，到末 P 仍失败时停止；重试从当前末 P 开始，不自动回到第一 P。
13. 快速切 P 或加载中停止，旧音源解析结果不能抢回播放；锁屏、关闭页面后仍自动续播，重新打开能看到当前分 P 和完整列表。

14. 连续播放 4 个不同项目，在首页没有搜索结果时，最近播放仅保留最后 3 个，最新在前；同一视频不同 P 分别记录，重播旧记录后移到首位且不重复。
15. 退出并重启应用，历史仍在；点击历史直接播放对应 P，无需再次选择。记录所指 P 删除或视频失效时显示错误，不跳到其他 P。
16. 音源解析失败、尚在缓冲时不新增记录；实际开始播放后新增。暂停继续、自然循环不改变排序。

17. 顶部仅保留登录入口；有历史时首页搜索结果区显示最近播放。搜索有结果时隐藏历史，加载中不展示历史；搜索返回空结果时显示历史及空结果提示，无历史时使用原空状态。历史卡片与搜索结果使用相同颜色、字号和播放图标。

构建和测试结果见 `VALIDATION.md`。

## GitHub Actions

目标仓库：<https://github.com/douxc/bili-player>。
工作流 `.github/workflows/android-release.yml`：

- main 推送、PR 和手动触发：Release 测试、Lint、arm64 构建与 APK 校验。
- 推送与 versionName 一致的 v* 标签：签名构建后创建 GitHub Release，上传 APK 和 SHA256。
- APK 与 mapping、验证报告分别作为 Artifacts 保存 30 天；Gradle 缓存与 Wrapper 校验已启用。
- 所有 Actions 固定到已核对的官方提交 SHA。

在仓库 Actions Secrets 添加 `SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`
和 `SIGNING_KEY_PASSWORD`，在 Actions Variables 添加 `SIGNING_KEY_ALIAS`。
别名优先读取 Variables，也兼容同名 Secret。沿用原测试密钥时，密码默认 android，
别名默认 androiddebugkey。

已登录 GitHub CLI 且本地持有原密钥时：

```bash
base64 < signing/tingjian-debug.keystore | gh secret set SIGNING_KEYSTORE_BASE64 --repo douxc/bili-player
```

无签名 Secret 的普通构建仅上传文件名带 -unsigned 的未签名包；标签发布会明确失败。
PR 构建不读取签名 Secret。修改版本并提交后，推送 `v0.1.5` 标签触发发布。

混淆映射由构建生成，Actions 单独保存；私有源码 ZIP 也保存对应签名 APK 的 mapping。

## 正式签名密钥

正式密钥别名为 `tingjian`，PKCS12 / RSA 3072。密钥和密码保存在个人签名备份包，
仓库不包含 signing 目录。将备份包 github-secrets 中的同名文件内容设置为：

- Actions Secrets：`SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_PASSWORD`。
- Actions Variables：`SIGNING_KEY_ALIAS`，值为 `tingjian`。

也可使用备份包 configure-github-secrets.py，通过已登录的 GitHub CLI 将四项全部
设置为 Secrets；工作流兼容这种配置。
使用新签名时首次安装需卸载旧测试包，之后固定此密钥更新。
