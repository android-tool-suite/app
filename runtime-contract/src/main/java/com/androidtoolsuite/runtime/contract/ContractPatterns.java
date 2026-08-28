package com.androidtoolsuite.runtime.contract;

import java.util.regex.Pattern;

public final class ContractPatterns {
    public static final Pattern ID = Pattern.compile("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$");
    public static final Pattern VERSION = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$"
    );
    public static final Pattern ENTRY_CLASS = Pattern.compile(
            "^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+$"
    );

    private ContractPatterns() {
    }

    public static String requireId(String label, String value, int maxLength) throws ContractException {
        String clean = requireText(label, value, maxLength);
        if (!ID.matcher(clean).matches()) {
            throw new ContractException(label + " 格式无效");
        }
        return clean;
    }

    public static String requireText(String label, String value, int maxLength) throws ContractException {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            throw new ContractException("缺少 " + label);
        }
        if (clean.length() > maxLength) {
            throw new ContractException(label + " 超出长度限制");
        }
        return clean;
    }
}
