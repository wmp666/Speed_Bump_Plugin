package com.wmp.parser.bili;

import com.alibaba.fastjson2.JSONObject;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractSpecialSettingsPage;
import com.wmp.downloader.tools.StringFormat;
import com.wmp.downloader.tools.file.DataControl;
import com.wmp.downloader.tools.ui.IconControl;
import com.wmp.downloader.ui.FunctionDialog;
import org.apache.log4j.Logger;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

public class BiliSettings extends AbstractSpecialSettingsPage {

    private static final Logger logger = Logger.getLogger(BiliSettings.class);


    private JPanel mainPanel;
    private JLabel loginIconLabel;
    private JLabel loginStatusLabel;
    private JButton ScanCodeButton;
    private JButton loggedOutButton;
    private JComboBox<String> qualityComboBox;
    private JLabel UserNameLabel;
    private JPanel loginPanel;

    @Override
    public void setDefaultButton() {
        getRootPane().setDefaultButton(ScanCodeButton);
    }

    @Override
    public String getSettingsName() {
        return StringFormat.translate("special_settings", "bili_special_settings");
    }
public BiliSettings() {
            super();

            this.setLayout(new BorderLayout());
            this.add(mainPanel, BorderLayout.CENTER);
            UserNameLabel.putClientProperty("FlatLaf.style", "font: bold $h3.font");
            ScanCodeButton.putClientProperty("FlatLaf.style", "font: $h3.font");
            loggedOutButton.putClientProperty("FlatLaf.style", "font: $h3.font");

            initUserInfo();

            //初始化图标
            IconControl.addInDynamicConverter(() -> {
                loginIconLabel.setIcon(IconControl.getIcon("login", loginPanel.getPreferredSize().height));
                ScanCodeButton.setIcon(IconControl.getIcon("scan", ScanCodeButton.getFont().getSize()));
                loggedOutButton.setIcon(IconControl.getIcon("power_switch", loggedOutButton.getFont().getSize()));
            });

            //初始化监听
            ScanCodeButton.addActionListener(e -> {
                var biliScanCodePanel = new BiliScanCodePanel();
                FunctionDialog.showDialog(SwingUtilities.getWindowAncestor(this), StringFormat.translate("special_settings", "bili_special_settings.login.scan_code"),
                        biliScanCodePanel,
                        result -> {
                            if (result == FunctionDialog.RESULT_OK) {
                                initUserInfo();
                            }
                            biliScanCodePanel.setExit(true);
                        }, FunctionDialog.DEFAULT_BUTTONS, 0,
                        null, 0);
            });
            loggedOutButton.addActionListener(e -> {
                DataControl.putAndSave("bili_sessdata", "");
                initUserInfo();
            });
            /*qualityComboBox.addItemListener(e -> {
                if (e.getStateChange() == ItemEvent.SELECTED) {
                    DataControl.put("biliQuality", qualityComboBox.getSelectedItem());
                    DataControl.save();

                }
            });*/

        }

        public void initUserInfo() {
            try {
                Connection.Response response = Jsoup.connect("https://api.bilibili.com/x/web-interface/nav")
                        .header("Cookie", "SESSDATA=" + DataControl.get("bili_sessdata", "")) // 关键：在Header中设置Cookie
                        .header("User-Agent", "Mozilla/5.0")      // 设置UA，模拟浏览器
                        .ignoreContentType(true)                  // 忽略内容类型，处理JSON
                        .method(Connection.Method.GET)
                        .execute();
                JSONObject jsonObject = JSONObject.parseObject(response.body());
                var userData = jsonObject.getJSONObject("data");

                if (!userData.getBooleanValue("isLogin", false)) {
                    UserNameLabel.setText("未登录");
                    loginStatusLabel.setText("无");
                    return;
                }

                logger.debug("用户信息: " + userData);
                String userName = userData.getString("uname");
                var userId = userData.getLongValue("mid", 0);
                boolean isVip = userData.getIntValue("vipStatus", 0) == 1;//0:非会员，1:会员
                UserNameLabel.setText(userName);
                loginStatusLabel.setText("UID: " + userId + " " + (isVip ? " 会员" : " 非会员"));
                loggedOutButton.setEnabled(true);
            } catch (IOException e) {
                UserNameLabel.setText("未登录");
                loginStatusLabel.setText("无");
                loggedOutButton.setEnabled(false);
                logger.error("获取用户信息失败", e);
            }
        }

}
