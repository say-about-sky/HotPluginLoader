package com.saysky.hotplugloader.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import aoc.kingdoms.lukasz.jakowski.CFG;
import aoc.kingdoms.lukasz.jakowski.Game;
import aoc.kingdoms.lukasz.jakowski.Renderer.Renderer;
import aoc.kingdoms.lukasz.menu.Colors;
import aoc.kingdoms.lukasz.menu.Menu;
import aoc.kingdoms.lukasz.menu.menuTitle.MenuTitle;
import aoc.kingdoms.lukasz.menu_element.MenuElement;
import aoc.kingdoms.lukasz.menu_element.Status;
import aoc.kingdoms.lukasz.menu_element.button.ButtonGame2;
import aoc.kingdoms.lukasz.menu_element.button.ButtonMain;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_Hover;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement_Type;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement_Type_Line;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement_Type_TextTitle_BG_Center;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement_Type_Text_Desc;
import aoc.kingdoms.lukasz.menu_element.textStatic.Text_Static;
import aoc.kingdoms.lukasz.menu_element.textStatic.Text_Title_v2_TextLR;
import aoc.kingdoms.lukasz.textures.Images;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.saysky.hotplugloader.HotPluginHost;
import com.saysky.hotplugloader.LoadQueue;
import com.saysky.hotplugloader.LoadStatus;
import com.saysky.hotplugloader.LoaderPaths;
import com.saysky.hotplugloader.PluginManifest;
import com.saysky.hotplugloader.PluginRegistry;
import com.saysky.hotplugloader.PluginStates;
import com.saysky.hotplugloader.ReflectUtil;
import com.saysky.hotplugloader.SafeLog;

/**
 * 「附加包」界面(镜像版「编辑器」:面板在**右侧**、视图过渡、剧本地图背景)。
 *
 * <p>结构(学习自 {@code menusEditor.Editor} 官方实现):</p>
 * <ul>
 *   <li>返回(居中) + 文件行 + 操作行 + 说明文本,全部 {@code ButtonMain}/{@code Text_Static};</li>
 *   <li>面板锚点:官方面板在左 {@code menuX = W/10},这里是镜像的右侧
 *       {@code W - W/10 - LEFT_MENU_WIDTH};</li>
 *   <li>背景与过渡由 {@link PluginScreen} 负责(把本菜单挂进 EDITOR 视图,
 *       {@code setViewID(View.EDITOR)} 触发原版同款窗口滑动)。</li>
 * </ul>
 *
 * <p>每行 = 一个附加包文件({@code .dex/.zip/.jar} + 同名 {@code .json} 清单);
 * 行卡片为「加载器设置」同款变色开关(ButtonGame2 + checkBox):<b>绿条 = 已加载、红条 = 未加载</b>。
 * 点击「未加载/错误/未信任」行 = 尝试加载;点击「已加载」行 = 禁用/重新启用。</p>
 */
public class PluginListPanel extends Menu {

    private static final String TAG = "HotLoader/panel";
    private static final int MAX_ROWS = 8;
    private static final int LABEL_CLIP = 38;
    /** 删除二次确认(b20):待确认的文件 + 首次点击时间(4 秒内再点一次才真删,防误触)。 */
    private static File pendingDelete = null;
    private static long pendingDeleteAt = 0L;
    /** 当前面板实例(b21 手势校验用;重建时刷新)。 */
    private static PluginListPanel currentPanel = null;
    /** 行元素引用/文件/元素下标(b21 滑动换位用;数组与视觉槽位同步交换)。 */
    private static MenuElement[] ROW_BTNS = new MenuElement[0];
    /** 重建接力的滚动偏移(b26:rebuild 前记录、构造函数末尾消费;-1 = 不恢复)。 */
    private static int pendingScrollOffset = -1;
    private static MenuElement[] ROW_DELS = new MenuElement[0];
    private static File[] ROW_FILES = new File[0];
    private static int[] ROW_ELEM_INDEXES = new int[0];

