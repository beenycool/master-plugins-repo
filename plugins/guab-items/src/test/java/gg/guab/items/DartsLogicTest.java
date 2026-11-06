package gg.guab.items;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DartsLogicTest {

    @Test
    void legCountsDownAndBustsRestoreTheTurnStart() {
        DartsGame game = new DartsGame(UUID.randomUUID(), "Tester", 101, 1);

        assertEquals(DartsGame.Kind.THROW, game.throwDart(hit(60, 3, "T20", false)).kind());
        assertEquals(41, game.score());

        DartsGame.Result bust = game.throwDart(hit(42, 3, "T14", false));
        assertEquals(DartsGame.Kind.BUST, bust.kind());
        assertEquals(101, game.score());
        assertEquals(3, game.dartsLeft());
        assertTrue(bust.bust());
    }

    @Test
    void onlyADoubleCanFinish() {
        DartsGame game = new DartsGame(UUID.randomUUID(), "Tester", 101, 1);
        game.throwDart(hit(60, 3, "T20", false));
        game.throwDart(hit(1, 1, "S1", false));

        DartsGame.Result singleFinish = game.throwDart(hit(40, 1, "S20", false));
        assertEquals(DartsGame.Kind.BUST, singleFinish.kind());
        assertEquals(101, game.score());

        DartsGame checkout = new DartsGame(UUID.randomUUID(), "Tester", 101, 1);
        checkout.throwDart(hit(60, 3, "T20", false));
        checkout.throwDart(hit(1, 1, "S1", false));
        DartsGame.Result doubleFinish = checkout.throwDart(hit(40, 2, "D20", true));
        assertEquals(DartsGame.Kind.CHECKOUT, doubleFinish.kind());
        assertEquals(3, doubleFinish.legDarts());
        assertEquals(40, doubleFinish.checkout());
        assertTrue(doubleFinish.matchWon());
    }

    @Test
    void threeTrebleTwentiesAreOneHundredAndEighty() {
        DartsGame game = new DartsGame(UUID.randomUUID(), "Tester", 501, 1);
        game.throwDart(hit(60, 3, "T20", false));
        game.throwDart(hit(60, 3, "T20", false));
        DartsGame.Result result = game.throwDart(hit(60, 3, "T20", false));

        assertEquals(DartsGame.Kind.TURN_END, result.kind());
        assertTrue(result.oneEighty());
        assertEquals(321, game.score());
        assertEquals(3, game.dartsLeft());
    }

    @Test
    void checkoutHintsCoverTheClassicFinishes() {
        assertEquals("T20 T20 BULL", DartsCheckout.hint(170, 3));
        assertEquals("T17 BULL", DartsCheckout.hint(101, 3));
        assertEquals("D20", DartsCheckout.hint(40, 3));
        assertEquals("D1", DartsCheckout.hint(2, 1));
        assertEquals("S1 D1", DartsCheckout.hint(3, 2));
        assertNull(DartsCheckout.hint(169, 3));
        assertNull(DartsCheckout.hint(1, 3));
    }

    @Test
    void geometryMapsTheBoard() {
        DartsConfig config = config();
        Location centre = new Location(null, 1.0, 0.5, 0.5);

        assertEquals(50, DartsGeometry.score(centre, BlockFace.EAST, 0.5, centre, config, 0).score());
        assertTrue(DartsGeometry.score(centre, BlockFace.EAST, 0.5,
                new Location(null, 1.0, centre.getY() + 0.3, centre.getZ()), config, 0)
                .label().equals("T20"));
        assertEquals(40, DartsGeometry.score(centre, BlockFace.EAST, 0.5,
                new Location(null, 1.0, centre.getY() + 0.4825, centre.getZ()), config, 0).score());
        assertEquals(0, DartsGeometry.score(centre, BlockFace.EAST, 0.5,
                new Location(null, 1.0, centre.getY() + 0.6, centre.getZ()), config, 0).score());
        assertTrue(DartsGeometry.score(centre, BlockFace.EAST, 0.5, centre, config, 0).doubleOut());
    }

    private static DartsGeometry.Hit hit(int score, int multiplier, String label, boolean doubleOut) {
        return new DartsGeometry.Hit(score / multiplier, multiplier, score, label, doubleOut, 0, 0);
    }

    private static DartsConfig config() {
        return new DartsConfig(true, "world", 0, 0, 0, BlockFace.EAST, 0.5, 350, 8, 501, 1, 10,
                0.008, 0.012, 0.25, 0.055, 0.115, 0.56, 0.64, 0.93, 1.0,
                true, 20, 60, 100, 9, 15, 21, 2, 0.1, 300);
    }
}
