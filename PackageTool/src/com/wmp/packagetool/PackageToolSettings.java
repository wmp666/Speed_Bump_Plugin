package com.wmp.packagetool;

import com.formdev.flatlaf.util.SystemFileChooser;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractSpecialSettingsPage;
import com.wmp.downloader.tools.file.DataControl;
import com.wmp.downloader.tools.ui.ToastMessage;
import org.apache.log4j.Logger;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 「打包工具」设置页：把 {@code jpackage} 的常用参数做成表单，填完点一下就能打包。
 *
 * <p>功能：</p>
 * <ul>
 *     <li><strong>基础字段</strong>：类型、名称、输入目录、Jar、输出目录、图标、版本（与原版 PackageTool 一致）；</li>
 *     <li><strong>高级字段</strong>：jpackage 路径、--main-class、--java-options、--vendor、--description
 *         以及 --win-console / --win-menu / --win-shortcut / --win-dir-chooser；</li>
 *     <li><strong>实时日志</strong>：在后台虚拟线程里执行 jpackage，输出逐行回显，可随时取消；</li>
 *     <li><strong>预设存外部文件</strong>：打包数据保存在普通文本文件里（默认
 *         {@code <减速带数据目录>/PackageTool/package.info}），可另存、可载入、可直接用记事本改，
 *         格式与原版 {@code package.info} 完全兼容。<strong>不使用宿主配置项存储</strong>。</li>
 * </ul>
 *
 * @author 无名牌
 */
public class PackageToolSettings extends AbstractSpecialSettingsPage {

    private static final Logger logger = Logger.getLogger(PackageToolSettings.class);

    /** 日志区最多保留的行数，防止长时间运行占满内存 */
    private static final int MAX_LOG_LINES = 2000;

    private final JpackageRunner runner = new JpackageRunner();

    /** 当前关联的预设文件（保存时直接写回它） */
    private File presetFile;

    private JComboBox<String> typeCombo;
    private JTextField nameField;
    private JTextField versionField;
    private PathRow inputRow;
    private PathRow mainJarRow;
    private PathRow outputRow;
    private PathRow iconRow;

    private PathRow jpackageRow;
    private JTextField mainClassField;
    private JTextField javaOptionsField;
    private JTextField vendorField;
    private JTextField descriptionField;
    private JCheckBox winConsoleCheck;
    private JCheckBox winMenuCheck;
    private JCheckBox winShortcutCheck;
    private JCheckBox winDirChooserCheck;

    private JTextArea logArea;
    private JLabel statusLabel;
    private JLabel presetLabel;

    private JButton detectButton;
    private JButton loadButton;
    private JButton saveButton;
    private JButton saveAsButton;
    private JButton copyButton;
    private JButton clearLogButton;
    private JButton openOutputButton;
    private JButton cancelButton;
    private JButton runButton;

    public PackageToolSettings() {
        super();
        initUI();
        applyPrefill();
        loadDefaultPreset();
    }

    // ------------------------------------------------------------------ 界面

    private void initUI() {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        var titleLabel = new JLabel("打包工具设置");
        titleLabel.putClientProperty("FlatLaf.style", "font: bold $h2.font");
        add(titleLabel, BorderLayout.NORTH);

        // ---------- 中部：表单 + 日志 ----------
        var splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(buildFormPanel()), buildLogPanel());
        splitPane.setResizeWeight(0.62);
        splitPane.setBorder(null);
        splitPane.setOpaque(false);
        add(splitPane, BorderLayout.CENTER);

