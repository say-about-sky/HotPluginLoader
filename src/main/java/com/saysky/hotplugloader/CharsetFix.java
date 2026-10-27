package com.saysky.hotplugloader;

import aoc.kingdoms.lukasz.jakowski.Game;
import com.badlogic.gdx.utils.ObjectMap;

/**
 * 桌面端字形补全:把宿主 UI 文案用到的字符并入当前语言的 charset。
 *
 * <p>背景:游戏用 FreeType 按 {@code Game.lang.get("charset")} 一次性烘焙字形
 * ({@code Renderer.loadFont},桌面端 {@code incremental=false})——
 * <b>charset 里没有的字符,画出来就是空白</b>(如"插"不在中文语言包的 charset 里)。
 * 其他中文模组的自定义文案同样靠把字符写进 charset 来适配(见各语言的
 * {@code Bundle_*.properties} 的 charset 行);这里在运行时做等效的事:
 * 把缺失字符补进当前语言包的属性表,安卓端(增量模式)无影响。</p>
 *
 * <p>时机:AA_Game.create() 里先 {@code Game.loadLanguage()} 再 {@code initGame()}
 * (附加包 init 时机),字体真正烘焙(InitGame 过程)更晚——因此每帧复查一次,
 * 即可稳定先于字体加载完成补丁。</p>
 */
public final class CharsetFix {

    /**
     * 宿主全部界面文案里出现过的字符(已存在的会被跳过)。
     * ⚠ 新增界面文案时,记得把新字符也拼进来。
     */
    private static final String EXTRA_CHARS =
            "附加包" + "加载器设置" + "启用热加载" + "允许未校验附加包" + "详细日志"
                    + "复制附加包目录路径" + "路径已复制" + "刷新" + "关闭" + "返回" + "目录" + "构建" + "·" + "…"
                    // 附加包面板
                    + "未找到附加包文件。放入 .dex/.jar 与同名 .json 后点「刷新」。"
                    + "个文件" + "全部重载" + "点击行:未加载 → 加载;已加载 → 禁用;已禁用 → 启用。"
                    + "安卓端:请放 d8 生成的 .dex(多 dex 用 .zip)。"
                    + "桌面端:请放 .jar(开发调试);.dex 仅安卓可用。"
                    + "附加包 = 外部代码,请自行甄别来源;更新后重启游戏。"
                    + "已加载" + "已禁用" + "已启用" + "未信任" + "未加载" + "错误" + "加载失败"
                    + "已重载: 成功 " + ",失败 "
                    + "正在重启游戏以重载附加包…" + "重载出现异常,正在重启游戏兜底…" + "重载失败,正在重启游戏兜底…"
                    + "原位重载(不重启游戏):退订全部附加包、清空注册表,再重新扫描加载一遍,并补一轮 .jar 转换;同时重扫资源覆盖索引(新文件对新开一局/后续读取生效,已进入内存的启动期资源仍需重启游戏)—— 附加包文件更新后点这里,新版本立即生效;仅在重载出现致命异常时才会自动兜底重启游戏。"
                    // b18:加载状态行 + 进度
                    + "转换中 " + "加载中 " + "队列 " + "D8 运行中" + "写入 / 加载" + "准备"
                    + "就绪(更新附加包后点「全部重载」)" + "重载进行中…" + "重载启动失败(详情见日志)"
                    + "已重载: 成功 " + ",失败 " + "加载耗时: " + " ms" + "进度显示在顶部状态行。"
                    // b20:日志开关 + 行删除
                    + "开启日志" + "启动时刷新日志" + "删除" + "再点一次确认删除: " + "已删除: " + "(共 " + " 个文件)" + "删除失败: "
                    + "全局日志总开关:默认关闭,排障时到设置里开启;关闭后不再写任何日志(hotloader.log / 框架日志镜像 / 启动器诊断)。"
                    + "每次启动清空 hotloader.log,保证日志只含本次会话(默认关闭);关闭则追加写入,超过 512KB 自动清理。"
                    + "移除该模组:本体 + 转换产物 + 同名 json 一并删除;已加载的会先停用;需连点两次确认。不可恢复。"
                    + "行尾按钮:删除该模组(本体 + 转换产物 + 同名 json;连点两次确认)。"
                    // b21/b22:加载顺序(按住滑动调整)
                    + "点击行=加载/禁用/启用;按住行上下滑动=调整顺序。"
                    + "按住行上下滑动:调整加载顺序(滑得越快、换位越快)。"
                    + "顺序已更新(下次加载生效)"
                    // b23:删除按钮用文字 ×(不依赖贴图留白)
                    + "×"
                    // b24:行卡片改「加载器设置」同款变色开关(绿=已加载、红=未加载)
                    + "色条:绿=已加载、红=未加载。点击行=加载/禁用/启用。"
                    + "按住行上下滑动=调整顺序。"
                    + "行首色条:绿=已加载、红=未加载;其余状态(已禁用 / 未信任 / 错误)显示在行内;"
                    // b26:刷新保留滚动位置 + 可滚动列表按住排序(提示文案)
                    + "按住行上下滑动=调整顺序(列表可滚动:先按住不动再拖)。"
                    + "按住行上下滑动:调整加载顺序(滑得越快、换位越快;列表可滚动时先按住不动约半秒再拖)。"
                    + "已被较新的同名副本替代(保留 "
                    // 设置面板
                    + "启动后自动加载附加包目录中的附加包(下次启动生效)。"
                    + "关闭时:仅加载清单中 trusted:true 的附加包。"
                    + "输出加载过程的额外日志(调试用)。"
                    + "已加载附加包: "
                    // 清单错误文案
                    + "缺少清单 " + "清单读取失败" + "清单 JSON 格式错误" + "清单 JSON 必须是对象"
                    + "清单缺少 id" + "id 含非法字符(仅限字母数字._-)" + "清单缺少 mainClass" + "清单错误"
                    // 加载错误文案
                    + "id 与 " + " 重复" + "未信任(开「允许未校验附加包」或清单 trusted:true)"
                    + "主类未实现 HotPlugin 接口,且没有 public init()"
                    + "安卓端不能加载 .jar(请用 d8 转成 .dex)"
                    + "桌面端无法加载 .dex(开发调试请用 .jar;.dex 仅安卓可用)"
                    + "桌面端不支持 .zip 附加包(请用 .jar)"
                    + "zip 内没有 classes*.dex" + "未知错误"
                    // 附加包界面 v2:分隔栏「模组列表」+ 精简提示 + 悬停提示
                    + "模组列表" + "加载目录" + "点击行 = 加载 / 禁用 / 启用;放入文件后点「刷新」。"
                    + "把加载目录里所有未加载的模组依次加载;已加载 / 已禁用的行会跳过。"
                    + "复制目录路径" + "把加载目录的完整路径复制到剪贴板;粘贴到文件管理器可直接打开。"
                    + "刷新列表" + "重新扫描加载目录(含 dex / json 子目录);把文件放入后点击这里。"
                    + "附加包 = 外部代码,请自行甄别来源。"
                    + "行尾状态:已加载 / 已禁用 / 未信任 / 错误;禁用会记入 registry.json;更新附加包文件后建议重启游戏。"
                    + "状态" + "文件" + "未找到模组文件。放入 .dex/.json 到目录后点「刷新」。"
                    // 导入(SAF 文件选择器)
                    + "导入附加包文件" + "打开系统文件管理器选择附加包文件(可多选),自动复制进加载目录。"
                    + "安卓专用;桌面端直接把文件放进加载目录即可。" + "桌面端请直接把文件放入 hotplugins 目录"
                    + "当前环境不支持打开系统选择器" + "已导入 " + " 个文件到加载目录" + "(失败 " + ")"
                    + "导入失败,请检查文件是否可读" + "提示: .jar 需转换为 .dex 后才能加载(见列表状态)"
                    // 转换器(b5)
                    + "D8 转换器" + "已开始转换 .jar …"
                    + "当前没有转换器,.jar 需转换为 .dex 后才能加载(安装「D8 转换器」或使用 PC 脚本)"
                    + "转换失败: " + "(详情见日志)" + "已转换并加载: " + "已转换: " + "(点击列表行加载)"
                    // b8:安卓 .jar 行引导(b12:转换器已内置)
                    + "jar 已转换为 " + ",请点击同名 .zip 行加载"
                    + "已开始转换 .jar …(完成后点「刷新」查看)"
                    + " · 未转换(点行转换)" + " · 已转换(点同名 .zip 行)"
                    + "已内置转换器(请删除本文件)"
                    + ";加载失败: "
                    // b9:单个 .dex 自动生成清单
                    + " · 待生成清单(点行)" + "自动生成清单失败: "
                    + "没有 HotPlugin 实现类(旧式框架插件请提供 json 或经「D8 转换器」)"
                    + "dex 里没有类" + "不是有效的 dex 文件(magic 不符)"
                    // 用户 UI 修订(b2 后的补充)
                    + "每帧扫所有实例" + "启动后自动加载目录中的附加包(下次启动生效)。";

