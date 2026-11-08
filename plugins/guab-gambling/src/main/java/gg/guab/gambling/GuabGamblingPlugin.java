package gg.guab.gambling;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class GuabGamblingPlugin extends JavaPlugin {

    private CasinoConfig config;
    private CasinoVenue venue;
    private Blackjack blackjack;
    private BlackjackDealer dealer;

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

        // MineGames resolves station worlds during its own enable, so make sure the
        // casino world exists first. It also keeps the venue warm between events.
        try {
            venue.world();
        } catch (RuntimeException exception) {
            getLogger().warning("Could not pre-load the casino world: " + exception.getMessage());
        }

        this.blackjack = new Blackjack(this);
        this.blackjack.start();
        this.dealer = new BlackjackDealer(this, blackjack);
        this.dealer.start();
        BlackjackCommand blackjackExecutor = new BlackjackCommand(this, blackjack, dealer);
        PluginCommand blackjackCommand = getCommand("blackjack");
        if (blackjackCommand != null) {
            blackjackCommand.setExecutor(blackjackExecutor);
            blackjackCommand.setTabCompleter(blackjackExecutor);
        }
        PluginCommand newHandCommand = getCommand("new");
        if (newHandCommand != null) {
            newHandCommand.setExecutor(blackjackExecutor);
            newHandCommand.setTabCompleter(blackjackExecutor);
        }
        getServer().getPluginManager().registerEvents(new CasinoGuard(venue), this);
        getServer().getPluginManager().registerEvents(dealer, this);

        getLogger().info("GuabGambling ready (casino " + (config.enabled() ? "open" : "closed") + ").");
    }

    public void reload() {
        reloadConfig();
        this.config = CasinoConfig.load(this);
        this.venue = new CasinoVenue(this);
        if (dealer != null) {
            dealer.reload();
        }
    }

    public CasinoConfig config() {
        return config;
    }

    public CasinoVenue venue() {
        return venue;
    }

    public BlackjackDealer dealer() {
        return dealer;
    }

    @Override
    public void onDisable() {
        if (dealer != null) {
            dealer.stop();
            dealer = null;
        }
        if (blackjack != null) {
            blackjack.stop();
            blackjack = null;
        }
    }
}
