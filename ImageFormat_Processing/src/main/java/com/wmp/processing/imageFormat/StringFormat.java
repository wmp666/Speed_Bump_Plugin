package com.wmp.processing.imageFormat;

import com.wmp.downloader.tools.platform.GetPlatform;

import java.io.File;
import java.util.Locale;
import java.util.ResourceBundle;

public class StringFormat {
    private static final String BUNDLE_PREFIX = "";

    public static String translate(String key) {
        return translate(null, key);
    }

    public static String translate(String rootLocal, String key) {
        ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_PREFIX + "laug", Locale.getDefault());
        try {
            return bundle.getString(key);
        } catch (Exception e) {
            return String.format("%s: %s", "laug", key);
        }
    }

}