    /** 上次读到的有效 charset(引用比较,未变化直接短路)。 */
    private static Object lastValue = null;
    /** 已写入过的值(防"被更外层语言包遮蔽"时每帧空转)。 */
    private static String pushedValue = null;

    private CharsetFix() {
    }

    /** 每个渲染帧调用一次(开销:一次 map 读取 + 引用比较)。 */
    @SuppressWarnings("unchecked")
    public static void ensure() {
        try {
            String cur = Game.lang == null ? null : Game.lang.get("charset");
            if (cur == null || cur.equals("charset") || cur.charAt(0) == '[') {
                return; // 未就绪(get 未命中会回退返回 key;翻译键调试模式返回 "[key]")
            }
            if (cur == lastValue) {
                return; // 未变化
            }
            lastValue = cur;

            boolean missing = false;
            for (int i = 0; i < EXTRA_CHARS.length(); i++) {
                if (cur.indexOf(EXTRA_CHARS.charAt(i)) < 0) {
                    missing = true;
                    break;
                }
            }
            if (!missing) {
                return; // 字符已齐(补过,或该语言文件本身够全)
            }

            Object bundle = ReflectUtil.get(Game.lang, "bundle");
            Object mapObj = bundle == null ? null : ReflectUtil.get(bundle, "properties");
            if (!(mapObj instanceof ObjectMap)) {
                return;
            }
            ObjectMap<String, String> map = (ObjectMap<String, String>) mapObj;

            StringBuilder sb = new StringBuilder(cur.length() + EXTRA_CHARS.length());
            sb.append(cur);
            for (int i = 0; i < EXTRA_CHARS.length(); i++) {
                char c = EXTRA_CHARS.charAt(i);
                if (cur.indexOf(c) < 0) {
                    sb.append(c);
                }
            }
            String patched = sb.toString();
            if (patched.equals(pushedValue)) {
                return; // 写过但有效值没变(被 mod 语言包遮蔽),不再重复
            }
            pushedValue = patched;
            map.put("charset", patched);
            SafeLog.i("CharsetFix: charset +" + (patched.length() - cur.length())
                    + " chars, now " + Game.lang.get("charset").length());
        } catch (Throwable t) {
            SafeLog.e("CharsetFix failed", t);
        }
    }
}
