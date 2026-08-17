package com.wmp.processing.imageFormat;

import com.alibaba.fastjson2.JSONObject;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractParser;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractSpecialSettingsPage;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractTask;
import com.wmp.downloader.newArchitecture.abstractTask.linkInfoPanel.AbstractLinkInfoPanel;
import com.wmp.downloader.newArchitecture.abstractTask.linkInfoPanel.LinkFileInfoPanel;
import com.wmp.downloader.tools.StringFormat;
import com.wmp.downloader.ui.FunctionDialog;
import org.apache.log4j.Logger;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.event.ActionEvent;
import java.io.File;

public class Processing extends AbstractParser {

    static {
        try {
            // 1. 将上下文类加载器临时切换为当前类的加载器（即插件加载器）
            ClassLoader pluginLoader = ImageConverter.class.getClassLoader();
            Thread.currentThread().setContextClassLoader(pluginLoader);

            // 2. 强制 ImageIO 扫描当前上下文类加载器中的 SPI 服务
            ImageIO.scanForPlugins();

            System.out.println("ImageIO 插件扫描完成，已加载 ImageIO 扩展。");
        } catch (Exception e) {
            System.err.println("ImageIO 插件扫描失败: " + e.getMessage());
        }
    }

    private static final Logger logger = Logger.getLogger(Processing.class);

    private String type = null;

    @Override
    public String getID() {
        return "图片格式转换器";
    }

    @Override
    public String getSupportTip() {
        return Translate.translate("support");
    }

    @Override
    protected void updateLinkInfo(String link) {

    }

    @Override
    protected AbstractLinkInfoPanel getLinkedInfoPanel(String link, Info info) {
        var file = new File(link);

        return new LinkFileInfoPanel(file.getName(), 0, "Image-Converter", link, info) {
            @Override
            public void editButtonAction(ActionEvent e) {
                var temp = fileName.split("\\.");
                StringBuilder sb = new StringBuilder();
                for (var i = 0; i < temp.length; i++) {
                    if (i == temp.length - 2){
                        sb.append(temp[i]);
                        break;
                    }else sb.append(temp[i]).append(".");
                }
                var taskFileEditPanel = new ImageLinkFileEditPanel(sb.toString(), type);
                FunctionDialog.showDialog(SwingUtilities.getWindowAncestor(this), StringFormat.translate("task", "task.create_task.download_settings.task_edit"), taskFileEditPanel.getMainPanel(),
                        result -> {
                            if (result == FunctionDialog.RESULT_SAVE) {
                                type = taskFileEditPanel.getType();
                                var name = taskFileEditPanel.getFileName() + "." + type;
                                nameLabel.setText(name);
                                fileName = name;

                            }

                        },
                        FunctionDialog.SAVE_CANCEL_BUTTONS, 0, null, 0);
            }
        };
    }

    @Override
    public boolean isMeetRequirements(String link) {
        try {
            var file = new File(link);
            return stringIsEndsContains(file.getName(), ImageIO.getReaderFileSuffixes());
        } catch (Exception e) {
            logger.error("这个链接不是文件", e);
        }
        return false;
    }

    private boolean stringIsEndsContains(String source, String... ends){
        for (String end : ends) {
            if (source.endsWith(end)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected AbstractTask getTask(String link, JSONObject infoJson) {
        infoJson.put("image_type", type);
        return new ImageConverterTask(infoJson);
    }

    @Override
    public AbstractSpecialSettingsPage getSettingsPage() {
        return null;
    }
}
