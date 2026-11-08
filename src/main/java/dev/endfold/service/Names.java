package dev.endfold.service;

import java.util.regex.Pattern;

/** Validation for user-chosen EndSpace names. Names are always rendered as plain text (never parsed as MiniMessage). */
public final class Names {
    private static final Pattern ALLOWED = Pattern.compile("[\\p{L}\\p{N} _'\\-!&.,()]+");

    private Names() {}

    public static String normalise(String raw) {
        if (raw == null) return "";
        return raw.strip().replaceAll("\\s+", " ");
    }

    public static boolean valid(String name, int min, int max) {
        String n = normalise(name);
        int len = n.codePointCount(0, n.length());
        return len >= min && len <= max && ALLOWED.matcher(n).matches() && n.indexOf('<') < 0 && n.indexOf('§') < 0;
    }
}