    PluginListPanel() {
        currentPanel = this;
        ROW_BTNS = new MenuElement[0];
        ROW_DELS = new MenuElement[0];
        ROW_FILES = new File[0];
        ROW_ELEM_INDEXES = new int[0];
        ArrayList<MenuElement> elements = new ArrayList<MenuElement>();
        int paddingLeft = CFG.PADDING * 2;
        int titleHeight = CFG.BUTTON_HEIGHT;
        int textPosX = CFG.PADDING * 4;
        int buttonYPadding = CFG.PADDING * 2;
        int buttonY = buttonYPadding;
        int btnW = CFG.LEFT_MENU_WIDTH - paddingLeft * 2;
        int sectionW = CFG.LEFT_MENU_WIDTH - Images.boxTitleBORDERWIDTH * 2;
        final int rowFont = 1; // 与官方 Editor 行按钮一致(ButtonMain + fontID=1)

        File dir = LoaderPaths.pluginsDir();
        final String dirPath = dir.getAbsolutePath();

        // ① 返回(官方同款:居中文本 + 固定顶部 padding;动作 = 带过渡退回主菜单)
        elements.add(new ButtonMain("返回", rowFont, -1, paddingLeft, CFG.PADDING, btnW, true) {
            @Override
            public void actionElement() {
                PluginScreen.close();
            }
        });

        // ② 精简提示(统一放在「返回」下方;详细说明收进各按钮/分隔栏的悬停提示)
        // ⚠ 长文本必须钉宽度:Text_Static 的宽 = 文本像素宽,超宽会把菜单算成
        //    横向可滚动(initMenu→updateScrollable:最大右边界 > 菜单宽 → scrollableX)
        //    → 整个面板可被左右拖动(与主菜单「贴边元素」同类坑,2026-10-13 实锤)。
        buttonY += elements.get(elements.size() - 1).getHeight() + buttonYPadding;
        Text_Static hint1 = new Text_Static("加载目录: " + clip(dirPath, 34), paddingLeft, buttonY, CFG.FONT_REGULAR_SMALL);
        hint1.setWidth(btnW);
        elements.add(hint1);
        buttonY += CFG.TEXT_HEIGHT + CFG.PADDING;
        Text_Static hint2 = new Text_Static("色条:绿=已加载、红=未加载。点击行=加载/禁用/启用。",
                paddingLeft, buttonY, CFG.FONT_REGULAR_SMALL);
        hint2.setWidth(btnW);
        elements.add(hint2);
        buttonY += CFG.TEXT_HEIGHT + CFG.PADDING;
        Text_Static hint3 = new Text_Static("按住行上下滑动=调整顺序(列表可滚动:先按住不动再拖)。",
                paddingLeft, buttonY, CFG.FONT_REGULAR_SMALL);
        hint3.setWidth(btnW);
        elements.add(hint3);

        // ②.5 状态行 + 进度条(b18;b25:条加高提亮 + 与状态行拉开间距,防文字下沿遮条)
        buttonY += CFG.TEXT_HEIGHT + CFG.PADDING;
        elements.add(new LoadStatusText(LoadStatus.display(), paddingLeft, buttonY,
                CFG.FONT_REGULAR_SMALL, btnW));
        buttonY += CFG.TEXT_HEIGHT + 6;
        elements.add(new LoadProgressBar(paddingLeft, buttonY, btnW, 16));
        buttonY += 16 + CFG.PADDING;

        // ③ 操作按钮(悬停提示仿「设置→地区」)
        elements.add(new ButtonMain("全部重载", rowFont, textPosX, paddingLeft, buttonY, btnW, true) {
            @Override
            public void actionElement() {
                onReloadAllClicked();
            }

            @Override
            public void buildElementHover() {
                this.menuElementHover = hoverTitleDesc("全部重载",
                        "原位重载(不重启游戏):退订全部附加包、清空注册表,再重新扫描加载一遍,"
                                + "并补一轮 .jar 转换;同时重扫资源覆盖索引(新文件对新开一局/后续读取生效,"
                                + "已进入内存的启动期资源仍需重启游戏)—— 附加包文件更新后点这里,新版本立即生效;"
                                + "仅在重载出现致命异常时才会自动兜底重启游戏;进度显示在顶部状态行。");
            }
        });

        buttonY += elements.get(elements.size() - 1).getHeight() + buttonYPadding;
        elements.add(new ButtonMain("导入附加包文件", rowFont, textPosX, paddingLeft, buttonY, btnW, true) {
            @Override
            public void actionElement() {
                com.saysky.hotplugloader.HotBridge.startImport();
            }

            @Override
            public void buildElementHover() {
                this.menuElementHover = hoverTitleDesc("导入附加包文件",
                        "打开系统文件管理器选择附加包文件(可多选),自动复制进加载目录。\n"
                                + "安卓专用;桌面端直接把文件放进加载目录即可。");
            }
        });

        buttonY += elements.get(elements.size() - 1).getHeight() + buttonYPadding;
        elements.add(new ButtonMain("复制附加包目录路径", rowFont, textPosX, paddingLeft, buttonY, btnW, true) {
            @Override
            public void actionElement() {
                try {
                    Gdx.app.getClipboard().setContents(dirPath);
                    toast("路径已复制: " + dirPath);
                } catch (Throwable t) {
                    SafeLog.e(TAG + ": copy path failed", t);
                }
            }

            @Override
            public void buildElementHover() {
                this.menuElementHover = hoverTitleDesc("复制目录路径",
                        "把加载目录的完整路径复制到剪贴板;粘贴到文件管理器可直接打开。");
            }
        });

        buttonY += elements.get(elements.size() - 1).getHeight() + buttonYPadding;
        elements.add(new ButtonMain("刷新", rowFont, textPosX, paddingLeft, buttonY, btnW, true) {
            @Override
            public void actionElement() {
                PluginScreen.rebuild();
            }

            @Override
            public void buildElementHover() {
                this.menuElementHover = hoverTitleDesc("刷新列表",
                        "重新扫描加载目录(含 dex / json 子目录);把文件放入后点击这里。");
            }
        });

        // ④ 分隔栏「模组列表」(仿「设置」的画面/游戏/音效/地区;自带悬停说明)
        buttonY += elements.get(elements.size() - 1).getHeight() + buttonYPadding;
        elements.add(new Text_Title_v2_TextLR("模组列表", CFG.BUTTON_WIDTH / 4,
                Images.boxTitleBORDERWIDTH, buttonY, sectionW, CFG.TEXT_HEIGHT + CFG.PADDING * 4, "") {
            @Override
            public void buildElementHover() {
                ArrayList<MenuElement_HoverElement> nElements = new ArrayList<MenuElement_HoverElement>();
                ArrayList<MenuElement_HoverElement_Type> nData = new ArrayList<MenuElement_HoverElement_Type>();
                nData.add(new MenuElement_HoverElement_Type_TextTitle_BG_Center("模组列表", CFG.FONT_BOLD, Colors.HOVER_GOLD));
                nElements.add(new MenuElement_HoverElement(nData));
                nData.clear();
                nData.add(new MenuElement_HoverElement_Type_Text_Desc(
                        "附加包 = 外部代码,请自行甄别来源。", CFG.FONT_REGULAR_SMALL, Colors.HOVER_RIGHT));
                nElements.add(new MenuElement_HoverElement(nData));
                nData.clear();
                nData.add(new MenuElement_HoverElement_Type_Line());
                nElements.add(new MenuElement_HoverElement(nData));
                nData.clear();
                nData.add(new MenuElement_HoverElement_Type_Text_Desc(
                        "行首色条:绿=已加载、红=未加载;其余状态(已禁用 / 未信任 / 错误)显示在行内;"
                                + "禁用会记入 registry.json;更新附加包文件后建议重启游戏。",
                        CFG.FONT_REGULAR_SMALL, Colors.HOVER_RIGHT2));
                nElements.add(new MenuElement_HoverElement(nData));
                nData.clear();
                this.menuElementHover = new MenuElement_Hover(nElements);
            }
        });
        buttonY += elements.get(elements.size() - 1).getHeight() + CFG.PADDING;

        // ⑤ 模组行(都在分隔栏「模组列表」下方;间距较紧凑)
        List<File> files = HotPluginHost.scan();
        if (files.isEmpty()) {
            Text_Static empty = new Text_Static("未找到模组文件。放入 .dex/.json 到目录后点「刷新」。",
                    paddingLeft, buttonY, CFG.FONT_REGULAR_SMALL);
            empty.setWidth(btnW);
            elements.add(empty);
            buttonY += CFG.TEXT_HEIGHT + CFG.PADDING;
        } else {
            int shown = Math.min(files.size(), MAX_ROWS);
            int delW = CFG.BUTTON_HEIGHT;
            int delGap = CFG.PADDING;
            int rowW = btnW - delW - delGap;
            MenuElement[] rowRefs = new MenuElement[shown];
            MenuElement[] delRefs = new MenuElement[shown];
            File[] fileRefs = new File[shown];
            int[] elemIdx = new int[shown];
            for (int i = 0; i < shown; i++) {
                final File f = files.get(i);
                elemIdx[i] = elements.size();
                fileRefs[i] = f;
                // 行卡片 = 「加载器设置」同款变色开关(ButtonGame2 + checkBox,
                // 原生绘制行首色条:绿=已加载、红=未加载;状态每帧由 getCheckboxState 实时读取)
                elements.add(new ButtonGame2(clip(rowText(f), LABEL_CLIP), CFG.FONT_REGULAR, textPosX,
                        paddingLeft, buttonY, rowW, true, CFG.BUTTON_HEIGHT, true) {
                    @Override
                    public boolean getCheckboxState() {
                        return rowLoaded(f);
                    }

                    @Override
                    public void actionElement() {
                        if (PluginScreen.consumeRowClickSuppress()) {
                            return; // 长按/滑动会话的松手:不触发加载/禁用
                        }
                        onRowClicked(f);
                    }

                    @Override
                    public void buildElementHover() {
                        this.menuElementHover = rowHover(f);
                    }
                });
                rowRefs[i] = elements.get(elements.size() - 1);
                final int rowH = rowRefs[i].getHeight();
                elements.add(new ButtonGame2("×", CFG.FONT_REGULAR, -1,
                        paddingLeft + rowW + delGap, buttonY, delW, true) {
                    @Override
                    public void actionElement() {
                        if (PluginScreen.consumeRowClickSuppress()) {
                            return; // 滑动会话的松手:不触发删除
                        }
                        onDeleteClicked(f);
                    }

                    @Override
                    public void buildElementHover() {
                        this.menuElementHover = hoverTitleDesc("删除",
                                "移除该模组(" + clip(f.getName(), 28) + "):本体 + 转换产物 + 同名 json 一并删除;"
                                        + "已加载的会先停用;需连点两次确认。不可恢复。");
                    }
                });
                delRefs[i] = elements.get(elements.size() - 1);
                buttonY += rowH + CFG.PADDING;
            }
            ROW_BTNS = rowRefs;
            ROW_DELS = delRefs;
            ROW_FILES = fileRefs;
            ROW_ELEM_INDEXES = elemIdx;
            if (files.size() > shown) {
                Text_Static count = new Text_Static("… 共 " + files.size() + " 个文件", paddingLeft, buttonY, CFG.FONT_REGULAR_SMALL);
                count.setWidth(btnW);
                elements.add(count);
                buttonY += CFG.TEXT_HEIGHT + CFG.PADDING;
            }
        }

        // ===== 镜像版「编辑器」布局:官方在左(W/10),我们在右(W - W/10 - 宽) =====
        int menuX = Math.max(CFG.PADDING, CFG.GAME_WIDTH - CFG.GAME_WIDTH / 10 - CFG.LEFT_MENU_WIDTH);
        int menuY = CFG.GAME_HEIGHT / 10;
        buttonY += elements.get(elements.size() - 1).getHeight() + buttonYPadding;
        int height = Math.min(buttonY + elements.get(0).getHeight() + buttonYPadding * 2,
                CFG.GAME_HEIGHT - titleHeight - menuY - CFG.PADDING * 2);
        initMenu(new MenuTitle("附加包", 1.0f, titleHeight, true, true),
                menuX, titleHeight + menuY, CFG.LEFT_MENU_WIDTH, height, elements, true, false);
        // 固定宽度面板:钉死横向滚动(不重算、只关开关;iMaxSliderPosition* 保持构造值 ——
        //  与主菜单修复同一原则:超宽元素/贴边元素都会把 scrollableX 算成 true)
        ReflectUtil.setBoolean(this, "scrollableX", false);
        applyPendingScroll(); // b26:刷新重建时接力滚动位置(不跳回顶部)
    }

