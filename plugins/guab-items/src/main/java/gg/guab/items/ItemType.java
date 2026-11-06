package gg.guab.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;

import java.util.List;

/**
 * The prank items this plugin knows how to create and recognise.
 *
 * <p>The display name is only cosmetic. Everything else keys off the persistent
 * data tag written by {@link ItemFactory}, so renaming or crafting a lookalike
 * produces an inert item.</p>
 */
public enum ItemType {

    WIND_CHARGE(
            "wind_charge",
            Material.WIND_CHARGE,
            "Wind Charge",
            NamedTextColor.AQUA,
            List.of(
                    "Right-click to boost yourself forward",
                    "and knock nearby players away.")),

    JUMP_BOOST(
            "jump_boost",
            Material.FEATHER,
            "Jump Boost Feather",
            NamedTextColor.GREEN,
            List.of(
                    "Right-click for permanent Jump Boost.",
                    "Consumed on use.")),

    BEER(
            "beer",
            Material.GLASS_BOTTLE,
            "Beer",
            NamedTextColor.YELLOW,
            List.of(
                    "A pint from Wetherspoons.",
                    "Right-click to drink."),
            "guab:beer");

    private final String id;
    private final Material material;
    private final String plainName;
    private final Component displayName;
    private final List<Component> lore;
    private final String itemModel;

    ItemType(String id, Material material, String plainName, NamedTextColor color, List<String> lore) {
        this(id, material, plainName, color, lore, null);
    }

    ItemType(String id, Material material, String plainName, NamedTextColor color, List<String> lore, String itemModel) {
        this.itemModel = itemModel;
        this.id = id;
        this.material = material;
        this.plainName = plainName;
        this.lore = lore.stream()
                .<Component>map(line -> Component.text(line, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false))
                .toList();
        this.displayName = Component.text(plainName, color)
                .decoration(TextDecoration.ITALIC, false);
    }

    public String id() {
        return id;
    }

    public Material material() {
        return material;
    }

    /** Custom item-model key from the resource pack, or null for a vanilla model. */
    public String itemModel() {
        return itemModel;
    }

    public String plainName() {
        return plainName;
    }

    public Component displayName() {
        return displayName;
    }

    public List<Component> lore() {
        return lore;
    }

    public static ItemType byId(String id) {
        if (id == null) {
            return null;
        }
        for (ItemType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }
}