        // ---------- 底部 ----------
        add(buildSouthPanel(), BorderLayout.SOUTH);
    }

    private JPanel buildFormPanel() {
        var formPanel = new JPanel(new GridBagLayout());
        formPanel.setOpaque(false);
        formPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 4));

        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 6, 5, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // ===== 基础 =====
        row = addSection(formPanel, gbc, row, "基础设置（jpackage 必填项）");

        typeCombo = new JComboBox<>(PackagePreset.TYPES.toArray(String[]::new));
        typeCombo.setToolTipText("app-image：免安装的应用目录；exe / msi：Windows 安装包");
        row = addRow(formPanel, gbc, row, "打包类型：", typeCombo,
                "app-image 生成免安装目录，exe / msi 生成 Windows 安装包。");

        nameField = new JTextField(26);
        nameField.setToolTipText("生成的应用/安装包名称，例如 Speed Bump");
        row = addRow(formPanel, gbc, row, "打包名称：", nameField,
                "对应 --name，生成物的名字。");

        inputRow = new PathRow("", "存放待打包文件的目录（对应 --input）", SystemFileChooser.DIRECTORIES_ONLY);
        row = addRow(formPanel, gbc, row, "输入目录：", inputRow,
                "对应 --input，里面放主 Jar 及其依赖。");

        mainJarRow = new PathRow("", "主 Jar 的文件名，例如 DownLoader_windows.jar（对应 --main-jar）",
                SystemFileChooser.FILES_ONLY);
        row = addRow(formPanel, gbc, row, "Jar 文件：", mainJarRow,
                "对应 --main-jar，应填写相对于输入目录的文件名，直接选文件也可以。");

        outputRow = new PathRow("", "生成结果的输出目录（对应 --dest）", SystemFileChooser.DIRECTORIES_ONLY);
        row = addRow(formPanel, gbc, row, "输出目录：", outputRow,
                "对应 --dest，打包结果会放在这里。");

        iconRow = new PathRow("", "应用图标，Windows 上用 .ico（对应 --icon）", SystemFileChooser.FILES_ONLY);
        row = addRow(formPanel, gbc, row, "图标文件：", iconRow,
                "对应 --icon，可留空；Windows 建议使用 .ico 文件。");

        versionField = new JTextField(26);
        versionField.setToolTipText("版本号，必须以数字开头，例如 1.0.0（对应 --app-version）");
        row = addRow(formPanel, gbc, row, "版本号：", versionField,
                "对应 --app-version，必须以数字开头；可留空。");

        // ===== 高级 =====
        row = addSection(formPanel, gbc, row, "高级选项（可留空）");

        jpackageRow = new PathRow("", "jpackage 可执行文件（属于 JDK，位于 <JDK>/bin/jpackage.exe）",
                SystemFileChooser.FILES_ONLY);
        row = addRow(formPanel, gbc, row, "jpackage 路径：", jpackageRow,
                "留空会自动探测；打包前若找不到会在这里提示你手动选择。");

        mainClassField = new JTextField(26);
        mainClassField.setToolTipText("主类全名，例如 com.wmp.downloader.Run（对应 --main-class）");
        row = addRow(formPanel, gbc, row, "主类：", mainClassField,
                "对应 --main-class；Jar 的 MANIFEST 里已写 Main-Class 时可留空。");

        javaOptionsField = new JTextField(26);
        javaOptionsField.setToolTipText("传给应用的 JVM 参数，多个用空格分隔，例如 -Xmx2g -Dfile.encoding=UTF-8");
        row = addRow(formPanel, gbc, row, "JVM 参数：", javaOptionsField,
                "对应 --java-options，按空格拆成多个参数逐个传递。");

        vendorField = new JTextField(26);
        vendorField.setToolTipText("发行商名称（对应 --vendor）");
        row = addRow(formPanel, gbc, row, "发行商：", vendorField, "对应 --vendor。");

        descriptionField = new JTextField(26);
        descriptionField.setToolTipText("应用描述（对应 --description）");
        row = addRow(formPanel, gbc, row, "描述：", descriptionField, "对应 --description。");

        // Windows 专有开关
        winConsoleCheck = new JCheckBox("保留控制台窗口");
        winConsoleCheck.setToolTipText("--win-console，仅 exe / msi 生效");
        winMenuCheck = new JCheckBox("创建开始菜单项");
        winMenuCheck.setToolTipText("--win-menu，仅 exe / msi 生效");
        winShortcutCheck = new JCheckBox("创建桌面快捷方式");
        winShortcutCheck.setToolTipText("--win-shortcut，仅 exe / msi 生效");
        winDirChooserCheck = new JCheckBox("允许选择安装目录");
        winDirChooserCheck.setToolTipText("--win-dir-chooser，仅 exe / msi 生效");

        var winPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        winPanel.setOpaque(false);
        winPanel.add(winConsoleCheck);
        winPanel.add(winMenuCheck);
        winPanel.add(winShortcutCheck);
        winPanel.add(winDirChooserCheck);
        row = addRow(formPanel, gbc, row, "Windows 安装包：", winPanel,
                "这些选项只在打包类型为 exe 或 msi 时有效，app-image 下会被忽略。");

        // 让表单收缩时贴在顶部
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        var filler = new JPanel();
        filler.setOpaque(false);
        formPanel.add(filler, gbc);

        return formPanel;
    }

    private JPanel buildLogPanel() {
        var logPanel = new JPanel(new BorderLayout(4, 4));
        logPanel.setOpaque(false);
        logPanel.setBorder(BorderFactory.createEmptyBorder(6, 4, 0, 4));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setText("");

        var logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(600, 180));
        logScroll.setBorder(BorderFactory.createTitledBorder("打包输出"));
        logPanel.add(logScroll, BorderLayout.CENTER);

        return logPanel;
    }

    private JPanel buildSouthPanel() {
        var southPanel = new JPanel(new BorderLayout(10, 4));
        southPanel.setOpaque(false);

        statusLabel = new JLabel(" ");
        statusLabel.setForeground(new Color(130, 130, 140));
        presetLabel = new JLabel(" ");
        presetLabel.setForeground(new Color(130, 130, 140));

        var labelPanel = new JPanel(new BorderLayout(6, 2));
        labelPanel.setOpaque(false);
        labelPanel.add(statusLabel, BorderLayout.NORTH);
        labelPanel.add(presetLabel, BorderLayout.SOUTH);
        southPanel.add(labelPanel, BorderLayout.NORTH);

        var buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));

        var firstRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        firstRow.setOpaque(false);

        detectButton = new JButton("检测 jpackage");
        detectButton.setToolTipText("自动查找 JDK 里的 jpackage 并填入上面的路径");
        detectButton.addActionListener(e -> detectJpackage());

        loadButton = new JButton("载入预设…");
        loadButton.setToolTipText("从外部文件读取打包数据（兼容原版 PackageTool 的 package.info）");
        loadButton.addActionListener(e -> chooseAndLoadPreset());

        saveButton = new JButton("保存预设");
        saveButton.setToolTipText("把当前表单保存到预设文件");
        saveButton.addActionListener(e -> savePreset());

        saveAsButton = new JButton("另存为…");
        saveAsButton.setToolTipText("把当前表单另存为一个新的预设文件");
        saveAsButton.addActionListener(e -> savePresetAs());

        copyButton = new JButton("复制命令");
        copyButton.setToolTipText("把等价于当前表单的 jpackage 命令行复制到剪贴板");
        copyButton.addActionListener(e -> copyCommand());

        firstRow.add(detectButton);
        firstRow.add(loadButton);
        firstRow.add(saveButton);
        firstRow.add(saveAsButton);
        firstRow.add(copyButton);

        var secondRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        secondRow.setOpaque(false);

        clearLogButton = new JButton("清空日志");
        clearLogButton.addActionListener(e -> logArea.setText(""));

        openOutputButton = new JButton("打开输出目录");
        openOutputButton.addActionListener(e -> openOutputDir());

        cancelButton = new JButton("取消");
        cancelButton.setEnabled(false);
        cancelButton.addActionListener(e -> cancelPackaging());

        runButton = new JButton("开始打包");
        runButton.putClientProperty("JButton.buttonType", "accent");
        runButton.setToolTipText("执行 jpackage 打包，过程输出会显示在下面的日志里");
        runButton.addActionListener(e -> startPackaging());

        secondRow.add(clearLogButton);
        secondRow.add(openOutputButton);
        secondRow.add(cancelButton);
        secondRow.add(runButton);

        buttons.add(firstRow);
        buttons.add(secondRow);
        southPanel.add(buttons, BorderLayout.SOUTH);

        return southPanel;
    }

    /** 添加一个分组标题行，返回下一个可用的行号 */
    private int addSection(JPanel panel, GridBagConstraints gbc, int row, String title) {
        var label = new JLabel(title);
        label.putClientProperty("FlatLaf.style", "font: bold $h3.font");
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        panel.add(label, gbc);

        gbc.gridwidth = 1;
        return row + 1;
    }

    /** 添加一行「标签 + 组件 + 说明」 */
    private int addRow(JPanel panel, GridBagConstraints gbc, int row, String labelText,
                       java.awt.Component component, String tip) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(labelText), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(component, gbc);

        var tipLabel = new JLabel(tip);
        tipLabel.setForeground(new Color(130, 130, 140));
        gbc.gridx = 1;
        gbc.gridy = row + 1;
        panel.add(tipLabel, gbc);

        return row + 2;
    }

    // ------------------------------------------------------------------ 预设

    /** 默认预设文件：减速带数据目录下的 PackageTool/package.info */
    private static File defaultPresetFile() {
        try {
            File dir = new File(DataControl.getDataPath(), "PackageTool");
            return new File(dir, PackagePreset.DEFAULT_FILE_NAME);
        } catch (Throwable t) {
            return new File(System.getProperty("user.home"), PackagePreset.DEFAULT_FILE_NAME);
        }
    }

    /** 首次打开时的默认值：类型/版本/输出目录/自动探测到的 jpackage */
    private void applyPrefill() {
        typeCombo.setSelectedItem(PackagePreset.DEFAULT_TYPE);
        versionField.setText("1.0.0");

        File defaultFile = defaultPresetFile();
        File outDir = new File(defaultFile.getParentFile(), "out");
        outputRow.setText(outDir.getAbsolutePath());

        File jpackage = JpackageLocator.find(null);
        if (jpackage != null) {
            jpackageRow.setText(jpackage.getAbsolutePath());
            presetFile = defaultFile;
            presetLabel.setText("预设文件：" + defaultFile.getAbsolutePath());
        } else {
            presetFile = defaultFile;
            presetLabel.setText("预设文件：" + defaultFile.getAbsolutePath());
            statusLabel.setText("未自动找到 jpackage，请点「检测 jpackage」或手动选择 JDK 里的 jpackage.exe。");
        }
    }

    private void loadDefaultPreset() {
        File defaultFile = defaultPresetFile();
        if (defaultFile.isFile()) {
            applyPresetFile(defaultFile, false);
        } else {
            log("尚未保存过预设，将使用默认值。预设文件将保存到：" + defaultFile.getAbsolutePath());
        }
    }

    private void chooseAndLoadPreset() {
        var chooser = new SystemFileChooser();
        chooser.setDialogType(SystemFileChooser.OPEN_DIALOG);
        chooser.setFileSelectionMode(SystemFileChooser.FILES_ONLY);
        chooser.setFileHidingEnabled(true);
        if (presetFile != null && presetFile.getParentFile() != null) {
            chooser.setCurrentDirectory(presetFile.getParentFile());
        }
        if (chooser.showOpenDialog(this) != SystemFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (file != null) applyPresetFile(file, true);
    }

    /** 读取并应用预设文件；notify 为 true 时给出提示 */
    private void applyPresetFile(File file, boolean notify) {
        try {
            PackagePreset preset = PackagePreset.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            applyPreset(preset);
            presetFile = file;
            presetLabel.setText("预设文件：" + file.getAbsolutePath());
            statusLabel.setText("已载入预设：" + file.getName());
            log("已载入预设：" + file.getAbsolutePath());
            if (notify) {
                ToastMessage.show("已载入打包预设：" + file.getName(), ToastMessage.SUCCESS);
            }
            logger.info("「打包工具」载入预设：" + file.getAbsolutePath());
        } catch (Exception e) {
            log("载入预设失败：" + e.getMessage());
            statusLabel.setText("载入预设失败：" + e.getMessage());
            ToastMessage.show("载入预设失败：" + e.getMessage(), ToastMessage.ERROR);
            logger.error("载入打包预设失败：" + file.getAbsolutePath(), e);
        }
    }

    private void savePreset() {
        File target = presetFile != null ? presetFile : defaultPresetFile();
        savePresetTo(target, true);
    }

    private void savePresetAs() {
        var chooser = new SystemFileChooser();
        chooser.setDialogType(SystemFileChooser.SAVE_DIALOG);
        chooser.setFileSelectionMode(SystemFileChooser.FILES_ONLY);
        chooser.setFileHidingEnabled(true);
        chooser.setSelectedFile(new File(
                presetFile != null ? presetFile.getName() : PackagePreset.DEFAULT_FILE_NAME));
        if (presetFile != null && presetFile.getParentFile() != null) {
            chooser.setCurrentDirectory(presetFile.getParentFile());
        }
        if (chooser.showSaveDialog(this) != SystemFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (file != null) savePresetTo(file, true);
    }

    /** 把当前表单写入预设文件；notify 为 true 时给出提示。返回是否成功 */
    private boolean savePresetTo(File file, boolean notify) {
        try {
            File parent = file.getParentFile();
            if (parent != null) Files.createDirectories(parent.toPath());
            Files.writeString(file.toPath(), readForm().toText(), StandardCharsets.UTF_8);
            presetFile = file;
            presetLabel.setText("预设文件：" + file.getAbsolutePath());
            statusLabel.setText("已保存预设：" + file.getName());
            log("已保存预设：" + file.getAbsolutePath());
            if (notify) {
                ToastMessage.show("打包预设已保存：" + file.getName(), ToastMessage.SUCCESS);
            }
            logger.info("「打包工具」保存预设：" + file.getAbsolutePath());
            return true;
        } catch (Exception e) {
            log("保存预设失败：" + e.getMessage());
            statusLabel.setText("保存预设失败：" + e.getMessage());
            if (notify) {
                ToastMessage.show("保存预设失败：" + e.getMessage(), ToastMessage.ERROR);
            }
            logger.error("保存打包预设失败：" + file.getAbsolutePath(), e);
            return false;
        }
    }

    // ------------------------------------------------------------------ 表单读写

    private PackagePreset readForm() {
        Object selectedType = typeCombo.getSelectedItem();
        return new PackagePreset(
                selectedType == null ? PackagePreset.DEFAULT_TYPE : String.valueOf(selectedType),
                nameField.getText().strip(),
                inputRow.getText(),
                mainJarRow.getText(),
                outputRow.getText(),
                iconRow.getText(),
                versionField.getText().strip(),
                jpackageRow.getText(),
                mainClassField.getText().strip(),
                javaOptionsField.getText().strip(),
                vendorField.getText().strip(),
                descriptionField.getText().strip(),
                winConsoleCheck.isSelected(),
                winMenuCheck.isSelected(),
                winShortcutCheck.isSelected(),
                winDirChooserCheck.isSelected());
    }

    private void applyPreset(PackagePreset preset) {
        if (PackagePreset.TYPES.contains(preset.type())) {
            typeCombo.setSelectedItem(preset.type());
        } else {
            log("预设中的打包类型“" + preset.type() + "”无法识别，已保留当前选择。");
        }
        nameField.setText(preset.name());
        inputRow.setText(preset.input());
        mainJarRow.setText(preset.mainJar());
        outputRow.setText(preset.output());
        iconRow.setText(preset.iconPath());
        versionField.setText(preset.version());

        jpackageRow.setText(preset.jpackagePath());
        mainClassField.setText(preset.mainClass());
        javaOptionsField.setText(preset.javaOptions());
        vendorField.setText(preset.vendor());
        descriptionField.setText(preset.description());
        winConsoleCheck.setSelected(preset.winConsole());
        winMenuCheck.setSelected(preset.winMenu());
        winShortcutCheck.setSelected(preset.winShortcut());
        winDirChooserCheck.setSelected(preset.winDirChooser());
    }

    // ------------------------------------------------------------------ 动作

    private void detectJpackage() {
        List<File> found = JpackageLocator.findAll();
        if (found.isEmpty()) {
            statusLabel.setText("没有找到 jpackage，请手动选择 JDK 的 bin/jpackage.exe。");
            log("未找到任何 jpackage（已查找 JAVA_HOME、当前运行时、PATH 与常见 JDK 安装目录）。");
            ToastMessage.show("没有找到 jpackage，请手动选择 JDK 里的 jpackage.exe", ToastMessage.WARNING);
            return;
        }
        File first = found.getFirst();
        jpackageRow.setText(first.getAbsolutePath());
        log("找到 " + found.size() + " 个 jpackage，已选用版本最高的一个：");
        for (File file : found) {
            log("    " + file.getAbsolutePath());
        }
        statusLabel.setText("已选用：" + first.getAbsolutePath());
        ToastMessage.show("已找到 jpackage：" + first.getName(), ToastMessage.SUCCESS);
    }

    private void copyCommand() {
        PackagePreset preset = readForm();
        File jpackage = JpackageLocator.find(preset.jpackagePath());
        String text = JpackageCommand.toDisplayString(JpackageCommand.build(preset, jpackage));
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        log("已复制命令：" + text);
        statusLabel.setText("命令行已复制到剪贴板。");
    }

    private void openOutputDir() {
        File dir = new File(outputRow.getText());
        if (!dir.isDirectory()) {
            statusLabel.setText("输出目录还不存在：" + dir.getAbsolutePath());
            ToastMessage.show("输出目录还不存在", ToastMessage.WARNING);
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            }
        } catch (Exception e) {
            logger.error("打开输出目录失败", e);
            ToastMessage.show("打开输出目录失败：" + e.getMessage(), ToastMessage.ERROR);
        }
    }

    private void cancelPackaging() {
        if (!runner.isRunning()) return;
        log("正在取消打包…");
        runner.cancel();
    }

    private void startPackaging() {
        if (runner.isRunning()) {
            ToastMessage.show("已经有一个打包任务在运行了", ToastMessage.WARNING);
            return;
        }

        PackagePreset preset = readForm();
        File jpackage = JpackageLocator.find(preset.jpackagePath());

        List<String> errors = JpackageCommand.validate(preset, jpackage);
        if (!errors.isEmpty()) {
            errors.forEach(error -> log("错误：" + error));
            statusLabel.setText("有 " + errors.size() + " 处需要修正后才能打包。");
            ToastMessage.show(errors.getFirst(), ToastMessage.ERROR);
            return;
        }
        for (String warning : JpackageCommand.warnings(preset)) {
            log("提示：" + warning);
        }

        // 记住当前表单，下次打开时自动恢复
        savePresetTo(presetFile != null ? presetFile : defaultPresetFile(), false);

        List<String> command = JpackageCommand.build(preset, jpackage);
        log("开始打包：" + preset.type() + " / " + preset.name());
        setRunning(true);
        statusLabel.setText("正在打包…（输出见下方日志）");

        boolean started = runner.start(command, null,
                line -> SwingUtilities.invokeLater(() -> appendLog(line)),
                (exitCode, cancelled) -> SwingUtilities.invokeLater(() -> onFinished(exitCode, cancelled, preset)));
        if (!started) {
            setRunning(false);
            statusLabel.setText("启动失败：已有任务在运行。");
            ToastMessage.show("启动失败：已有任务在运行", ToastMessage.ERROR);
        }
    }

    private void onFinished(int exitCode, boolean cancelled, PackagePreset preset) {
        setRunning(false);
        if (cancelled) {
            log("打包已取消。");
            statusLabel.setText("打包已取消。");
            ToastMessage.show("打包已取消", ToastMessage.WARNING);
            return;
        }
        if (exitCode == 0) {
            log("打包完成，输出目录：" + preset.output());
            statusLabel.setText("打包完成，输出目录：" + preset.output());
            ToastMessage.show("打包完成，输出在 " + new File(preset.output()).getName(), ToastMessage.SUCCESS);
        } else {
            log("打包失败，jpackage 退出码：" + exitCode);
            statusLabel.setText("打包失败，退出码 " + exitCode + "，详情见日志。");
            ToastMessage.show("打包失败，退出码 " + exitCode + "，详情见日志", ToastMessage.ERROR);
        }
    }

    private void setRunning(boolean running) {
        runButton.setEnabled(!running);
        cancelButton.setEnabled(running);
        detectButton.setEnabled(!running);
        loadButton.setEnabled(!running);
    }

    // ------------------------------------------------------------------ 日志

    /** 追加一行日志（自动滚动、自动裁剪） */
    private void appendLog(String line) {
        logArea.append(line + "\n");
        try {
            Element root = logArea.getDocument().getDefaultRootElement();
            while (root.getElementCount() > MAX_LOG_LINES) {
                logArea.getDocument().remove(0, root.getElement(0).getEndOffset());
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        } catch (BadLocationException e) {
            // 忽略：仅影响日志显示
        }
    }

    private void log(String line) {
        appendLog(line);
        logger.info("「打包工具」" + line);
    }

    // ------------------------------------------------------------------ 覆写

    @Override
    public void setDefaultButton() {
        if (getRootPane() != null) {
            getRootPane().setDefaultButton(runButton);
        }
    }

    @Override
    public String getSettingsName() {
        return "打包工具";
    }

    /**
     * 「标签 + 输入框 + 选择按钮 + 说明」的一行路径输入。
     */
    private static final class PathRow extends JPanel {

        private final JTextField field = new JTextField(26);

        PathRow(String value, String tooltip, int selectionMode) {
            setOpaque(false);
            setLayout(new BorderLayout(6, 0));
            field.setText(value == null ? "" : value);
            field.setToolTipText(tooltip);

            var chooseButton = new JButton("…");
            chooseButton.setToolTipText(selectionMode == SystemFileChooser.DIRECTORIES_ONLY ? "选择目录" : "选择文件");
            chooseButton.addActionListener(e -> {
                File file = DataControl.getPath(this, SystemFileChooser.OPEN_DIALOG, selectionMode);
                if (file != null) field.setText(file.getAbsolutePath());
            });

            add(field, BorderLayout.CENTER);
            add(chooseButton, BorderLayout.EAST);
        }

        String getText() {
            String text = field.getText();
            return text == null ? "" : text.strip();
        }

        void setText(String value) {
            field.setText(value == null ? "" : value);
        }
    }
}
