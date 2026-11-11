package gg.guab.core;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired after a player's ledger balance changes. The delta is the amount the
 * balance actually moved, so clamped writes report the real change.
 */
public final class GuabPointsChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final long before;
    private final long after;

    public GuabPointsChangeEvent(UUID player, long before, long after) {
        this.player = player;
        this.before = before;
        this.after = after;
    }

    public UUID player() {
        return player;
    }

    public long before() {
        return before;
    }

    public long after() {
        return after;
    }

    public long delta() {
        return after - before;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
