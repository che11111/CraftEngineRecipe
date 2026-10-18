package dev.leon.cer.gui;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Sounds {
    private static final String NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?";
    private static final Pattern PARAMETERS = Pattern.compile(
            "^(.*?)-\\s*(" + NUMBER + ")\\s*(?:-\\s*(" + NUMBER + ")\\s*)?$");

    private Sounds() {
    }

    public static void play(Player player, String spec) {
        play(player, spec, 0.7f);
    }

    public static void play(Player player, String spec, float defaultVolume) {
        playSpec(player, parse(spec, defaultVolume));
    }

    /**
     * 解析按钮 action 的声音规格：<声音>[-<音量>-<音调>]。
     */
    public static void playAction(Player player, String spec) {
        play(player, spec, 1.0f);
    }

    private static void playSpec(Player player, SoundSpec spec) {
        if (spec != null) play(player, spec.name(), spec.volume(), spec.pitch());
    }

    /** Shared by config sounds and button actions; only numeric suffixes are parameters. */
    static SoundSpec parse(String spec, float defaultVolume) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String name = spec.trim();
        float volume = defaultVolume;
        float pitch = 1.0f;
        Matcher match = PARAMETERS.matcher(name);
        if (match.matches()) {
            name = match.group(1).trim();
            volume = Math.max(0.0f, parseFloat(match.group(2), defaultVolume));
            if (match.group(3) != null) pitch = Math.max(0.0f, parseFloat(match.group(3), 1.0f));
        }
        return new SoundSpec(name, volume, pitch);
    }

    record SoundSpec(String name, float volume, float pitch) {}

    public static void play(Player player, String soundName, float volume, float pitch) {
        if (player == null || soundName == null || soundName.isBlank()) {
            return;
        }
        Sound sound = resolve(soundName.trim());
        if (sound != null) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    private static Sound resolve(String name) {
        try {
            return Sound.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
        }
        try {
            NamespacedKey key = name.contains(":")
                    ? NamespacedKey.fromString(name)
                    : NamespacedKey.minecraft(name);
            return Registry.SOUNDS.get(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float parseFloat(String s, float def) {
        try {
            float value = Float.parseFloat(s.trim());
            return Float.isFinite(value) ? value : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
