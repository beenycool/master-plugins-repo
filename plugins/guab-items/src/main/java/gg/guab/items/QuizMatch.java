package gg.guab.items;

import java.util.List;
import java.util.Locale;

/**
 * Lenient answer matching for the pub quiz. The configured answer keeps its
 * display casing; matching ignores punctuation, extra spaces and a leading
 * article, so "Canberra!" and "the Canberra" both count.
 */
final class QuizMatch {

    private QuizMatch() {
    }

    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        String value = text.toLowerCase(Locale.ROOT).trim();
        // Turn punctuation into spaces so "Canberra!" and "Can't" stay word-like.
        value = value.replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim();
        value = value.replaceAll("^(the|a|an)\\s+", "");
        return value;
    }

    static boolean matches(List<String> accepted, String message) {
        String candidate = normalise(message);
        if (candidate.isEmpty()) {
            return false;
        }
        for (String answer : accepted) {
            if (normalise(answer).equals(candidate)) {
                return true;
            }
        }
        return false;
    }
}
