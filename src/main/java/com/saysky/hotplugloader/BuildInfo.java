package com.saysky.hotplugloader;

/**
 * 构建信息 / 调试开关。
 *
 * <p>真机排查时,面板与日志里带上 BUILD_TAG,才能确认"跑的是哪一版"
 * (见开发指南 §10.8c)。</p>
 */
public final class BuildInfo {

    /** 构建标记(显示在面板/日志里)。b2 = 多实例挂载 + 可见性修复版;b3 = 清单 json 自动生成(注解);
     *  b4 = 「导入」文件选择器(整类替换 AndroidLauncher 接管 onActivityResult);
     *  b5 = 「D8 转换器」接驳:导入 .jar 后自动转 dex + 生成清单并加载;
     *  b6 = 导入回传诊断(launcher.log 生命周期轨迹 + 替换生效检测);
     *  b7 = 导入回传修复:补整类替换暮色真启动类 team.rainfall.sternstunden.AlternativeLauncher;
     *  b8 = 面板 .jar 行引导(未装转换器时明确提示;已转换则指向同名 .zip 行);
     *  b9 = 单个 .dex 无清单时自动解析生成 json(安卓;桌面无法加载 dex);
     *  b10 = hotplugins/dex + json 子目录分类(导入/转换/自动清单全部自动归类,根目录仍兼容);
     *        registry.json 注册配置管理启用/禁用(禁用 = 下次启动不加载、不实例化,面板开关同步写回);
     *  b11 = 「全部重载」按钮(回车退订+软重载;安卓优先重启游戏回到加载画面,重启后自动清状态重载)、
     *        导入同名文件就地覆盖 + 同名新副本自动替代旧副本(修更新后旧副本占用 id 的问题);
     *  b12 = D8 转换器并入宿主(加载器 = 一个库:转换+加载同随补丁 dex 发布;不再需要单独安装转换器;
     *        旧 d8converter 文件加载时提示删除;jar 行点按可直接触发转换);
     *  b13 = 转换产物原子落位(失败的转换不留残渣/孤儿 zip)+ 失败记忆(文件没变不重试、不重复弹错)
     *        + jar 行「已转换」以产物+清单齐备为准;
     *  b14 = 「全部重载」改整进程重启(闹钟自拉起 + killProcess)——同进程 recreate 会复用失效
     *        GL 资源/静态状态 → 游戏纯色蓝屏(真机实锤);失败退还软重载;
     *  b15 = 重启链路升级(优先 setAlarmClock,逐级降级 + 通道诊断日志);
     *        dex\ 只放 .dex(新增 jar\ 归档 .jar/.jar.done/.zip,dex\ 遗留件启动自动搬运);
     *        导入写失败自动删除半截文件;
     *  b16 = jar 转换直出 dex(单 dex 直接落 dex\<同名>.dex,不再产 zip;成功后源 jar 删除、
     *        不留档;多 dex 极少见时退回 zip 方案;旧 zip/.done 自动清理);
     *  b17 = 「全部重载」改**原位整层重载**(不再重启进程,免去重跑 InitGame 150+ 步启动加载
     *        流水线):退订清表 → 重扫重载 → 补一轮 .jar 转换;仅重置/扫描出现致命异常才兜底整进程重启;
     *  b18 = **加载进度可视化**(仿 InitGame 分帧状态机):「全部重载」队列化(≈10ms/帧推进)+
     *        面板状态行(加载 3/7、转换队列 n/m、D8 运行秒表、结果留痕)+ 自绘进度条(加载=精确比例,
     *        转换=扫动段,不做假百分比);ConverterService 阶段上报;模组行悬停新增「加载耗时」;
     *  b19 = 单插件故障隔离加固:autoLoad/reloadAll 逐文件 try/catch(坏插件不再可能中断其余)、
     *        HotEvents 监听者失败日志限流(前 3 次 + 每 30s 一条,防每帧刷屏)、hotloader.log
     *        错误带堆栈(截断 12 帧 + 2 层 cause);失败条目 = ERROR(本次会话不实例化,面板显原因);
     *  b20 = 设置面板新增「开启日志」(全局日志总开关)与「启动时刷新日志」(每次启动清空
     *        hotloader.log);模组行缩宽 + 行尾删除按钮(连点两次确认;删除本体 + 转换产物 + 同名 json);
     *  b21 = 加载顺序可调:registry.json 新增 order 数组(scan 按其排序,列表与加载同源);按住行上
     *        下滑动 = 按位移换位(松手落定;仅限不可滚动列表);
     *  b22 = 取消长按弹菜单(滑动为唯一调整方式);换位速度改由**手指实时位移**驱动
     *        (每滑动一个行距 = 换一格,停手即停;单帧最多 3 格);
     *  b23 = 删除按钮改文字「×」(ButtonGame2 居中文字;放弃 btnh_close 贴图 —— 其画布留白不对称
     *        导致图标偏移,且不同整合包资源变体尺寸不一);
     *  b24 = 模组行卡片改「加载器设置」同款变色开关(ButtonGame2 + checkBox,原生红/绿色条):
     *        绿=已加载、红=未加载;行内不再重复「· 已加载」,其余状态文字保留;
     *  b25 = 进度条观感修正:条高 8 → 16px、金色边框/轨道提亮(空闲也清晰可见)、填充内缩自适应;
     *        与状态行拉开 6px 间距(防「已重载:…」文字下沿遮条);
     *  b26 = 「刷新保留滚动位置」(重建面板时接力菜单滚动偏移 iMenuPosY)+ 可滚动列表重新支持
     *        拖动排序(按住不动 450ms 进入;进入后禁掉本次手势的列表滚动,松手提交);
     *  b27 = 修复「开启日志」对启动器诊断不生效:替换版启动类(AndroidLauncher/AlternativeLauncher)
     *        的日志并入 hotloader.log 并受总开关门控(直读同一份 SharedPreferences,启动早期也准确);
     *        旧 launcher.log 废弃并自动清理;
     *  b28 = 「底层资源覆盖接口」: ResourceOverride 装饰 FileManager.LoadInterface(原模组机制原样保留:
     *        桌面创意工坊/mods → 安卓 local→assets),hotplugins/override/ 镜像游戏路径的资源顶替原文件;
     *        安装 = 启动类 onCreate 起后台线程,ColdBootstrap 等 loadInterface 就位后换装
     *        (AA_Game.create 第一条指令之后即刻生效);热插件可 API 注册/撤销覆盖;
     *  b29 = 「最早接口直挂」:整类替换 FileManager$3(游戏 initLoadInterface 直接实例化的安卓实现),
     *        构造函数内建立覆盖层——从游戏代码第一条指令起覆盖层就在位(零等待零竞态);
     *        逐字复刻框架版行为(FontFix.isLocalStorage 开关 + Steam 模组链 + external/local/internal),
     *        无框架时退原版 local→internal;线程/每帧兜底保留;
     *  b30 = 「冷加载预载」:热插件扫描/转换触发/加载提前到冷加载阶段、在后台线程完成(ColdTick
     *        覆盖整个加载屏;StartupLoader 干活)——进主菜单不再有同步加载的卡顿;预载进度写进
     *        游戏加载文案(InitGame.loadingName,框架版为静态字段,反射直写);转换产物改在转换
     *        线程直接加载(不再回主线程,只有 toast/刷新走 post);「启动时刷新日志」改在启动器
     *        最早时机执行——启动器轨迹 + FileManager$3 直挂日志不再被首帧 init 清掉;
     *        注意:插件 init 现运行于冷加载期间的后台线程(勿在 init 里直接操作 UI,用 postRunnable);
     *  b31 = 「加载屏阶段预载」:实测 b30 预载在启动图(logo)阶段就跑完、加载屏上什么都看不到
     *        (该阶段 loadingName 不会被绘制)—— 改为等加载屏出现(InitGame.splash_id < 0)
     *        后才点火预载;进度写两条静态通道:接管底部提示语行 Renderer.sLoadingText
     *        (游戏每 4s 刷新提示语,预载期间我们每帧写回)+ 步骤行 loadingName(保留游戏文字
     *        追加后缀);完成后结果再展示约 4 秒;启动图阶段不动任何文字。
     *  b32 = 「静默后台预载」:真机实证——进度条其实在卡图标阶段就在后台推进,等可见时已近尾声,
     *        「做进加载屏」没有意义;按要求把加载<b>往后移</b>:冷加载全部结束后(首个 Button.draw)
     *        才在后台线程静默加载;彻底移除所有文字注入(sLoadingText / loadingName 均不再触碰)、
     *        不再显示任何消息;demo 插件的加载 toast 一并移除;ColdTick 类删除。
     *  b33 = 「冷加载后期点火」:按要求「相对于冷加载期间后移」—— b30 太早(第一步)、b32 太晚
     *        (主菜单后):恢复冷加载期点火,但起点 = <b>启动图阶段结束/加载屏出现</b>
     *        (InitGame.splash_id < 0,即冷加载后期);依然全程静默(不显示任何消息、
     *        不替换任何游戏文字);字段不可读时等 ~4-6s 再起步(确保不是第一步),
     *        主菜单首帧兜底点火;
     *  b34 = 「图标阶段内起步」:实测 b33 起点太晚——图标褪去时游戏已到 Loading Scenario 收尾;
     *        查清图标逻辑(每张 logo 显示时长 = 配置 duration,固定,与加载进度无关;
     *        流水线在整个图标阶段后台推进)→ 改为冷加载开始约 2s 后(仍在图标阶段内、
     *        远早于褪去)静默点火预载;
     *  b35 = 「跳过启动图」(用户提议:图标持续时间=0):ColdTick 每帧把 InitGame.splash_id
     *        强制置 -1(游戏自身的"切加载屏"信号)→ 启动直达加载屏、立刻看到进度条,
     *        不再有"卡图标"与"褪去时机"问题;安全门槛 = 等 InitGame.background != null
     *        (流水线第 0 步 loadBackground 已跑)后才切;预载仍冷加载约 2s 后静默点火;
     *        恢复图标 = 把 ColdTick.SKIP_SPLASH_ICON 改 false 重新出 dex;
     *  b36 = 「起步调回最前」:按要求把模组冷加载调回最前面 —— START_DELAY_MS=0,
     *        冷加载一开始(第一步)就在后台点火预载(配合 b35 的跳过图标:启动即进度条+后台加载);
     *  b37 = 「详细日志默认输出 + 冷加载隔离对齐」:移除设置面板「详细日志」开关(SafeLog.v 默认输出,
     *        LoaderConfig.verboseLog/setVerboseLog 删除);冷加载(autoLoad)故障隔离核对 =
     *        与「全部重载」同款(逐文件 try/catch 忽略、失败条目不实例化、会话内不重试、面板显原因),
     *        并补齐统计口径(LOADED=成功;DISABLED=跳过;其余含 BLOCKED=失败)+ 加载前 isFile 检查;
     *  b38 = 「加载屏显示插件加载」:预载窗口内在加载屏文字行显示 "Loading hotplugins  <当前文件>"
     *        (与游戏 "Loading ..." 同风格、位于加载序列最前;含转换队列);窗口结束还原游戏文字
     *        (仅在当前值仍为我们的注入时才写回,防覆盖游戏新阶段文字);
     *  b39 = 「resources 统一资源目录」:override/ 与插件私有资源职能整合为
     *        hotplugins/resources/<插件名>/(镜像游戏路径 = 覆盖游戏资源;其余 = 私有资源/数据);
     *        ctx.dataDir() 指向该目录;多插件命中同路径按加载顺序取先;插件被禁用时其资源树不生效
     *        (加载/启用/禁用/删除插件时实时切换;删除插件连带删除资源目录);旧 override/ 弃用并提示迁移;
     *  b40 = 「resources 结构修正」:游戏覆盖资源必须放在 {@code resources/<插件名>/override/} 子目录内
     *        (镜像游戏路径;先者优先按加载顺序);resources/<插件名>/ 下 override/ 之外的文件
     *        = 插件私有资源/数据,**不参与覆盖**;
     *  b41 = 「APK 内嵌 hotplugins 首启播种」:发行版 APK 根目录里带 hotplugins/ 时,首次启动
     *        把它提取到 files/hotplugins(同名覆盖、多余文件保留),随后走既有检测/补全;完成后
     *        写标记 .apk_seed_done —— 之后每次启动直接跳过,绝不刷新用户数据(删标记/清数据可
     *        重新播种);空/无内容不写标记,以后换成带内容的 APK 仍可播种;
     *  b42 = 「日志开关默认关闭」:「开启日志」「启动时刷新日志」两开关默认值 true → false
     *        (LoaderConfig + 两个启动类直读 SharedPreferences 的兜底默认同步改;面板悬停说明同步);
     *        需要排障时到「加载器设置」里打开即可;日志文件逻辑本身不变;
     *  b43 = 「全部重载一并重扫资源覆盖」:按钮点击时(LoadQueue 与 reloadAll 两条路径)先调
     *        ColdBootstrap.rescanResources() 重扫 resources/插件名/override/ 索引 —— 运行中新增/
     *        改动的文件对"之后的读取"(新开一局、按需读取的音乐等)立即生效;已进入内存的
     *        启动期资源(贴图/JSON/字体)仍需重启游戏(引擎缓存模型,见交付说明);
     *  b44 = 「插件便捷 API」: api 包新增 HotFiles(纯 JDK 文件工具:原子读写/复制/递归删/
     *        目录拼接,失败返回 null/false 不抛)与 HotConfig(扁平 JSON 配置助手:def 默认值 +
     *        缺文件自动生成模板 + 宽容解析(注释/尾逗号) + 未知键原样保留 + lastError 查询)。
     *        旧宿主上不存在这两个类:插件最低宿主版本 = b44(可用 ctx.hostVersion() 判断);
     *  b45 = 「JDWP 调试支持」:启动最早期写 debug: debuggable/connected/waitMarker 状态;
     *        存在 hotplugins/.wait_debugger 时轮询等待调试器接入(默认上限 180s,文件内容可写
     *        秒数;删除即取消)—— 冷加载/预载/插件 init 也能断到;桌面新增 StartFinality-jdwp.bat
     *        (端口 5005);完整用法见「热插件调试指南_JDWP.md」;
     *  b46 = 「注解调试点」:api 新增 `@HotDebug` 注解(标 public 方法;参数最多 2 个且仅自动转换
     *        String;static 或主类实例方法)→ 插件加载时自动登记(日志列出,便于 JDWP 方法断点);
     *        HotContext 新增 logEnabled()(ctx.log 本就受总开关控制);
     *  b47 = 「调试点改自动执行」(按用户要求去掉 .debug_cmd/debug.out 文件通道):插件加载后
     *        (且「开启日志」打开时)宿主自动执行一次全部可执行调试点(无参,或注解 args={…}
     *        提供参数;不足跳过),结果/异常直接写日志(`hotplugin[<id>]: [debug] <名> -> OK/EX…`,
     *        与 ctx.log 同款)—— 改代码 →「全部重载」→ 看 hotloader.log 即可验证某个方法;
     *  b48 = 「预载让路冷加载」:核对确认"冷加载与预载并行"**不存在文件层资源争夺**
     *        (覆盖索引在游戏第一次读文件之前一次建好、之后只读;两边读互不相同的文件;
     *        ResourceOverride 全部方法 synchronized)——真正争的只有 CPU/带宽;
     *        故预载线程与 D8 转换线程降为 Thread.MIN_PRIORITY,渲染线程优先,进度条不被拖慢;
     *  b50 = 「插件」改名「附加包」:游戏内可见文案(主菜单按钮 / 面板标题与按钮 / 悬停说明 /
     *        状态行 / 错误提示 / registry.json note)与源码注释统一改为「附加包」;CharsetFix
     *        字形表同步(新增 附/包 二字);英文标识符、hotplugins/ 目录名、日志标签 hotplugin[...]、
     *        加载屏 "Loading hotplugins" 保持不变(兼容性);指代 Finality 旧式框架附加包的措辞保留。
     *  b53 = 本批三项:①「附加包」界面文案「模组目录」→「加载目录」;②hotplugins 目录规范化 ——
     *        构建期 `HotPackCheck` 严格校验(<名>.dex ↔ json/<名>.json ↔ resources/<名>/ 一一对应,
     *        HotManifestGen 同步校验主名),运行期 scan/重载自动清理 json/ 孤儿清单(资源目录只提示);
     *        ③hotpluginsUI.json(与 hotplugins 同级):Display=false 默认不生效;true 时可用 absolute
     *        屏幕坐标或 snap 吸附(元素文本 / LoadGame / Editor / @settings / @plugins + 上下左右 + 像素微调)
     *        摆放「设置」与「附加包」两个主菜单按钮。 */
    public static final String BUILD_TAG = "HotLoader v0.2.0b53";
    /** 调试输出开关(编译期常量,false 时 javac 会把分支整段消除)。 */
    public static final boolean DEBUG_OUTPUT = true;

    private BuildInfo() {
    }
}
