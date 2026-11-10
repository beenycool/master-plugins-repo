package gg.guab.rickroll;

import gg.guab.core.HudChannel;
import gg.guab.core.PlayerPreferencesStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public final class GuabRickrollPlugin extends JavaPlugin {

    private RickrollConfig config;
    private Cinema cinema;
    private PackManager packs;
    private ShowManager shows;
    private BlockStore blocks;
    private Lyrics lyrics;
    private TriggerListener triggers;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.lyrics = loadLyrics();
        reload();
        this.blocks = new BlockStore(this);
        this.cinema = new Cinema(this);
        this.cinema.world();
        this.packs = new PackManager(this);
        this.shows = new ShowManager(this);
        this.triggers = new TriggerListener(this);

        getServer().getPluginManager().registerEvents(this.triggers, this);
        getServer().getPluginManager().registerEvents(this.packs, this);

        PluginCommand command = getCommand("rickroll");
        if (command != null) {
            command.setExecutor(new RickrollCommand(this));
        }
        getLogger().info("GuabRickroll ready (" + config.frameCount() + " frames, " + config.fps() + " fps).");
    }

    public void reload() {
        reloadConfig();
        this.config = RickrollConfig.load(this);
        this.lyrics = loadLyrics();
        if (this.blocks != null) {
            this.blocks.reload();
        }
        if (this.packs != null) {
            this.packs.reload();
        }
        if (this.cinema != null) {
            this.cinema.reload();
        }
        if (this.shows != null) {
            this.shows.reload();
        }
        if (this.triggers != null) {
            this.triggers.reloadConfig();
        }
    }

    private Lyrics loadLyrics() {
        File local = new File(getDataFolder(), "lyrics.txt");
        if (local.isFile()) {
            try (InputStream input = Files.newInputStream(local.toPath())) {
                return Lyrics.load(input);
            } catch (IOException exception) {
                getLogger().warning("Could not read " + local.getPath() + ": " + exception.getMessage()
                        + "; falling back to the bundled lyrics.");
            }
        }
        return Lyrics.load(getResource("lyrics.txt"));
    }

    @Override
    public void onDisable() {
        if (shows != null) {
            shows.stopAll();
        }
        if (cinema != null) {
            cinema.cleanup();
        }
    }

    public RickrollConfig config() {
        return config;
    }

    public Cinema cinema() {
        return cinema;
    }

    public PackManager packs() {
        return packs;
    }

    public ShowManager shows() {
        return shows;
    }

    public BlockStore blocks() {
        return blocks;
    }

    public Lyrics lyrics() {
        return lyrics;
    }

    public TriggerListener triggers() {
        return triggers;
    }

    private PlayerPreferencesStore preferences() {
        var registration = getServer().getServicesManager().getRegistration(PlayerPreferencesStore.class);
        return registration == null ? null : registration.getProvider();
    }

    /** Opted-out players never get natural block triggers; staff can still run /rickroll test. */
    public boolean rickrollOptOut(Player player) {
        PlayerPreferencesStore store = preferences();
        return store != null && store.rickrollOptOut(player.getUniqueId());
    }

    /** Reduced motion skips the camera dolly and flashing effects, but keeps the film and lyrics. */
    public boolean rickrollReducedMotion(Player player) {
        PlayerPreferencesStore store = preferences();
        return store != null && store.rickrollReducedMotion(player.getUniqueId());
    }

    public HudChannel hudChannel() {
        var registration = getServer().getServicesManager().getRegistration(HudChannel.class);
        return registration == null ? null : registration.getProvider();
    }
}
