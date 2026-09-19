package com.carstream.app;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small persistent logger for real-device troubleshooting. It deliberately
 * redacts passwords, tokens, API keys, pairing codes, and full magnet links.
 */
public final class EventLogger {
    private static final Object LOCK = new Object();
    private static final String CURRENT_NAME = "carstream-events.log";
    private static final String PREVIOUS_NAME = "carstream-events-previous.log";
    private static final int ROTATE_BYTES = 768 * 1024;
    private static volatile File currentFile;
    private static volatile File previousFile;

    private EventLogger() { }

    public static void initialize(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            File directory = context.getApplicationContext().getFilesDir();
            currentFile = new File(directory, CURRENT_NAME);
            previousFile = new File(directory, PREVIOUS_NAME);
        }
        info("App", "Process started on " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
    }

    public static void debug(Context context, String category, String message) { initializeIfNeeded(context); debug(category, message); }
    public static void info(Context context, String category, String message) { initializeIfNeeded(context); info(category, message); }
    public static void warn(Context context, String category, String message) { initializeIfNeeded(context); warn(category, message); }
    public static void warn(Context context, String category, String message, Throwable error) { initializeIfNeeded(context); warn(category, message, error); }
    public static void error(Context context, String category, String message) { initializeIfNeeded(context); error(category, message); }
    public static void error(Context context, String category, String message, Throwable error) { initializeIfNeeded(context); error(category, message, error); }

    public static void debug(String category, String message) { write("DEBUG", category, message, null); }
    public static void info(String category, String message) { write("INFO", category, message, null); }
    public static void warn(String category, String message) { write("WARN", category, message, null); }
    public static void warn(String category, String message, Throwable error) { write("WARN", category, message, error); }
    public static void error(String category, String message) { write("ERROR", category, message, null); }
    public static void error(String category, String message, Throwable error) { write("ERROR", category, message, error); }

    public static String readRecent(Context context, int maximumBytes) {
        initializeIfNeeded(context);
        synchronized (LOCK) {
            int limit = Math.max(4 * 1024, maximumBytes);
            String older = tail(previousFile, limit / 3);
            String newer = tail(currentFile, limit - older.getBytes(StandardCharsets.UTF_8).length);
            if (older.length() == 0) return newer;
            if (newer.length() == 0) return older;
            return older + "\n--- current log ---\n" + newer;
        }
    }


    public static String read(Context context) {
        return readRecent(context, 1024 * 1024);
    }

    public static String deviceHeader() {
        return "CarStream " + BuildInfo.VERSION_NAME + "\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n";
    }

    public static String buildReport(Context context, String liveSummary) {
        StringBuilder report = new StringBuilder();
        report.append("CarStream ").append(BuildInfo.VERSION_NAME).append(" diagnostic report\n")
                .append("Generated: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()))
                .append("\nDevice: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("\nAndroid: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        if (liveSummary != null && liveSummary.trim().length() > 0) {
            report.append("\nLIVE STATE\n----------\n").append(redact(liveSummary.trim())).append('\n');
        }
        String events = readRecent(context, 1024 * 1024);
        report.append("\nEVENT LOG\n---------\n")
                .append(events.length() == 0 ? "No events recorded.\n" : events);
        return report.toString();
    }

    public static void clear(Context context) {
        initializeIfNeeded(context);
        synchronized (LOCK) {
            if (currentFile != null && currentFile.exists()) currentFile.delete();
            if (previousFile != null && previousFile.exists()) previousFile.delete();
        }
        info("Diagnostics", "Event log cleared by user");
    }

    private static void write(String level, String category, String message, Throwable error) {
        File target = currentFile;
        if (target == null) return;
        synchronized (LOCK) {
            try {
                rotateIfNeeded();
                StringBuilder line = new StringBuilder();
                line.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date()))
                        .append(' ').append(level)
                        .append(" [").append(cleanCategory(category)).append("]")
                        .append(" [").append(Thread.currentThread().getName()).append("] ")
                        .append(redact(message == null ? "" : message));
                if (error != null) {
                    StringWriter stack = new StringWriter();
                    PrintWriter writer = new PrintWriter(stack);
                    error.printStackTrace(writer);
                    writer.flush();
                    line.append('\n').append(redact(stack.toString()));
                }
                line.append('\n');
                FileOutputStream output = new FileOutputStream(currentFile, true);
                try {
                    output.write(line.toString().getBytes(StandardCharsets.UTF_8));
                    output.flush();
                } finally {
                    output.close();
                }
            } catch (Exception ignored) { }
        }
    }

    private static void rotateIfNeeded() {
        if (currentFile == null || !currentFile.isFile() || currentFile.length() < ROTATE_BYTES) return;
        if (previousFile != null && previousFile.exists()) previousFile.delete();
        if (previousFile != null) currentFile.renameTo(previousFile);
    }

    private static String tail(File file, int maximumBytes) {
        if (file == null || !file.isFile() || maximumBytes <= 0) return "";
        RandomAccessFile input = null;
        try {
            input = new RandomAccessFile(file, "r");
            long length = input.length();
            int count = (int) Math.min((long) maximumBytes, length);
            input.seek(Math.max(0L, length - count));
            byte[] data = new byte[count];
            input.readFully(data);
            String value = new String(data, StandardCharsets.UTF_8);
            int firstLine = value.indexOf('\n');
            if (length > count && firstLine >= 0) value = value.substring(firstLine + 1);
            return value.trim();
        } catch (Exception ignored) {
            return "Could not read diagnostic log";
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
        }
    }

    private static void initializeIfNeeded(Context context) {
        if (currentFile == null && context != null) initialize(context);
    }

    private static String cleanCategory(String value) {
        if (value == null || value.trim().length() == 0) return "General";
        String clean = value.trim().replace('\n', ' ').replace('\r', ' ');
        return clean.length() > 40 ? clean.substring(0, 40) : clean;
    }

    public static String redact(String value) {
        if (value == null || value.length() == 0) return "";
        String clean = value;
        clean = clean.replaceAll("(?i)(authorization\\s*[:=]\\s*)(?:bearer\\s+)?[^\\s]+", "$1[redacted]");
        clean = clean.replaceAll("(?i)([?&](?:token|api_key|apikey)=)[^&\\s]+", "$1[redacted]");
        clean = clean.replaceAll("(?i)((?:api[ _-]?key|password|pairing[ _-]?code|access[ _-]?token|token)\\s*[:=]\\s*)[^&\\s]+", "$1[redacted]");
        clean = clean.replaceAll("(?i)magnet:\\?[^\\s]+", "magnet:[redacted]");
        return clean;
    }
}
