package com.carstream.app;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashReporter {
    private static final String FILE_NAME = "last-crash.txt";

    private CrashReporter() { }

    public static void install(final Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                record(context.getApplicationContext(), thread, error);
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }

    public static void record(Context context, Throwable error) {
        record(context.getApplicationContext(), Thread.currentThread(), error);
    }

    public static String read(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) return "";
        try {
            byte[] data = IoUtils.readFile(file, 512 * 1024);
            return new String(data, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "Could not read the previous crash report";
        }
    }

    public static boolean hasReport(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        return file.isFile() && file.length() > 0;
    }

    public static void clear(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (file.exists()) file.delete();
    }

    /** Moves an old crash into the detailed event log, then removes the home-screen artifact. */
    public static void archivePreviousCrash(Context context) {
        String report = read(context);
        if (report.length() == 0) return;
        EventLogger.error("PreviousCrash", "Recovered crash from the prior app run:\n" + report);
        clear(context);
    }

    public static void record(Context context, Thread thread, Throwable error) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        try {
            StringWriter stack = new StringWriter();
            PrintWriter stackWriter = new PrintWriter(stack);
            error.printStackTrace(stackWriter);
            stackWriter.flush();

            StringBuilder report = new StringBuilder();
            report.append("Time: ")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()))
                    .append('\n')
                    .append("Thread: ").append(thread == null ? "unknown" : thread.getName()).append('\n')
                    .append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    .append("Android: ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n")
                    .append(stack.toString());

            FileOutputStream output = new FileOutputStream(file, false);
            try {
                output.write(report.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally {
                output.close();
            }
        } catch (Exception ignored) { }
        EventLogger.error("Crash", "Uncaught exception", error);
    }
}
