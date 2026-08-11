package com.wmp.parser.ed2k;

import com.alibaba.fastjson2.JSONObject;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractParser;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractSpecialSettingsPage;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractTask;
import com.wmp.downloader.newArchitecture.abstractTask.linkInfoPanel.AbstractLinkInfoPanel;
import com.wmp.downloader.newArchitecture.abstractTask.linkInfoPanel.LinkFileInfoPanel;
import com.wmp.downloader.newArchitecture.ui.task.gopeed.GopeedParser;

public class ED2KParser extends AbstractParser {

    private final GopeedParser parser = new GopeedParser();
    private Info info;

    @Override
    public String getID() {
        return "ED2K解析器";
    }

    @Override
    public String getSupportTip() {
        return "ED2K";
    }

    @Override
    protected void updateLinkInfo(String link) {
        info = parser.getParserInfo(link);
    }

    @Override
    protected AbstractLinkInfoPanel getLinkedInfoPanel(String link, Info info) {
        // 1. 去除 "ed2k://|file|" 前缀和末尾的 "|/"
        String processingStr = link.substring("ed2k://|file|".length(), link.length() - 2);

        // 2. 按 "|" 分割
        String[] parts = processingStr.split("\\|");

        if (parts.length >= 3) {
            String fileName = parts[0];
            String fileSize = parts[1]; // 这是字符串形式的字节数

            return LinkFileInfoPanel
                    .createPanel(fileName, Long.parseLong(fileSize), "ed2k", link, info);
        }
        return null;
    }

    @Override
    public boolean isMeetRequirements(String link) {
        return link.strip().startsWith("ed2k://");
    }

    @Override
    protected AbstractTask getTask(String link, JSONObject infoJson) {
        return info.getTask(infoJson);
    }


    @Override
    public AbstractSpecialSettingsPage getSettingsPage() {
        return null;
    }
}
