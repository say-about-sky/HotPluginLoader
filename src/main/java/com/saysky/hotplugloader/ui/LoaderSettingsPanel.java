package com.saysky.hotplugloader.ui;

import java.util.ArrayList;
import java.util.List;

import aoc.kingdoms.lukasz.jakowski.CFG;
import aoc.kingdoms.lukasz.jakowski.Game;
import aoc.kingdoms.lukasz.menu.Colors;
import aoc.kingdoms.lukasz.menu.Menu;
import aoc.kingdoms.lukasz.menu.menuTitle.MenuTitleIMG;
import aoc.kingdoms.lukasz.menu_element.MenuElement;
import aoc.kingdoms.lukasz.menu_element.button.ButtonGame2;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_Hover;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement_Type;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement_Type_TextTitle_BG_Center;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement_Type_Text_Desc;
import aoc.kingdoms.lukasz.menu_element.textStatic.Text_Static;
import aoc.kingdoms.lukasz.textures.Images;
import com.saysky.hotplugloader.BuildInfo;
import com.saysky.hotplugloader.LoaderConfig;
import com.saysky.hotplugloader.MainMenuMount;
import com.saysky.hotplugloader.PluginRegistry;
import com.saysky.hotplugloader.SafeLog;

/**
 * 「加载器设置」面板(主菜单齿轮按钮打开)。
 *
 * <p>开关行复用原生 checkbox 样式(与「设置 → 边缘滚动」同款)。</p>
 */
public class LoaderSettingsPanel extends Menu {

    private static final String TAG = "HotLoader/settings";

    private static LoaderSettingsPanel instance = null;
    private static int slotIndex = -1;

    private LoaderSettingsPanel() {
        ArrayList<MenuElement> elements = new ArrayList<MenuElement>();
        int menuWidth = PluginListPanel.panelWidth();
        // 弹在主菜单左侧、不遮盖:先按左侧可用空间收窄面板(拿不到主菜单位置时保持原宽)
        int mainLeft = MainMenuMount.mainMenuPanelLeft();
        if (mainLeft > 0 && menuWidth > mainLeft - CFG.PADDING * 4) {
            menuWidth = Math.max(360, mainLeft - CFG.PADDING * 4);
        }
        int paddingLeft = CFG.PADDING * 2 + Images.boxTitleBORDERWIDTH;
        int btnW = menuWidth - paddingLeft * 2;
        int rowY = CFG.PADDING;

        elements.add(new ButtonGame2("启用热加载", CFG.FONT_REGULAR, -1, paddingLeft, rowY,
                menuWidth - paddingLeft * 2, true, CFG.BUTTON_HEIGHT, true) {
            @Override
            public boolean getCheckboxState() {
                return LoaderConfig.hotloadEnabled();
            }

            @Override
            public void actionElement() {
                LoaderConfig.setHotloadEnabled(!LoaderConfig.hotloadEnabled());
            }

            @Override
            public void buildElementHover() {
                this.menuElementHover = hoverTitleDesc("启用热加载",
                        "启动后自动加载目录中的附加包(下次启动生效)。");
            }
        });
        rowY += elements.get(elements.size() - 1).getHeight() + CFG.PADDING * 2;

        elements.add(new ButtonGame2("允许未校验附加包", CFG.FONT_REGULAR, -1, paddingLeft, rowY,
                menuWidth - paddingLeft * 2, true, CFG.BUTTON_HEIGHT, true) {
            @Override
            public boolean getCheckboxState() {
                return LoaderConfig.allowUntrusted();
            }

            @Override
            public void actionElement() {
                LoaderConfig.setAllowUntrusted(!LoaderConfig.allowUntrusted());
            }

            @Override
            public void buildElementHover() {
                this.menuElementHover = hoverTitleDesc("允许未校验附加包",
                        "关闭时:仅加载清单中 trusted:true 的附加包。");
            }
        });
        rowY += elements.get(elements.size() - 1).getHeight() + CFG.PADDING * 2;

        // b20:日志开关(2026-10-13 两次被旧缓冲区误覆盖,已按本地历史快照逐行还原)
        elements.add(new ButtonGame2("开启日志", CFG.FONT_REGULAR, -1, paddingLeft, rowY,
                menuWidth - paddingLeft * 2, true, CFG.BUTTON_HEIGHT, true) {
            @Override
            public boolean getCheckboxState() {
                return LoaderConfig.logEnabled();
            }

            @Override
            public void actionElement() {
                LoaderConfig.setLogEnabled(!LoaderConfig.logEnabled());
            }

            @Override
            public void buildElementHover() {
                this.menuElementHover = hoverTitleDesc("开启日志",
                        "全局日志总开关:默认关闭,排障时到设置里开启;关闭后不再写任何日志(hotloader.log / 框架日志镜像 / 启动器诊断)。");
            }
        });
        rowY += elements.get(elements.size() - 1).getHeight() + CFG.PADDING * 2;

        elements.add(new ButtonGame2("启动时刷新日志", CFG.FONT_REGULAR, -1, paddingLeft, rowY,
                menuWidth - paddingLeft * 2, true, CFG.BUTTON_HEIGHT, true) {
            @Override
            public boolean getCheckboxState() {
                return LoaderConfig.refreshLogOnStart();
            }

            @Override
            public void actionElement() {
                LoaderConfig.setRefreshLogOnStart(!LoaderConfig.refreshLogOnStart());
            }

            @Override
            public void buildElementHover() {
                this.menuElementHover = hoverTitleDesc("启动时刷新日志",
                        "每次启动清空 hotloader.log,保证日志只含本次会话(默认关闭);关闭则追加写入,超过 512KB 自动清理。");
            }
        });
        rowY += elements.get(elements.size() - 1).getHeight() + CFG.PADDING * 2;

        Text_Static infoCount = new Text_Static("已加载附加包: " + PluginRegistry.countActive() + " 个",
                paddingLeft, rowY, CFG.FONT_REGULAR_SMALL);
        infoCount.setWidth(btnW);
        elements.add(infoCount);
        rowY += CFG.TEXT_HEIGHT + CFG.PADDING;

        Text_Static infoBuild = new Text_Static("构建: " + BuildInfo.BUILD_TAG, paddingLeft, rowY, CFG.FONT_REGULAR_SMALL);
        infoBuild.setWidth(btnW);
        elements.add(infoBuild);
        rowY += CFG.TEXT_HEIGHT + CFG.PADDING;

        elements.add(new ButtonGame2("关闭", CFG.FONT_REGULAR, -1, paddingLeft, rowY,
                menuWidth - paddingLeft * 2, true) {
            @Override
            public void actionElement() {
                close();
            }
        });
        rowY += elements.get(elements.size() - 1).getHeight() + CFG.PADDING;

        // 弹在主菜单左侧、不遮盖主菜单(拿不到主菜单实例时贴屏幕左侧)
        int posX = mainLeft > 0
                ? Math.max(CFG.PADDING * 2, mainLeft - menuWidth - CFG.PADDING * 2)
                : CFG.PADDING * 2;
        int posY = Math.max(CFG.PADDING * 4, (CFG.GAME_HEIGHT - rowY) / 2);
        initMenu(new MenuTitleIMG("加载器设置", false, false, Images.title500),
                posX, posY, menuWidth, rowY, elements, true, false);
        // 固定宽度面板:钉死横向滚动(防超宽文本把面板变成可拖动)
        com.saysky.hotplugloader.ReflectUtil.setBoolean(this, "scrollableX", false);
    }

