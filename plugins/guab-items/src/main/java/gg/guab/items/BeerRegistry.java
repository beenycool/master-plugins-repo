package gg.guab.items;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The beer menu. Built-in defaults keep the pub working even when the config is
 * old or minimal; {@code beer.styles} can override every style individually.
 */
public final class BeerRegistry {

    private final GuabItemsPlugin plugin;
    private final Map<String, BeerStyle> styles = new LinkedHashMap<>();
    private final Map<String, BeerBrand> brands = new LinkedHashMap<>();

    public BeerRegistry(GuabItemsPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        Map<String, BeerStyle> defaults = defaults();
        styles.clear();

        ConfigurationSection root = plugin.getConfig().getConfigurationSection("beer.styles");
        for (Map.Entry<String, BeerStyle> entry : defaults.entrySet()) {
            BeerStyle fallback = entry.getValue();
            ConfigurationSection section = root == null ? null : root.getConfigurationSection(entry.getKey());
            if (section != null && !section.getBoolean("enabled", true)) {
                continue;
            }
            styles.put(entry.getKey(), read(section, fallback));
        }

        if (styles.isEmpty()) {
            styles.put("lager", defaults.get("lager"));
        }

        brands.clear();
        addBrand("stella", "Stella Artois", "lager", "guab:beer_stella");
        addBrand("carling", "Carling", "lager", "guab:beer_carling");
        addBrand("fosters", "Foster's", "lager", "guab:beer_fosters");
        addBrand("sanmiguel", "San Miguel", "lager", "guab:beer_sanmiguel");
        addBrand("peroni", "Peroni", "lager", "guab:beer_peroni");
        addBrand("moretti", "Birra Moretti", "lager", "guab:beer_moretti");
        addBrand("heineken", "Heineken", "lager", "guab:beer_heineken");
        addBrand("corona", "Corona", "lager", "guab:beer_corona");
        addBrand("budweiser", "Budweiser", "lager", "guab:beer_budweiser");
        addBrand("guinness", "Guinness", "stout", "guab:beer_guinness");
        addBrand("johnsmiths", "John Smith's", "ale", "guab:beer_johnsmiths");
        addBrand("londonpride", "London Pride", "ale", "guab:beer_londonpride");
        addBrand("strongbow", "Strongbow", "cider", "guab:beer_strongbow");
        addBrand("thatchers", "Thatchers Gold", "cider", "guab:beer_thatchers");
        addBrand("alcohol_free", "Alcohol-Free", "alcohol_free", "guab:beer");
    }

    private void addBrand(String id, String displayName, String styleId, String itemModel) {
        BeerStyle style = styles.get(styleId);
        if (style != null) {
            brands.put(id, new BeerBrand(id, displayName, style, itemModel));
        }
    }

