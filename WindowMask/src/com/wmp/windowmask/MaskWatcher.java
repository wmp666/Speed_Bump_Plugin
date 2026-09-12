package com.wmp.windowmask;

import com.wmp.downloader.tools.file.DataControl;
import org.apache.log4j.Logger;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * 「窗口遮挡」检测器：轮询当前<strong>获得焦点的窗口</strong>（前台窗口），命中匹配文字时盖上一层自定义文字遮罩。
 *
 * <p>职责：</p>
 * <ol>
 *     <li>定时读取配置（{@link DataControl#get(String, Object)}），因此设置页保存后<strong>立即生效</strong>，
 *         不需要重建任何定时器；</li>
 *     <li>每 {@value #TICK_MILLIS} 毫秒取一次前台窗口标题，标题包含任意一个匹配字段即视为命中
 *         （匹配字段支持多个，用英文分号 {@code ;} 分隔）；</li>
 *     <li>命中时把 {@link MaskOverlay} 以该窗口的矩形显示出来，窗口移动/缩放时遮罩跟随；</li>
 *     <li>不命中、被最小化或已切走时立即隐藏遮罩。</li>
 * </ol>
 *
 * <p>实现说明：与「每日金句」同样的思路，不使用 {@link java.util.Timer}，而是一个守护轮询线程；
 * 遮罩窗口不抢焦点，所以它不会顶替前台窗口，检测结果不会自我干扰。</p>
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

    private static final MaskWatcher INSTANCE = new MaskWatcher();

    private final MaskOverlay overlay = new MaskOverlay();

    private volatile boolean started = false;
    /** 测试遮挡的截止时间戳（设置页「试一下」用） */
    private volatile long testUntil = 0L;
    /** 当前正被遮挡的窗口句柄，0 表示未遮挡（仅用于状态日志，避免刷屏） */
    private volatile long maskedHwnd = 0L;

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

    /** 单次检测：决定此刻该不该遮挡当前前台窗口 */
    private void tick() {
        long hwnd = resolveTarget(System.currentTimeMillis() < testUntil);
        if (hwnd == 0L) {
            clearMask();
            return;
        }
        Rectangle physical = WinNative.windowRect(hwnd);
        if (physical == null || physical.width <= 0 || physical.height <= 0) {
            clearMask();
            return;
        }
        overlay.showAt(ScreenScale.toLogical(physical),
                decodeText(getString(KEY_DISPLAY_TEXT, DEFAULT_DISPLAY_TEXT)));

        if (maskedHwnd != hwnd) {
            maskedHwnd = hwnd;
            logger.info("「窗口遮挡」已遮挡窗口：" + WinNative.windowTitle(hwnd));
        }
    }

    /**
     * 判断此刻应当遮挡哪个窗口。
     *
     * <p>只看<strong>当前获得焦点的窗口</strong>（{@code GetForegroundWindow}）：它可见、未最小化、
     * 标题命中任意匹配字段时就返回它，否则返回 0 表示不遮挡。</p>
     *
     * @param testing 是否处于设置页的「试一下」预览中（预览时无视匹配文字与启用开关）
     */
    private long resolveTarget(boolean testing) {
        long hwnd = WinNative.foregroundWindow();
        if (hwnd == 0L || !WinNative.isVisible(hwnd) || WinNative.isMinimized(hwnd)) {
            return 0L;
        }
        if (testing) {
            return hwnd;
        }
        if (!isEnabled()) {
            return 0L;
        }
        List<String> keywords = parseKeywords(getString(KEY_MATCH_TEXT, ""));
        if (keywords.isEmpty()) {
            // 未配置匹配文字：静默状态
            return 0L;
        }
        if (!matches(WinNative.windowTitle(hwnd), keywords)) {
            return 0L;
        }
        if (WinNative.isOwnWindow(hwnd)) {
            // 安全阀：绝不遮挡减速带自己的窗口（主界面/对话框），
            // 否则用户可能连设置页都点不到，只能靠改配置文件恢复。
            return 0L;
        }
        return hwnd;
    }

    private void clearMask() {
        if (maskedHwnd != 0L) {
            maskedHwnd = 0L;
            logger.info("「窗口遮挡」遮挡已解除。");
        }
        overlay.hide();
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
