package com.saysky.hotplugloader.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import aoc.kingdoms.lukasz.jakowski.CFG;
import aoc.kingdoms.lukasz.jakowski.Game;
import aoc.kingdoms.lukasz.jakowski.Renderer.Renderer;
import aoc.kingdoms.lukasz.menu.Menu;
import aoc.kingdoms.lukasz.menu.View;
import com.badlogic.gdx.Gdx;
import com.saysky.hotplugloader.ReflectUtil;
import com.saysky.hotplugloader.SafeLog;

/**
 * 「附加包」界面的视图编排(学习原版「编辑器」的窗口跳转实现)。
 *
 * <p>原理(已按源码核实):</p>
 * <ul>
 *   <li>原版 {@code menusEditor.Editor} 是一个"独占视图"(MenuManager 懒创建,
 *       索引 = {@code getViewID(View.EDITOR)});</li>
 *   <li>{@code setViewID(View.EDITOR)} 触发<b>整视图水平滑动过渡</b>
 *       (fromView/toView 两个视图各滑一半;过渡期间 {@code getFromViewID() >= 0},结束复位 -1);</li>
 *   <li>剧本地图背景由视图机制自带(编辑器/设置界面天然显示当前载入的地图)。</li>
 * </ul>
 *
 * <p>做法:把我们的面板作为 1 号菜单挂进 EDITOR 视图(游戏自身重建时执行
 * {@code menus.get(EDITOR).set(0, new Editor())},1 号位不受影响):</p>
 * <ul>
 *   <li>打开:隐藏编辑器菜单 → 显示我们的面板 → {@code setViewID(View.EDITOR)}(带过渡)→ 置于最前;</li>
 *   <li>返回:与官方 Editor 返回同款({@code Renderer.drawArmyInProvince = true} + 切回 MAINMENU,带过渡);
 *       面板保持可见到滑出结束,再恢复编辑器菜单可见性;</li>
 *   <li>兼容:官方「编辑器」按钮照常工作(我们的面板在此期间保持隐藏)。</li>
 * </ul>
 */
public final class PluginScreen {

    private static final String TAG = "HotLoader/screen";
    private static final Class<?>[] NO_TYPES = new Class<?>[0];
    private static final Object[] NO_ARGS = new Object[0];

    private static int viewIndex = -1;
    private static int menuIndex = -1;
    private static Menu ourMenu = null;
    private static boolean session = false;
    private static final List<Menu> hiddenByUs = new ArrayList<Menu>();

    /* ---- 行手势状态(b21/b22:按住滑动调整顺序) ---- */

    private static boolean pressDown = false;
    private static int pressMenuId = -1;
    private static int pressElemId = -1;
    private static int pressScrollY = 0;
    private static boolean slideMode = false;
    /** 已消费位移(原始屏幕 px;每帧直接取 Gdx.input.getDeltaY,每满一个行距换一格)。 */
    private static int slideAcc = 0;
    /** 抑制随后的行点击(滑动会话的那次松手)。 */
    private static boolean suppressRowClick = false;
    /** 本次触摸是否已被判定为「滚动」(可滚动列表):判定后本机不再参与,直到抬手。 */
    private static boolean touchScrolled = false;
    /** 按住累计时长(毫秒;用帧时间累加,不受卡顿影响)。 */
    private static int pressHoldMs = 0;
    /** 按下时的菜单滚动位置(可滚动列表:判断「是否真在滚」)。 */
    private static int pressMenuPosY = 0;
    /** 滑动会话是否真的换过位(决定松手要不要提交/提示)。 */
    private static boolean slideDirty = false;
    /** 可滚动列表:按住不动进入排序的时长阈值(毫秒)。 */
    private static final int ARM_HOLD_MS = 450;

    private PluginScreen() {
    }

    /** 每帧维护(由 HotLoaderTick 调用;未用过时零开销)。 */
    public static void tick() {
        try {
            if (viewIndex < 0 || ourMenu == null) {
                return;
            }
            if (isAnimatingViewChange()) {
                return; // 过渡中:保持现状,滑入/滑出才顺滑
            }
            boolean active = getCurrentView() == viewIndex;
            if (session) {
                if (!active) {
                    session = false; // 经返回手势等其它路径离开了附加包视图
                } else {
                    ensureSessionVisuals();
                    tickRowGesture();
                    return;
                }
            }
            // 非会话:我们的面板隐藏;此前被我们隐藏的原版菜单恢复
            if (ourMenu.getVisible()) {
                ourMenu.setVisible(false);
            }
            for (Menu m : hiddenByUs) {
                try {
                    if (!m.getVisible()) {
                        m.setVisible(true);
                    }
                } catch (Throwable ignored) {
                }
            }
            hiddenByUs.clear();
        } catch (Throwable t) {
            SafeLog.e(TAG + ": tick failed", t);
        }
    }

