package gg.guab.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tiny token cache used by {@link HudManager}. A refresh builds the token map once,
 * then reuses it for every sidebar and tab line instead of recalculating the
 * leaderboard and area for each line.
 */
final class HudTokens {

    private final Map<String, String> values = new LinkedHashMap<>();

    HudTokens put(String token, String value) {
        if (token != null && !token.isEmpty()) {
            values.put(token.startsWith("%") ? token : "%" + token + "%",
                    value == null ? "" : value);
        }
        return this;
    }

    String apply(String raw) {
        if (raw == null || raw.isEmpty() || values.isEmpty()) {
            return raw == null ? "" : raw;
        }
        String out = raw;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out = out.replace(entry.getKey(), entry.getValue());
        }
        return out;
    }

    int size() {
        return values.size();
    }
}
