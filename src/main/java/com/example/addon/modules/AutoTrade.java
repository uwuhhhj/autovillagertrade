package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;

public class AutoTrade extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    public AutoTrade() {
        super(AddonTemplate.CATEGORY, "auto-trade", "自动化村民交易模块。");
    }
    // 你可以在这里添加设置和事件处理方法
} 