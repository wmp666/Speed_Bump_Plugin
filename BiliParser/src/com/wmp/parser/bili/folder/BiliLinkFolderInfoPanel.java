package com.wmp.parser.bili.folder;

import com.wmp.downloader.exception.BiliDownloadTaskException;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractParser;
import com.wmp.downloader.newArchitecture.abstractTask.linkInfoPanel.LinkFolderInfoPanel;
import com.wmp.downloader.tools.BiliInfoFormat;
import com.wmp.downloader.tools.StringFormat;
import com.wmp.downloader.ui.FunctionDialog;
import com.wmp.parser.bili.info.BiliAudioInfo;
import com.wmp.parser.bili.info.BiliDownloadInfo;
import com.wmp.parser.bili.info.BiliVideoInfo;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BiliLinkFolderInfoPanel extends LinkFolderInfoPanel {

    private static Logger logger = Logger.getLogger(BiliLinkFolderInfoPanel.class);

    private String[] originalPartNames;

    private final long[][] sizes;
    private final BiliDownloadInfo[] downloadInfos;
    private int videoQualityInfoIndex = 0;
    private int audioQualityInfoIndex = 0;
    private final ArrayList<String> allVideoUrlList = new ArrayList<>();
    private final ArrayList<String> allAudioUrlList = new ArrayList<>();

    //private int[] qualities;
    //private String[] qualityStrList;
    //private int selectionQuality = 64;

    //private final String BVID;
    //private final long[] cids;

    public BiliLinkFolderInfoPanel(String folderName, String[] partNames, BiliDownloadInfo[] downloadInfos, AbstractParser.Info info) {
        if (downloadInfos == null) throw new BiliDownloadTaskException("下载信息不能为空");
        long[] tempSizes = new long[downloadInfos.length];
        for (var i = 0; i < downloadInfos.length; i++) {
            tempSizes[i] = downloadInfos[i].videoInfos()[0].size() + downloadInfos[i].audioInfos()[0].size();
        }
        String[] tempUrls = new String[downloadInfos.length];
        for (var i = 0; i < downloadInfos.length; i++) {
            tempUrls[i] = downloadInfos[i].videoInfos()[0].url();
        }
        var fileTypes = new String[downloadInfos.length];
        Arrays.fill(fileTypes, "mp4");
        super(folderName, tempSizes, "bilibili", tempUrls, partNames, fileTypes, info);

        this.originalPartNames = partNames.clone();   // 保存原始名称
        this.sizes = new long[downloadInfos.length][2];
        this.downloadInfos = downloadInfos;

        updateJsonInfo();
    }

    private void updateJsonInfo() {
        jsonInfo.put("linkStyle", 1);
        jsonInfo.put("rootName", folderName);
        jsonInfo.put("partNames", originalPartNames);
        jsonInfo.put("selectedStatus", fileSelectionStatus); // boolean[]，父类字段

        // 获取当前全局画质/音质字符串列表（与 resetSizes 逻辑一致）
        List<String> uniqueVideoQualities = new ArrayList<>();
        for (BiliDownloadInfo info : downloadInfos) {
            for (BiliVideoInfo vi : info.videoInfos()) {
                String q = BiliInfoFormat.VideoFormat(vi.quality()) + " " + BiliInfoFormat.getVideoCode(vi.codecid());
                if (!uniqueVideoQualities.contains(q)) uniqueVideoQualities.add(q);
            }
        }
        List<String> uniqueAudioQualities = new ArrayList<>();
        for (BiliDownloadInfo info : downloadInfos) {
            for (BiliAudioInfo ai : info.audioInfos()) {
                String q = BiliInfoFormat.AudioFormat(ai.bitrate());
                if (!uniqueAudioQualities.contains(q)) uniqueAudioQualities.add(q);
            }
        }
        String selectedVideoQ = (videoQualityInfoIndex >= 0 && videoQualityInfoIndex < uniqueVideoQualities.size())
                ? uniqueVideoQualities.get(videoQualityInfoIndex) : null;
        String selectedAudioQ = (audioQualityInfoIndex >= 0 && audioQualityInfoIndex < uniqueAudioQualities.size())
                ? uniqueAudioQualities.get(audioQualityInfoIndex) : null;

        // 收集选中文件的信息
        List<String> selectedNames = new ArrayList<>();
        List<String> videoUrlList = new ArrayList<>();
        List<String> audioUrlList = new ArrayList<>();
        List<Long> videoSizeList = new ArrayList<>();
        List<Long> audioSizeList = new ArrayList<>();

        for (int i = 0; i < downloadInfos.length; i++) {
            if (!fileSelectionStatus[i]) continue;

            // 匹配视频
            BiliVideoInfo selectedVideo = null;
            for (BiliVideoInfo vi : downloadInfos[i].videoInfos()) {
                String q = BiliInfoFormat.VideoFormat(vi.quality()) + " " + BiliInfoFormat.getVideoCode(vi.codecid());
                if (q.equals(selectedVideoQ)) {
                    selectedVideo = vi;
                    break;
                }
            }
            if (selectedVideo == null) selectedVideo = downloadInfos[i].videoInfos()[0]; // fallback

            // 匹配音频
            BiliAudioInfo selectedAudio = null;
            for (BiliAudioInfo ai : downloadInfos[i].audioInfos()) {
                String q = BiliInfoFormat.AudioFormat(ai.bitrate());
                if (q.equals(selectedAudioQ)) {
                    selectedAudio = ai;
                    break;
                }
            }
            if (selectedAudio == null) selectedAudio = downloadInfos[i].audioInfos()[0]; // fallback

            String fileName = originalPartNames[i] + ".mp4";
            selectedNames.add(fileName);
            videoUrlList.add(selectedVideo.url());
            audioUrlList.add(selectedAudio.url());
            videoSizeList.add(selectedVideo.size());
            audioSizeList.add(selectedAudio.size());
        }

        // 写入 jsonInfo
        jsonInfo.put("selectedFileNames", selectedNames.toArray(String[]::new));
        jsonInfo.put("videoUrls", videoUrlList.toArray(String[]::new));
        jsonInfo.put("audioUrls", audioUrlList.toArray(String[]::new));
        jsonInfo.put("videoSizes", videoSizeList.stream().mapToLong(Long::longValue).toArray());
        jsonInfo.put("audioSizes", audioSizeList.stream().mapToLong(Long::longValue).toArray());
        // 可选：总大小
        long total = 0;
        for (int i = 0; i < videoSizeList.size(); i++) {
            total += videoSizeList.get(i) + audioSizeList.get(i);
        }
        jsonInfo.put("totalSize", total);
    }


    @Override
    public void editButtonAction(ActionEvent e) {
        //更新所有数据
        var taskFileEditPanel = new BiliTaskFolderEditPanel(folderNameLabel.getText(), this.downloadInfos, this.videoQualityInfoIndex, this.audioQualityInfoIndex);
        FunctionDialog.showDialog(SwingUtilities.getWindowAncestor(this), StringFormat.translate("task", "task.create_task.download_settings.task_edit"), taskFileEditPanel,
                result -> {
                    if (result == FunctionDialog.RESULT_SAVE) {
                        folderNameLabel.setText(taskFileEditPanel.getFileName());
                        this.folderName = taskFileEditPanel.getFileName();
                        this.videoQualityInfoIndex = taskFileEditPanel.getVideoInfoIndex();
                        this.audioQualityInfoIndex = taskFileEditPanel.getAudioInfoIndex();
                        //清理
                        allVideoUrlList.clear();
                        allAudioUrlList.clear();

                        //添加
                        allVideoUrlList.addAll(List.of(taskFileEditPanel.getVideoUrl()));
                        allAudioUrlList.addAll(List.of(taskFileEditPanel.getAudioUrl()));

                        resetSize();
                        updateJsonInfo();
                    }
                },
                FunctionDialog.SAVE_CANCEL_BUTTONS, 0, null, 0);
    }

    @Override
    public void selectionFileListChangeAction() {
        resetSize();           // 更新界面显示大小
        updateJsonInfo();      // 同步 jsonInfo
    }

    /**
     * 刷新每个下载信息中的视频，音频大小（已选的质量信息对应的）
     */
    private void resetSizes() {
        ArrayList<String> uniqueVideoQualities = new ArrayList<>();
        for (var info : downloadInfos) {
            for (var vi : info.videoInfos()) {
                String qStr = BiliInfoFormat.VideoFormat(vi.quality()) + " "
                        + BiliInfoFormat.getVideoCode(vi.codecid());
                if (!uniqueVideoQualities.contains(qStr)) {
                    uniqueVideoQualities.add(qStr);
                }
            }
        }

        ArrayList<String> uniqueAudioQualities = new ArrayList<>();
        for (var info : downloadInfos) {
            for (var ai : info.audioInfos()) {
                String qStr = BiliInfoFormat.AudioFormat(ai.bitrate());
                if (!uniqueAudioQualities.contains(qStr)) {
                    uniqueAudioQualities.add(qStr);
                }
            }
        }

        String selectedVideoQ = (videoQualityInfoIndex < uniqueVideoQualities.size())
                ? uniqueVideoQualities.get(videoQualityInfoIndex) : null;
        String selectedAudioQ = (audioQualityInfoIndex < uniqueAudioQualities.size())
                ? uniqueAudioQualities.get(audioQualityInfoIndex) : null;

        for (int i = 0; i < downloadInfos.length; i++) {
            int vIdx = 0;
            if (selectedVideoQ != null) {
                for (int j = 0; j < downloadInfos[i].videoInfos().length; j++) {
                    String qStr = BiliInfoFormat.VideoFormat(downloadInfos[i].videoInfos()[j].quality()) + " "
                            + BiliInfoFormat.getVideoCode(downloadInfos[i].videoInfos()[j].codecid());
                    if (qStr.equals(selectedVideoQ)) {
                        vIdx = j;
                        break;
                    }
                }
            }

            int aIdx = 0;
            if (selectedAudioQ != null) {
                for (int j = 0; j < downloadInfos[i].audioInfos().length; j++) {
                    String qStr = BiliInfoFormat.AudioFormat(downloadInfos[i].audioInfos()[j].bitrate());
                    if (qStr.equals(selectedAudioQ)) {
                        aIdx = j;
                        break;
                    }
                }
            }

            this.allFileSizes[i] = downloadInfos[i].videoInfos()[vIdx].size() + downloadInfos[i].audioInfos()[aIdx].size();
            sizes[i][0] = downloadInfos[i].videoInfos()[vIdx].size();
            sizes[i][1] = downloadInfos[i].audioInfos()[aIdx].size();
        }
    }

    private void resetSize() {
        resetSizes();

        //获取真正被选中的文件大小
        long tempSize = 0;
        for (var i = 0; i < this.fileSelectionStatus.length; i++) {
            if (this.fileSelectionStatus[i]) {//选中
                tempSize += this.sizes[i][0] + this.sizes[i][1];
            }
        }
        sizeLabel.setText(StringFormat.formatSize(tempSize));
    }


    /**
     * 获取下载链接
     *
     * @return 下载链接 [选中的文件][选中文件中的各个下载链接]
     */
    public String[][] getBiliDownloadUrls() {
        ArrayList<String> uniqueVideoQualities = new ArrayList<>();
        for (var info : downloadInfos) {
            for (var vi : info.videoInfos()) {
                String qStr = BiliInfoFormat.VideoFormat(vi.quality()) + " "
                        + BiliInfoFormat.getVideoCode(vi.codecid());
                if (!uniqueVideoQualities.contains(qStr)) {
                    uniqueVideoQualities.add(qStr);
                }
            }
        }

        ArrayList<String> uniqueAudioQualities = new ArrayList<>();
        for (var info : downloadInfos) {
            for (var ai : info.audioInfos()) {
                String qStr = BiliInfoFormat.AudioFormat(ai.bitrate());
                if (!uniqueAudioQualities.contains(qStr)) {
                    uniqueAudioQualities.add(qStr);
                }
            }
        }

        String selectedVideoQ = (videoQualityInfoIndex < uniqueVideoQualities.size())
                ? uniqueVideoQualities.get(videoQualityInfoIndex) : null;
        String selectedAudioQ = (audioQualityInfoIndex < uniqueAudioQualities.size())
                ? uniqueAudioQualities.get(audioQualityInfoIndex) : null;

        ArrayList<String[]> result = new ArrayList<>();
        for (int i = 0; i < downloadInfos.length; i++) {
            if (!fileSelectionStatus[i]) continue;

            String videoUrl = null;
            if (selectedVideoQ != null) {
                for (var vi : downloadInfos[i].videoInfos()) {
                    String qStr = BiliInfoFormat.VideoFormat(vi.quality()) + " "
                            + BiliInfoFormat.getVideoCode(vi.codecid());
                    if (qStr.equals(selectedVideoQ)) {
                        videoUrl = vi.url();
                        break;
                    }
                }
            }

            String audioUrl = null;
            if (selectedAudioQ != null) {
                for (var ai : downloadInfos[i].audioInfos()) {
                    String qStr = BiliInfoFormat.AudioFormat(ai.bitrate());
                    if (qStr.equals(selectedAudioQ)) {
                        audioUrl = ai.url();
                        break;
                    }
                }
            }

            ArrayList<String> urls = new ArrayList<>();
            if (videoUrl != null) urls.add(videoUrl);
            if (audioUrl != null) urls.add(audioUrl);
            result.add(urls.toArray(String[]::new));
        }
        return result.toArray(String[][]::new);
    }

    public long[] getSelectedBiliFileSizes() {
        ArrayList<Long> result = new ArrayList<>();
        for (int i = 0; i < downloadInfos.length; i++) {
            if (fileSelectionStatus[i]) {
                result.add(sizes[i][0] + sizes[i][1]);
            }
        }
        return result.stream().mapToLong(Long::longValue).toArray();
    }

    public long getSelectedBiliTotalSize() {
        long total = 0;
        for (int i = 0; i < downloadInfos.length; i++) {
            if (fileSelectionStatus[i]) {
                total += sizes[i][0] + sizes[i][1];
            }
        }
        return total;
    }

    public long[] getSelectedVideoSizes() {
        ArrayList<Long> result = new ArrayList<>();
        for (int i = 0; i < downloadInfos.length; i++) {
            if (fileSelectionStatus[i]) {
                result.add(sizes[i][0]);
            }
        }
        return result.stream().mapToLong(Long::longValue).toArray();
    }

    public long[] getSelectedAudioSizes() {
        ArrayList<Long> result = new ArrayList<>();
        for (int i = 0; i < downloadInfos.length; i++) {
            if (fileSelectionStatus[i]) {
                result.add(sizes[i][1]);
            }
        }
        return result.stream().mapToLong(Long::longValue).toArray();
    }

}
