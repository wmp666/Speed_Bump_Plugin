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
 *     <li>{@link JWindow#setFocusableWindowState(boolean) setFocusableWindowState(false)} +
 *         {@code setAutoRequestFocus(false)}：遮挡层<strong>不会抢焦点</strong>，
 *         因此不会顶替用户正在使用的窗口，也不会干扰检测；</li>
 *     <li>它虽然不抢焦点，但依然接收鼠标消息，所以被遮挡期间点击<strong>不会穿透</strong>到下面的窗口；</li>
 *     <li>层级不用 {@code setAlwaysOnTop}，而是由 {@link WinNative#placeAboveTarget} 把遮罩放到
 *         目标窗口<strong>正上方</strong>，于是“后台窗口的遮罩”不会浮到最前面挡住用户当前在用的窗口；
 *         每轮检测都会重新放一次，以跟随窗口的激活/置顶变化；</li>
 *     <li>万一层级放置失败或复核没通过，退化为<strong>置顶显示</strong>（兜底），
 *         保证遮罩不会出现“被压在目标窗口下面看不见”的情况；恢复正常后会自动取消置顶；</li>
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
    /** 被遮挡的目标窗口句柄（0 表示不做层级调整） */
    private volatile long wantedTarget = 0L;

    /** 以下字段仅在 EDT 上访问 */
    private JWindow window;
    /** 遮罩层自身的 Win32 窗口句柄（缓存，用于层级调整） */
    private long ownHwnd = 0L;
    /** 是否正处于“置顶兜底”状态 */
    private boolean topMostFallback = false;

    /**
     * 在指定矩形上显示遮罩（矩形为 Swing 逻辑坐标）。
     *
     * @param bounds     目标矩形，为 {@code null} 或尺寸非正时等同于 {@link #hide()}
     * @param text       要显示的文字，{@code \n} 表示换行；为空则显示纯色遮罩
     * @param targetHwnd 被遮挡窗口的句柄，用于把遮罩放到它的 z 序正上方（0 表示不调整）
     */
    void showAt(Rectangle bounds, String text, long targetHwnd) {
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            hide();
            return;
        }
        String value = text == null ? "" : text;
        Rectangle target = new Rectangle(bounds);
        boolean changed = !wantedVisible
                || !target.equals(wantedBounds)
                || !value.equals(wantedText)
                || targetHwnd != wantedTarget;
        wantedBounds = target;
        wantedText = value;
        wantedTarget = targetHwnd;
        wantedVisible = true;
        if (changed) {
            SwingUtilities.invokeLater(this::applyState);
        } else {
            // 位置文字都没变，但窗口的激活/置顶状态可能变了：只需要重放一次层级
            SwingUtilities.invokeLater(this::restack);
        }
    }

    /** 隐藏遮罩（幂等） */
    void hide() {
        if (!wantedVisible) {
            return;
        }
        wantedVisible = false;
        wantedTarget = 0L;
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
        restack();
        panel.repaint();
    }

    /**
     * 把遮罩放到目标窗口正上方；失败时用置顶兜底。
     *
     * <p>每轮检测都会调用一次：窗口被激活、被其它窗口覆盖等都会改变 z 序，需要持续跟随。</p>
     */
    private void restack() {
        long target = wantedTarget;
        JWindow w = window;
        if (target == 0L || w == null || !w.isVisible()) {
            return;
        }
        if (ownHwnd == 0L || !WinNative.isWindow(ownHwnd)) {
            // 首次或句柄失效时按“本进程 + 无标题 + 矩形匹配”重新定位自己
            ownHwnd = WinNative.findOwnTitlelessWindow(w.getBounds());
        }
        if (ownHwnd == 0L) {
            return;
        }
        boolean placed = WinNative.placeAboveTarget(ownHwnd, target);
        if (!placed) {
            // 兜底：直接置顶，先保证遮挡看得见（例如目标窗口是管理员进程、层级调用被拒绝时）
            if (!topMostFallback) {
                topMostFallback = WinNative.setTopMost(ownHwnd);
            }
            return;
        }
        if (topMostFallback) {
            // 已经能正常放到目标上方了：取消兜底置顶，恢复跟随目标窗口的普通层级
            WinNative.clearTopMost(ownHwnd);
            topMostFallback = false;
            WinNative.placeAboveTarget(ownHwnd, target);
        }
    }

    private void ensureWindow() {
        if (window != null) {
            return;
        }
        JWindow w = new JWindow();
        // 不抢焦点：否则遮罩自己会成为前台窗口，既干扰用户也不利于检测
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
