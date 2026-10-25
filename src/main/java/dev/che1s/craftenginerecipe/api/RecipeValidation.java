package dev.che1s.craftenginerecipe.api;

final class RecipeValidation {
    private RecipeValidation() {}

    static String identifier(String value, String label) {
        if (value == null || !value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException(label + " must be a namespaced ID (namespace:path)");
        }
        return value;
    }

    static String provider(String value) {
        if (value == null || !value.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("provider must contain only lowercase letters, digits, _, . or -");
        }
        return value;
    }

    static int slot(int slot, int size, String label) {
        if (slot < -1 || slot >= size) throw new IllegalArgumentException(label + " must be -1 or between 0 and " + (size - 1));
        return slot;
    }

    static int amount(int amount) {
        if (amount < 1) throw new IllegalArgumentException("amount must be positive");
        return amount;
    }
}