    @Override
    public void draw(SpriteBatch oSB, int iTranslateX, int iTranslateY, boolean menuIsActive, Status titleStatus) {
        // 与官方 Editor 同款的背景绘制(方框 + 编辑器面板底),实现界面风格完全一致
        Renderer.drawBoxCorner(oSB, this.getPosX() + iTranslateX,
                this.getPosY() - this.getTitle().getHeight() + iTranslateY,
                this.getWidth(), this.getHeight() + this.getTitle().getHeight() + CFG.PADDING);
        Renderer.drawEditorMenuBG(oSB, this.getPosX(), this.getPosY(),
                this.getWidth(), this.getHeight() + CFG.PADDING, iTranslateX, iTranslateY);
        super.draw(oSB, iTranslateX, iTranslateY, menuIsActive, titleStatus);
    }

    /** 悬停提示:标题(CenterBG 金色)+ 一段说明(仿「设置→地区」样式)。 */
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

    /** 模组行悬停:名称/状态/文件详情 + 点击说明。 */
    private static MenuElement_Hover rowHover(File f) {
        ArrayList<MenuElement_HoverElement> nElements = new ArrayList<MenuElement_HoverElement>();
        ArrayList<MenuElement_HoverElement_Type> nData = new ArrayList<MenuElement_HoverElement_Type>();
        PluginRegistry.Entry e = PluginRegistry.bySource(f);
        String title = f.getName();
        String id = null;
        String state = "未加载";
        if (e != null && e.manifest != null) {
            title = e.manifest.name + " v" + e.manifest.version;
            id = e.manifest.id;
            switch (e.status) {
                case LOADED:
                    state = "已加载";
                    break;
                case DISABLED:
                    state = "已禁用";
                    break;
                case BLOCKED:
                    state = "未信任";
                    break;
                default:
                    state = "错误: " + (e.error == null ? "?" : e.error);
                    break;
            }
        } else {
            try {
                PluginManifest m = PluginManifest.load(f);
                title = m.name + " v" + m.version;
                id = m.id;
            } catch (Throwable ignored) {
            }
        }
        nData.add(new MenuElement_HoverElement_Type_TextTitle_BG_Center(title, CFG.FONT_BOLD, Colors.HOVER_GOLD));
        nElements.add(new MenuElement_HoverElement(nData));
        nData.clear();
        if (id != null) {
            nData.add(new MenuElement_HoverElement_Type_Text_Desc("id: " + id, CFG.FONT_REGULAR_SMALL, Colors.HOVER_RIGHT));
            nElements.add(new MenuElement_HoverElement(nData));
            nData.clear();
        }
        nData.add(new MenuElement_HoverElement_Type_Text_Desc("状态: " + state, CFG.FONT_REGULAR_SMALL, Colors.HOVER_RIGHT));
        nElements.add(new MenuElement_HoverElement(nData));
        nData.clear();
        if (e != null && e.loadMs > 0) {
            nData.add(new MenuElement_HoverElement_Type_Text_Desc("加载耗时: " + e.loadMs + " ms", CFG.FONT_REGULAR_SMALL, Colors.HOVER_RIGHT2));
            nElements.add(new MenuElement_HoverElement(nData));
            nData.clear();
        }
        nData.add(new MenuElement_HoverElement_Type_Text_Desc("文件: " + LoaderPaths.displayPath(f), CFG.FONT_REGULAR_SMALL, Colors.HOVER_RIGHT2));
        nElements.add(new MenuElement_HoverElement(nData));
        nData.clear();
        nData.add(new MenuElement_HoverElement_Type_Line());
        nElements.add(new MenuElement_HoverElement(nData));
        nData.clear();
        nData.add(new MenuElement_HoverElement_Type_Text_Desc("点击行:未加载 → 加载;已加载 → 禁用;已禁用 → 启用。",
                CFG.FONT_REGULAR_SMALL, Colors.HOVER_RIGHT));
        nElements.add(new MenuElement_HoverElement(nData));
        nData.clear();
        nData.add(new MenuElement_HoverElement_Type_Text_Desc("行尾按钮:删除该模组(本体 + 转换产物 + 同名 json;连点两次确认)。",
                CFG.FONT_REGULAR_SMALL, Colors.HOVER_RIGHT2));
        nElements.add(new MenuElement_HoverElement(nData));
        nData.clear();
        nData.add(new MenuElement_HoverElement_Type_Text_Desc(
                "按住行上下滑动:调整加载顺序(滑得越快、换位越快;列表可滚动时先按住不动约半秒再拖)。",
                CFG.FONT_REGULAR_SMALL, Colors.HOVER_RIGHT2));
        nElements.add(new MenuElement_HoverElement(nData));
        nData.clear();
        return new MenuElement_Hover(nElements);
    }

