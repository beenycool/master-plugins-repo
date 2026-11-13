package gg.guab.marriage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarriageConfigTest {

    @Test
    void emptyConfigUsesTheDocumentedDefaults() {
        MarriageConfig config = MarriageConfig.load(new YamlConfiguration());

        assertTrue(config.enabled());
        assertEquals(250, config.weddingCost());
        assertTrue(config.weddingAnnounce());
        assertEquals(60, config.proposalTimeoutSeconds());
        assertEquals(30, config.proposalCooldownSeconds());
        assertEquals(30, config.divorceConfirmSeconds());
        assertEquals(100, config.divorceCost());
        assertEquals(60, config.teleportCooldownSeconds());
        assertEquals(0, config.teleportWarmupSeconds());
        assertEquals(60, config.homeCooldownSeconds());
        assertEquals(30, config.kissCooldownSeconds());
        assertEquals(4.0, config.kissRange(), 0.0001);
        assertEquals(10, config.giftCooldownSeconds());
        assertEquals(5.0, config.giftRange(), 0.0001);
        assertTrue(config.pvpDefault());
        assertTrue(config.ringEnabled());
        assertEquals(300, config.ringCooldownSeconds());
    }

    @Test
    void costsAndCooldownsAreClamped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("wedding.cost", -5);
        yaml.set("divorce.cost", Integer.MAX_VALUE);
        yaml.set("proposal.timeout-seconds", 0);
        yaml.set("divorce.confirm-seconds", -2);
        yaml.set("teleport.cooldown-seconds", -50);
        yaml.set("teleport.warmup-seconds", -1);
        yaml.set("ring.cooldown-seconds", 999_999);

        MarriageConfig config = MarriageConfig.load(yaml);

        assertEquals(0, config.weddingCost());
        assertEquals(MarriageConfig.MAX_COST, config.divorceCost());
        assertEquals(1, config.proposalTimeoutSeconds());
        assertEquals(1, config.divorceConfirmSeconds());
        assertEquals(0, config.teleportCooldownSeconds());
        assertEquals(0, config.teleportWarmupSeconds());
        assertEquals(MarriageConfig.MAX_COOLDOWN_SECONDS, config.ringCooldownSeconds());
    }

    @Test
    void rangesAreClampedAndNonFiniteFallsBack() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("kiss.range", 999.0);
        yaml.set("gift.range", 0.0);
        MarriageConfig high = MarriageConfig.load(yaml);
        assertEquals(MarriageConfig.MAX_RANGE, high.kissRange(), 0.0001);
        assertEquals(MarriageConfig.MIN_RANGE, high.giftRange(), 0.0001);

        yaml.set("kiss.range", Double.NaN);
        MarriageConfig fallback = MarriageConfig.load(yaml);
        assertEquals(4.0, fallback.kissRange(), 0.0001);
    }

    @Test
    void booleanSwitchesAreReadAndMillisHelpersMatchSeconds() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enabled", false);
        yaml.set("wedding.announce", false);
        yaml.set("pvp.default", false);
        yaml.set("ring.enabled", false);
        yaml.set("proposal.timeout-seconds", 12);
        yaml.set("divorce.confirm-seconds", 7);
        yaml.set("teleport.cooldown-seconds", 3);
        yaml.set("home.cooldown-seconds", 4);
        yaml.set("kiss.cooldown-seconds", 5);
        yaml.set("gift.cooldown-seconds", 6);
        yaml.set("ring.cooldown-seconds", 8);

        MarriageConfig config = MarriageConfig.load(yaml);

        assertFalse(config.enabled());
        assertFalse(config.weddingAnnounce());
        assertFalse(config.pvpDefault());
        assertFalse(config.ringEnabled());
        assertEquals(12_000L, config.proposalTimeoutMillis());
        assertEquals(7_000L, config.divorceConfirmMillis());
        assertEquals(3_000L, config.teleportCooldownMillis());
        assertEquals(4_000L, config.homeCooldownMillis());
        assertEquals(5_000L, config.kissCooldownMillis());
        assertEquals(6_000L, config.giftCooldownMillis());
        assertEquals(8_000L, config.ringCooldownMillis());
    }
}
