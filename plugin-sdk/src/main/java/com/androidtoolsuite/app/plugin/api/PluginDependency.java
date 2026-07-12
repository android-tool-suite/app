package com.androidtoolsuite.app.plugin.api;

import java.util.Map;

public final class PluginDependency {
    public final String raw;
    public final String id;
    public final String operator;
    public final String version;

    private PluginDependency(String raw, String id, String operator, String version) {
        this.raw = raw;
        this.id = id;
        this.operator = operator;
        this.version = version;
    }

    public static PluginDependency parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        String[] operators = new String[] {">=", "<=", "==", ">", "<", "="};
        for (String operator : operators) {
            int index = value.indexOf(operator);
            if (index > 0) {
                return new PluginDependency(
                        value,
                        value.substring(0, index).trim(),
                        operator,
                        value.substring(index + operator.length()).trim()
                );
            }
        }
        return new PluginDependency(value, value, "", "");
    }

    public boolean isSatisfied(Map<String, String> activeVersions) {
        String activeVersion = activeVersions.get(id);
        if (activeVersion == null) {
            return false;
        }
        if (operator.isEmpty() || version.isEmpty()) {
            return true;
        }
        int comparison = compareVersions(activeVersion, version);
        switch (operator) {
            case ">=":
                return comparison >= 0;
            case "<=":
                return comparison <= 0;
            case ">":
                return comparison > 0;
            case "<":
                return comparison < 0;
            case "=":
            case "==":
                return comparison == 0;
            default:
                return false;
        }
    }

    public String label() {
        if (operator.isEmpty() || version.isEmpty()) {
            return id;
        }
        return id + " " + operator + " " + version;
    }

    public static int compareVersions(String left, String right) {
        String[] leftParts = normalize(left).split("\\.");
        String[] rightParts = normalize(right).split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftValue = i < leftParts.length ? parsePart(leftParts[i]) : 0;
            int rightValue = i < rightParts.length ? parsePart(rightParts[i]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private static String normalize(String version) {
        String value = version == null ? "" : version.trim();
        int suffix = value.indexOf('-');
        if (suffix >= 0) {
            value = value.substring(0, suffix);
        }
        return value.isEmpty() ? "0" : value;
    }

    private static int parsePart(String value) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }
}
