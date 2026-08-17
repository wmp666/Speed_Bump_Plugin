package com.wmp.processing.imageFormat;

import com.alibaba.fastjson2.JSONObject;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractTask;
import com.wmp.downloader.tools.ui.ToastMessage;
import org.apache.log4j.Logger;

import java.io.File;

public class ImageConverterTask extends AbstractTask {
    private static final Logger logger = Logger.getLogger(ImageConverterTask.class);

    public ImageConverterTask(JSONObject jsonObject) {
        super(jsonObject);
    }

    @Override
    public void doWhenStart() throws Exception {
        this.downloadControlButton.setEnabled(false);
        this.exitButton.setEnabled(false);
        Thread.ofVirtual().start(()->{
            try {
                ImageConverter.convertImage(this.jsonObject.getString("url"), new File(this.savePath, this.fileName).getAbsolutePath(), this.jsonObject.getString("image_type"), null);
                this.exitButton.setEnabled(true);
                isFinally = true;
            } catch (Exception e) {
                isStart = false;
                isFinally = false;
                startCount = 0;
                this.downloadControlButton.setEnabled(true);
                this.exitButton.setEnabled(true);
                logger.error("类型转换失败", e);
                ToastMessage.show(Translate.translate("failed"), ToastMessage.ERROR);
            }

        });

    }

    @Override
    public void doWhenRestart() throws Exception {

    }

    @Override
    public void doWhenStop() {

    }
}
