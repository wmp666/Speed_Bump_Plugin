package com.wmp.juzi;

import com.wmp.downloader.newArchitecture.abstractTask.AbstractSpecialSettingsPage;
import com.wmp.downloader.tools.file.DataControl;
import com.wmp.downloader.tools.ui.ToastMessage;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.awt.*;

/**
 * 「每日金句」设置页。
 *
 * <p>用于配置 {@code JuziReminder} 所需的数据：</p>
 * <ul>
 *     <li>显示方式（{@code great_juzi.show_way}）：启动时 / 固定时间点；</li>
 *     <li>固定时间点列表（{@code great_juzi.show_times}）：多个用分号 {@code ;} 分隔。</li>
 * </ul>
 *
 * <p>保存即通过 {@link DataControl#putAndSave} 写入持久化配置，并立即让调度器按新配置重新排程。</p>
 *
 * @author 吴鹤轩
 */
public class DailyJuziSettings extends AbstractSpecialSettingsPage {

    private static final Logger logger = Logger.getLogger(DailyJuziSettings.class);

    private JComboBox<String> showWayComboBox;
    private JTextField timesField;
    private JButton saveButton;
    private JButton testButton;

    public DailyJuziSettings() {
        super();
        initUI();
        loadCurrentConfig();
    }

    private void initUI() {
        setLayout(new BorderLayout(10, 10));

        // ---------- 标题 ----------
        var titleLabel = new JLabel("每日金句设置");
        titleLabel.putClientProperty("FlatLaf.style", "font: bold $h2.font");
        add(titleLabel, BorderLayout.NORTH);

        // ---------- 表单 ----------
        var formPanel = new JPanel(new GridBagLayout());
        formPanel.setOpaque(false);
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.anchor = GridBagConstraints.WEST;

        // 显示方式
        gbc.gridx = 0;
        gbc.gridy = 0;
        formPanel.add(new JLabel("显示方式："), gbc);

        showWayComboBox = new JComboBox<>(new String[]{
                JuziReminder.VALUE_START + " - 启动时显示",
                JuziReminder.VALUE_TIME + " - 固定时间点"
        });
        showWayComboBox.setPrototypeDisplayValue("固定时间点                ");
        showWayComboBox.addActionListener(e -> timesField.setEnabled(isTimeMode()));
        gbc.gridx = 1;
        formPanel.add(showWayComboBox, gbc);

        // 固定时间点
        gbc.gridx = 0;
        gbc.gridy = 1;
        formPanel.add(new JLabel("固定时间点："), gbc);

        timesField = new JTextField(24);
        timesField.setToolTipText("仅“固定时间点”模式生效；多个用分号 ; 分隔，例如：09:00;12:00;21:30");
        gbc.gridx = 1;
        formPanel.add(timesField, gbc);

        add(formPanel, BorderLayout.CENTER);

        // ---------- 底部按钮 ----------
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        buttonPanel.setOpaque(false);

        testButton = new JButton("试看一条");
        testButton.setToolTipText("立即推送一句金句到系统托盘，用于预览");
        testButton.addActionListener(e -> showTest());

        saveButton = new JButton("保存设置");
        saveButton.addActionListener(e -> saveConfig());

        buttonPanel.add(testButton);
        buttonPanel.add(saveButton);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void loadCurrentConfig() {
        String showWay = DataControl.get(JuziReminder.KEY_SHOW_WAY, JuziReminder.VALUE_START);
        showWayComboBox.setSelectedIndex(isStartMode(String.valueOf(showWay)) ? 0 : 1);

        String times = DataControl.get(JuziReminder.KEY_SHOW_TIMES, "");
        timesField.setText(String.valueOf(times));

        timesField.setEnabled(isTimeMode());
    }

    private boolean isTimeMode() {
        return !isStartMode(String.valueOf(selectedValue()));
    }

    private String selectedValue() {
        Object selected = showWayComboBox.getSelectedItem();
        return selected == null ? "" : selected.toString().split(" ")[0];
    }

    private static boolean isStartMode(String v) {
        return v == null || JuziReminder.VALUE_START.equalsIgnoreCase(v.trim());
    }

    private void saveConfig() {
        // 保存“显示方式”
        DataControl.putAndSave(JuziReminder.KEY_SHOW_WAY, isTimeMode() ? JuziReminder.VALUE_TIME : JuziReminder.VALUE_START);
        // 保存“固定时间点”（固定保存配置，切换回 time 模式时无需重填）
        DataControl.putAndSave(JuziReminder.KEY_SHOW_TIMES, timesField.getText() == null ? "" : timesField.getText().trim());

        // 立即按新配置重新排程
        JuziReminder.getInstance().planByConfig();

        ToastMessage.show("「每日金句」设置已保存并生效", ToastMessage.SUCCESS);
        logger.info("「每日金句」设置已保存：show_way="
                + (isTimeMode() ? JuziReminder.VALUE_TIME : JuziReminder.VALUE_START)
                + ", show_times=" + timesField.getText());
    }

    private void showTest() {
        // 试看仅在托盘可用时有效；JuziReminder 内部对托盘不可用做了提示与兜底。
        JuziReminder.getInstance().testShow();
    }

    @Override
    public void setDefaultButton() {
        if (getRootPane() != null) {
            getRootPane().setDefaultButton(saveButton);
        }
    }

    @Override
    public String getSettingsName() {
        return "每日金句";
    }
}
