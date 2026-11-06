package gg.guab.items;

import java.util.UUID;

/**
 * One player's darts match. A leg starts at 101, 301 or 501 and counts down in
 * visits of three darts. The final dart must be a double (bullseye 50 counts).
 */
public final class DartsGame {

    private final UUID playerId;
    private final String playerName;
    private final int mode;
    private final int legsToWin;

    private int score;
    private int turnStartScore;
    private int dartsLeft;
    private int turnTotal;
    private int dartsThisLeg;
    private int legsWon;
    private int legNumber = 1;
    private boolean matchOver;
    private DartsGeometry.Hit lastHit;

    public DartsGame(UUID playerId, String playerName, int mode, int legsToWin) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.mode = mode;
        this.legsToWin = legsToWin;
        this.score = mode;
        this.turnStartScore = mode;
        this.dartsLeft = 3;
    }

    public Result throwDart(DartsGeometry.Hit hit) {
        this.lastHit = hit;
        if (matchOver) {
            return new Result(Kind.THROW, hit, score, score, 0, dartsThisLeg,
                    legsWon, true, turnTotal, false, false);
        }

        dartsLeft--;
        turnTotal += hit.score();
        dartsThisLeg++;

        int before = score;
        int after = before - hit.score();

        if (after == 0 && hit.doubleOut()) {
            int checkout = before;
            int legDarts = dartsThisLeg;
            legsWon++;
            boolean matchWon = legsWon >= legsToWin;
            if (matchWon) {
                matchOver = true;
            } else {
                startNextLeg();
            }
            return new Result(Kind.CHECKOUT, hit, before, 0, checkout, legDarts,
                    legsWon, matchWon, turnTotal, false, false);
        }

        if (after < 0 || after == 1 || after == 0) {
            int restored = turnStartScore;
            score = restored;
            startNextTurn();
            return new Result(Kind.BUST, hit, before, restored, 0, dartsThisLeg,
                    legsWon, false, turnTotal, false, true);
        }

        score = after;
        if (dartsLeft == 0) {
            boolean oneEighty = turnTotal == 180;
            startNextTurn();
            return new Result(Kind.TURN_END, hit, before, after, 0, dartsThisLeg,
                    legsWon, false, turnTotal, oneEighty, false);
        }

        return new Result(Kind.THROW, hit, before, after, 0, dartsThisLeg,
                legsWon, false, turnTotal, false, false);
    }

    private void startNextTurn() {
        turnStartScore = score;
        dartsLeft = 3;
        turnTotal = 0;
    }

    private void startNextLeg() {
        legNumber++;
        score = mode;
        dartsThisLeg = 0;
        dartsLeft = 3;
        turnTotal = 0;
        turnStartScore = mode;
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public int mode() {
        return mode;
    }

    public int legsToWin() {
        return legsToWin;
    }

    public int score() {
        return score;
    }

    public int dartsLeft() {
        return dartsLeft;
    }

    public int dartsThisLeg() {
        return dartsThisLeg;
    }

    public int legsWon() {
        return legsWon;
    }

    public int legNumber() {
        return legNumber;
    }

    public boolean matchOver() {
        return matchOver;
    }

    public DartsGeometry.Hit lastHit() {
        return lastHit;
    }

    public String checkoutHint() {
        return DartsCheckout.hint(score, dartsLeft);
    }

    public enum Kind {
        THROW,
        TURN_END,
        BUST,
        CHECKOUT
    }

    public record Result(Kind kind, DartsGeometry.Hit hit, int scoreBefore, int scoreAfter,
                         int checkout, int legDarts, int legsWon, boolean matchWon,
                         int turnTotal, boolean oneEighty, boolean bust) {
    }
}
