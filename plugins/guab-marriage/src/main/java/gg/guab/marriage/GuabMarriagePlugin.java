package gg.guab.marriage;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class GuabMarriagePlugin extends JavaPlugin {

    private MarriageConfig config;
    private MarriageStore store;
    private Proposals proposals;
    private MarriageService service;
    private MarriageMenu menu;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = MarriageConfig.load(getConfig());
        if (!config.enabled()) {
            getLogger().info("GuabMarriage is disabled in config.yml; no commands or listeners were registered.");
            return;
        }

        this.store = new MarriageStore(new File(getDataFolder(), "marriages.yml").toPath(), getLogger());
        this.proposals = new Proposals(config.proposalTimeoutMillis());
        this.service = new MarriageService(this, store, proposals);
        this.menu = new MarriageMenu(this);

        getServer().getPluginManager().registerEvents(new MarriageListener(this, service), this);
        register("marry");
        register("mc");

        getLogger().info("GuabMarriage ready (" + store.size() + " couples, "
                + proposals.size(System.currentTimeMillis()) + " pending proposal(s)).");
    }

    private void register(String name) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command '" + name + "' is missing from plugin.yml.");
            return;
        }
        MarriageCommand executor = new MarriageCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    /** Reloads config.yml and marriages.yml, keeping in-memory couples if the store is bad. */
    public boolean reload() {
        reloadConfig();
        this.config = MarriageConfig.load(getConfig());
        if (proposals != null) {
            proposals.setTimeoutMillis(config.proposalTimeoutMillis());
        }
        if (store == null) {
            return true;
        }
        return store.reload();
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.shutdown();
        }
        if (store != null) {
            store.save();
        }
    }

    public MarriageConfig config() {
        return config;
    }

    public MarriageStore store() {
        return store;
    }

    public Proposals proposals() {
        return proposals;
    }

    public MarriageService service() {
        return service;
    }

    public MarriageMenu menu() {
        return menu;
    }
}
