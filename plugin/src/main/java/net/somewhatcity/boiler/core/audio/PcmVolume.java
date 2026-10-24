package net.somewhatcity.boiler.core.audio;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.RemoteAction;

/** Shared gain stage for signed mono PCM16 little endian, before voice-chat encoding. */
public final class PcmVolume {
    private PcmVolume() {}
    public static int volume(JsonObject settings) {
        return settings != null && settings.has("volume") ? Math.max(0, Math.min(100, settings.get("volume").getAsInt())) : 100;
    }
    public static boolean muted(JsonObject settings) { return settings != null && settings.has("muted") && settings.get("muted").getAsBoolean(); }

    public static byte[] scale(byte[] input, int volume, boolean muted) {
        if (!muted && volume >= 100) return input;
        byte[] output = new byte[input.length];
        if (muted || volume <= 0) return output;
        for (int offset = 0; offset + 1 < input.length; offset += 2) {
            short sample = (short) ((input[offset] & 255) | input[offset + 1] << 8);
            int value = sample * Math.min(100, volume) / 100;
            output[offset] = (byte) value;
            output[offset + 1] = (byte) (value >> 8);
        }
        return output;
    }

    public static boolean change(IBoilerDisplay display, RemoteAction action) {
        if (action != RemoteAction.VOLUME_UP && action != RemoteAction.VOLUME_DOWN && action != RemoteAction.MUTE) return false;
        JsonObject settings = display.settings().deepCopy();
        if (action == RemoteAction.MUTE) settings.addProperty("muted", !muted(settings));
        else settings.addProperty("volume", Math.max(0, Math.min(100, volume(settings) + (action == RemoteAction.VOLUME_UP ? 5 : -5))));
        display.settings(settings);
        return true;
    }
}
