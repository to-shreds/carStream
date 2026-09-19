package com.carstream.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class IoUtils {
    private IoUtils() { }

    static byte[] readAll(InputStream input, int maximumBytes) throws IOException {
        if (input == null) return new byte[0];
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int count;
            while ((count = source.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) throw new IOException("Response exceeded " + maximumBytes + " bytes");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    static String readUtf8(InputStream input, int maximumBytes) throws IOException {
        return new String(readAll(input, maximumBytes), StandardCharsets.UTF_8);
    }

    static byte[] readFile(File file, int maximumBytes) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return readAll(input, maximumBytes);
        }
    }

    static void writeAtomically(File target, byte[] data) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        File temporary = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(data);
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IOException("Could not replace " + target);
        if (!temporary.renameTo(target)) throw new IOException("Could not move " + temporary + " to " + target);
    }

    static String sha256(byte[] data) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest(data);
            StringBuilder text = new StringBuilder(value.length * 2);
            for (byte item : value) text.append(String.format(Locale.US, "%02x", item & 0xff));
            return text.toString();
        } catch (Exception e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
