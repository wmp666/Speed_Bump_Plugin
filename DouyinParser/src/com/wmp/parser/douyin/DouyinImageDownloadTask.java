package com.wmp.parser.douyin;

import com.alibaba.fastjson2.JSONObject;
import com.wmp.downloader.newArchitecture.abstractTask.downloadTask.FolderDownloadTask;
import com.wmp.downloader.newArchitecture.abstractTask.downloadTask.StatusTipPanel;
import com.wmp.downloader.tools.StringFormat;
import com.wmp.downloader.tools.download.URLDownloadTool;
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

public class DouyinImageDownloadTask extends FolderDownloadTask {
    private static final Logger logger = Logger.getLogger(DouyinImageDownloadTask.class);

    private final String[] imageUrls;          // 每张图片的下载链接
    private final String[] imageNames;         // 每张图片的文件名
    private final long[] imageSizes;           // 每张图片的文件大小（字节）
    private final long totalFileSize;          // 所有图片总大小

    private final ArrayList<URLDownloadTool.PauseController> pauseControllerList = new ArrayList<>();
    private final ArrayList<URLDownloadTool.DownloadProgress> downloadProgressList = new ArrayList<>();
    private final ArrayList<JPanel> progressBarPanelList = new ArrayList<>();
    private Timer progressTimer;
    //private JTabbedPane fileTabbedPane;
    private AtomicInteger completedCount;
    private volatile boolean hasAnyError = false;

    private final StatusTipPanel DOWNLOAD_SIZE_PANEL = StatusTipPanel.DOWNLOAD_SIZE_CREATOR.create();
    private final StatusTipPanel DOWNLOAD_SPEED_PANEL = StatusTipPanel.DOWNLOAD_SPEED_CREATOR.create();
    private final StatusTipPanel DOWNLOAD_FAILED_PANEL = StatusTipPanel.DOWNLOAD_FAILED_CREATOR.create();
    private final StatusTipPanel DOWNLOAD_SUCCESS_PANEL = StatusTipPanel.DOWNLOAD_SUCCESS_CREATOR.create();

    /**
     * 构造器
     */
    public DouyinImageDownloadTask(JSONObject jsonObject) {
        super(jsonObject);
        this.imageUrls = jsonObject.getJSONArray("selectedUrls").stream().map(Object::toString).toArray(String[]::new);
        this.imageNames = jsonObject.getJSONArray("selectedFileNames").stream().map(Object::toString).toArray(String[]::new);
        this.imageSizes = jsonObject.getJSONArray("sizes").stream().mapToLong(size -> Long.parseLong(size.toString())).toArray();

        // 计算总大小
        long total = 0;
        for (long s : imageSizes) total += s;
        this.totalFileSize = total;

        // 创建临时目录（用于存放分块文件）
        File tempDir = new File(DataControl.getTempPath(), fileName + ".temp");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        } else {
            DataControl.deleteFolder(tempDir, false);
            tempDir.mkdirs();
        }

        addStatusTips(DOWNLOAD_SIZE_PANEL, DOWNLOAD_SPEED_PANEL);
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", "https://www.douyin.com");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
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

