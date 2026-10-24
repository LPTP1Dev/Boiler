package net.somewhatcity.boiler.api.util;

/** TV operations are deliberately separate from keyboard keys such as Escape and Home. */
public enum RemoteAction {
    HOME, BACK, SETTINGS, OK, TEXT_INPUT,
    PLAY_PAUSE, STOP, SEEK_BACKWARD, SEEK_FORWARD,
    VOLUME_DOWN, VOLUME_UP, MUTE
}
