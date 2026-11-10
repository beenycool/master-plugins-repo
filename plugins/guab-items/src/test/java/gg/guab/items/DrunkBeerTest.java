package gg.guab.items;

import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DrunkBeerTest {

    @Test
    void drunkTierThresholdsMatchThePubLadder() {
        assertEquals(0, DrunkTiers.levelFor(0.0));
        assertEquals(0, DrunkTiers.levelFor(2.99));
        assertEquals(1, DrunkTiers.levelFor(3.0));
        assertEquals(2, DrunkTiers.levelFor(5.0));
        assertEquals(3, DrunkTiers.levelFor(7.0));
        assertEquals(4, DrunkTiers.levelFor(9.5));
        assertEquals(5, DrunkTiers.levelFor(13.0));
        assertEquals(5, DrunkTiers.levelFor(99.0));
    }

    @Test
    void minimumUnitsRoundsTripThroughTheSameTable() {
        for (int level = 0; level <= 5; level++) {
            assertEquals(level, DrunkTiers.levelFor(DrunkTiers.minimumUnits(level)));
        }
    }

    @Test
    void beerUnitsComeFromAbvAndVolume() {
        BeerStyle lager = new BeerStyle("lager", "Lager", 4.2, 568, 1,
                NamedTextColor.GOLD, List.of("Stella"), List.of("cheap"), List.of(), false);
        assertEquals(2386, lager.unitsMilli());
        assertEquals(2.386, lager.units(), 0.0001);
        assertEquals("2.4", lager.unitsLabel());

        BeerStyle alcoholFree = new BeerStyle("alcohol_free", "Alcohol-Free", 0.0, 568, 1,
                NamedTextColor.AQUA, List.of(), List.of(), List.of(), false);
        assertEquals(0, alcoholFree.unitsMilli());
        assertEquals("0.0", alcoholFree.unitsLabel());

        BeerBrand stout = new BeerBrand("guinness", "Guinness", lager, "guab:beer_guinness",
                4.1, 568);
        assertEquals(2329, stout.unitsMilli());
    }
}
