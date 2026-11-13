package gg.guab.marriage;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable, validated view of config.yml. Every numeric value is clamped so a
 * typo cannot create a negative cooldown, an absurd cost or an unlimited reach.
 */
public record MarriageConfig(
        boolean enabled,
        int weddingCost,
        boolean weddingAnnounce,
        int proposalTimeoutSeconds,
        int proposalCooldownSeconds,
        int divorceConfirmSeconds,
        int divorceCost,
        int teleportCooldownSeconds,
        int teleportWarmupSeconds,
        int homeCooldownSeconds,
        int kissCooldownSeconds,
        double kissRange,
        int giftCooldownSeconds,
        double giftRange,
        boolean pvpDefault,
        boolean ringEnabled,
        int ringCooldownSeconds) {

    public static final int MAX_COST = 1_000_000_000;
    public static final int MAX_COOLDOWN_SECONDS = 86_400;
    public static final int MAX_PROPOSAL_SECONDS = 3_600;
    public static final double MIN_RANGE = 0.5;
    public static final double MAX_RANGE = 32.0;

    public static MarriageConfig load(ConfigurationSection cfg) {
        return new MarriageConfig(
                cfg.getBoolean("enabled", true),
                cost(cfg, "wedding.cost", 250),
                cfg.getBoolean("wedding.announce", true),
                Math.clamp(cfg.getInt("proposal.timeout-seconds", 60), 1, MAX_PROPOSAL_SECONDS),
                cooldown(cfg, "proposal.cooldown-seconds", 30),
                Math.clamp(cfg.getInt("divorce.confirm-seconds", 30), 1, MAX_PROPOSAL_SECONDS),
                cost(cfg, "divorce.cost", 100),
                cooldown(cfg, "teleport.cooldown-seconds", 60),
                cooldown(cfg, "teleport.warmup-seconds", 0),
                cooldown(cfg, "home.cooldown-seconds", 60),
                cooldown(cfg, "kiss.cooldown-seconds", 30),
                range(cfg, "kiss.range", 4.0),
                cooldown(cfg, "gift.cooldown-seconds", 10),
                range(cfg, "gift.range", 5.0),
                cfg.getBoolean("pvp.default", true),
                cfg.getBoolean("ring.enabled", true),
                cooldown(cfg, "ring.cooldown-seconds", 300));
    }

    public long weddingCostLong() {
        return weddingCost;
    }

    public long divorceCostLong() {
        return divorceCost;
    }

    public long proposalTimeoutMillis() {
        return proposalTimeoutSeconds * 1000L;
    }

    public long proposalCooldownMillis() {
        return proposalCooldownSeconds * 1000L;
    }

    public long divorceConfirmMillis() {
        return divorceConfirmSeconds * 1000L;
    }

    public long teleportCooldownMillis() {
        return teleportCooldownSeconds * 1000L;
    }

    public long homeCooldownMillis() {
        return homeCooldownSeconds * 1000L;
    }

    public long kissCooldownMillis() {
        return kissCooldownSeconds * 1000L;
    }

    public long giftCooldownMillis() {
        return giftCooldownSeconds * 1000L;
    }

    public long ringCooldownMillis() {
        return ringCooldownSeconds * 1000L;
    }

    private static int cost(ConfigurationSection cfg, String path, int fallback) {
        return Math.clamp(cfg.getInt(path, fallback), 0, MAX_COST);
    }

    private static int cooldown(ConfigurationSection cfg, String path, int fallback) {
        return Math.clamp(cfg.getInt(path, fallback), 0, MAX_COOLDOWN_SECONDS);
    }

    private static double range(ConfigurationSection cfg, String path, double fallback) {
        double value = cfg.getDouble(path, fallback);
        if (!Double.isFinite(value)) {
            value = fallback;
        }
        return Math.clamp(value, MIN_RANGE, MAX_RANGE);
    }
}
