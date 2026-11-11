package gg.guab.core;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared counters such as deaths, kills and rickrolls. */
public interface StatsService {

    String DEATHS = "deaths";
    String KILLS = "kills";
    String RICKROLLS = "rickrolls";
    String VOID_DEATHS = "void_deaths";

    List<String> METRICS = List.of(DEATHS, KILLS, RICKROLLS, VOID_DEATHS);

    void increment(Player player, String metric, int amount);

    int get(UUID player, String metric);

    String name(UUID player);

    UUID find(String name);

    List<Entry> top(String metric, int limit);

    /** Known tracked UUIDs and their last-seen names. */
    default Map<UUID, String> knownPlayers() {
        return Map.of();
    }

    record Entry(UUID uuid, String name, int value) {
    }
}
