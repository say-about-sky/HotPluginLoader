package com.saysky.hotplugloader;

import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import aoc.kingdoms.lukasz.jakowski.CFG;
import aoc.kingdoms.lukasz.jakowski.Game;
import aoc.kingdoms.lukasz.menu.Menu;
import aoc.kingdoms.lukasz.menu_element.MenuElement;
import aoc.kingdoms.lukasz.menu_element.button.ButtonGame2;
import aoc.kingdoms.lukasz.menu_element.button.ButtonGame2_IMG;
import aoc.kingdoms.lukasz.textures.Images;
import com.saysky.hotplugloader.ui.LoaderSettingsPanel;
import com.saysky.hotplugloader.ui.PluginScreen;

/**
 * 把「附加包 / 设置」一行按钮注入主菜单(MainMenu)的菜单元素表。
 *
 * <p>全部运行时完成,不 Mixin 任何游戏类:</p>
 * <ol>
 *   <li>用 {@code Gdx.app.postRunnable} 每帧重试,直到游戏的菜单表构建完毕;</li>
 *   <li>每帧扫描所有视图的菜单,<b>所有</b>主菜单实例都挂(安卓真机:菜单会被重建、
 *       可能同时存在多个实例;新实例补挂、元素表被重建的实例重挂);识别优先按类名
 *       {@code aoc.kingdoms.lukasz.menus.MainMenu},兜底按「载入游戏 + 编辑器」文案;</li>
 *   <li>在其元素表里、「载入游戏」与「编辑器/设置」之间插入一行:
 *       「附加包」按钮(80%)+ 齿轮图标按钮(20%);</li>
 *   <li>把编辑器及以下的元素整体下移一行,并把主菜单面板高度加高一行
 *       (反射其私有字段,失败则跳过加高);</li>
 *   <li>把两个新按钮登记进主菜单的滑动动画列表(反射;失败仅少一段动画)。</li>
 * </ol>
 *
 * <p>坐标语义:主菜单 {@code initMenu(null,0,0,GW,GH,...)},其元素坐标即屏幕绝对坐标,
 * 因此直接沿用锚点元素的坐标系插入即可。</p>
 *
 * <p>调用时机:由 {@link HotLoaderTick} 每帧调用 {@link #tick()}(本类不再自己接力循环)。</p>
 */
public final class MainMenuMount {

    private static final String TAG = "HotLoader/mount";

    private static boolean firstTickLogged = false;

    /**
     * 已挂载的主菜单实例 → 该实例上的两个按钮(身份表)。
     * 安卓真机教训:主菜单会被重建、且可能同时存在多个实例 —— 必须"每帧扫所有实例":
     * 没挂过的立即补挂;挂过但元素表被重建的重新挂。只盯一个实例会让重建后的
     * 新主菜单永远没有按钮(2026-10-13 修复)。
     */
    private static final IdentityHashMap<Menu, MenuElement[]> PATCHED =
            new IdentityHashMap<Menu, MenuElement[]>();
    private static boolean everMounted = false;

    /** 状态签名(主菜单实例集合 + 活动菜单);变化时打印一行日志,便于真机定位「挂错实例/换实例」。 */
    private static String lastStateSig = "";

    /** b51:帧计数(用于兜底扫描/状态日志限流,避免每帧做重活)。 */
    private static long tickCount = 0L;

    private MainMenuMount() {
    }

    /** 是否已至少成功挂载过一次(自动加载的时机参考)。 */
    public static boolean isMounted() {
        return everMounted;
    }

    /** 游戏重启(新 GDX 实例):重置会话级状态;旧实例的 PATCHED 身份表已无意义,清掉。 */
    public static void onNewSession() {
        firstTickLogged = false;
        everMounted = false;
        lastStateSig = "";
        PATCHED.clear();
    }

