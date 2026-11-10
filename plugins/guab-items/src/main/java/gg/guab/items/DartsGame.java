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
    private final boolean beginner;
    private final boolean practice;

    private int score;
    private int turnStartScore;
    private int dartsLeft;
    private int turnTotal;
    private int dartsThisLeg;
    private int legsWon;
    private int legNumber = 1;
    private boolean matchOver;
    private DartsGeometry.Hit lastHit;

    public DartsGame(UUID playerId, String playerName, int mode, int legsToWin, boolean beginner) {
        this(playerId, playerName, mode, legsToWin, beginner, false);
    }

    public DartsGame(UUID playerId, String playerName, int mode, int legsToWin, boolean beginner,
                     boolean practice) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.mode = mode;
        this.legsToWin = legsToWin;
        this.beginner = beginner;
        this.practice = practice;
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

        if (after == 0 && (beginner || hit.doubleOut())) {
            int checkout = before;
            int legDarts = dartsThisLeg;
            // Capture the visit total before startNextLeg() resets it. Without
            // this a 180 checkout in a multi-leg match would not count as a 180.
            int visitTotal = turnTotal;
            boolean oneEighty = visitTotal == 180;
            legsWon++;
            boolean matchWon = legsWon >= legsToWin;
            if (matchWon) {
                matchOver = true;
            } else {
                startNextLeg();
            }
            // A 180 checkout is still a 180, even though the visit ends on the
            // winning arrow rather than a normal turn end.
            return new Result(Kind.CHECKOUT, hit, before, 0, checkout, legDarts,
                    legsWon, matchWon, visitTotal, oneEighty, false);
        }

        if (after < 0 || after == 1 || after == 0) {
            if (beginner) {
                // Beginner assist: an impossible finish is simply ignored instead of
                // wiping the whole visit. The score stays where it was.
                turnTotal -= hit.score();
                if (dartsLeft == 0) {
                    startNextTurn();
                    return new Result(Kind.TURN_END, hit, before, before, 0, dartsThisLeg,
                            legsWon, false, turnTotal, false, false);
                }
                return new Result(Kind.THROW, hit, before, before, 0, dartsThisLeg,
                        legsWon, false, turnTotal, false, false);
            }
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

    /** Resets this player for the next leg of a shared match. */
    public void resetLeg() {
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

    /** Practice legs never touch career records, rewards or announcements. */
    public boolean practice() {
        return practice;
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
