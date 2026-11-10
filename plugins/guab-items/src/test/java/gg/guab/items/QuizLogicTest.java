package gg.guab.items;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuizLogicTest {

    @Test
    void answersKeepTheirConfiguredDisplayCasing() {
        QuizQuestion question = QuizQuestion.parse(
                "What is the capital of Australia? | Canberra / ACT");

        assertEquals("Canberra", question.displayAnswer());
        assertTrue(question.matches("Canberra"));
        assertTrue(question.matches("canberra"));
    }

    @Test
    void punctuationAndArticlesAreIgnoredForMatching() {
        assertTrue(QuizMatch.matches(List.of("Canberra"), "Canberra!"));
        assertTrue(QuizMatch.matches(List.of("The Queen Vic"), "Queen Vic"));
        assertTrue(QuizMatch.matches(List.of("Queen Vic"), "the queen vic!"));
        assertTrue(QuizMatch.matches(List.of("fifty six"), "Fifty-six"));
        assertTrue(QuizMatch.matches(List.of("India Pale Ale"), "  india   pale ale "));
        assertFalse(QuizMatch.matches(List.of("Canberra"), "Sydney"));
        assertFalse(QuizMatch.matches(List.of("Canberra"), "   "));
    }

    @Test
    void malformedQuestionsAreRejected() {
        assertNull(QuizQuestion.parse("No answer separator"));
        assertNull(QuizQuestion.parse("Question? |   "));
        assertEquals("Guinness", QuizQuestion.parse("Black stuff? | Guinness").displayAnswer());
    }
}
