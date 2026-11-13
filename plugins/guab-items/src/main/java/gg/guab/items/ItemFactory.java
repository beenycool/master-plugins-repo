package gg.guab.items;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.UseCooldown;
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Creates tagged items and answers the only question that matters for security:
 * does this stack carry our persistent data tag?
 *
 * <p>Beer is also built with 26.3 item components: the drink animation, style
 * effects and cooldown are real data components, not a right-click workaround.</p>
 */
public final class ItemFactory {

    public static final String TYPE_KEY = "item_type";
    public static final String STYLE_KEY = "beer_style";
    public static final String BRAND_KEY = "beer_brand";
    public static final String MILK_KEY = "pub_milk";

    private final GuabItemsPlugin plugin;
    private final NamespacedKey typeKey;
    private final NamespacedKey styleKey;
    private final NamespacedKey brandKey;
    private final NamespacedKey milkKey;

    public ItemFactory(GuabItemsPlugin plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, TYPE_KEY);
        this.styleKey = new NamespacedKey(plugin, STYLE_KEY);
        this.brandKey = new NamespacedKey(plugin, BRAND_KEY);
        this.milkKey = new NamespacedKey(plugin, MILK_KEY);
    }

    public ItemStack create(ItemType type, int amount) {
        if (type == ItemType.BEER) {
            return createBeer(plugin.beer().houseBrand(), amount);
        }
        int max = Math.max(1, type.material().getMaxStackSize());
        ItemStack stack = new ItemStack(type.material(), Math.clamp(amount, 1, max));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(type.displayName());
        meta.lore(type.lore());
        applyModel(meta, type.itemModel());
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
        stack.setItemMeta(meta);
        return stack;
    }

    public ItemStack createMilk() {
        ItemStack milk = new ItemStack(Material.MILK_BUCKET);
        ItemMeta meta = milk.getItemMeta();
        meta.displayName(Component.text("Pint of Milk", NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Sober up.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Clears the drunk meter and potion effects.", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setItemModel(NamespacedKey.fromString("guab:milk"));
        meta.getPersistentDataContainer().set(milkKey, PersistentDataType.BYTE, (byte) 1);
        milk.setItemMeta(meta);
        return milk;
    }

    public boolean isMilk(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(milkKey, PersistentDataType.BYTE);
    }

    public ItemStack createBeer(BeerStyle style, int amount) {
        return createBeer(plugin.beer().randomBrand(style), amount);
    }

    public ItemStack createBeer(BeerBrand brand, int amount) {
        if (brand == null) {
            brand = plugin.beer().houseBrand();
        }
        BeerStyle style = brand.style();
        int max = Math.max(1, ItemType.BEER.material().getMaxStackSize());
        ItemStack stack = new ItemStack(ItemType.BEER.material(), Math.clamp(amount, 1, max));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(brand.displayName(), style.colour())
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(beerLore(brand));
        applyModel(meta, brand.itemModel());
        if (plugin.config().beerReturnEmptyBottle()) {
            meta.setUseRemainder(new ItemStack(Material.GLASS_BOTTLE));
        }
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, ItemType.BEER.id());
        meta.getPersistentDataContainer().set(styleKey, PersistentDataType.STRING, style.id());
        meta.getPersistentDataContainer().set(brandKey, PersistentDataType.STRING, brand.id());
        stack.setItemMeta(meta);
        applyBeerComponents(stack, style);
        return stack;
    }

    private List<Component> beerLore(BeerBrand brand) {
        BeerStyle style = brand.style();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(style.displayName() + "  ·  "
                        + String.format(Locale.ROOT, "%.1f", brand.abv()) + "% ABV",
                style.colour()).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(brand.unitsLabel() + " units per pint", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (style.lethal()) {
            lore.add(Component.text("☠ Fatal. Do not drink this.", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Right-click to drink.", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        return lore;
    }

    private void applyBeerComponents(ItemStack stack, BeerStyle style) {
        Consumable.Builder consumable = Consumable.consumable()
                .consumeSeconds((float) plugin.config().beerConsumeSeconds())
                .animation(ItemUseAnimation.DRINK)
                .sound(Key.key("minecraft:entity.generic.drink"))
                .hasConsumeParticles(false);

        if (!style.effects().isEmpty()) {
            List<PotionEffect> effects = style.effects().stream()
                    .map(effect -> new PotionEffect(effect.type(), effect.durationTicks(),
                            effect.amplifier(), false, true, true))
                    .toList();
            consumable.addEffect(ConsumeEffect.applyStatusEffects(effects, 1.0f));
        }
        consumable.addEffect(ConsumeEffect.playSoundConsumeEffect(
                Key.key("minecraft:entity.player.burp")));
        stack.setData(DataComponentTypes.CONSUMABLE, consumable.build());
        stack.setData(DataComponentTypes.USE_COOLDOWN, UseCooldown
                .useCooldown((float) Math.max(0.05, plugin.config().beerCooldownSeconds()))
                .cooldownGroup(Key.key("guab", "beer"))
                .build());
    }

    private void applyModel(ItemMeta meta, String itemModel) {
        if (itemModel == null) {
            return;
        }
        NamespacedKey model = NamespacedKey.fromString(itemModel);
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

    /** The specific branded pint, falling back to the house brand for legacy items. */
    public BeerBrand beerBrandOf(ItemStack stack) {
        if (typeOf(stack) != ItemType.BEER) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        String id = meta.getPersistentDataContainer().get(brandKey, PersistentDataType.STRING);
        BeerBrand brand = plugin.beer().findBrand(id);
        if (brand != null) {
            return brand;
        }
        String styleId = meta.getPersistentDataContainer().get(styleKey, PersistentDataType.STRING);
        BeerStyle style = plugin.beer().find(styleId);
        return style == null ? plugin.beer().houseBrand() : plugin.beer().randomBrand(style);
    }

    /** The specific beer style, falling back to the house pint for legacy items. */
    public BeerStyle beerStyleOf(ItemStack stack) {
        BeerBrand brand = beerBrandOf(stack);
        return brand == null ? null : brand.style();
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
        for (BeerStyle style : plugin.beer().all()) {
            if (plain.equalsIgnoreCase(style.displayName())) {
                return true;
            }
        }
        for (BeerBrand brand : plugin.beer().allBrands()) {
            if (plain.equalsIgnoreCase(brand.displayName())) {
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
        meta.getPersistentDataContainer().remove(styleKey);
        meta.getPersistentDataContainer().remove(brandKey);
        stack.setItemMeta(meta);
    }

    /**
     * True when a beer stack has to be rebuilt before use. A stack that already
     * carries a live brand tag and a display name is left untouched, which avoids
     * rewriting the inventory slot on every single right-click.
     */
    public boolean needsCanonicalize(ItemStack stack) {
        if (typeOf(stack) != ItemType.BEER) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return true;
        }
        String id = meta.getPersistentDataContainer().get(brandKey, PersistentDataType.STRING);
        if (id == null || id.isBlank()) {
            return true;
        }
        BeerBrand brand = plugin.beer().findBrand(id);
        return brand == null;
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
        if (type == ItemType.BEER) {
            BeerBrand brand = beerBrandOf(stack);
            return createBeer(brand == null ? plugin.beer().houseBrand() : brand, stack.getAmount());
        }
        ItemStack copy = stack.clone();
        ItemMeta meta = copy.getItemMeta();
        meta.displayName(type.displayName());
        meta.lore(type.lore());
        applyModel(meta, type.itemModel());
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
        copy.setItemMeta(meta);
        return copy;
    }
}
