package gg.guab.core;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Bridges the Guab points ledger into Vault so third-party plugins (the casino)
 * can spend and pay out points without knowing anything about GuabCore.
 */
final class VaultEconomy implements Economy {

    private final PointsService points;

    VaultEconomy(PointsService points) {
        this.points = points;
    }

    /**
     * Convert a Vault amount to the 64-bit ledger. Vault itself speaks {@code double},
     * so integers are exact up to 2^53. Negative and non-finite values become zero.
     */
    static long toPoints(double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0) {
            return 0L;
        }
        return Math.round(amount);
    }

    private boolean canWithdraw(UUID id, double amount) {
        return points.get(id) >= toPoints(amount);
    }

    private static UUID id(String playerName) {
        return Bukkit.getOfflinePlayer(playerName).getUniqueId();
    }

    private EconomyResponse fail(double balance, String reason) {
        return new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE, reason);
    }

    private EconomyResponse notImplemented() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Guab points do not support banks");
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "GuabPoints";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 0;
    }

    @Override
    public String format(double amount) {
        return toPoints(amount) + " points";
    }

    @Override
    public String currencyNamePlural() {
        return "points";
    }

    @Override
    public String currencyNameSingular() {
        return "point";
    }

    @Override
    public boolean hasAccount(String playerName) {
        return true;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return true;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return true;
    }

    @Override
    public double getBalance(String playerName) {
        return (double) points.get(id(playerName));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return (double) points.get(player.getUniqueId());
    }

    @Override
    public double getBalance(String playerName, String worldName) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String worldName) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return canWithdraw(id(playerName), amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return canWithdraw(player.getUniqueId(), amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdraw(id(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdraw(player.getUniqueId(), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdraw(id(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdraw(player.getUniqueId(), amount);
    }

    private EconomyResponse withdraw(UUID id, double amount) {
        long value = toPoints(amount);
        long balance = points.get(id);
        if (value <= 0L) {
            return fail(balance, "Amount must be at least 1 point");
        }
        if (!points.take(id, value)) {
            return fail(balance, "Insufficient points");
        }
        return new EconomyResponse((double) value, (double) points.get(id),
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return deposit(id(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return deposit(player.getUniqueId(), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return deposit(id(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return deposit(player.getUniqueId(), amount);
    }

    private EconomyResponse deposit(UUID id, double amount) {
        long value = toPoints(amount);
        if (value <= 0L) {
            return fail(points.get(id), "Amount must be at least 1 point");
        }
        return new EconomyResponse((double) value, (double) points.add(id, value),
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public List<String> getBanks() {
        return Collections.emptyList();
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return true;
    }
}
