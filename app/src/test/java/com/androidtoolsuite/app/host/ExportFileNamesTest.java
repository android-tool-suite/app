package com.androidtoolsuite.app.host;

import static org.junit.Assert.*;
import java.util.Calendar;
import org.junit.Test;

public final class ExportFileNamesTest {
    @Test public void dataNamesKeepOwnerAndLocalTimeWithoutSoftwareVersion() {
        Calendar calendar=Calendar.getInstance();calendar.clear();calendar.set(2026,9,7,9,5,30);
        assertEquals("ATS-data-20261007-090530.atsbackup",ExportFileNames.dataBackup("android_tool_suite",calendar.getTime(),false));
        assertEquals("ATS-data-20261007-090530-debug.atsbackup",ExportFileNames.dataBackup("android-tool-suite",calendar.getTime(),true));
        assertEquals("phigros_advisor-data-20261007-090530-debug.atsbackup",ExportFileNames.dataBackup("phigros_advisor",calendar.getTime(),true));
        String name=ExportFileNames.dataBackup("bad/name:*?",calendar.getTime(),false);
        assertTrue(name.endsWith("-data-20261007-090530.atsbackup"));
        assertFalse(name.matches(".*[\\\\/:*?\"<>|].*"));
    }

    @Test public void pluginPackageNamesKeepVersionAndDistinguishDebugExportsWithoutTime() {
        assertEquals("phigros_advisor-v3.2.0.atsplugin",ExportFileNames.pluginPackage("phigros_advisor","3.2.0",false));
        assertEquals("phigros_advisor-v3.2.0-debug.atsplugin",ExportFileNames.pluginPackage("phigros_advisor","3.2.0",true));
        assertEquals("phigros_advisor-v3.2.0-debug.atsplugin",ExportFileNames.pluginPackage("phigros_advisor","3.2.0-debug",true));
        String name=ExportFileNames.pluginPackage("bad/name:*?","2.1.0/dev",true);
        assertTrue(name.endsWith("-v2.1.0-dev-debug.atsplugin"));
        assertFalse(name.matches(".*[\\\\/:*?\"<>|].*"));
    }

    @Test public void everyPluginFileExportUsesTheHostBuildMarkerAndKeepsItsExtension() {
        assertEquals("UIGF-20261007-090530-debug.json",ExportFileNames.forBuild("UIGF-20261007-090530.json",true));
        assertEquals("Phigros-B30-20261007-090530-debug.png",ExportFileNames.forBuild("Phigros-B30-20261007-090530.png",true));
        assertEquals("曲绘成绩-debug.PNG",ExportFileNames.forBuild("曲绘成绩.PNG",true));
        assertEquals("report-debug.csv",ExportFileNames.forBuild("report-debug.csv",true));
        assertEquals("report.json",ExportFileNames.forBuild("report.json",false));
        assertEquals("report-debug",ExportFileNames.forBuild("report",true));
    }
}
