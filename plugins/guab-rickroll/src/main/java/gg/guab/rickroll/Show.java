package gg.guab.rickroll;

import gg.guab.core.HudChannel;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
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
    private final PostEffects effects;
    private final boolean reducedMotion;

    private PlayerState snapshot;
    private ItemDisplay display;
    private BossBar bossBar;
    private BukkitTask task;
    private BukkitTask timeout;
    private CameraIntro cameraIntro;
    private String lastLyric;
    private int lastTimerSecond = -1;
    private int tick;
    private boolean started;
    private boolean finished;
    private long beganMillis;

    Show(GuabRickrollPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.id = player.getUniqueId();
        this.reducedMotion = plugin.rickrollReducedMotion(player);
        PostEffects cinematicEffects;
        try {
            cinematicEffects = new PostEffects(plugin, player, reducedMotion);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Cinematic post effects are unavailable for " + player.getName()
                    + " (" + throwable.getClass().getSimpleName() + "); continuing without them.");
            cinematicEffects = null;
        }
        this.effects = cinematicEffects;
    }

    void begin() {
        this.beganMillis = System.currentTimeMillis();
        try {
            this.snapshot = PlayerState.capture(player);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not capture pre-show state for " + player.getName() + ": "
                    + throwable.getClass().getSimpleName());
            plugin.shows().remove(this);
            return;
        }

        // Write the restore marker before mutating anything, so a crash mid-begin still has a way back.
        try {
            player.getPersistentDataContainer().set(markerKey(), PersistentDataType.STRING, snapshot.serialize());
            player.getPersistentDataContainer().set(backKey(), PersistentDataType.STRING,
                    serializeLocation(player.getLocation()));
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not write the rickroll restore marker for " + player.getName() + ": "
                    + throwable.getClass().getSimpleName() + "; refusing to start the show.");
            plugin.shows().remove(this);
            return;
        }

        try {
            player.setGameMode(GameMode.SPECTATOR);
            player.setAllowFlight(true);
            player.setFlySpeed(0.0f);
            player.setWalkSpeed(0.0f);
            player.setPlayerTime(18000L, false);
            player.teleport(plugin.cinema().seat());
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not start the rickroll for " + player.getName() + ": "
                    + throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : " (" + throwable.getMessage() + ")"));
            finish();
            return;
        }

        player.showTitle(Title.title(
                Component.text("BUFFERING", NamedTextColor.GRAY),
                Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ZERO)));

        if (!plugin.packs().available()) {
            // pack.enabled=false or a blank URL: do not make the player wait for a pack that will never arrive.
            startFallback();
            return;
        }
        if (plugin.packs().isReady(player)) {
            startShow();
        } else if (plugin.packs().isDenied(player)) {
            startFallback();
        } else if (!plugin.packs().send(player)) {
            startFallback();
        } else {
            timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!started && !finished) {
                    startFallback();
                }
            }, 20L * plugin.config().packTimeoutSeconds());
        }
    }

    void onPackReady() {
        if (!started && !finished) {
            cancelTimeout();
            startShow();
        }
    }

    void onPackDenied() {
        if (!started && !finished) {
            cancelTimeout();
            startFallback();
        }
    }

    private void startShow() {
        if (finished) {
            return;
        }
        started = true;
        cancelTimeout();
        List<ItemStack> frames = plugin.cinema().frames();
        try {
            display = plugin.cinema().spawnScreen();
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not spawn the cinema screen for " + player.getName() + ": "
                    + throwable.getClass().getSimpleName());
            display = null;
        }
        if (display != null && !frames.isEmpty()) {
            display.setItemStack(frames.get(0));
        } else if (frames.isEmpty()) {
            plugin.getLogger().warning("The rickroll pack has no frames; playing audio without a picture.");
        }
        int intro = plugin.config().introTicks();
        if (!reducedMotion && plugin.config().cameraIntroEnabled()) {
            cameraIntro = new CameraIntro(
                    plugin, player, plugin.cinema().seat(), plugin.config().cameraIntroDurationTicks());
            if (cameraIntro.start()) {
                // The film starts as soon as the camera settles; the intro duration therefore becomes the pre-roll.
                intro = plugin.config().cameraIntroDurationTicks();
            } else {
                cameraIntro = null;
                player.teleport(plugin.cinema().seat());
            }
        } else {
            // Camera intro disabled, or reduced motion: no dolly, no fast camera movement.
            player.teleport(plugin.cinema().seat());
        }
        int total = intro + plugin.config().showSeconds() * 20;
        final int preRoll = intro;

        if (effects != null) {
            effects.apply();
        }

        player.showTitle(Title.title(
                Component.text("TRACKING", NamedTextColor.GRAY),
                Component.text("VHS", NamedTextColor.DARK_GRAY),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ZERO)));

        bossBar = BossBar.bossBar(
                Component.text("Never Gonna Give You Up - " + formatTime(plugin.config().showSeconds()),
                        NamedTextColor.LIGHT_PURPLE),
                1.0f,
                BossBar.Color.PINK,
                BossBar.Overlay.NOTCHED_20);
        player.showBossBar(bossBar);
        playProjectorStart();
        actionBar(Component.text("▌▌ VHS TRACKING ▌▌", NamedTextColor.DARK_GRAY), 40);

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (finished) {
                return;
            }
            int elapsed = tick - preRoll;
            if (tick == preRoll) {
                player.playSound(player, "guab:rickroll", SoundCategory.MASTER, 1.0f, 1.0f);
                actionBar(Component.text("Now playing: Never Gonna Give You Up", NamedTextColor.GOLD), 60);
            }
            if (elapsed >= 0) {
                if (display != null && display.isValid() && !frames.isEmpty()) {
                    int frame = (int) ((elapsed * (long) plugin.config().fps()) / 20);
                    display.setItemStack(frames.get(Math.min(frame, frames.size() - 1)));
                }

                int totalTicks = plugin.config().showSeconds() * 20;
                if (bossBar != null) {
                    bossBar.progress(Math.max(0.0f, Math.min(1.0f, 1.0f - (float) elapsed / totalTicks)));

                    int second = elapsed / 20;
                    if (second != lastTimerSecond) {
                        lastTimerSecond = second;
                        int remaining = Math.max(0, plugin.config().showSeconds() - second);
                        bossBar.name(Component.text("Never Gonna Give You Up - " + formatTime(remaining),
                                NamedTextColor.LIGHT_PURPLE));
                    }
                }

                String lyric = plugin.lyrics().lineAt(elapsed / 20.0 + plugin.config().lyricsOffsetSeconds());
                if (lyric != null && !lyric.equals(lastLyric)) {
                    NamedTextColor color = lyric.startsWith("Never gonna")
                            ? NamedTextColor.LIGHT_PURPLE
                            : NamedTextColor.GOLD;
                    actionBar(Component.text(lyric, color), 60);
                    lastLyric = lyric;
                } else if (lyric == null && lastLyric != null) {
                    actionBar(Component.empty(), 20);
                    lastLyric = null;
                }
            }
            tick++;
            if (tick >= total) {
                finish();
            }
        }, 1L, 1L);
    }

    private void playProjectorStart() {
        player.playSound(player, Sound.BLOCK_PISTON_EXTEND, SoundCategory.MASTER, 0.4f, 0.7f);
        player.playSound(player, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.MASTER, 0.35f, 0.6f);
        player.playSound(player, Sound.BLOCK_LEVER_CLICK, SoundCategory.MASTER, 0.5f, 0.7f);
        spawnStaticBurst();
    }

    private void spawnStaticBurst() {
        if (reducedMotion || display == null || !display.isValid()) {
            return;
        }
        World world = display.getWorld();
        Location screen = display.getLocation();
        world.spawnParticle(Particle.ELECTRIC_SPARK, screen, 60, 6.0, 3.0, 0.15, 0.03);
        world.spawnParticle(Particle.WHITE_ASH, screen, 40, 6.0, 3.0, 0.15, 0.01);
        world.spawnParticle(Particle.CLOUD, screen, 12, 5.0, 2.5, 0.15, 0.01);
    }

    private void startFallback() {
        if (finished) {
            return;
        }
        started = true;
        stopIntro();
        if (effects != null) {
            effects.stop();
        }
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
        try {
            cleanup(true);
        } finally {
            plugin.shows().remove(this);
        }
    }

    void quit() {
        if (finished) {
            return;
        }
        finished = true;
        try {
            // PlayerQuitEvent is normally still online; teleporting back keeps the saved logout location outside
            // the cinema. If the player is already gone we leave the PDC marker for the join-time repair.
            cleanup(player.isOnline());
        } finally {
            plugin.shows().remove(this);
        }
    }

    private void cleanup(boolean teleport) {
        stopIntro();
        if (effects != null) {
            effects.stop();
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
        cancelTimeout();
        if (display != null) {
            try {
                display.remove();
            } catch (Throwable ignored) {
                // Already removed with the cinema world.
            }
            display = null;
        }
        if (bossBar != null) {
            player.hideBossBar(bossBar);
            bossBar = null;
        }
        if (player.isOnline()) {
            player.stopSound("guab:rickroll");
            player.stopSound(Sound.MUSIC_DISC_PIGSTEP);
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            player.sendActionBar(Component.empty());
            if (snapshot != null) {
                try {
                    snapshot.apply(player, teleport, plugin);
                } catch (Throwable throwable) {
                    plugin.getLogger().warning("Could not fully restore " + player.getName()
                            + " after the rickroll: " + throwable.getClass().getSimpleName());
                }
            }
            player.getPersistentDataContainer().remove(markerKey());
            player.getPersistentDataContainer().remove(backKey());
        }
    }

    private void stopIntro() {
        if (cameraIntro != null) {
            cameraIntro.stop();
            cameraIntro = null;
        }
    }

    private void cancelTimeout() {
        if (timeout != null) {
            timeout.cancel();
            timeout = null;
        }
    }

    private String formatTime(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * Lyrics and show captions go through GuabCore's shared action-bar channel at CRITICAL priority so points,
     * darts hints and other HUD messages cannot overwrite them. Falls back to a direct action bar when GuabCore's
     * service is unavailable.
     */
    private void actionBar(Component message, int durationTicks) {
        HudChannel channel = plugin.hudChannel();
        if (channel != null) {
            channel.send(player, message, HudChannel.PRIORITY_CRITICAL, durationTicks);
        } else {
            player.sendActionBar(message);
        }
    }

    void onReload() {
        if (started && effects != null) {
            effects.reload();
        }
    }

    int elapsedSeconds() {
        return beganMillis == 0L ? 0 : (int) Math.max(0L, (System.currentTimeMillis() - beganMillis) / 1000L);
    }

    String playerName() {
        return player.getName();
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
