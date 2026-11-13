package gg.guab.items;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies the drunk aim wobble to the actual arrow in the darts room. Scoring
 * then uses the real impact point with no extra mathematical wobble, so a
 * perfect-looking bullseye always scores as one.
 */
public final class DartsShotListener implements Listener {

    private static final double MIN_DISTANCE = 1.0;

    private final GuabItemsPlugin plugin;

    public DartsShotListener(GuabItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        DartsRoom room = plugin.darts().room();
        ItemStack bow = event.getBow();
        if (!room.isInRoom(player)
                || !room.isDartsBow(bow)
                || !plugin.darts().isPlaying(player.getUniqueId())) {
            return;
        }
        double sigma = plugin.darts().wobbleSigma(player.getUniqueId());
        if (sigma <= 0.0) {
            return;
        }
        Location centre = room.targetCentre();
        double distance = centre == null
                ? 10.0
                : Math.max(MIN_DISTANCE, player.getEyeLocation().distance(centre));

        Vector velocity = event.getProjectile().getVelocity();
        double speed = velocity.length();
        if (speed < 1.0E-4) {
            return;
        }
        Vector direction = velocity.clone().multiply(1.0 / speed);
        Vector right = new Vector(0, 1, 0).crossProduct(direction);
        if (right.lengthSquared() < 1.0E-8) {
            right = new Vector(1, 0, 0);
        }
        right.normalize();
        Vector up = direction.clone().crossProduct(right).normalize();

        // Sigma is measured in board radii, so convert it into the angular
        // deviation the arrow needs at the distance of the board.
        double scale = sigma * DartsRoom.BOARD_RADIUS / distance;
        double sideNoise = ThreadLocalRandom.current().nextGaussian() * scale;
        double upNoise = ThreadLocalRandom.current().nextGaussian() * scale;
        Vector perturbed = direction.add(right.multiply(sideNoise))
                .add(up.multiply(upNoise))
                .normalize()
                .multiply(speed);
        event.getProjectile().setVelocity(perturbed);
    }
}