    /** 悬停提示:标题(金色 CenterBG)+ 说明(仿「设置→地区」样式)。 */
    private static MenuElement_Hover hoverTitleDesc(String title, String desc) {
        ArrayList<MenuElement_HoverElement> nElements = new ArrayList<MenuElement_HoverElement>();
        ArrayList<MenuElement_HoverElement_Type> nData = new ArrayList<MenuElement_HoverElement_Type>();
        nData.add(new MenuElement_HoverElement_Type_TextTitle_BG_Center(title, CFG.FONT_BOLD, Colors.HOVER_GOLD));
        nElements.add(new MenuElement_HoverElement(nData));
        nData.clear();
        nData.add(new MenuElement_HoverElement_Type_Text_Desc(desc, CFG.FONT_REGULAR_SMALL, Colors.HOVER_RIGHT));
        nElements.add(new MenuElement_HoverElement(nData));
        nData.clear();
        return new MenuElement_Hover(nElements);
    }

    /* ------------------------------------------------------------------ */

    /** 打开(或刷新)面板。仅应在主菜单视图里调用。 */
    public static void open() {
        try {
            PluginListPanel.close();
            LoaderSettingsPanel panel = new LoaderSettingsPanel();
            List<Menu> menus = Game.menuManager.getActiveMenu();
            if (slotIndex < 0 || slotIndex >= menus.size()) {
                slotIndex = Game.menuManager.addNextMenuToView(Game.menuManager.MAINMENU, panel);
            } else {
                menus.set(slotIndex, panel);
            }
            instance = panel;
            panel.setVisible(true);
            Game.menuManager.setOrderOfMenu(slotIndex);
            SafeLog.d(TAG + ": settings panel opened (slot=" + slotIndex + ")");
        } catch (Throwable t) {
            SafeLog.e(TAG + ": open settings panel failed", t);
        }
    }

    public static void close() {
        try {
            if (instance != null) {
                instance.setVisible(false);
            }
        } catch (Throwable ignored) {
        }
    }
}
