package com.wmp.packagetool;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link PackagePreset} 翻译成 jpackage 命令行。
 *
 * <p>本类只做纯粹的字符串拼装，不接触进程与界面，因此可以被单独测试。</p>
 *
 * @author 无名牌
 */
public final class JpackageCommand {

    private JpackageCommand() {
    }

    /**
     * 生成完整的命令行（第一个元素是可执行文件路径）。
     *
     * @param preset  打包数据
     * @param jpackage 已确定的 jpackage 可执行文件；为 {@code null} 时退化为 "jpackage"（依赖 PATH）
     */
    public static List<String> build(PackagePreset preset, File jpackage) {
        List<String> command = new ArrayList<>();
        command.add(jpackage == null ? "jpackage" : jpackage.getAbsolutePath());

        command.add("--type");
        command.add(preset.type());
        command.add("--name");
        command.add(preset.name());
        command.add("--input");
        command.add(preset.input());
        command.add("--main-jar");
        command.add(preset.mainJar());
        command.add("--dest");
        command.add(preset.output());

        if (!preset.iconPath().isBlank()) {
            command.add("--icon");
            command.add(preset.iconPath());
        }
        if (!preset.version().isBlank()) {
            command.add("--app-version");
            command.add(preset.version());
        }
        if (!preset.mainClass().isBlank()) {
            command.add("--main-class");
            command.add(preset.mainClass());
        }
        if (!preset.vendor().isBlank()) {
            command.add("--vendor");
            command.add(preset.vendor());
        }
        if (!preset.description().isBlank()) {
            command.add("--description");
            command.add(preset.description());
        }
        // 每个 JVM 参数单独出一个 --java-options，避免含空格的参数被 jpackage 再次拆分
        for (String option : splitJavaOptions(preset.javaOptions())) {
            command.add("--java-options");
            command.add(option);
        }

        // --win-* 只在 exe / msi 下被 jpackage 接受，app-image 时给出去了会直接报错
        if (isInstallerType(preset.type())) {
            if (preset.winConsole()) command.add("--win-console");
            if (preset.winMenu()) command.add("--win-menu");
            if (preset.winShortcut()) command.add("--win-shortcut");
            if (preset.winDirChooser()) command.add("--win-dir-chooser");
        }

        return command;
    }

    /**
     * 拼成可以复制的命令行文本（含空格的参数加双引号）。
     */
    public static String toDisplayString(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (String arg : command) {
            if (sb.length() > 0) sb.append(' ');
            if (arg.indexOf(' ') >= 0 || arg.indexOf('\t') >= 0) {
                sb.append('"').append(arg).append('"');
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }

    /** JVM 参数按空白拆分，每个 token 作为一个 --java-options */
    public static List<String> splitJavaOptions(String javaOptions) {
        List<String> result = new ArrayList<>();
        if (javaOptions == null || javaOptions.isBlank()) return result;
        for (String token : javaOptions.strip().split("\\s+")) {
            if (!token.isBlank()) result.add(token);
        }
        return result;
    }

    /** 是否是会生成安装包的类型（--win-* 选项只在此时有效） */
    public static boolean isInstallerType(String type) {
        return "exe".equalsIgnoreCase(type) || "msi".equalsIgnoreCase(type);
    }

    /**
     * 打包前的硬性检查，返回全部错误；返回空列表表示可以执行。
     */
    public static List<String> validate(PackagePreset preset, File jpackage) {
        List<String> errors = new ArrayList<>();

        if (jpackage == null || !jpackage.isFile()) {
            errors.add("找不到 jpackage：请在「jpackage 路径」里手动选择 JDK 的 bin/jpackage.exe（jpackage 属于 JDK，精简运行时里没有）。");
        }
        if (!PackagePreset.TYPES.contains(preset.type())) {
            errors.add("打包类型必须是 " + String.join(" / ", PackagePreset.TYPES) + " 之一，当前为“" + preset.type() + "”。");
        }
        if (preset.name().isBlank()) {
            errors.add("打包名称不能为空。");
        }
        if (preset.input().isBlank()) {
            errors.add("输入目录不能为空。");
        } else if (!new File(preset.input()).isDirectory()) {
            errors.add("输入目录不存在或不是目录：" + preset.input());
        }
        if (preset.mainJar().isBlank()) {
            errors.add("Jar 文件不能为空。");
        }
        if (preset.output().isBlank()) {
            errors.add("输出目录不能为空。");
        }
        if (!preset.iconPath().isBlank() && !new File(preset.iconPath()).isFile()) {
            errors.add("图标文件不存在：" + preset.iconPath());
        }
        if (!preset.version().isBlank() && !Character.isDigit(preset.version().charAt(0))) {
            errors.add("版本号必须以数字开头（jpackage 的限制），当前为“" + preset.version() + "”。");
        }
        return errors;
    }

    /**
     * 不阻断执行、但值得提示的问题。
     */
    public static List<String> warnings(PackagePreset preset) {
        List<String> warnings = new ArrayList<>();

        String mainJar = preset.mainJar();
        if (!mainJar.isBlank() && (mainJar.contains("/") || mainJar.contains("\\"))) {
            warnings.add("--main-jar 应当是相对于输入目录的文件名（例如 DownLoader_windows.jar），路径形式可能被 jpackage 拒绝。");
        }
        if (!isInstallerType(preset.type()) && preset.hasWindowsOnlyOptions()) {
            warnings.add("已勾选 Windows 安装包选项，但类型是 app-image，这些选项本次不会生效（请改用 exe 或 msi）。");
        }
        return warnings;
    }
}
