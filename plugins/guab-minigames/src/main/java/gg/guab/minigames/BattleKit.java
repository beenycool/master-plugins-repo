package gg.guab.minigames;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** The shared battle pit PvP loadout. */
final class BattleKit {

    private BattleKit() {
    }

    static void give(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.setHelmet(new ItemStack(Material.IRON_HELMET));
        inventory.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        inventory.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
        inventory.setBoots(new ItemStack(Material.IRON_BOOTS));
        inventory.setItemInOffHand(new ItemStack(Material.SHIELD));

        inventory.setItem(0, new ItemStack(Material.IRON_SWORD));
        inventory.setItem(1, new ItemStack(Material.BOW));
        inventory.setItem(2, new ItemStack(Material.ARROW, 32));
        inventory.setItem(3, new ItemStack(Material.GOLDEN_APPLE, 4));
        inventory.setItem(4, new ItemStack(Material.COOKED_BEEF, 16));
        inventory.setItem(5, new ItemStack(Material.COBBLESTONE, 32));
        inventory.setItem(6, new ItemStack(Material.IRON_PICKAXE));
        inventory.setItem(7, new ItemStack(Material.IRON_AXE));
    }
}