    /* ------------------------------------------------------------------ */
    /* 行为                                                                */
    /* ------------------------------------------------------------------ */

    private static void onRowClicked(File f) {
        try {
            PluginRegistry.Entry e = PluginRegistry.bySource(f);
            if (e != null && e.manifest != null) {
                if (e.status == PluginRegistry.Status.LOADED) {
                    HotPluginHost.setEnabled(e.manifest.id, false);
                    toast("已禁用: " + e.label());
                    PluginScreen.rebuild();
                    return;
                }
                if (e.status == PluginRegistry.Status.DISABLED) {
                    HotPluginHost.setEnabled(e.manifest.id, true);
                    toast("已启用: " + e.label());
                    PluginScreen.rebuild();
                    return;
                }
            }
            // 安卓上 .jar 不能直接加载:先由内置转换器转换;给出明确指引
            if (HotPluginHost.isAndroid() && isJarFile(f)) {
                java.io.File zip = LoaderPaths.convertedZipFor(f);
                if (zip.isFile() && LoaderPaths.resolveManifest(zip) != null) {
                    toastError("jar 已转换为 " + zip.getName() + ",请点击同名 .zip 行加载");
                } else {
                    // b12:转换器已内置 —— 点行即触发转换(允许重试此前失败的)
                    com.saysky.hotplugloader.convert.ConverterService.scanAndConvertAsync(true);
                    toast("已开始转换 .jar …(完成后点「刷新」查看)");
                }
                return;
            }
            PluginRegistry.Entry r = HotPluginHost.load(f);
            if (r != null && r.status == PluginRegistry.Status.LOADED) {
                toast("已加载: " + r.label());
            } else if (r != null && r.error != null) {
                toastError("加载失败: " + r.error);
            } else {
                toastError("加载失败");
            }
            PluginScreen.rebuild();
        } catch (Throwable t) {
            SafeLog.e(TAG + ": row click failed", t);
            PluginScreen.rebuild();
        }
    }

