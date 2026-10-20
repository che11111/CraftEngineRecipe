package dev.che1s.craftenginerecipe.update;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Numeric release comparison, including local development/prerelease versions. */
public final class ReleaseVersion {
    private static final Pattern VERSION = Pattern.compile(
            "[vV]?(\\d+(?:\\.\\d+){0,7})(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");

    private ReleaseVersion() {
    }

    /** Unknown formats never produce an update notification. Build metadata is ignored. */
    public static boolean isNewer(String candidate, String installed) {
        Optional<Version> next = parse(candidate);
        Optional<Version> current = parse(installed);
        return next.isPresent() && current.isPresent() && next.get().compareTo(current.get()) > 0;
    }

    static boolean isStable(String version) {
        return parse(version).filter(parsed -> parsed.prerelease().isEmpty()).isPresent();
    }

    private static Optional<Version> parse(String text) {
        if (text == null || text.length() > 128) return Optional.empty();
        Matcher match = VERSION.matcher(text);
        if (!match.matches()) return Optional.empty();
        List<BigInteger> numbers = new ArrayList<>();
        for (String number : match.group(1).split("\\.")) numbers.add(new BigInteger(number));
        List<String> prerelease = match.group(2) == null ? List.of() : List.of(match.group(2).split("\\."));
        return Optional.of(new Version(List.copyOf(numbers), prerelease));
    }

    private record Version(List<BigInteger> numbers, List<String> prerelease) implements Comparable<Version> {
        @Override
        public int compareTo(Version other) {
            int count = Math.max(numbers.size(), other.numbers.size());
            for (int i = 0; i < count; i++) {
                BigInteger left = i < numbers.size() ? numbers.get(i) : BigInteger.ZERO;
                BigInteger right = i < other.numbers.size() ? other.numbers.get(i) : BigInteger.ZERO;
                int compared = left.compareTo(right);
                if (compared != 0) return compared;
            }
            if (prerelease.isEmpty()) return other.prerelease.isEmpty() ? 0 : 1;
            if (other.prerelease.isEmpty()) return -1;
            count = Math.min(prerelease.size(), other.prerelease.size());
            for (int i = 0; i < count; i++) {
                String left = prerelease.get(i);
                String right = other.prerelease.get(i);
                boolean leftNumber = numeric(left);
                boolean rightNumber = numeric(right);
                int compared;
                if (leftNumber && rightNumber) compared = new BigInteger(left).compareTo(new BigInteger(right));
                else if (leftNumber != rightNumber) compared = leftNumber ? -1 : 1;
                else compared = left.compareTo(right);
                if (compared != 0) return compared;
            }
            return Integer.compare(prerelease.size(), other.prerelease.size());
        }

        private static boolean numeric(String value) {
            return value.chars().allMatch(character -> character >= '0' && character <= '9');
        }
    }
}
