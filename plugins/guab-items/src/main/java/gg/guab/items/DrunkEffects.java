package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
        // Tier 4+ no longer blinds or makes the room spin. An amber pub glow
        // plus a gentle horizontal particle wave sells the state without taking
        // the dartboard (or the bar) away from the player.
        WRECKED(4, DrunkTiers.WRECKED, "Wrecked", NamedTextColor.DARK_RED, List.of(
                new EffectSpec(PotionEffectType.SLOWNESS, 30, 0))),
        ABSOLUTELY_WETHERSPOONS(5, DrunkTiers.ABSOLUTELY_WETHERSPOONS, "Absolutely Wetherspoons", NamedTextColor.DARK_PURPLE, List.of(
                new EffectSpec(PotionEffectType.SLOWNESS, 30, 1)));

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

    /** When each player is next due a short tier 4+ blink, in epoch millis. */
    private static final Map<UUID, Long> NEXT_BLINK = new HashMap<>();

    private static final List<String> BLINK_MESSAGES = List.of(
            "The room blinks. Try to stay upright.",
            "You hiccup. The floor sways politely.",
            "Everything goes dark for a second. Probably fine.",
            "The carpet is definitely moving.",
            "You forget which pint you are on.");

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
        if (tier.level() >= Tier.WRECKED.level()) {
            maybeHiccup(player, tier);
        }
    }

    /**
     * The tier 4+ signature look: a warm amber glow around the player and a
     * slow horizontal dust wave at head height. It is purely visual, so the
     * player can still aim at darts, read the bar and walk home.
     */
    static void ambient(Player player, Tier tier, int tickCounter) {
        if (tier.level() < Tier.WRECKED.level() || player.getWorld() == null) {
            return;
        }
        if (tickCounter % 2 != 0) {
            return;
        }
        long phase = tickCounter / 2L;
        var world = player.getWorld();
        var base = player.getLocation();
        int points = tier.level() >= Tier.ABSOLUTELY_WETHERSPOONS.level() ? 10 : 7;
        double radius = tier.level() >= Tier.ABSOLUTELY_WETHERSPOONS.level() ? 2.2 : 1.7;
        for (int index = 0; index < points; index++) {
            double angle = phase * 0.18 + (Math.PI * 2.0 * index / points);
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double y = 1.05 + Math.sin(angle * 2.0 + phase * 0.12) * 0.42;
            world.spawnParticle(Particle.DUST, base.clone().add(x, y, z), 1,
                    0.05, 0.05, 0.05, 0.0,
                    new Particle.DustOptions(Color.fromRGB(0xF2A93B), 0.85f));
        }
        // A soft amber aura at eye level: enough to tint the player's view with
        // motes without a blindness/darkness effect.
        world.spawnParticle(Particle.DUST, base.clone().add(0, 1.55, 0), 2,
                0.55, 0.35, 0.55, 0.0,
                new Particle.DustOptions(Color.fromRGB(0xFFC46B), 1.15f));
        if (tickCounter % 160 == 0) {
            world.spawnParticle(Particle.END_ROD, base.clone().add(0, 1.8, 0), 2,
                    0.25, 0.15, 0.25, 0.01);
        }
    }

    /** Occasional flavour, not a screen blackout. */
    private static void maybeHiccup(Player player, Tier tier) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < NEXT_BLINK.getOrDefault(id, 0L)) {
            return;
        }
        boolean worst = tier.level() >= Tier.ABSOLUTELY_WETHERSPOONS.level();
        NEXT_BLINK.put(id, now + (worst ? 11_000L : 15_000L));
        if (ThreadLocalRandom.current().nextInt(100) < 80) {
            player.sendMessage(Component.text(
                    BLINK_MESSAGES.get(ThreadLocalRandom.current().nextInt(BLINK_MESSAGES.size())),
                    NamedTextColor.GOLD));
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
        NEXT_BLINK.remove(player.getUniqueId());
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
        NEXT_BLINK.remove(player);
    }
}
