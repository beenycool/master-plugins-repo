package gg.guab.troll.rickroll;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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

    private final TrollRickrollPlugin plugin;
    private final Player player;
    private final UUID id;
    private final Location back;
    private final GameMode mode;
    private final boolean allowFlight;
    private final float flySpeed;
    private final float walkSpeed;

    private ItemDisplay display;
    private BukkitTask task;
    private BukkitTask timeout;
    private int tick;
    private int period = 2;
    private boolean started;
    private boolean finished;

    Show(TrollRickrollPlugin plugin, Player player) {
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
            }, 20L * 15);
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
        period = Math.max(1, 20 / plugin.config().fps());
        display = plugin.cinema().spawnScreen();
        List<ItemStack> frames = plugin.cinema().frames();
        int intro = plugin.config().introTicks();
        int total = intro + plugin.config().showSeconds() * 20;

        player.showTitle(Title.title(
                Component.text("TRACKING", NamedTextColor.GRAY),
                Component.text("VHS", NamedTextColor.DARK_GRAY),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ZERO)));
        player.sendActionBar(Component.text("Never gonna give you up...", NamedTextColor.DARK_GRAY));

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (tick == intro) {
                player.playSound(player, "guab:rickroll", SoundCategory.MASTER, 1.0f, 1.0f);
            }
            if (tick >= intro && (tick - intro) % period == 0) {
                int frame = (tick - intro) / period;
                display.setItemStack(frames.get(frame % frames.size()));
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
        if (player.isOnline()) {
            player.stopSound("guab:rickroll");
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
        }
        player.getPersistentDataContainer().remove(markerKey());
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
}
