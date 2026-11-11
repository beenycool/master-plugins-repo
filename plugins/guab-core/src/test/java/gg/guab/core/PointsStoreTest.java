package gg.guab.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointsStoreTest {

    private static PointsStore store() {
        return new PointsStore(Path.of("build", "tmp", "points-test-" + UUID.randomUUID() + ".yml"));
    }

    @Test
    void negativeDeltaForUnknownPlayerClampsToZero() {
        PointsStore store = store();
        UUID player = UUID.randomUUID();

        assertEquals(0L, store.add(player, -5));
        assertEquals(0L, store.get(player));
    }

    @Test
    void positiveDeltaForUnknownPlayerInsertsAmount() {
        PointsStore store = store();
        UUID player = UUID.randomUUID();

        assertEquals(25L, store.add(player, 25));
        assertEquals(25L, store.get(player));
    }

    @Test
    void addBelowZeroClampsToZero() {
        PointsStore store = store();
        UUID player = UUID.randomUUID();
        store.set(player, 10);

        assertEquals(0L, store.add(player, -15));
        assertEquals(0L, store.get(player));
    }

    @Test
    void setNegativesClampToZero() {
        PointsStore store = store();
        UUID player = UUID.randomUUID();

        store.set(player, -1);
        assertEquals(0L, store.get(player));
    }

    @Test
    void takeFailsWithoutChangingBalance() {
        PointsStore store = store();
        UUID player = UUID.randomUUID();
        store.set(player, 5);

        assertFalse(store.take(player, 6));
        assertEquals(5L, store.get(player));
    }

    @Test
    void takeIsAtomicUnderContention() throws InterruptedException {
        PointsStore store = store();
        UUID player = UUID.randomUUID();
        store.set(player, 10);

        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    if (store.take(player, 1)) {
                        successes.incrementAndGet();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(10, successes.get());
        assertEquals(0L, store.get(player));
    }

    @Test
    void keepsBalancesBeyondTheIntegerRange(@TempDir Path temp) {
        PointsStore store = new PointsStore(temp.resolve("points.yml"));
        UUID player = UUID.randomUUID();

        store.set(player, 4_294_967_297L);
        assertEquals(4_294_967_297L, store.get(player));
        assertTrue(store.take(player, 3_000_000_000L));
        assertEquals(1_294_967_297L, store.get(player));

        store.save();
        PointsStore reloaded = new PointsStore(temp.resolve("points.yml"));
        reloaded.load();
        assertEquals(1_294_967_297L, reloaded.get(player));
    }

    @Test
    void maxBalanceDoesNotWrap() {
        PointsStore store = store();
        UUID player = UUID.randomUUID();

        store.set(player, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, store.add(player, 1));
        assertEquals(Long.MAX_VALUE, store.get(player));
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir Path temp) {
        Path file = temp.resolve("points.yml");
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        PointsStore original = new PointsStore(file);
        original.set(alice, 1_234);
        original.set(bob, 56);
        original.save();

        PointsStore reloaded = new PointsStore(file);
        reloaded.load();

        assertFalse(reloaded.loadFailed());
        assertEquals(1_234L, reloaded.get(alice));
        assertEquals(56L, reloaded.get(bob));
    }

    @Test
    void topIsOrderedHighToLowAndSkipsEmptyAccounts(@TempDir Path temp) {
        PointsStore store = new PointsStore(temp.resolve("points.yml"));
        UUID low = UUID.randomUUID();
        UUID high = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        store.set(low, 5);
        store.set(high, 50);
        store.set(middle, 20);
        store.set(empty, 0);

        List<Map.Entry<UUID, Long>> top = store.top(10);

        assertEquals(3, top.size());
        assertEquals(List.of(50L, 20L, 5L), top.stream().map(Map.Entry::getValue).toList());
        assertEquals(high, top.getFirst().getKey());
        assertTrue(top.stream().noneMatch(entry -> entry.getKey().equals(empty)));
        assertEquals(1, store.rank(high));
        assertEquals(3, store.rank(low));
        assertEquals(0, store.rank(empty));
    }

    @Test
    void transferMovesWholeAmountOrNothing(@TempDir Path temp) {
        PointsStore store = new PointsStore(temp.resolve("points.yml"));
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        store.set(alice, 100);

        assertTrue(store.transfer(alice, bob, 40));
        assertEquals(60L, store.get(alice));
        assertEquals(40L, store.get(bob));

        assertFalse(store.transfer(alice, bob, 61));
        assertEquals(60L, store.get(alice));
        assertEquals(40L, store.get(bob));

        assertFalse(store.transfer(alice, alice, 10));
        assertEquals(60L, store.get(alice));
    }

    @Test
    void unreadableLedgerIsPreservedAndNotOverwritten(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("points.yml");
        String corrupt = "players: [this is not valid";
        Files.writeString(file, corrupt);

        PointsStore store = new PointsStore(file);
        store.load();

        assertTrue(store.loadFailed());
        assertTrue(Files.isRegularFile(temp.resolve("points.yml.bak")));
        assertThrows(IllegalStateException.class, store::save);
        assertEquals(corrupt, Files.readString(file),
                "the original file must not be replaced by an empty ledger");
    }
}
