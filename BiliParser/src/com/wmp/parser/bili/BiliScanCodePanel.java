package com.wmp.parser.bili;

import com.alibaba.fastjson2.JSONObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.wmp.downloader.tools.StringFormat;
import com.wmp.downloader.tools.file.DataControl;
import com.wmp.downloader.tools.ui.ToastMessage;
import org.apache.log4j.Logger;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;

public class BiliScanCodePanel extends JPanel {

    private static final Logger logger = Logger.getLogger(BiliScanCodePanel.class);
    // 保存登录成功后获取的 Cookie（包含 SESSDATA）
    private static String cookies = "";
    private JLabel QRLabel;
    private JButton QRRefreshButton;
    private JButton linkButton;
    private JPanel mainPanel;
    private JLabel QRStatusLabel;
    private String loginURL;
    private boolean isExit = false;


    public BiliScanCodePanel() {
        this.setLayout(new BorderLayout());
        this.add(mainPanel, BorderLayout.CENTER);

        QRRefreshButton.putClientProperty("FlatLaf.style", "font: $h2.font");
        linkButton.putClientProperty("FlatLaf.style", "font: $h2.font");

        refreshQR();

        QRRefreshButton.addActionListener(e -> {
            refreshQR();
        });
        linkButton.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(URI.create(loginURL));
            } catch (IOException ex) {
                ToastMessage.show(null, StringFormat.translate("special_settings", "open_link.error"), ToastMessage.ERROR);
                logger.error(StringFormat.translate("special_settings", "bili_special_settings.cannot_open_browser"), ex);
            }
        });
    }

    private void refreshQR() {
        Thread.ofVirtual().start(() -> {
            try {
                String qrcodeKey = fetchQRCode();
                if (qrcodeKey == null) {
                    logger.error(StringFormat.translate("special_settings", "bili_special_settings.get_qr_code_failed"));
                    ToastMessage.show(null, StringFormat.translate("special_settings", "bili_special_settings.get_qr_code.error"), ToastMessage.ERROR);
                    return;
                }

                String sessData = pollLoginStatus(qrcodeKey);
                if (sessData != null) {
                    logger.info(StringFormat.translate("special_settings", "bili_special_settings.login_success_sessdata") + sessData);
                    //保存
                    DataControl.putAndSave("bili_sessdata", sessData);
                    // 现在可以使用 cookies 调用其他 API
                } else {
                    logger.error(StringFormat.translate("special_settings", "bili_special_settings.login_failed_or_timeout"));
                }
            } catch (Exception e) {
                logger.error(StringFormat.translate("special_settings", "bili_special_settings.login_process_error"), e);
                ToastMessage.show(null, StringFormat.translate("special_settings", "bili_special_settings.login_process.error"), ToastMessage.ERROR);
            }
        });
    }

    // 步骤1：申请二维码
    private String fetchQRCode() throws Exception {
        String url = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";

        Connection.Response response = Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .ignoreContentType(true)   // 返回 JSON，非 HTML
                .followRedirects(false)    // 禁用重定向（可选）
                .method(Connection.Method.GET)
                .execute();

        String json = response.body();
        JSONObject jsonObject = JSONObject.parseObject(json);
        if (jsonObject.getInteger("code") == 0) {
            JSONObject data = jsonObject.getJSONObject("data");
            String qrcodeKey = data.getString("qrcode_key");
            this.loginURL = data.getString("url");

            // 生成二维码图片
            String statusMsg = StringFormat.translate("special_settings", "bili_special_settings.qr_code_generated");
            var icon = new ImageIcon(generateQRCodeImage(this.loginURL, (int) (QRRefreshButton.getFont().getSize() * 10)));
            QRLabel.setIcon(icon);
            QRLabel.setPreferredSize(new Dimension(icon.getIconWidth() + 10, icon.getIconHeight() + 10));
            QRStatusLabel.setText(statusMsg);
            logger.info(statusMsg);
            return qrcodeKey;
        }
        return null;
    }

    /**
     * 生成二维码图片
     *
     * @param text 要编码的文本（https）
     * @param size 二维码图片的大小
     * @return 二维码图片
     * @throws Exception 如果生成二维码图片时发生错误
     */
    private BufferedImage generateQRCodeImage(String text, int size) throws Exception {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, size, size);
        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }

    // 步骤2：轮询登录状态
    private String pollLoginStatus(String qrcodeKey) throws InterruptedException {
        String pollUrl = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" + qrcodeKey;
        System.out.println(StringFormat.translate("special_settings", "bili_special_settings.poll_login_status_url") + pollUrl);

        // 轮询最多 90 次，每次间隔 2 秒（共 180 秒）
        for (int i = 0; i < 90 && !isExit; i++) {
            try {
                Connection.Response response = Jsoup.connect(pollUrl)
                        .userAgent("Mozilla/5.0")
                        .ignoreContentType(true)
                        .followRedirects(false)
                        .method(Connection.Method.GET)
                        .execute();

                // 提取 Set-Cookie（可能包含多个，这里取全部）
                String setCookie = response.header("Set-Cookie");
                if (setCookie != null) {
                    cookies = setCookie;
                }

                String json = response.body();
                JSONObject jsonObject = JSONObject.parseObject(json);
                int code = jsonObject.getJSONObject("data").getInteger("code");
                switch (code) {
                    case 0 -> {
                        // 登录成功
                        String successMsg = StringFormat.translate("special_settings", "bili_special_settings.login_success");
                        QRStatusLabel.setText(successMsg);
                        ToastMessage.show(successMsg, ToastMessage.SUCCESS);
                        return extractSESSDATA(cookies);
                    }
                    case 86038 -> {
                        String expiredMsg = StringFormat.translate("special_settings", "bili_special_settings.qr_code_expired");
                        QRStatusLabel.setText(expiredMsg);
                        logger.warn(expiredMsg);
                        return null;
                    }
                    case 86090 -> {
                        String scannedMsg = StringFormat.translate("special_settings", "bili_special_settings.scanned_confirm");
                        QRStatusLabel.setText(scannedMsg);
                        logger.info(scannedMsg);
                    }
                    case 86101 -> {
                        String waitingMsg = StringFormat.translate("special_settings", "bili_special_settings.waiting_scan");
                        QRStatusLabel.setText(waitingMsg);
                        logger.info(waitingMsg);
                    }

                    default -> {
                        String unknownMsg = StringFormat.translate("special_settings", "bili_special_settings.unknown_status_code") + code;
                        QRStatusLabel.setText(unknownMsg);
                        logger.error(unknownMsg);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            Thread.sleep(2000);
        }
        return null;
    }

    // 从 Cookie 字符串中提取 SESSDATA
    private String extractSESSDATA(String cookieHeader) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            part = part.trim();
            if (part.startsWith("SESSDATA=")) {
                // SESSDATA 的值可能包含 path 等，只取第一个分号前
                String value = part.substring("SESSDATA=".length());
                return value.split(";")[0];
            }
        }
        return null;
    }

    public boolean isExit() {
        return isExit;
    }

    public void setExit(boolean exit) {
        isExit = exit;
    }
}
