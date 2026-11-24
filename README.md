# PolarBell

把手机当成电脑的外放音箱。这是 **StudyTimer**（Windows 桌面端学习计时器）的 Android 配套 App：
手机通过 USB 连上电脑后，StudyTimer 的阶段音乐、提醒声音、完成提示音都交给手机的 `MediaPlayer` 播放，
电脑只负责计时和画面。

手机 App 不在时电脑端照常工作，会自动改回电脑扬声器。

## 功能

### 电脑联动
- 前台服务常驻监听，USB 一接通就显示「已连接电脑」
- 接收电脑的播放 / 停止 / 音量指令，播完自动通知电脑接下一首
- 电脑可管理手机音频库：上传、删除、重命名、逻辑文件夹、按块取回试听

### 音频库
- 从手机里选音频导入（多选），记录名称、时长、大小
- 试听、重命名、上移 / 下移排序、删除
- 逻辑文件夹（只一层，文件不动）：新建 / 改名 / 删除；删文件夹时音频回到「未分类」
- 顶部文件夹标签切换，「全部」里才能调整顺序
- 显示并清空电脑上传的缓存

### 手机独立计划
电脑没开的时候，手机自己也能计时（OPPO R15 / Android 9 上验证过）：

- 单个计划，多个阶段，每段可设阶段名、分钟数、提前播放秒数
- 每段音乐可选：不改变（上一段继续）/ 播放某文件夹（顺序或随机）/ 停止
- 前台服务 + `WakeLock` 计时，熄屏照常；按墙上时间记录阶段结束点，进程被杀后重启能接上
- 提前时间到就开始连续播放该文件夹（播完接下一首），直到下一段音乐开始或计划停止
- 通知栏显示阶段名与系统倒计时（`setUsesChronometer`，不每秒刷新），点击通知回到 App
- 电脑一连接就自动停止手机计划（避免两边同时计时）

### 其他
- 内置三段提示音（`bell_1/2/3`），供电脑端 `PLAY:1-3` 使用
- 绿白主题；ColorOS 后台运行引导（自启动 / 后台运行 / 电池优化 / 锁定任务）

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Android Studio | 最新版（项目用 AGP 9.5.1、Gradle 9.6.0） |
| JDK | 21 及以上（用 Android Studio 自带的 JBR 即可；`gradle/gradle-daemon-jvm.properties` 里指定了 25，Gradle 会自行下载） |
| Android SDK | compileSdk / targetSdk 37 |
| 手机 | Android 7.0（API 24）及以上 |

`local.properties`（含本机 SDK 路径）不入库：Android Studio 打开项目会自动生成；
纯命令行构建需要自己指定 SDK，设置环境变量 `ANDROID_HOME`，或在项目根目录新建 `local.properties`：

```properties
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
```

第一次构建需要联网下载 Gradle 发行版与依赖。

## 构建与安装

```bash
# 命令行构建（Windows）
gradlew.bat assembleDebug

# 产物
app\build\outputs\apk\debug\app-debug.apk

# 装到连着的手机
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

用 Android Studio 打开本目录，直接点 Run 也可以。

Release 构建需要在 `app/build.gradle.kts` 里自行配置签名，仓库不含任何密钥文件。

## 与电脑配对

电脑端（StudyTimer）通过 ADB 端口转发找到手机：

```bash
adb forward tcp:8988 tcp:8988
```

之后电脑连 `127.0.0.1:8988` 就是手机的 `SocketServer`（端口 `8988`）。
StudyTimer 会自动执行这条命令，只有手机装了 PolarBell 并打开过才连得上。

手机端首次运行会常驻一个前台服务。Android 13 及以上需要允许通知，否则看不到阶段信息。

## 通信协议

纯文本命令行协议，UTF-8，`\n` 结尾；上传数据时命令后面紧跟原始字节。
`CAPABILITIES` 回复当前支持的能力：

```text
CAP:PLAY_UPLOAD,PLAY_UPLOAD_HASH,UPLOAD_CHUNKED,LIST,STOP,LIBRARY_MANAGE,VOLUME,LIB_FOLDERS,PLAY_STATE,LIB_READ
```

### 基础命令

| 命令 | 回复 | 说明 |
| --- | --- | --- |
| `PING` | `PONG` | 保活（电脑每 5 秒一次，空闲 120 秒断开） |
| `CAPABILITIES` | `CAP:...` | 查询能力 |
| `PLAY:1` / `:2` / `:3` | `OK` | 播放内置提示音 |
| `PLAY_AUDIO:<audioId>` | `OK` / `AUDIO_NOT_FOUND` | 播放音频库里的音频 |
| `PLAY_STATE` | `PLAYING` / `IDLE` | 是否还在响，电脑据此接播下一首 |
| `STOP` | `OK` | 停止 |
| `SET_VOLUME:<0-100>` | `OK` / `INVALID_VOLUME` | 相对手机媒体音量 |

`LIST` 回复一个 JSON：`{"status":"OK","audios":[{"audioId","name","duration","fileSize","folder"}],"folders":[...]}`。

### 音频库管理（能力 `LIBRARY_MANAGE` / `LIB_FOLDERS` / `LIB_READ`）

| 命令 | 回复 |
| --- | --- |
| `LIB_ADD:<sha256>:<name>` | `OK:<audioId>`（文件需先用分块上传传完） |
| `LIB_ADD_TO:<sha256>:<folder>:<name>` | `OK:<audioId>` / `FOLDER_NOT_FOUND` |
| `LIB_DELETE:<audioId>` | `OK` / `AUDIO_NOT_FOUND` |
| `LIB_RENAME:<audioId>:<name>` | `OK` / `INVALID_NAME` |
| `LIB_MOVE:<audioId>:<folder>` | `OK` / `AUDIO_NOT_FOUND` |
| `LIB_FOLDER_CREATE:<name>` | `OK` / `ERROR:<原因>` |
| `LIB_FOLDER_RENAME:<old>:<new>` | `OK` / `ERROR:<原因>` |
| `LIB_FOLDER_DELETE:<name>` | `OK`（其中音频回到未分类） |
| `LIB_READ:<audioId>:<offset>:<len>` | `DATA:<总大小>:<扩展名>:<Base64>`（单次 ≤512KB，供电脑试听） |

文件夹名不含冒号或换行，最长 40 字，不能叫「未分类」；`folder` 为空字符串即未分类。

### 文件上传播放

两种：一次性上传（上限 32MB）和分块续传（上限 512MB，单块 ≤4MB）。

```text
# 一次性
PLAY_UPLOAD:<size>:<name>
手机 -> READY
电脑 -> <size> 字节
手机 -> OK

