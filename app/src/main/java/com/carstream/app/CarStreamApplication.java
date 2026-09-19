package com.carstream.app;

import android.app.Application;

public final class CarStreamApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        try { EventLogger.initialize(this); } catch (Throwable ignored) { }
        try { CrashReporter.install(this); } catch (Throwable error) {
            EventLogger.error("App", "Could not install crash handler", error);
        }
    }
}
