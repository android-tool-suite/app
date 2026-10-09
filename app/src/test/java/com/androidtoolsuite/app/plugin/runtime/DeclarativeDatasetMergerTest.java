package com.androidtoolsuite.app.plugin.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class DeclarativeDatasetMergerTest {
    private Object merge(String a,String b,String rule) throws Exception {
        return DeclarativeDatasetMerger.value(new org.json.JSONTokener(a).nextValue(),
                new org.json.JSONTokener(b).nextValue(),new JSONObject(rule),0);
    }

    @Test public void settingsAndCredentialsRetainLocalValuesAndAddMissingKeys() throws Exception {
        var result=(JSONObject)merge("{\"preferences\":{\"theme\":{\"type\":\"string\",\"value\":\"dark\"}},\"cookie\":\"fixture-current\"}",
                "{\"preferences\":{\"theme\":{\"type\":\"string\",\"value\":\"light\"},\"new\":{\"value\":true}},\"cookie\":\"fixture-old\"}","{\"kind\":\"object\"}");
        assertEquals("dark",result.getJSONObject("preferences").getJSONObject("theme").getString("value"));
        assertTrue(result.getJSONObject("preferences").getJSONObject("new").getBoolean("value"));
        assertEquals("fixture-current",result.getString("cookie"));
    }

    @Test public void recordsMergeByAccountPoolAndIDIncludingLegacyPoolAliases() throws Exception {
        String rule="{\"kind\":\"array\",\"keys\":[\"uid\",\"uigf_gacha_type|gacha_type\",\"id\"],\"requireAllKeys\":true,\"aliases\":{\"uigf_gacha_type|gacha_type\":{\"400\":\"301\"}}}";
        var result=(JSONArray)merge("[{\"uid\":\"8\",\"gacha_type\":\"400\",\"id\":\"1\",\"name\":\"existing\"}]",
                "[{\"uid\":\"8\",\"gacha_type\":\"301\",\"id\":\"1\",\"item_id\":\"a\"},{\"uid\":\"9\",\"gacha_type\":\"301\",\"id\":\"1\"},{\"uid\":\"8\",\"gacha_type\":\"302\",\"id\":\"1\"}]",rule);
        assertEquals(3,result.length());assertEquals("existing",result.getJSONObject(0).getString("name"));
        assertEquals("a",result.getJSONObject(0).getString("item_id"));
        assertThrows(java.io.IOException.class,()->merge("[]","[{\"id\":\"1\"}]",rule));
    }

    @Test public void cloudSaveMergeNeverRollsTheCurrentSnapshotBack() throws Exception {
        var result=(JSONObject)merge("{\"profile\":{\"saveUpdatedAt\":\"2026-10-08T00:00:00Z\"},\"records\":[{\"score\":1000000}]}",
                "{\"profile\":{\"saveUpdatedAt\":\"2026-10-07T00:00:00Z\"},\"records\":[{\"score\":900000}]}",
                "{\"kind\":\"newer\",\"timestamp\":\"profile.saveUpdatedAt\"}");
        assertEquals(1000000,result.getJSONArray("records").getJSONObject(0).getInt("score"));
    }

    @Test public void restoredLegacyAutomaticRulesAreExplicitAndIndependentOfNewFavorites() throws Exception {
        String rule="{\"kind\":\"object\",\"legacySets\":{\"autoRestore\":{\"source\":\"favorites\",\"when\":\"autoGrant\"}},\"constants\":{\"autoGrant\":false},\"fields\":{\"favorites\":{\"kind\":\"set\"},\"autoRestore\":{\"kind\":\"set\"}}}";
        var result=(JSONObject)merge("{\"favorites\":[\"local/S\"],\"autoRestore\":[],\"autoGrant\":false}",
                "{\"favorites\":[\"backup/S\"],\"autoGrant\":true}",rule);
        assertEquals(2,result.getJSONArray("favorites").length());
        assertEquals("[\"backup/S\"]",result.getJSONArray("autoRestore").toString());
        assertFalse(result.getBoolean("autoGrant"));
    }

    @Test public void historyKeepsDistinctEvidenceAndAllPinnedCatalogRevisions() throws Exception {
        var timeline=(JSONArray)merge("[{\"source\":{\"saveUpdatedAt\":\"same\"},\"rawChanges\":[{\"score\":1}],\"analysis\":\"local\"}]",
                "[{\"rawChanges\":[{\"score\":1}],\"source\":{\"saveUpdatedAt\":\"same\"},\"analysis\":\"old\"},{\"source\":{\"saveUpdatedAt\":\"older\"},\"rawChanges\":[{\"score\":0}]}]",
                "{\"kind\":\"array\",\"keys\":[\"source|saveTimestamp|timestamp\",\"rawChanges|changes\"],\"items\":{\"kind\":\"current\"}}");
        assertEquals(2,timeline.length());assertEquals("local",timeline.getJSONObject(0).getString("analysis"));
        var catalogs=(JSONArray)merge("[{\"code\":320,\"revision\":\"local\"}]","[{\"code\":320,\"revision\":\"old\"}]",
                "{\"kind\":\"array\",\"keys\":[\"code\",\"revision\"],\"incomingFirst\":true}");
        assertEquals(2,catalogs.length());assertEquals("local",catalogs.getJSONObject(1).getString("revision"));
    }

    private File zip(String path,String body) throws Exception {
        File file=File.createTempFile("merge-contract-", ".zip");
        try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(file))){out.putNextEntry(new ZipEntry(path));out.write(body.getBytes(StandardCharsets.UTF_8));out.closeEntry();}
        return file;
    }

    @Test public void archiveMergeSelectsTheNewerLiveSaveAndRejectsUnsafeNamesWithoutChangingInputs() throws Exception {
        File local=zip("saves/save-a.json","{\"profile\":{\"saveUpdatedAt\":\"2026-10-08T00:00:00Z\"},\"score\":100}");
        File backup=zip("saves/save-a.json","{\"profile\":{\"saveUpdatedAt\":\"2026-10-07T00:00:00Z\"},\"score\":50}");
        File invalid=zip("../outside.json","{}");
        try{
            byte[] before=Files.readAllBytes(local.toPath());ByteArrayOutputStream merged=new ByteArrayOutputStream();
            JSONObject policy=new JSONObject("{\"format\":\"zip\",\"entries\":[{\"pattern\":\"saves/*.json\",\"rule\":{\"kind\":\"newer\",\"timestamp\":\"profile.saveUpdatedAt\"}}]}");
            DeclarativeDatasetMerger.merge(local,backup,policy,65536,merged);
            try(ZipInputStream stream=new ZipInputStream(new java.io.ByteArrayInputStream(merged.toByteArray()))){
                assertEquals("saves/save-a.json",stream.getNextEntry().getName());
                assertEquals(100,new JSONObject(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getInt("score"));
            }
            assertThrows(java.io.IOException.class,()->DeclarativeDatasetMerger.merge(local,invalid,policy,65536,new ByteArrayOutputStream()));
            assertArrayEquals(before,Files.readAllBytes(local.toPath()));
        }finally{local.delete();backup.delete();invalid.delete();}
    }
}
