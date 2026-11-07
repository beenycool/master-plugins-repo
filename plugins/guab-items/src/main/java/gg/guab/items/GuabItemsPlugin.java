package gg.guab.items;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class GuabItemsPlugin extends JavaPlugin {

    private ItemsConfig config;
    private BeerRegistry beer;
    private ItemFactory items;
    private Cooldowns cooldowns;
    private BeerService beerService;
    private BeerMenuDialog beerMenu;
    private PubManager pub;
    private PubQuiz quiz;
    private DartsManager darts;
    private MenuShelves menuShelves;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = ItemsConfig.load(this);
        this.beer = new BeerRegistry(this);
        this.items = new ItemFactory(this);
        this.cooldowns = new Cooldowns();
        this.beerService = new BeerService(this);
        this.beerMenu = new BeerMenuDialog(this);

        this.pub = new PubManager(this);
        this.pub.start();
        this.quiz = new PubQuiz(this);
        this.quiz.start();
        this.darts = new DartsManager(this);
        this.darts.start();
        this.menuShelves = new MenuShelves(this);

        getServer().getPluginManager().registerEvents(new ItemListener(this), this);
        getServer().getPluginManager().registerEvents(new BeerTapListener(this), this);
        getServer().getPluginManager().registerEvents(new BeerDrinkListener(this), this);
        getServer().getPluginManager().registerEvents(new DartsBoard(this), this);
        getServer().getPluginManager().registerEvents(darts, this);
        getServer().getPluginManager().registerEvents(darts.room(), this);
        getServer().getPluginManager().registerEvents(menuShelves, this);
        getServer().getPluginManager().registerEvents(new PubProtection(this), this);

        PluginCommand command = getCommand("guabitems");
        if (command != null) {
            GuabItemsCommand executor = new GuabItemsCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        PubCommand pubCommand = new PubCommand(this);
        for (String name : new String[]{"round", "pints", "quiz", "beer"}) {
            PluginCommand pubExecutor = getCommand(name);
            if (pubExecutor != null) {
                pubExecutor.setExecutor(pubCommand);
                pubExecutor.setTabCompleter(pubCommand);
            }
        }
        DartsCommand dartsCommand = new DartsCommand(this);
        PluginCommand dartsExecutor = getCommand("darts");
        if (dartsExecutor != null) {
            dartsExecutor.setExecutor(dartsCommand);
            dartsExecutor.setTabCompleter(dartsCommand);
        }

        getLogger().info("GuabItems ready (wind charge power=" + config.windChargePower()
                + ", jump boost amplifier=" + config.jumpBoostAmplifier()
                + ", " + pub.pintsCount() + " pints on record, "
                + beer.all().size() + " beers on the menu).");
    }

    public PubManager pub() {
        return pub;
    }

    public PubQuiz quiz() {
        return quiz;
    }

    public BeerRegistry beer() {
        return beer;
    }

    public BeerService beerService() {
        return beerService;
    }

    public BeerMenuDialog beerMenu() {
        return beerMenu;
    }

    public DartsManager darts() {
        return darts;
    }

    public void reload() {
        reloadConfig();
        this.config = ItemsConfig.load(this);
        beer.load();
        pub.reload();
        quiz.reloadQuestions();
        menuShelves.reload();
        darts.reload();
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
        if (darts != null) {
            darts.stop();
            darts = null;
        }
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
