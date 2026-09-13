package com.wmp.windowmask;

import com.wmp.downloader.tools.file.DataControl;
import org.apache.log4j.Logger;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「窗口遮挡」检测器：轮询所有顶层窗口，标题命中匹配文字的就套上自定义文字遮罩。
 *
 * <p>职责：</p>
 * <ol>
 *     <li>定时读取配置（{@link DataControl#get(String, Object)}），因此设置页保存后<strong>立即生效</strong>，
 *         不需要重建任何定时器；</li>
 *     <li>每 {@value #TICK_MILLIS} 毫秒枚举一次全机可见的顶层窗口，<strong>不依赖焦点</strong>：
 *         只要某个窗口的标题包含任意一个匹配字段（多个字段用英文分号 {@code ;} 分隔）就被遮挡，
 *         后台窗口同样处理，并把遮罩放到该窗口的 z 序正上方；</li>
 *     <li>窗口移动/缩放时遮罩跟随；窗口关闭、标题变化、被最小化时遮罩立即收起。</li>
 * </ol>
 *
 * <p>实现说明：与「每日金句」同样的思路，不使用 {@link java.util.Timer}，而是一个守护轮询线程；
 * 遮罩窗口不抢焦点，也不会浮到最顶层，因此既不会干扰用户当前在用的窗口，也不会自我干扰检测。</p>
 *
 * @author 无名牌
 */
public class MaskWatcher {

    private static final Logger logger = Logger.getLogger(MaskWatcher.class);

    /** 是否启用遮挡 */
    public static final String KEY_ENABLE = "window_mask.enable";
    /** 匹配文字：判断窗口标题是否包含这些文字，多个用英文分号 ; 分隔 */
    public static final String KEY_MATCH_TEXT = "window_mask.match_text";
    /** 遮挡时显示的文字 */
    public static final String KEY_DISPLAY_TEXT = "window_mask.display_text";

    /** 遮挡时显示文字的默认值 */
    public static final String DEFAULT_DISPLAY_TEXT = "先专注手上的事";

    /** 轮询间隔（毫秒） */
    private static final long TICK_MILLIS = 200L;

    /** 同时最多遮挡的窗口数量（防止匹配文字过于宽泛时创建过多遮罩窗口） */
    private static final int MAX_OVERLAYS = 8;

    private static final MaskWatcher INSTANCE = new MaskWatcher();

    /** 当前活跃的遮罩：窗口句柄 → 遮挡层（仅检测线程访问） */
    private final Map<Long, MaskOverlay> overlays = new LinkedHashMap<>();
    /** 上一轮记过日志的遮挡集合，避免每 200 毫秒写一次日志 */
    private final Set<Long> loggedHwnds = new HashSet<>();

    private volatile boolean started = false;
    /** 测试遮挡的截止时间戳（设置页「试一下」用） */
    private volatile long testUntil = 0L;
    /** 匹配数量超限的日志只记一次 */
    private volatile boolean overflowLogged = false;

    private MaskWatcher() {
    }

    public static MaskWatcher getInstance() {
        return INSTANCE;
    }

    /** 当前环境是否支持窗口遮挡（仅 Windows 可用） */
    public static boolean isSupported() {
        return WinNative.available;
    }

    /**
     * 启动检测线程（幂等）。
     *
     * <p>说明：调用时机是插件主类被主程序加载的瞬间，此时还没有任何窗口相关的前提要求，
     * 检测线程会自行在每一轮读取最新配置。</p>
     * <p>TODO: 若主程序后续提供“插件被停用/应用退出”等生命周期回调，可在此登记线程的停止与遮罩的清理。</p>
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        if (!WinNative.available) {
            logger.warn("「窗口遮挡」未启动：当前环境不支持原生窗口查询（本插件仅支持 Windows）。");
            return;
        }
        Thread.ofVirtual().name("window-mask-worker").start(this::workLoop);
        logger.info("「窗口遮挡」检测线程已启动。");
    }

    /**
     * 试一下：让遮罩在当前前台窗口上显示指定时长（无视匹配文字与启用开关），用于预览效果。
     *
     * @param millis 显示时长（毫秒）
     */
    public void testShow(long millis) {
        if (!WinNative.available) {
            return;
        }
        testUntil = System.currentTimeMillis() + Math.max(500L, millis);
    }

    /** 立即结束测试遮挡 */
    public void stopTest() {
        testUntil = 0L;
    }

    /** 读取当前前台窗口标题（设置页「捕获窗口标题」用） */
    public static String currentForegroundTitle() {
        long hwnd = WinNative.foregroundWindow();
        return hwnd == 0L ? "" : WinNative.windowTitle(hwnd);
    }

    /** 把配置里保存的转义文本还原为真实文本（{@code \n} → 换行） */
    public static String decodeText(String raw) {
        return raw == null ? "" : raw.replace("\\n", "\n");
    }

    /** 把设置页输入的真实文本转义为可保存的单行文本（换行 → {@code \n}） */
    public static String encodeText(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n");
    }

    /**
     * 读取字符串配置。
     *
     * <p>说明：{@link DataControl#get(String, Object)} 在“键存在但值是 null”时会返回 null，
     * 这里统一按“未配置”处理，避免界面上出现字符串 {@code "null"}。</p>
     */
    public static String getString(String key, String defaultValue) {
        Object value = DataControl.get(key, (Object) defaultValue);
        return value == null ? defaultValue : String.valueOf(value);
    }

    /**
     * 解析匹配文字：多个字段用英文分号 {@code ;} 分隔（顺手兼容中文分号与逗号误输入），忽略空字段。
     */
    public static List<String> parseKeywords(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String normalized = raw.replace('；', ';').replace('，', ';');
        for (String part : normalized.split(";")) {
            String keyword = part == null ? "" : part.trim();
            if (!keyword.isEmpty()) {
                result.add(keyword);
            }
        }
        return result;
    }

    /**
     * 解析“是否启用”的配置值（兼容布尔与字符串写法；缺失/空值视为启用）。
     *
     * <p>设置页与检测线程共用同一套判断，避免界面显示与实际行为不一致。</p>
     */
    public static boolean parseEnable(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value);
        return text.isBlank() || Boolean.parseBoolean(text.trim());
    }

    // ==================================================================
    // 检测循环
    // ==================================================================

    private void workLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                tick();
            } catch (Exception e) {
                logger.error("「窗口遮挡」检测过程出错", e);
            }
            sleepQuietly(TICK_MILLIS);
        }
    }

    /** 单次检测：按标题找出所有该遮挡的窗口，并套用遮罩 */
    private void tick() {
        String displayText = decodeText(getString(KEY_DISPLAY_TEXT, DEFAULT_DISPLAY_TEXT));
        applyTargets(collectTargets(), displayText);
    }

    /**
     * 收集本轮需要遮挡的窗口（句柄 → Swing 逻辑矩形）。
     *
     * <p>关键：这里枚举的是<strong>所有可见顶层窗口</strong>，不看谁在前台，
     * 因此“没获得焦点的窗口”同样会被遮挡。</p>
     */
    private Map<Long, Rectangle> collectTargets() {
        Map<Long, Rectangle> targets = new LinkedHashMap<>();

        if (System.currentTimeMillis() < testUntil) {
            // 「试一下」：无视匹配文字，只遮当前前台窗口（方便在主界面上预览效果）
            collectOne(targets, WinNative.foregroundWindow());
            return targets;
        }

        if (!isEnabled()) {
            return targets;
        }
        List<String> keywords = parseKeywords(getString(KEY_MATCH_TEXT, ""));
        if (keywords.isEmpty()) {
            // 未配置匹配文字：静默状态
            return targets;
        }

        int overflow = 0;
        for (WinNative.WindowEntry entry : WinNative.listVisibleWindows()) {
            long hwnd = entry.hwnd();
            String title = entry.title();
            if (title.isEmpty() || !matches(title, keywords)) {
                continue;
            }
            if (WinNative.isOwnWindow(hwnd)) {
                // 安全阀：绝不遮挡减速带自己的窗口（主界面/对话框/遮罩层自身）
                continue;
            }
            if (targets.size() >= MAX_OVERLAYS) {
                overflow++;
                continue;
            }
            collectOne(targets, hwnd);
        }
        logOverflow(overflow);
        return targets;
    }

    /** 收集单个窗口（要求可见、未最小化、矩形有效） */
    private void collectOne(Map<Long, Rectangle> targets, long hwnd) {
        if (hwnd == 0L || !WinNative.isVisible(hwnd) || WinNative.isMinimized(hwnd)) {
            return;
        }
        Rectangle physical = WinNative.windowRect(hwnd);
        if (physical == null || physical.width <= 0 || physical.height <= 0) {
            return;
        }
        targets.put(hwnd, ScreenScale.toLogical(physical));
    }

    /** 套用遮罩：为每个目标窗口显示/更新遮罩，并收起不再命中的遮罩 */
    private void applyTargets(Map<Long, Rectangle> targets, String displayText) {
        for (Map.Entry<Long, Rectangle> entry : targets.entrySet()) {
            long hwnd = entry.getKey();
            MaskOverlay overlay = overlays.get(hwnd);
            if (overlay == null) {
                overlay = new MaskOverlay();
                overlays.put(hwnd, overlay);
            }
            overlay.showAt(entry.getValue(), displayText, hwnd);
        }

        for (Iterator<Map.Entry<Long, MaskOverlay>> it = overlays.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, MaskOverlay> entry = it.next();
            if (!targets.containsKey(entry.getKey())) {
                entry.getValue().hide();
                it.remove();
            }
        }

        if (!targets.keySet().equals(loggedHwnds)) {
            loggedHwnds.clear();
            loggedHwnds.addAll(targets.keySet());
            if (targets.isEmpty()) {
                logger.info("「窗口遮挡」当前没有需要遮挡的窗口。");
            } else {
                logger.info("「窗口遮挡」正在遮挡 " + targets.size() + " 个窗口：" + describe(targets.keySet()));
            }
        }
    }

    private void logOverflow(int overflow) {
        if (overflow > 0 && !overflowLogged) {
            overflowLogged = true;
            logger.warn("「窗口遮挡」匹配到的窗口超过上限 " + MAX_OVERLAYS + " 个，有 " + overflow
                    + " 个未遮挡；建议把匹配文字写得更具体一些。");
        } else if (overflow == 0) {
            overflowLogged = false;
        }
    }

    /** 把窗口标题拼成简短描述（最多 3 个），仅用于日志 */
    private static String describe(Set<Long> hwnds) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (long hwnd : hwnds) {
            if (count++ > 0) {
                builder.append("、");
            }
            builder.append("[").append(WinNative.windowTitle(hwnd)).append("]");
            if (count >= 3) {
                builder.append("…");
                break;
            }
        }
        return builder.toString();
    }

    /** 读取启用开关；配置缺失或为空时视为启用 */
    private boolean isEnabled() {
        return parseEnable(DataControl.get(KEY_ENABLE, Boolean.TRUE));
    }

    /** 标题是否包含任意一个匹配字段（忽略大小写） */
    private static boolean matches(String title, List<String> keywords) {
        if (title == null || title.isEmpty()) {
            return false;
        }
        String lowerTitle = title.toLowerCase();
        for (String keyword : keywords) {
            if (lowerTitle.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
