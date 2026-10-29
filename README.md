# HotPluginLoader —— 主菜单「插件」入口 + 外部 dex 热加载(宿主插件)

> **简介**:给《Age of History 3》用的**热加载宿主** —— 主菜单多一个「插件/附加包」入口,
> 扫描 `hotplugins\` 下的 `.dex/.zip/.jar`,运行时加载 / 启停 / 重载,不用重打 APK
> (安卓端内置 D8,能把 jar 自动转 dex 并生成清单)。
>
> **快速开始**:① `set AOH_GAME_ROOT=<游戏目录>`（一次性）→ ② `gradlew build` →
> ③ 产物 `build\libs\HotPluginLoader-0.2.0.jar` + `out\game.dex.zip`（自行部署）。
> **依赖**:全部由 Gradle 解析 —— loader 走 JitPack，R8 走 Maven，`game.jar` / `android.jar` 从本机路径取（见下文「构建」）。
> **许可**:GPL-3.0,见 `LICENSE.TXT`。
> **隐私**:纯本地运行,不联网、不收集不上报任何数据;日志只写本地 `hotplugins\hotloader.log`。
> 插件属于第三方代码,请只安装可信来源的插件。
>
> 设计/能力边界与逐版本记录见下文。

> 参考 `LoaderSettings\LongPressMoveArmy` 的工程与验证流程(同一套构建/出 dex/合并管线)。
> 设计文档:`..\热加载插件系统设计.md`(能力边界 / 加载流程 / §9 审计结论)。
> 演示热插件工程:`..\HotPluginDemo`(模板 + `build-hotdex.bat` 一键出 dex)。
> 测试样本包:`..\HotPluginDemos`(一键出 3 个 dex + 4 个 jar,覆盖全部新流程与失败路径;见其 README)。

## 当前进度(v0.2.0)

- [x] 主菜单「载入游戏」与「编辑器/设置」之间注入一行:
      **「插件」按钮(80%)+ 齿轮设置按钮(20%)**;
- [x] 「插件」面板:插件文件列表(名称/版本/状态)+ 逐项加载/禁用/启用 + 全部加载 + 复制目录路径 + 刷新;
- [x] 热插件加载管线:扫描 → 清单校验 → 建类加载器 → 实例化 → init → 登记;
      - 安卓:`.dex` / `.zip`(多 dex),优先 `InMemoryDexClassLoader`(API 26+),回退 `DexClassLoader`;
      - 桌面(开发调试):`.jar`(JRE 不能读 dex,仅供联调插件逻辑);
- [x] 插件注册表 + 状态显示(未加载 / 已加载 / 已禁用 / 未信任 / 错误:原因);
- [x] 事件总线(首批 `frame` 帧事件;地图点击等需宿主 Mixin 钩子,后续版本加);
- [x] 设置接线:启动自动加载(下次启动生效)/ 信任门槛;
- [x] 桌面端中文字形补全(`CharsetFix`:字体烘焙前把文案字符并入语言 charset —— 原理见开发指南 §11);
- [x] **安卓自举**:整合包内的 finality 是另一代实现(无 `api/Plugin`/`PluginsCollection`),
      插件 `init()` 永不执行 → 宿主自带一枚引导 Mixin 注入 `Button.draw(HEAD)`,
      首个按钮绘制即启动每帧循环(桌面仍走框架 `init()`;两入口幂等并存);
- [x] 文件日志 `hotplugins/hotloader.log`(`SafeLog` 全量镜像;安卓上 `loader.log` 不存在,真机排障靠它);
- [x] **清单 json 自动生成**(b3):主类标注 `@HotPluginInfo`(RUNTIME 注解,随 class 进 dex)
      → 工具 `..\.tools\hotjson\HotManifestGen` 从 jar 生成 `<名字>.json`;
      `HotPluginDemo\build-hotdex.bat` 已接入 → 作者无需手写清单
      (未标注解时回退读 jar 清单的 `Implementation-Title / Implementation-Version`);
- [x] **「导入」文件选择器**(b4):面板新增「导入插件文件」→ 系统 SAF 多选 → 后台线程复制进模组目录;
      **无需改 manifest** —— 整类替换 `aoc.kingdoms.lukasz.jakowski.AndroidLauncher`(逐指令复刻原版 +
      接管 `onActivityResult`,同 PolarisCore 的 402 类替换机制;编译用 `.tools\stubs\make-stubs.bat` 的编译期 stubs);
- [x] **游戏内 D8 转换**(b5):独立「D8 转换器」热插件(工程 `..\HotConverter\`;不烤进宿主、放 hotplugins 才启用)——
      目录里出现 .jar(含「导入」进来的)时自动转成 `<同名>.zip`(多 dex)+ 生成 `<同名>.json`,然后直接加载;
      触发 = 转换器启动扫描 + `HotEvents.IMPORT_DONE`;r8/d8 库 dex 化后单 classes.dex ≈7.2MB(zip ≈2.9MB);
- [x] **导入回传修复 + 引导**(b6–b8):暮色整合包的启动 Activity 其实是 `team.rainfall.sternstunden.AlternativeLauncher`
      (manifest 实锤)→ **双整类替换**(原版 + 暮色版)接管 `onActivityResult`;替换生效检测(`HB_REPLACEMENT_MARKER`)
      + 生命周期轨迹日志(b27 起并入 `hotloader.log` 并受「开启日志」总开关门控);面板对 `.jar` 行明确引导(未装转换器 / 已转换指向同名 .zip 行);
- [x] **单个 .dex 自动生成清单**(b9):裸 dex 无 json 时 —— 宿主解析 dex 类表(纯字节,零依赖)、
      类加载找 `HotPlugin` 实现(不执行插件代码)、读 `@HotPluginInfo` 写 `<同名>.json`;
      导入后自动生成并加载,启动扫描同效;面板未生成为「待生成清单(点行)」;仅安卓(桌面 JVM 无法加载 dex);
- [x] **目录分类 + 注册配置**(b10):`dex\`(插件本体: .dex/.zip/.jar)/ `json\`(清单)两个子目录 ——
      扫描、导入、转换产物、自动清单全部自动归类(根目录仍兼容,旧文件不用搬;也识别隐藏式 `.dex\`/`.json\`);
      `registry.json`(根目录)管理启用/禁用:禁用 = 下次启动不加载、不实例化;面板开关同步写回文件;
- [x] **「全部重载」+ 更新防占用**(b11):按钮由「全部加载」改为「全部重载」—— 有已加载实例时
      **安卓先重启游戏**(活动重建回到加载画面;`HotBootstrap/Tick` 检测 GDX 实例变化 → 清掉上一局插件状态 → 自动重新加载),
      桌面/失败退化为软重载(退订+清表+重新扫描加载);导入同名文件**就地覆盖**(不再产生副本);
      同名新副本(更新场景)**自动替代**旧副本,旧副本行显示「已被较新的同名副本替代(保留 …)」;
- [x] **加载器 = 一个库**(b12):D8 转换器**并入宿主** —— 转换+加载同在补丁 dex(`game.dex.zip`)里,
      不再需要单独安装转换器(旧 `d8converter.*` 文件加载时提示删除;jar 行点按可直接触发转换);
      补丁 = 单 `classes.dex` 7.4MB(与游戏自带 11MB 级 dex 同量级;合并脚本同时支持多 dex 补丁);
- [x] **重启 = 整进程重启 + 转换防残渣**(b13/b14;b17 起重启降级为异常兜底):曾把「全部重载」改为
      **闹钟自拉起 + killProcess** 的整进程重启 —— 同进程 `Activity.recreate()` 实测会让游戏纯色"蓝屏"(复用失效 GL 资源/静态状态);
      转换产物**临时 zip 原子落位**(失败不留残渣/孤儿 zip)、**失败记忆**(文件没变不重试、不重复弹错)、
      jar 行「已转换」以产物+清单齐备为准;
- [x] **目录再整理 + 重启链路加固**(b15):`dex\` 只放 `.dex`,新增 `jar\` 归档 `.jar / .jar.done / .zip`
      (dex\ 里的遗留 jar/zip 启动时自动搬入,同目录旧式清单一并搬入 json\);重启链路升级为
      `setAlarmClock`(闹钟级)→ 精确 → 非精确逐级降级,并把实际通道与 exactAllowed 写日志;导入写失败自动删半截文件;
- [x] **jar 转换直出 dex**(b16):单 dex 转换产物**直接落 `dex\<同名>.dex`**(不再产 zip、不再留档 ——
      转换成功后**源 jar 删除**,`.jar.done` / 旧 zip 自动清理;更新版本直接重新导入即重转);多 dex(极少见)
      才退回 `<同名>.zip` 方案;跳过规则同时认 `.dex`/`.zip` 产物;
- [x] **「全部重载」改原位整层重载**(b17):不再重启游戏进程 —— 冷启动要重跑 `menus.InitGame`
      150+ 步加载流水线(整合包上以分钟计),而插件层本就活在主菜单之后、全由宿主管理:
      退订清表 → 重扫重载 → 补一轮 .jar 转换,秒级完成;仅重置/扫描出现致命异常才兜底整进程重启
      (重启链路保留,见 b15);
- [x] **加载进度可视化**(b18):「全部重载」改用**队列分帧推进**(≈10ms/帧,仿 `menus.InitGame`
      状态机;不再阻塞渲染线程)→ 面板顶部**状态行**(加载 3/7、转换队列 n/m、D8 运行秒表、结果留痕)
      + **自绘进度条**(加载 = 精确比例;转换 = D8 无进度回调,扫动段而非假百分比);
      `ConverterService` 阶段上报(准备/D8/写入加载);模组行悬停新增「加载耗时」;
- [x] **单插件故障隔离**(b19):加载全程逐文件 try/catch —— 某插件报错(清单/类加载/init/转换)
      只标记该插件为「错误」(面板显示原因),**跳过并继续**加载其余;`hotloader.log` 错误带堆栈(截断);
      `HotEvents` 监听者抛错的日志限流(前 3 次 + 每 30s 一条,防 FRAME 事件每帧刷屏);
- [x] **日志开关 + 行删除**(b20):设置面板新增「开启日志」(全局日志总开关,关闭后不再写
      hotloader.log 与框架日志镜像)与「启动时刷新日志」(每次启动清空日志);模组行缩宽并在行尾
      加删除按钮(连点两次确认),删除 = 本体 + 转换产物(dex/zip/.done)+ 同名 json,
      并自动停用/移出注册表与 registry.json 禁用项(删除按钮 b23 起用文字「×」,规避 btnh_close 贴图留白偏移);
- [x] **模组加载顺序可调**(b21/b22):顺序持久化进 `registry.json` 的 `order`(按文件主名;列表与加载同源);
      **按住行上下滑动 = 拖动换位**:按手指实时位移驱动(滑满一个行距换一格、滑得越快换得越快、
      停手即停,单帧最多 3 格),松手落定并写入 order;长按弹菜单已移除(b22);列表可滚动时禁用滑动防冲突;
- [x] **模组行改「加载器设置」同款变色开关**(b24):行卡片改用 `ButtonGame2` + `checkBox`(与加载器设置
      面板开关同款原生控件)—— 行首色条**绿 = 已加载、红 = 未加载**(`getCheckboxState()` 每帧实时读取,
      点击行即时变色);行内不再重复「· 已加载」(其余状态 已禁用/未信任/错误 仍以文字标出);
- [x] **刷新保留滚动位置 + 可滚动列表排序**(b26):面板重建(加载/禁用/删除/刷新/排序落定)不再跳回顶部 ——
      重建时接力菜单滚动偏移(`iMenuPosY`),越界自动收敛;可滚动列表(超 8 行)重新支持拖动排序:
      **按住行不动约 0.45s 进入排序**,进入后本次手势的列表滚动被禁用(松手提交;未换位则静默结束);
- [x] **进度条观感修正**(b25):条高 8→16px、金色边框/轨道提亮(空闲时也清晰可见)、填充内缩自适应;
      与状态行拉开 6px 间距(防「已重载:…」文字下沿遮条);
- [x] **底层资源覆盖接口**(b28):`hotplugins/override/` 镜像游戏内路径即可顶替游戏资源
      (图片/json/数据/……);实现 = `ResourceOverride` 装饰游戏的 `FileManager.LoadInterface`
      (原加载链 100% 保留:桌面创意工坊/mods → 安卓 local→assets);安装 = 启动类 onCreate 起后台线程
      (`ColdBootstrap`,等 `AA_Game.create` 第一条指令初始化出接口后换装;tick 兑底),
      热插件可用 API 注册覆盖(`ColdBootstrap.register`)。详见「资源覆盖接口使用帮助.md」;
- [x] **加载器直挂“最早接口”**(b29):整类替换 `FileManager$3`(游戏 `AA_Game.create()`
      第一条指令 `initLoadInterface` 里直接 `new` 的安卓端文件实现)——实例化即建立资源覆盖层
      (构造函数内扫描 `override/`),零等待零竞态;逐字复刻框架版行为(FontFix.isLocalStorage 开关 +
      Steam 模组链 + external/local/internal 回退),无框架时退原版 local→internal;
      线程/每帧兜底保留(兼容未被替换 $3 的底座);
- [x] **冷加载预载(进菜单零卡顿)**(b30):热插件的扫描/转换触发/加载提前到**冷加载阶段**,
      且全部在**后台线程**完成(`ColdTick` 从 `FileManager$3` 直挂点火、覆盖整个加载屏;
      `StartupLoader` 干活)—— 进主菜单不再出现同步加载的卡顿;预载进度写进游戏加载文案
      (`InitGame.loadingName`,框架版为静态字段,反射直写 `Loading HotPlugins — …`);
      转换产物改在转换线程直接加载(不再回主线程,只有 toast/刷新走 post);
      「启动时刷新日志」改在启动器 onCreate 首行执行 —— 启动器轨迹与 `FileManager$3` 直挂日志
      不再被首帧 `init` 清掉;注意:插件 `init()` 现运行于冷加载期间的后台线程(勿在 init 里
      直接操作 UI,需要回主线程用 `Gdx.app.postRunnable`);
- [x] **加载屏阶段预载**(b31):实测 b30 预载在启动图(logo)阶段就跑完、加载屏上什么都看不到
      (`InitGame.draw` 里 `splash_id >= 0` = 启动图阶段,`loadingName` 不会被绘制)—— 改为等
      **加载屏出现**(`InitGame.splash_id < 0`)后才点火预载;进度写两条静态通道:
      ① 底部提示语行 `Renderer.sLoadingText`(游戏每 4 秒刷新随机提示语,预载期间我们每帧写回
      → 稳定可见);② 步骤行 `InitGame.loadingName`(保留游戏文字 + 追加后缀);完成后结果
      再展示约 4 秒、随后游戏自行接回;启动图阶段不动任何文字;
- [x] **静默后台预载**(b32):真机实证——进度条在卡图标阶段其实一直在后台推进,等它可见时已近尾声,
      「做进加载屏」没有意义;按要求把加载**往后移**:冷加载全部结束后(首个 `Button.draw` = 主菜单
      出现)才在后台线程**静默**加载 —— 不显示任何消息、**不替换任何游戏文字**
      (`sLoadingText` / `loadingName` 注入已全部移除;b31 的加载屏显示方案作废)、`ColdTick` 类已删除;
      demo 插件自带的加载 toast 也已移除;
- [x] **冷加载后期点火**(b33,定稿):按要求「相对于冷加载期间后移」—— b30 太早(第一步)、b32 太晚
      (主菜单后);最终 = 恢复冷加载期点火,起点为**启动图阶段结束/加载屏出现**
      (`InitGame.splash_id < 0`,即冷加载后期,冷加载第一步不加载);全程**静默**(不显示任何消息、
      不替换任何文字);字段不可读时等 ~4-6 秒再起步,主菜单首帧兜底;
- [x] **图标阶段内起步**(b34):实测 b33 起点太晚(图标褪去时游戏已到 Loading Scenario 收尾);
      查清图标逻辑(`InitGame.draw`:每张 logo 显示时长 = 配置 `duration`,固定,**与加载进度无关**;
      流水线在整个图标阶段后台推进)→ 改为冷加载开始约 **2 秒**后(仍在图标阶段内)静默点火预载;
      日志:`cold gate armed (start delay 2000ms; …)` → `engaging silent preload (elapsed=…ms, splash_id=…)`;
- [x] **跳过启动图**(b35,用户提议「图标持续时间=0」):`ColdTick` 每帧把 `InitGame.splash_id` 强制置 -1
      (游戏自身的「切加载屏」信号)→ 启动**直达加载屏**、立刻看到进度条,不再有「卡图标」和「图标褪去时机」问题;
      安全门槛 = 等 `InitGame.background != null`(流水线第 0 步 `loadBackground()` 已跑)后才切;
      想恢复图标 = 把 `ColdTick.SKIP_SPLASH_ICON` 改为 `false` 重新出 dex;
- [x] **模组加载调回最前**(b36):`START_DELAY_MS = 0` —— 冷加载一开始(第一步)就在后台点火预载
      (配合 b35:启动即进加载屏 + 模组后台加载,全部静默);
- [x] **详细日志默认输出 + 冷加载失败隔离对齐**(b37):移除「加载器设置」的「详细日志」开关
      (`SafeLog.v` 改为默认输出;`LoaderConfig.verboseLog/setVerboseLog` 已删除);核对冷加载(autoLoad)
      故障隔离 = 与「全部重载」同款(逐文件 try/catch 忽略 + 失败条目不实例化、会话内不重试、面板显原因);
      补齐差异:统计口径对齐(LOADED=成功;DISABLED=跳过;其余含 BLOCKED=失败)+ 加载前 `f.isFile()` 检查;
- [x] **加载屏显示插件冷加载**(b38):预载窗口内在加载屏文字行显示 `Loading hotplugins  <当前文件>`
      (与游戏自身 `Loading ...` 同风格、位于加载序列最前;转换队列在跑时也覆盖);窗口结束**还原游戏文字**
      (仅在当前值仍为我们的注入时才写回,防覆盖游戏新阶段文字);
- [x] **resources 统一资源目录**(b39):`override/` 与插件私有资源职能整合为 `hotplugins/resources/<插件名>/`
      (镜像游戏路径 = 覆盖游戏资源;其余 = 私有资源/数据);`ctx.dataDir()` 指向该目录;
      多插件命中同路径按加载顺序取先;插件被禁用时其资源树不生效(加载/启用/禁用/删除 时实时切换);
      删除插件连带删除资源目录;旧 `override/` 弃用并提示迁移;
- [x] **resources 结构修正**(b40):游戏覆盖资源必须放在 `resources/<插件名>/override/` 子目录内
      (镜像游戏路径;**多插件同路径按加载顺序先者优先**);`resources/<插件名>/` 下 override/ 之外的文件
      = 插件私有资源/数据,**不参与覆盖**;
- [x] **APK 内嵌 hotplugins 首启播种**(b41):发行版 APK 的**根目录**里可以带一个 `hotplugins/` 文件夹 ——
      **首次启动**时自动提取到 `Android/data/<包名>/files/hotplugins/`(同名文件覆盖、目标里多出来的文件保留),
      随后照常走已有的检测/补全(预载 `scan()` → `ensureSubDirs` 补出缺失的 dex/ json/ resources/ 结构;
      `jar/` 按需创建);
      **只播种一次**:完成后写标记 `hotplugins/.apk_seed_done`,之后每次启动直接跳过(绝不刷新用户导入/改动;
      删标记或清应用数据可重新播种);APK 里没放或为空时不写标记,以后换成带内容的 APK 仍可播种;
- [x] **日志开关默认关闭**(b42):「加载器设置」的「开启日志」与「启动时刷新日志」**默认值改为关闭**
      (`LoaderConfig` 与两个启动类读 SharedPreferences 的兜底默认同步修改;已写过偏好的用户不受影响);
      需要排障时在面板里打开即可;
- [x] **「全部重载」一并重扫资源覆盖**(b43):点按钮时除了重载插件,还会重扫
      `resources/<插件名>/override/` 全部资源树(运行中新增/改动的文件立即进索引,之后的读取
      ——如新开一局、按需读取的音乐等——即用新内容);已在内存中的启动期资源(贴图/JSON/字体)
      仍需重启游戏才能替换(引擎缓存所限);旧的「资源索引只在启动时扫一次」限制消除;
- [x] **插件便捷 API**(b44):api 包新增 `HotFiles`(纯 JDK 文件工具:原子读写/复制/递归删/目录拼接,
      失败返回 null/false 不抛)与 `HotConfig`(扁平 JSON 配置助手:def 默认值 + 缺文件自动生成模板 +
      宽容解析(注释/尾逗号) + 未知键原样保留 + lastError 查询);插件最低宿主版本 = b44(`ctx.hostVersion()` 判断);
- [x] **JDWP 调试支持**(b45):启动最早期写 `debug: debuggable/connected/waitMarker` 状态;
      存在 `hotplugins/.wait_debugger` 时轮询等待调试器接入(默认上限 180s、内容可写秒数、删除即取消)
      ——冷加载/预载/插件 init 也能断到;桌面新增 `StartFinality-jdwp.bat`(端口 5005);
      用法见「热插件调试指南_JDWP.md」(测试 APK 开 debuggable + adb forward + IDE/jdb);
- [x] **注解调试点**(b46;b47 按用户要求改为自动执行):插件方法标一行 `@HotDebug` → 加载时自动登记,
      且「开启日志」时**自动执行一次**(无参;有参数用 `args = {...}` 给默认参数;不足跳过),结果/异常
      直接写 `hotloader.log`(`hotplugin[<id>]: [debug] <名> -> OK/EX…`,与 `ctx.log` 同款)——
      改代码 →「全部重载」→ 看日志即可验证某个方法;配合 JDWP 可在方法内下断点;
      `HotContext` 新增 `logEnabled()`;`.debug_cmd` 文件通道已移除(b47);
- [x] **预载让路冷加载**(b48):核对确认"预载与冷加载并行"**不存在文件层资源争夺**
      (覆盖索引在游戏第一次读文件前一次建好、之后只读;两边读互不相同的文件;`ResourceOverride`
      全部方法 `synchronized`)——真正争的只有 CPU/带宽:预载线程与 D8 转换线程改为
      `Thread.MIN_PRIORITY`,渲染线程优先,进度条不被拖慢(附加包稍晚就绪但不打架);
- [x] **「插件」改名「附加包」**(b50):游戏内可见文案统一改名 —— 主菜单按钮、面板标题、按钮与悬停说明、
      状态行、错误提示、`registry.json` 的 note;全部源码注释同步;`CharsetFix` 字形表补「附」「包」两字
      (否则中文语言包缺字 = 空白)。**不变**:英文标识符、`hotplugins/` 目录名、日志标签 `hotplugin[...]`、
      加载屏 `Loading hotplugins`(兼容性),以及指代 Finality 旧式框架插件的措辞;
- [x] **每帧钩子的白烧开销收敛**(b51,2026-10-22):真机日志显示关日志后仍有固定帧成本,定位到两处 ——
      ①`ReflectUtil.get/set/call` 每次调用都重做 `Class.getDeclaredField` / `getMethod`(ART 上这两个 API
      每次都会重新查找并新建 `Field`/`Method` 对象),而 `MainMenuMount` 每帧要查 60~70 次;
      → 加 `ConcurrentHashMap` 缓存(上限 512,只缓存命中结果),命中后 = 一次 hash + `Field.get`;
      ②`MainMenuMount.logStateIfChanged()` 每帧都拼状态签名 + 反射 `getActiveMenu`(即使日志关着),
      `ensureMounted()` 的「文案兜底扫描」在游戏内(没有主菜单)每帧遍历**所有菜单的所有元素**做文本比较;
      → 状态日志改为**每 32 帧 + 仅开日志时**执行,兜底扫描改为**每 8 帧一次**(主菜单出现后仍有 0.13s 内识别);
      估算从 **0.2~0.8ms/帧** 降到 **0.01~0.03ms/帧**;
- [x] **「附加包」界面文案「模组目录」→「加载目录」**(b53):面板提示行 + 按钮悬停说明 + 导入完成提示统一改名(`CharsetFix` 字形表同步);
- [x] **hotplugins 目录规范化**(b53):**构建期**严格命名 —— 工具 `..\.tools\hotjson\HotPackCheck`
      校验 `<名>.dex` ↔ `json/<名>.json` ↔ `resources/<名>/` 一一对应、主名只允许 `A-Z a-z 0-9 . _ -`,
      不合规退出码 1(可直接接入 build.bat 做拦截);`HotManifestGen` 同步校验主名;
      **运行期纠正包容** —— 每次扫描/重载自动删除 `json/` 里没有同名本体的**孤儿清单**,
      孤儿 `resources/<名>/` 只记日志提示、**不自动删除**(里面可能有用户数据);
- [x] **hotpluginsUI.json 主菜单按钮布局**(b53):与 `hotplugins` **同级**(安卓 `Android/data/<包名>/files/`)
      的配置文件 —— `Display:false`(默认,自动生成模板)时不生效;`true` 时可把「设置」与「附加包」
      两个按钮摆到任意位置:`mode=absolute` 用屏幕坐标 `x/y`,`mode=snap` 吸附已有元素
      (`anchor` = 元素文本 / `LoadGame` / `Editor` / `@settings` / `@plugins`,`side` = top/bottom/left/right,
      `offsetX/offsetY` 像素微调);改完重启游戏生效;
- [x] UI 修正 1:主菜单保持「不可滚动」不变量(插入元素后**绝不能重算** `updateScrollable()` —— 主菜单是全屏 `Menu`,贴边元素会被算成溢出 → 菜单可拖动/出现滚动条;只强制 `scrollableX/Y=false`);
- [x] UI 修正 2(安卓真机):①**每帧扫描所有主菜单实例**(安卓会重建/多实例;新实例补挂、被重建的实例重挂,日志带 `id=`);②不再统一重算元素可见性(`updateMenuElements_IsInView` 会把菜单滑入期的元素误标不可见 → 整个菜单黑屏;只给自己的按钮 `setIsInView(true)`);
- [x] 「插件」界面升级为**镜像版「编辑器」**:挂进 EDITOR 视图(游戏重建不动 1 号位)+ 原版窗口滑动过渡 + 剧本地图背景;面板在**右侧**(官方面板在左的镜像);行按钮 = 官方 `ButtonMain` 样式 + `drawBoxCorner`/`drawEditorMenuBG` 底;「返回」= 官方 Editor 同款动作;主菜单行内改为「设置(左,20%) / 插件(右,80%)」;
- [x] 界面分区仿「设置」:精简提示统一在「返回」下方 → 操作按钮 →「模组列表」分隔栏(`Text_Title_v2_TextLR`,同「画面/游戏/音效/地区」)→ 模组行;按钮与分隔栏/行均有 `MenuElement_Hover` 悬停说明(仿「地区」栏提示);
- [x] UI 修正 3(真机实锤):自建面板防横向拖动 —— `Text_Static` 长文本必须 `setWidth(...)` 钉宽(其宽=文本像素宽,超宽会让 `initMenu→updateScrollable` 判定 `scrollableX=true` → 整面板可拖动);并在 `initMenu` 后强制 `scrollableX=false` 兜底(插件面板 + 设置面板均已加);
- [x] 设置面板弹位:弹在**主菜单左侧、不遮盖主菜单**(`MainMenuMount.mainMenuPanelLeft()` 反射主菜单 `iXPos` 定位;左侧空间不足自动收窄面板;拿不到实例时贴屏幕左缘);
- [ ] `mapTap` 等地图交互事件、机上 D8、`pluginMap` 注册 —— 后续版本。

**实现要点**:
- 挂载/加载/事件全部运行时完成;**唯一的 Mixin 是一枚引导钩**
  (`MixinButton` 注入 `Button.draw` 头部;`Button` 经逐方法指令数比对与 PC 语义一致,
  且不在任何现有 Mixin 目标清单里 —— 无重复注入风险);每帧循环 = `HotLoaderTick`(`postRunnable` 接力):
  CharsetFix → 主菜单挂载(自愈) → frame 事件派发 → 首次自动加载(主菜单就绪后一次);
- 找主菜单 → 反射其 `menuElements` 插入两个按钮 → 下方元素整体下移一行 + 面板加高一行(反射私有 `iHeight`)→
  同步 `iMenuElementsSize` 缓存 → 登记进滑动动画列表(`buttonElementIDs`);
- 热插件的父加载器 = 宿主类加载器(= 游戏加载器)→ 可解析游戏类 / finality-api / 宿主 api;
- 每个插件加载全程 try/catch(Throwable),失败只标错误,不影响游戏与其它插件;
- 重载(再次加载同 id)= 旧实例自动退订事件 + 新实例接管;卸载 = 停止派发 + 移出注册表(类无法卸载,重启彻底消失)。

## 热插件格式

每个插件 = 一个二进制 + 一个同名清单 json(本体默认 `dex\`,清单 `json\`;兼容同目录或根目录):

```
hotplugins\
  dex\               ← 插件本体:.dex(导入的裸 dex + b16 起 jar 单 dex 转换直出)
    demo-plugin.dex
  jar\               ← 待转换/多 dex 的 .jar 与 .zip(b16 起转换成功后源 jar 自动删除,不留档;
                       按需创建:没有 jar/zip 时不再预建空目录,已存在的照样扫描)
    demo-plugin.jar
    demo-plugin.zip
  json\
    demo-plugin.json ← 清单(与本体同主名;自动生成 / 转换器清单都放这里)  override\          ← 资源覆盖(b28):镜像游戏内路径的文件顶替游戏资源(见「资源覆盖接口使用帮助.md」)  registry.json      ← 启用/禁用注册配置(见下)
  (根目录仍可放:旧文件与直接丢放继续可用;dex\ 里遗留的 jar/zip 启动时自动搬到 jar\)
