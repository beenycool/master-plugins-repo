package gg.guab.items;

import java.util.ArrayList;
import java.util.List;

/**
 * One parsed quiz question. Answers are stored exactly as configured so the
 * broadcast can show the proper casing, while {@link QuizMatch} does the
 * forgiving comparison.
 */
record QuizQuestion(String question, List<String> answers) {

    static QuizQuestion parse(String raw) {
        if (raw == null) {
            return null;
        }
        int split = raw.indexOf('|');
        if (split < 1) {
            return null;
        }
        String question = raw.substring(0, split).trim();
        List<String> answers = new ArrayList<>();
        for (String answer : raw.substring(split + 1).split("/")) {
            String cleaned = answer.trim();
            if (!cleaned.isEmpty()) {
                answers.add(cleaned);
            }
        }
        if (question.isEmpty() || answers.isEmpty()) {
            return null;
        }
        return new QuizQuestion(question, List.copyOf(answers));
    }

    boolean matches(String message) {
        return QuizMatch.matches(answers, message);
    }

    String displayAnswer() {
        return answers.get(0);
    }
}
