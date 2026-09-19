package com.wmp.packagetool;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 定位 {@code jpackage} 可执行文件。
 *
 * <p>为什么要专门做探测：jpackage 是 <strong>JDK</strong> 自带的（不是 JRE），而减速带本体是以
 * jpackage 打包出来的运行时启动的（{@code java.home} 指向的是精简运行时，里面没有 jpackage），
 * 用户机器上 jpackage 也往往不在 {@code PATH} 里。因此按下列顺序查找：</p>
 *
 * <ol>
 *     <li>用户在设置页里手填的路径；</li>
 *     <li>{@code JAVA_HOME/bin}；</li>
 *     <li>当前运行时 {@code java.home} 及其上一级的 {@code bin}；</li>
 *     <li>{@code PATH} 中的每一项；</li>
 *     <li>常见 JDK 安装位置（{@code C:\Program Files\Java\jdk-*} 等），取版本号最大的一个。</li>
 * </ol>
 *
 * @author 无名牌
 */
public final class JpackageLocator {

    /** 当前平台的 jpackage 文件名 */
    private static final String EXECUTABLE_NAME =
            isWindows() ? "jpackage.exe" : "jpackage";

    /** Windows 上常见的 JDK 安装根目录 */
    private static final String[] COMMON_JDK_ROOTS = {
            "C:\\Program Files\\Java",
            "C:\\Program Files\\Eclipse Adoptium",
            "C:\\Program Files\\Microsoft",
            "C:\\Program Files\\Amazon Corretto",
            "C:\\Program Files\\Zulu",
            "C:\\Program Files\\BellSoft",
            "C:\\Program Files\\Semeru"
    };

    private JpackageLocator() {
    }

    /** 是否为 Windows 平台 */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 探测可用的 jpackage。
     *
     * @param preferredPath 用户手填的路径，可为空
     * @return 找到的 jpackage；找不到返回 {@code null}
     */
    public static File find(String preferredPath) {
        if (preferredPath != null && !preferredPath.isBlank()) {
            File file = new File(preferredPath.strip());
            if (isExecutable(file)) return file;
        }
        List<File> all = findAll();
        return all.isEmpty() ? null : all.getFirst();
    }

    /**
     * 全部可用的 jpackage，按“JDK 版本从新到旧”排序（先去重）。
     */
    public static List<File> findAll() {
        Set<String> seen = new LinkedHashSet<>();
        List<File> result = new ArrayList<>();
        for (Path candidate : candidates()) {
            File file = candidate.toFile();
            if (!isExecutable(file)) continue;
            String key;
            try {
                key = file.getCanonicalPath();
            } catch (Exception e) {
                key = file.getAbsolutePath();
            }
            if (seen.add(key)) result.add(file);
        }
        return result;
    }

    /** 候选路径（未做存在性校验） */
    private static List<Path> candidates() {
        List<Path> list = new ArrayList<>();

        addBin(list, System.getenv("JAVA_HOME"));

        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path home = Path.of(javaHome);
            addBin(list, home.toString());
            // java.home 可能是 .../jre，真正带 jpackage 的 bin 在上一级
            if (home.getParent() != null) {
                addBin(list, home.getParent().toString());
            }
        }

        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(File.pathSeparator)) {
                if (!entry.isBlank()) list.add(Path.of(entry.strip(), EXECUTABLE_NAME));
            }
        }

        list.addAll(scanCommonRoots());

        return list;
    }

    private static void addBin(List<Path> list, String home) {
        if (home == null || home.isBlank()) return;
        list.add(Path.of(home.strip(), "bin", EXECUTABLE_NAME));
    }

    /**
     * 扫描常见的 JDK 安装目录，返回其中所有 {@code <root>/<jdk dir>/bin/jpackage}。
     * 目录名里能解析出版本号的排在后面（版本越新越靠前）。
     */
    private static List<Path> scanCommonRoots() {
        List<Path> found = new ArrayList<>();
        for (String root : COMMON_JDK_ROOTS) {
            File rootDir = new File(root);
            File[] children = rootDir.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (!child.isDirectory()) continue;
                Path candidate = child.toPath().resolve("bin").resolve(EXECUTABLE_NAME);
                if (Files.isRegularFile(candidate)) found.add(candidate);
            }
        }
        // 版本号大的排前面（例如 jdk-25 在 jdk-17 之前）
        found.sort(Comparator.comparingInt(JpackageLocator::versionScore).reversed());
        return found;
    }

    /** 从目录名里粗略取出版本号权重，用于排序；取不到返回 0 */
    private static int versionScore(Path path) {
        // path: <root>/<jdk dir>/bin/jpackage -> 目录名在上一级的上一级
        Path bin = path.getParent();
        if (bin == null || bin.getParent() == null) return 0;
        String dirName = bin.getParent().getFileName().toString().toLowerCase(Locale.ROOT);
        int score = 0;
        StringBuilder digits = new StringBuilder();
        for (char c : dirName.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (digits.length() > 0) {
                score = Math.max(score, Integer.parseInt(digits.toString()));
                digits.setLength(0);
            }
        }
        if (digits.length() > 0) score = Math.max(score, Integer.parseInt(digits.toString()));
        return score;
    }

    /** 存在且是可执行文件 */
    private static boolean isExecutable(File file) {
        return file != null && file.isFile() && file.canExecute();
    }
}
