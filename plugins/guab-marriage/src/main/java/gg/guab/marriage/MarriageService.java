package gg.guab.marriage;

import gg.guab.core.CooldownMap;
import gg.guab.core.PointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * All marriage behaviour lives here. The command class only parses arguments;
 * keeping the rules in one service makes the permission and fee paths obvious.
 */
public final class MarriageService {

    private static final int LIST_PAGE_SIZE = 8;
    private static final String RING_ITEM_MODEL = "guab:wedding_ring";

    private final GuabMarriagePlugin plugin;
    private final MarriageStore store;
    private final Proposals proposals;
    private final CooldownMap<String> cooldowns = new CooldownMap<>();
    private final Map<UUID, Long> divorceConfirmations = new HashMap<>();
    private final Map<UUID, Warmup> warmups = new HashMap<>();
    private final NamespacedKey ringKey;
    private boolean warnedNoPoints;

    public MarriageService(GuabMarriagePlugin plugin, MarriageStore store, Proposals proposals) {
        this.plugin = plugin;
        this.store = store;
        this.proposals = proposals;
        this.ringKey = new NamespacedKey(plugin, "marriage_ring");
    }

    // ------------------------------------------------------------------ propose

    public void propose(Player proposer, String targetName) {
        if (targetName == null || targetName.isBlank()) {
            MarriageMessages.error(proposer, "Usage: /marry <player> — online, willing, and preferably sober.");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName.trim());
        if (target == null) {
            MarriageMessages.error(proposer, "No online player called " + targetName.trim() + ". Check the spelling, or your friends list.");
            return;
        }
        if (target.getUniqueId().equals(proposer.getUniqueId())) {
            MarriageMessages.error(proposer, "You cannot marry yourself. That is a diary entry, not a wedding.");
            return;
        }
        if (store.find(proposer.getUniqueId()) != null) {
            MarriageMessages.error(proposer, "You are already married. One spouse at a time; /marry info shows the current one.");
            return;
        }
        if (store.find(target.getUniqueId()) != null) {
            MarriageMessages.error(proposer, target.getName() + " is already married. Scandalous.");
            return;
        }
        long now = System.currentTimeMillis();
        if (proposals.find(target.getUniqueId(), proposer.getUniqueId(), now) != null) {
            MarriageMessages.error(proposer, "You already have a proposal sitting with "
                    + target.getName() + ". Give them a minute.");
            return;
        }
        if (!checkCooldown(proposer, "propose", config().proposalCooldownSeconds())) {
            return;
        }
        if (!proposals.propose(proposer.getUniqueId(), proposer.getName(), target.getUniqueId(),
                target.getName(), now)) {
            MarriageMessages.error(proposer, "That proposal could not be sent. The carrier pigeon has unionised.");
            return;
        }
        startCooldown(proposer, "propose", config().proposalCooldownSeconds());

        Component prompt = Component.text("[Marriage] ", MarriageMessages.ACCENT)
                .append(Component.text(proposer.getName()
                        + " wants to marry you. Wild, but go on. ", NamedTextColor.WHITE))
                .append(Component.text("[Accept]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/marry accept " + proposer.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Accept " + proposer.getName() + ". Bold move."))))
                .append(Component.text("  ", NamedTextColor.GRAY))
                .append(Component.text("[Deny]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/marry deny " + proposer.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Deny " + proposer.getName() + ". Keep them in suspense."))));
        target.sendMessage(prompt);
        MarriageMessages.info(proposer, "Proposal sent to " + target.getName() + ". They have "
                + config().proposalTimeoutSeconds() + "s to make a decision.");
    }

    public void accept(Player target, String proposerArg) {
        if (store.find(target.getUniqueId()) != null) {
            MarriageMessages.error(target, "You are already married. The register office is closed to you.");
            return;
        }
        long now = System.currentTimeMillis();
        Proposals.Proposal proposal = selectProposal(target, proposerArg, now);
        if (proposal == null) {
            return;
        }
        if (store.find(proposal.proposer()) != null) {
            proposals.remove(proposal);
            MarriageMessages.error(target, proposal.proposerName() + " got married to someone else. Proposal binned.");
            return;
        }
        int cost = config().weddingCost();
        if (cost > 0) {
            PointsService points = points();
            if (!MarriageEconomy.charge(points, proposal.proposer(), cost)) {
                MarriageMessages.error(target, proposal.proposerName() + " cannot afford the "
                        + cost + "-point wedding fee. The registry office does not do credit; "
                        + "the proposal stays open until it expires.");
                Player proposer = Bukkit.getPlayer(proposal.proposer());
                if (proposer != null) {
                    MarriageMessages.error(proposer, "You need " + cost + " points for the wedding fee. "
                            + "The proposal is still pending, like your finances.");
                }
                return;
            }
        }

        proposals.remove(proposal);
        proposals.clear(target.getUniqueId());
        proposals.clear(proposal.proposer());

        long marriedAt = System.currentTimeMillis();
        MarriageRecord record = new MarriageRecord(proposal.proposer(), target.getUniqueId(),
                proposal.proposerName(), target.getName(), marriedAt, config().pvpDefault(), null);
        store.put(record);
        celebrate(record, target);
    }

    public void deny(Player target, String proposerArg) {
        long now = System.currentTimeMillis();
        Proposals.Proposal proposal = selectProposal(target, proposerArg, now);
        if (proposal == null) {
            return;
        }
        proposals.remove(proposal);
        MarriageMessages.info(target, "You denied " + proposal.proposerName() + ". The bell has stopped ringing.");
        Player proposer = Bukkit.getPlayer(proposal.proposer());
        if (proposer != null) {
            MarriageMessages.info(proposer, target.getName() + " denied your proposal. The pub has been informed.");
        }
    }

    private Proposals.Proposal selectProposal(Player target, String proposerArg, long now) {
        List<Proposals.Proposal> pending = proposals.pendingFor(target.getUniqueId(), now);
        if (pending.isEmpty()) {
            MarriageMessages.info(target, "No pending proposals. The silence is deafening.");
            return null;
        }
        if (proposerArg != null && !proposerArg.isBlank()) {
            Proposals.Proposal named = proposals.findByName(target.getUniqueId(), proposerArg, now);
            if (named == null) {
                MarriageMessages.error(target, "No pending proposal from " + proposerArg.trim() + ". They may have sobered up.");
            }
            return named;
        }
        if (pending.size() > 1) {
            List<String> names = new ArrayList<>();
            for (Proposals.Proposal proposal : pending) {
                names.add(proposal.proposerName());
            }
            MarriageMessages.error(target, "Look at you. Several proposals; pick one with /marry accept <player>. Pending: "
                    + String.join(", ", names) + ".");
            return null;
        }
        return pending.get(0);
    }

    private void celebrate(MarriageRecord record, Player target) {
        Player first = Bukkit.getPlayer(record.first());
        Player second = Bukkit.getPlayer(record.second());
        if (first != null) {
            hearts(first);
            MarriageSounds.weddingBell(first);
        }
        if (second != null) {
            hearts(second);
            MarriageSounds.weddingBell(second);
        }
        if (config().weddingAnnounce()) {
            Bukkit.broadcast(Component.text("[Marriage] ", MarriageMessages.ACCENT)
                    .append(Component.text(record.firstName() + " and " + record.secondName()
                            + " are now married. The pub expects a round. ", NamedTextColor.LIGHT_PURPLE))
                    .append(Component.text("💍", NamedTextColor.GOLD)));
        }
        MarriageMessages.info(target, "You are now married to " + record.otherName(target.getUniqueId())
                + ". Congratulations, probably.");
        Player proposer = Bukkit.getPlayer(record.other(target.getUniqueId()));
        if (proposer != null && proposer != target) {
            MarriageMessages.info(proposer, "You are now married to " + target.getName() + ". No refunds.");
        }
        if (config().ringEnabled()) {
            if (first != null) {
                giveRing(first, record.otherName(first.getUniqueId()));
            }
            if (second != null) {
                giveRing(second, record.otherName(second.getUniqueId()));
            }
        }
    }

    // ------------------------------------------------------------------ divorce

    public void divorce(Player player, boolean confirm) {
        MarriageRecord record = store.find(player.getUniqueId());
        if (record == null) {
            MarriageMessages.error(player, "You are not married. There is nothing to dissolve.");
            return;
        }
        long now = System.currentTimeMillis();
        long window = config().divorceConfirmMillis();
        String partnerName = record.otherName(player.getUniqueId());

        if (!confirm) {
            divorceConfirmations.put(player.getUniqueId(), now + window);
            MarriageMessages.info(player, "This dissolves the marriage to " + partnerName + " and costs "
                    + config().divorceCost() + " points. Run /marry divorce confirm within "
                    + config().divorceConfirmSeconds() + "s. The pub will pretend not to hear.");
            return;
        }

        Long expiresAt = divorceConfirmations.get(player.getUniqueId());
        if (expiresAt == null || now >= expiresAt) {
            divorceConfirmations.put(player.getUniqueId(), now + window);
            MarriageMessages.info(player, "Your divorce window closed. Run /marry divorce confirm within "
                    + config().divorceConfirmSeconds() + "s, if you are sure.");
            return;
        }
        divorceNow(player);
    }

    /** Divorces immediately; used by the UI after its own confirmation page. */
    public void divorceNow(Player player) {
        MarriageRecord record = store.find(player.getUniqueId());
        if (record == null) {
            MarriageMessages.error(player, "You are not married. There is nothing to dissolve.");
            return;
        }
        String partnerName = record.otherName(player.getUniqueId());
        if (!charge(player, player.getUniqueId(), config().divorceCost())) {
            return;
        }
        divorceConfirmations.remove(player.getUniqueId());
        store.remove(player.getUniqueId());
        MarriageMessages.info(player, "You are now divorced from " + partnerName + ". The ring is now a very small frisbee.");
        Player partner = Bukkit.getPlayer(record.other(player.getUniqueId()));
        if (partner != null) {
            MarriageMessages.info(partner, player.getName() + " divorced you. The pub sends a lukewarm pint.");
        }
    }

    public void adminDivorce(CommandSender sender, String name) {
        if (!sender.hasPermission("guab.marriage.admin")) {
            MarriageMessages.error(sender, "You need guab.marriage.admin to do that.");
            return;
        }
        if (name == null || name.isBlank()) {
            MarriageMessages.error(sender, "Usage: /marry admin divorce <player> — no ceremony, no fee, no tears.");
            return;
        }
        MarriageRecord record = null;
        Player online = Bukkit.getPlayerExact(name.trim());
        if (online != null) {
            record = store.find(online.getUniqueId());
        }
        if (record == null) {
            record = store.findByName(name);
        }
        if (record == null) {
            MarriageMessages.error(sender, "No married player called " + name.trim() + ". Try /marry list.");
            return;
        }
        store.remove(record.first(), record.second());
        MarriageMessages.info(sender, "Divorced " + record.firstName() + " and " + record.secondName()
                + ". No fee, no confetti.");
        Player first = Bukkit.getPlayer(record.first());
        if (first != null) {
            MarriageMessages.info(first, "An admin divorced you from " + record.secondName() + ". It is official.");
        }
        Player second = Bukkit.getPlayer(record.second());
        if (second != null) {
            MarriageMessages.info(second, "An admin divorced you from " + record.firstName() + ". It is official.");
        }
    }

    // --------------------------------------------------------------- teleports

    public void teleportToPartner(Player player) {
        if (!player.hasPermission("guab.marriage.tp")) {
            MarriageMessages.error(player, "You need guab.marriage.tp to teleport. The magic is members-only.");
            return;
        }
        MarriageRecord record = requireMarriage(player);
        if (record == null) {
            return;
        }
        Player partner = Bukkit.getPlayer(record.other(player.getUniqueId()));
        if (partner == null) {
            MarriageMessages.error(player, "Your partner is offline. Shouting is free; teleporting is not.");
            return;
        }
        if (!checkCooldown(player, "tp", config().teleportCooldownSeconds())) {
            return;
        }
        startCooldown(player, "tp", config().teleportCooldownSeconds());
        int warmup = config().teleportWarmupSeconds();
        if (warmup <= 0) {
            doPartnerTeleport(player, partner);
            return;
        }
        startWarmup(player, partner, warmup);
    }

    private void doPartnerTeleport(Player player, Player partner) {
        player.teleport(partner.getLocation());
        player.setFallDistance(0.0f);
        MarriageSounds.play(player, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.7f, 1.2f);
        MarriageMessages.info(player, "You materialise next to " + partner.getName() + ". Try to look normal.");
    }

    private void startWarmup(Player player, Player partner, int seconds) {
        cancelWarmup(player);
        Warmup warmup = new Warmup(player.getLocation(), partner.getUniqueId());
        warmups.put(player.getUniqueId(), warmup);
        MarriageMessages.info(player, "Teleporting to " + partner.getName() + " in " + seconds
                + "s. Stand still or the magic cancels.");
        warmup.task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Warmup current = warmups.remove(player.getUniqueId());
            if (current == null) {
                return;
            }
            if (!player.isOnline()) {
                return;
            }
            Player destination = Bukkit.getPlayer(current.target);
            if (destination == null) {
                MarriageMessages.error(player, "Your partner went offline. Teleport cancelled; the void says hi.");
                return;
            }
            if (!current.from.getWorld().equals(player.getWorld())
                    || player.getLocation().distanceSquared(current.from) > 1.0) {
                MarriageMessages.error(player, "Teleport cancelled — you moved. The teleporter is disappointed.");
                return;
            }
            doPartnerTeleport(player, destination);
        }, Math.max(1L, seconds) * 20L);
    }

    public void handleMove(Player player, Location to) {
        if (to == null) {
            return;
        }
        Warmup warmup = warmups.get(player.getUniqueId());
        if (warmup == null) {
            return;
        }
        if (!warmup.from.getWorld().equals(to.getWorld()) || warmup.from.distanceSquared(to) > 1.0) {
            cancelWarmup(player);
            MarriageMessages.error(player, "Teleport cancelled — you moved. The teleporter is disappointed.");
        }
    }

    private void cancelWarmup(Player player) {
        Warmup warmup = warmups.remove(player.getUniqueId());
        if (warmup != null && warmup.task != null) {
            warmup.task.cancel();
        }
    }

    public void setHome(Player player) {
        MarriageRecord record = requireMarriage(player);
        if (record == null) {
            return;
        }
        Location location = player.getLocation();
        if (location.getWorld() == null) {
            MarriageMessages.error(player, "This world is unavailable. Pick another spot, ideally one that exists.");
            return;
        }
        MarriageRecord.Home home = new MarriageRecord.Home(location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
        store.put(record.withHome(home));
        MarriageMessages.info(player, "Shared home saved at " + formatHome(home) + ". Try not to lose it.");
    }

    public void teleportHome(Player player) {
        MarriageRecord record = requireMarriage(player);
        if (record == null) {
            return;
        }
        MarriageRecord.Home home = record.home();
        if (home == null) {
            MarriageMessages.info(player, "No shared home yet. Stand somewhere nice and run /marry sethome. Anywhere but the void.");
            return;
        }
        World world = Bukkit.getWorld(home.world());
        if (world == null) {
            MarriageMessages.error(player, "The shared home world '" + home.world()
                    + "' is not loaded. Your home is currently theoretical; ask an admin.");
            return;
        }
        if (!checkCooldown(player, "home", config().homeCooldownSeconds())) {
            return;
        }
        startCooldown(player, "home", config().homeCooldownSeconds());
        player.teleport(new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch()));
        player.setFallDistance(0.0f);
        MarriageSounds.play(player, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.7f, 1.0f);
        MarriageMessages.info(player, "Home sweet home. Mind the cobwebs.");
    }

    // ------------------------------------------------------------------ gifts

    public void gift(Player player) {
        MarriageRecord record = requireMarriage(player);
        if (record == null) {
            return;
        }
        Player partner = onlinePartner(player, record, "give a gift");
        if (partner == null) {
            return;
        }
        if (!withinRange(player, partner, config().giftRange(), "give a gift")) {
            return;
        }
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack == null || stack.getType().isAir()) {
            MarriageMessages.error(player, "Your main hand is empty. You cannot gift a vibe.");
            return;
        }
        if (!checkCooldown(player, "gift", config().giftCooldownSeconds())) {
            return;
        }
        ItemStack gift = stack.clone();
        int amount = gift.getAmount();
        String label = prettyMaterial(gift.getType());
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        Map<Integer, ItemStack> leftovers = partner.getInventory().addItem(gift);
        for (ItemStack leftover : leftovers.values()) {
            partner.getWorld().dropItemNaturally(partner.getLocation(), leftover);
        }
        startCooldown(player, "gift", config().giftCooldownSeconds());
        MarriageMessages.info(player, "You gave " + amount + "x " + label + " to " + partner.getName() + ". Romance is alive.");
        MarriageMessages.info(partner, player.getName() + " gave you " + amount + "x " + label + ". No returns.");
        MarriageSounds.play(partner, Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.6f, 1.2f);
    }

    public void kiss(Player player) {
        MarriageRecord record = requireMarriage(player);
        if (record == null) {
            return;
        }
        Player partner = onlinePartner(player, record, "kiss");
        if (partner == null) {
            return;
        }
        if (!withinRange(player, partner, config().kissRange(), "kiss")) {
            return;
        }
        if (!checkCooldown(player, "kiss", config().kissCooldownSeconds())) {
            return;
        }
        startCooldown(player, "kiss", config().kissCooldownSeconds());
        hearts(player);
        hearts(partner);
        MarriageSounds.weddingBell(player);
        MarriageSounds.play(partner, Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.PLAYERS, 0.7f, 1.6f);
        MarriageMessages.info(player, "You kiss " + partner.getName() + ". Somewhere, a single violin plays.");
        MarriageMessages.info(partner, player.getName() + " kisses you. The pub applauds, mostly sarcastically.");
    }

    public void pvp(Player player, String value) {
        MarriageRecord record = requireMarriage(player);
        if (record == null) {
            return;
        }
        if (value == null || value.isBlank()) {
            MarriageMessages.info(player, "Spouse PvP is " + (record.pvp() ? "on" : "off")
                    + ". Use /marry pvp on|off. 'Maybe' is not a setting.");
            return;
        }
        Boolean enabled = switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "enable", "enabled" -> Boolean.TRUE;
            case "off", "false", "no", "disable", "disabled" -> Boolean.FALSE;
            case "toggle" -> !record.pvp();
            default -> null;
        };
        if (enabled == null) {
            MarriageMessages.error(player, "Use /marry pvp on or /marry pvp off. 'Maybe' is not a setting.");
            return;
        }
        if (enabled == record.pvp()) {
            MarriageMessages.info(player, "Spouse PvP is already " + (enabled ? "on" : "off") + ". The paperwork is filed.");
            return;
        }
        store.put(record.withPvp(enabled));
        MarriageMessages.info(player, "Spouse PvP is now " + (enabled
                ? "on. You may bonk each other. God help you."
                : "off. You are now too precious to bonk."));
    }

