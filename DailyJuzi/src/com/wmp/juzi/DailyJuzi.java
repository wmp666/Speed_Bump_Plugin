package com.wmp.juzi;

import com.alibaba.fastjson2.JSONObject;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractParser;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractSpecialSettingsPage;
import com.wmp.downloader.newArchitecture.abstractTask.AbstractTask;
import com.wmp.downloader.newArchitecture.abstractTask.linkInfoPanel.AbstractLinkInfoPanel;

/**
 * 「每日金句」插件主类。
 *
 * <p>说明：减速带（DownLoader）的第三方插件机制只识别 {@link AbstractParser} 子类，
 * 因此本类继承它以便被主程序加载。本插件并不解析下载链接：{@link #isMeetRequirements} 恒为
 * {@code false}，相关链接方法均为空实现，插件实际行为（读文本 + 定时/启动通知）由
 * {@link JuziReminder} 承担。</p>
 *
 * @author 吴鹤轩
 */
public class DailyJuzi extends AbstractParser {

    static {
        // 插件主类被主程序加载（实例化）时，启动提醒调度器。
        JuziReminder.getInstance().start();
    }

    @Override
    public String getID() {
        return "每日金句";
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
        // TODO: 主程序若仍会以空链接触发，可在主程序侧过滤掉本插件，而不在此返回空面板。
        return null;
    }

    @Override
    public boolean isMeetRequirements(String link) {
        // 永不匹配，避免“每日金句”被当作解析器去尝试抓取用户输入。
        return false;
    }

    @Override
    protected AbstractTask getTask(String link, JSONObject infoJson) {
        return null;
    }

    @Override
    public AbstractSpecialSettingsPage getSettingsPage() {
        // 特殊设置页面（会出现在“设置 -> 特殊设置”的 Tab 中）。
        return new DailyJuziSettings();
    }
}
