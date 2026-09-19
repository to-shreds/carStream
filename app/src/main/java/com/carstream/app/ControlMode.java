package com.carstream.app;

public enum ControlMode {
    FREE("Free", "Child can choose and control playback"),
    GUIDED("Guided", "Parent chooses the title; child keeps playback controls"),
    LOCKED("Locked", "Parent controls both title and playback");

    public final String label;
    public final String description;

    ControlMode(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public static ControlMode fromWire(String value) {
        if (value == null) return FREE;
        try { return valueOf(value); }
        catch (IllegalArgumentException ignored) { return FREE; }
    }

    @Override public String toString() { return label; }
}
