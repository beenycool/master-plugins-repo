package gg.guab.rickroll;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

final class Show {

    private final GuabRickrollPlugin plugin;
    private final Player player;
    private final UUID id;
    private final Location back;
    private final GameMode mode;
    private final boolean allowFlight;
    private final float flySpeed;
    private final float walkSpeed;

    private ItemDisplay display;
    private BossBar bossBar;
    private BukkitTask task;
    private BukkitTask timeout;
    private String lastLyric;
    private int lastTimerSecond = -1;
    private int tick;
    private boolean started;
    private boolean finished;

    Show(GuabRickrollPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.id = player.getUniqueId();
        this.back = player.getLocation().clone();
        this.mode = player.getGameMode();
        this.allowFlight = player.getAllowFlight();
        this.flySpeed = player.getFlySpeed();
        this.walkSpeed = player.getWalkSpeed();
    }

    void begin() {
        player.setGameMode(GameMode.SPECTATOR);
        player.setAllowFlight(true);
        player.setFlySpeed(0.0f);
        player.setWalkSpeed(0.0f);
        player.setPlayerTime(18000L, false);
        player.getPersistentDataContainer().set(markerKey(), PersistentDataType.STRING, mode.name());
        player.getPersistentDataContainer().set(backKey(), PersistentDataType.STRING, serializeLocation(back));
        player.teleport(plugin.cinema().seat());
        player.showTitle(Title.title(
                Component.text("BUFFERING", NamedTextColor.GRAY),
                Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ZERO)));

        if (plugin.packs().isReady(player)) {
            startShow();
        } else if (plugin.packs().isDenied(player)) {
            startFallback();
        } else {
            plugin.packs().send(player);
            timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!started) {
                    startFallback();
                }
            }, 20L * plugin.config().packTimeoutSeconds());
        }
    }

    void onPackReady() {
        if (!started) {
            cancelTimeout();
            startShow();
        }
    }

    void onPackDenied() {
        if (!started) {
            cancelTimeout();
            startFallback();
        }
    }

    private void startShow() {
        started = true;
        display = plugin.cinema().spawnScreen();
        List<ItemStack> frames = plugin.cinema().frames();
        int intro = plugin.config().introTicks();
        int total = intro + plugin.config().showSeconds() * 20;

        player.showTitle(Title.title(
                Component.text("TRACKING", NamedTextColor.GRAY),
                Component.text("VHS", NamedTextColor.DARK_GRAY),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ZERO)));

        bossBar = BossBar.bossBar(
                Component.text("Never Gonna Give You Up - " + formatTime(plugin.config().showSeconds()), NamedTextColor.LIGHT_PURPLE),
                1.0f,
                BossBar.Color.PINK,
                BossBar.Overlay.NOTCHED_20);
        player.showBossBar(bossBar);
        player.playSound(player, Sound.BLOCK_LEVER_CLICK, SoundCategory.MASTER, 0.5f, 0.7f);
        player.sendActionBar(Component.text("Now playing: Never Gonna Give You Up", NamedTextColor.GOLD));

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int elapsed = tick - intro;
            if (tick == intro) {
                player.playSound(player, "guab:rickroll", SoundCategory.MASTER, 1.0f, 1.0f);
            }
            if (elapsed >= 0) {
                int frame = (int) ((elapsed * (long) plugin.config().fps()) / 20);
                display.setItemStack(frames.get(Math.min(frame, frames.size() - 1)));

                int totalTicks = plugin.config().showSeconds() * 20;
                bossBar.progress(Math.max(0.0f, Math.min(1.0f, 1.0f - (float) elapsed / totalTicks)));

                int second = elapsed / 20;
                if (second != lastTimerSecond) {
                    lastTimerSecond = second;
                    int remaining = Math.max(0, plugin.config().showSeconds() - second);
                    bossBar.name(Component.text("Never Gonna Give You Up - " + formatTime(remaining), NamedTextColor.LIGHT_PURPLE));
                }

                String lyric = plugin.lyrics().lineAt(elapsed / 20.0 + plugin.config().lyricsOffsetSeconds());
                if (lyric != null && !lyric.equals(lastLyric)) {
                    NamedTextColor color = lyric.startsWith("Never gonna") ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GOLD;
                    player.sendActionBar(Component.text(lyric, color));
                    lastLyric = lyric;
                } else if (lyric == null && lastLyric != null) {
                    player.sendActionBar(Component.empty());
                    lastLyric = null;
                }
            }
            tick++;
            if (tick >= total) {
                finish();
            }
        }, 1L, 1L);
    }

    private void startFallback() {
        started = true;
        int seconds = plugin.config().fallbackSeconds();
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * seconds, 0, false, false));
        player.showTitle(Title.title(
                Component.text("NEVER GONNA GIVE YOU UP", NamedTextColor.RED),
                Component.text("resource pack declined", NamedTextColor.DARK_GRAY),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(4), Duration.ofSeconds(1))));
        player.playSound(player, Sound.MUSIC_DISC_PIGSTEP, SoundCategory.MASTER, 1.0f, 1.0f);
        task = Bukkit.getScheduler().runTaskLater(plugin, this::finish, 20L * seconds);
    }

    void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
        cancelTimeout();
        if (display != null) {
            display.remove();
            display = null;
        }
        if (bossBar != null) {
            player.hideBossBar(bossBar);
            bossBar = null;
        }
        if (player.isOnline()) {
            player.stopSound("guab:rickroll");
            player.sendActionBar(Component.empty());
        }
        restore();
        plugin.shows().remove(this);
    }

    void quit() {
        if (finished) {
            return;
        }
        finished = true;
        if (task != null) {
            task.cancel();
        }
        cancelTimeout();
        if (display != null) {
            display.remove();
        }
        if (bossBar != null) {
            player.hideBossBar(bossBar);
            bossBar = null;
        }
        if (player.isOnline()) {
            player.sendActionBar(Component.empty());
        }
        restore();
        plugin.shows().remove(this);
    }

    private void restore() {
        player.resetPlayerTime();
        player.setGameMode(mode);
        player.setAllowFlight(allowFlight);
        player.setFlySpeed(flySpeed);
        player.setWalkSpeed(walkSpeed);
        if (player.isOnline()) {
            player.teleport(back);
            player.getPersistentDataContainer().remove(markerKey());
            player.getPersistentDataContainer().remove(backKey());
        }
    }

    private String formatTime(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private void cancelTimeout() {
        if (timeout != null) {
            timeout.cancel();
            timeout = null;
        }
    }

    UUID playerId() {
        return id;
    }

    private NamespacedKey markerKey() {
        return new NamespacedKey(plugin, "active");
    }

    private NamespacedKey backKey() {
        return new NamespacedKey(plugin, "back");
    }

    static String serializeLocation(Location location) {
        if (location.getWorld() == null) {
            return "";
        }
        return location.getWorld().getName() + ";" + location.getX() + ";" + location.getY() + ";" + location.getZ()
                + ";" + location.getYaw() + ";" + location.getPitch();
    }

    static Location deserializeLocation(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split(";");
        if (parts.length != 6) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            return new Location(world,
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    Float.parseFloat(parts[4]),
                    Float.parseFloat(parts[5]));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
