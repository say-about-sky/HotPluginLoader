# 热插件调试指南(JDWP 远程调试)

> 版本:b45 起随宿主提供。目标:在**不重新编译游戏**的前提下,对热插件(以及加载器、游戏本体)
> 下**真正的断点**、单步、看变量 —— 不再只靠手动加日志。
> 原理:Java 调试走 **JDWP**(Java Debug Wire Protocol)。ART 在**可调试(debuggable)的应用**里
> 自带 JDWP 服务,adb 把它转发给 PC,PC 上的 IDE / `jdb` 即可 attach —— 断点对**整个进程**生效
> (热插件、加载器、游戏类都能断)。

---

## 一、桌面(最简单,推荐日常用这个)

桌面版游戏就是一个普通 JVM,热插件的 `.jar` 由 URLClassLoader 直接加载 —— **源码级断点**没有任何门槛。

1. 双击 **`StartFinality-jdwp.bat`**(游戏根目录;端口 5005,`suspend=n`)启动游戏;
   - 想连"启动最早期的代码"再改成 `suspend=y`(启动即暂停等你 attach)。
2. IDE(IntelliJ IDEA / Android Studio)新建 **Remote JVM Debug** 配置:
   Host `localhost`、Port `5005`,attach。
3. **对插件源码工程下断点**即可(类名+行号对应;编译时建议 `javac -g`,局部变量才可见)。
   加载器/游戏类的断点同样可用(游戏源码可挂 `.tools\src\missions` 反编译树)。
4. 改完插件代码:重新构建 jar → 放进 `hotplugins/` → 游戏内点「全部重载」→ **新代码生效、断点继续命中**
   (重载=新类加载器,IDE 里旧类的断点失效属正常,按新类重新命中断点)。

## 二、安卓(同样支持,前提是 APK 可调试)

1. **把测试 APK 改成 debuggable**(只做测试包,别发布):
   - MT 管理器 → APK 编辑 → `AndroidManifest.xml` → 加 `android:debuggable="true"`(或去掉 `debuggable="false"`);
   - 重新签名、安装。
2. 启动游戏(可以正常冷启动,游戏本身不变慢多少;attach 后才会明显变慢,见"注意事项")。
3. PC 上(adb 已连接):
   - `adb shell pidof <包名>`(如 `age.of.history3.polaris`)拿到进程号;
   - `adb forward tcp:8700 jdwp:<pid>`;
4. IDE 新建 **Remote JVM Debug**(Host `localhost`、Port `8700`)attach;
   或命令行用 JDK 自带的 `jdb`:
   ```
   jdb -attach localhost:8700
   stop at com.example.myplugin.MyPlugin:123
   locals
   print someField
   ```

### 断到"启动最早期"(冷加载/预载/插件 init)

attach 是"之后才生效"的 —— 想断到**游戏启动最初几秒**的代码(APK 播种、资源树建立、
冷加载预载、插件 `init()`),用 **等待标记**:

- 在 `Android/data/<包名>/files/hotplugins/` 放一个文件 **`.wait_debugger`**
  (内容可写秒数,如 `300`;留空 = 默认上限 180 秒);
- 游戏启动时会在一切加载器逻辑**之前**轮询等待调试器接入(启动日志会写
  `debug: debuggable=… connected=… waitMarker=true -> waited …ms`);你在这段时间里 attach,
  随后**所有后续代码**(包括冷加载预载、插件加载)都可断;
- 删掉 `.wait_debugger` 即恢复正常秒开;超时(默认 180s)也会照常继续,不会卡死。

> 备选(标准做法,不用我们的标记):`adb shell am set-debug-app -w --persistent <包名>`
> —— 每次启动都等调试器;取消:`adb shell am clear-debug-app`。

### 确认能不能 attach(排障第一步)

启动日志第二行就是诊断:
`debug: debuggable=true/false connected=true/false waitMarker=true/false`
- `debuggable=false` → manifest 没改成功(或装的是发布包):attach 永远失败;
- `debuggable=true` 但 IDE 连不上 → 检查 `adb forward` 的 pid 是否是**当前**进程(重启后 pid 会变,需要重新 forward)。

## 三、断点怎么下(两个端通用)

- **断点按「类名 + 行号」匹配**:插件 jar 由 javac 编译(带行号/源文件名),D8 转 dex 后**行号信息保留**,
  所以源码级断点有效;建议编译加 `-g`(局部变量表),调试时能看 `locals`;
- 类还没被加载时下断点没问题(命中前会一直挂着);已加载类的断点在「全部重载」后**重新下**
  (重载 = 新的类加载器/新的类对象);
- 游戏类没有源码也能断(会显示反汇编/无源码),有反编译源码更舒服。

## 四、注意事项 / 边界

- **attach 后整进程变慢**(ART 会关闭部分优化、断点全线程暂停):别把断点下在每帧都跑的热路径
  (如 FRAME 事件、draw),否则像幻灯片;调试完 IDE 里 detach(别 kill);
- **debbuggable 只给测试包**:发布包必须是 `debuggable="false"`,否则有安全/性能影响;
- 想改**已加载类**的代码 = 不支持(ART 无热替换):插件 → 重新构建 + 「全部重载」;
  游戏类 → 重新出补丁 dex + 重启;
- JDWP 只能"暂停/观察/求值",**替换不了资源**(贴图/JSON 见 `资源覆盖接口使用帮助.md`:文件层改动靠重启,
  索引层改动点「全部重载」);
- 联机/联网校验场景勿用调试包。

## 五、工具速查

| 场景 | 做法 |
| --- | --- |
| 桌面调试 | `StartFinality-jdwp.bat` → IDE attach `localhost:5005` |
| 安卓调试 | APK 开 debuggable → `adb forward tcp:8700 jdwp:<pid>` → IDE/jdb attach |
| 断启动最早期 | `hotplugins/.wait_debugger`(可写秒数)或 `am set-debug-app -w` |
| 查 attach 条件 | 启动日志 `debug: …` 行;`adb shell pidof <包名>` |
| 无 IDE 快速看 | `jdb -attach localhost:8700`;`stop at 类:行`、`where`、`locals`、`print 变量`、`cont` |

---

## 六、注解调试点(b46;b47 起自动执行,输出进日志)

插件里给方法标一行注解,加载后宿主会**自动执行它一次**,结果直接写日志 —— 不用放任何文件:

```java
@HotDebug(value = "testBuy", desc = "测试购买流程")
public static void testBuy() { ... }              // 无参:加载后自动跑

@HotDebug(args = { "sword", "3" })                // 有参数:注解里给默认参数
public static void testBuy(String item, String n) { ... }
```

- **登记**:插件加载时自动登记并写一行 `debug point <插件id>.testBuy -> 类#方法`(便于 JDWP 方法断点);
- **自动执行**:加载(含「全部重载」)后,**仅当「开启日志」打开时**执行一次 —— 无参方法直接跑;
  有参数的用 `args = {...}` 提供(不足则跳过并记一条 skip 日志);有副作用的方法慎用;
- **输出**(与 `ctx.log` 同款,在 `hotplugins/hotloader.log`):
  `hotplugin[<插件id>]: [debug] testBuy -> OK(12ms): 返回值` 或 `EX: …`(异常带栈);
- **配合 JDWP**:在方法里下断点 → 加载/「全部重载」时的自动执行会带你命中一次;
- **约束**:`public`;参数 ≤ 2 个且仅自动转换 `String`;`static` 或主类实例方法;
  每次加载/重载自动重扫,禁用/删除自动注销;开发期功能,发布前删掉注解。