    public BeerStyle find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return styles.get(normalise(id));
    }

    public BeerStyle byId(String id) {
        BeerStyle style = find(id);
        return style == null ? house() : style;
    }

    public BeerStyle house() {
        BeerStyle configured = styles.get(normalise(plugin.getConfig().getString("beer.house-style", "lager")));
        if (configured != null) {
            return configured;
        }
        return styles.values().iterator().next();
    }

    public BeerStyle randomHouse() {
        List<BeerStyle> all = all();
        if (all.isEmpty()) {
            return house();
        }
        ConfigurationSection weights = plugin.getConfig().getConfigurationSection("beer.house-weights");
        if (weights == null) {
            return all.get(ThreadLocalRandom.current().nextInt(all.size()));
        }

        double total = 0;
        Map<BeerStyle, Double> weighted = new LinkedHashMap<>();
        for (BeerStyle style : all) {
            double weight = Math.max(0.0, weights.getDouble(style.id(), 1.0));
            if (weight > 0) {
                weighted.put(style, weight);
                total += weight;
            }
        }
        if (total <= 0) {
            return house();
        }
        double roll = ThreadLocalRandom.current().nextDouble(total);
        for (Map.Entry<BeerStyle, Double> entry : weighted.entrySet()) {
            roll -= entry.getValue();
            if (roll <= 0) {
                return entry.getKey();
            }
        }
        return weighted.keySet().iterator().next();
    }

    public List<BeerStyle> all() {
        return List.copyOf(styles.values());
    }

    public List<BeerBrand> allBrands() {
        return List.copyOf(brands.values());
    }

    public BeerBrand findBrand(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return brands.get(normalise(id));
    }

    public BeerBrand randomBrand(BeerStyle style) {
        List<BeerBrand> candidates = brands.values().stream()
                .filter(brand -> brand.style().id().equals(style.id()))
                .toList();
        if (candidates.isEmpty()) {
            return brands.isEmpty() ? null : brands.values().iterator().next();
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    public BeerBrand houseBrand() {
        return randomBrand(house());
    }

    public BeerBrand randomHouseBrand() {
        return randomBrand(randomHouse());
    }

    /** Accepts either a brand id or a style id (styles pick a random brand). */
    public BeerBrand brandFor(String id) {
        BeerBrand brand = findBrand(id);
        if (brand != null) {
            return brand;
        }
        BeerStyle style = find(id);
        return style == null ? null : randomBrand(style);
    }

    private BeerStyle read(ConfigurationSection section, BeerStyle fallback) {
        if (section == null) {
            return fallback;
        }
        String displayName = section.getString("display-name", fallback.displayName());
        double abv = Math.clamp(section.getDouble("abv", fallback.abv()), 0.0, 20.0);
        int volume = Math.clamp(section.getInt("volume-ml", fallback.volumeMl()), 1, 2000);
        int price = Math.clamp(section.getInt("price", fallback.price()), 0, 1_000_000);
        String model = section.getString("model", fallback.itemModel());
        List<String> names = section.isList("names") ? section.getStringList("names") : fallback.names();
        List<String> messages = section.isList("messages")
                ? section.getStringList("messages") : fallback.messages();
        List<ItemsConfig.BeerEffect> effects = section.isList("effects")
                ? ItemsConfig.parseEffects(section.getList("effects")) : fallback.effects();

        if (names.isEmpty()) {
            names = fallback.names();
        }
        if (messages.isEmpty()) {
            messages = fallback.messages();
        }
        if (effects.isEmpty()) {
            effects = fallback.effects();
        }
        return new BeerStyle(fallback.id(), displayName, abv, volume, price, model,
                fallback.colour(), List.copyOf(names), List.copyOf(messages), List.copyOf(effects));
    }

    private Map<String, BeerStyle> defaults() {
        Map<String, BeerStyle> map = new LinkedHashMap<>();
        List<ItemsConfig.BeerEffect> houseEffects = plugin.config().beerEffects();
        List<String> houseNames = plugin.getConfig().getStringList("beer.names");
        if (houseNames.isEmpty()) {
            houseNames = List.of("Stella Artois", "Carling", "Foster's", "San Miguel", "Peroni",
                    "Birra Moretti", "Heineken", "Corona", "Budweiser");
        }
        List<String> houseMessages = plugin.getConfig().getStringList("beer.messages");
        if (houseMessages.isEmpty()) {
            houseMessages = sharedMessages("Cheap beer, expensive regrets.",
                    "The carpet really ties the room together.",
                    "You order at the bar. Nobody comes.",
                    "A true Spoons classic.");
        }

        map.put("lager", new BeerStyle(
                "lager", "Lager", 4.2, 568, 1, "guab:beer", NamedTextColor.GOLD,
                houseNames, houseMessages, houseEffects));

        map.put("stout", new BeerStyle(
                "stout", "Stout", 4.1, 568, 2, "guab:beer", NamedTextColor.DARK_RED,
                List.of("Guinness", "Murphy's", "Beamish"),
                sharedMessages("The black stuff. Smooth as a receipt.",
                        "You feel a sudden urge to talk about rugby.",
                        "There is a shamrock in the foam, allegedly."),
                List.of(fx(PotionEffectType.NAUSEA, 8, 0),
                        fx(PotionEffectType.SLOWNESS, 6, 0))));

        map.put("ale", new BeerStyle(
                "ale", "Ale", 4.5, 568, 2, "guab:beer", NamedTextColor.GREEN,
                List.of("London Pride", "Doom Bar", "Timothy Taylor Landlord", "Greene King IPA"),
                sharedMessages("Warm, flat and proud of it.",
                        "You develop strong opinions about hops.",
                        "A proper pint for a proper pub."),
                List.of(fx(PotionEffectType.NAUSEA, 5, 0),
                        fx(PotionEffectType.SPEED, 20, 0))));

        map.put("cider", new BeerStyle(
                "cider", "Cider", 4.5, 568, 2, "guab:beer", NamedTextColor.YELLOW,
                List.of("Strongbow", "Thatchers Gold", "Aspall", "Old Mout"),
                sharedMessages("Dangerously easy to drink.",
                        "You can hear the orchard from here.",
                        "That is not a fruit portion."),
                List.of(fx(PotionEffectType.NAUSEA, 7, 0),
                        fx(PotionEffectType.SPEED, 12, 0))));

        map.put("alcohol_free", new BeerStyle(
                "alcohol_free", "Alcohol-Free", 0.0, 568, 1, "guab:beer", NamedTextColor.AQUA,
                List.of("Beck's Blue", "Heineken 0.0", "Guinness 0.0"),
                sharedMessages("All of the pub, none of the hangover.",
                        "Designated driver energy.",
                        "You still feel left out of the round."),
                List.of(fx(PotionEffectType.REGENERATION, 4, 0))));

        return map;
    }

    private static List<String> sharedMessages(String... extra) {
        List<String> messages = new ArrayList<>();
        messages.add("You feel mildly Wetherspoons.");
        messages.add("That is one pint closer to closing time.");
        messages.addAll(List.of(extra));
        return List.copyOf(messages);
    }

    private static ItemsConfig.BeerEffect fx(PotionEffectType type, int seconds, int amplifier) {
        return new ItemsConfig.BeerEffect(type, Math.max(1, seconds) * 20, Math.max(0, amplifier));
    }

    private static String normalise(String id) {
        return id == null ? "lager" : id.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
    }
}
