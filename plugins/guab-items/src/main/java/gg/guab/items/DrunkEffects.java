package gg.guab.items;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * Central table for the pub's drunk tiers and the potion effects each one
 * applies. Keeping the definitions here means {@link PubManager} only has to
 * decide which tier a player is in and then ask this class to apply it.
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
        SOBER(0, 0.0, "Sober", NamedTextColor.GRAY, List.of()),
        TIPSY(1, 1.0, "Tipsy", NamedTextColor.YELLOW, List.of(
                new EffectSpec(PotionEffectType.NAUSEA, 30, 0))),
        MERRY(2, 2.5, "Merry", NamedTextColor.GOLD, List.of(
                new EffectSpec(PotionEffectType.NAUSEA, 30, 0),
                new EffectSpec(PotionEffectType.SLOWNESS, 30, 0))),
        DRUNK(3, 4.5, "Drunk", NamedTextColor.RED, List.of(
                new EffectSpec(PotionEffectType.NAUSEA, 30, 0),
                new EffectSpec(PotionEffectType.SLOWNESS, 30, 0))),
        WRECKED(4, 7.0, "Wrecked", NamedTextColor.DARK_RED, List.of(
                new EffectSpec(PotionEffectType.NAUSEA, 30, 1),
                new EffectSpec(PotionEffectType.SLOWNESS, 30, 0),
                new EffectSpec(PotionEffectType.BLINDNESS, 30, 0))),
        ABSOLUTELY_WETHERSPOONS(5, 10.0, "Absolutely Wetherspoons", NamedTextColor.DARK_PURPLE, List.of(
                new EffectSpec(PotionEffectType.NAUSEA, 30, 1),
                new EffectSpec(PotionEffectType.SLOWNESS, 30, 1),
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

    private static final List<EffectSpec> HANGOVER = List.of(
            new EffectSpec(PotionEffectType.NAUSEA, 20, 0),
            new EffectSpec(PotionEffectType.SLOWNESS, 15, 0),
            new EffectSpec(PotionEffectType.WEAKNESS, 15, 0));

    static Tier forUnits(double units) {
        Tier result = Tier.SOBER;
        for (Tier tier : Tier.values()) {
            if (units >= tier.minimumUnits()) {
                result = tier;
            }
        }
        return result;
    }

    /**
     * Applies a tier. The previous tier is cleared only when it changes so the
     * same effect can be refreshed without blinking off between ticks.
     */
    static void apply(Player player, Tier tier, Tier previous) {
        if (tier == Tier.SOBER) {
            clear(player);
            return;
        }
        if (previous != tier) {
            clear(player);
        }
        for (EffectSpec effect : tier.effects()) {
            effect.apply(player);
        }
    }

    static void applyHangover(Player player) {
        for (EffectSpec effect : HANGOVER) {
            effect.apply(player);
        }
    }

    /** Removes every effect this class can apply, including hangover effects. */
    static void clear(Player player) {
        for (Tier tier : Tier.values()) {
            for (EffectSpec effect : tier.effects()) {
                player.removePotionEffect(effect.type());
            }
        }
        for (EffectSpec effect : HANGOVER) {
            player.removePotionEffect(effect.type());
        }
    }
}
