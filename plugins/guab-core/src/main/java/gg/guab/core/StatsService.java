package gg.guab.core;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** Shared counters such as deaths, kills, rickrolls and parkour uses. */
public interface StatsService {

    String DEATHS = "deaths";
    String KILLS = "kills";
    String RICKROLLS = "rickrolls";
    String PARKOUR = "parkour";

    List<String> METRICS = List.of(DEATHS, KILLS, RICKROLLS, PARKOUR);

    void increment(Player player, String metric, int amount);

    int get(UUID player, String metric);

    String name(UUID player);

    UUID find(String name);

    List<Entry> top(String metric, int limit);

    record Entry(UUID uuid, String name, int value) {
    }
}