    /** 行尾删除按钮:首次点击进入确认态,4 秒内再点一次才真删(防误触)。 */
    private static void onDeleteClicked(File f) {
        try {
            long now = System.currentTimeMillis();
            if (pendingDelete == null || !pendingDelete.equals(f) || now - pendingDeleteAt > 4000L) {
                pendingDelete = f;
                pendingDeleteAt = now;
                toast("再点一次确认删除: " + f.getName());
                return;
            }
            pendingDelete = null;
            doDelete(f);
        } catch (Throwable t) {
            SafeLog.e(TAG + ": delete failed " + f.getName(), t);
            toastError("删除失败: " + t.getMessage());
            PluginScreen.rebuild();
        }
    }

    /** 真删:停用 + 移出注册表 + 删本体/清单/转换产物。 */
    private static void doDelete(File f) {
        File json = LoaderPaths.resolveManifest(f);
        File zip = LoaderPaths.convertedZipFor(f);
        File done = new File(f.getParentFile(), f.getName() + ".done");
        File convertedDex = null;
        if (isJarFile(f)) {
            String base = PluginManifest.baseName(f.getName());
            convertedDex = new File(LoaderPaths.importDirFor(base + ".dex"), base + ".dex");
        }
        // 停用 + 移出注册表(本体与转换产物两条来源都查)
        List<PluginRegistry.Entry> entries = new ArrayList<PluginRegistry.Entry>();
        PluginRegistry.Entry e1 = PluginRegistry.bySource(f);
        if (e1 != null) {
            entries.add(e1);
        }
        if (convertedDex != null) {
            PluginRegistry.Entry e2 = PluginRegistry.bySource(convertedDex);
            if (e2 != null && e2 != e1) {
                entries.add(e2);
            }
        }
        for (PluginRegistry.Entry e : entries) {
            if (e.manifest == null) {
                continue;
            }
            PluginRegistry.Entry rm = PluginRegistry.remove(e.manifest.id);
            if (rm != null && rm.instance instanceof com.saysky.hotplugloader.api.HotListener) {
                com.saysky.hotplugloader.api.HotEvents.unregister(
                        (com.saysky.hotplugloader.api.HotListener) rm.instance);
            }
            try {
                PluginStates.setDisabled(e.manifest.id, false);
            } catch (Throwable ignored) {
            }
        }
        // b39:附加包资源目录(resources/<附加包名>/)——先停用,再随附加包一起整目录删除
        String resBase = PluginManifest.baseName(f.getName());
        try {
            com.saysky.hotplugloader.cold.ColdBootstrap.setResourcesActive(resBase, false);
        } catch (Throwable ignored) {
        }
        // 删文件:本体 + 清单 + 转换产物 / 旧命名
        int n = 0;
        n += deleteQuietly(f) ? 1 : 0;
        if (json != null && !json.equals(f)) {
            n += deleteQuietly(json) ? 1 : 0;
        }
        if (zip != null && !zip.equals(f)) {
            n += deleteQuietly(zip) ? 1 : 0;
        }
        n += deleteQuietly(done) ? 1 : 0;
        if (convertedDex != null && !convertedDex.equals(f)) {
            n += deleteQuietly(convertedDex) ? 1 : 0;
        }
        File resDir = new File(LoaderPaths.resourcesDir(), resBase);
        if (resDir.isDirectory()) {
            n += deleteDirRecursive(resDir);
        }
        SafeLog.i(TAG + ": deleted " + LoaderPaths.displayPath(f) + ", " + n + " file(s)");
        toast("已删除: " + f.getName() + "(共 " + n + " 个文件)");
        PluginScreen.rebuild();
    }

