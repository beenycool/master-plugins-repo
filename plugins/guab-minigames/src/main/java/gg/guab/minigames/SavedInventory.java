package gg.guab.minigames;

import org.bukkit.inventory.ItemStack;

/**
 * A snapshot of everything a fighter owned before they joined the battle pit.
 * The arrays are cloned on capture and restore so the live inventory can never
 * mutate a stored snapshot.
 */
record SavedInventory(
        ItemStack[] contents,
        ItemStack[] armor,
        ItemStack offHand,
        ItemStack cursor,
        int heldSlot,
        int level,
        float exp) {

    SavedInventory copy() {
        return new SavedInventory(clone(contents), clone(armor),
                offHand == null ? null : offHand.clone(),
                cursor == null ? null : cursor.clone(),
                heldSlot, level, exp);
    }

    private static ItemStack[] clone(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int index = 0; index < items.length; index++) {
            copy[index] = items[index] == null ? null : items[index].clone();
        }
        return copy;
    }
}