```

清单字段:

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | ✅ | 唯一标识,仅字母数字 `._-` |
| `mainClass` | ✅ | 主类:public + 无参构造 + 实现 `HotPlugin`(或继承框架 `Plugin`/有 public `init()`) |
| `name` / `version` | | 显示名 / 版本(面板与日志) |
| `trusted` | | `true` = 免开「允许未校验插件」也能加载 |
| `gameVersion` / `description` | | 仅展示 |

> 安卓端:**单个 `.dex` 缺清单时会自动生成** —— 解析 dex 类表(类名纯字节解析,
> 对照 dexdump 校验过:11MB dex 7919 类 / 52ms)→ 类加载找 `HotPlugin` 实现(不初始化,不执行插件代码)
> → 读 `@HotPluginInfo` 写 `<同名>.json`(写入 `json\` 子目录;无注解时默认 `trusted:true`)。
> 导入的 dex 会自动生成并加载;面板行显示「待生成清单(点行)」时可手动触发。

### registry.json(启用/禁用注册配置)

```json
{
  "note": "把插件 id 放进 disabled 数组可在下次启动时禁用;移出即启用。",
  "disabled": ["com.example.pluginA"]
}
```

- **禁用** = 启动时不加载(不实例化、不执行 init);插件 id 见插件面板的悬停提示;
- 面板上的「已加载 → 禁用 / 已禁用 → 启用」会同步写回本文件,手改文件重启游戏也生效;
- 文件不存在 = 全部启用;损坏时按「无禁用项」降级(不影响加载)。

**代码契约**(热插件工程用 `compileOnly` 引用宿主 jar,**绝不打包**):

```java
@HotPluginInfo(id = "com.example.myplugin", name = "My Plugin", version = "0.1.0", trusted = true)
public class MyPlugin implements HotPlugin, HotListener {
    public void init(HotContext ctx) {
        ctx.log("hello");                            // 写日志
        HotEvents.register(this, HotEvents.FRAME);   // 订阅帧事件
    }
    public void onEvent(String type, Object[] args) {
        // FRAME: args[0]=Long frameId, args[1]=Float rawDelta
    }
}
```

**清单 json 可自动生成**(无需手写):

```
java -cp "<宿主jar>;<game.jar>;<工具目录>" HotManifestGen <插件.jar> [--name 输出主名] [--outDir 目录]
```

- 主类标注 `@HotPluginInfo` → 用注解里的 id/name/version/trusted/description;
- 未标注解 → id = 主类全名,name/version 回退读 jar 清单的 `Implementation-Title / Implementation-Version`,再退回类名/`?`;
- 注解是 `RUNTIME` 保留 → **随 class 进 dex**(jar 里的 json 资源反而会被 d8 丢掉),所以 dex 与 jar 两条链路都能读到;
- 工具离线运行、只读字节码(`Class.forName(..., false, cl)` 不初始化)→ 不会执行插件代码;
- jar 里有多个 `HotPlugin` 实现 → 报错要求给主类加注解(避免猜错)。

**作者侧构建流程**:见 `..\HotPluginDemo\`(`gradlew jar` → d8 → `.dex` + **自动生成的 json**;
一键脚本 `build-hotdex.bat`,自动把桌面测试副本部署到 `hotplugins/`)。

## 导入(安卓,b4)

「插件」界面 →「导入插件文件」→ 系统文件管理器(可多选)→ 自动复制进模组目录
(**b15 起自动归类:`.dex` → `dex\`,`.jar/.zip` → `jar\`,清单 `.json` → `json\`**;
同名文件已存在时**就地覆盖**——b11,更新插件直接导入即可)。

- 机制:整类替换 `aoc.kingdoms.lukasz.jakowski.AndroidLauncher` —— 它只存在于 APK、Mixin 够不到,
  而补丁 dex 先加载、同名类先到先得;替换版逐指令复刻原版逻辑(onCreate / check / hasInternetPermission),
  新增 `onActivityResult` 转发给 `android.HotImporter`;**不需要改 AndroidManifest**;
- 编译:`AndroidLauncher`/`HotImporter` 引用 gdx-android 后端 / androidx / compactScale(APK 内有、game.jar 没有)
  → 由 `..\.tools\stubs\` 的编译期 stubs 顶(classpath;**绝不打包**;`make-stubs.bat` 一键重建到 `libs\`);
- ⚠ 替换类重写自暮色 0.25.1 的反汇编;换整合包前需重新反汇编核对;
- 导入的 `.jar` 由**内置转换器**(b12)自动转 dex:导入完成即自动转换(也可点 jar 行手动触发)——不装任何额外组件;
  **b16 起转换成功后源 jar 自动删除、单 dex 直出 `dex\<同名>.dex`**(不留档;更新版本直接重新导入即可)。

## 内置 D8 转换器(安卓;b12 起并入宿主)

作用:把插件目录里的 `.jar` 在游戏进程内转成插件本体 + `<同名>.json` 清单,并直接加载。
b16 起**单 dex 直出**:产物直接落 `dex\<同名>.dex`(不再打 zip);多 dex(极少见)才退回 `<同名>.zip`。

- **不再单独部署**:d8 库随宿主打进补丁 dex(`game.dex.zip` 单 classes.dex ≈7.4MB / 11,539 类;
  游戏 APK 本身即含 11MB 级 dex,同量级);旧的 `d8converter.dex/zip/json` 加载时提示「已内置转换器(请删除本文件)」;
  原 `..\HotConverter\` 工程仅留历史参考(`build-converter.bat` 不必再跑);
- 触发:①启动自动加载完成后扫一遍目录;②导入完成后;③点面板 .jar 行手动触发;④「全部重载」软重载后;
  跳过规则:产物(`dex\<同名>.dex` 或 `<同名>.zip`)不旧于 jar 且清单在 → 视为已转换(顺带清掉冗余源文件);
  受「启用热加载」总开关控制(桌面不转换,直接加载 .jar);
- **v0.2.0 新增**:①**旧式插件回退** —— jar 内没实现 `HotPlugin` 时,自动读 jar 内嵌的
  `plugin.json`(Finality 旧插件都带)取 id/name/version/mainClass 生成清单;
  ②**转换成功后源 jar 删除(不留档)** + 自动清 `.jar.done` / 同名旧 zip(外部还有原文件,重导即重转;
  面板里 jar 行转换完即消失);
  ③生成的清单默认 `trusted:true`(用户显式导入即信任;作者可用 `@HotPluginInfo(trusted=false)` 显式要求门槛);
- 流程:D8(RELEASE,min-api 26,缺类警告忽略)→ classes*.dex(单 dex 直投 `dex\`,多 dex 打 zip)→ InMemoryDexClassLoader(反射)
  → 扫 jar 内类找 HotPlugin 实现(优先 `@HotPluginInfo`)→ 写 json → 回主线程加载 + 提示 + 刷新界面;
- 测试:`..\.tools\verify\D8ConvertTest.java`(桌面跑同一 `JarToDex` 代码路径,22 断言全过,含单 dex 直出/打包);
  真机已实测:ART 上 D8 转换正常(与旧独立转换器同一管线;单 dex 7.4MB 合并/启动无压力)。

## 构建(本工程)

```
set JAVA_HOME=<JDK11 路径,如 %JAVA_HOME%>
gradlew.bat shadowJar
```

产物:`build\libs\HotPluginLoader-0.2.0.jar`(只出 jar)、`out\game.dex.zip`(`gradlew dex` / `gradlew build`)。

> **依赖全部由 Gradle 解析（无外部脚本）**：
> - loader 编译器/可执行包：`com.github.Finality-Framework:loader:1.6.0`（JitPack，自动缓存）
> - 内置 D8/R8：`com.android.tools:r8:8.6.27`（Google Maven，国内走阿里云镜像）
> - 随仓库的小件：`libs\finality-api-0.1.0.jar`、`libs\gdx-android-stubs.jar`（6 个编译桩）
> - 仅本机：`game.jar`（默认取本工程上一级）、`android.jar` + `d8.jar`（从 `ANDROID_HOME` 探测）
>
> 覆盖方式：环境变量 `AOH_GAME_ROOT` / `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）/ `JAVA_HOME`，
> 或 `-PgameRoot= -PandroidSdk= -PandroidJar= -Pd8Jar=`。详见 `libs\README.md`。

