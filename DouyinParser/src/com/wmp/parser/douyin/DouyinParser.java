package com.wmp.parser.douyin;

import com.alibaba.fastjson2.JSONObject;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractParser;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractSpecialSettingsPage;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractTask;
import com.wmp.downloader.newArchitecture.abstractTask.linkInfoPanel.AbstractLinkInfoPanel;
import com.wmp.downloader.newArchitecture.abstractTask.linkInfoPanel.LinkFileInfoPanel;
import com.wmp.downloader.newArchitecture.abstractTask.linkInfoPanel.LinkFolderInfoPanel;
import com.wmp.downloader.tools.StringFormat;
import com.wmp.downloader.tools.download.URLDownloadTool;
import com.wmp.downloader.tools.ui.ToastMessage;
import org.apache.log4j.Logger;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class DouyinParser extends AbstractParser {

    private static final Logger logger = Logger.getLogger(DouyinParser.class);

    @Override
    public String getID() {
        return "抖音分享链接解析器";
    }

    @Override
    public String getSupportTip() {
        return StringFormat.translate("support_text.douyin");
    }

    @Override
    protected void updateLinkInfo(String link) {}

    @Override
    protected AbstractLinkInfoPanel getLinkedInfoPanel(String link, Info info) {
        try {
            //请求链接（图集，视频）：https://api.yujn.cn/api/dy_jx.php?msg=
            var conn = (HttpURLConnection) URI.create("https://api.yujn.cn/api/dy_jx.php?msg=" + link).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows; U; Windows NT 5.1; zh-CN; rv:1.9.2.15)");
            conn.connect();

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String jsonText = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                var jsonObject = JSONObject.parseObject(jsonText);
                logger.debug(jsonObject);


                if (jsonObject.getString("type").equals("视频")) {
                    //获取视频大小

                    var url = jsonObject.getString("play_video");
                    return LinkFileInfoPanel.createPanel(
                            jsonObject.getString("title") + ".mp4",
                            URLDownloadTool.getFileSize(url),
                            "douyin",
                            url, info
                    );
                } else if (jsonObject.getString("type").equals("图集")) {
                    long[] sizes = new long[jsonObject.getJSONArray("images").size()];
                    for (int i = 0; i < sizes.length; i++) {
                        sizes[i] = URLDownloadTool.getFileSize(jsonObject.getJSONArray("images").getString(i));
                    }
                    String[] images = new String[jsonObject.getJSONArray("images").size()];
                    for (int i = 0; i < images.length; i++) {
                        images[i] = jsonObject.getJSONArray("images").getString(i);
                    }
                    String[] names = new String[jsonObject.getJSONArray("images").size()];
                    String[] types = new String[names.length];
                    for (int i = 0; i < names.length; i++) {
                        String imageName = URLDownloadTool.extractFileName(images[i]);

                        var tempStringList = imageName.split("\\.");
                        types[i] = tempStringList.length <= 1 ? "None" : tempStringList[tempStringList.length - 1];
                        names[i] = i + "." + types[i];
                    }
                    return LinkFolderInfoPanel.createPanel(
                            jsonObject.getString("title"),
                            sizes,
                            "douyin",
                            images,
                            names,
                            types, info
                    );
                }
            }


        } catch (Exception e) {
            logger.error("抖音链接解析失败", e);
        }


        return null;
    }

    @Override
    public boolean isMeetRequirements(String link) {
        if (link.strip().contains("v.douyin.com")) {
            return true;
        }else if (link.strip().contains("douyin.com")){
            ToastMessage.show(StringFormat.translate("task.create_task.douyin.a_wrong_link"), ToastMessage.WARNING);
        }
        return false;
    }

    @Override
    protected AbstractTask getTask(String link, JSONObject infoJson) {
        try {
            if (infoJson.getIntValue("linkStyle") == 0) {
                return new DouyinVideoDownloadTask(infoJson);
            } else if (infoJson.getIntValue("linkStyle") == 1) {
                return new DouyinImageDownloadTask(infoJson);
            }
        } catch (Exception e) {
            logger.error("数据异常", e);
        }
        return null;
    }


    @Override
    public AbstractSpecialSettingsPage getSettingsPage() {
        return null;
    }
}
