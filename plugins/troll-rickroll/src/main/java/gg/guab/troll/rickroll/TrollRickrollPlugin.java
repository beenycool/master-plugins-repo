package gg.guab.troll.rickroll;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class TrollRickrollPlugin extends JavaPlugin {

    private RickrollConfig config;
    private Cinema cinema;
    private PackManager packs;
    private ShowManager shows;
    private BlockStore blocks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reload();
        this.blocks = new BlockStore(this);
        this.cinema = new Cinema(this);
        this.packs = new PackManager(this);
        this.shows = new ShowManager(this);

        getServer().getPluginManager().registerEvents(new TriggerListener(this), this);
        getServer().getPluginManager().registerEvents(this.packs, this);

        PluginCommand command = getCommand("rickroll");
        if (command != null) {
            command.setExecutor(new RickrollCommand(this));
        }
        getLogger().info("TrollRickroll ready (" + config.frameCount() + " frames, " + config.fps() + " fps).");
    }

    public void reload() {
        reloadConfig();
        this.config = RickrollConfig.load(this);
        if (this.packs != null) {
            this.packs.reload();
        }
        if (this.cinema != null) {
            this.cinema.reload();
        }
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
}
