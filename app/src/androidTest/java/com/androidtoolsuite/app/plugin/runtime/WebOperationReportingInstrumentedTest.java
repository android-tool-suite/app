package com.androidtoolsuite.app.plugin.runtime;

import static org.junit.Assert.*;

import android.os.SystemClock;
import android.graphics.Rect;
import android.view.View;
import android.view.MotionEvent;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.lifecycle.Lifecycle;
import com.androidtoolsuite.app.host.MainActivity;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real page touch -> message bridge -> app capability -> host task model. */
@RunWith(AndroidJUnit4.class)
public final class WebOperationReportingInstrumentedTest {
    private static final String ID = "test.operation_report";
    private static final String SCRIPT = """
        (() => {
          const pluginId=document.querySelector('meta[name="ats-plugin-id"]').content;
          const sessionId=document.querySelector('meta[name="ats-session-id"]').content;
          let request=0,operation=0,id='';
          const pending=new Map();
          const state=document.getElementById('state');
          window.atsTransport.onmessage=({data})=>{
            const message=JSON.parse(data);
            if(message.kind==='ready')state.textContent='ready';
            if(message.kind==='response'){
              const status=pending.get(message.requestId);pending.delete(message.requestId);
              state.textContent=message.ok?status:'error:'+message.error.code;
            }
          };
          function report(status){
            const requestId=String(++request);pending.set(requestId,status);
            window.atsTransport.postMessage(JSON.stringify({protocol:'2.0',kind:'request',pluginId,sessionId,requestId,
              method:'app.reportOperation',payload:{id,label:'同步本地测试记录',status,detail:'本地状态验证'},deadlineMs:5000}));
          }
          document.getElementById('run').onclick=()=>{id='operation-'+(++operation);report('running');};
          window.finishOperation=()=>report('success');
          window.atsTransport.postMessage(JSON.stringify({protocol:'2.0',kind:'hello',pluginId,sessionId,requestId:'0',payload:{supportedProtocols:['2.0'],features:[]}}));
        })();
        """;

