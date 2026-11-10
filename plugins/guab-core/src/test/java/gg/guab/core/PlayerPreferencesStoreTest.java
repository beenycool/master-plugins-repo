package gg.guab.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerPreferencesStoreTest {

    @Test
    void defaultsArePlayerFriendly(@TempDir Path temp) {
        PlayerPreferencesStore store = new PlayerPreferencesStore(temp);
        UUID player = UUID.randomUUID();

        assertTrue(store.sidebar(player));
        assertTrue(store.tab(player));
        assertTrue(store.nametag(player));
        assertTrue(store.sounds(player));
        assertTrue(store.actionbar(player));
        assertTrue(store.titles(player));
        assertFalse(store.rickrollReducedMotion(player));
        assertFalse(store.rickrollOptOut(player));
    }

    @Test
    void roundTripsAcrossStoreInstances(@TempDir Path temp) {
        UUID player = UUID.randomUUID();
        PlayerPreferencesStore store = new PlayerPreferencesStore(temp);
        store.set(player, PlayerPreferencesStore.Toggle.SIDEBAR, false);
        store.set(player, PlayerPreferencesStore.Toggle.TAB, false);
        store.set(player, PlayerPreferencesStore.Toggle.RICKROLL_REDUCED_MOTION, true);

        assertTrue(Files.isRegularFile(temp.resolve(player + ".yml")));

        PlayerPreferencesStore reloaded = new PlayerPreferencesStore(temp);
        assertFalse(reloaded.sidebar(player));
        assertFalse(reloaded.tab(player));
        assertTrue(reloaded.rickrollReducedMotion(player));
        assertTrue(reloaded.nametag(player), "untouched defaults survive the round trip");
    }

    @Test
    void toggleFlipsAndPersists(@TempDir Path temp) {
        UUID player = UUID.randomUUID();
        PlayerPreferencesStore store = new PlayerPreferencesStore(temp);

        assertFalse(store.toggle(player, PlayerPreferencesStore.Toggle.SOUNDS));
        assertFalse(new PlayerPreferencesStore(temp).sounds(player));

        assertTrue(store.toggle(player, PlayerPreferencesStore.Toggle.SOUNDS));
        assertTrue(new PlayerPreferencesStore(temp).sounds(player));
    }

    @Test
    void snapshotsAreDefensiveCopies(@TempDir Path temp) {
        UUID player = UUID.randomUUID();
        PlayerPreferencesStore store = new PlayerPreferencesStore(temp);
        PlayerPreferencesStore.Preferences snapshot = store.snapshot(player);

        store.set(player, PlayerPreferencesStore.Toggle.ACTIONBAR, false);

        assertTrue(snapshot.get(PlayerPreferencesStore.Toggle.ACTIONBAR));
        assertFalse(store.actionbar(player));
    }
}