# 按内容哈希复用缓存
PLAY_UPLOAD_HASH:<size>:<sha256>:<name>
手机 -> CACHED（命中，直接播放）或按上面流程上传后回复 OK
```

```text
# 分块 + 断点续传
UPLOAD_BEGIN:<size>:<sha256>:<name>
手机 -> DONE（文件已完整存在）或 OFFSET:<已收到字节数>

UPLOAD_CHUNK:<sha256>:<offset>:<len>
手机 -> READY，收完 -> ACK:<新长度>；偏移不对 -> OFFSET:<实际长度>

UPLOAD_PLAY:<sha256>
手机校验 SHA-256 后播放 -> OK
```

失败回复：`INVALID_UPLOAD`、`INVALID_SIZE`、`INVALID_HASH`、`NO_SESSION`、`UPLOAD_INCOMPLETE`、
`HASH_MISMATCH`、`SAVE_FAILED`、`PLAY_FAILED`、`UNKNOWN_COMMAND`。

上传缓存放在 `cache/polarbell-upload/`，服务启动时清理 7 天前的文件。

## 数据存放

均在本应用的存储目录内，卸载即清除：

| 路径 | 内容 |
| --- | --- |
| `Android/data/com.suto.polarbell/files/audio/library.json` | 音频库索引（audioId / 名称 / 时长 / 大小 / folder） |
| `Android/data/com.suto.polarbell/files/audio/folders.json` | 文件夹列表 |
| `Android/data/com.suto.polarbell/files/audio/audio_<uuid>.<ext>` | 索引对应的音频文件 |
| `files/phone-plan.json` | 手机独立计划 |
| `files/phone-plan-state.json` | 计划计时状态（重启后接上） |
| `cache/polarbell-upload/` | 电脑上传的临时文件与缓存 |

音频库放在应用的外部私有目录（`getExternalFilesDir`），方便在文件管理器里查看；
索引采用临时文件 + 改名的方式原子写入，写入失败不会留下半截文件。

## 项目结构

```
app/src/main/java/com/suto/polarbell/
├── MainActivity.java          # 主界面：连接状态、音频库、手机计划卡片
├── PlanActivity.java          # 编辑手机计划（阶段、音乐、提示音）
├── audio/AudioPlayer.java     # MediaPlayer 封装（媒体音量通道、熄屏播放、音量、暂停）
├── media/                     # AudioItem / AudioLibrary（索引、文件夹、导入、原子写入）
├── network/SocketServer.java  # TCP 8988 命令服务与上传
├── plan/                      # PhonePlan（数据）、PhonePlanEngine（计时与连续播放）
└── service/PolarBellService.java  # 前台服务、通知、WakeLock、闹钟兜底
app/src/main/res/             # 布局、主题、图标、内置提示音
```

## 已知限制

- 只在 OPPO R15 / Android 9 上实测过；ColorOS 需要手动允许自启动与后台运行，否则熄屏后计划可能被系统停止（App 内有引导）
- 手机计划只能有一个，且不支持跨过的阶段补播
- 通知栏只有状态和倒计时，没有暂停 / 跳过按钮（App 内「后台设置」的说明文字与实现略有出入），操作要回到 App 里
- 仓库里的 `ExampleUnitTest` / `ExampleInstrumentedTest` 只是工程模板，没有实际测试用例
- 未配置 release 签名，仓库不含密钥

## 相关项目

- 电脑端 **StudyTimer**：https://github.com/zlr-Aii/StudyTimer

## 许可证

MIT，见 [LICENSE](LICENSE)。
