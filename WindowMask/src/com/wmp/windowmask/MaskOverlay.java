package com.wmp.windowmask;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * 遮挡层：盖住指定窗口并显示自定义文字。
 *
 * <p>实现要点：</p>
 * <ol>
 *     <li>使用无边框的 {@link JWindow}（不出现在任务栏，也不需要标题栏）；</li>
 *     <li>{@code setAlwaysOnTop(true)} 保证盖在目标窗口（当前获得焦点的那个窗口）之上；</li>
 *     <li>{@link JWindow#setFocusableWindowState(boolean) setFocusableWindowState(false)} +
 *         {@code setAutoRequestFocus(false)}：遮挡层<strong>不会抢焦点</strong>，
 *         因此它不会成为「前台窗口」，检测循环看到的仍然是被遮挡的那个窗口，不会出现遮挡/消失抖动；</li>
 *     <li>它虽然不抢焦点，但依然位于最顶层并接收鼠标消息，所以被遮挡期间点击<strong>不会穿透</strong>到下面的窗口；</li>
 *     <li>所有窗口操作都在 EDT 上执行，检测线程只写入 {@code wanted*} 状态。</li>
 * </ol>
 *
 * @author 无名牌
 */
final class MaskOverlay {

    /** 遮罩底色 */
    private static final Color BACKGROUND = new Color(14, 14, 18);
    /** 文字颜色 */
    private static final Color TEXT_COLOR = new Color(238, 238, 244);
    /** 遮罩整体不透明度；系统不支持时退化为完全不透明 */
    private static final float OPACITY = 0.93f;
    /** 文字四周留白 */
    private static final int PADDING = 40;

    private final MaskPanel panel = new MaskPanel();

    /** 期望状态（由检测线程写入，EDT 读取） */
    private volatile boolean wantedVisible = false;
    private volatile Rectangle wantedBounds = null;
    private volatile String wantedText = "";

    private JWindow window;

    /**
     * 在指定矩形上显示遮罩（矩形为 Swing 逻辑坐标）。
     *
     * @param bounds 目标矩形，为 {@code null} 或尺寸非正时等同于 {@link #hide()}
     * @param text   要显示的文字，{@code \n} 表示换行；为空则显示纯色遮罩
     */
    void showAt(Rectangle bounds, String text) {
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            hide();
            return;
        }
        String value = text == null ? "" : text;
        Rectangle target = new Rectangle(bounds);
        // 状态没变化就不必再往 EDT 派发任务（轮询很频繁，这里能省掉大量空转）
        if (wantedVisible && target.equals(wantedBounds) && value.equals(wantedText)) {
            return;
        }
        wantedBounds = target;
        wantedText = value;
        wantedVisible = true;
        SwingUtilities.invokeLater(this::applyState);
    }

    /** 隐藏遮罩（幂等） */
    void hide() {
        if (!wantedVisible) {
            return;
        }
        wantedVisible = false;
        SwingUtilities.invokeLater(this::applyState);
    }

    /** 遮罩当前是否处于显示状态（需要在 EDT 上调用） */
    boolean isShowing() {
        JWindow w = window;
        return w != null && w.isVisible();
    }

    // ==================================================================
    // EDT 侧
    // ==================================================================

    private void applyState() {
        if (!wantedVisible) {
            JWindow w = window;
            if (w != null && w.isVisible()) {
                w.setVisible(false);
            }
            return;
        }
        Rectangle bounds = wantedBounds;
        if (bounds == null) {
            return;
        }
        ensureWindow();
        panel.setText(wantedText);
        if (!bounds.equals(window.getBounds())) {
            window.setBounds(bounds);
        }
        if (!window.isVisible()) {
            window.setVisible(true);
        }
        panel.repaint();
    }

    private void ensureWindow() {
        if (window != null) {
            return;
        }
        JWindow w = new JWindow();
        w.setAlwaysOnTop(true);
        // 不抢焦点：否则遮挡层自己会成为前台窗口，检测循环会误判为“不匹配”而立刻收起遮罩
        w.setFocusableWindowState(false);
        w.setAutoRequestFocus(false);
        w.setLayout(new BorderLayout());
        w.add(panel, BorderLayout.CENTER);
        try {
            w.setOpacity(OPACITY);
        } catch (Throwable ignored) {
            // 系统不支持窗口不透明度：保持不透明，功能不受影响
        }
        window = w;
    }

    /**
     * 遮罩内容面板：纯色底 + 居中文字，字号按窗口尺寸自适应。
     */
    private static final class MaskPanel extends JPanel {

        private String text = "";

        MaskPanel() {
            setOpaque(true);
            setBackground(BACKGROUND);
            setFocusable(false);
        }

        void setText(String value) {
            String v = value == null ? "" : value;
            if (!v.equals(text)) {
                text = v;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (text.isEmpty() || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                String[] lines = text.split("\n", -1);
                int maxWidth = Math.max(40, getWidth() - PADDING * 2);
                int maxHeight = Math.max(32, getHeight() - PADDING * 2);

                Font font = pickFont(g2, lines, maxWidth, maxHeight);
                g2.setFont(font);
                g2.setColor(TEXT_COLOR);

                FontMetrics fm = g2.getFontMetrics();
                int lineHeight = (int) Math.round(fm.getHeight() * 1.25);
                int totalHeight = lineHeight * lines.length;
                int y = (getHeight() - totalHeight) / 2 + fm.getAscent();
                for (String line : lines) {
                    int width = fm.stringWidth(line);
                    g2.drawString(line, (getWidth() - width) / 2, y);
                    y += lineHeight;
                }
            } finally {
                g2.dispose();
            }
        }

        /**
         * 按窗口尺寸挑选尽量大的字号：从窗口高度的 1/5 起逐步缩小，直到所有行都能完整放下。
         */
        private Font pickFont(Graphics2D g2, String[] lines, int maxWidth, int maxHeight) {
            int size = Math.max(16, Math.min((int) (getHeight() / 5.0), 120));
            while (size > 12) {
                Font candidate = new Font(Font.SANS_SERIF, Font.BOLD, size);
                FontMetrics fm = g2.getFontMetrics(candidate);
                int lineHeight = (int) Math.round(fm.getHeight() * 1.25);
                int widest = 0;
                for (String line : lines) {
                    widest = Math.max(widest, fm.stringWidth(line));
                }
                if (widest <= maxWidth && lineHeight * lines.length <= maxHeight) {
                    return candidate;
                }
                size -= 2;
            }
            return new Font(Font.SANS_SERIF, Font.BOLD, 12);
        }
    }
}
