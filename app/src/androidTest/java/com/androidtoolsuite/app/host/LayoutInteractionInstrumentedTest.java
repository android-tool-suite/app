package com.androidtoolsuite.app.host;

import static org.junit.Assert.*;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Real pointer input is necessary: invoking semantics actions bypasses parent gesture interception. */
@RunWith(AndroidJUnit4.class)
public class LayoutInteractionInstrumentedTest {
    private static JSONObject snapshot(MainActivity activity) {
        try{var method=MainActivity.class.getDeclaredMethod("captureHostSettings");method.setAccessible(true);return (JSONObject)method.invoke(activity);}
        catch(Exception error){throw new AssertionError(error);}
    }
    private static void restore(MainActivity activity,JSONObject value) {
        try{var method=MainActivity.class.getDeclaredMethod("applyHostSettings",JSONObject.class);method.setAccessible(true);assertEquals(true,method.invoke(activity,value));activity.navigateForUi(0);}
        catch(Exception error){throw new AssertionError(error);}
    }
    @Test public void detailsButtonReceivesTouchWithoutOpeningTheToolInstead() throws Exception {
        try(var scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<String> title=new AtomicReference<>(),id=new AtomicReference<>();
            scenario.onActivity(activity->{var plugin=activity.importedDescriptorsForUi().get(0);title.set(plugin.title);id.set(plugin.id);activity.navigateForUi(1);});
            tap(bounds("插件详情 "+title.get()));
            await(scenario,activity->id.get().equals(activity.managedPluginIdForUi()));
            assertNotNull(node("启用插件"));
        }
    }
    @Test public void emptyFolderNameEditorHasNoMemberPickerAndDragTransfersPreserveTheWidgets() throws Exception {
        try(var scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<JSONObject> backup=new AtomicReference<>();AtomicReference<String> source=new AtomicReference<>(),folder=new AtomicReference<>();
            scenario.onActivity(activity->{
                backup.set(snapshot(activity));
                try{JSONObject controlled=new JSONObject(backup.get().toString()).put("folders",new JSONArray()).put("gridLayout",new JSONObject());restore(activity,controlled);}
                catch(Exception error){throw new AssertionError(error);}
                var widgets=activity.allWidgetsForUi();var target=widgets.stream().filter(widget->widget.pluginId().equals("shizuku_auth")).findFirst().orElseThrow();
                for(var widget:widgets)activity.setWidgetVisibleForUi(widget,widget==target);
                activity.setWidgetSizeForUi(target,1,1);source.set(target.pluginId()+":"+target.id());
                assertTrue(activity.saveFolderForUi(HostFolders.HOME,null,"拖放测试",List.of()));
                folder.set(activity.foldersForUi(HostFolders.HOME).get(0).id);activity.navigateForUi(0);
            });
            try {
                scenario.onActivity(activity->activity.editFolderForUi(HostFolders.HOME,folder.get()));
                bounds("文件夹名称");assertNull(node("小部件"));assertNull(node("插件"));tap(bounds("取消"));
                Rect item=bounds("小部件 Shizuku"),cover=bounds("文件夹 拖放测试");
                drag(item.centerX(),item.centerY(),cover.centerX(),cover.centerY());
                await(scenario,activity->HostFolders.find(activity.foldersForUi(HostFolders.HOME),folder.get()).members.contains(source.get()));
                tap(bounds("文件夹 拖放测试"));
                item=bounds("小部件 Shizuku");Rect exit=bounds("拖到这里移回主页");
                drag(item.centerX(),item.centerY(),exit.centerX(),exit.centerY());
                await(scenario,activity->HostFolders.find(activity.foldersForUi(HostFolders.HOME),folder.get()).members.isEmpty());
                scenario.onActivity(activity->{activity.openFolderForUi(null);assertTrue(activity.allWidgetsForUi().stream().anyMatch(widget->(widget.pluginId()+":"+widget.id()).equals(source.get())));});
                tap(bounds("排列"));item=bounds("小部件 Shizuku");cover=bounds("文件夹 拖放测试");
                int stepX=cover.left-item.left,stepY=item.height()+Math.round(12*InstrumentationRegistry.getInstrumentation().getTargetContext().getResources().getDisplayMetrics().density);
                drag(item.centerX(),item.centerY(),item.centerX()+3*stepX,item.centerY()+2*stepY);
                await(scenario,activity->{var cell=activity.gridPositionsForUi("home").optJSONObject(source.get());return cell!=null&&cell.optInt("column")==3&&cell.optInt("row")==2;});
                scenario.onActivity(activity->{var state=snapshot(activity);restore(activity,state);assertEquals(2,activity.gridPositionsForUi("home").optJSONObject(source.get()).optInt("row"));});
            } finally {scenario.onActivity(activity->restore(activity,backup.get()));}
        }
    }
    @Test public void toolCanBeDraggedIntoAndOutOfAnEmptyFolder() throws Exception {
        try(var scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<JSONObject> backup=new AtomicReference<>();AtomicReference<String> id=new AtomicReference<>(),title=new AtomicReference<>(),folder=new AtomicReference<>();
            scenario.onActivity(activity->{
                backup.set(snapshot(activity));try{restore(activity,new JSONObject(backup.get().toString()).put("folders",new JSONArray()));}catch(Exception error){throw new AssertionError(error);}
                var plugin=activity.importedDescriptorsForUi().get(0);id.set(plugin.id);title.set(plugin.title);
                for(var descriptor:activity.importedDescriptorsForUi())activity.setToolVisibleByIdForUi(descriptor.id,descriptor.id.equals(id.get()));
                assertTrue(activity.saveFolderForUi(HostFolders.TOOLS,null,"工具文件夹",List.of()));folder.set(activity.foldersForUi(HostFolders.TOOLS).get(0).id);activity.navigateForUi(1);
            });
            try {
                Rect source=bounds(title.get()),target=bounds("工具文件夹");drag(source.centerX(),source.centerY(),target.centerX(),target.centerY());
                await(scenario,activity->HostFolders.find(activity.foldersForUi(HostFolders.TOOLS),folder.get()).members.contains(id.get()));
                tap(bounds("工具文件夹"));source=bounds(title.get());target=bounds("拖到这里移回工具页");drag(source.centerX(),source.centerY(),target.centerX(),target.centerY());
                await(scenario,activity->HostFolders.find(activity.foldersForUi(HostFolders.TOOLS),folder.get()).members.isEmpty());
            }finally{scenario.onActivity(activity->restore(activity,backup.get()));}
        }
    }
    @Test public void folderSupportsSmallCoverAndRenderedTwoUnitPreviewAndRestoresBoth() throws Exception {
        try(var scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<JSONObject> backup=new AtomicReference<>();AtomicReference<String> id=new AtomicReference<>();
            scenario.onActivity(activity->{
                backup.set(snapshot(activity));try{restore(activity,new JSONObject(backup.get().toString()).put("folders",new JSONArray()));}catch(Exception error){throw new AssertionError(error);}
                var widgets=activity.widgetsForUi();assertTrue(widgets.size()>=2);var members=widgets.subList(0,2).stream().map(widget->widget.pluginId()+":"+widget.id()).toList();
                assertTrue(activity.saveFolderForUi(HostFolders.HOME,null,"缩略测试",members));id.set(activity.foldersForUi(HostFolders.HOME).get(0).id);
                var folder=new FolderHomeWidget(activity.foldersForUi(HostFolders.HOME).get(0),activity);
                activity.setWidgetSizeForUi(folder,2,2);activity.navigateForUi(0);
            });
            try {
                bounds("文件夹 缩略测试");capture("folder-rendered-preview.png");
                scenario.onActivity(activity->{var value=snapshot(activity);restore(activity,value);var folder=new FolderHomeWidget(activity.foldersForUi(HostFolders.HOME).get(0),activity);assertEquals(2,activity.widgetWidthUnitsForUi(folder));activity.setWidgetSizeForUi(folder,1,1);});
                bounds("文件夹 缩略测试");capture("folder-small-cover.png");
            }finally{scenario.onActivity(activity->restore(activity,backup.get()));}
        }
    }
    private static void capture(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(300);
        var bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        try(var out=new java.io.FileOutputStream(new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),name))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
    private static void await(ActivityScenario<MainActivity> scenario,Predicate<MainActivity> condition) {
        long deadline=SystemClock.uptimeMillis()+10000;var result=new AtomicReference<Boolean>();
        do{scenario.onActivity(activity->result.set(condition.test(activity)));if(Boolean.TRUE.equals(result.get()))return;SystemClock.sleep(40);}while(SystemClock.uptimeMillis()<deadline);
        fail("Expected model after actual touch input");
    }
    private static AccessibilityNodeInfo node(String text){return find(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),text);}
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root,String text){
        if(root==null||!root.refresh())return null;
        if(root.isVisibleToUser()&&(text.contentEquals(root.getText()==null?"":root.getText())||text.contentEquals(root.getContentDescription()==null?"":root.getContentDescription())))return root;
        for(int i=0;i<root.getChildCount();i++){var child=find(root.getChild(i),text);if(child!=null)return child;}return null;
    }
    private static Rect bounds(String text){
        long deadline=SystemClock.uptimeMillis()+12000;
        do{var element=node(text);if(element!=null){var rect=new Rect();element.getBoundsInScreen(rect);if(!rect.isEmpty())return rect;}SystemClock.sleep(50);}while(SystemClock.uptimeMillis()<deadline);
        fail("Expected visible UI: "+text);return null;
    }
    private static void tap(Rect rect){long start=SystemClock.uptimeMillis();event(start,start,MotionEvent.ACTION_DOWN,rect.centerX(),rect.centerY());SystemClock.sleep(80);event(start,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,rect.centerX(),rect.centerY());}
    private static void drag(float x,float y,float targetX,float targetY){
        long start=SystemClock.uptimeMillis();event(start,start,MotionEvent.ACTION_DOWN,x,y);SystemClock.sleep(650);
        for(int i=1;i<=20;i++){event(start,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,x+(targetX-x)*i/20,y+(targetY-y)*i/20);SystemClock.sleep(20);}
        SystemClock.sleep(80);event(start,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,targetX,targetY);SystemClock.sleep(300);
    }
    private static void event(long down,long time,int action,float x,float y){
        var event=MotionEvent.obtain(down,time,action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(event,true));}finally{event.recycle();}
    }
}
