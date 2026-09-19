package com.wmp.packagetool;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 jpackage 打包所需的全部数据。
 *
 * <p>本类同时负责预设文件的读写。文件格式与被替换掉的原 {@code PackageTool} 完全兼容：
 * <strong>前 7 行依次是</strong></p>
 *
 * <pre>
 * 类型             (--type)        app-image / exe / msi
 * 名称             (--name)
 * 输入目录         (--input)
 * Jar 文件         (--main-jar)
 * 输出目录         (--dest)
 * 图标文件         (--icon)       可为空行
 * 版本             (--app-version) 可为空行
 * </pre>
 *
 * <p>第 8 行起是本插件扩展的高级参数，形如 {@code 键=值}，不认识的行会被忽略；
 * 原版工具只读前 7 行，因此本插件写出的文件原版工具同样能读，反之亦然。</p>
 *
 * @param type         打包类型：app-image / exe / msi
 * @param name         应用名称（--name）
 * @param input        输入目录（--input）
 * @param mainJar      主 Jar（--main-jar，相对于输入目录）
 * @param output       输出目录（--dest）
 * @param iconPath     图标文件（--icon），可空
 * @param version      版本号（--app-version），可空
 * @param jpackagePath jpackage 可执行文件路径，留空则自动探测
 * @param mainClass    主类（--main-class），可空
 * @param javaOptions  传给应用的 JVM 参数（--java-options），多个用空格分隔，可空
 * @param vendor       发行商（--vendor），可空
 * @param description  描述（--description），可空
 * @param winConsole   是否保留控制台窗口（--win-console，仅 exe/msi）
 * @param winMenu      是否创建开始菜单项（--win-menu，仅 exe/msi）
 * @param winShortcut  是否创建桌面快捷方式（--win-shortcut，仅 exe/msi）
 * @param winDirChooser 是否允许安装时选择目录（--win-dir-chooser，仅 exe/msi）
 * @author 无名牌
 */
