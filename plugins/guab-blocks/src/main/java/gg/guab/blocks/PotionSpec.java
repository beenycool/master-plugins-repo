package gg.guab.blocks;

import org.bukkit.potion.PotionEffectType;

public record PotionSpec(PotionEffectType type, int durationTicks, int amplifier) {

    public String name() {
        return type.getKey().getKey().replace('_', ' ');
    }
}
