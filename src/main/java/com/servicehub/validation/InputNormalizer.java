package com.servicehub.validation;

import java.text.Normalizer;
import java.util.Locale;

public final class InputNormalizer {
    private InputNormalizer() { }
    public static String name(String value) {
        return value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFC)
                .strip().replaceAll("[\\p{Z}\\s]+", " ");
    }
    public static String email(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }
    public static String text(String value) {
        return value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    }
}