    /** 打开附加包界面(原版同款窗口过渡;由主菜单「附加包」按钮调用)。 */
    public static void open() {
        try {
            if (session && getCurrentView() == viewIndex) {
                return; // 已在附加包界面
            }
            if (!resolveView()) {
                return;
            }
            ensureMenu();
            if (ourMenu == null) {
                return;
            }
            // 隐藏同期视图里的其它菜单(编辑器本体)
            hiddenByUs.clear();
            List<Menu> list = menusOf(viewIndex);
            if (list != null) {
                for (Menu m : list) {
                    if (m == null || m == ourMenu) {
                        continue;
                    }
                    if (m.getVisible()) {
                        m.setVisible(false);
                        hiddenByUs.add(m);
                    }
                }
            }
            ourMenu.setVisible(true);
            session = true;
            try {
                LoaderSettingsPanel.close();
            } catch (Throwable ignored) {
            }
            Game.menuManager.setViewID(View.EDITOR);
            Game.menuManager.setOrderOfMenu(menuIndex);
            SafeLog.d(TAG + ": opened (view=" + viewIndex + ", menu=" + menuIndex + ")");
        } catch (Throwable t) {
            SafeLog.e(TAG + ": open failed", t);
        }
    }

    /** 返回主菜单(与官方 Editor 返回按钮同款动作)。 */
    public static void close() {
        try {
            session = false;
            Renderer.drawArmyInProvince = true;
            Game.menuManager.setViewID(View.MAINMENU);
            SafeLog.d(TAG + ": closed");
        } catch (Throwable t) {
            SafeLog.e(TAG + ": close failed", t);
        }
    }

