package com.wmp.parser.bili.file;


import com.alibaba.fastjson2.JSONObject;
import com.wmp.downloader.newArchitecture.abstractTask.downloadTask.FileDownloadTask;
import com.wmp.downloader.newArchitecture.abstractTask.downloadTask.StatusTipPanel;
import com.wmp.downloader.tools.StringFormat;
import com.wmp.downloader.tools.download.ConvergenceTool;
import com.wmp.downloader.tools.download.URLDownloadTool;
import com.wmp.downloader.tools.file.DataControl;
import com.wmp.downloader.tools.ui.ToastMessage;
import com.wmp.downloader.tools.ui.UITools;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public class BiliFileDownloadTask extends FileDownloadTask {

    private static final Logger logger = Logger.getLogger(BiliFileDownloadTask.class);

    private final String[] url;
    private final int threadNum;
    private final long[] fileSize;
    private final int mode;
    private final ArrayList<JProgressBar> threadProgressBarList = new ArrayList<>();
    private final URLDownloadTool.PauseController pauseController = new URLDownloadTool.PauseController();
    private final URLDownloadTool.DownloadProgress downloadProgress = new URLDownloadTool.DownloadProgress();
    private Timer progressTimer;

    private final StatusTipPanel DOWNLOAD_SIZE_PANEL = StatusTipPanel.DOWNLOAD_SIZE_CREATOR.create();
    private final StatusTipPanel DOWNLOAD_SPEED_PANEL = StatusTipPanel.DOWNLOAD_SPEED_CREATOR.create();
    private final StatusTipPanel FILE_MERGE_PANEL = StatusTipPanel.FILE_MERGE_CREATOR.create();
    private final StatusTipPanel DOWNLOAD_FAILED_PANEL = StatusTipPanel.DOWNLOAD_FAILED_CREATOR.create();
    private final StatusTipPanel DOWNLOAD_SUCCESS_PANEL = StatusTipPanel.DOWNLOAD_SUCCESS_CREATOR.create();

    public BiliFileDownloadTask(JSONObject jsonObject) {
        super(jsonObject);
        this.fileSize = jsonObject.getJSONArray("biliSize").stream().mapToLong(o -> Long.parseLong(o.toString())).toArray();
        this.url = jsonObject.getJSONArray("biliUrl").stream().map(Object::toString).toArray(String[]::new);
        this.threadNum = jsonObject.getIntValue("threadNum");
        this.mode = jsonObject.getIntValue("threadMode", 0);

        //删除将要用于存入数据的文件夹
        File tempDir = new File(savePath, fileName + ".temp");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        } else {
            DataControl.deleteFolder(tempDir, false);
        }

        this.addStatusTips(DOWNLOAD_SIZE_PANEL, DOWNLOAD_SPEED_PANEL, FILE_MERGE_PANEL);
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", "https://www.bilibili.com");
        headers.put("Sec-Fetch-Mode", "no-cors");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/77.0.3865.90 Safari/537.36");
        return headers;
    }

    public void doWhenStart() throws Exception {

        removeAllStatusTip();
        addStatusTips(DOWNLOAD_SIZE_PANEL, DOWNLOAD_SPEED_PANEL, FILE_MERGE_PANEL);

        ProgressBarsPanel.removeAll();
        threadProgressBarList.clear();

        File tempDir = new File(savePath, fileName + ".temp");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }

        Map<String, String> headers = buildHeaders();
        URI videoUri = URI.create(url[0]);
        URI audioUri = URI.create(url[1]);

        Thread.ofVirtual().start(() -> {
            try {

                boolean videoSuccess = false;
                boolean audioSuccess = false;

                try {
                    videoSuccess = downloadFile(videoUri, tempDir, "video.m4s", headers, fileSize[0]);
                    //if (!videoSuccess) return;
                } catch (Exception e) {
                    logger.error(StringFormat.translate("task", "task.download_task.video_download_failed"), e);
                    ToastMessage.show(this, StringFormat.translate("task", "task.download_task.video_download_failed"), ToastMessage.ERROR);
                }

                downloadProgress.resetSpeed();
                downloadProgress.resetMergedBytes(0);
                ProgressBarsPanel.removeAll();
                threadProgressBarList.clear();

                pauseController.resume();

                try {
                    progressTimer.restart();
                    audioSuccess = downloadFile(audioUri, tempDir, "audio.m4s", headers, fileSize[1]);
                    //if (!audioSuccess) return;
                } catch (Exception e) {
                    logger.error(StringFormat.translate("task", "task.download_task.audio_download_failed"), e);
                    ToastMessage.show(this, StringFormat.translate("task", "task.download_task.audio_download_failed"), ToastMessage.ERROR);
                }

                //合并文件
                if (videoSuccess && audioSuccess) {
                    exitButton.setEnabled(false);
                    downloadControlButton.setEnabled(false);
                    ProgressBarsPanel.removeAll();
                    var jProgressBar = new JProgressBar();
                    jProgressBar.setStringPainted(false);
                    ProgressBarsPanel.add(UITools.createProgressBarPanel(jProgressBar));
                    FILE_MERGE_PANEL.setText(StringFormat.translate("task", "task.download_task.merging_file"));
                    var isConverged = ConvergenceTool.converge(new File(tempDir, "video.m4s"), new File(tempDir, "audio.m4s"), new File(savePath, fileName), jProgressBar);

                    //删除文件
                    DataControl.deleteFolder(tempDir, false);

                    if (isConverged) {
                        removeAllStatusTip();
                        addStatusTip(DOWNLOAD_SUCCESS_PANEL);
                    } else {
                        removeAllStatusTip();
                        addStatusTip(DOWNLOAD_FAILED_PANEL);
                    }
                }

                isFinally = true;
                downloadControlButton.setEnabled(false);
                exitButton.setEnabled(true);
                ProgressBarsPanel.removeAll();
                this.revalidate();
                this.repaint();
            } catch (Exception e) {
                if (progressTimer != null) progressTimer.stop();
                logger.error(StringFormat.translate("task", "task.download_task.download_exception"), e);
                ToastMessage.show(this, String.format(StringFormat.translate("task", "task.download_task.download_failed_detail"), e.getMessage()), ToastMessage.ERROR);
                isStart = false;
                downloadControlButton.setEnabled(true);
                exitButton.setEnabled(true);
            }
        });
    }

    @Override
    public void doWhenRestart() throws Exception {
        pauseController.resume();
        if (progressTimer != null) progressTimer.restart();
    }

    private boolean downloadFile(URI uri, File tempDir, String targetFileName, Map<String, String> headers, long size) throws Exception {
        return downloadSingleThread(uri, tempDir, targetFileName, headers, size);
    }

    private boolean downloadSingleThread(URI uri, File tempDir, String targetFileName, Map<String, String> headers, long size) throws Exception {
        progressTimer = new Timer(1000, e -> {
            if (isStart) {
                downloadProgress.updateSpeed();
                DOWNLOAD_SIZE_PANEL.setText(
                        URLDownloadTool.DownloadProgress.formatSize(downloadProgress.getDownloadedBytes())
                );
                DOWNLOAD_SPEED_PANEL.setText(
                        URLDownloadTool.DownloadProgress.formatSize(downloadProgress.getSpeed()) + "/s"
                );
            }
        });
        progressTimer.start();

        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(false);
        threadProgressBarList.add(progressBar);
        ProgressBarsPanel.add(UITools.createProgressBarPanel(progressBar));

        boolean isSuccess = URLDownloadTool.singleThreadDownload(uri, tempDir, targetFileName, size, 10, progressBar, pauseController, downloadProgress, headers);
        progressTimer.stop();

        if (!isSuccess) {
            downloadControlButton.setEnabled(false);
            removeAllStatusTip();
            addStatusTip(DOWNLOAD_FAILED_PANEL);
            ToastMessage.show(this, StringFormat.translate("task", "task.download_task.download_failed_single"), ToastMessage.ERROR);
            stop();
            return false;
        }

        isStart = false;
        ProgressBarsPanel.removeAll();
        threadProgressBarList.clear();
        return true;
    }

    public void doWhenStop() {
        pauseController.pause();
        if (progressTimer != null) progressTimer.stop();
    }

    @Override
    public void doWhenExit() {
        if (progressTimer != null) progressTimer.stop();
        threadProgressBarList.clear();
    }

}
