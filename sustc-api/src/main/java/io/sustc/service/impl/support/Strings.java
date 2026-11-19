package io.sustc.service.impl.support;

/** String helpers for request validation. */
public final class Strings {

    private Strings() {}

    public static boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /** Returns {@code null} for {@code null} or empty input, the input otherwise. */
    public static String emptyToNull(String s) {
        return isNullOrEmpty(s) ? null : s;
    }
}
