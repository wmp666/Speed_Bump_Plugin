package com.wmp.packagetool;

import com.alibaba.fastjson2.JSONObject;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractParser;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractSpecialSettingsPage;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractTask;
import com.wmp.downloader.newArchitecture.abstractTask.linkInfoPanel.AbstractLinkInfoPanel;

/**
 * 「打包工具」插件主类。
 *
 * <p>说明：减速带（DownLoader）的第三方插件机制只识别 {@link AbstractParser} 子类，
 * 因此本类继承它以便被主程序加载。本插件并不解析下载链接：{@link #isMeetRequirements} 恒为
 * {@code false}，相关链接方法均为空实现。</p>
 *
 * <p>插件没有后台线程，也不会在加载时做任何事——所有功能都在「设置 → 特殊设置 → 打包工具」
 * 页面里（{@link PackageToolSettings}），由用户点「开始打包」时才启动 jpackage 进程。
 * 这一点和「窗口遮挡」不同，因此这里没有 static 初始化块。</p>
 *
 * @author 无名牌
 */
public class PackageTool extends AbstractParser {

    @Override
    public String getID() {
        return "打包工具";
    }

    @Override
    public String getSupportTip() {
        // 本插件不属于链接解析器，不希望在“支持”列表中展示额外说明，故返回空白。
        return "";
    }

    @Override
    protected void updateLinkInfo(String link) {
        // 无链接解析逻辑
    }

    @Override
    protected AbstractLinkInfoPanel getLinkedInfoPanel(String link, Info info) {
        // 本插件不处理任何链接，不会走到此方法。
        return null;
    }

    @Override
    public boolean isMeetRequirements(String link) {
        // 永不匹配，避免「打包工具」被当作解析器去尝试抓取用户输入。
        return false;
    }

    @Override
    protected AbstractTask getTask(String link, JSONObject infoJson) {
        return null;
    }

    @Override
    public AbstractSpecialSettingsPage getSettingsPage() {
        // 特殊设置页面（会出现在“设置 -> 特殊设置”的 Tab 中）。
        return new PackageToolSettings();
    }
}
