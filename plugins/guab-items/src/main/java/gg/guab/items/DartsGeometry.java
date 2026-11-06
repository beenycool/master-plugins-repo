package gg.guab.items;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Real dartboard geometry: the player's eye ray decides where the dart lands,
 * then the board is split into the twenty standard sectors and scoring rings.
 */
public final class DartsGeometry {

    /** Clockwise from the top, exactly as a real dartboard is numbered. */
    private static final int[] SECTORS = {
            20, 1, 18, 4, 13, 6, 10, 15, 2, 17, 3, 19, 7, 16, 8, 11, 14, 9, 12, 5
    };

    private DartsGeometry() {
    }

    /** Absolute centre of the board face. */
    public static Location centre(Location blockLocation, BlockFace face) {
        Vector normal = face.getDirection();
        Vector centre = blockLocation.toVector()
                .add(new Vector(0.5, 0.5, 0.5))
                .add(normal.clone().multiply(0.5));
        return centre.toLocation(blockLocation.getWorld());
    }

    /**
     * Scores an interaction point on the board face. {@code wobbleSigma} is the
     * Gaussian aim error in board radii, which lets a few pints make the board
     * feel considerably smaller.
     */
    public static Hit score(Location centre, BlockFace face, double radius, Location point,
                            DartsConfig config, double wobbleSigma) {
        Vector normal = face.getDirection();
        Vector right = new Vector(0, 1, 0).crossProduct(normal);
        Vector delta = point.toVector().subtract(centre.toVector());

        double x = delta.dot(right) / radius;
        double y = delta.getY() / radius;

        if (wobbleSigma > 0.0) {
            x += ThreadLocalRandom.current().nextGaussian() * wobbleSigma;
            y += ThreadLocalRandom.current().nextGaussian() * wobbleSigma;
        }

        double distance = Math.hypot(x, y);
        if (distance > config.doubleOuter()) {
            return Hit.miss();
        }
        if (distance <= config.bull()) {
            return new Hit(25, 2, 50, "Bullseye 50", true, x, y);
        }
        if (distance <= config.outerBull()) {
            return new Hit(25, 1, 25, "Outer bull 25", false, x, y);
        }

        double angle = Math.toDegrees(Math.atan2(x, y));
        int index = (int) Math.floor(((angle + 9.0 + 360.0) % 360.0) / 18.0) % SECTORS.length;
        int number = SECTORS[index];

        if (distance >= config.trebleInner() && distance <= config.trebleOuter()) {
            return new Hit(number, 3, number * 3, "T" + number, false, x, y);
        }
        if (distance >= config.doubleInner() && distance <= config.doubleOuter()) {
            return new Hit(number, 2, number * 2, "D" + number, true, x, y);
        }
        return new Hit(number, 1, number, "S" + number, false, x, y);
    }

    /**
     * Diagnostic helper used by the checkout display: how far a point is from
     * the exact centre, in board radii.
     */
    public static double radialDistance(Location centre, BlockFace face, double radius, Location point) {
        Vector normal = face.getDirection();
        Vector right = new Vector(0, 1, 0).crossProduct(normal);
        Vector delta = point.toVector().subtract(centre.toVector());
        double x = delta.dot(right) / radius;
        double y = delta.getY() / radius;
        return Math.hypot(x, y);
    }

    public record Hit(int number, int multiplier, int score, String label, boolean doubleOut,
                      double x, double y) {

        public static Hit miss() {
            return new Hit(0, 0, 0, "Miss", false, 0, 0);
        }

        public boolean isBullseye() {
            return score == 50;
        }
    }
}
