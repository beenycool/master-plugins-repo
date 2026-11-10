package gg.guab.items;

import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A beer on the Wetherspoons menu. The item still stacks by style, but the ABV,
 * flavour text and effects are all driven by this record.
 */
public record BeerStyle(
        String id,
        String displayName,
        double abv,
        int volumeMl,
        int price,
        NamedTextColor colour,
        List<String> names,
        List<String> messages,
        List<ItemsConfig.BeerEffect> effects,
        boolean lethal) {

    public int unitsMilli() {
        return (int) Math.round(abv * volumeMl);
    }

    public double units() {
        return unitsMilli() / 1000.0;
    }

    public String unitsLabel() {
        return String.format(Locale.ROOT, "%.1f", units());
    }

    public boolean alcoholic() {
        return unitsMilli() > 0;
    }

    public String randomName() {
        if (names.isEmpty()) {
            return displayName;
        }
        return names.get(ThreadLocalRandom.current().nextInt(names.size()));
    }

    public String randomMessage() {
        if (messages.isEmpty()) {
            return "You feel mildly Wetherspoons.";
        }
        return messages.get(ThreadLocalRandom.current().nextInt(messages.size()));
    }
}