public record PackagePreset(
        String type,
        String name,
        String input,
        String mainJar,
        String output,
        String iconPath,
        String version,
        String jpackagePath,
        String mainClass,
        String javaOptions,
        String vendor,
        String description,
        boolean winConsole,
        boolean winMenu,
        boolean winShortcut,
        boolean winDirChooser
) {

    /** 支持的打包类型（与 jpackage 的 --type 取值一致） */
    public static final List<String> TYPES = List.of("app-image", "exe", "msi");

    /** 默认打包类型 */
    public static final String DEFAULT_TYPE = "app-image";

    /** 预设文件默认文件名（沿用原版 PackageTool 的名字） */
    public static final String DEFAULT_FILE_NAME = "package.info";

    private static final String KEY_JPACKAGE = "jpackage";
    private static final String KEY_MAIN_CLASS = "mainClass";
    private static final String KEY_JAVA_OPTIONS = "javaOptions";
    private static final String KEY_VENDOR = "vendor";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_WIN_CONSOLE = "winConsole";
    private static final String KEY_WIN_MENU = "winMenu";
    private static final String KEY_WIN_SHORTCUT = "winShortcut";
    private static final String KEY_WIN_DIR_CHOOSER = "winDirChooser";

    /** 基础字段的行数（与原版工具一致） */
    private static final int BASE_LINE_COUNT = 7;

    public PackagePreset {
        // 任何字段都不允许为 null，避免设置页到处判空
        type = nn(type);
        name = nn(name);
        input = nn(input);
        mainJar = nn(mainJar);
        output = nn(output);
        iconPath = nn(iconPath);
        version = nn(version);
        jpackagePath = nn(jpackagePath);
        mainClass = nn(mainClass);
        javaOptions = nn(javaOptions);
        vendor = nn(vendor);
        description = nn(description);
    }

    /** 除了三个路径与 jpackage 位置外全部为空的预设 */
    public static PackagePreset empty() {
        return new PackagePreset(DEFAULT_TYPE, "", "", "", "", "", "", "", "", "", "", "",
                false, false, false, false);
    }

    /**
     * 解析预设文件内容。
     *
     * @param text 文件内容
     * @return 预设
     * @throws IllegalArgumentException 内容不足 7 行时抛出（无法与原版格式对应）
     */
    public static PackagePreset parse(String text) {
        String normalized = nn(text).replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        if (lines.length < BASE_LINE_COUNT) {
            throw new IllegalArgumentException(
                    "打包数据至少需要 " + BASE_LINE_COUNT + " 行（类型/名称/输入目录/Jar/输出目录/图标/版本），当前只有 "
                            + lines.length + " 行");
        }

        String type = lines[0].strip();
        String name = lines[1].strip();
        String input = lines[2].strip();
        String mainJar = lines[3].strip();
        String output = lines[4].strip();
        String iconPath = lines[5].strip();
        String version = lines[6].strip();

        String jpackagePath = "";
        String mainClass = "";
        String javaOptions = "";
        String vendor = "";
        String description = "";
        boolean winConsole = false;
        boolean winMenu = false;
        boolean winShortcut = false;
        boolean winDirChooser = false;

        // 第 8 行起为扩展参数：键=值
        for (int i = BASE_LINE_COUNT; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            switch (key) {
                case KEY_JPACKAGE -> jpackagePath = value;
                case KEY_MAIN_CLASS -> mainClass = value;
                case KEY_JAVA_OPTIONS -> javaOptions = value;
                case KEY_VENDOR -> vendor = value;
                case KEY_DESCRIPTION -> description = value;
                case KEY_WIN_CONSOLE -> winConsole = Boolean.parseBoolean(value);
                case KEY_WIN_MENU -> winMenu = Boolean.parseBoolean(value);
                case KEY_WIN_SHORTCUT -> winShortcut = Boolean.parseBoolean(value);
                case KEY_WIN_DIR_CHOOSER -> winDirChooser = Boolean.parseBoolean(value);
                default -> {
                    // 不认识的行直接忽略，便于将来向前兼容
                }
            }
        }

        return new PackagePreset(type.isEmpty() ? DEFAULT_TYPE : type, name, input, mainJar, output,
                iconPath, version, jpackagePath, mainClass, javaOptions, vendor, description,
                winConsole, winMenu, winShortcut, winDirChooser);
    }

    /**
     * 序列化为预设文件内容（前 7 行与原版工具完全一致）。
     * 值里的换行会被替换成空格，保证一行一项。
     */
    public String toText() {
        List<String> lines = new ArrayList<>();
        lines.add(oneLine(type));
        lines.add(oneLine(name));
        lines.add(oneLine(input));
        lines.add(oneLine(mainJar));
        lines.add(oneLine(output));
        lines.add(oneLine(iconPath));
        lines.add(oneLine(version));

        lines.add(KEY_JPACKAGE + "=" + oneLine(jpackagePath));
        lines.add(KEY_MAIN_CLASS + "=" + oneLine(mainClass));
        lines.add(KEY_JAVA_OPTIONS + "=" + oneLine(javaOptions));
        lines.add(KEY_VENDOR + "=" + oneLine(vendor));
        lines.add(KEY_DESCRIPTION + "=" + oneLine(description));
        lines.add(KEY_WIN_CONSOLE + "=" + winConsole);
        lines.add(KEY_WIN_MENU + "=" + winMenu);
        lines.add(KEY_WIN_SHORTCUT + "=" + winShortcut);
        lines.add(KEY_WIN_DIR_CHOOSER + "=" + winDirChooser);

        return String.join("\n", lines) + "\n";
    }

    /** 是否设置了任何只在 exe/msi 下生效的 Windows 选项 */
    public boolean hasWindowsOnlyOptions() {
        return winConsole || winMenu || winShortcut || winDirChooser;
    }

    private static String oneLine(String value) {
        return nn(value).replace("\r", " ").replace("\n", " ").strip();
    }

    private static String nn(String value) {
        return value == null ? "" : value;
    }
}
