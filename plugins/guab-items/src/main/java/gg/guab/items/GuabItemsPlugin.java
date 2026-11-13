package gg.guab.items;

import gg.guab.core.HudChannel;
import gg.guab.core.HudContextService;
import gg.guab.core.PlayerPreferencesStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class GuabItemsPlugin extends JavaPlugin {

    private ItemsConfig config;
    private BeerRegistry beer;
    private ItemFactory items;
    private Cooldowns cooldowns;
    private BeerService beerService;
    private BeerMenuDialog beerMenu;
    private GuabMenuDialog menu;
    private PubManager pub;
    private PubQuiz quiz;
    private DartsManager darts;
    private MenuShelves menuShelves;
    private BeerTapListener beerTap;
    private Bartender bartender;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = ItemsConfig.load(this);
        this.beer = new BeerRegistry(this);
        this.items = new ItemFactory(this);
        this.cooldowns = new Cooldowns();
        this.beerService = new BeerService(this);
        this.beerMenu = new BeerMenuDialog(this);
        this.menu = new GuabMenuDialog(this);

        this.pub = new PubManager(this);
        this.pub.start();
        this.quiz = new PubQuiz(this);
        this.quiz.start();
        this.darts = new DartsManager(this);
        this.darts.start();
        getServer().getServicesManager().register(
                HudContextService.class, new PubHudContext(this), this, ServicePriority.Normal);
        this.menuShelves = new MenuShelves(this);
        this.bartender = new Bartender(this);
        this.bartender.start();

        this.beerTap = new BeerTapListener(this);
        getServer().getPluginManager().registerEvents(new ItemListener(this), this);
        getServer().getPluginManager().registerEvents(beerTap, this);
        getServer().getPluginManager().registerEvents(new BeerDrinkListener(this), this);
        getServer().getPluginManager().registerEvents(new DartsBoard(this), this);
        getServer().getPluginManager().registerEvents(darts, this);
        getServer().getPluginManager().registerEvents(darts.room(), this);
        getServer().getPluginManager().registerEvents(new DartsShotListener(this), this);
        getServer().getPluginManager().registerEvents(menuShelves, this);
        getServer().getPluginManager().registerEvents(new PubProtection(this), this);

        PluginCommand command = getCommand("guabitems");
        if (command != null) {
            GuabItemsCommand executor = new GuabItemsCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        PubCommand pubCommand = new PubCommand(this);
        for (String name : new String[]{"round", "pints", "quiz", "beer", "pub"}) {
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
        GuabMenuCommand menuCommand = new GuabMenuCommand(this);
        PluginCommand menuExecutor = getCommand("guab");
        if (menuExecutor != null) {
            menuExecutor.setExecutor(menuCommand);
            menuExecutor.setTabCompleter(menuCommand);
        }

        getLogger().info("GuabItems ready (wind charge power=" + config.windChargePower()
                + ", jump boost amplifier=" + config.jumpBoostAmplifier()
                + ", " + pub.totalPints() + " pints served, "
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

    public GuabMenuDialog menu() {
        return menu;
    }

    public void reload() {
        reloadConfig();
        this.config = ItemsConfig.load(this);
        beer.load();
        pub.reload();
        quiz.reloadQuestions();
        menuShelves.reload();
        darts.reload();
        bartender.reload();
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

    public MenuShelves menuShelves() {
        return menuShelves;
    }

    public BeerTapListener beerTap() {
        return beerTap;
    }

    /** Shared action-bar channel from GuabCore, or null during early startup. */
    public HudChannel hudChannel() {
        var registration = getServer().getServicesManager().getRegistration(HudChannel.class);
        return registration == null ? null : registration.getProvider();
    }

    /** Shared player preferences, or null during early startup. */
    public PlayerPreferencesStore preferences() {
        var registration = getServer().getServicesManager()
                .getRegistration(PlayerPreferencesStore.class);
        return registration == null ? null : registration.getProvider();
    }

    @Override
    public void onDisable() {
        if (bartender != null) {
            bartender.stop();
            bartender = null;
        }
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
