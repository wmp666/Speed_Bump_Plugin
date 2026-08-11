package com.wmp.parser.douyin;

import com.alibaba.fastjson2.JSONObject;
import com.wmp.downloader.newArchitecture.ui.task.http.HTTPDownloadTask;

public class DouyinVideoDownloadTask extends HTTPDownloadTask {
    public DouyinVideoDownloadTask(JSONObject jsonObject) {
        jsonObject.put("threadMode", 1);
        super(jsonObject);
    }
}
