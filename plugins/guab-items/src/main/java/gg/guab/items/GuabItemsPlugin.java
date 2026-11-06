package gg.guab.items;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class GuabItemsPlugin extends JavaPlugin {

    private ItemsConfig config;
    private ItemFactory items;
    private Cooldowns cooldowns;
    private PubManager pub;
    private PubQuiz quiz;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = ItemsConfig.load(this);
        this.items = new ItemFactory(this);
        this.cooldowns = new Cooldowns();

        this.pub = new PubManager(this);
        this.pub.start();
        this.quiz = new PubQuiz(this);
        this.quiz.start();

        getServer().getPluginManager().registerEvents(new ItemListener(this), this);
        getServer().getPluginManager().registerEvents(new BeerTapListener(this), this);
        getServer().getPluginManager().registerEvents(new DartsBoard(this), this);
        getServer().getPluginManager().registerEvents(new MenuShelves(this), this);

        PluginCommand command = getCommand("guabitems");
        if (command != null) {
            GuabItemsCommand executor = new GuabItemsCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        PubCommand pubCommand = new PubCommand(this);
        for (String name : new String[]{"round", "pints", "quiz"}) {
            PluginCommand pubExecutor = getCommand(name);
            if (pubExecutor != null) {
                pubExecutor.setExecutor(pubCommand);
                pubExecutor.setTabCompleter(pubCommand);
            }
        }

        getLogger().info("GuabItems ready (wind charge power=" + config.windChargePower()
                + ", jump boost amplifier=" + config.jumpBoostAmplifier()
                + ", " + pub.pintsCount() + " pints on record).");
    }

    public PubManager pub() {
        return pub;
    }

    public PubQuiz quiz() {
        return quiz;
    }

    public void reload() {
        reloadConfig();
        this.config = ItemsConfig.load(this);
    }

    public ItemsConfig config() {
        return config;
    }

    public ItemFactory items() {
        return items;
    }

    public Cooldowns cooldowns() {
        return cooldowns;
    }

    @Override
    public void onDisable() {
        if (quiz != null) {
            quiz.stop();
            quiz = null;
        }
        if (pub != null) {
            pub.stop();
            pub = null;
        }
        getLogger().info("GuabItems disabled.");
    }
}
