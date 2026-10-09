package com.androidtoolsuite.app.host;

import static org.junit.Assert.*;
import android.os.SystemClock;
import android.graphics.Bitmap;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class CollectionsInstrumentedTest {
    private JSONObject snapshot(MainActivity activity) {
        try {var method=MainActivity.class.getDeclaredMethod("captureHostSettings");method.setAccessible(true);return (JSONObject)method.invoke(activity);}
        catch(Exception error){throw new AssertionError(error);}
    }
    private void restore(MainActivity activity,JSONObject state) {
        try {var method=MainActivity.class.getDeclaredMethod("applyHostSettings",JSONObject.class);method.setAccessible(true);assertEquals(true,method.invoke(activity,state));activity.navigateForUi(0);}
        catch(Exception error){throw new AssertionError(error);}
    }
    @Test public void widgetFolderContainsRealWidgetsAndDissolvingRetainsThem() throws Exception {
        try(var scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<JSONObject> backup=new AtomicReference<>();AtomicReference<String> folder=new AtomicReference<>();
            scenario.onActivity(activity->{backup.set(snapshot(activity));var widgets=activity.widgetsForUi();assertTrue(widgets.size()>=2);
                var members=widgets.subList(0,2).stream().map(widget->widget.pluginId()+":"+widget.id()).toList();
                assertTrue(activity.saveFolderForUi(HostFolders.HOME,null,"验收文件夹",members));
                folder.set(activity.foldersForUi(HostFolders.HOME).stream().filter(item->item.name.equals("验收文件夹")).findFirst().orElseThrow().id);
                activity.navigateForUi(0);
            });
            try{
                awaitText("验收文件夹");capture("collections-home.png");
                scenario.onActivity(activity->activity.openFolderForUi(folder.get()));
                awaitText("编辑文件夹");capture("collections-home-folder.png");
                scenario.onActivity(activity->{int before=activity.allWidgetsForUi().size();activity.dissolveFolderForUi(folder.get());assertEquals(before,activity.allWidgetsForUi().size());assertNull(HostFolders.find(activity.foldersForUi(HostFolders.HOME),folder.get()));});
            }finally{scenario.onActivity(activity->restore(activity,backup.get()));}
        }
    }
    @Test public void toolsCombineManagementAndFoldersWithoutRemovingPluginDetails() throws Exception {
        try(var scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<JSONObject> backup=new AtomicReference<>();AtomicReference<String> folder=new AtomicReference<>();
            scenario.onActivity(activity->{backup.set(snapshot(activity));var descriptors=activity.importedDescriptorsForUi();assertTrue(descriptors.size()>=2);
                assertTrue(activity.saveFolderForUi(HostFolders.TOOLS,null,"系统工具",List.of(descriptors.get(0).id,descriptors.get(1).id)));
                folder.set(activity.foldersForUi(HostFolders.TOOLS).stream().filter(item->item.name.equals("系统工具")).findFirst().orElseThrow().id);
                activity.navigateForUi(2);assertEquals(1,activity.currentSectionForUi());
            });
            try{
                awaitText("系统工具");capture("collections-tools.png");
                scenario.onActivity(activity->activity.openFolderForUi(folder.get()));
                awaitText("编辑文件夹");capture("collections-tool-folder.png");
                scenario.onActivity(activity->activity.openFolderForUi(null));
                scenario.onActivity(activity->{var descriptor=activity.importedDescriptorsForUi().get(0);activity.showPluginManagementForUi(descriptor.id,"detail");});
                awaitText("启用插件");capture("collections-plugin-details.png");
                scenario.onActivity(activity->{activity.handleBackForUi();assertEquals(1,activity.currentSectionForUi());});
                scenario.onActivity(activity->{
                    String id=activity.importedDescriptorsForUi().get(0).id;boolean enabled=activity.isImportedPluginEnabled(id);
                    activity.setToolVisibleByIdForUi(id,false);
                    assertFalse(activity.isToolVisibleByIdForUi(id));
                    assertNull(HostFolders.containing(activity.foldersForUi(HostFolders.TOOLS),HostFolders.TOOLS,id));
                    assertEquals(enabled,activity.isImportedPluginEnabled(id));
                    activity.moveFolderMemberForUi(HostFolders.TOOLS,id,folder.get());
                    assertTrue(activity.isToolVisibleByIdForUi(id));
                    assertEquals(folder.get(),HostFolders.containing(activity.foldersForUi(HostFolders.TOOLS),HostFolders.TOOLS,id).id);
                });
            }finally{scenario.onActivity(activity->restore(activity,backup.get()));}
        }
    }
    @Test public void compactSystemWidgetsAcceptOneUnitWithoutChangingTheirConfiguration() throws Exception {
        try(var scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<JSONObject> backup=new AtomicReference<>();
            scenario.onActivity(activity->{backup.set(snapshot(activity));for(var widget:activity.widgetsForUi())if(widget.pluginId().equals("shizuku_auth")||widget.pluginId().equals("accessibility_grant")){
                assertTrue(widget.supportedSizes().contains(new com.androidtoolsuite.app.plugin.api.HomeWidgetSize(1,1)));
                activity.setWidgetSizeForUi(widget,1,1);assertEquals(1,activity.widgetWidthUnitsForUi(widget));assertEquals(1,activity.widgetHeightUnitsForUi(widget));
            }activity.navigateForUi(0);});
            try{awaitText("常用，一眼可见");capture("collections-small-widgets.png");}
            finally{scenario.onActivity(activity->restore(activity,backup.get()));}
        }
    }
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node,String text){
        if(node==null)return null;node.refresh();
        if(node.isVisibleToUser()&&(text.contentEquals(node.getText()==null?"":node.getText())||text.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())))return node;
        for(int i=0;i<node.getChildCount();i++){var child=find(node.getChild(i),text);if(child!=null)return child;}return null;
    }
    private static void awaitText(String text){
        long end=SystemClock.uptimeMillis()+15000;
        do{if(find(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),text)!=null)return;SystemClock.sleep(100);}while(SystemClock.uptimeMillis()<end);
        fail("Expected UI: "+text);
    }
    private static void capture(String name)throws Exception{
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        try(var output=new FileOutputStream(new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}
    }
}
