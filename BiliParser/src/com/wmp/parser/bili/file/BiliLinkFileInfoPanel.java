package com.wmp.parser.bili.file;

import com.wmp.downloader.exception.BiliDownloadTaskException;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractParser;
import com.wmp.downloader.newArchitecture.abstractTask.linkInfoPanel.LinkFileInfoPanel;
import com.wmp.downloader.tools.StringFormat;
import com.wmp.downloader.ui.FunctionDialog;
import com.wmp.parser.bili.info.BiliDownloadInfo;

import javax.swing.*;
import java.awt.event.ActionEvent;

public class BiliLinkFileInfoPanel extends LinkFileInfoPanel {

    private final BiliDownloadInfo downloadInfo;
    private int videoInfoIndex = 0;
    private int audioInfoIndex = 0;

    public BiliLinkFileInfoPanel(String name, BiliDownloadInfo downloadInfo, AbstractParser.Info info) {
        if (downloadInfo == null) throw new BiliDownloadTaskException("下载信息不能为空");
        super(name, downloadInfo.videoInfos()[0].size() + downloadInfo.audioInfos()[0].size(), "bilibili", downloadInfo.videoInfos()[0].url(), info);

        this.downloadInfo = downloadInfo;

        jsonInfo.put("linkStyle", 0);
        jsonInfo.put("biliSize", getFileSize());
        jsonInfo.put("biliUrl", getBiliDownloadUrl());
    }

    @Override
    public void editButtonAction(ActionEvent e) {
        var taskFileEditPanel = new BiliTaskFileEditPanel(nameLabel.getText(), downloadInfo, videoInfoIndex, audioInfoIndex);
        FunctionDialog.showDialog(SwingUtilities.getWindowAncestor(this), StringFormat.translate("task", "task.create_task.download_settings.task_edit"), taskFileEditPanel,
                result -> {
                    if (result == FunctionDialog.RESULT_SAVE) {
                        nameLabel.setText(taskFileEditPanel.getFileName());
                        fileName = taskFileEditPanel.getFileName();
                        sizeLabel.setText(StringFormat.formatSize(taskFileEditPanel.getFileSizeNum()));
                        videoInfoIndex = taskFileEditPanel.getVideoInfoIndex();
                        audioInfoIndex = taskFileEditPanel.getAudioInfoIndex();
                        jsonInfo.put("rootName", fileName);
                        jsonInfo.put("biliSize", getFileSize());
                        jsonInfo.put("biliUrl", getBiliDownloadUrl());

                    }
                },
                FunctionDialog.SAVE_CANCEL_BUTTONS, 0, null, 0);
    }

    public long[] getFileSize() {
        return new long[]{downloadInfo.videoInfos()[videoInfoIndex].size(), downloadInfo.audioInfos()[audioInfoIndex].size()};
    }

    /**
     * 获取B站下载地址
     *
     * @return 0-视频地址 1-音频地址
     */
    public String[] getBiliDownloadUrl() {
        return new String[]{downloadInfo.videoInfos()[videoInfoIndex].url(), downloadInfo.audioInfos()[audioInfoIndex].url()};
    }
}
