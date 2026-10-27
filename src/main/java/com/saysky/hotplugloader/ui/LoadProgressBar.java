package com.saysky.hotplugloader.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.saysky.hotplugloader.LoadStatus;

import aoc.kingdoms.lukasz.jakowski.Renderer.Renderer;
import aoc.kingdoms.lukasz.menu_element.MenuElement;
import aoc.kingdoms.lukasz.textures.Images;

/**
 * 进度条元素(b18;b25 观感修正):加载 = 精确比例填充;转换 = 扫动段
 * (D8 是一次黑盒调用、没有进度回调,不做假百分比)。
 *
 * <p>全程自绘(pix 拉伸 + 原生 drawBoxLineFrame 边框),不依赖新美术资源。
 * b25:条高由调用方传 16px;边框/轨道提亮(空闲时也清晰可见);填充内缩随高度自适应。</p>
 */
public class LoadProgressBar extends MenuElement {

    private static final Color TRACK = new Color(0.0f, 0.0f, 0.0f, 0.55f);
    private static final Color FILL = new Color(0.90f, 0.72f, 0.28f, 1.0f);
    private static final Color FRAME = new Color(0.72f, 0.60f, 0.35f, 1.0f);

    public LoadProgressBar(int iPosX, int iPosY, int width, int height) {
        setPosX(iPosX);
        setPosY(iPosY);
        setWidth(width);
        setHeight(height);
    }

    @Override
    public void draw(SpriteBatch oSB, int iTranslateX, int iTranslateY, boolean isActive, boolean scrollableY) {
        try {
            int x = getPosX() + iTranslateX;
            int y = getPosY() + iTranslateY;
            int w = getWidth();
            int h = getHeight();
            if (w <= 2 || h <= 2) {
                return;
            }
            int inset = Math.max(1, h / 6); // b25:填充内缩随高度自适应(16px 高条留 2px)
            int innerW = Math.max(1, w - inset * 2);
            oSB.setColor(TRACK);
            Images.pix.draw(oSB, x, y, w, h);
            if (LoadStatus.loadActive()) {
                int fw = (int) (innerW * LoadStatus.loadFraction());
                if (fw > 0) {
                    oSB.setColor(FILL);
                    Images.pix.draw(oSB, x + inset, y + inset, fw, h - inset * 2);
                }
            } else if (LoadStatus.convActive()) {
                // 扫动段:左→右无缝循环
                int seg = Math.max(24, innerW / 5);
                int p = (int) (System.currentTimeMillis() % (long) (innerW + seg)) - seg;
                int sx = Math.max(p, 0);
                int ex = Math.min(p + seg, innerW);
                if (ex > sx) {
                    oSB.setColor(FILL);
                    Images.pix.draw(oSB, x + inset + sx, y + inset, ex - sx, h - inset * 2);
                }
            }
            Renderer.drawBoxLineFrame(oSB, x, y, w, h, FRAME);
            oSB.setColor(Color.WHITE);
        } catch (Throwable ignored) {
        }
    }
}
