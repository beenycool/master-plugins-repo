package gg.guab.items;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Central table for the pub's drunk tiers, the universal drunk effects and the
 * signature effect each style adds once you are past sober.
 */
final class DrunkEffects {

    private DrunkEffects() {
    }

    /** One potion effect in a tier. Durations are refreshed while the tier holds. */
    record EffectSpec(PotionEffectType type, int seconds, int amplifier) {
        void apply(Player player) {
            player.addPotionEffect(new PotionEffect(type, seconds * 20, amplifier,
                    false, true, true));
        }
    }

    /** The drunk ladder, ordered from sober to absolutely Wetherspoons. */
    enum Tier {
        SOBER(0, DrunkTiers.SOBER, "Sober", NamedTextColor.GRAY, List.of()),
        TIPSY(1, DrunkTiers.TIPSY, "Tipsy", NamedTextColor.YELLOW, List.of()),
        MERRY(2, DrunkTiers.MERRY, "Merry", NamedTextColor.GOLD, List.of(
                new EffectSpec(PotionEffectType.SLOWNESS, 30, 0))),
        DRUNK(3, DrunkTiers.DRUNK, "Drunk", NamedTextColor.RED, List.of(
                new EffectSpec(PotionEffectType.SLOWNESS, 30, 0),
                new EffectSpec(PotionEffectType.NAUSEA, 30, 0))),
        WRECKED(4, DrunkTiers.WRECKED, "Wrecked", NamedTextColor.DARK_RED, List.of(
                new EffectSpec(PotionEffectType.SLOWNESS, 30, 0),
                new EffectSpec(PotionEffectType.NAUSEA, 30, 0),
                new EffectSpec(PotionEffectType.BLINDNESS, 30, 0))),
        ABSOLUTELY_WETHERSPOONS(5, DrunkTiers.ABSOLUTELY_WETHERSPOONS, "Absolutely Wetherspoons", NamedTextColor.DARK_PURPLE, List.of(
                new EffectSpec(PotionEffectType.SLOWNESS, 30, 1),
                new EffectSpec(PotionEffectType.NAUSEA, 30, 1),
                new EffectSpec(PotionEffectType.BLINDNESS, 30, 0),
                new EffectSpec(PotionEffectType.DARKNESS, 30, 0)));

        private final int level;
        private final double minimumUnits;
        private final String label;
        private final NamedTextColor colour;
        private final List<EffectSpec> effects;

        Tier(int level, double minimumUnits, String label, NamedTextColor colour,
                List<EffectSpec> effects) {
            this.level = level;
            this.minimumUnits = minimumUnits;
            this.label = label;
            this.colour = colour;
            this.effects = effects;
        }

        int level() {
            return level;
        }

        double minimumUnits() {
            return minimumUnits;
        }

        String label() {
            return label;
        }

        NamedTextColor colour() {
            return colour;
        }

        List<EffectSpec> effects() {
            return effects;
        }
    }

    /**
     * What each style adds once you are at least tipsy. This is what makes a
     * lager feel different from a stout or a cider.
     */
    private static final Map<String, EffectSpec> SIGNATURES = Map.of(
            "lager", new EffectSpec(PotionEffectType.SPEED, 10, 0),
            "stout", new EffectSpec(PotionEffectType.RESISTANCE, 10, 0),
            "ale", new EffectSpec(PotionEffectType.STRENGTH, 10, 0),
            "cider", new EffectSpec(PotionEffectType.JUMP_BOOST, 10, 0));

    private static final List<EffectSpec> HANGOVER = List.of(
            new EffectSpec(PotionEffectType.NAUSEA, 20, 0),
            new EffectSpec(PotionEffectType.SLOWNESS, 15, 0),
            new EffectSpec(PotionEffectType.WEAKNESS, 15, 0));

    /** Only the effects this plugin actually applied, per player. */
    private static final Map<UUID, Set<PotionEffectType>> APPLIED = new HashMap<>();

    static Tier forUnits(double units) {
        Tier[] tiers = Tier.values();
        return tiers[DrunkTiers.levelFor(units)];
    }

    /**
     * Applies a tier plus the latest drink's signature effect. The previous
     * tier's effects are cleared on a tier change so they do not linger.
     */
    static void apply(Player player, Tier tier, Tier previous, String styleId) {
        if (tier == Tier.SOBER) {
            clear(player);
            return;
        }
        if (previous != tier) {
            clear(player);
        }
        for (EffectSpec effect : tier.effects()) {
            applyEffect(player, effect);
        }
        if (tier.level() >= Tier.TIPSY.level() && styleId != null) {
            EffectSpec signature = SIGNATURES.get(styleId);
            if (signature != null) {
                applyEffect(player, signature);
            }
        }
    }

    static void applyHangover(Player player) {
        for (EffectSpec effect : HANGOVER) {
            applyEffect(player, effect);
        }
    }

    private static void applyEffect(Player player, EffectSpec effect) {
        effect.apply(player);
        APPLIED.computeIfAbsent(player.getUniqueId(),
                id -> new HashSet<>()).add(effect.type());
    }

    /**
     * Removes only the effects this plugin applied. A Speed potion from another
     * source is left alone unless the pub itself handed out Speed.
     */
    static void clear(Player player) {
        Set<PotionEffectType> applied = APPLIED.remove(player.getUniqueId());
        if (applied == null) {
            return;
        }
        for (PotionEffectType type : applied) {
            player.removePotionEffect(type);
        }
    }

    static void forget(UUID player) {
        APPLIED.remove(player);
    }
}
