package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Creates tagged items and answers the only question that matters for security:
 * does this stack carry our persistent data tag?
 */
public final class ItemFactory {

    public static final String TYPE_KEY = "item_type";

    private final NamespacedKey typeKey;

    public ItemFactory(GuabItemsPlugin plugin) {
        this.typeKey = new NamespacedKey(plugin, TYPE_KEY);
    }

    public ItemStack create(ItemType type, int amount) {
        int max = Math.max(1, type.material().getMaxStackSize());
        ItemStack stack = new ItemStack(type.material(), Math.clamp(amount, 1, max));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(type.displayName());
        meta.lore(type.lore());
        applyModel(meta, type);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
        stack.setItemMeta(meta);
        return stack;
    }

    private void applyModel(ItemMeta meta, ItemType type) {
        if (type.itemModel() == null) {
            return;
        }
        NamespacedKey model = NamespacedKey.fromString(type.itemModel());
        if (model != null) {
            meta.setItemModel(model);
        }
    }

    /**
     * Returns the tagged type, or null when the stack is a normal/renamed item.
     * Material and display name are deliberately not trusted.
     */
    public ItemType typeOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        String id = meta.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return ItemType.byId(id);
    }

    public boolean isTagged(ItemStack stack) {
        return typeOf(stack) != null;
    }

    /**
     * True when a plain item was renamed to one of our display names. It is still
     * inert, but we strip the name so it cannot be used to impersonate a real item.
     */
    public boolean hasFakeName(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta() || isTagged(stack)) {
            return false;
        }
        Component name = stack.getItemMeta().displayName();
        if (name == null) {
            return false;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(name).trim();
        for (ItemType type : ItemType.values()) {
            if (plain.equalsIgnoreCase(type.plainName())) {
                return true;
            }
        }
        return false;
    }

    public void stripFakeName(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(null);
        meta.lore(null);
        stack.setItemMeta(meta);
    }

    public void stripTag(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().remove(typeKey);
        stack.setItemMeta(meta);
    }

    /**
     * Re-applies the canonical name, lore and tag to a genuine item. Used by the
     * anvil guard so a real item cannot be renamed into a different-looking one.
     */
    public ItemStack canonicalize(ItemStack stack) {
        ItemType type = typeOf(stack);
        if (type == null) {
            return stack;
        }
        ItemStack copy = stack.clone();
        ItemMeta meta = copy.getItemMeta();
        meta.displayName(type.displayName());
        meta.lore(type.lore());
        applyModel(meta, type);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
        copy.setItemMeta(meta);
        return copy;
    }
}
