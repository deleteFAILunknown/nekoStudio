package com.adb.kitty.ui.it.cpu;

interface ICpuBinder {
    float[] getCpuCurrentFreqs();
    float[] getCpuCoreLimits(int core);
    float[] getGpuMetrics();
    float[] getSystemMetrics();
    long[] getNetworkStats();
    float getMeasuredFps();
    long[] getDiskStats();
    Bundle getBatteryMetrics();
    String getCurrentResolution();
    List<String> getSupportedDisplayModes();
    float getActiveRefreshRate();
}
