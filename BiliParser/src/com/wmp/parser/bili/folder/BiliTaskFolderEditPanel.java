package com.wmp.parser.bili.folder;


import com.wmp.downloader.tools.BiliInfoFormat;
import com.wmp.parser.bili.info.BiliDownloadInfo;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class BiliTaskFolderEditPanel extends JPanel {

    private static final Logger logger = Logger.getLogger(BiliTaskFolderEditPanel.class);
    //记录指定质量信息的对应下载链接
    private final HashMap<String, String[]> videoUrlMap = new HashMap<>();
    private final HashMap<String, String[]> audioUrlMap = new HashMap<>();
    private JTextField folderNameTextField;
    private JPanel mainPanel;
    private JComboBox<String> QualityComboBox;
    //private int[] quality_int = new int[0];
    private JComboBox<String> VideoQualityComboBox;
    private JComboBox<String> SoundQualityComboBox;

    /**
     * 编辑器
     *
     * @param name                  标题
     * @param downloadInfos         所有下载信息
     * @param videoQualityInfoIndex 按照从大到小的顺序排列出的画质数据的第几行
     * @param audioQualityInfoIndex 按照从大到小的顺序排列出的音质数据的第几行
     */
    public BiliTaskFolderEditPanel(String name, BiliDownloadInfo[] downloadInfos, int videoQualityInfoIndex, int audioQualityInfoIndex) {
        this.setLayout(new BorderLayout());
        this.add(mainPanel);

        folderNameTextField.setText(name);

        //添加值
        ArrayList<String> tempVideoQualityList = new ArrayList<>();
        for (var i = 0; i < downloadInfos.length; i++) {
            for (var j = 0; j < downloadInfos[i].videoInfos().length; j++) {

                var tempString = BiliInfoFormat.VideoFormat(downloadInfos[i].videoInfos()[j].quality()) + " "
                        + BiliInfoFormat.getVideoCode(downloadInfos[i].videoInfos()[j].codecid());

                addUrlInList(videoUrlMap, tempString, downloadInfos[i].videoInfos()[j].url());


                if (tempVideoQualityList.contains(tempString)) {
                    //已存在该画质信息
                    continue;
                }

                VideoQualityComboBox.addItem(tempString);
                tempVideoQualityList.add(tempString);
            }
        }
        VideoQualityComboBox.setSelectedIndex(videoQualityInfoIndex);

        ArrayList<String> tempSoundQualityList = new ArrayList<>();
        for (var i = 0; i < downloadInfos.length; i++) {
            for (var j = 0; j < downloadInfos[i].audioInfos().length; j++) {

                var tempString = BiliInfoFormat.AudioFormat(downloadInfos[i].audioInfos()[j].bitrate());

                addUrlInList(audioUrlMap, tempString, downloadInfos[i].audioInfos()[j].url());

                if (tempSoundQualityList.contains(tempString)) continue;

                SoundQualityComboBox.addItem(tempString);
                tempSoundQualityList.add(tempString);
            }
        }
        SoundQualityComboBox.setSelectedIndex(audioQualityInfoIndex);
        //添加监听


        /*Map<Integer, String> qualityMap = new HashMap<>();
        for (int i = 0; i < quality_int.length; i++) {
            qualityMap.put(quality_int[i], quality_str[i]);
        }
        logger.info("画质数据：" + qualityMap);

        this.quality_int = quality_int;
        QualityComboBox.removeAllItems();
        for (var s : quality_str) {
            QualityComboBox.addItem(s);
        }

        folderNameTextField.setText(name);

        var qualityInt = new ArrayList<>();
        for (int i : quality_int) {
            qualityInt.add(i);
        }
        QualityComboBox.setSelectedItem(quality_str[qualityInt.indexOf(quality)]);*/

    }

    private void addUrlInList(HashMap<String, String[]> map, String key, String value) {
        if (map.containsKey(key)) {
            var strings = map.get(key);
            strings = Arrays.copyOf(strings, strings.length + 1);
            strings[strings.length - 1] = value;
            map.put(key, strings);
        } else {
            map.put(key, new String[]{value});
        }
    }

    public String getFileName() {
        return folderNameTextField.getText();
    }

    public void setFileName(String name) {
        folderNameTextField.setText(name);
    }

    public int getVideoInfoIndex() {
        return VideoQualityComboBox.getSelectedIndex();
    }

    public int getAudioInfoIndex() {
        return SoundQualityComboBox.getSelectedIndex();
    }

    /**
     * 用于获取每个视频链接的位置
     *
     * @return 每个视频链接的位置
     */
    public String[] getVideoUrl() {
        return videoUrlMap.get(VideoQualityComboBox.getSelectedItem().toString());
    }

    /**
     * 用于获取每个音频链接的位置
     *
     * @return 每个音频链接的位置
     */
    public String[] getAudioUrl() {
        return audioUrlMap.get(SoundQualityComboBox.getSelectedItem().toString());
    }
}
