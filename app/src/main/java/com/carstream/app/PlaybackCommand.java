package com.carstream.app;

import org.json.JSONException;
import org.json.JSONObject;

final class PlaybackCommand {
    private PlaybackCommand() { }

    static JSONObject simple(String action) throws JSONException {
        return new JSONObject().put("action", action);
    }

    static JSONObject seekRelative(double seconds) throws JSONException {
        return simple("seekRelative").put("seconds", seconds);
    }

    static JSONObject seekTo(double seconds) throws JSONException {
        return simple("seekTo").put("seconds", seconds);
    }

    static JSONObject setMedia(MediaItem item, double startAt, boolean autoplay) throws JSONException {
        return simple("setMedia")
                .put("media", item.toJson())
                .put("startAt", startAt)
                .put("autoplay", autoplay);
    }

    static JSONObject controlMode(ControlMode mode) throws JSONException {
        return simple("controlMode").put("mode", mode.name());
    }
}
