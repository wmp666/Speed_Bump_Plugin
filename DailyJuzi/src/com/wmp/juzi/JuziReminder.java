package com.wmp.juzi;

import com.wmp.downloader.tools.file.DataControl;
import com.wmp.downloader.ui.Downloader;
import org.apache.log4j.Logger;

import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「每日金句」调度器。
 *
 * <p>职责：</p>
 * <ol>
 *     <li>从插件自带文本文件 {@code /com/wmp/resource/text.txt}（每行一句）读取金句库；</li>
 *     <li>读取配置（使用 {@link DataControl#get(String, Object)}），支持两种显示方式：</li>
 *     <li>启动时显示，或每日在多个固定时间点（分号 {@code ;} 分隔）推送；</li>
 *     <li>通过系统托盘 {@link Downloader#trayIcon} 弹出系统通知。</li>
 * </ol>
 *
 * <p>实现说明：为避免 {@link java.util.Timer}/{@link java.util.TimerTask} 复用已执行任务、
 * 单定时线程被异常杀死等造成的“只显示一次/后续不触发”问题，本类改用<strong>单个守护线程按秒轮询</strong>：
 * 每当系统时间走到某个已配置的 HH:mm 且当日尚未推送过即推送一次；跨天自动清空“今日已推”，因此能持续每天触发，
 * 且每次读取最新配置（保存设置即时生效，无需重建任何定时器）。</p>
 *
 * @author 吴鹤轩
 */
public class JuziReminder {

    private static final Logger logger = Logger.getLogger(JuziReminder.class);

    /** 金句文本（classpath 资源，随插件 jar 打包） */
    public static final String TEXT_RESOURCE = "/com/wmp/resource/text.txt";

    /** 显示方式 key：start=启动时显示，time=固定时间点显示 */
    public static final String KEY_SHOW_WAY = "great_juzi.show_way";
    /** 固定时间点 key：多个用英文分号 ; 分隔，如 "09:00;12:00;21:30" */
    public static final String KEY_SHOW_TIMES = "great_juzi.show_times";

    public static final String VALUE_START = "start";
    public static final String VALUE_TIME = "time";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    /** 轮询间隔（毫秒） */
    private static final long TICK_MILLIS = 250L;

    private static final JuziReminder INSTANCE = new JuziReminder();

    private final List<String> quotes = new ArrayList<>();
    private volatile boolean started = false;

    /** 守护轮询线程 */
    private volatile Thread worker;
    /** 仅保存当前模式快照，避免每次读 DataControl 带来的不确定类型 */
    private volatile String currentShowWay = VALUE_START;
    /** start 模式下“本次启动已推送过”标记 */
    private volatile boolean startFiredInSession = false;
    /** 去重用的“最近一次检查的日期”与“当日已推送时间点” */
    private LocalDate lastDay = null;
    private final Set<LocalTime> firedToday = new HashSet<>();

    private int quoteIndex = 0;

    private JuziReminder() {
    }

    public static JuziReminder getInstance() {
        return INSTANCE;
    }

    /**
     * 启动提醒逻辑（幂等）。
     *
     * <p>说明：调用时机是插件主类被主程序实例化的瞬间，此时 {@link Downloader#trayIcon} 可能尚未就绪，
     * 因此轮询线程会等待托盘可用后再开始工作。</p>
     * <p>TODO: 若主程序后续提供“应用启动完成”等生命周期回调，可改为在回调中调用本方法。</p>
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;

        loadQuotes();

        worker = Thread.ofVirtual().name("daily-juzi-worker").start(this::workLoop);
    }

    /**
     * 主循环：等待托盘就绪后，按秒轮询执行“启动时显示”或“固定时间点”推送。
     */
    private void workLoop() {
        // 等待系统托盘与 trayIcon 就绪（最多约 30 秒）
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Downloader.trayIcon != null && SystemTray.isSupported()) {
                break;
            }
            sleepQuietly(500L);
        }
        if (Downloader.trayIcon == null || !SystemTray.isSupported()) {
            logger.warn("等待 Downloader.trayIcon 就绪超时，「每日金句」本次未启动。");
            return;
        }
        logger.info("「每日金句」轮询线程已就绪。");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                String showWay = String.valueOf(DataControl.get(KEY_SHOW_WAY, VALUE_START));
                currentShowWay = (VALUE_TIME.equalsIgnoreCase(showWay)) ? VALUE_TIME : VALUE_START;

                LocalDate today = LocalDate.now();
                if (!today.equals(lastDay)) {
                    // 跨天：重置“今日已推送”集合
                    lastDay = today;
                    firedToday.clear();
                    logger.debug("「每日金句」已切换到新的一天：" + today);
                }

                if (VALUE_START.equals(currentShowWay)) {
                    // 启动时：本次进程只推一次（跨天也仅当本次是新会话首次推进时触发）
                    if (!startFiredInSession) {
                        showOneRandom();
                        startFiredInSession = true;
                    }
                } else {
                    // 固定时间点：准点触发——当前时刻所在“分钟”命中某个配置时间点且当日尚未推过，则推一次
                    List<LocalTime> times = parseTimes(String.valueOf(DataControl.get(KEY_SHOW_TIMES, "")));
                    if (times.isEmpty()) {
                        // 未配置时间点：等待，不推
                    } else {
                        LocalTime now = LocalTime.now();
                        for (LocalTime t : times) {
                            if (sameMinute(now, t) && firedToday.add(t)) {
                                // 时间点已到达（当前处于该分钟的轮询窗口）且当日尚未推过
                                showOneRandom();
                                logger.info("「每日金句」已在时间点触发：" + t);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("「每日金句」轮询过程出错", e);
            }
            sleepQuietly(TICK_MILLIS);
        }
    }

    /**
     * 按当前配置立即执行一次“该做的动作”（供设置页保存后调用）：
     * <ul>
     *     <li>start 模式：把“已推送”标记复位并立即推一次（等效一次新的启动）；</li>
     *     <li>time 模式：无需特判，轮询线程每次都会读最新配置，保存即时生效。</li>
     * </ul>
     */
    public synchronized void planByConfig() {
        String showWay = String.valueOf(DataControl.get(KEY_SHOW_WAY, VALUE_START));
        currentShowWay = (VALUE_TIME.equalsIgnoreCase(showWay)) ? VALUE_TIME : VALUE_START;
        if (VALUE_START.equals(currentShowWay)) {
            startFiredInSession = false;
            if (Downloader.trayIcon != null && SystemTray.isSupported()) {
                showOneRandom();
                startFiredInSession = true;
            }
        }
    }

    /**
     * 试看：立即推送一句金句（供设置页预览使用，不影响排程状态）。
     */
    public synchronized void testShow() {
        if (!SystemTray.isSupported() || Downloader.trayIcon == null) {
            showNotify("每日金句", "托盘尚未就绪，暂无法预览。");
            return;
        }
        showOneRandom();
    }

    /**
     * 解析“HH:mm;HH:mm”形式的时间点列表（支持中文分号误输入，自动替换为英文分号）。
     */
    private List<LocalTime> parseTimes(String raw) {
        List<LocalTime> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String normalized = raw.replace('；', ';').replace('，', ',');
        String[] parts = normalized.split("[,;]");
        for (String part : parts) {
            String s = part == null ? "" : part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                result.add(LocalTime.parse(s, TIME_FORMAT));
            } catch (Exception e) {
                logger.warn("无法解析时间点：" + s);
            }
        }
        return result;
    }

    /**
     * 顺序展示一句金句（轮换取下一句，避免重复）。若没有读取到任何金句则跳过。
     */
    private synchronized void showOneRandom() {
        if (quotes.isEmpty()) {
            logger.warn("金句库为空，未推送。请检查 " + TEXT_RESOURCE + " 是否包含内容。");
            return;
        }
        String quote = quotes.get(quoteIndex % quotes.size());
        quoteIndex = (quoteIndex + 1) % quotes.size();
        showNotify("每日金句", quote);
    }

    /**
     * 通过系统托盘弹出一条系统通知。
     */
    private void showNotify(String title, String message) {
        if (!SystemTray.isSupported() || Downloader.trayIcon == null) {
            logger.warn("系统托盘不可用，无法弹出通知：「每日金句」- " + message);
            return;
        }
        try {
            Downloader.trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        } catch (Exception e) {
            logger.error("托盘通知发送失败", e);
        }
    }

    /**
     * 判断两个时刻是否处于同一分钟（用于“准点触发”判断）。
     */
    private static boolean sameMinute(LocalTime now, LocalTime target) {
        return now.getHour() == target.getHour() && now.getMinute() == target.getMinute();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 从插件自带文本文件加载金句库（每行一句；支持 # 注释行与空行忽略）。
     */
    private void loadQuotes() {
        quotes.clear();
        try (var is = DailyJuzi.class.getResourceAsStream(TEXT_RESOURCE)) {
            if (is == null) {
                logger.warn("未找到金句文件：" + TEXT_RESOURCE);
                return;
            }
            try (var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String quote = line.trim();
                    if (quote.isEmpty() || quote.startsWith("#")) {
                        continue;
                    }
                    quotes.add(quote.replace("\\n", "\n"));
                }
            }
        } catch (IOException e) {
            logger.error("读取金句文件失败：" + TEXT_RESOURCE, e);
        }
        logger.info("「每日金句」已加载 " + quotes.size() + " 条内容。");
    }
}
