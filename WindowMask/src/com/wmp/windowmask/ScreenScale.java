package com.wmp.windowmask;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;

/**
 * 屏幕缩放换算。
 *
 * <p>Win32 的 {@code GetWindowRect} 返回<strong>物理像素</strong>，而 Swing 用的是<strong>逻辑坐标</strong>
 * （系统界面缩放不为 100% 时两者不同）。本类负责两者互转，并缓存缩放值。</p>
 *
 * <p>TODO: 这里用的是主屏的界面缩放；多显示器且各屏缩放不同时换算会有偏差，
 * 后续可改为按目标窗口所在屏幕（或 {@code GetDpiForWindow}）取缩放。</p>
 *
 * @author 无名牌
 */
final class ScreenScale {

    /** 缓存值，-1 表示尚未探测 */
    private static volatile double cached = -1.0;

    private ScreenScale() {
    }

    /** 界面缩放倍数（100% 时为 1.0；取不到按 1.0 处理） */
    static double scale() {
        double value = cached;
        if (value > 0) {
            return value;
        }
        double scale = 1.0;
        try {
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            AffineTransform transform = gc.getDefaultTransform();
            if (transform != null && transform.getScaleX() > 0) {
                scale = transform.getScaleX();
            }
        } catch (Throwable t) {
            scale = 1.0;
        }
        cached = scale;
        return scale;
    }

    /** 物理像素矩形 → Swing 逻辑矩形 */
    static Rectangle toLogical(Rectangle physical) {
        if (physical == null) {
            return null;
        }
        double scale = scale();
        if (Math.abs(scale - 1.0) < 1e-6) {
            return new Rectangle(physical);
        }
        return new Rectangle(
                (int) Math.round(physical.x / scale),
                (int) Math.round(physical.y / scale),
                (int) Math.round(physical.width / scale),
                (int) Math.round(physical.height / scale));
    }

    /** Swing 逻辑矩形 → 物理像素矩形 */
    static Rectangle toPhysical(Rectangle logical) {
        if (logical == null) {
            return null;
        }
        double scale = scale();
        if (Math.abs(scale - 1.0) < 1e-6) {
            return new Rectangle(logical);
        }
        return new Rectangle(
                (int) Math.round(logical.x * scale),
                (int) Math.round(logical.y * scale),
                (int) Math.round(logical.width * scale),
                (int) Math.round(logical.height * scale));
    }
}
