package com.wmp.parser.bili.file;


import com.alibaba.fastjson2.JSONObject;
import com.wmp.downloader.newArchitecture.abstractTask.downloadTask.FileDownloadTask;
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
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", "https://www.bilibili.com");
        headers.put("Sec-Fetch-Mode", "no-cors");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/77.0.3865.90 Safari/537.36");
        return headers;
    }

    public void doWhenStart() throws Exception {

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

                SwingUtilities.invokeLater(() -> infoLabel.setText(StringFormat.translate("task", "task.download_task.downloading_video")));
                try {
                    videoSuccess = downloadFile(videoUri, tempDir, "video.m4s", headers, fileSize[0]);
                    //if (!videoSuccess) return;
                } catch (Exception e) {
                    logger.error(StringFormat.translate("task", "task.download_task.video_download_failed"), e);
                    ToastMessage.show(this, StringFormat.translate("task", "task.download_task.video_download_failed"), ToastMessage.ERROR);
                }

                SwingUtilities.invokeLater(() -> infoLabel.setText(StringFormat.translate("task", "task.download_task.downloading_audio")));
                downloadProgress.resetSpeed();
                downloadProgress.resetMergedBytes();
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
                    infoLabel.setText(StringFormat.translate("task", "task.download_task.merging_file"));
                    var isConverged = ConvergenceTool.converge(new File(tempDir, "video.m4s"), new File(tempDir, "audio.m4s"), new File(savePath, fileName), jProgressBar);
                    infoLabel.setText(StringFormat.translate("task", isConverged ? "task.download_task.merge_success" : "task.download_task.merge_failed"));

                    //删除文件
                    DataControl.deleteFolder(tempDir, false);
                }

                isFinally = true;
                downloadControlButton.setEnabled(false);
                exitButton.setEnabled(true);
                ProgressBarsPanel.removeAll();
                SwingUtilities.invokeLater(() -> infoLabel.setText(String.format(
                        StringFormat.translate("task.download_task.download_complete"),
                        StringFormat.formatSize(fileSize[0] + fileSize[1]), StringFormat.formatSize(fileSize[0] + fileSize[1]))));
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
                infoLabel.setText(String.format("<html>%s</html>",
                        String.format(StringFormat.translate("task", "task.download_task.progress_single"),
                                StringFormat.formatSize(downloadProgress.getDownloadedBytes()),
                                StringFormat.formatSize(downloadProgress.getSpeed()))));
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
            infoLabel.setText(StringFormat.translate("task", "task.download_task.single_thread_error"));
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
        infoLabel.setText("<html>" + StringFormat.translate("task", "task.download_task.paused") + "</html>");
    }

    @Override
    public void doWhenExit() {
        if (progressTimer != null) progressTimer.stop();
        threadProgressBarList.clear();
    }

}