### 构建加速（国内网络）

- **JDK：构建需要 JDK 11+**（载荷 r8 8.6.x 的 class 文件版本是 55.0，JDK 8 的 javac 会报“类文件具有错误的版本 55.0，应为 52.0”）。
  `build.gradle` 自己找 JDK：`-PjdkHome=` > `AOH_JDK` > `JAVA_HOME` > `Program Files\{Microsoft,Java,...}\jdk-*`（取刚好 ≥11 的最小版本），
  所以 **IDEA 里 Gradle JVM 指向 JDK 8 也能构建**（自动 fork JDK 11 的 javac、用 JDK 11 跑 loader 出 dex）。
- 镜像顺序写在 `settings.gradle` 的 `pluginManagement` + `build.gradle` 的 `repositories` 里：
  阿里云 `gradle-plugin` → 阿里云 `public` → 阿里云 `google` → 华为云 → `mavenCentral()`（兜底）→ JitPack（仅 loader 本体）。
  实测：阿里云 gdx 9.3 MB/s、华为 5.6 MB/s、Maven Central 0.2 MB/s；Gradle Plugin Portal 单个 POM 1.9 s，阿里云 0.14 s。
- `gradle.properties` 已开 `daemon / parallel / caching / vfs.watch`、2 GB 堆与 HTTP 超时；增量构建通常 1~4 s。
- Gradle 发行包走腾讯云镜像（`mirrors.cloud.tencent.com`，实测 3.7 MB/s）。
- `dex` 任务的 loader 输出会写 `build\dex\loader.log`（不再直连控制台），后台/管道环境下不会因缓冲区写满而“卡死”。