    /** 删除单个文件;返回是否真正删掉(不存在/失败 = false)。 */
    private static boolean deleteQuietly(File f) {
        try {
            return f != null && f.isFile() && f.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 递归删除目录(b39;返回删除的文件数)。 */
    private static int deleteDirRecursive(File dir) {
        int n = 0;
        try {
            File[] cs = dir.listFiles();
            if (cs != null) {
                for (File c : cs) {
                    if (c.isDirectory()) {
                        n += deleteDirRecursive(c);
                    } else if (c.isFile() && c.delete()) {
                        n++;
                    }
                }
            }
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        } catch (Throwable ignored) {
        }
        return n;
    }

    /* ------------------------------------------------------------------ */
    /* 行手势支持(b21)                                                     */
    /* ------------------------------------------------------------------ */

    /** 当前面板实例(手势校验:滑动期间面板未被重建)。 */
    static PluginListPanel currentPanel() {
        return currentPanel;
    }

    /** 相邻两行的垂直间距(游戏单位;滑动换位步长用;无行时返回 0)。 */
    static int rowPitch() {
        try {
            if (ROW_BTNS.length > 0 && ROW_BTNS[0] != null) {
                return ROW_BTNS[0].getHeight() + CFG.PADDING;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 长按/滑动目标查询:菜单元素下标 → 对应文件(无则 null)。 */
    static File rowFileAt(int elementIndex) {
        for (int i = 0; i < ROW_ELEM_INDEXES.length && i < ROW_FILES.length; i++) {
            if (ROW_ELEM_INDEXES[i] == elementIndex) {
                return ROW_FILES[i];
            }
        }
        return null;
    }

    /** rebuild 前调用(b26):记录旧面板的滚动偏移(0=顶部;菜单滚动 = iPosY - iMenuPosY)。 */
    static void preserveScrollFrom(Menu old) {
        pendingScrollOffset = -1;
        if (old == null) {
            return;
        }
        try {
            pendingScrollOffset = Math.max(0, old.getPosY() - old.getMenuPosY());
        } catch (Throwable ignored) {
        }
    }

    /** 构造函数末尾调用:恢复重建前记录的滚动偏移(越界由 updateMenuPosY 自行收敛)。 */
    private void applyPendingScroll() {
        int off = pendingScrollOffset;
        pendingScrollOffset = -1;
        if (off <= 0) {
            return;
        }
        try {
            updateMenuPosY(getPosY() - off); // 下一帧 update() 生效并重算元素可见性
            SafeLog.d(TAG + ": scroll restored (" + off + "px)");
        } catch (Throwable t) {
            SafeLog.e(TAG + ": restore scroll failed", t);
        }
    }

    /** 滑动换位一步:交换相邻两槽位的元素高度与引用(视觉即时;数据松手时落盘)。 */
    static boolean stepRow(File f, int dir) {
        try {
            int i = -1;
            for (int k = 0; k < ROW_FILES.length; k++) {
                if (ROW_FILES[k].getAbsolutePath().equals(f.getAbsolutePath())) {
                    i = k;
                    break;
                }
            }
            if (i < 0) {
                return false;
            }
            int j = i + dir;
            if (j < 0 || j >= ROW_FILES.length) {
                return false; // 已到边界:静默停
            }
            swapPosY(ROW_BTNS, i, j);
            swapPosY(ROW_DELS, i, j);
            swapArr(ROW_FILES, i, j);
            swapArr(ROW_BTNS, i, j);
            swapArr(ROW_DELS, i, j);
            swapInt(ROW_ELEM_INDEXES, i, j);
            return true;
        } catch (Throwable t) {
            SafeLog.e(TAG + ": stepRow failed", t);
            return false;
        }
    }

    /** 滑动会话结束:把可见行顺序写回 registry.json(其余文件保持原相对序)。 */
    static void commitRowOrder() {
        try {
            List<File> full = new ArrayList<File>();
            for (File f : ROW_FILES) {
                full.add(f);
            }
            for (File s : HotPluginHost.scan()) {
                boolean seen = false;
                for (File f : ROW_FILES) {
                    if (f.getAbsolutePath().equals(s.getAbsolutePath())) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    full.add(s);
                }
            }
            HotPluginHost.saveOrder(full);
        } catch (Throwable t) {
            SafeLog.e(TAG + ": commitRowOrder failed", t);
        }
    }

    private static void swapPosY(MenuElement[] arr, int i, int j) {
        int y = arr[i].getPosY();
        arr[i].setPosY(arr[j].getPosY());
        arr[j].setPosY(y);
    }

    private static void swapArr(Object[] arr, int i, int j) {
        Object t = arr[i];
        arr[i] = arr[j];
        arr[j] = t;
    }

    private static void swapInt(int[] arr, int i, int j) {
        int t = arr[i];
        arr[i] = arr[j];
        arr[j] = t;
    }

    /** 「全部重载」:原位整层重载(队列化;进度实时显示在面板顶部状态行)。 */
    private static void onReloadAllClicked() {
        try {
            if (LoadQueue.isActive()) {
                toast("重载进行中…");
                return;
            }
            if (!LoadQueue.beginReloadAll()) {
                // 重置/扫描环节出现致命异常 → 兜底整进程重启(等同彻底关闭再打开)
                if (com.saysky.hotplugloader.HotBridge.restartGame()) {
                    toast("重载出现异常,正在重启游戏兜底…");
                    return;
                }
                toastError("重载启动失败(详情见日志)");
            }
            // 完成后由队列统一 toast + 刷新界面
        } catch (Throwable t) {
            SafeLog.e(TAG + ": reload all failed", t);
            if (com.saysky.hotplugloader.HotBridge.restartGame()) {
                toast("重载失败,正在重启游戏兜底…");
                return;
            }
            PluginScreen.rebuild();
        }
    }

    /** 行首变色开关状态(与「加载器设置」开关同款):true=已加载(绿条)、false=未加载(红条)。 */
    private static boolean rowLoaded(File f) {
        PluginRegistry.Entry e = PluginRegistry.bySource(f);
        return e != null && e.status == PluginRegistry.Status.LOADED;
    }

    /** 行文案:显示名 + 状态(错误时附原因;「已加载」由变色开关展示,不重复写进文本)。 */
    private static String rowText(File f) {
        PluginRegistry.Entry e = PluginRegistry.bySource(f);
        if (e != null && e.manifest != null) {
            String label = e.label();
            switch (e.status) {
                case LOADED:
                    return label; // 「已加载」由行首变色开关展示(绿条),文本不重复
                case DISABLED:
                    return label + " · 已禁用";
                case BLOCKED:
                    return label + " · 未信任";
                default:
                    return label + " · 错误: " + (e.error == null ? "?" : e.error);
            }
        }
        // 安卓上的 .jar:未转换 / 已转换直接说明(不再显示"缺少清单";产物+清单齐备才算已转换)
        if (HotPluginHost.isAndroid() && isJarFile(f)) {
            java.io.File zip = LoaderPaths.convertedZipFor(f);
            boolean converted = zip.isFile() && LoaderPaths.resolveManifest(zip) != null;
            return f.getName() + (converted ? " · 已转换(点同名 .zip 行)" : " · 未转换(点行转换)");
        }
        try {
            PluginManifest m = PluginManifest.load(f);
            return m.name + " v" + m.version + " · 未加载";
        } catch (Throwable t) {
            String msg = t.getMessage();
            // 安卓上的单个 .dex 缺清单:load() 会自动解析生成,这里直接给出引导
            if (HotPluginHost.isAndroid() && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".dex")
                    && msg != null && msg.contains("缺少清单")) {
                return f.getName() + " · 待生成清单(点行)";
            }
            return f.getName() + " · " + (msg == null || msg.length() == 0 ? "清单错误" : msg);
        }
    }

    private static void toast(String msg) {
        try {
            Game.menuManager.addToast(msg);
        } catch (Throwable ignored) {
        }
    }

    private static void toastError(String msg) {
        try {
            Game.menuManager.addToast_Error(msg, Images.missions);
        } catch (Throwable ignored) {
        }
    }

    /* ------------------------------------------------------------------ */

    /** 兼容旧接口(设置面板打开时调用):如正处附加包界面,则退回主菜单。 */
    public static void close() {
        PluginScreen.closeIfOpen();
    }

    /* ------------------------------------------------------------------ */

    static int panelWidth() {
        int w = CFG.BUTTON_WIDTH * 2;
        if (w < 520) {
            w = 520;
        }
        if (w > CFG.GAME_WIDTH - CFG.PADDING * 4) {
            w = CFG.GAME_WIDTH - CFG.PADDING * 4;
        }
        return w;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return "…" + s.substring(s.length() - max + 1);
    }

    private static boolean isJarFile(File f) {
        return f != null && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
    }
}
