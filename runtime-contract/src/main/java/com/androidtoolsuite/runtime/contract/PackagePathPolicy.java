package com.androidtoolsuite.runtime.contract;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class PackagePathPolicy {
    private static final Pattern SAFE_PATH = Pattern.compile("^[A-Za-z0-9._/-]+$");

    private PackagePathPolicy() {
    }

    public static String validateFilePath(String raw) throws ContractException {
        if (raw == null || raw.isEmpty()) {
            throw new ContractException("插件包包含空路径");
        }
        if (raw.length() > 240) {
            throw new ContractException("插件包路径过长");
        }
        if (!SAFE_PATH.matcher(raw).matches()) {
            throw new ContractException("插件包路径包含不允许的字符：" + raw);
        }
        if (raw.startsWith("/") || raw.endsWith("/") || raw.contains("\\") || raw.contains(":")) {
            throw new ContractException("插件包路径不是相对文件路径：" + raw);
        }
        String[] segments = raw.split("/", -1);
        if (segments.length > 16) {
            throw new ContractException("插件包路径层级过深：" + raw);
        }
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new ContractException("插件包路径包含空段或穿越段：" + raw);
            }
        }
        return raw;
    }

    public static void addUnique(Set<String> exactPaths, Set<String> foldedPaths, String path)
            throws ContractException {
        String clean = validateFilePath(path);
        String folded = clean.toLowerCase(Locale.ROOT);
        if (!exactPaths.add(clean) || !foldedPaths.add(folded)) {
            throw new ContractException("插件包包含重复路径：" + clean);
        }
    }

    public static boolean isAllowedTopLevel(String path) {
        return "manifest.json".equals(path)
                || path.startsWith("web/")
                || path.startsWith("ui/")
                || path.startsWith("workers/")
                || path.startsWith("android/")
                || path.startsWith("META-INF/");
    }
}
