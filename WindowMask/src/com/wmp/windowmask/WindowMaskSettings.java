package com.wmp.windowmask;

import com.wmp.downloader.newArchitecture.abstractTask.AbstractSpecialSettingsPage;
import com.wmp.downloader.tools.file.DataControl;
import com.wmp.downloader.tools.ui.ToastMessage;
import org.apache.log4j.Logger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * 「窗口遮挡」设置页。
 *
 * <p>用于配置 {@link MaskWatcher} 所需的数据：</p>
 * <ul>
 *     <li>是否启用（{@code window_mask.enable}）；</li>
 *     <li>匹配文字（{@code window_mask.match_text}）：判断窗口标题是否包含这些文字，多个用分号 {@code ;} 分隔；</li>
 *     <li>遮挡时显示的文字（{@code window_mask.display_text}）：可用换行。</li>
 * </ul>
 *
 * <p>保存即通过 {@link DataControl#putAndSave} 写入持久化配置；检测线程每轮都会读取最新配置，
 * 因此保存后立即生效，无需重启。</p>
 *
 * @author 无名牌
 */
public class WindowMaskSettings extends AbstractSpecialSettingsPage {

    private static final Logger logger = Logger.getLogger(WindowMaskSettings.class);

    /** 「捕获窗口标题」的等待时间：留出切窗口的时间 */
    private static final int CAPTURE_DELAY_MILLIS = 3000;
    /** 「试一下」的遮挡时长 */
    private static final long TEST_DURATION_MILLIS = 3000L;

    private JCheckBox enableCheck;
    private JTextField matchField;
    private JTextArea displayArea;
    private JLabel statusLabel;
    private JButton captureButton;
    private JButton testButton;
    private JButton saveButton;

    public WindowMaskSettings() {
        super();
        initUI();
        loadCurrentConfig();
    }

    private void initUI() {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // ---------- 标题 ----------
        var titleLabel = new JLabel("窗口遮挡设置");
        titleLabel.putClientProperty("FlatLaf.style", "font: bold $h2.font");
        add(titleLabel, BorderLayout.NORTH);

        // ---------- 表单 ----------
        var formPanel = new JPanel(new GridBagLayout());
        formPanel.setOpaque(false);
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // 启用开关
        enableCheck = new JCheckBox("启用窗口遮挡");
        enableCheck.setToolTipText("关闭后不再遮挡任何窗口，配置仍然保留");
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        formPanel.add(enableCheck, gbc);

        // 匹配文字
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.gridx = 0;
        gbc.gridy = 1;
        formPanel.add(new JLabel("匹配文字："), gbc);

        matchField = new JTextField(28);
        matchField.setToolTipText("窗口标题包含其中任意一段文字即触发遮挡（仅在这个窗口成为当前窗口时生效）；多个字段用英文分号 ; 分隔，例如：bilibili;抖音;Steam");
        gbc.gridx = 1;
        gbc.weightx = 1;
        formPanel.add(matchField, gbc);

        // 匹配说明
        var matchTip = new JLabel("多个字段用英文分号 ; 分隔，当前窗口标题命中任意一个即遮挡；留空则不遮挡任何窗口。");
        matchTip.setForeground(new Color(130, 130, 140));
        gbc.gridx = 1;
        gbc.gridy = 2;
        formPanel.add(matchTip, gbc);

        // 遮挡时显示的文字
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        formPanel.add(new JLabel("遮挡时显示的文字："), gbc);

        displayArea = new JTextArea(3, 28);
        displayArea.setLineWrap(true);
        displayArea.setWrapStyleWord(true);
        displayArea.setToolTipText("遮挡层上显示的文字，可以换行；留空则只显示纯色遮罩");
        var displayScroll = new JScrollPane(displayArea);
        displayScroll.setPreferredSize(new Dimension(320, 72));
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.BOTH;
        formPanel.add(displayScroll, gbc);

        // 平台提示
        if (!MaskWatcher.isSupported()) {
            var platformTip = new JLabel("⚠ 当前环境不支持窗口标题读取（本插件仅支持 Windows），遮挡不会生效。");
            platformTip.setForeground(new Color(200, 90, 60));
            gbc.gridx = 1;
            gbc.gridy = 4;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            formPanel.add(platformTip, gbc);
        }

        add(formPanel, BorderLayout.CENTER);

        // ---------- 底部 ----------
        var southPanel = new JPanel(new BorderLayout(10, 6));
        southPanel.setOpaque(false);

        statusLabel = new JLabel(" ");
        statusLabel.setForeground(new Color(130, 130, 140));
        southPanel.add(statusLabel, BorderLayout.NORTH);

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        buttonPanel.setOpaque(false);

        captureButton = new JButton("捕获窗口标题");
        captureButton.setToolTipText("点击后 " + (CAPTURE_DELAY_MILLIS / 1000) + " 秒内切到目标窗口，自动把它的标题填入“匹配文字”");
        captureButton.addActionListener(e -> startCapture());

        testButton = new JButton("试一下");
        testButton.setToolTipText("在当前窗口上临时显示 " + (TEST_DURATION_MILLIS / 1000) + " 秒遮挡效果（无视匹配文字）");
        testButton.addActionListener(e -> showTest());

        saveButton = new JButton("保存设置");
        saveButton.addActionListener(e -> saveConfig());

        buttonPanel.add(captureButton);
        buttonPanel.add(testButton);
        buttonPanel.add(saveButton);
        southPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(southPanel, BorderLayout.SOUTH);
    }

    private void loadCurrentConfig() {
        Object enabled = DataControl.get(MaskWatcher.KEY_ENABLE, Boolean.TRUE);
        enableCheck.setSelected(MaskWatcher.parseEnable(enabled));

        matchField.setText(MaskWatcher.getString(MaskWatcher.KEY_MATCH_TEXT, ""));

        String display = MaskWatcher.getString(MaskWatcher.KEY_DISPLAY_TEXT, MaskWatcher.DEFAULT_DISPLAY_TEXT);
        displayArea.setText(MaskWatcher.decodeText(display));
        displayArea.setCaretPosition(0);
    }

    private void saveConfig() {
        String matchText = matchField.getText() == null ? "" : matchField.getText().trim();
        String displayText = MaskWatcher.encodeText(displayArea.getText()).trim();

        // 保存三项配置：检测线程下一轮即读取最新值，无需重启
        DataControl.putAndSave(MaskWatcher.KEY_ENABLE, enableCheck.isSelected());
        DataControl.putAndSave(MaskWatcher.KEY_MATCH_TEXT, matchText);
        DataControl.putAndSave(MaskWatcher.KEY_DISPLAY_TEXT, displayText);

        int count = MaskWatcher.parseKeywords(matchText).size();
        statusLabel.setText("已保存：" + (enableCheck.isSelected() ? "已启用" : "已停用")
                + "，匹配字段 " + count + " 个。");
        ToastMessage.show("「窗口遮挡」设置已保存并生效", ToastMessage.SUCCESS);
        logger.info("「窗口遮挡」设置已保存：enable=" + enableCheck.isSelected()
                + ", match_text=" + matchText + ", display_text=" + displayText);
    }

    /**
     * 捕获窗口标题：给用户 {@value #CAPTURE_DELAY_MILLIS} 毫秒切到目标窗口，然后把读到的标题追加到匹配文字框。
     */
    private void startCapture() {
        if (!MaskWatcher.isSupported()) {
            ToastMessage.show("当前环境不支持读取窗口标题（仅 Windows 可用）", ToastMessage.WARNING);
            return;
        }
        captureButton.setEnabled(false);
        statusLabel.setText("请在 " + (CAPTURE_DELAY_MILLIS / 1000) + " 秒内切换到要匹配的窗口…");

        Timer timer = new Timer(CAPTURE_DELAY_MILLIS, e -> finishCapture());
        timer.setRepeats(false);
        timer.start();
    }

    private void finishCapture() {
        captureButton.setEnabled(true);
        String title = MaskWatcher.currentForegroundTitle();
        if (title == null || title.isBlank()) {
            statusLabel.setText("没有读取到窗口标题，请把目标窗口切到最前面后重试。");
            return;
        }
        String existing = matchField.getText() == null ? "" : matchField.getText().trim();
        if (!existing.isEmpty() && !existing.endsWith(";")) {
            existing = existing + ";";
        }
        matchField.setText(existing + title);
        statusLabel.setText("已捕获标题，可自行精简为关键词后点“保存设置”。");
        logger.info("「窗口遮挡」捕获到窗口标题：" + title);
    }

    private void showTest() {
        if (!MaskWatcher.isSupported()) {
            ToastMessage.show("当前环境不支持窗口遮挡（仅 Windows 可用）", ToastMessage.WARNING);
            return;
        }
        MaskWatcher.getInstance().testShow(TEST_DURATION_MILLIS);
        statusLabel.setText("已在当前窗口上显示 " + (TEST_DURATION_MILLIS / 1000) + " 秒遮挡效果。");
    }

    @Override
    public void setDefaultButton() {
        if (getRootPane() != null) {
            getRootPane().setDefaultButton(saveButton);
        }
    }

    @Override
    public String getSettingsName() {
        return "窗口遮挡";
    }
}