    @Test public void realUiActionReportsBackgroundCompletionAndSessionCloseCancelsRemaining() throws Exception {
        final File[] directory={null};
        final DeclarativeToolPlugin[] tool={null};
        final String[] first={null},second={null},raced={null};
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            try{
                scenario.onActivity(activity->{
                    try{
                        directory[0]=Files.createTempDirectory(activity.getCacheDir().toPath(),"operation-report-").toFile();
                        File ui=new File(directory[0],"ui"),web=new File(directory[0],"web");
                        assertTrue(ui.mkdir());assertTrue(web.mkdir());
                        Files.write(new File(ui,"main.json").toPath(),"{\"formatVersion\":1,\"body\":{\"type\":\"webview\",\"entry\":\"web/index.html\"}}".getBytes(StandardCharsets.UTF_8));
                        Files.write(new File(web,"index.html").toPath(),("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                            +"</head><body style=\"margin:0\"><button id=\"run\" style=\"width:100%;height:80px\">开始操作</button><p id=\"state\">starting</p><script src=\"test.js\"></script></body></html>").getBytes(StandardCharsets.UTF_8));
                        Files.write(new File(web,"test.js").toPath(),SCRIPT.getBytes(StandardCharsets.UTF_8));
                        RuntimePluginManifest manifest=RuntimePluginManifest.parse("""
                            {"format":"ats-plugin","formatVersion":3,
                             "plugin":{"id":"test.operation_report","title":"Operation report test","description":"Local metadata test",
                               "version":"1.0.0","versionCode":1,"minHostVersionCode":24,"publisher":"test.publisher","kind":"tool"},
                             "platforms":["android"],"runtime":{"ui":[{"id":"main","type":"declarative","entry":"ui/main.json"}]},
                             "requires":{"capabilities":[{"id":"app","version":"^1.0.0","optional":false,"scopes":{}}]},
                             "contributes":{"tools":[{"id":"main","uiEntry":"main"}]}}
                            """);
                        PluginRuntime.get(activity).permissions().reconcile(manifest);
                        tool[0]=new DeclarativeToolPlugin(new PluginPackageStore.InstalledPlugin(manifest,directory[0],
                            new File(directory[0],"fixture.atsplugin"),true,"test","",false),activity);
                        tools(activity).add(tool[0]);activity.navigateForUi(1);activity.openPluginForUi(tool[0]);
                    }catch(Exception error){throw new AssertionError(error);}
                });
                awaitState(tool[0],"ready");
                tapStart(tool[0]);awaitState(tool[0],"running");
                scenario.onActivity(activity->{
                    MainActivity.UiTask task=activity.tasksForUi().stream().filter(item->ID.equals(item.pluginId)).findFirst().orElseThrow(() -> new AssertionError("Expected plugin operation"));
                    first[0]=task.id;assertEquals("plugin",task.kind);assertEquals(ID,task.owner);assertEquals("running",task.status);
                    activity.navigateForUi(7);
                });
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->view(tool[0]).evaluateJavascript("finishOperation()",ignored->{}));
                awaitState(tool[0],"success");
                scenario.onActivity(activity->{
                    assertEquals("success",activity.tasksForUi().stream().filter(item->first[0].equals(item.id)).findFirst().orElseThrow(() -> new AssertionError("Expected plugin operation")).status);
                    activity.returnToTaskSourceForUi(first[0]);assertSame(tool[0],activity.selectedPluginForUi());
                });
                tapStart(tool[0]);awaitState(tool[0],"running");
                final CapabilityCall[] admitted={null};
                scenario.onActivity(activity->{
                    MainActivity.UiTask task=activity.tasksForUi().stream().filter(item->ID.equals(item.pluginId)&&"running".equals(item.status)).findFirst().orElseThrow(() -> new AssertionError("Expected plugin operation"));
                    second[0]=task.id;
                    String sessionId=(String)field(field(tool[0],"activeWebSession"),"sessionId");
                    assertTrue(activity.canStartUiOperation(ID,sessionId));
                    try {
                        admitted[0]=new CapabilityCall(ID,sessionId,"app.reportOperation",
                            new JSONObject().put("id","picker-race").put("label","选择文件").put("status","running"),
                            new JSONObject(),true,System.currentTimeMillis()+5000);
                    } catch(Exception error){throw new AssertionError(error);}
                });
                // A system picker pauses the host after RPC receipt but before a cached worker runs.
                // Delay handling deliberately to reproduce that ordering with no files or grants.
                scenario.moveToState(Lifecycle.State.STARTED);
                AtomicReference<CompletableFuture<JSONObject>> reportResult=new AtomicReference<>();
                scenario.onActivity(activity->{
                    assertFalse(activity.canStartUiOperation(ID,admitted[0].sessionId));
                    reportResult.set(activity.reportOperation(admitted[0]));
                });
                raced[0]=reportResult.get().get(5,TimeUnit.SECONDS).getString("taskId");
                scenario.onActivity(activity->{
                    assertEquals("running",activity.tasksForUi().stream().filter(item->raced[0].equals(item.id)).findFirst().orElseThrow(() -> new AssertionError("Expected admitted operation")).status);
                    try {
                        reportResult.set(activity.reportOperation(new CapabilityCall(ID,admitted[0].sessionId,"app.reportOperation",
                            new JSONObject().put("id","hidden-start").put("label","选择文件").put("status","running"),
                            new JSONObject(),false,System.currentTimeMillis()+5000)));
                    } catch(Exception error){throw new AssertionError(error);}
                });
                try { reportResult.get().get(5,TimeUnit.SECONDS); fail("A start without visible dispatch admission must fail"); }
                catch(ExecutionException error){assertEquals("CONSENT_REQUIRED",((CapabilityFailure)error.getCause()).code);}
                scenario.moveToState(Lifecycle.State.RESUMED);
                scenario.onActivity(activity->{
                    activity.navigateForUi(0);tool[0].onDestroy();
                    assertEquals("cancelled",activity.tasksForUi().stream().filter(item->second[0].equals(item.id)).findFirst().orElseThrow(() -> new AssertionError("Expected plugin operation")).status);
                    assertEquals("cancelled",activity.tasksForUi().stream().filter(item->raced[0].equals(item.id)).findFirst().orElseThrow(() -> new AssertionError("Expected admitted operation")).status);
                    assertEquals("success",activity.tasksForUi().stream().filter(item->first[0].equals(item.id)).findFirst().orElseThrow(() -> new AssertionError("Expected plugin operation")).status);
                });
            }finally{
                scenario.onActivity(activity->{activity.navigateForUi(0);if(tool[0]!=null){tools(activity).remove(tool[0]);tool[0].onDestroy();}PluginRuntime.get(activity).permissions().removePlugin(ID);});
                if(directory[0]!=null)removeFixture(directory[0]);
            }
        }
    }
    private static void tapStart(DeclarativeToolPlugin tool)throws Exception{
        awaitVisibleFrame(tool);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            WebView view=view(tool);float x=view.getWidth()/2f,y=40*view.getResources().getDisplayMetrics().density;
            long now=SystemClock.uptimeMillis();
            MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);
            MotionEvent up=MotionEvent.obtain(now,now+40,MotionEvent.ACTION_UP,x,y,0);
            try{view.dispatchTouchEvent(down);view.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}
        });
    }
    private static void awaitVisibleFrame(DeclarativeToolPlugin tool)throws Exception{
        for(int attempt=0;attempt<120;attempt++){
            CountDownLatch frame=new CountDownLatch(1);
            AtomicReference<Boolean> ready=new AtomicReference<>(false);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                Object session=field(tool,"activeWebSession");WebView view=view(tool);Rect bounds=new Rect();
                if(session!=null&&Boolean.TRUE.equals(field(session,"visible"))&&view!=null&&view.isShown()
                    &&view.getWindowVisibility()==View.VISIBLE&&view.hasWindowFocus()
                    &&view.getWidth()>0&&view.getHeight()>0&&view.getGlobalVisibleRect(bounds)&&!bounds.isEmpty()){
                    // The retention SideEffect runs before placement. Wait for the next frame's
                    // traversal before delivering a real touch to the newly displayed WebView.
                    ready.set(true);view.postOnAnimation(frame::countDown);
                }else frame.countDown();
            });
            assertTrue("Visible WebView frame timed out",frame.await(2,TimeUnit.SECONDS));
            if(ready.get()){InstrumentationRegistry.getInstrumentation().waitForIdleSync();return;}
            SystemClock.sleep(50);
        }
        fail("WebView did not become visible and laid out before touch");
    }
    private static void awaitState(DeclarativeToolPlugin tool,String expected)throws Exception{
        String actual=null;
        for(int index=0;index<120;index++){
            AtomicReference<String> value=new AtomicReference<>();CountDownLatch read=new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                WebView view=view(tool);if(view==null){read.countDown();return;}
                view.evaluateJavascript("document.getElementById('state')?.textContent",text->{value.set(text);read.countDown();});
            });
            assertTrue("WebView read timed out",read.await(2,TimeUnit.SECONDS));actual=value.get();
            if(("\""+expected+"\"").equals(actual))return;SystemClock.sleep(50);
        }
        fail("Expected "+expected+", got "+actual);
    }
    private static WebView view(DeclarativeToolPlugin tool){Object session=field(tool,"activeWebSession");return session==null?null:(WebView)field(session,"webView");}
    @SuppressWarnings("unchecked")private static List<HostTool> tools(MainActivity activity){return(List<HostTool>)field(activity,"plugins");}
    private static Object field(Object target,String name){
        try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static void removeFixture(File file)throws Exception{File[] children=file.listFiles();if(children!=null)for(File child:children)removeFixture(child);Files.deleteIfExists(file.toPath());}
}
