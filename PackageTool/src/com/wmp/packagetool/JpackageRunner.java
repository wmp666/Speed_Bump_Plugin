package com.wmp.packagetool;

import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * jpackage 执行器：在后台虚拟线程里启动外部进程，逐行把输出回传出去。
 *
 * <p>调用方（设置页）负责把回调切到 EDT，本类不碰 Swing。</p>
 *
 * @author 无名牌
 */
public final class JpackageRunner {

    private static final Logger logger = Logger.getLogger(JpackageRunner.class);

    /** 子进程输出的解码字符集 */
    private static final Charset OUTPUT_CHARSET = resolveOutputCharset();

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Process process;
    private volatile boolean cancelled;

    /** 是否正在打包 */
    public boolean isRunning() {
        return running.get();
    }

    /** 上一次执行是否被用户取消 */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 启动打包。同一时间只允许一个任务。
     *
     * @param command  完整命令行（含可执行文件）
     * @param workDir  工作目录，可为 {@code null}
     * @param onLine   每读到一行输出回调一次（在后台线程）
     * @param onFinish 结束时回调：第一个参数是退出码，第二个参数表示是否被取消（在后台线程）
     * @return 是否成功启动（已在运行时返回 {@code false}）
     */
    public boolean start(List<String> command, File workDir, Consumer<String> onLine,
                         BiConsumer<Integer, Boolean> onFinish) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        cancelled = false;

        Thread.ofVirtual().name("jpackage-runner").start(() -> {
            int exitCode = -1;
            try {
                onLine.accept("> " + JpackageCommand.toDisplayString(command));

                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true);
                if (workDir != null && workDir.isDirectory()) {
                    builder.directory(workDir);
                }

                process = builder.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), OUTPUT_CHARSET))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        onLine.accept(line);
                    }
                }
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled = true;
                onLine.accept("打包已被中断。");
            } catch (Exception e) {
                onLine.accept("启动 jpackage 失败：" + e);
                logger.error("启动 jpackage 失败", e);
            } finally {
                process = null;
                running.set(false);
                onFinish.accept(exitCode, cancelled);
            }
        });
        return true;
    }

    /** 取消当前打包（强制结束子进程） */
    public void cancel() {
        cancelled = true;
        Process current = process;
        if (current != null) {
            current.destroy();
            if (current.isAlive()) {
                current.destroyForcibly();
            }
        }
    }

    /**
     * 子进程输出的编码：Windows 上通常不是 UTF-8，优先用 {@code native.encoding}
     * （即系统 ANSI 代码页，如 GBK），取不到才退回默认字符集。
     *
     * <p>TODO: 若某些环境下 jpackage 的输出仍出现乱码，可在设置页加一个“输出编码”选项，
     * 或改为把子进程输出重定向到文件后按 UTF-8 读取。</p>
     */
    private static Charset resolveOutputCharset() {
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null && !nativeEncoding.isBlank()) {
            try {
                return Charset.forName(nativeEncoding);
            } catch (Exception ignored) {
                // 认不出来就退回默认
            }
        }
        return Charset.defaultCharset();
    }
}
