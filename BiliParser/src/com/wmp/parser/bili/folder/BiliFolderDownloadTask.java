package com.wmp.parser.bili.folder;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wmp.downloader.newArchitecture.abstractTask.downloadTask.FolderDownloadTask;
import com.wmp.downloader.newArchitecture.abstractTask.downloadTask.StatusTipPanel;
import com.wmp.downloader.tools.StringFormat;
import com.wmp.downloader.tools.download.ConvergenceTool;
import com.wmp.downloader.tools.download.URLDownloadTool;
import com.wmp.downloader.tools.download.URLDownloadTool.DownloadProgress;
import com.wmp.downloader.tools.download.URLDownloadTool.PauseController;
import com.wmp.downloader.tools.file.DataControl;
import com.wmp.downloader.tools.ui.ToastMessage;
import com.wmp.downloader.tools.ui.UITools;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class BiliFolderDownloadTask extends FolderDownloadTask {

    private static final Logger logger = Logger.getLogger(BiliFolderDownloadTask.class);

    private final String[] videoUrls;
    private final String[] audioUrls;
    private final String[] fileNames;
    private final long[] videoSizes;
    private final long[] audioSizes;
    private final int threadNum;
    private final int mode;
    private final long totalFileSize;

    private final ArrayList<PauseController> pauseControllerList = new ArrayList<>();
    private final ArrayList<DownloadProgress> downloadProgressList = new ArrayList<>();
    private final ArrayList<JPanel> progressBarPanelList = new ArrayList<>();
    private Timer progressTimer;
    private AtomicInteger completedCount;
    private volatile boolean hasAnyError = false;

    private final StatusTipPanel DOWNLOAD_SIZE_PANEL = StatusTipPanel.DOWNLOAD_SIZE_CREATOR.create();
    private final StatusTipPanel DOWNLOAD_SPEED_PANEL = StatusTipPanel.DOWNLOAD_SPEED_CREATOR.create();
    private final StatusTipPanel DOWNLOAD_FAILED_PANEL = StatusTipPanel.DOWNLOAD_FAILED_CREATOR.create();
    private final StatusTipPanel DOWNLOAD_SUCCESS_PANEL = StatusTipPanel.DOWNLOAD_SUCCESS_CREATOR.create();

    public BiliFolderDownloadTask(JSONObject jsonObject) {
        super(jsonObject);  // 父类处理 savePath, folderName 等

        JSONArray videoUrlsJson = jsonObject.getJSONArray("videoUrls");
        JSONArray audioUrlsJson = jsonObject.getJSONArray("audioUrls");
        JSONArray videoSizesJson = jsonObject.getJSONArray("videoSizes");
        JSONArray audioSizesJson = jsonObject.getJSONArray("audioSizes");
        JSONArray namesJson = jsonObject.getJSONArray("selectedFileNames");

        int count = videoUrlsJson.size();
        this.videoUrls = new String[count];
        this.audioUrls = new String[count];
        this.videoSizes = new long[count];
        this.audioSizes = new long[count];
        this.fileNames = new String[count];

        for (int i = 0; i < count; i++) {
            videoUrls[i] = videoUrlsJson.getString(i);
            audioUrls[i] = audioUrlsJson.getString(i);
            videoSizes[i] = videoSizesJson.getLong(i);
            audioSizes[i] = audioSizesJson.getLong(i);
            fileNames[i] = namesJson.getString(i);
        }

        this.threadNum = jsonObject.getIntValue("threadNum", 5);
        this.mode = jsonObject.getIntValue("threadMode", 0);

        long total = 0;
        for (int i = 0; i < count; i++) {
            total += videoSizes[i] + audioSizes[i];
        }
        this.totalFileSize = total;

        // 清理临时文件夹（父类的 folderName 是文件夹名）
        File tempDir = new File(DataControl.getTempPath(), this.fileName + ".temp");
        if (tempDir.exists()) DataControl.deleteFolder(tempDir, false);
        tempDir.mkdirs();

        addStatusTips(DOWNLOAD_SIZE_PANEL, DOWNLOAD_SPEED_PANEL);
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", "https://www.bilibili.com");
        headers.put("Sec-Fetch-Mode", "no-cors");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/77.0.3865.90 Safari/537.36");
        return headers;
    }

    private String truncateTabTitle(String name) {
        if (name == null) return StringFormat.translate("task", "task.download_task.unknown_file");
        if (name.length() > 10) {
            int dotIdx = name.lastIndexOf('.');
            if (dotIdx > 0 && name.length() - dotIdx <= 5) {
                return name.substring(0, 7) + "..." + name.substring(dotIdx);
            }
            return name.substring(0, 10) + "...";
        }
        return name;
    }

    public void doWhenStart() throws Exception {
        removeAllStatusTip();
        addStatusTips(DOWNLOAD_SIZE_PANEL, DOWNLOAD_SPEED_PANEL);

        if (pauseControllerList.isEmpty()) {
            for (int i = 0; i < videoUrls.length; i++) {
                pauseControllerList.add(new PauseController());
                downloadProgressList.add(new DownloadProgress());
            }
        } else {
            pauseControllerList.forEach(PauseController::resume);
        }
        ProgressBarsPanel.removeAll();


        Map<String, String> headers = buildHeaders();
        completedCount = new AtomicInteger(0);
        hasAnyError = false;

        File tempDir = new File(DataControl.getTempPath(), fileName + ".temp");
        if (!tempDir.exists()) tempDir.mkdirs();

        for (int i = 0; i < videoUrls.length; i++) {
            final int fileIndex = i;
            String videoUrl = videoUrls[i];
            String audioUrl = audioUrls[i];
            long vSize = videoSizes[i];
            long aSize = audioSizes[i];
            String fName = fileNames[i];

            JPanel progressPanel = new JPanel(new GridLayout(1, 0));

            progressBarPanelList.add(UITools.createProgressBarsPanel(progressPanel));

            File partTempDir = new File(tempDir, "part_" + fileIndex);

            partTempDir.mkdirs();

            var ref = new Object() {
                String outputName = String.format("%02d_%s", fileIndex + 1, fName);
            };
            if (!ref.outputName.toLowerCase().endsWith(".mp4")) {
                ref.outputName = ref.outputName + ".mp4";
            }


            Thread.ofVirtual().start(() -> {
                try {
                    downloadAndConvergePart(fileIndex, videoUrl, audioUrl,
                            partTempDir, new File(savePath, fileName), ref.outputName,
                            vSize, aSize, headers, progressPanel);
                } catch (Exception e) {
                    logger.error("文件[" + fName + "]处理异常", e);
                    hasAnyError = true;
                    checkAllFilesCompleted();
                }
            });
        }

        ProgressBarsPanel.add(UITools.createProgressBarsPanel(progressBarPanelList.toArray(JPanel[]::new)));

        progressTimer = new Timer(1000, e -> {
            if (isStart) {
                long totalDownloaded = 0;
                long totalSpeed = 0;
                for (DownloadProgress dp : downloadProgressList) {
                    totalDownloaded += dp.getDownloadedBytes();
                    totalSpeed += dp.getSpeed();
                }
                DOWNLOAD_SIZE_PANEL.setText(DownloadProgress.formatSize(totalDownloaded));
                DOWNLOAD_SPEED_PANEL.setText(DownloadProgress.formatSize(totalSpeed) + "/s");
            }
        });
        progressTimer.start();
    }

    @Override
    public void doWhenRestart() throws Exception {
        pauseControllerList.forEach(PauseController::resume);
        if (progressTimer != null) {
            progressTimer.start();
        }
    }

    private void downloadAndConvergePart(int fileIndex, String videoUrl, String audioUrl,
                                         File partTempDir, File parentDir, String outputName,
                                         long videoSize, long audioSize,
                                         Map<String, String> headers,
                                         JPanel progressPanel) {
        PauseController pc = pauseControllerList.get(fileIndex);
        DownloadProgress dp = downloadProgressList.get(fileIndex);
        pc.resume();

        boolean videoSuccess = false;
        boolean audioSuccess = false;

        try {
            if (videoUrl != null) {
                JProgressBar videoProgressBar = new JProgressBar(0, 100);
                videoProgressBar.setStringPainted(false);
                SwingUtilities.invokeLater(() -> progressPanel.add(videoProgressBar));
                SwingUtilities.invokeLater(() -> progressPanel.revalidate());

                videoSuccess = URLDownloadTool.singleThreadDownload(
                        URI.create(videoUrl), partTempDir, "video.m4s",
                        videoSize, 10, videoProgressBar, pc, dp, headers);

                if (!videoSuccess) {
                    logger.error(StringFormat.translate("task", "task.download_task.video_download_failed") + ": " + outputName);
                    hasAnyError = true;
                    checkAllFilesCompleted();
                    return;
                }
            }

            dp.resetSpeed();
            dp.resetMergedBytes();
            PauseController audioPc = new PauseController();
            audioPc.resume();
            pauseControllerList.set(fileIndex, audioPc);
            DownloadProgress audioDp = new DownloadProgress();
            downloadProgressList.set(fileIndex, audioDp);

            if (audioUrl != null) {
                JProgressBar audioProgressBar = new JProgressBar(0, 100);
                audioProgressBar.setStringPainted(false);
                SwingUtilities.invokeLater(() -> progressPanel.add(audioProgressBar));
                SwingUtilities.invokeLater(() -> progressPanel.revalidate());

                audioSuccess = URLDownloadTool.singleThreadDownload(
                        URI.create(audioUrl), partTempDir, "audio.m4s",
                        audioSize, 10, audioProgressBar, audioPc, audioDp, headers);

                if (!audioSuccess) {
                    logger.error(StringFormat.translate("task", "task.download_task.audio_download_failed") + ": " + outputName);
                    hasAnyError = true;
                    checkAllFilesCompleted();
                    return;
                }
            }

            if (videoSuccess && audioSuccess) {
                SwingUtilities.invokeLater(() -> {
                    progressPanel.removeAll();
                    progressPanel.revalidate();
                    progressPanel.repaint();
                });

                JProgressBar mergeProgressBar = new JProgressBar(0, 100);
                mergeProgressBar.setStringPainted(false);
                SwingUtilities.invokeLater(() -> {
                    progressPanel.add(mergeProgressBar);
                    progressPanel.revalidate();
                });

                exitButton.setEnabled(false);
                downloadControlButton.setEnabled(false);
                boolean converged = ConvergenceTool.converge(
                        new File(partTempDir, "video.m4s"),
                        new File(partTempDir, "audio.m4s"),
                        new File(parentDir, outputName),
                        mergeProgressBar);
                exitButton.setEnabled(true);
                downloadControlButton.setEnabled(true);
                if (!converged) {
                    logger.error(StringFormat.translate("task", "task.download_task.merge_failed") + ": " + outputName);
                    hasAnyError = true;
                }else{
                    downloadControlButton.setEnabled(false);
                }

                SwingUtilities.invokeLater(() -> {
                    progressPanel.removeAll();
                    progressPanel.revalidate();
                    progressPanel.repaint();
                });

                DataControl.deleteFolder(partTempDir, false);
            }

        } catch (Exception e) {
            logger.error(String.format(StringFormat.translate("task", "task.download_task.download_exception") + "[%s]", outputName), e);
            hasAnyError = true;
        } finally {
            checkAllFilesCompleted();
        }
    }

    private void checkAllFilesCompleted() {
        int completed = completedCount.incrementAndGet();
        if (completed >= videoUrls.length) {
            SwingUtilities.invokeLater(() -> {
                if (progressTimer != null) progressTimer.stop();
                ProgressBarsPanel.removeAll();
                downloadControlButton.setEnabled(false);
                isFinally = true;

                File tempDir = new File(DataControl.getTempPath(), fileName + ".temp");
                DataControl.deleteFolder(tempDir, false);

                if (hasAnyError) {
                    removeAllStatusTip();
                    addStatusTip(DOWNLOAD_FAILED_PANEL);
                    ToastMessage.show(this, StringFormat.translate("task", "task.download_task.partial_failed_detail"), ToastMessage.ERROR);
                } else {
                    removeAllStatusTip();
                    addStatusTip(DOWNLOAD_SUCCESS_PANEL);
                }
                this.revalidate();
                this.repaint();
            });
        }
    }

    public void doWhenStop() {
        pauseControllerList.forEach(PauseController::pause);
        if (progressTimer != null) progressTimer.stop();
    }

    @Override
    public void doWhenExit() {
        if (progressTimer != null) progressTimer.stop();
        pauseControllerList.clear();
        downloadProgressList.clear();

        File tempDir = new File(DataControl.getTempPath(), fileName + ".temp");
        DataControl.deleteFolder(tempDir, false);
    }
}
