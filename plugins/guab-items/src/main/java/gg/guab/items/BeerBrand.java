package gg.guab.items;

/**
 * A specific branded pint. The style controls ABV, price and effects; the brand
 * controls the inventory name and the resource-pack model.
 */
public record BeerBrand(String id, String displayName, BeerStyle style, String itemModel) {
}
