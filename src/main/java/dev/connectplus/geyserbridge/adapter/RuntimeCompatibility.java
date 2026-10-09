package dev.connectplus.geyserbridge.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimum Geyser version and supported ViaProxy series; build hashes are not runtime gates. */
public final class RuntimeCompatibility {
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-[A-Za-z0-9][A-Za-z0-9._-]*)?(?: \\(git-[A-Za-z0-9._-]+\\))?$");

    private RuntimeCompatibility() {}

    public static List<String> problems(String geyserVersion, String viaProxyVersion, int javaFeature,
                                        String platform) {
        List<String> problems = new ArrayList<>();
        if (!atLeast(geyserVersion, 2, 11, 3)) {
            problems.add("unsupported Geyser version: requires 2.11.3 or later");
        }
        if (!inSeries(viaProxyVersion, 3, 4, 13)) {
            problems.add("unsupported ViaProxy version: requires 3.4.x >= 3.4.13");
        }
        if (javaFeature < 21) {
            problems.add("unsupported JDK: requires Java 21 or later");
        }
        if (!"ViaProxy".equals(platform)) {
            problems.add("Geyser must run on the VIAPROXY platform");
        }
        return List.copyOf(problems);
    }

    private static boolean atLeast(String value, int major, int minor, int minimumPatch) {
        if (value == null) return false;
        Matcher version = VERSION.matcher(value);
        if (!version.matches()) return false;
        try {
            int actualMajor = Integer.parseInt(version.group(1));
            int actualMinor = Integer.parseInt(version.group(2));
            int actualPatch = Integer.parseInt(version.group(3));
            return actualMajor > major
                    || (actualMajor == major && (actualMinor > minor
                    || (actualMinor == minor && actualPatch >= minimumPatch)));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean inSeries(String value, int major, int minor, int minimumPatch) {
        if (value == null) return false;
        Matcher version = VERSION.matcher(value);
        if (!version.matches()) return false;
        try {
            return Integer.parseInt(version.group(1)) == major
                    && Integer.parseInt(version.group(2)) == minor
                    && Integer.parseInt(version.group(3)) >= minimumPatch;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
