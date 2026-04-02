package com.bg7yoz.ft8cn;

import com.bg7yoz.ft8cn.connector.ConnectMode;

public final class GeneralVariables {
    public static final long START_QUERY_FREQ_DELAY = 1000L;
    public static final long QUERY_FREQ_TIMEOUT = 1000L;

    public static boolean swr_switch_on = false;
    public static boolean alc_switch_on = false;
    public static int connectMode = ConnectMode.USB_CABLE;
    public static int instructionSet = 0;
    public static float volumePercent = 1.0f;
    public static float baseFrequency = 1500f;

    private GeneralVariables() {
    }

    public static String getStringFromResource(int id) {
        if (id == R.string.none) return "None";
        if (id == R.string.swr_high_alert) return "SWR high";
        if (id == R.string.alc_high_alert) return "ALC high";
        if (id == R.string.alc_low_alert) return "ALC low";
        return "";
    }

    public static float getBaseFrequency() {
        return baseFrequency;
    }
}