    /** 当前主菜单面板的左边缘(其私有 iXPos;其它面板借它定位到主菜单左侧);无实例时 -1。 */
    public static int mainMenuPanelLeft() {
        try {
            for (Menu m : PATCHED.keySet()) {
                Integer v = ReflectUtil.getOwnInt(m, "iXPos");
                if (v != null && v > 0) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 每帧调用一次(轻量;已挂载时只做一次元素自检)。 */
    public static void tick() {
        try {
            tickCount++;
            if (!firstTickLogged) {
                firstTickLogged = true;
                SafeLog.i(TAG + ": watching for main menu ...");
            }
            ensureMounted();
            // b51:状态签名要拼字符串/反射,每 32 帧看一次且仅在开启日志时（关日志时 SafeLog 也会丢弃）
            if ((tickCount & 31L) == 0L) {
                logStateIfChanged();
            }
        } catch (Throwable t) {
            SafeLog.e(TAG + ": tick failed", t);
        }
    }

    private static void ensureMounted() {
        List<List<Menu>> all = allMenus();
        if (all == null) {
            return;
        }
        // 第一轮:按类名处理所有主菜单实例(安卓上可能同时存在新旧多个实例)
        boolean foundNamed = false;
        for (List<Menu> view : all) {
            if (view == null) {
                continue;
            }
            for (Menu m : view) {
                if (m == null || !"aoc.kingdoms.lukasz.menus.MainMenu".equals(m.getClass().getName())) {
                    continue;
                }
                foundNamed = true;
                handleInstance(m);
            }
        }
        if (foundNamed) {
            return;
        }
        // b51:兜底扫描要遍历每个菜单的每个元素做文本比较(游戏内每帧几十~上百个菜单 = 纯浪费),
        // 改成每 8 帧一次 —— 主菜单出现后最多 0.13s 内就能识别到。
        if ((tickCount & 7L) != 0L) {
            return;
        }
        // 第二轮(兜底):类名对不上时按「载入游戏 + 编辑器」文案识别(仅在前一轮空手时做)
        String loadGame = textOfLang("LoadGame");
        String editor = textOfLang("Editor");
        if (loadGame == null || editor == null) {
            return;
        }
        for (List<Menu> view : all) {
            if (view == null) {
                continue;
            }
            for (Menu m : view) {
                if (m != null && findElementByText(m, loadGame) != null && findElementByText(m, editor) != null) {
                    handleInstance(m);
                }
            }
        }
    }

    /** 单个主菜单实例:未挂过 → 挂;挂过但元素表被重建 → 重挂。 */
    private static void handleInstance(Menu menu) {
        MenuElement[] row = PATCHED.get(menu);
        if (row != null) {
            List<MenuElement> elements = elementsOf(menu);
            if (elements != null && elements.contains(row[0]) && elements.contains(row[1])) {
                // 每帧把「我们的按钮」钉为可见:游戏在位置/分辨率变化时会全量重算元素可见性
                // (时机不巧会把它们一起标成不可见),这里直接自愈。
                try {
                    row[0].setIsInView(true);
                    row[1].setIsInView(true);
                } catch (Throwable ignored) {
                }
                return; // 仍完好
            }
            PATCHED.remove(menu);
            SafeLog.i(TAG + ": main menu elements rebuilt, re-mounting (id="
                    + System.identityHashCode(menu) + ")");
        }
        patch(menu);
    }

    /** 状态变化诊断日志:主菜单实例集合(带是否已挂载)+ 当前活动菜单;仅在变化时打印一行。 */
    private static void logStateIfChanged() {
        try {
            if (!LoaderConfig.logEnabled()) {
                return;   // b51:关日志时连签名都不拼(原来每帧一次 StringBuilder + 反射)
            }
            List<List<Menu>> all = allMenus();
            if (all == null) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (List<Menu> view : all) {
                if (view == null) {
                    continue;
                }
                for (Menu m : view) {
                    if (m == null || !"aoc.kingdoms.lukasz.menus.MainMenu".equals(m.getClass().getName())) {
                        continue;
                    }
                    count++;
                    MenuElement[] row = PATCHED.get(m);
                    List<MenuElement> els = row == null ? null : elementsOf(m);
                    boolean has = els != null && els.contains(row[0]);
                    sb.append(System.identityHashCode(m)).append(has ? ":row " : ":none ");
                }
            }
            Object active = ReflectUtil.call(Game.menuManager, "getActiveMenu", new Class<?>[0], new Object[0]);
            String activeDesc = "-";
            if (active instanceof Menu) {
                Menu am = (Menu) active;
                MenuElement[] r = PATCHED.get(am);
                List<MenuElement> els = r == null ? null : elementsOf(am);
                boolean has = els != null && els.contains(r[0]);
                activeDesc = System.identityHashCode(am) + "/" + am.getClass().getSimpleName()
                        + (has ? ":row" : ":none");
            }
            String sig = "n=" + count + " [" + sb.toString().trim() + "] active=" + activeDesc;
            if (!sig.equals(lastStateSig)) {
                lastStateSig = sig;
                SafeLog.i(TAG + ": state " + sig);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ------------------------------------------------------------------ */

    private static boolean patch(Menu menu) {
        List<MenuElement> elements = elementsOf(menu);
        if (elements == null || elements.isEmpty()) {
            return false;
        }

        MenuElement loadGame = findElementByText(menu, textOfLang("LoadGame"));
        MenuElement editor = findElementByText(menu, textOfLang("Editor"));
        if (loadGame == null || editor == null) {
            return false;
        }

        // ===== 计算新行几何:「载入游戏」下方一整行(默认布局) =====
        int rowX = loadGame.getPosX();
        int rowW = loadGame.getWidth();
        int rowY = loadGame.getPosY() + loadGame.getHeight() + CFG.PADDING * 2;

        int gap = CFG.PADDING;
        int gearW = (rowW - gap) * 2 / 10;     // 20% —— 设置(图标)在左
        int pluginW = rowW - gap - gearW;      // 80% —— 附加包在右
        int gearX = rowX;
        int pluginX = rowX + gearW + gap;

        // b53:主菜单按钮布局配置(hotplugins 同级的 hotpluginsUI.json;Display=false 时不生效)
        UiConfig ui = uiConfig();
        boolean custom = ui.display;
        if (custom) {
            if (ui.settings.width > 0) {
                gearW = ui.settings.width;
            }
            if (ui.plugins.width > 0) {
                pluginW = ui.plugins.width;
            }
        }

        // ===== 编辑器及以下整体下移一行(只动主菜单面板横向范围内的元素)=====
        // 仅默认布局需要(自定义布局不占那一行,也就不能把别的东西整体推下去)
        int delta = editor.getHeight() + CFG.PADDING * 2;
        if (!custom) {
            int editorY = editor.getPosY();
            int xMin = rowX - CFG.PADDING * 3;
            int xMax = rowX + rowW + CFG.PADDING * 3;
            for (MenuElement e : elements) {
                try {
                    if (e.getPosY() >= editorY - 2 && e.getPosX() >= xMin && e.getPosX() <= xMax) {
                        e.setPosY(e.getPosY() + delta);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // ===== 新建两个按钮并追加到元素表(设置图标在左,附加包在右) =====
        final ButtonGame2 pluginBtn = new ButtonGame2("附加包", CFG.FONT_REGULAR, -1, pluginX, rowY, pluginW, true) {
            @Override
            public void actionElement() {
                PluginScreen.open();
            }
        };
        final ButtonGame2_IMG gearBtn = new ButtonGame2_IMG(null, CFG.FONT_REGULAR, -1, gearX, rowY, gearW, true, Images.settings) {
            @Override
            public void actionElement() {
                LoaderSettingsPanel.open();
            }
        };
        elements.add(pluginBtn);
        elements.add(gearBtn);
        int newSize = elements.size();

        // ===== 同步 Menu 的缓存元素数量(游戏循环用的是缓存值)=====
        ReflectUtil.setInt(menu, "iMenuElementsSize", newSize);

        // 只把“我们的两个按钮”标记为可见;绝不调 menu.updateMenuElements_IsInView():
        // 挂载时机可能正处于菜单滑入/分辨率切换过程中,统一重算会把原版元素误标为
        // 不可见 → 整个菜单渲染成黑块(真机实锤:黑屏状态下盲点按钮位置仍可交互)。
        // 原版元素的可见性标志交给游戏自己维护(菜单位置变化时它会自行重算)。
        try {
            pluginBtn.setIsInView(true);
            gearBtn.setIsInView(true);
        } catch (Throwable ignored) {
        }

        // ===== 保持「不可滚动」不变量(2026-10-13 修复:主菜单可拖动/出现滚动条)=====
        // ⚠ 绝不能调用 menu.updateScrollable()!
        // 主菜单本体是全屏 Menu(initMenu(null,0,0,GW,GH)),其元素里有多个「贴着屏幕边界」的
        // 配置项(右侧图标列、版本号文本等)。原版仅在 initMenu() 里算过那一次
        // (边界恰好相等 → 不可滚动);插入新行后重算,任何元素越界 1px 都会
        // scrollableX/Y=true → 整个菜单可拖动 + 绘制出滚动条(UI bug)。
        // 这里显式把滚动状态钉死为「关」:
        try {
            logExtents(menu, elements);
        } catch (Throwable ignored) {
        }
        // 只钉死这两个布尔开关;iMaxSliderPosition* 保持原版构造期算出的值不动
        // (若清零反而会破坏 updateMenuPosX 的夹取公式 → 菜单可能被滑到屏幕外)。
        ReflectUtil.setBoolean(menu, "scrollableX", false);
        ReflectUtil.setBoolean(menu, "scrollableY", false);

        // ===== 主菜单面板加高一行(字段在主菜单类自身;找不到就不加高)=====
        // b53:自定义布局不占行 → 不加高
        if (!custom) {
            Integer height = ReflectUtil.getOwnInt(menu, "iHeight");
            if (height != null) {
                ReflectUtil.setOwnInt(menu, "iHeight", height + delta);
            } else {
                SafeLog.d(TAG + ": iHeight not found, panel box not grown");
            }
        }

        // ===== 登记进滑动动画列表(主菜单返回时的滑入动画会平移这些元素)=====
        Object ids = ReflectUtil.getOwn(menu, "buttonElementIDs");
        if (ids instanceof ArrayList) {
            try {
                @SuppressWarnings("unchecked")
                ArrayList<Integer> list = (ArrayList<Integer>) ids;
                list.add(newSize - 2);
                list.add(newSize - 1);
            } catch (Throwable ignored) {
            }
        }

        // b53:自定义布局(absolute / snap)在按钮创建后应用(两者相互吸附也能成立)
        if (custom) {
            applyCustomLayout(ui, elements, gearBtn, pluginBtn, gap);
        }

        PATCHED.put(menu, new MenuElement[]{pluginBtn, gearBtn});
        everMounted = true;
        SafeLog.i(TAG + ": mounted at y=" + rowY + (custom ? " (custom layout)" : " (delta=" + delta + ")")
                + " w=" + pluginW + "+" + gearW + ", id=" + System.identityHashCode(menu));
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* b53:hotpluginsUI.json(主菜单两个按钮的布局)                            */
    /* ------------------------------------------------------------------ */

    private static UiConfig uiCfgCache;
    private static long uiCfgMtime = -1L;
    private static boolean uiCfgLogged;

    /** 读取(必要时生成)布局配置;文件变化会自动重读。失败 → Display=false。 */
    private static UiConfig uiConfig() {
        try {
            File f = LoaderPaths.uiConfigFile();
            long m = f.isFile() ? f.lastModified() : -1L;
            if (uiCfgCache != null && m == uiCfgMtime) {
                return uiCfgCache;
            }
            LoaderPaths.ensureUiConfig();
            m = f.isFile() ? f.lastModified() : -1L;
            String text = f.isFile() ? new String(PluginManifest.readAll(f), "UTF-8") : null;
            uiCfgCache = UiConfig.parse(text);
            uiCfgMtime = m;
            if (!uiCfgLogged) {
                uiCfgLogged = true;
                SafeLog.i(TAG + ": ui config Display=" + uiCfgCache.display
                        + (uiCfgCache.lastError == null ? "" : " (" + uiCfgCache.lastError + ")")
                        + " file=" + f.getAbsolutePath());
            }
            return uiCfgCache;
        } catch (Throwable t) {
            return UiConfig.parse(null);
        }
    }

    /** 应用自定义布局:先 absolute(两者都先定住),再 snap(吸附;互相吸附也不成环)。 */
    private static void applyCustomLayout(UiConfig ui, List<MenuElement> elements,
                                          MenuElement gearBtn, MenuElement pluginBtn, int gap) {
        if (ui.settings.mode == UiConfig.MODE_ABSOLUTE) {
            gearBtn.setPosX(ui.settings.x);
            gearBtn.setPosY(ui.settings.y);
        }
        if (ui.plugins.mode == UiConfig.MODE_ABSOLUTE) {
            pluginBtn.setPosX(ui.plugins.x);
            pluginBtn.setPosY(ui.plugins.y);
        }
        applySnap(ui.settings, "settings", gearBtn, pluginBtn, elements, gap);
        applySnap(ui.plugins, "plugins", pluginBtn, gearBtn, elements, gap);
        SafeLog.i(TAG + ": ui layout applied: settings=" + gearBtn.getPosX() + "," + gearBtn.getPosY()
                + " plugins=" + pluginBtn.getPosX() + "," + pluginBtn.getPosY());
    }

    private static void applySnap(UiConfig.Btn b, String selfName, MenuElement self, MenuElement other,
                                  List<MenuElement> elements, int gap) {
        if (b.mode != UiConfig.MODE_SNAP) {
            return;
        }
        MenuElement anchor = resolveAnchor(b.anchor, selfName, self, other, elements);
        if (anchor == null) {
            SafeLog.i(TAG + ": ui anchor not found '" + b.anchor + "' (" + selfName + " 保持原位置)");
            return;
        }
        int[] p = UiConfig.place(b, anchor.getPosX(), anchor.getPosY(),
                anchor.getWidth(), anchor.getHeight(), self.getWidth(), self.getHeight(), gap);
        self.setPosX(p[0]);
        self.setPosY(p[1]);
    }

    /** 锚点解析:@settings / @plugins(我们两个按钮)、LoadGame / Editor(语言键)、其它按元素文本。 */
    private static MenuElement resolveAnchor(String anchor, String selfName, MenuElement self,
                                             MenuElement other, List<MenuElement> elements) {
        if (anchor == null) {
            return null;
        }
        String a = anchor.trim();
        if (a.length() == 0) {
            return null;
        }
        boolean selfIsSettings = "settings".equals(selfName);
        if (a.equalsIgnoreCase("@settings") || a.equals("设置")) {
            return selfIsSettings ? self : other;
        }
        if (a.equalsIgnoreCase("@plugins") || a.equals("附加包")) {
            return selfIsSettings ? other : self;
        }
        if (a.equalsIgnoreCase("LoadGame")) {
            a = textOfLang("LoadGame");
        } else if (a.equalsIgnoreCase("Editor")) {
            a = textOfLang("Editor");
        }
        if (a == null) {
            return null;
        }
        if (elements != null) {
            for (MenuElement e : elements) {
                try {
                    if (a.equals(e.getText())) {
                        return e;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 诊断日志:记录元素表最大右/下边界(排查滚动条类 UI 问题用;只读不写)。 */
    private static void logExtents(Menu menu, List<MenuElement> elements) {
        try {
            int maxX = 0;
            int maxY = 0;
            String whoX = "-";
            String whoY = "-";
            for (MenuElement e : elements) {
                int r = e.getPosX() + e.getWidth();
                int b = e.getPosY() + e.getHeight();
                if (r > maxX) {
                    maxX = r;
                    whoX = e.getClass().getSimpleName() + "@" + e.getPosX() + "+" + e.getWidth();
                }
                if (b > maxY) {
                    maxY = b;
                    whoY = e.getClass().getSimpleName() + "@" + e.getPosY() + "+" + e.getHeight();
                }
            }
            SafeLog.i(TAG + ": extents right=" + maxX + " (" + whoX + ") bottom=" + maxY
                    + " (" + whoY + ") menu=" + menu.getWidth() + "x" + menu.getHeight());
        } catch (Throwable ignored) {
        }
    }

    /* ------------------------------------------------------------------ */

    @SuppressWarnings("unchecked")
    private static List<List<Menu>> allMenus() {
        try {
            Object menus = ReflectUtil.get(Game.menuManager, "menus");
            return (List<List<Menu>>) menus;
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<MenuElement> elementsOf(Menu menu) {
        try {
            return (List<MenuElement>) ReflectUtil.get(menu, "menuElements");
        } catch (Throwable t) {
            return null;
        }
    }

    private static MenuElement findElementByText(Menu menu, String text) {
        if (text == null) {
            return null;
        }
        List<MenuElement> elements = elementsOf(menu);
        if (elements == null) {
            return null;
        }
        for (MenuElement e : elements) {
            try {
                if (text.equals(e.getText())) {
                    return e;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String textOfLang(String key) {
        try {
            return Game.lang.get(key);
        } catch (Throwable t) {
            return null;
        }
    }
}
