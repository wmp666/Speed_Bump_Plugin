package com.wmp.juzi;

import com.wmp.downloader.tools.file.DataControl;
import com.wmp.downloader.ui.Downloader;
import org.apache.log4j.Logger;

import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 「每日金句」调度器。
 *
 * <p>职责：</p>
 * <ol>
 *     <li>从插件自带文本文件 {@code /com/wmp/resource/text.txt}（每行一句）读取金句库；</li>
 *     <li>读取配置（使用 {@link DataControl#get(String, Object)}），支持两种显示方式：</li>
 *     <li>启动时显示一次，或在多个固定时间点（分号 {@code ;} 分隔）每日推送；</li>
 *     <li>通过系统托盘 {@link Downloader#trayIcon} 弹出系统通知。</li>
 * </ol>
 *
 * <p>由于减速带尚未为第三方功能插件提供明确的“启动完成”生命周期回调，本类通过守护线程
 * 轮询托盘就绪状态后自行启动调度；若日后主程序提供对应回调，可改用回调接入（见 TODO）。</p>
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

    private static final JuziReminder INSTANCE = new JuziReminder();

    private final List<String> quotes = new ArrayList<>();
    private volatile boolean started = false;
    private volatile Timer timer;
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
     * 因此启动一个守护线程轮询等待托盘可用后再执行显示与排程。</p>
     * <p>TODO: 若主程序后续提供“应用启动完成”等生命周期回调，应改为在回调中调用本方法，替代轮询。</p>
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;

        loadQuotes();

        Thread.ofVirtual().start(() -> {
            // 轮询等待系统托盘与 trayIcon 就绪（最多约 30 秒）
            long deadline = System.currentTimeMillis() + 30_000L;
            while (System.currentTimeMillis() < deadline) {
                if (Downloader.trayIcon != null && SystemTray.isSupported()) {
                    planByConfig();
                    return;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("等待托盘就绪被打断", e);
                    return;
                }
            }
            logger.warn("等待 Downloader.trayIcon 就绪超时，「每日金句」本次未启动。");
        });
    }

    /**
     * 读取配置并执行：启动时显示 / 定时排程。
     */
    public synchronized void planByConfig() {
        cancelAllTasks();

        String showWay = DataControl.get(KEY_SHOW_WAY, VALUE_START);
        logger.info("「每日金句」显示方式配置 show_way=" + showWay);

        if (VALUE_START.equalsIgnoreCase(String.valueOf(showWay))) {
            // 启动时：显示一次（若需每日一次可改每日排程，见 TODO）
            // TODO: 若希望“每天启动后仅当天推送一次”，需由主程序记录“今日是否已推送”，这里仅保留启动即推一次。
            showOneRandom();
        } else {
            // 固定时间点：解析分号分隔的 HH:mm，为每个时间点安排每日循环任务
            String rawTimes = DataControl.get(KEY_SHOW_TIMES, "");
            List<LocalTime> times = parseTimes(String.valueOf(rawTimes));
            if (times.isEmpty()) {
                logger.warn("固定时间点配置为空或格式不正确，未排程。配置=" + rawTimes);
                return;
            }
            for (LocalTime time : times) {
                scheduleDaily(time);
            }
            logger.info("「每日金句」已排程 " + times.size() + " 个每日时间点：" + times);
        }
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
     * 为单个时间点安排“每日重复”任务。
     */
    private void scheduleDaily(LocalTime time) {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                showOneRandom();
                reschedule(time, this);
            }
        }, msUntilNext(time));
    }

    /**
     * 任务执行后，将同一个任务安排到下一个该时间点（跨天）。
     */
    private void reschedule(LocalTime time, TimerTask task) {
        // 计算从当前时刻起到「下一个该时间点」的延时；若今天该时间已过则顺延到明天。
        timer.schedule(task, msUntilNext(time));
    }

    private long msUntilNext(LocalTime target) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.with(target);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }

    /**
     * 取消所有已排程的定时任务（配置变更后重新排程时使用），并新建一个定时器。
     */
    private void cancelAllTasks() {
        Timer oldTimer = timer;
        timer = new Timer("daily-juzi-timer", true);
        if (oldTimer != null) {
            oldTimer.cancel();
        }
    }

    /**
     * 随机顺序展示一句金句（轮换取下一句，避免重复）。若没有读取到任何金句则跳过。
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
     * 试看：立即推送一句金句（供设置页预览使用，不修改排程）。
     */
    public synchronized void testShow() {
        if (!SystemTray.isSupported() || Downloader.trayIcon == null) {
            showNotify("每日金句", "托盘尚未就绪，暂无法预览。");
            return;
        }
        showOneRandom();
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
