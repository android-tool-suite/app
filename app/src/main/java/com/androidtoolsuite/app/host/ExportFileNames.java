package com.androidtoolsuite.app.host;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Human-readable file identity; the archive's internal format and version are unchanged. */
final class ExportFileNames {
    static String dataBackup(String owner,Date createdAt,boolean debug) {
        String prefix = "android_tool_suite".equals(owner) || "android-tool-suite".equals(owner) ? "ATS" : safe(owner);
        return forBuild(prefix+"-data-"+timestamp(createdAt)+".atsbackup",debug);
    }
    static String pluginPackage(String owner,String version,boolean debug) {
        return forBuild(safe(owner)+"-v"+safe(version)+".atsplugin",debug);
    }
    static String forBuild(String fileName,boolean debug) {
        if (!debug) return fileName;
        int extension = fileName.lastIndexOf('.');
        String stem = extension > 0 ? fileName.substring(0,extension) : fileName;
        if (stem.toLowerCase(Locale.ROOT).endsWith("-debug")) return fileName;
        return stem+"-debug"+(extension > 0 ? fileName.substring(extension) : "");
    }
    private static String timestamp(Date createdAt) {
        return new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(createdAt);
    }
    private static String safe(String text) {
        String cleaned=text.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","-").trim();
        return cleaned.isEmpty()?"export":cleaned;
    }
}