    /** 如正处附加包界面则返回(供设置面板等外部调用)。 */
    public static void closeIfOpen() {
        try {
            if (session) {
                close();
            }
        } catch (Throwable ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /* 行手势(b21/b22):按住行滑动 → 按手指实时位移换位;停手即停          */
    /* ------------------------------------------------------------------ */

    /** 行点击前查询:是否应吞掉本次点击(由 PluginListPanel 行按钮调用)。 */
    public static boolean consumeRowClickSuppress() {
        boolean s = suppressRowClick;
        suppressRowClick = false;
        return s;
    }

    /**
     * 每帧手势机(仅会话期间调用;无手指时零开销)。
     *
     * <p>按压目标取游戏自己的激活元素(MenuManager.activeSliderMenuID/activeMenuElementID,
     * 私有字段反射读)——不自己做坐标换算;每帧位移量直接取 libGDX 原生
     * {@code Gdx.input.getDeltaY()}(不做手动差值);换位速度由实时位移驱动:
     * 每滑动一个行距换一格,滑得越快换得越快,停手即停(单帧最多 3 格)。</p>
     */
    private static void tickRowGesture() {
        try {
            boolean down = Gdx.input.isTouched(0);
            if (!down) {
                if (slideMode) {
                    endSlide();
                }
                resetPress();
                touchScrolled = false; // 抬手:结束本次触摸的滚动判定
                return;
            }
            if (!pressDown) {
                // 按下起始:记录目标(位移从下一帧起用原生 delta 累积)
                pressDown = true;
                slideAcc = 0;
                pressHoldMs = 0;
                slideDirty = false;
                pressMenuId = activeInt("activeSliderMenuID");
                pressElemId = activeInt("activeMenuElementID");
                pressScrollY = ourMenu == null ? 0 : ourMenu.getScrollPosY();
                pressMenuPosY = ourMenu == null ? 0 : ourMenu.getMenuPosY();
                slideMode = false;
                suppressRowClick = false; // 新手势:清残留
                return;
            }
            if (touchScrolled) {
                return; // 本次触摸已判定为滚动:交给游戏,本机不再参与
            }
            if (pressMenuId != menuIndex || PluginListPanel.currentPanel() != ourMenu) {
                resetPress();
                return;
            }
            File rowFile = pressElemId >= 0 ? PluginListPanel.rowFileAt(pressElemId) : null;
            if (rowFile == null) {
                return; // 按在非行元素上:不做手势(松手自然复位)
            }
            if (slideMode) {
                slideAcc += deltaY(); // 排序/滑动中:继续累积本帧位移
            } else if (ourMenu != null && ourMenu.getScrollableY()) {
                // ——— 可滚动列表:拖动=滚动(交给游戏);「按住不动」才进入拖动排序 ———
                // 每帧直接取"本帧位移量"(libGDX 原生 Gdx.input.getDeltaY,无需自己做差值)
                slideAcc += deltaY();
                int slop = Math.max(30, screenH() / 32);
                int drift = Math.abs(ourMenu.getMenuPosY() - pressMenuPosY);
                if (drift > slop || Math.abs(slideAcc) > slop) {
                    touchScrolled = true; // 真在滚:本次触摸不再排序(松手自然滚动)
                    return;
                }
                pressHoldMs += (int) (Gdx.graphics.getRawDeltaTime() * 1000f); // 帧时间累加
                if (pressHoldMs < ARM_HOLD_MS) {
                    return; // 还在等「按住不动」
                }
                armReorder(); // 进入排序(归零位移、禁掉本次手势的菜单滚动)
                return;
            } else {
                if (ourMenu != null && ourMenu.getScrollPosY() != pressScrollY) {
                    resetPress(); // 期间列表滚过:交给滚动
                    return;
                }
                slideAcc += deltaY();
                int enter = Math.max(36, screenH() / 22); // 进入滑动的位移阈值(按屏幕高比例)
                if (Math.abs(slideAcc) >= enter) {
                    slideMode = true; // 不可滚动列表:直接拖即可
                    suppressRowClick = true; // 整个滑动会话的松手点击都吞掉
                }
                return;
            }
            int stepPx = stepPx();
            int guard = 3; // 单帧最多 3 格(快速甩动的上限)
            while (guard > 0 && slideAcc <= -stepPx) {
                if (!PluginListPanel.stepRow(rowFile, -1)) {
                    slideAcc = 0; // 已到边界:清账,反向滑立即生效
                    break;
                }
                slideDirty = true;
                slideAcc += stepPx;
                guard--;
            }
            while (guard > 0 && slideAcc >= stepPx) {
                if (!PluginListPanel.stepRow(rowFile, 1)) {
                    slideAcc = 0;
                    break;
                }
                slideDirty = true;
                slideAcc -= stepPx;
                guard--;
            }
        } catch (Throwable t) {
            SafeLog.e(TAG + ": row gesture failed", t);
        }
    }

    /** 可滚动列表:按住不动达到时长 → 进入拖动排序;此后本次手势不再滚动(菜单拖动已被禁)。 */
    private static void armReorder() {
        slideMode = true;
        suppressRowClick = true;
        slideDirty = false;
        slideAcc = 0; // 起步归零:按住阶段的位移不计入换位
        try {
            Game.menuManager.setMenu_MoveInnerElements(false); // 本次手势的列表滚动到此为止
        } catch (Throwable t) {
            SafeLog.e(TAG + ": suppress menu scroll failed", t);
        }
        try {
            if (ourMenu != null) {
                ourMenu.updateMenuPosY(pressMenuPosY); // 弹回按住期间的微小漂移
            }
        } catch (Throwable ignored) {
        }
        SafeLog.d(TAG + ": reorder armed on scrollable list");
    }

    /** 本帧手指 Y 位移(原始屏幕 px;libGDX 原生差值,上滑 = 负)。 */
    private static int deltaY() {
        try {
            return Gdx.input.getDeltaY();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 一步的行距(原始屏幕 px;把面板行距按屏幕/游戏比例换算,至少 24px)。 */
    private static int stepPx() {
        int pitch = PluginListPanel.rowPitch();
        int sh = screenH();
        try {
            if (pitch > 0 && CFG.GAME_HEIGHT > 0 && sh != CFG.GAME_HEIGHT) {
                pitch = (int) ((long) pitch * sh / CFG.GAME_HEIGHT);
            }
        } catch (Throwable ignored) {
        }
        return Math.max(24, pitch > 0 ? pitch : sh / 14);
    }

    /** 滑动会话结束:提交顺序 + 重建面板 + 提示(未换位则静默结束)。 */
    private static void endSlide() {
        if (!slideDirty) {
            return; // 按住未换位(如可滚动列表"按住又放开"):不提交、不重建、不打扰
        }
        try {
            PluginListPanel.commitRowOrder();
            rebuild();
            toast("顺序已更新(下次加载生效)");
            SafeLog.i(TAG + ": slide reorder committed");
        } catch (Throwable t) {
            SafeLog.e(TAG + ": slide commit failed", t);
        }
    }

    private static void resetPress() {
        pressDown = false;
        pressElemId = -1;
        pressMenuId = -1;
        slideMode = false;
        slideAcc = 0;
        slideDirty = false;
        pressHoldMs = 0;
    }

    private static void toast(String msg) {
        try {
            Game.menuManager.addToast(msg);
        } catch (Throwable ignored) {
        }
    }

    private static int activeInt(String name) {
        Object v = ReflectUtil.get(Game.menuManager, name);
        return v instanceof Integer ? (Integer) v : -1;
    }

    private static int screenH() {
        try {
            return Gdx.graphics.getHeight();
        } catch (Throwable t) {
            return CFG.GAME_HEIGHT;
        }
    }

    /** 刷新内容:用新面板实例替换(游戏对编辑器本体也是 set(0, new Editor()))。 */
    public static void rebuild() {
        try {
            if (viewIndex < 0) {
                return;
            }
            List<Menu> list = menusOf(viewIndex);
            if (list == null) {
                return;
            }
            PluginListPanel.preserveScrollFrom(ourMenu); // b26:刷新不重置视角(记录旧面板滚动偏移)
            PluginListPanel panel = new PluginListPanel();
            int idx = findOurIndex(list);
            if (idx >= 0) {
                list.set(idx, panel);
                menuIndex = idx;
            } else {
                menuIndex = Game.menuManager.addNextMenuToView(viewIndex, panel);
            }
            ourMenu = panel;
            if (session) {
                panel.setVisible(true);
                Game.menuManager.setOrderOfMenu(menuIndex);
            }
            SafeLog.d(TAG + ": rebuilt (menu=" + menuIndex + ")");
        } catch (Throwable t) {
            SafeLog.e(TAG + ": rebuild failed", t);
        }
    }

    /* ------------------------------------------------------------------ */

    /** 解析(并按游戏自身逻辑懒创建)EDITOR 视图索引。 */
    private static boolean resolveView() {
        if (viewIndex >= 0) {
            return true;
        }
        try {
            Object v = ReflectUtil.call(Game.menuManager, "getViewID",
                    new Class<?>[]{View.class}, new Object[]{View.EDITOR});
            if (v instanceof Integer && (Integer) v >= 0) {
                viewIndex = (Integer) v;
                SafeLog.i(TAG + ": editor view index=" + viewIndex);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 确保我们的面板已挂进 EDITOR 视图(挂进 1 号位,游戏重建 0 号位不影响)。 */
    private static void ensureMenu() {
        List<Menu> list = menusOf(viewIndex);
        if (list == null) {
            return;
        }
        int idx = findOurIndex(list);
        if (idx >= 0) {
            ourMenu = list.get(idx);
            menuIndex = idx;
            return;
        }
        PluginListPanel panel = new PluginListPanel();
        menuIndex = Game.menuManager.addNextMenuToView(viewIndex, panel);
        ourMenu = panel;
        SafeLog.i(TAG + ": menu attached at index=" + menuIndex);
    }

    /** 会话期间的显隐维护(游戏可能在我们停留期间重建 0 号菜单,需再次隐藏)。 */
    private static void ensureSessionVisuals() {
        try {
            List<Menu> list = menusOf(viewIndex);
            if (list != null) {
                for (Menu m : list) {
                    if (m == null || m == ourMenu) {
                        continue;
                    }
                    if (m.getVisible()) {
                        m.setVisible(false);
                        hiddenByUs.add(m);
                    }
                }
            }
            if (!ourMenu.getVisible()) {
                ourMenu.setVisible(true);
            }
            if (menuIndex >= 0) {
                Game.menuManager.setOrderOfMenu(menuIndex);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int findOurIndex(List<Menu> list) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof PluginListPanel) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static List<Menu> menusOf(int view) {
        try {
            Object menus = ReflectUtil.get(Game.menuManager, "menus");
            if (menus instanceof List) {
                List<List<Menu>> all = (List<List<Menu>>) menus;
                if (view >= 0 && view < all.size()) {
                    return all.get(view);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isAnimatingViewChange() {
        Object v = ReflectUtil.call(Game.menuManager, "getFromViewID", NO_TYPES, NO_ARGS);
        return v instanceof Integer && (Integer) v >= 0;
    }

    private static int getCurrentView() {
        Object v = ReflectUtil.call(Game.menuManager, "getViewID", NO_TYPES, NO_ARGS);
        return v instanceof Integer ? (Integer) v : -1;
    }
}