    @Override
    public void doWhenStart() throws Exception {
        removeAllStatusTip();
        addStatusTips(DOWNLOAD_SIZE_PANEL, DOWNLOAD_SPEED_PANEL);

        // 初始化暂停控制器和进度对象
        if (pauseControllerList.isEmpty()) {
            for (int i = 0; i < imageUrls.length; i++) {
                pauseControllerList.add(new URLDownloadTool.PauseController());
                downloadProgressList.add(new URLDownloadTool.DownloadProgress());
            }
        } else {
            pauseControllerList.forEach(URLDownloadTool.PauseController::resume);
        }

        // UI 初始化：选项卡面板
        ProgressBarsPanel.removeAll();

        Map<String, String> headers = buildHeaders();
        completedCount = new AtomicInteger(0);
        hasAnyError = false;

        File tempDir = new File(DataControl.getTempPath(), fileName + ".temp");
        if (!tempDir.exists()) tempDir.mkdirs();

        // 为每张图片启动一个虚拟线程并发下载
        for (int i = 0; i < imageUrls.length; i++) {
            final int index = i;
            String imgName = imageNames[index];
            long imgSize = imageSizes[index];

            // 创建该图片的 UI 面板
            JPanel progressPanel = new JPanel(new GridLayout(1, 0));

            progressBarPanelList.add(progressPanel);

            // 每个图片独立存储分块的临时子目录
            File partTempDir = new File(tempDir, "part_" + index);
            partTempDir.mkdirs();

            Thread.ofVirtual().start(() -> {
                try {
                    downloadImage(index, imageUrls[index], partTempDir,
                            new File(savePath, fileName), imgName,
                            imgSize, headers, progressPanel);
                } catch (Exception e) {
                    logger.error("图片[" + imgName + "]下载异常", e);
                    hasAnyError = true;
                    checkAllFilesCompleted();
                }
            });
        }

        ProgressBarsPanel.add(UITools.createProgressBarsPanel(progressBarPanelList.toArray(JPanel[]::new)));

        // 定时器更新总进度信息
        progressTimer = new Timer(1000, e -> {
            if (isStart) {
                long totalDownloaded = 0;
                long totalSpeed = 0;
                for (URLDownloadTool.DownloadProgress dp : downloadProgressList) {
                    totalDownloaded += dp.getDownloadedBytes();
                    totalSpeed += dp.getSpeed();
                }
                DOWNLOAD_SIZE_PANEL.setText(URLDownloadTool.DownloadProgress.formatSize(totalDownloaded));
                DOWNLOAD_SPEED_PANEL.setText(URLDownloadTool.DownloadProgress.formatSize(totalSpeed) + "/s");
            }
        });
        progressTimer.start();
    }

    @Override
    public void doWhenRestart() throws Exception {
        pauseControllerList.forEach(URLDownloadTool.PauseController::resume);
        if (progressTimer != null) progressTimer.restart();

    }

    /**
     * 下载单张图片（支持多线程分块或单线程）
     */
    private void downloadImage(int index, String url, File tempDir, File parentDir,
                               String fileName, long fileSize,
                               Map<String, String> headers, JPanel progressPanel) {
        URLDownloadTool.PauseController pc = pauseControllerList.get(index);
        URLDownloadTool.DownloadProgress dp = downloadProgressList.get(index);
        pc.resume();
        dp.resetSpeed();
        dp.resetMergedBytes();

        try {
            URI uri = URI.create(url);


                // ----- 单线程下载 -----
                JProgressBar singleBar = new JProgressBar(0, 100);
                singleBar.setStringPainted(false);
                SwingUtilities.invokeLater(() -> {
                    progressPanel.add(singleBar);
                    progressPanel.revalidate();
                });

                boolean success = URLDownloadTool.singleThreadDownload(
                        uri, parentDir, fileName, fileSize,
                        10, singleBar, pc, dp, headers
                );

                if (!success) {
                    logger.error("单线程下载图片失败: " + fileName);
                    hasAnyError = true;
                    return;
                }

            // 下载成功，更新UI
            SwingUtilities.invokeLater(() -> {
                progressPanel.removeAll();
                progressPanel.revalidate();
                progressPanel.repaint();
            });

        } catch (Exception e) {
            logger.error("图片下载异常: " + fileName, e);
            hasAnyError = true;
        } finally {
            checkAllFilesCompleted();
        }
    }

    /**
     * 检查是否所有图片都处理完毕
     */
    private void checkAllFilesCompleted() {
        int completed = completedCount.incrementAndGet();
        if (completed >= imageUrls.length) {
            SwingUtilities.invokeLater(() -> {
                if (progressTimer != null) progressTimer.stop();
                ProgressBarsPanel.removeAll();
                downloadControlButton.setEnabled(false);
                isFinally = true;

                // 删除临时目录
                File tempDir = new File(DataControl.getTempPath(), fileName + ".temp");
                DataControl.deleteFolder(tempDir, false);

                if (hasAnyError) {
                    removeAllStatusTip();
                    addStatusTip(DOWNLOAD_FAILED_PANEL);
                    ToastMessage.show(this, StringFormat.translate("task", "task.download_task.partial_failed"), ToastMessage.ERROR);
                } else {
                    removeAllStatusTip();
                    addStatusTip(DOWNLOAD_SUCCESS_PANEL);
                }
                this.revalidate();
                this.repaint();
            });
        }
    }

    @Override
    public void doWhenStop() {
        pauseControllerList.forEach(URLDownloadTool.PauseController::pause);
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
