package gg.guab.items;

import java.util.Collection;

/**
 * Pure capacity and oche-slot arithmetic for the darts room. Kept separate from
 * the manager so the "room is full" rules can be unit tested without a server.
 */
final class DartsSlots {

    /** The room has four oches; no more than four players may hold a game. */
    static final int MAX_PLAYERS = 4;
    /** A 1v1 always takes exactly two of those places. */
    static final int MATCH_PLAYERS = 2;

    private DartsSlots() {
    }

    static boolean canStartSingle(int currentGames) {
        return currentGames >= 0 && currentGames + 1 <= MAX_PLAYERS;
    }

    static boolean canStartMatch(int currentGames) {
        return currentGames >= 0 && currentGames + MATCH_PLAYERS <= MAX_PLAYERS;
    }

    /** First unused oche, or oche 0 when every slot is taken. */
    static int nextFreeSlot(Collection<Integer> used, int slotCount) {
        for (int slot = 0; slot < slotCount; slot++) {
            if (!used.contains(slot)) {
                return slot;
            }
        }
        return 0;
    }
}
