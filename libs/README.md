# libs/ —— 编译期依赖说明

现在的 `build.gradle` 不再把 `libs/` 整目录塞进 classpath，而是逐项声明（改前是 `fileTree('libs')` + `files('libs/d8.jar')`）。

## 随仓库提供（小体积、可再分发）

| 文件 | 用途 |
|---|---|
| `finality-api-0.1.0.jar` | Finality API（`Plugin` 基类等），来自 [Finality-Framework/api-aoh3](https://github.com/Finality-Framework/api-aoh3)（GPLv3） |
| `gdx-android-stubs.jar` | **6 个编译桩**：`androidx.core.content.ContextCompat`、`com.badlogic.gdx.backends.android.AndroidApplication(Configuration)`、`team.rainfall.compactScale.WorkThread`、`team.rainfall.sternstunden.Core`、`team.rainfall.sternstunden.CrashHandler`。后三个是整合包专用类，没有公开坐标可替代 |

## Maven 坐标（自动下载，不进产物）

| 坐标 | 用途 | 来源 |
|---|---|---|
| `com.github.Finality-Framework:loader:1.6.0` | Finality 框架运行时（FinalityLogger / luminosity2 / Mixin CallbackInfo） | JitPack（GPLv3） |
| `com.android.tools:r8:8.6.27` | 内置 D8 转换器，**会被打进产物** | Google Maven，本工程走阿里云镜像 `https://maven.aliyun.com/repository/google` |

## 需要本机准备（不进版本库）

| 文件 | 用途 | 获取方式 |
|---|---|---|
| `libs/game.jar`（优先）或 `<游戏根>/game.jar` | 游戏本体类 **+ libGDX 核心**。必须与运行时版本一致，**不要**换成 Maven 的 `gdx`（编译期 API 可能高于运行期） | 从自己的游戏安装目录取 |
| `android.jar` | Android 平台类（`android.app.*` 等） | `<sdk>/platforms/android-34/android.jar`（`ANDROID_HOME` 自动探测） |
| `d8.jar`（可选） | 不想用 Maven R8 时的替代载荷 | `<sdk>/build-tools/<ver>/lib/d8.jar` |
| `loader-1.6.0.jar`（可选） | 不想用 JitPack 时的替代 | <https://github.com/Finality-Framework/loader/releases/download/1.6.0/loader-1.6.0.jar> |

## 一键准备

```powershell
powershell -ExecutionPolicy Bypass -File HotPluginLoader\tools\fetch-libs.ps1
```

- 必填：`game.jar`（复制到 `libs/game.jar`）
- 可选：`android.jar`、`d8.jar`、`loader-1.6.0.jar`（缺失只提示，不中断）
- 已存在的文件默认跳过，`-Force` 覆盖

## 完全离线构建

把两个 Maven 坐标换成本地文件即可（离线时不需要任何网络）：

```gradle
// compileOnly 'com.github.Finality-Framework:loader:1.6.0'
compileOnly files('libs/loader-1.6.0.jar')
// implementation 'com.android.tools:r8:8.6.27'
implementation files('libs/d8.jar')
```
