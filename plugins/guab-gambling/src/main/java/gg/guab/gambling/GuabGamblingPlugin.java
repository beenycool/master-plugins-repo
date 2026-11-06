package gg.guab.gambling;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class GuabGamblingPlugin extends JavaPlugin {

    private CasinoConfig config;
    private CasinoVenue venue;
    private Blackjack blackjack;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = CasinoConfig.load(this);
        this.venue = new CasinoVenue(this);

        PluginCommand command = getCommand("casino");
        if (command != null) {
            CasinoCommand executor = new CasinoCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        this.blackjack = new Blackjack(this);
        this.blackjack.start();
        PluginCommand blackjackCommand = getCommand("blackjack");
        if (blackjackCommand != null) {
            BlackjackCommand executor = new BlackjackCommand(this, blackjack);
            blackjackCommand.setExecutor(executor);
            blackjackCommand.setTabCompleter(executor);
        }
        getServer().getPluginManager().registerEvents(new CasinoGuard(venue), this);

        // MineGames resolves station worlds during its own enable, so make sure the
        // casino world exists first. It also keeps the venue warm between events.
        try {
            venue.world();
        } catch (RuntimeException exception) {
            getLogger().warning("Could not pre-load the casino world: " + exception.getMessage());
        }

        getLogger().info("GuabGambling ready (casino " + (config.enabled() ? "open" : "closed") + ").");
    }

    public void reload() {
        reloadConfig();
        this.config = CasinoConfig.load(this);
        this.venue = new CasinoVenue(this);
    }

    public CasinoConfig config() {
        return config;
    }

    public CasinoVenue venue() {
        return venue;
    }

    @Override
    public void onDisable() {
        if (blackjack != null) {
            blackjack.stop();
            blackjack = null;
        }
    }
}