### IntelliJ IDEA 运行/调试配置

`.run\` 是 IDEA 共享配置目录，打开工程后直接出现在运行配置下拉框：

| 配置 | 类型 | 作用 |
|---|---|---|
| 构建 dex（gradle dex） | Gradle | 跑 `dex` 任务（自动先 `shadowJar`） |
| 调试游戏（桌面 JDWP 5005） | Remote JVM Debug | 先 `StartFinality-jdwp.bat` 启动游戏，再点 Debug 即可在源码断点 |
| 调试游戏（安卓 adb 8700） | Remote JVM Debug | 手机：`adb forward tcp:8700 jdwp:<pid>` 后 Debug（详见 `热插件调试指南_JDWP.md`） |

## 安装

- **桌面(快速调试)**:把 jar 放进 `mods\HotPluginLoader\plugins\`(配 `mod.txt`),双击 `StartFinality.bat`;
- **安卓**:与其它插件一样走补丁管线 —— 用 loader 的 patch 模式出 dex,合并进 APK。
  ⚠ 快速操作插件(长按移动/框选/快捷国策)已迁到 `LoaderSettings\LongPressMoveArmy`,**不再**默认包含在本项目的 patch 配置里
  (`PatchBuild\patch.json` 现在只列 `HotPluginLoader` 一个 jar)。
  如仍需同时启用它:把 `LoaderSettings\LongPressMoveArmy\build\libs\LongPressMoveArmy-*.jar` 加回 `patch.json` 的 `plugins`,
  且**必须放进同一批 patch 构建** —— 否则两个补丁各带一份 `PluginsCollection`,先加载的会顶掉另一个(宿主不会被实例化)。
  合并进 APK(补丁 dex 按编号最前置)后宿主自举生效;
  b12 起补丁 ≈2.9MB(b16 实测 `game.dex.zip` 3,005,837 B,内含单 classes.dex ≈7.4MB / 11,539 类,含内置 D8)。
- **产出补丁 dex**:`gradlew build`(= `shadowJar` + `dex`)一次出齐 jar 与 dex；
  单跑:`gradlew shadowJar`(只出 jar) / `gradlew dex`(只出 dex,复用已有 jar)；
  `build.bat` / `build-dex.bat` 只是双击用的 Gradle 包装。
  dex 任务在 `build\patch\` 里生成临时 `patch.json`(相对路径),产物拷到 **`out\game.dex.zip`**。
  **本工程只负责构建，不向游戏目录写任何文件。**

## 插件目录

- 安卓:`/Android/data/<游戏包名>/files/hotplugins/`(MT 等文件管理器复制 `.dex`/`.zip` 进去,复制而非剪切);
- 桌面:游戏根目录 `hotplugins/`。

## 验证清单(桌面)

1. 启动后进入主菜单:应在「载入游戏」下方看到「插件 / ⚙」一行,编辑器/设置/退出整体下移一行,面板边框随之加高;
2. 把 `HotPluginDemo` 产出的 `demo-plugin.jar + demo-plugin.json` 放入 `hotplugins/` 后重启游戏:
   自动加载成功 → 主菜单弹提示 `Demo hot plugin loaded!`;游戏根目录 `loader.log` 与
   `hotplugins\hotloader.log` 出现 `hotplugin[com.saysky.demo.hotdemo]: ...` 与每 900 帧一次的
   `frame events received: N`;
3. 点「插件」:行显示 `Hot Demo v0.1.0 · 已加载`;点击该行 → 已禁用(事件停止);再点 → 恢复;
4. 错误样例:缺 json → 行显示「缺少清单 …」;`trusted:false` 且未开「允许未校验插件」→「未信任」;
5. 自动化:`..\.tools\verify\HotLoadCheck.java`(`-Xverify:all`,58 类;classpath 需加 `android.jar` + `libs\gdx-android-stubs.jar`)、
   `..\.tools\verify\HotHostTest.java`(48 断言,含 dex/jar/json 子目录、registry.json、同名副本、软重载、dex\\残留迁移)用例)、`..\.tools\verify\D8ConvertTest.java`(22 断言,转换器+dex 解析):全部通过;
6. 补丁 dex 核对(`dexdump -f`):`Button.draw` 000b 调 `hb$onButtonDraw` → `HotBootstrap.onButtonDraw()`;
   补丁共 **11,539 类**(b12 含内置 D8 ≈11,470 + 宿主 58 + 游戏类 4 + LPA 11 + `PluginsCollection`),
   两个启动类替换均含 `onActivityResult`(原版 `AndroidLauncher` + 暮色 `AlternativeLauncher`)。
