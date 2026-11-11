package gg.guab.core;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.ServicePriority;

/**
 * Registers the points ledger as the server's Vault economy. Only touched when the
 * Vault plugin is present, so GuabCore still loads on a vanilla-ish server.
 */
final class VaultHook {

    private VaultHook() {
    }

    static boolean install(GuabCorePlugin plugin, PointsService points) {
        try {
            Economy economy = new VaultEconomy(points);
            plugin.getServer().getServicesManager()
                    .register(Economy.class, economy, plugin, ServicePriority.Highest);
            return true;
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Vault is present but the points economy could not register: " + throwable);
            return false;
        }
    }
}
