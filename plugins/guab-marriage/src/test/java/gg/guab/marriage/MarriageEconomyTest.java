package gg.guab.marriage;

import gg.guab.core.PointsService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarriageEconomyTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    void affordableWeddingTakesExactlyTheFee() {
        FakePoints points = new FakePoints(300L);

        assertTrue(MarriageEconomy.canAfford(points, PLAYER, 250L));
        assertTrue(MarriageEconomy.charge(points, PLAYER, 250L));
        assertEquals(50L, points.get(PLAYER));
    }

    @Test
    void unaffordableWeddingLeavesTheBalanceUntouched() {
        FakePoints points = new FakePoints(100L);

        assertFalse(MarriageEconomy.canAfford(points, PLAYER, 250L));
        assertFalse(MarriageEconomy.charge(points, PLAYER, 250L));
        assertEquals(100L, points.get(PLAYER));
    }

    @Test
    void zeroCostAndMissingLedgerAreFree() {
        FakePoints points = new FakePoints(0L);

        assertTrue(MarriageEconomy.charge(points, PLAYER, 0L));
        assertTrue(MarriageEconomy.charge(null, PLAYER, 250L));
        assertTrue(MarriageEconomy.canAfford(null, PLAYER, 250L));
        assertEquals(0L, points.get(PLAYER));
    }

    private static final class FakePoints implements PointsService {

        private long balance;

        private FakePoints(long balance) {
            this.balance = balance;
        }

        @Override
        public long get(UUID player) {
            return balance;
        }

        @Override
        public void set(UUID player, long amount) {
            balance = Math.max(0L, amount);
        }

        @Override
        public long add(UUID player, long amount) {
            balance = Math.max(0L, balance + amount);
            return balance;
        }

        @Override
        public boolean take(UUID player, long amount) {
            if (amount <= 0L) {
                return true;
            }
            if (balance < amount) {
                return false;
            }
            balance -= amount;
            return true;
        }

        @Override
        public List<Map.Entry<UUID, Long>> top(int limit) {
            return List.of(Map.entry(PLAYER, balance));
        }
    }
}
