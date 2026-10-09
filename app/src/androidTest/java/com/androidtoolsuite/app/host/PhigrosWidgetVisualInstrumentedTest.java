package com.androidtoolsuite.app.host;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.androidtoolsuite.app.plugin.runtime.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;

/** Renders synthetic local history and scores, restoring all original data and layout afterwards. */
@RunWith(AndroidJUnit4.class)
public class PhigrosWidgetVisualInstrumentedTest {
    private String originalTheme;
    @org.junit.Before public void applyAppearance(){String requested=InstrumentationRegistry.getArguments().getString("uiTheme");if(requested==null)return;try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){scenario.onActivity(activity->{originalTheme=activity.themePreferenceForUi();activity.setThemePreferenceForUi(requested);});}}
    @org.junit.After public void restoreAppearance(){if(originalTheme==null)return;try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){scenario.onActivity(activity->activity.setThemePreferenceForUi(originalTheme));}}
    @Test public void actualTrendAndDefaultTargetAccuracyAreVisible() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        PluginRuntime runtime=PluginRuntime.get(context);DatasetService datasets=runtime.datasets();
        var installed=runtime.packages().find("phigros_advisor");assertNotNull("Install integration plugins before this suite",installed);
        Map<String,byte[]> previous=new LinkedHashMap<>();
        for(String id:List.of("profiles","analysis-data")){if(datasets.hasDataset("phigros_advisor",id)){ByteArrayOutputStream out=new ByteArrayOutputStream();datasets.exportDataset("phigros_advisor",id,out);previous.put(id,out.toByteArray());}else previous.put(id,null);}
        var preferences=context.getSharedPreferences("main_ui",0);String oldInstances=preferences.getString(HomeWidgetInstances.KEY,"[]"),oldOrder=preferences.getString("widget_order","");
        var oldSizes=new java.util.LinkedHashSet<>(preferences.getStringSet("widget_sizes",java.util.Collections.emptySet()));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            try {
                JSONObject profiles=new JSONObject().put("formatVersion",1).put("selectedProfile","widget-fixture").put("profiles",new JSONArray().put(new JSONObject().put("id","widget-fixture").put("label","UI测试档案").put("server","CN").put("loggedOut",true)));
                byte[] save;try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("widget-phi-save.json")){save=input.readAllBytes();}
                long now=System.currentTimeMillis();JSONArray history=new JSONArray();for(int i=0;i<3;i++)history.put(new JSONObject().put("timestamp",now-(3-i)*86400000L).put("newRks",new double[]{15.83,15.80,15.82}[i]).put("changes",new JSONArray()));
                ByteArrayOutputStream archive=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(archive)){entry(zip,"saves/save-widget-fixture.json",save);entry(zip,"timeline-widget-fixture.json",history.toString().getBytes(StandardCharsets.UTF_8));}
                datasets.importDataset("phigros_advisor","profiles","replace",new ByteArrayInputStream(profiles.toString().getBytes(StandardCharsets.UTF_8)));
                datasets.importDataset("phigros_advisor","analysis-data","replace",new ByteArrayInputStream(archive.toByteArray()));
                final HomeWidgetInstances.Instance[] instance={null};
                JSONObject configuration=new JSONObject().put("profileId","widget-fixture").put("days","30");
                scenario.onActivity(activity->{instance[0]=activity.widgetInstancesForUi().save("phigros_advisor",null,installed.manifest.homeWidgetContributions.get(0),configuration,0);preferences.edit().putString("widget_order","phigros_advisor:"+instance[0].id+"\n"+oldOrder).commit();activity.widgetSavedForUi(instance[0],"4x2");activity.navigateForUi(0);activity.invalidateComposeUi();});
                long deadline=SystemClock.uptimeMillis()+15000;final boolean[] ready={false};
                do {scenario.onActivity(activity->{JSONObject value=runtime.widgetSnapshots().entry(installed,installed.manifest.homeWidgetContributions.get(0),instance[0].id,instance[0].configuration,instance[0].revision).getValue();ready[0]=value!=null&&value.optJSONArray("points")!=null&&value.optJSONArray("points").length()==3;});if(!ready[0])SystemClock.sleep(80);}while(!ready[0]&&SystemClock.uptimeMillis()<deadline);
                assertTrue("Actual worker trend did not reach the host",ready[0]);InstrumentationRegistry.getInstrumentation().waitForIdleSync();capture(context,"widget-fixture-home.png");
                android.view.accessibility.AccessibilityNodeInfo chart=null;
                long chartDeadline=SystemClock.uptimeMillis()+10000;
                do{chart=findChart(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());if(chart==null)SystemClock.sleep(80);}while(chart==null&&SystemClock.uptimeMillis()<chartDeadline);
                assertNotNull("The native trend chart must be exposed and visible",chart);
                android.graphics.Rect chartBounds=new android.graphics.Rect();chart.getBoundsInScreen(chartBounds);
                float chartDensity=context.getResources().getDisplayMetrics().density;
                float contentWidth=Math.min(context.getResources().getConfiguration().screenWidthDp,720);
                assertTrue("4x2 must give roughly half of the card to the chart",chartBounds.width()/chartDensity>=(contentWidth-84)/2-3);
                assertTrue("The trend must have usable vertical space",chartBounds.height()/chartDensity>=40);
                capture(context,"widget-fixture-home.png");
                final HostTool[] tool={null};scenario.onActivity(activity->{activity.openPluginForUi("phigros_advisor");tool[0]=activity.selectedPluginForUi();});
                awaitScript(tool[0],"!!document.querySelector('#rks-trend svg')");
                String bounds=script(tool[0],"(()=>{const b=[...document.querySelectorAll('#tabs button')].find(b=>b.textContent==='成绩').getBoundingClientRect();return [b.x+b.width/2,b.y+b.height/2]})()");JSONArray point=new JSONArray(bounds);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{WebView view=view(tool[0]);float density=view.getResources().getDisplayMetrics().density;long time=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,(float)point.optDouble(0)*density,(float)point.optDouble(1)*density,0),up=MotionEvent.obtain(time,time+40,MotionEvent.ACTION_UP,down.getX(),down.getY(),0);try{view.dispatchTouchEvent(down);view.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}});
                awaitScript(tool[0],"!!document.querySelector('.tab-pager[data-active-page=\"成绩\"][data-moving=\"false\"] .is-active .score-row:not([open]) > summary [data-push-identity]')");
                capture(context,"widget-fixture-scores.png");
            } finally {
                scenario.onActivity(activity->{activity.navigateForUi(0);activity.closeWidgetSettingsForUi();});
                try {
                    // Dependents must be removed before their required settings dataset.
                    for(String id:List.of("analysis-data","profiles"))if(previous.get(id)==null)datasets.delete("phigros_advisor",id);
                    for(var item:previous.entrySet())if(item.getValue()!=null)datasets.importDataset("phigros_advisor",item.getKey(),"replace",new ByteArrayInputStream(item.getValue()));
                } finally {
                    preferences.edit().putString(HomeWidgetInstances.KEY,oldInstances).putString("widget_order",oldOrder).putStringSet("widget_sizes",oldSizes).commit();scenario.onActivity(MainActivity::invalidateComposeUi);
                }
            }
        }
    }
    private static void entry(ZipOutputStream zip,String name,byte[] data)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}
    private static android.view.accessibility.AccessibilityNodeInfo findChart(android.view.accessibility.AccessibilityNodeInfo node){if(node==null)return null;CharSequence description=node.getContentDescription();if(description!=null&&description.toString().equals("RKS 趋势图，3 个历史节点"))return node;for(int index=0;index<node.getChildCount();index++){var result=findChart(node.getChild(index));if(result!=null)return result;}return null;}
    private static void capture(Context context,String name)throws IOException{Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);try(OutputStream out=new FileOutputStream(new File(context.getCacheDir(),name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();}
    private static Object field(Object target,String key){try{var field=target.getClass().getDeclaredField(key);field.setAccessible(true);return field.get(target);}catch(Exception error){throw new AssertionError(error);}}
    private static WebView view(HostTool tool){Object session=field(tool,"activeWebSession");return session==null?null:(WebView)field(session,"webView");}
    private static String script(HostTool tool,String code)throws Exception{AtomicReference<String> result=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{WebView view=view(tool);if(view==null){done.countDown();return;}view.evaluateJavascript(code,value->{result.set(value);done.countDown();});});assertTrue(done.await(3,TimeUnit.SECONDS));return result.get();}
    private static void awaitScript(HostTool tool,String code)throws Exception{long end=SystemClock.uptimeMillis()+15000;do{if("true".equals(script(tool,code)))return;SystemClock.sleep(80);}while(SystemClock.uptimeMillis()<end);fail("Expected real WebView content: "+code);}
}
