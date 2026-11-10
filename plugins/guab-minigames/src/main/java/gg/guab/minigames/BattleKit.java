package gg.guab.minigames;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** The shared battle pit PvP loadout. */
final class BattleKit {

    private static final String TAG = "battle-kit-item";

    private BattleKit() {
    }

    static NamespacedKey tagKey(GuabMinigamesPlugin plugin) {
        return new NamespacedKey(plugin, TAG);
    }

    /** Wipe every slot a player can see, then hand out a fresh loadout. */
    static void resetAndGive(GuabMinigamesPlugin plugin, Player player) {
        clear(player);
        give(plugin, player);
    }

    static void clear(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(null);
        player.setItemOnCursor(null);
    }

    static void give(GuabMinigamesPlugin plugin, Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.setHelmet(tag(plugin, new ItemStack(Material.IRON_HELMET)));
        inventory.setChestplate(tag(plugin, new ItemStack(Material.IRON_CHESTPLATE)));
        inventory.setLeggings(tag(plugin, new ItemStack(Material.IRON_LEGGINGS)));
        inventory.setBoots(tag(plugin, new ItemStack(Material.IRON_BOOTS)));
        inventory.setItemInOffHand(tag(plugin, new ItemStack(Material.SHIELD)));

        inventory.setItem(0, tag(plugin, new ItemStack(Material.IRON_SWORD)));
        inventory.setItem(1, tag(plugin, new ItemStack(Material.BOW)));
        inventory.setItem(2, tag(plugin, new ItemStack(Material.ARROW, 32)));
        inventory.setItem(3, tag(plugin, new ItemStack(Material.GOLDEN_APPLE, 4)));
        inventory.setItem(4, tag(plugin, new ItemStack(Material.COOKED_BEEF, 16)));
        inventory.setItem(5, tag(plugin, new ItemStack(Material.COBBLESTONE, 32)));
        inventory.setItem(6, tag(plugin, new ItemStack(Material.IRON_PICKAXE)));
        inventory.setItem(7, tag(plugin, new ItemStack(Material.IRON_AXE)));
    }

    static boolean isKitItem(GuabMinigamesPlugin plugin, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(tagKey(plugin), PersistentDataType.BYTE);
    }

    private static ItemStack tag(GuabMinigamesPlugin plugin, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(tagKey(plugin), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }
}
