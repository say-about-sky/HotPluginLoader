# libs/ —— 依赖说明（纯 Gradle，无脚本）

`build.gradle` 逐项声明依赖，**没有单独的依赖/构建脚本**；一条命令：

```powershell
set AOH_GAME_ROOT=<游戏目录>   # 一次性配置（也可 -PgameRoot=...）
gradlew.bat build              # = jar + patch dex
```

## 随仓库提供（小体积、可再分发）

| 文件 | 用途 |
|---|---|
| `finality-api-0.1.0.jar` | Finality API（`Plugin` 基类等），来自 [Finality-Framework/api-aoh3](https://github.com/Finality-Framework/api-aoh3)（GPLv3） |
| `gdx-android-stubs.jar` | **6 个编译桩**：`androidx.core.content.ContextCompat`、`com.badlogic.gdx.backends.android.AndroidApplication(Configuration)`、`team.rainfall.compactScale.WorkThread`、`team.rainfall.sternstunden.Core`、`team.rainfall.sternstunden.CrashHandler`。后三个是整合包专用类，没有公开坐标可替代 |

## Gradle 自动解析（不随仓库）

| 坐标 | 用途 | 来源 |
|---|---|---|
| `com.github.Finality-Framework:loader:1.6.0` | 编译期运行时 API + **`dex` 任务的可执行 loader** | JitPack（GPLv3） |
| `com.android.tools:r8:8.6.27` | 内置 D8 转换器（**打进产物**） | Google Maven，国内走阿里云镜像 |

## 本机路径（Gradle 直接读取，不复制、不打包）

| 文件 | 解析方式 |
|---|---|
| `game.jar` | 环境变量 `AOH_GAME_ROOT` → `-PgameRoot=` → 本工程上一级；优先 `libs/game.jar` |
| `android.jar` | `ANDROID_HOME` / `ANDROID_SDK_ROOT` → `-PandroidSdk=`；优先 `platforms/android-34`，否则最新 |
| `d8.jar`（仅传给 loader 用） | 同上 SDK；优先 `build-tools/35.0.0`，否则最新；可 `-Pd8Jar=` 覆盖 |

缺失时 `dex` 任务会直接报错并给出对应的设置方法。

## 完全离线

把 `implementation 'com.android.tools:r8:8.6.27'` 换成本机 SDK 的 d8：

```gradle
implementation files('<sdk>/build-tools/35.0.0/lib/d8.jar')
```

（loader 仍需 Gradle 从 JitPack 取一次，之后走 `~/.gradle` 缓存，可用 `--offline`。）
