package gg.guab.marriage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * One married couple. Both UUIDs are kept in a stable lexicographic order so
 * the pair key can never be written twice with the two players swapped.
 *
 * <p>The record is pure data: no Bukkit types, so the store and the tests can
 * use it without a running server.</p>
 */
public record MarriageRecord(
        UUID first,
        UUID second,
        String firstName,
        String secondName,
        long since,
        boolean pvp,
        Home home) {

    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("d MMM uuuu", Locale.ENGLISH);

    /** A stored shared home. Kept as primitives so it survives YAML cleanly. */
    public record Home(String world, double x, double y, double z, float yaw, float pitch) {

        public Home {
            world = world == null ? "" : world.trim();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Home coordinates must be finite");
            }
            if (!Float.isFinite(yaw)) {
                yaw = 0.0f;
            }
            if (!Float.isFinite(pitch)) {
                pitch = 0.0f;
            }
        }
    }

    public MarriageRecord {
        if (first == null || second == null) {
            throw new IllegalArgumentException("A marriage needs two player ids");
        }
        if (first.equals(second)) {
            throw new IllegalArgumentException("A player cannot marry themselves");
        }
        if (first.toString().compareTo(second.toString()) > 0) {
            UUID idSwap = first;
            first = second;
            second = idSwap;
            String nameSwap = firstName;
            firstName = secondName;
            secondName = nameSwap;
        }
        firstName = clean(firstName);
        secondName = clean(secondName);
        if (since < 0L) {
            since = 0L;
        }
    }

    public static String key(UUID one, UUID two) {
        String left = one.toString();
        String right = two.toString();
        return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
    }

    public String key() {
        return key(first, second);
    }

    public boolean involves(UUID player) {
        return player != null && (first.equals(player) || second.equals(player));
    }

    public UUID other(UUID player) {
        if (first.equals(player)) {
            return second;
        }
        if (second.equals(player)) {
            return first;
        }
        return null;
    }

    public String otherName(UUID player) {
        if (first.equals(player)) {
            return secondName;
        }
        if (second.equals(player)) {
            return firstName;
        }
        return "";
    }

    public String nameOf(UUID player) {
        if (first.equals(player)) {
            return firstName;
        }
        if (second.equals(player)) {
            return secondName;
        }
        return "";
    }

    public long daysTogether(long now) {
        long elapsed = now - since;
        return elapsed <= 0L ? 0L : elapsed / MILLIS_PER_DAY;
    }

    public String daysLabel(long now) {
        long days = daysTogether(now);
        return days == 1L ? "1 day" : days + " days";
    }

    public static String formatDate(long epochMillis, ZoneId zone) {
        return DATE.format(Instant.ofEpochMilli(epochMillis).atZone(zone == null ? ZoneId.systemDefault() : zone));
    }

    public static String formatDate(long epochMillis) {
        return formatDate(epochMillis, ZoneId.systemDefault());
    }

    public MarriageRecord withPvp(boolean value) {
        return new MarriageRecord(first, second, firstName, secondName, since, value, home);
    }

    public MarriageRecord withHome(Home value) {
        return new MarriageRecord(first, second, firstName, secondName, since, pvp, value);
    }

    public MarriageRecord withNames(String newFirstName, String newSecondName) {
        return new MarriageRecord(first, second, newFirstName, newSecondName, since, pvp, home);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
