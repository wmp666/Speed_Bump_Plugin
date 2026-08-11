package com.wmp.parser.bili.info;

import java.util.Arrays;

/**
 * 存放B站某一个视频中的所有视频和音频（画质，大小，下载链接，编码格式）
 *
 * @param videoInfos
 * @param audioInfos
 */
public record BiliDownloadInfo(BiliVideoInfo[] videoInfos, BiliAudioInfo[] audioInfos) {
    @Override
    public String toString() {
        return "BiliDownloadInfo{" +
                "videoInfos=" + Arrays.toString(videoInfos) +
                ", audioInfos=" + Arrays.toString(audioInfos) +
                '}';
    }
}
