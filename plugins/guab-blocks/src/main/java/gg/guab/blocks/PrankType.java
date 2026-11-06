package gg.guab.blocks;

import java.util.Locale;

public enum PrankType {
    FAKE,
    POTION,
    SWAP;

    public static PrankType parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
