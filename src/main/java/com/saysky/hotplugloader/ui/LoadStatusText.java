package com.saysky.hotplugloader.ui;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.saysky.hotplugloader.LoadStatus;

import aoc.kingdoms.lukasz.menu_element.textStatic.Text_Static;

/**
 * 实时状态行(b18):绘制前把 {@link LoadStatus#display()} 同步进文本;
 * 仅在文本变化时 setText(并重新钉宽 —— Text_Static.setText 会因更长文本把宽度调大,
 * 不钉回会破坏"防横向滚动"的不变量)。
 */
public class LoadStatusText extends Text_Static {

    private final int fixedWidth;
    private String shown;

    public LoadStatusText(String initial, int iPosX, int iPosY, int fontID, int width) {
        super(initial, iPosX, iPosY, fontID);
        fixedWidth = width;
        shown = initial;
        setWidth(width);
    }

    @Override
    public void draw(SpriteBatch oSB, int iTranslateX, int iTranslateY, boolean isActive, boolean scrollableY) {
        try {
            String now = LoadStatus.display();
            if (!now.equals(shown)) {
                shown = now;
                setText(now);
                setWidth(fixedWidth);
            }
        } catch (Throwable ignored) {
        }
        super.draw(oSB, iTranslateX, iTranslateY, isActive, scrollableY);
    }
}
