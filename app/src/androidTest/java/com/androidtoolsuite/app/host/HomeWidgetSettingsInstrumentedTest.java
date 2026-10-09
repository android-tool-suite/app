package com.androidtoolsuite.app.host;

import static org.junit.Assert.*;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class HomeWidgetSettingsInstrumentedTest {
    @Test public void hostBackupAndScopedRestoreKeepInstanceBindingsAndPins() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->{
                org.json.JSONObject original=null;
                try {
                    var capture=MainActivity.class.getDeclaredMethod("captureHostSettings");capture.setAccessible(true);
                    var apply=MainActivity.class.getDeclaredMethod("applyHostSettings",org.json.JSONObject.class);apply.setAccessible(true);
                    var merge=MainActivity.class.getDeclaredMethod("mergeHostSettings",org.json.JSONObject.class,org.json.JSONObject.class,java.util.Set.class);merge.setAccessible(true);
                    original=(org.json.JSONObject)capture.invoke(activity);
                    var current=new org.json.JSONObject(original.toString()).put("widgetInstances",new org.json.JSONArray("[{\"pluginId\":\"test.one\",\"id\":\"first\",\"presetId\":\"rks\",\"configuration\":{\"profileId\":\"one\"}},{\"pluginId\":\"test.two\",\"id\":\"second\",\"presetId\":\"summary\",\"configuration\":{\"account\":\"two\"}}]")).put("pinnedTools",new org.json.JSONArray("[\"test.one\",\"test.two\"]"));
                    assertEquals(true,apply.invoke(activity,current));
                    var restored=(org.json.JSONObject)capture.invoke(activity);
                    assertEquals("two",restored.getJSONArray("widgetInstances").getJSONObject(1).getJSONObject("configuration").getString("account"));
                    assertEquals(2,restored.getJSONArray("pinnedTools").length());
                    var incoming=new org.json.JSONObject(current.toString());incoming.getJSONArray("widgetInstances").getJSONObject(0).getJSONObject("configuration").put("profileId","updated");incoming.getJSONArray("widgetInstances").getJSONObject(1).getJSONObject("configuration").put("account","must-not-leak");
                    var scoped=(org.json.JSONObject)merge.invoke(activity,current,incoming,java.util.Set.of("test.one"));
                    var instances=HomeWidgetInstances.parse(scoped.getJSONArray("widgetInstances"));
                    assertEquals("two",instances.stream().filter(x->x.pluginId.equals("test.two")).findFirst().get().configuration.getString("account"));
                    assertEquals("updated",instances.stream().filter(x->x.pluginId.equals("test.one")).findFirst().get().configuration.getString("profileId"));
                }catch(Exception error){throw new AssertionError(error);}
                finally{if(original!=null)try{var apply=MainActivity.class.getDeclaredMethod("applyHostSettings",org.json.JSONObject.class);apply.setAccessible(true);assertEquals(true,apply.invoke(activity,original));}catch(Exception error){throw new AssertionError(error);}}
            });
        }
    }
    @Test public void instanceEditsPersistIndependentlyAndRejectStaleSaves() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences preferences=context.getSharedPreferences("widget-test-"+java.util.UUID.randomUUID(),0);
        try {
            var manifest=com.androidtoolsuite.runtime.contract.RuntimePluginManifest.parse("""
              {"format":"ats-plugin","formatVersion":3,"plugin":{"id":"test.widgets","title":"Widgets","description":"Fixture","version":"1.0.0","versionCode":1,"minHostVersionCode":24,"publisher":"test.publisher","kind":"tool"},"platforms":["android"],"runtime":{"ui":[{"id":"main","type":"declarative","entry":"ui/main.json"}]},"requires":{"capabilities":[{"id":"app","version":"^1.0.0","optional":false,"scopes":{}}]},"contributes":{"tools":[{"id":"main","uiEntry":"main"}],"homeWidgets":[{"id":"summary","title":"Summary","template":"metric","dataSource":"app.getSession","sizes":["2x2","4x2"]}]}}
              """);
            var preset=manifest.homeWidgetContributions.get(0);HomeWidgetInstances store=new HomeWidgetInstances(preferences);
            var a=store.save("test.widgets",null,preset,new org.json.JSONObject().put("account","one"),0);
            var b=store.save("test.widgets",null,preset,new org.json.JSONObject().put("account","two"),0);
            assertNotEquals(a.id,b.id);assertNull(store.find("other.plugin",a.id));
            var next=store.save(a.pluginId,a.id,preset,new org.json.JSONObject().put("account","three"),a.revision);
            assertEquals("two",new HomeWidgetInstances(preferences).find(b.pluginId,b.id).configuration.getString("account"));
            try {store.save(a.pluginId,a.id,preset,new org.json.JSONObject(),a.revision);fail("Stale edit accepted");}catch(IllegalStateException expected){}
            assertEquals("three",store.find(a.pluginId,a.id).configuration.getString("account"));
            assertEquals(2,HomeWidgetInstances.parse(HomeWidgetInstances.encode(store.all())).size());
            java.io.File directory=context.getCacheDir();
            var constructor=com.androidtoolsuite.app.plugin.runtime.PluginPackageStore.InstalledPlugin.class.getDeclaredConstructor(com.androidtoolsuite.runtime.contract.RuntimePluginManifest.class,java.io.File.class,java.io.File.class,boolean.class,String.class,String.class,boolean.class);
            constructor.setAccessible(true);
            var installed=constructor.newInstance(manifest,directory,new java.io.File(directory,"fixture"),true,"test","",false);
            store.ensureLegacy(java.util.List.of(installed));store.ensureLegacy(java.util.List.of(installed));
            assertEquals("Migration must not duplicate existing instances",2,store.all().size());
            assertEquals(next.revision,store.find(a.pluginId,a.id).revision);
        }finally{preferences.edit().clear().commit();}
    }
    @Test public void addButtonOpensAndCancelsWithoutChangingInstances() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            final String[] before={null};
            scenario.onActivity(activity->{activity.navigateForUi(0);before[0]=HomeWidgetInstances.encode(activity.widgetInstancesForUi().all()).toString();});
            assertTrue("Widget manager unavailable",waitClick("小部件"));
            scenario.onActivity(activity->assertEquals("",activity.widgetSettingsPluginForUi()));
            assertTrue("Configuration dialog missing",waitText("首页小部件"));
            assertTrue(waitClick("返回"));
            scenario.onActivity(activity->{assertNull(activity.widgetSettingsPluginForUi());assertEquals(before[0],HomeWidgetInstances.encode(activity.widgetInstancesForUi().all()).toString());});
        }
    }
    @Test public void removingAnInstanceClearsItsLayoutAndNeverRecreatesItThroughMigration() throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        var preferences=context.getSharedPreferences("widget-remove-test-"+java.util.UUID.randomUUID(),0);
        try {
            var manifest=com.androidtoolsuite.runtime.contract.RuntimePluginManifest.parse("""
              {"format":"ats-plugin","formatVersion":3,"plugin":{"id":"test.widgets","title":"Widgets","description":"Fixture","version":"1.0.0","versionCode":1,"minHostVersionCode":24,"publisher":"test.publisher","kind":"tool"},"platforms":["android"],"runtime":{"ui":[{"id":"main","type":"declarative","entry":"ui/main.json"}]},"requires":{"capabilities":[{"id":"app","version":"^1.0.0","optional":false,"scopes":{}}]},"contributes":{"tools":[{"id":"main","uiEntry":"main"}],"homeWidgets":[{"id":"summary","title":"Summary","template":"metric","dataSource":"app.getSession","sizes":["2x2","4x2"]}]}}
              """);
            var store=new HomeWidgetInstances(preferences);var preset=manifest.homeWidgetContributions.get(0);
            var a=store.save("test.widgets",null,preset,new org.json.JSONObject().put("account","one"),0);
            var b=store.save("test.widgets",null,preset,new org.json.JSONObject().put("account","two"),0);
            String key=a.pluginId+":"+a.id,other=b.pluginId+":"+b.id;
            preferences.edit().putString("widget_order",key+"\n"+other).putStringSet("hidden_widgets",java.util.Set.of(key,other))
                    .putStringSet("widget_sizes",java.util.Set.of(key+"=4x2",other+"=2x2")).putStringSet("full_width_widgets",java.util.Set.of(key)).commit();
            try{store.remove(a.pluginId,a.id,a.revision+1);fail("Stale delete accepted");}catch(IllegalStateException expected){}
            assertEquals(2,store.all().size());store.remove(a.pluginId,a.id,a.revision);
            assertNull(new HomeWidgetInstances(preferences).find(a.pluginId,a.id));assertNotNull(store.find(b.pluginId,b.id));
            assertEquals(other,preferences.getString("widget_order",""));assertEquals(java.util.Set.of(other),preferences.getStringSet("hidden_widgets",java.util.Set.of()));
            assertEquals(java.util.Set.of(other+"=2x2"),preferences.getStringSet("widget_sizes",java.util.Set.of()));assertTrue(preferences.getStringSet("full_width_widgets",java.util.Set.of()).isEmpty());
            store.remove(b.pluginId,b.id,b.revision);
            var directory=context.getCacheDir();
            var constructor=com.androidtoolsuite.app.plugin.runtime.PluginPackageStore.InstalledPlugin.class.getDeclaredConstructor(com.androidtoolsuite.runtime.contract.RuntimePluginManifest.class,java.io.File.class,java.io.File.class,boolean.class,String.class,String.class,boolean.class);
            constructor.setAccessible(true);
            var installed=constructor.newInstance(manifest,directory,new java.io.File(directory,"fixture"),true,"test","",false);
            store.ensureLegacy(java.util.List.of(installed));assertTrue("Deleted instances must stay deleted",store.all().isEmpty());
        } finally {preferences.edit().clear().commit();}
    }
    @Test public void backupRoundTripPreservesTheExplicitEmptyLayoutMigrationMarkers() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->{
                org.json.JSONObject original=null;
                try {
                    var capture=MainActivity.class.getDeclaredMethod("captureHostSettings");capture.setAccessible(true);
                    var apply=MainActivity.class.getDeclaredMethod("applyHostSettings",org.json.JSONObject.class);apply.setAccessible(true);
                    original=(org.json.JSONObject)capture.invoke(activity);
                    var empty=new org.json.JSONObject(original.toString()).put("widgetInstances",new org.json.JSONArray()).put("widgetMigrations",new org.json.JSONArray().put("phigros_advisor").put("gacha_analysis"));
                    assertEquals(true,apply.invoke(activity,empty));
                    var backup=(org.json.JSONObject)capture.invoke(activity);assertEquals(0,backup.getJSONArray("widgetInstances").length());
                    assertTrue(backup.getJSONArray("widgetMigrations").toString().contains("gacha_analysis"));
                    assertEquals(true,apply.invoke(activity,backup));
                    assertTrue(activity.widgetInstancesForUi().all().isEmpty());
                    var preferences=activity.getSharedPreferences("main_ui",0);
                    assertTrue(preferences.getStringSet("widget_instances_migrated",java.util.Set.of()).contains("phigros_advisor"));
                    // Legacy backups may omit the marker, but an explicit empty layout stays empty.
                    backup.remove("widgetMigrations");assertEquals(true,apply.invoke(activity,backup));
                    for(var plugin:activity.widgetPluginsForUi())if(!plugin.manifest.homeWidgetContributions.isEmpty())
                        assertTrue(preferences.getStringSet("widget_instances_migrated",java.util.Set.of()).contains(plugin.manifest.plugin.id));
                } catch(Exception error){throw new AssertionError(error);}
                finally{if(original!=null)try{var apply=MainActivity.class.getDeclaredMethod("applyHostSettings",org.json.JSONObject.class);apply.setAccessible(true);assertEquals(true,apply.invoke(activity,original));}catch(Exception error){throw new AssertionError(error);}}
            });
        }
    }
    private static boolean waitClick(String text){return waitNode(text,true);}
    private static boolean waitText(String text){return waitNode(text,false);}
    private static boolean waitNode(String text,boolean click){
        long end=SystemClock.uptimeMillis()+8000;
        do{InstrumentationRegistry.getInstrumentation().waitForIdleSync();AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();AccessibilityNodeInfo found=find(root,text);if(found!=null){if(!click)return true;while(found!=null&&!found.isClickable())found=found.getParent();if(found!=null&&found.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;}SystemClock.sleep(50);}while(SystemClock.uptimeMillis()<end);
        return false;
    }
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node,String text){
        if(node==null)return null;node.refresh();
        if(node.isEnabled()&&(text.contentEquals(node.getText()==null?"":node.getText())||text.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),text);if(found!=null)return found;}return null;
    }
}