    // ------------------------------------------------------------------- rings

    public void ring(Player player) {
        MarriageRecord record = requireMarriage(player);
        if (record == null) {
            return;
        }
        if (!config().ringEnabled()) {
            MarriageMessages.info(player, "Wedding rings are disabled. The wedding is now a very expensive handshake.");
            return;
        }
        if (!checkCooldown(player, "ring", config().ringCooldownSeconds())) {
            return;
        }
        String partnerName = record.otherName(player.getUniqueId());
        giveRing(player, partnerName);
        startCooldown(player, "ring", config().ringCooldownSeconds());
        MarriageMessages.info(player, "One wedding ring, bound to " + partnerName + ". Do not pawn it.");
    }

    public ItemStack createRing(String partnerName) {
        ItemStack ring = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta meta = ring.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Wedding Ring", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Bound to " + partnerName, NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("A cosmetic keepsake. No powers, no stats, no refunds.", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
            NamespacedKey model = NamespacedKey.fromString(RING_ITEM_MODEL);
            if (model != null) {
                meta.setItemModel(model);
            }
            meta.getPersistentDataContainer().set(ringKey, PersistentDataType.BYTE, (byte) 1);
            ring.setItemMeta(meta);
        }
        return ring;
    }

    public void giveRing(Player player, String partnerName) {
        ItemStack ring = createRing(partnerName);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(ring);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    // ---------------------------------------------------------------- info/list

    public void showInfo(CommandSender sender, String name) {
        if (name == null || name.isBlank()) {
            if (!(sender instanceof Player player)) {
                MarriageMessages.error(sender, "From console use /marry info <player>. I cannot guess who you are.");
                return;
            }
            MarriageRecord record = store.find(player.getUniqueId());
            if (record == null) {
                MarriageMessages.info(sender, "Not married yet. Try /marry <player>, or enjoy the silence.");
                return;
            }
            printInfo(sender, record, player.getUniqueId());
            return;
        }
        Player online = Bukkit.getPlayerExact(name.trim());
        if (online != null) {
            MarriageRecord record = store.find(online.getUniqueId());
            if (record == null) {
                MarriageMessages.info(sender, online.getName() + " is not married. The field is open.");
                return;
            }
            printInfo(sender, record, online.getUniqueId());
            return;
        }
        MarriageRecord record = store.findByName(name.trim());
        if (record == null) {
            MarriageMessages.error(sender, "No married player called " + name.trim() + ". Try /marry list.");
            return;
        }
        UUID subject = record.firstName().equalsIgnoreCase(name.trim()) ? record.first() : record.second();
        printInfo(sender, record, subject);
    }

    private void printInfo(CommandSender sender, MarriageRecord record, UUID subject) {
        UUID partnerId = record.other(subject);
        if (partnerId == null) {
            partnerId = record.first();
        }
        String partnerName = record.nameOf(partnerId);
        Player partner = Bukkit.getPlayer(partnerId);
        MarriageMessages.info(sender, "Partner: " + (partnerName.isBlank() ? "unknown" : partnerName)
                + (partner != null ? " (online). Lucky you." : " (offline). Freedom, briefly."));
        MarriageMessages.info(sender, "Married since " + MarriageRecord.formatDate(record.since())
                + " — " + record.daysLabel(System.currentTimeMillis())
                + " of shared chat and shared blame.");
        if (record.home() == null) {
            MarriageMessages.info(sender, "Shared home: not set. The floor is yours.");
        } else {
            MarriageMessages.info(sender, "Shared home: " + formatHome(record.home()) + ". Mind the cobwebs.");
        }
        MarriageMessages.info(sender, "Spouse PvP: " + (record.pvp() ? "on. You may bonk." : "off. Blessed peace."));
    }

    public void list(CommandSender sender, int requestedPage) {
        List<MarriageRecord> records = new ArrayList<>(store.all());
        if (records.isEmpty()) {
            MarriageMessages.info(sender, "No couples yet. The register office is suspiciously quiet.");
            return;
        }
        records.sort(Comparator.comparing((MarriageRecord record) -> record.firstName().toLowerCase(Locale.ROOT))
                .thenComparing(record -> record.secondName().toLowerCase(Locale.ROOT)));
        int pages = Math.max(1, (records.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
        int page = Math.clamp(requestedPage, 1, pages);
        int from = (page - 1) * LIST_PAGE_SIZE;
        int to = Math.min(records.size(), from + LIST_PAGE_SIZE);
        MarriageMessages.info(sender, "Married couples (" + records.size() + ") — page " + page + "/" + pages + ". Gossip below:");
        long now = System.currentTimeMillis();
        for (int index = from; index < to; index++) {
            MarriageRecord record = records.get(index);
            sender.sendMessage(Component.text("  " + (index + 1) + ". ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(record.firstName() + " & " + record.secondName(),
                            NamedTextColor.WHITE))
                    .append(Component.text(" — " + record.daysLabel(now), NamedTextColor.GRAY)));
        }
        if (pages > 1) {
            MarriageMessages.info(sender, "Next page: /marry list " + (page < pages ? page + 1 : 1) + ". There are more.");
        }
    }

    public void anniversary(Player player) {
        MarriageRecord record = requireMarriage(player);
        if (record == null) {
            return;
        }
        long now = System.currentTimeMillis();
        MarriageMessages.info(player, "You married " + record.otherName(player.getUniqueId()) + " on "
                + MarriageRecord.formatDate(record.since(), ZoneId.systemDefault())
                + " — " + record.daysLabel(now) + " of shared inventory space.");
    }

    // -------------------------------------------------------------------- chat

    public void chat(Player player, String message) {
        if (message == null || message.isBlank()) {
            MarriageMessages.error(player, "Usage: /mc <message>. Private, but not that private.");
            return;
        }
        MarriageRecord record = store.find(player.getUniqueId());
        if (record == null) {
            MarriageMessages.error(player, "You are not married. /mc currently stands for 'mostly confused'.");
            return;
        }
        Player partner = Bukkit.getPlayer(record.other(player.getUniqueId()));
        if (partner == null) {
            MarriageMessages.error(player, "Your partner is offline. Your message has been shouted into the void.");
            return;
        }
        String text = message.trim();
        player.sendMessage(Component.text("[Marriage] ", MarriageMessages.ACCENT)
                .append(Component.text("You → " + partner.getName() + ": ", NamedTextColor.GRAY))
                .append(Component.text(text, NamedTextColor.WHITE)));
        partner.sendMessage(Component.text("[Marriage] ", MarriageMessages.ACCENT)
                .append(Component.text(player.getName() + " → you: ", NamedTextColor.GRAY))
                .append(Component.text(text, NamedTextColor.WHITE)));
    }

    // -------------------------------------------------------------- lifecycle

    public void handleJoin(Player player) {
        MarriageRecord record = store.find(player.getUniqueId());
        if (record == null) {
            return;
        }
        // Keep the stored names current after a Minecraft name change.
        String first = record.first().equals(player.getUniqueId())
                ? player.getName() : record.firstName();
        String second = record.second().equals(player.getUniqueId())
                ? player.getName() : record.secondName();
        if (!first.equals(record.firstName()) || !second.equals(record.secondName())) {
            record = record.withNames(first, second);
            store.put(record);
        }
        Player partner = Bukkit.getPlayer(record.other(player.getUniqueId()));
        if (partner == null || partner == player) {
            return;
        }
        MarriageMessages.info(player, partner.getName()
                + " is online. /mc for sweet nothings, or just go stand next to them.");
    }

    public void handleQuit(Player player) {
        proposals.clear(player.getUniqueId());
        divorceConfirmations.remove(player.getUniqueId());
        cancelWarmup(player);
        String prefix = player.getUniqueId() + ":";
        for (String action : List.of("propose", "tp", "home", "gift", "kiss", "ring")) {
            cooldowns.clear(prefix + action);
        }
    }

    public void shutdown() {
        for (Warmup warmup : warmups.values()) {
            if (warmup.task != null) {
                warmup.task.cancel();
            }
        }
        warmups.clear();
    }

    // ----------------------------------------------------------------- helpers

    private MarriageConfig config() {
        return plugin.config();
    }

    private MarriageRecord requireMarriage(Player player) {
        MarriageRecord record = store.find(player.getUniqueId());
        if (record == null) {
            MarriageMessages.error(player, "Not married yet. Try /marry <player>, or enjoy the silence.");
        }
        return record;
    }

    private Player onlinePartner(Player player, MarriageRecord record, String action) {
        Player partner = Bukkit.getPlayer(record.other(player.getUniqueId()));
        if (partner == null) {
            MarriageMessages.error(player, "Your partner must be online to " + action + ". Long-distance romance has limits.");
        }
        return partner;
    }

    private boolean withinRange(Player player, Player partner, double range, String action) {
        if (!player.getWorld().equals(partner.getWorld())) {
            MarriageMessages.error(player, "Your partner must be in the same world to " + action + ". The void is not a date spot.");
            return false;
        }
        double distance = player.getLocation().distance(partner.getLocation());
        if (distance > range) {
            MarriageMessages.error(player, String.format(Locale.ROOT,
                    "You are too far away to %s (%.1f blocks; limit %.1f). Try walking.", action, distance, range));
            return false;
        }
        return true;
    }

    private void hearts(Player player) {
        player.getWorld().spawnParticle(Particle.HEART,
                player.getLocation().add(0.0, 1.2, 0.0), 12, 0.5, 0.5, 0.5, 0.0);
    }

    private boolean charge(CommandSender sender, UUID payer, long cost) {
        if (cost <= 0L) {
            return true;
        }
        PointsService points = points();
        if (!MarriageEconomy.charge(points, payer, cost)) {
            MarriageMessages.error(sender, "You need " + cost + " points for that. The ledger is unmoved.");
            return false;
        }
        return true;
    }

    private PointsService points() {
        var registration = Bukkit.getServicesManager().getRegistration(PointsService.class);
        if (registration == null) {
            if (!warnedNoPoints) {
                plugin.getLogger().warning("PointsService is not registered; marriage fees are free.");
                warnedNoPoints = true;
            }
            return null;
        }
        return registration.getProvider();
    }

    private boolean checkCooldown(Player player, String action, int seconds) {
        long remaining = cooldowns.remainingMillis(player.getUniqueId() + ":" + action);
        if (remaining > 0L) {
            MarriageMessages.error(player, "Wait " + gg.guab.core.GuabMessages.seconds(remaining)
                    + "s. Even romance has a cooldown.");
            return false;
        }
        return true;
    }

    private void startCooldown(Player player, String action, int seconds) {
        cooldowns.start(player.getUniqueId() + ":" + action, seconds * 1000L);
    }

    private static String formatHome(MarriageRecord.Home home) {
        return home.world() + " " + String.format(Locale.ROOT, "%.1f, %.1f, %.1f",
                home.x(), home.y(), home.z());
    }

    private static String prettyMaterial(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static final class Warmup {
        private final Location from;
        private final UUID target;
        private BukkitTask task;

        private Warmup(Location from, UUID target) {
            this.from = from;
            this.target = target;
        }
    }
}
