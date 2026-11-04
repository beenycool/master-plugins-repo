package gg.guab.rickroll;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Lyrics {

    private static final Pattern LINE = Pattern.compile("^\\[(\\d{2}):(\\d{2})\\.(\\d{2})]\\s*(.+)$");

    private final List<Line> lines;

    private Lyrics(List<Line> lines) {
        this.lines = List.copyOf(lines);
    }

    public static Lyrics load(InputStream input) {
        if (input == null) {
            return new Lyrics(List.of());
        }
        List<Line> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                Matcher matcher = LINE.matcher(raw.trim());
                if (!matcher.matches()) {
                    continue;
                }
                int minutes = Integer.parseInt(matcher.group(1));
                int seconds = Integer.parseInt(matcher.group(2));
                int hundredths = Integer.parseInt(matcher.group(3));
                double time = minutes * 60.0 + seconds + hundredths / 100.0;
                String text = matcher.group(4).trim();
                if (!text.isEmpty()) {
                    lines.add(new Line(time, text));
                }
            }
        } catch (IOException ignored) {
            // No lyrics is a valid fallback.
        }
        lines.sort((a, b) -> Double.compare(a.seconds(), b.seconds()));
        return new Lyrics(lines);
    }

    public String lineAt(double seconds) {
        String current = null;
        for (Line line : lines) {
            if (line.seconds() > seconds) {
                break;
            }
            current = line.text();
        }
        return current;
    }

    private record Line(double seconds, String text) {
    }
}
