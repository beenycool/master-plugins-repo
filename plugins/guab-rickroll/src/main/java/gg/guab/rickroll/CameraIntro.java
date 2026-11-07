package gg.guab.rickroll;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * A short, safe dolly along the cinema aisle. The player is already in spectator mode, so this only moves the client
 * camera; it never touches blocks and falls back to the normal seat if any point on the path is unsafe.
 */
final class CameraIntro {

    private final GuabRickrollPlugin plugin;
    private final Player player;
    private final Location seat;
    private final Location start;
    private final Location control;
    private final int durationTicks;

    private BukkitTask task;
    private int step;
    private boolean running;

    CameraIntro(GuabRickrollPlugin plugin, Player player, Location seat, int durationTicks) {
        this.plugin = plugin;
        this.player = player;
        this.seat = seat.clone();
        this.durationTicks = Math.max(1, durationTicks);

        // Small offsets around the existing seat, kept inside the central aisle (x block 10) to avoid the seats.
        this.start = seat.clone().add(0.0, 0.0, 2.2);
        this.start.setYaw(wrap(seat.getYaw() + 14.0f));
        this.start.setPitch(7.0f);
        this.control = seat.clone().add(0.0, 0.15, 1.1);
    }

    boolean start() {
        if (!safe(seat) || !safe(start) || !safe(control)) {
            return false;
        }
        running = true;
        player.teleport(start);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        return true;
    }

    void stop() {
        running = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (!running) {
            return;
        }
        if (!player.isOnline()) {
            stop();
            return;
        }

        step++;
        double progress = Math.min(1.0, step / (double) Math.max(1, durationTicks - 1));
        double eased = progress * progress * (3.0 - 2.0 * progress);

        Location point = bezier(eased);
        point.setYaw(lerpAngle(start.getYaw(), seat.getYaw(), eased));
        point.setPitch((float) (start.getPitch() + (seat.getPitch() - start.getPitch()) * eased));

        if (!safe(point)) {
            player.teleport(seat);
            stop();
            return;
        }

        player.teleport(point);
        if (progress >= 1.0) {
            stop();
        }
    }

    private Location bezier(double t) {
        double u = 1.0 - t;
        double x = u * u * start.getX() + 2.0 * u * t * control.getX() + t * t * seat.getX();
        double y = u * u * start.getY() + 2.0 * u * t * control.getY() + t * t * seat.getY();
        double z = u * u * start.getZ() + 2.0 * u * t * control.getZ() + t * t * seat.getZ();
        return new Location(seat.getWorld(), x, y, z, seat.getYaw(), seat.getPitch());
    }

    private boolean safe(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        Block feet = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        Block head = world.getBlockAt(location.getBlockX(), location.getBlockY() + 1, location.getBlockZ());
        Block floor = world.getBlockAt(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ());
        return feet.isPassable() && !feet.isLiquid()
                && head.isPassable() && !head.isLiquid()
                && !floor.isLiquid();
    }

    private static float lerpAngle(float from, float to, double t) {
        float delta = wrap(to - from);
        return wrap((float) (from + delta * t));
    }

    private static float wrap(float angle) {
        float wrapped = angle % 360.0f;
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        } else if (wrapped > 180.0f) {
            wrapped -= 360.0f;
        }
        return wrapped;
    }
}
