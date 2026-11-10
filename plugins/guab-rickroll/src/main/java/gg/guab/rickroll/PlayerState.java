package gg.guab.rickroll;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

/**
 * The complete pre-show state we mutate: game mode, back location, flight, speeds, client time and (when available
 * in memory) the previous scoreboard and tab-list name.
 *
 * <p>The PDC marker is written from {@link #serialize()} before any mutation, so a crash or disconnect can restore
 * the player from a partially applied show. The scoreboard and tab name are intentionally memory-only: they reset on
 * rejoin anyway.</p>
 */
final class PlayerState {

    private static final String PREFIX = "v1";

    private final Location back;
    private final String backWorldName;
    private final GameMode mode;
    private final boolean allowFlight;
    private final boolean flying;
    private final float flySpeed;
    private final float walkSpeed;
    private final long playerTime;
    private final boolean playerTimeRelative;
    private final Scoreboard scoreboard;
    private final Component tabName;

    private PlayerState(
            Location back,
            String backWorldName,
            GameMode mode,
            boolean allowFlight,
            boolean flying,
            float flySpeed,
            float walkSpeed,
            long playerTime,
            boolean playerTimeRelative,
            Scoreboard scoreboard,
            Component tabName) {
        this.back = back;
        this.backWorldName = backWorldName;
        this.mode = mode;
        this.allowFlight = allowFlight;
        this.flying = flying;
        this.flySpeed = flySpeed;
        this.walkSpeed = walkSpeed;
        this.playerTime = playerTime;
        this.playerTimeRelative = playerTimeRelative;
        this.scoreboard = scoreboard;
        this.tabName = tabName;
    }

    static PlayerState capture(Player player) {
        Location back = player.getLocation().clone();
        boolean relative = player.isPlayerTimeRelative();
        long time = relative ? player.getPlayerTimeOffset() : player.getPlayerTime();
        Scoreboard scoreboard;
        Component tabName;
        try {
            scoreboard = player.getScoreboard();
        } catch (Throwable throwable) {
            scoreboard = null;
        }
        try {
            tabName = player.playerListName();
        } catch (Throwable throwable) {
            tabName = null;
        }
        return new PlayerState(
                back,
                back.getWorld() == null ? "" : back.getWorld().getName(),
                player.getGameMode(),
                player.getAllowFlight(),
                player.isFlying(),
                player.getFlySpeed(),
                player.getWalkSpeed(),
                time,
                relative,
                scoreboard,
                tabName);
    }

    /** Serializes only what can be restored after a restart; safe for the persistent data container. */
    String serialize() {
        String world = backWorldName == null ? "" : backWorldName;
        double x = back == null ? 0.0 : back.getX();
        double y = back == null ? 0.0 : back.getY();
        double z = back == null ? 0.0 : back.getZ();
        float yaw = back == null ? 0.0f : back.getYaw();
        float pitch = back == null ? 0.0f : back.getPitch();
        return String.join("|",
                PREFIX,
                mode.name(),
                world,
                Double.toString(x),
                Double.toString(y),
                Double.toString(z),
                Float.toString(yaw),
                Float.toString(pitch),
                Boolean.toString(allowFlight),
                Boolean.toString(flying),
                Float.toString(flySpeed),
                Float.toString(walkSpeed),
                Long.toString(playerTime),
                Boolean.toString(playerTimeRelative));
    }

    static PlayerState parse(String value) {
        if (value == null || !value.startsWith(PREFIX + "|")) {
            return null;
        }
        String[] parts = value.split("\\|", -1);
        if (parts.length < 14) {
            return null;
        }
        try {
            GameMode mode = GameMode.valueOf(parts[1]);
            String worldName = parts[2];
            World world = worldName.isBlank() ? null : Bukkit.getWorld(worldName);
            Location back = world == null
                    ? null
                    : new Location(
                            world,
                            Double.parseDouble(parts[3]),
                            Double.parseDouble(parts[4]),
                            Double.parseDouble(parts[5]),
                            Float.parseFloat(parts[6]),
                            Float.parseFloat(parts[7]));
            return new PlayerState(
                    back,
                    worldName,
                    mode,
                    Boolean.parseBoolean(parts[8]),
                    Boolean.parseBoolean(parts[9]),
                    Float.parseFloat(parts[10]),
                    Float.parseFloat(parts[11]),
                    Long.parseLong(parts[12]),
                    Boolean.parseBoolean(parts[13]),
                    null,
                    null);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Restores everything. When {@code teleport} is false the player stays where they are (used on quit). */
    void apply(Player player, boolean teleport, GuabRickrollPlugin plugin) {
        if (!player.isOnline()) {
            return;
        }
        try {
            player.setGameMode(mode);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not restore game mode for " + player.getName() + ": "
                    + throwable.getClass().getSimpleName());
        }
        try {
            player.setAllowFlight(allowFlight);
            player.setFlySpeed(flySpeed);
            player.setWalkSpeed(walkSpeed);
            if (allowFlight) {
                player.setFlying(flying);
            }
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Could not restore flight/speeds for " + player.getName() + ": "
                    + exception.getMessage());
        }
        try {
            if (playerTimeRelative) {
                player.setPlayerTime(playerTime, true);
            } else {
                player.setPlayerTime(playerTime, false);
            }
        } catch (IllegalArgumentException exception) {
            player.resetPlayerTime();
        }
        if (scoreboard != null) {
            try {
                player.setScoreboard(scoreboard);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                plugin.getLogger().warning("Could not restore the scoreboard for " + player.getName() + ": "
                        + exception.getMessage());
            }
        }
        if (tabName != null) {
            try {
                player.playerListName(tabName);
            } catch (Throwable ignored) {
                // Tab-list names are cosmetic; do not fail the whole restore.
            }
        }
        if (teleport) {
            Location destination = back;
            if (destination == null
                    || destination.getWorld() == null
                    || Bukkit.getWorld(destination.getWorld().getName()) == null) {
                World fallback = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
                if (fallback != null) {
                    destination = fallback.getSpawnLocation();
                    plugin.getLogger().warning("Back world '" + backWorldName + "' for " + player.getName()
                            + " is not loaded; sending them to " + fallback.getName() + " spawn instead.");
                }
            }
            if (destination != null) {
                try {
                    player.teleport(destination);
                } catch (Throwable throwable) {
                    plugin.getLogger().warning("Could not teleport " + player.getName()
                            + " back after the rickroll: " + throwable.getClass().getSimpleName());
                }
            }
        }
    }
}
