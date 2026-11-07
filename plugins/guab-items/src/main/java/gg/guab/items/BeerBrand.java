package gg.guab.items;

import java.util.Locale;

/**
 * A specific branded pint. The style controls the price, colour and any shared
 * effects; the brand controls the real ABV, unit count, inventory name and
 * resource-pack model.
 */
public record BeerBrand(String id, String displayName, BeerStyle style, String itemModel,
                        double abv, int volumeMl) {

    public int unitsMilli() {
        return (int) Math.round(abv * volumeMl);
    }

    public double units() {
        return unitsMilli() / 1000.0;
    }

    public String unitsLabel() {
        return String.format(Locale.ROOT, "%.1f", units());
    }
}
