package com.wmp.processing.imageFormat;

import com.alibaba.fastjson2.JSONObject;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractParser;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractSpecialSettingsPage;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractTask;
import com.wmp.downloader.newArchitecture.abstractTask.linkInfoPanel.AbstractLinkInfoPanel;
import com.wmp.downloader.newArchitecture.abstractTask.linkInfoPanel.LinkFileInfoPanel;
import com.wmp.downloader.newArchitecture.ui.createTask.TaskFileEditPanel;
import com.wmp.downloader.tools.StringFormat;
import com.wmp.downloader.ui.FunctionDialog;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.io.File;

public class Processing extends AbstractParser {

    private static final Logger logger = Logger.getLogger(Processing.class);

    private String type = null;

    @Override
    public String getID() {
        return "图片格式转换器";
    }

    @Override
    public String getSupportTip() {
        return com.wmp.processing.imageFormat.StringFormat.translate("support");
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
                var taskFileEditPanel = new ImageLinkFileEditPanel(file.getName(), type);
                FunctionDialog.showDialog(SwingUtilities.getWindowAncestor(this), StringFormat.translate("task", "task.create_task.download_settings.task_edit"), taskFileEditPanel.getMainPanel(),
                        result -> {
                            if (result == FunctionDialog.RESULT_SAVE) {
                                nameLabel.setText(taskFileEditPanel.getFileName());
                                fileName = taskFileEditPanel.getFileName();
                                type = taskFileEditPanel.getType();
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
            return stringIsEndsContains(file.getName(), "png", "jpg", "jpeg", "bmp", "gif", "ico", "webp");
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
