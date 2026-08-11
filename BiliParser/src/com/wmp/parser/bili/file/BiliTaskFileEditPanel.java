package com.wmp.parser.bili.file;

import com.wmp.downloader.tools.BiliInfoFormat;
import com.wmp.downloader.tools.StringFormat;
import com.wmp.parser.bili.info.BiliDownloadInfo;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.awt.*;

public class BiliTaskFileEditPanel extends JPanel {

    private static final Logger logger = Logger.getLogger(BiliTaskFileEditPanel.class);

    private JTextField NameTextField;
    private JPanel mainPanel;
    private JLabel sizeLabel;
    private JComboBox<String> VideoQualityComboBox;
    private JComboBox<String> SoundQualityComboBox;
    private JComboBox<String> VideoCodecsComboBox;

    private long size = 0;


    public BiliTaskFileEditPanel(String name, BiliDownloadInfo downloadInfo, int videoInfoIndex, int audioInfoIndex) {
        this.setLayout(new BorderLayout());
        this.add(mainPanel);

        NameTextField.setText(name);

        size = downloadInfo.videoInfos()[videoInfoIndex].size() +
                downloadInfo.audioInfos()[audioInfoIndex].size();
        sizeLabel.setText(StringFormat.formatSize(size));

        //添加值
        for (var i = 0; i < downloadInfo.videoInfos().length; i++) {
            VideoQualityComboBox.addItem(
                    BiliInfoFormat.VideoFormat(downloadInfo.videoInfos()[i].quality()) + " "
                            + BiliInfoFormat.getVideoCode(downloadInfo.videoInfos()[i].codecid()));

        }
        VideoQualityComboBox.setSelectedIndex(videoInfoIndex);
        for (var i = 0; i < downloadInfo.audioInfos().length; i++) {
            SoundQualityComboBox.addItem(
                    BiliInfoFormat.AudioFormat(downloadInfo.audioInfos()[i].bitrate()));

        }
        SoundQualityComboBox.setSelectedIndex(audioInfoIndex);
        //添加监听
        VideoQualityComboBox.addItemListener(e -> {
            if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                int qualityIndex = VideoQualityComboBox.getSelectedIndex();
                if (qualityIndex >= 0 && qualityIndex < downloadInfo.videoInfos().length) {
                    size = downloadInfo.videoInfos()[qualityIndex].size() +
                            downloadInfo.audioInfos()[SoundQualityComboBox.getSelectedIndex()].size();
                    sizeLabel.setText(StringFormat.formatSize(size));
                }
            }
        });

        SoundQualityComboBox.addItemListener(e -> {
            if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                int audioIndex = SoundQualityComboBox.getSelectedIndex();
                if (audioIndex >= 0 && audioIndex < downloadInfo.audioInfos().length) {
                    size = downloadInfo.videoInfos()[VideoQualityComboBox.getSelectedIndex()].size() +
                            downloadInfo.audioInfos()[audioIndex].size();
                    sizeLabel.setText(StringFormat.formatSize(size));
                }
            }
        });

    }

    public String getFileName() {
        return NameTextField.getText();
    }

    public void setFileName(String name) {
        NameTextField.setText(name);
    }

    public long getFileSizeNum() {
        return this.size;
    }

    public int getVideoInfoIndex() {
        return VideoQualityComboBox.getSelectedIndex();
    }

    public int getAudioInfoIndex() {
        return SoundQualityComboBox.getSelectedIndex();
    }

}
