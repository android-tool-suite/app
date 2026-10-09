package com.androidtoolsuite.app.host;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.androidtoolsuite.app.plugin.runtime.HostTool;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;

/** Acceptance against installed packages and their real DOM. Synthetic fixtures are restored after use. */
@RunWith(AndroidJUnit4.class)
public final class UiDesignInstrumentedTest {
    private String originalTheme;

    @Before public void applyRequestedAppearance() {
        String requested=InstrumentationRegistry.getArguments().getString("uiTheme");
        if(requested==null)return;
        assertTrue("Unsupported acceptance theme",requested.equals("light")||requested.equals("dark"));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->{originalTheme=activity.themePreferenceForUi();activity.setThemePreferenceForUi(requested);});
        }
    }

    @After public void restoreAppearance() {
        if(originalTheme==null)return;
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.setThemePreferenceForUi(originalTheme));
        }
    }

    @Test public void repositoryIsADirectDestinationAndDataScopeDoesNotDependOnEntry() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                activity.showPluginRepositoryForUi();
                assertEquals(6, activity.currentSectionForUi());
                activity.showTaskListForUi();
                activity.handleBackForUi();
                assertEquals(6, activity.currentSectionForUi());
                activity.handleBackForUi();
                assertEquals(0, activity.currentSectionForUi());
                activity.setMainSectionFromPagerForUi(6);
                assertEquals(6, activity.currentSectionForUi());
                activity.showDataManagementForUi("gacha_analysis");
                assertEquals(5, activity.currentSectionForUi());
                assertNull(activity.dataOwnerForUi());
                activity.navigateForUi(0);
            });
        }
    }

    @Test public void hostBrowseActionsAndWidgetManagementUseTheSharedPageHierarchy() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            final String[] installedTitle={null};
            scenario.onActivity(activity->{installedTitle[0]="已安装 "+activity.importedDescriptorsForUi().size()+" 个插件";activity.navigateForUi(1);});
            awaitHostText(installedTitle[0]);assertNull(findVisible("搜索插件或文件夹"));
            captureHost("ui-feedback-tools.png");
            clickHost("搜索");awaitHostText("搜索插件或文件夹");
            clickHost("关闭搜索");awaitHostText(installedTitle[0]);
            scenario.onActivity(activity->activity.navigateForUi(2));awaitHostText(installedTitle[0]);
            captureHost("ui-feedback-manager.png");
            scenario.onActivity(activity->activity.showPluginManagementForUi("gacha_analysis","versions"));awaitHostText("版本与来源");
            assertNull(findVisible("安装与发布信息"));captureHost("ui-feedback-versions.png");
            scenario.onActivity(activity->activity.showPluginManagementForUi("gacha_analysis","info"));awaitHostText("安装与发布信息");
            captureHost("ui-feedback-plugin-info.png");
            scenario.onActivity(activity->activity.navigateForUi(6));awaitHostText("可安装插件");
            assertNull(findVisible("跃迁与祈愿分析"));captureHost("ui-feedback-warehouse.png");
            scenario.onActivity(activity->activity.showDataManagementForUi(null));awaitHostText("导出");
            scenario.onActivity(activity->activity.setDataQueryForUi("Shizuku"));awaitHostText("搜索应用或插件");
            scenario.onActivity(activity->activity.navigateForUi(3));awaitHostText("设置");
            scenario.onActivity(activity->assertEquals(3,activity.currentSectionForUi()));
            scenario.onActivity(activity->activity.showDataManagementForUi(null));awaitHostText("搜索应用或插件");
            clickHost("关闭搜索");awaitHostText("本地内容");
            scenario.onActivity(activity->assertEquals("",activity.dataQueryForUi()));assertNull(findVisible("搜索应用或插件"));
            android.graphics.Rect first=new android.graphics.Rect(),second=new android.graphics.Rect(),third=new android.graphics.Rect();
            findVisible("导出").getBoundsInScreen(first);findVisible("导入").getBoundsInScreen(second);findVisible("删除").getBoundsInScreen(third);
            assertEquals(first.centerY(),second.centerY());assertEquals(first.centerY(),third.centerY());captureHost("ui-feedback-data.png");
            scenario.onActivity(activity->{activity.navigateForUi(0);activity.openWidgetSettingsForUi(null,null);});awaitHostText("已添加");
            captureHost("ui-feedback-widgets.png");clickHost("添加");awaitHostText("添加小部件");
            captureHost("ui-feedback-widget-catalog.png");scenario.onActivity(activity->activity.closeWidgetSettingsForUi());
            scenario.onActivity(activity->{var instance=activity.widgetInstancesForUi().all().stream().filter(item->item.pluginId.equals("phigros_advisor")).findFirst().orElseThrow();activity.openWidgetSettingsForUi(instance.pluginId,instance.id);});
            awaitHostText("固定档案");
            android.graphics.Rect cancel=new android.graphics.Rect(),save=new android.graphics.Rect();
            findVisible("取消").getBoundsInScreen(cancel);findVisible("保存").getBoundsInScreen(save);
            final float[] density={0};scenario.onActivity(activity->density[0]=activity.getResources().getDisplayMetrics().density);
            assertTrue("Editor actions must follow the 720 dp content width",save.right-cancel.left<=720*density[0]+2);
            captureHost("ui-feedback-widget-editor.png");
            scenario.onActivity(activity->activity.closeWidgetSettingsForUi());
        }
    }

    @Test public void phigrosFiltersAndAccountEditorKeepLocalBackAndReadableResults() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            HostTool tool=open(scenario,"phigros_advisor");
            await(tool,"!!document.querySelector('.tab-page.is-active .player-identity h2')");
            assertEquals("0",script(tool,"document.querySelectorAll('.tab-page.is-active .profile-intro,.tab-page.is-active .profile-details').length"));
            assertEquals("true",script(tool,"(()=>{const root=document.querySelector('.tab-page.is-active'),rks=root.querySelector('.rks-reading').getBoundingClientRect(),name=root.querySelector('.player-identity').getBoundingClientRect();return name.left>=rks.right && name.bottom<=rks.bottom+2 && root.querySelector('.data-balance strong').textContent.trim().length>0})()"));
            capture(tool,"ui-feedback-phi-overview.png");
            script(tool,"document.getElementById('accounts-open').click()");
            await(tool,"!!document.querySelector('#accounts-dialog[open] .account-row')");
            await(tool,"(()=>{const panel=document.getElementById('accounts-dialog').getBoundingClientRect(),row=document.querySelector('#accounts-dialog .account-row').getBoundingClientRect();return panel.height>120 && row.top>=panel.top && row.bottom<=panel.bottom})()");
            capture(tool,"ui-design-phi-accounts.png");
            script(tool,"document.querySelector('#accounts-dialog .manage-account').click()");
            await(tool,"!!document.querySelector('#accounts-dialog .account-editor')");
            capture(tool,"ui-design-phi-account-editor.png");
            scenario.onActivity(MainActivity::handleBackForUi);
            await(tool,"!!document.querySelector('#accounts-dialog[open] .account-directory')");
            scenario.onActivity(MainActivity::handleBackForUi);
            await(tool,"!document.getElementById('accounts-dialog').open");
            script(tool,"[...document.querySelectorAll('#tabs button')].find(b=>b.textContent==='成绩').click()");
            await(tool,"document.querySelector('.tab-pager')?.dataset.activePage==='成绩' && document.querySelector('.tab-pager').dataset.moving==='false'");
            assertEquals("true",script(tool,"document.querySelector('.tab-page.is-active .search-toolbar').getBoundingClientRect().height<=120"));
            assertEquals("4",script(tool,"document.querySelectorAll('.tab-page.is-active .quick-levels button').length"));
            assertEquals("0",script(tool,"document.querySelectorAll('.tab-page.is-active #constant-min').length"));
            capture(tool,"ui-design-phi-scores.png");
            String originalResults=script(tool,"document.querySelector('.tab-page.is-active #result-count').textContent");
            script(tool,"document.querySelector('.tab-page.is-active #open-filters').click()");
            await(tool,"document.getElementById('filter-dialog').open");
            await(tool,"(()=>{const panel=document.getElementById('filter-dialog').getBoundingClientRect(),input=document.querySelector('#filter-dialog #constant-min').getBoundingClientRect();return panel.height>200 && input.top>=panel.top && input.bottom<=panel.bottom})()");
            await(tool,"document.querySelector('#filter-dialog #constant-min').getBoundingClientRect().height>=44");
            script(tool,"(()=>{const input=document.getElementById('constant-min');input.value='16';input.dispatchEvent(new Event('input',{bubbles:true}));})()");
            assertEquals(originalResults,script(tool,"document.querySelector('.tab-page.is-active #result-count').textContent"));
            script(tool,"(()=>{const input=document.getElementById('constant-max');input.value='14';input.dispatchEvent(new Event('input',{bubbles:true}));})()");
            await(tool,"document.getElementById('filter-close').disabled && document.getElementById('filter-error').textContent.includes('最低定数不能大于')");
            capture(tool,"ui-design-phi-filters.png");
            scenario.onActivity(MainActivity::handleBackForUi);
            await(tool,"!document.getElementById('filter-dialog').open");
            assertEquals(originalResults,script(tool,"document.querySelector('.tab-page.is-active #result-count').textContent"));
            scenario.onActivity(activity -> assertEquals("phigros_advisor",activity.selectedPluginForUi().id()));
            script(tool,"[...document.querySelectorAll('#tabs button')].find(b=>b.textContent==='定数表').click()");
            await(tool,"document.querySelector('.tab-pager')?.dataset.activePage==='定数表' && document.querySelector('.tab-pager').dataset.moving==='false' && !!document.querySelector('.tab-page.is-active #catalog-version')");
            assertEquals("4",script(tool,"document.querySelectorAll('.tab-page.is-active .quick-levels button').length"));
            assertEquals("1",script(tool,"document.querySelectorAll('.tab-page.is-active #update-catalog').length"));
            assertEquals("0",script(tool,"document.querySelectorAll('.tab-page.is-active #catalog-more').length"));
            capture(tool,"ui-design-phi-catalog.png");
            script(tool,"document.querySelector('.tab-page.is-active .catalog-view[data-view=diff]').click()");
            await(tool,"document.querySelector('.tab-page.is-active .catalog-view[data-view=diff]').getAttribute('aria-pressed')==='true'");
            assertEquals("0",script(tool,"document.querySelectorAll('.tab-page.is-active #update-catalog,.tab-page.is-active #catalog-more').length"));
            capture(tool,"ui-design-phi-comparison.png");
        }
    }

    @Test public void gameSwitchAndWidgetEntryFollowTheirAccountWithoutReplayingOldNavigation() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            HostTool tool=open(scenario,"gacha_analysis");
            var datasets=com.androidtoolsuite.app.plugin.runtime.PluginRuntime.get(InstrumentationRegistry.getInstrumentation().getTargetContext()).datasets();
            java.io.ByteArrayOutputStream settingsBackup=new java.io.ByteArrayOutputStream();datasets.exportDataset("gacha_analysis","gacha-settings",settingsBackup);
            try {
                script(tool,"window.widgetEntryTrace=[];ats.onEvent('app.visibilityChanged',payload=>{window.widgetEntryTrace.push({visible:payload.visible,id:payload.widgetEntry?.id});})");
                assertEquals("0",script(tool,"document.querySelectorAll('#tabs button:not([hidden])').length && !document.getElementById('tabs').hidden ? document.querySelectorAll('#tabs button').length : 0"));
                await(tool,"[...document.querySelectorAll('[data-switch-game]')].every(button=>!button.disabled)");
                final com.androidtoolsuite.app.plugin.runtime.HostHomeWidget[] bound={null};
                final String[] key={null};
                scenario.onActivity(activity->{
                    var instance=activity.widgetInstancesForUi().all().stream().filter(item->item.pluginId.equals("gacha_analysis")&&item.configuration.optString("account").startsWith("hkrpg:")).findFirst().orElseThrow();
                    key[0]=instance.configuration.optString("account");bound[0]=activity.allWidgetsForUi().stream().filter(widget->widget.pluginId().equals(instance.pluginId)&&widget.id().equals(instance.id)).findFirst().orElseThrow();activity.navigateForUi(0);
                });
                awaitHostText("常用，一眼可见");scenario.onActivity(activity->activity.openWidgetForUi(bound[0]));
                await(tool,"window.widgetEntryTrace?.some(entry=>entry.visible && entry.id)===true");
                await(tool,"document.querySelector('[data-switch-game=hkrpg]').getAttribute('aria-pressed')==='true' && document.getElementById('account-picker').textContent.includes('"+key[0].substring(key[0].indexOf(':')+1)+"')");
                await(tool,"[...document.querySelectorAll('[data-switch-game]')].every(button=>!button.disabled)");
                if("true".equals(script(tool,"document.body.classList.contains('secondary-view')")))script(tool,"document.getElementById('view-back').click()");
                await(tool,"!document.body.classList.contains('secondary-view')");
                await(tool,"[...document.querySelectorAll('[data-switch-game]')].every(button=>!button.disabled)");
                script(tool,"document.querySelector('[data-switch-game=hk4e]').click()");
                await(tool,"document.querySelector('[data-switch-game=hk4e]').getAttribute('aria-pressed')==='true' && [...document.querySelectorAll('[data-switch-game]')].every(button=>!button.disabled)");
                scenario.onActivity(activity->activity.navigateForUi(0));awaitHostText("常用，一眼可见");scenario.onActivity(activity->activity.openPluginForUi("gacha_analysis"));
                await(tool,"document.querySelector('[data-switch-game=hk4e]').getAttribute('aria-pressed')==='true'");
                capture(tool,"ui-optimize-gacha-overview.png");
                script(tool,"document.querySelector('[data-view=months]').click()");
                await(tool,"document.querySelectorAll('#view-content .distribution-row').length>0");
                assertEquals("\"记录分布\"",script(tool,"document.getElementById('view-title').textContent"));
                capture(tool,"ui-feedback-gacha-months.png");
                script(tool,"document.querySelector('#view-content .distribution-row').click()");
                await(tool,"!!document.querySelector('#view-content #search') && document.querySelector('#view-content #search').value==='' && !!document.querySelector('#view-content .distribution-scope') && !document.getElementById('clear-filters').hidden");
                scenario.onActivity(MainActivity::handleBackForUi);await(tool,"document.querySelectorAll('#view-content .distribution-row').length>0");
                scenario.onActivity(MainActivity::handleBackForUi);await(tool,"!document.body.classList.contains('secondary-view')");
                script(tool,"document.querySelector('[data-open-pool]').click()");
                await(tool,"!!document.querySelector('#view-content .wish-pool-stats')");
                capture(tool,"ui-feedback-gacha-pool.png");
            } finally {
                scenario.close();
                long restoreDeadline=SystemClock.uptimeMillis()+10000;
                while(true){try{datasets.importDataset("gacha_analysis","gacha-settings","replace",new java.io.ByteArrayInputStream(settingsBackup.toByteArray()));break;}catch(java.io.IOException error){
                    if(!(error.getCause() instanceof com.androidtoolsuite.app.plugin.runtime.CapabilityFailure)||!((com.androidtoolsuite.app.plugin.runtime.CapabilityFailure)error.getCause()).code.equals("CONFLICT")||SystemClock.uptimeMillis()>=restoreDeadline)throw error;
                    SystemClock.sleep(80);
                }}
            }
        }
    }

    @Test public void gachaAccountAndAcquisitionControlsUseTheSameBoundsAndManagerIconsCannotSqueezeUIDs() throws Exception {
        try(var scenario=ActivityScenario.launch(MainActivity.class)) {
            HostTool tool=open(scenario,"gacha_analysis");
            assertEquals("true",script(tool,"(()=>{const a=document.getElementById('account-picker').getBoundingClientRect(),b=document.getElementById('open-acquire').getBoundingClientRect();return Math.abs(a.top-b.top)<1 && Math.abs(a.bottom-b.bottom)<1 && a.height>=44})()"));
            assertEquals("true",script(tool,"(()=>{const game=document.querySelector('.wish-game-switch').getBoundingClientRect(),row=document.querySelector('.wish-account-line').getBoundingClientRect(),hero=document.querySelector('.tab-page.is-active .wish-hero').getBoundingClientRect();return Math.abs((row.top-game.bottom)-(hero.top-row.bottom))<=2})()"));
            capture(tool,"gacha-row-layout.png");
            script(tool,"document.getElementById('account-picker').click();document.getElementById('open-data').click()");
            await(tool,"document.querySelectorAll('#view-content .account-manage').length>0");
            assertEquals("true",script(tool,"[...document.querySelectorAll('#view-content .account-manage')].every(row=>{const icon=row.querySelector('svg').getBoundingClientRect(),text=row.querySelector('.grow').getBoundingClientRect();return icon.width<=24 && icon.height<=24 && text.width>=150})"));
            capture(tool,"gacha-account-layout.png");
        }
    }

    @Test public void phigrosScoresAndGeneratedImagesUseRealArtworkAndSurfaceMissingPermission() throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        var runtime=com.androidtoolsuite.app.plugin.runtime.PluginRuntime.get(context);
        var datasets=runtime.datasets();var installed=runtime.packages().find("phigros_advisor");assertNotNull(installed);
        var policy=context.getSharedPreferences("runtime_v2_permissions",android.content.Context.MODE_PRIVATE);
        var policyBackup=new java.util.LinkedHashMap<String,String>();
        for(var entry:policy.getAll().entrySet()){assertTrue(entry.getValue() instanceof String);policyBackup.put(entry.getKey(),(String)entry.getValue());}
        var backup=new java.io.ByteArrayOutputStream();var profiles=new java.io.ByteArrayOutputStream();
        datasets.exportDataset("phigros_advisor","analysis-data",backup);datasets.exportDataset("phigros_advisor","profiles",profiles);
        try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"phigros-artwork-original-analysis.zip"))){backup.writeTo(out);}
        String profileId=new org.json.JSONObject(profiles.toString(java.nio.charset.StandardCharsets.UTF_8)).getString("selectedProfile");
        String scope=profileId.replaceAll("[^A-Za-z0-9._-]","_");if(scope.length()>80)scope=scope.substring(0,80);
        String songId="Rrharil.TeamGrimoire";
        var records=new org.json.JSONArray();String[] levels={"AT","IN","EZ","HD"};int[] scores={1000000,985432,890000,620000};double[] accuracies={100,98,90,65};
        for(int index=0;index<levels.length;index++)records.put(new org.json.JSONObject().put("id",songId).put("title","Rrharil")
                .put("level",levels[index]).put("score",scores[index]).put("accuracy",accuracies[index]).put("fc",index==0));
        var save=new org.json.JSONObject().put("formatVersion",2).put("profile",new org.json.JSONObject().put("playerId","成绩与曲绘测试")
                .put("gameVersion",199).put("saveUpdatedAt","2026-10-09T00:00:00Z").put("money",new org.json.JSONArray().put(1024))).put("records",records);
        var song=new org.json.JSONObject().put("id",songId).put("title","Rrharil").put("constants",new org.json.JSONArray().put(5).put(10).put(16.5).put(16.9));
        var catalog=new org.json.JSONObject().put("code",199).put("label","Artwork UI fixture").put("revision","f".repeat(64)).put("sourceRef","e".repeat(40)).put("songs",new org.json.JSONArray().put(song));
        var timeline=new org.json.JSONArray();for(int index=0;index<24;index++){
            String stamp="2026-09-"+String.format(java.util.Locale.ROOT,"%02d",index+1)+"T08:00:00Z";
            var change=new org.json.JSONObject().put("id",songId).put("title","Rrharil").put("level","IN").put("tag","成绩提升")
                    .put("oldScore",940000+index*1000).put("newScore",941000+index*1000).put("oldAccuracy",94+index*.1).put("newAccuracy",94.1+index*.1);
            timeline.put(new org.json.JSONObject().put("timestamp",java.time.Instant.parse(stamp).toEpochMilli()).put("oldRks",15+index*.001)
                    .put("newRks",15.001+index*.001).put("changes",new org.json.JSONArray().put(change)));
        }
        var bytes=new java.io.ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(bytes)){
            zip.putNextEntry(new java.util.zip.ZipEntry("saves/save-"+scope+".json"));zip.write(save.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("catalog-snapshots.json"));zip.write(new org.json.JSONArray().put(catalog).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("timeline-"+scope+".json"));zip.write(timeline.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }
        ActivityScenario<MainActivity> scenario=null;Throwable primaryFailure=null;
        try{
            runtime.storage().closePluginSessions("phigros_advisor");datasets.closePluginSessions("phigros_advisor");
            System.out.println("Phigros artwork acceptance: importing isolated fixture");
            restorePluginDataset(datasets,"phigros_advisor","analysis-data",bytes.toByteArray());
            runtime.permissions().setGranted(installed.manifest,"network.request",true);
            scenario=ActivityScenario.launch(MainActivity.class);HostTool tool=open(scenario,"phigros_advisor");
            System.out.println("Phigros artwork acceptance: score layout");
            script(tool,"[...document.querySelectorAll('#tabs button')].find(button=>button.textContent==='成绩').click()");
            await(tool,"document.querySelectorAll('.tab-page.is-active .score-row').length===2");
            assertEquals("0",script(tool,"[...document.querySelectorAll('.scores-tab')].filter(button=>button.textContent==='RKS 列表').length"));
            assertEquals("true",script(tool,"document.querySelector('.scores-tab[aria-pressed=true]').textContent==='全部成绩'"));
            assertEquals("true",script(tool,"[...document.querySelectorAll('.tab-page.is-active .score-row>.score-open')].every(row=>row.parentElement.dataset.scoreLayout==='all'&&!!row.querySelector('.score-points-line .score-points')&&!!row.querySelector('.score-points-line .grade')&&row.querySelectorAll('.score-current-accuracy,.score-rks,.score-effective-accuracy,.score-display-accuracy').length===4&&row.querySelector('.score-push-heading').textContent==='推分 ACC'&&row.querySelectorAll('.score-push-values>span').length===2&&!row.querySelector('.score-heading .score-rks'))"));
            assertEquals("true",script(tool,"[...document.querySelectorAll('.tab-page.is-active .score-row>.score-open strong,.tab-page.is-active .score-row>.score-open small,.tab-page.is-active .score-chart')].every(item=>{const range=document.createRange();range.selectNodeContents(item);const text=range.getBoundingClientRect(),parent=item.parentElement.getBoundingClientRect();return text.right<=parent.right+1&&text.left>=parent.left-1})"));
            assertEquals("true",script(tool,"[...document.querySelectorAll('.tab-page.is-active .score-row>.score-open')].every(row=>row.querySelector('.score-effective-accuracy').textContent===row.querySelector('.score-current-accuracy').textContent&&[...row.querySelectorAll('.score-push-values small')].map(item=>item.textContent).join('|')==='实际增长|显示增长')"));
            assertEquals("true",script(tool,"[...document.querySelectorAll('.tab-page.is-active .score-row>.score-open')].every(row=>{const result=row.querySelector('.score-result').getBoundingClientRect(),push=row.querySelector('.score-push-targets').getBoundingClientRect(),accuracy=row.querySelector('.score-current-accuracy').getBoundingClientRect(),rks=row.querySelector('.score-rks').getBoundingClientRect();return result.bottom<=push.top&&Math.abs(accuracy.bottom-rks.bottom)<=1})"));
            assertEquals("0",script(tool,"document.querySelectorAll('.tab-page.is-active .score-row details,.tab-page.is-active .score-row summary,.tab-page.is-active .score-detail').length"));
            assertEquals("true",script(tool,"[...document.querySelectorAll('.tab-page.is-active .score-open')].every(row=>Math.abs(row.querySelector('.score-current-accuracy').getBoundingClientRect().left-row.querySelector('.score-effective-accuracy').getBoundingClientRect().left)<=1&&Math.abs(row.querySelector('.score-rks').getBoundingClientRect().left-row.querySelector('.score-display-accuracy').getBoundingClientRect().left)<=1&&[...row.querySelectorAll('.score-target')].every(target=>{const label=target.querySelector('small').getBoundingClientRect(),value=target.querySelector('strong').getBoundingClientRect();return value.top>=label.bottom-1&&value.top-label.bottom<=4}))"));
            script(tool,"document.querySelector('.tab-page.is-active .level-filter[data-level=HD]').click()");
            await(tool,"document.querySelectorAll('.tab-page.is-active .score-row').length===3");
            assertEquals("true",script(tool,"[...document.querySelectorAll('.tab-page.is-active .score-row')].some(row=>row.querySelector('.score-points').textContent==='620,000'&&row.textContent.includes('0.0000'))"));
            capture(tool,"phigros-all-scores-layout.png");
            script(tool,"[...document.querySelectorAll('.tab-page.is-active .score-open')].find(row=>row.dataset.level==='IN').click()");
            await(tool,"document.getElementById('history-dialog').open&&document.querySelectorAll('#history-content .song-history-event').length===24");
            assertEquals("true",script(tool,"[...document.querySelectorAll('#history-content .timeline-item')].every(item=>getComputedStyle(item).borderBottomStyle==='none')"));
            assertDialogHeaderOutsideScroll(tool,"history-dialog",".section-header","history-content","phigros-song-history-scrolled.png");
            script(tool,"document.getElementById('history-close').click()");
            System.out.println("Phigros artwork acceptance: B30 layout");
            script(tool,"document.querySelector('.tab-page.is-active .scores-tab[data-page=B30]').click()");
            await(tool,"document.querySelectorAll('.tab-page.is-active .score-row').length>0");
            assertEquals("true",script(tool,"[...document.querySelectorAll('.tab-page.is-active .score-row>.score-open')].every(row=>row.parentElement.dataset.scoreLayout==='b30'&&!!row.querySelector('.score-heading .score-rks')&&!row.querySelector('.score-result .score-rks')&&row.querySelectorAll('.score-current-accuracy,.score-rks,.score-display-accuracy').length===3&&!row.querySelector('.score-effective-accuracy')&&row.querySelector('.score-push-heading').textContent==='推分 ACC'&&row.querySelectorAll('.score-push-values>span').length===1&&row.querySelector('.score-push-values small').textContent==='显示增长')"));
            assertEquals("true",script(tool,"[...document.querySelectorAll('.tab-page.is-active .score-row>.score-open strong,.tab-page.is-active .score-row>.score-open small,.tab-page.is-active .score-chart')].every(item=>{const range=document.createRange();range.selectNodeContents(item);const text=range.getBoundingClientRect(),parent=item.parentElement.getBoundingClientRect();return text.right<=parent.right+1&&text.left>=parent.left-1})"));
            assertEquals("true",script(tool,"[...document.querySelectorAll('.tab-page.is-active .score-row>.score-open')].every(row=>parseFloat(getComputedStyle(row.querySelector('.score-rks')).fontSize)>parseFloat(getComputedStyle(row.querySelector('.score-current-accuracy')).fontSize)&&row.querySelector('.score-result').getBoundingClientRect().bottom<=row.querySelector('.score-push-targets').getBoundingClientRect().top)"));
            assertEquals("true",script(tool,"[...document.querySelectorAll('.tab-page.is-active .score-board-group')].every(group=>{const heading=group.querySelector('.score-heading'),header=group.querySelector('header');if(!heading)return true;const gap=heading.getBoundingClientRect().top-header.getBoundingClientRect().bottom;return gap>=0&&gap<=12})"));
            capture(tool,"phigros-b30-three-metrics.png");
            System.out.println("Phigros artwork acceptance: B30 real image");
            script(tool,"document.querySelector('.tab-page.is-active .scores-tab[data-page=B30]').click();document.querySelector('.tab-page.is-active #b30-image').click()");
            awaitNetworkImage(tool,"document.getElementById('image-dialog').open && document.getElementById('image-preview').naturalWidth===1080");
            assertEquals("true",script(tool,"document.getElementById('image-status').textContent.includes('4/4 张已加载')"));
            System.out.println("Phigros artwork acceptance: "+script(tool,"document.getElementById('image-status').textContent"));
            assertEquals("true",script(tool,"(()=>{const image=document.getElementById('image-preview'),canvas=document.createElement('canvas');canvas.width=image.naturalWidth;canvas.height=image.naturalHeight;const ctx=canvas.getContext('2d');ctx.drawImage(image,0,0);const pixels=ctx.getImageData(60,242,76,76).data,colors=new Set();for(let i=0;i<pixels.length;i+=4)colors.add([pixels[i],pixels[i+1],pixels[i+2]].join(','));return colors.size>100})()"));
            saveGeneratedPreview(tool,"phigros-b30-generated.png");capture(tool,"phigros-b30-artwork-preview.png");
            script(tool,"document.getElementById('image-dialog').style.maxHeight='360px'");
            assertDialogHeaderOutsideScroll(tool,"image-dialog",".preview-actions","image-content","phigros-b30-preview-scrolled.png");
            script(tool,"document.getElementById('image-dialog').style.maxHeight=''");
            script(tool,"document.getElementById('close-image').click();[...document.querySelectorAll('#tabs button')].find(button=>button.textContent==='总览').click()");
            await(tool,"!!document.querySelector('.tab-page.is-active #profile-image')");
            script(tool,"document.querySelector('.tab-page.is-active #profile-image').click()");
            System.out.println("Phigros artwork acceptance: profile real image");
            awaitNetworkImage(tool,"document.getElementById('image-dialog').open && document.getElementById('image-preview').naturalWidth===1080");
            assertEquals("true",script(tool,"document.getElementById('image-status').textContent.includes('3/3 张已加载')"));
            saveGeneratedPreview(tool,"phigros-profile-generated.png");capture(tool,"phigros-profile-artwork-preview.png");
            scenario.close();scenario=null;
            runtime.permissions().setGranted(installed.manifest,"network.request",false);
            scenario=ActivityScenario.launch(MainActivity.class);tool=open(scenario,"phigros_advisor");
            script(tool,"document.querySelector('.tab-page.is-active #profile-image').click()");
            awaitNetworkImage(tool,"document.getElementById('message').textContent.includes('网络请求') && document.getElementById('message').textContent.includes('允许后重试')");
            assertEquals("false",script(tool,"document.getElementById('image-dialog').open"));
            capture(tool,"phigros-artwork-permission-required.png");
        }catch(Exception|AssertionError failure){primaryFailure=failure;throw failure;
        }finally{
            if(scenario!=null)scenario.close();
            try{
                runtime.storage().closePluginSessions("phigros_advisor");datasets.closePluginSessions("phigros_advisor");
                restorePluginDataset(datasets,"phigros_advisor","analysis-data",backup.toByteArray());
            }catch(Exception cleanupFailure){if(primaryFailure!=null)primaryFailure.addSuppressed(cleanupFailure);else throw cleanupFailure;
            }finally{var edit=policy.edit().clear();for(var entry:policyBackup.entrySet())edit.putString(entry.getKey(),entry.getValue());assertTrue(edit.commit());}
        }
    }
    private static void assertDialogHeaderOutsideScroll(HostTool tool,String dialogId,String headerSelector,String scrollId,String captureName) throws Exception {
        String nodes="const dialog=document.getElementById('"+dialogId+"'),header=dialog.querySelector('"+headerSelector+"'),body=document.getElementById('"+scrollId+"');";
        assertEquals("true",script(tool,"(()=>{"+nodes+"const rect=header.getBoundingClientRect();window.uiAcceptanceDialogHeader={top:rect.top,bottom:rect.bottom};return body.scrollHeight>body.clientHeight&&body.clientHeight>0})()"));
        script(tool,"(()=>{"+nodes+"body.scrollTop=Math.min(300,body.scrollHeight-body.clientHeight)})()");
        await(tool,"document.getElementById('"+scrollId+"').scrollTop>0");
        assertEquals("true",script(tool,"(()=>{"+nodes+"const rect=header.getBoundingClientRect(),content=body.getBoundingClientRect(),hit=document.elementFromPoint(rect.left+rect.width/2,rect.top+rect.height/2),before=window.uiAcceptanceDialogHeader;return dialog.scrollTop===0&&Math.abs(rect.top-before.top)<=1&&Math.abs(rect.bottom-before.bottom)<=1&&content.top>=rect.bottom-1&&header.contains(hit)})()"));
        capture(tool,captureName);
    }
    private static void awaitNetworkImage(HostTool tool,String code) throws Exception {
        long end=SystemClock.uptimeMillis()+45000;
        do{if("true".equals(script(tool,code)))return;SystemClock.sleep(100);}while(SystemClock.uptimeMillis()<end);
        fail("Image acquisition/rendering did not complete: "+script(tool,"document.getElementById('image-status').textContent+' / '+document.getElementById('message').textContent"));
    }
    private static void saveGeneratedPreview(HostTool tool,String name) throws Exception {
        String raw=script(tool,"(()=>{const image=document.getElementById('image-preview'),canvas=document.createElement('canvas');canvas.width=image.naturalWidth;canvas.height=image.naturalHeight;canvas.getContext('2d').drawImage(image,0,0);return canvas.toDataURL('image/png').split(',')[1]})()");
        String base64=(String)new org.json.JSONTokener(raw).nextValue();
        byte[] bytes=android.util.Base64.decode(base64,android.util.Base64.DEFAULT);
        File file=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),name);
        try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}
    }

    @Test public void phigrosRksAndB30ShareTheirRowDensity() throws Exception {
        var datasets=com.androidtoolsuite.app.plugin.runtime.PluginRuntime.get(InstrumentationRegistry.getInstrumentation().getTargetContext()).datasets();
        var backup=new java.io.ByteArrayOutputStream();var profiles=new java.io.ByteArrayOutputStream();
        datasets.exportDataset("phigros_advisor","analysis-data",backup);datasets.exportDataset("phigros_advisor","profiles",profiles);
        String profileId=new org.json.JSONObject(profiles.toString(java.nio.charset.StandardCharsets.UTF_8)).getString("selectedProfile");
        String scope=profileId.replaceAll("[^A-Za-z0-9._-]","_");if(scope.length()>80)scope=scope.substring(0,80);
        byte[] source;try(var input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("widget-phi-save.json")){source=input.readAllBytes();}
        var save=new org.json.JSONObject(new String(source,java.nio.charset.StandardCharsets.UTF_8));
        save.getJSONObject("profile").put("gameVersion",199).remove("officialRks");
        var songs=new org.json.JSONArray();for(int i=0;i<save.getJSONArray("records").length();i++) {
            var record=save.getJSONArray("records").getJSONObject(i);songs.put(new org.json.JSONObject().put("id",record.getString("id")).put("title",record.getString("title"))
                    .put("constants",new org.json.JSONArray().put(5).put(10).put(14).put(16)));
        }
        var catalog=new org.json.JSONObject().put("code",199).put("label","UI density fixture").put("revision","f".repeat(64)).put("sourceRef","e".repeat(40)).put("songs",songs);
        var bytes=new java.io.ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("saves/save-"+scope+".json"));zip.write(save.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("catalog-snapshots.json"));zip.write(new org.json.JSONArray().put(catalog).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }
        ActivityScenario<MainActivity> scenario=null;
        try {
            restorePluginDataset(datasets,"phigros_advisor","analysis-data",bytes.toByteArray());scenario=ActivityScenario.launch(MainActivity.class);
            HostTool tool=open(scenario,"phigros_advisor");script(tool,"[...document.querySelectorAll('#tabs button')].find(button=>button.textContent==='成绩').click()");
            await(tool,"document.querySelectorAll('.tab-page.is-active .score-row').length>0");
            String before=script(tool,"document.querySelector('.tab-page.is-active .score-row>.score-open').getBoundingClientRect().height");
            script(tool,"[...document.querySelectorAll('.tab-page.is-active button')].find(button=>button.textContent.trim()==='B30').click()");
            await(tool,"document.querySelectorAll('.tab-page.is-active .score-list .score-row').length>0");
            assertEquals("true",script(tool,"[...document.querySelectorAll('.tab-page.is-active .score-list')].every(list=>getComputedStyle(list).gap==='0px')"));
            String after=script(tool,"document.querySelector('.tab-page.is-active .score-row>.score-open').getBoundingClientRect().height");
            assertEquals(Double.parseDouble(before),Double.parseDouble(after),1.0);capture(tool,"phigros-b30-density.png");
        }finally{if(scenario!=null)scenario.close();restorePluginDataset(datasets,"phigros_advisor","analysis-data",backup.toByteArray());}
    }
    private static void restorePluginDataset(com.androidtoolsuite.app.plugin.runtime.DatasetService datasets,String plugin,String id,byte[] bytes) throws Exception {
        long deadline=SystemClock.uptimeMillis()+10000;
        while(true)try{datasets.importDataset(plugin,id,"replace",new java.io.ByteArrayInputStream(bytes));return;}
        catch(java.io.IOException error){if(!(error.getCause() instanceof com.androidtoolsuite.app.plugin.runtime.CapabilityFailure)||!((com.androidtoolsuite.app.plugin.runtime.CapabilityFailure)error.getCause()).code.equals("CONFLICT")||SystemClock.uptimeMillis()>=deadline)throw error;SystemClock.sleep(80);}
    }

    @Test public void gachaManagementHasIndependentScopeAndOneDeletionFlow() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            HostTool tool=open(scenario,"gacha_analysis");
            assertEquals("0",script(tool,"document.querySelectorAll('.main-header').length"));
            script(tool,"document.getElementById('account-picker').click()");
            await(tool,"document.getElementById('accounts-dialog').open && document.getElementById('accounts-dialog').getBoundingClientRect().height>120");
            capture(tool,"ui-design-gacha-picker.png");
            assertEquals("true",script(tool,"(()=>{const game=document.querySelector('[data-switch-game][aria-pressed=true]').dataset.switchGame,choices=[...document.querySelectorAll('#account-choices [data-choose-account]')];return choices.length>0 && choices.every(item=>item.dataset.chooseAccount.startsWith(game+':')) && choices.filter(item=>item.getAttribute('aria-pressed')==='true'&&item.disabled).length===1})()"));
            script(tool,"document.getElementById('open-data').click()");
            await(tool,"!!document.querySelector('#view-content .account-group')");
            assertEquals("\"\"",script(tool,"document.getElementById('view-subtitle').textContent"));
            assertEquals("0",script(tool,"document.querySelectorAll('#view-content [data-view=analysis],#home-widget-settings,.delete-game').length"));
            assertEquals("1",script(tool,"document.querySelectorAll('#view-content [data-view=delete]').length"));
            assertEquals("0",script(tool,"document.querySelectorAll('#view-content [data-select-account],#view-content .account-selected').length"));
            capture(tool,"ui-design-gacha-management.png");
            script(tool,"document.querySelector('#view-content [data-account-actions]').click()");
            await(tool,"!!document.querySelector('#view-content .account-note-form')");
            assertEquals("0",script(tool,"document.querySelectorAll('#view-content .account-identity .badge').length"));
            capture(tool,"ui-design-gacha-account.png");
            script(tool,"(()=>{const input=document.getElementById('account-note');input.value='未保存的验收备注';input.dispatchEvent(new Event('input',{bubbles:true}));})()");
            scenario.onActivity(MainActivity::handleBackForUi);
            await(tool,"document.getElementById('confirm-dialog').open");
            script(tool,"document.getElementById('confirm-action').click()");
            await(tool,"!!document.querySelector('#view-content .account-group')");
            script(tool,"document.querySelector('#view-content [data-view=delete]').click()");
            await(tool,"!!document.getElementById('delete-selected')");
            assertEquals("true",script(tool,"document.getElementById('delete-selected').disabled"));
            assertEquals("0",script(tool,"document.querySelectorAll('#view-content .delete-account-choice:checked').length"));
            scenario.onActivity(MainActivity::handleBackForUi);
            await(tool,"!!document.querySelector('#view-content .account-group')");
        }
    }

    @Test public void distributionSegmentsAndFiltersUseTheSameRecordsAsTheDrillDown() throws Exception {
        var datasets=com.androidtoolsuite.app.plugin.runtime.PluginRuntime.get(InstrumentationRegistry.getInstrumentation().getTargetContext()).datasets();
        var recordsBackup=new java.io.ByteArrayOutputStream();var railBackup=new java.io.ByteArrayOutputStream();var settingsBackup=new java.io.ByteArrayOutputStream();
        datasets.exportDataset("gacha_analysis","genshin-records",recordsBackup);datasets.exportDataset("gacha_analysis","starrail-records",railBackup);datasets.exportDataset("gacha_analysis","gacha-settings",settingsBackup);
        String uid="900000123";
        org.json.JSONArray records=new org.json.JSONArray();
        for(int index=0;index<12;index++) records.put(new org.json.JSONObject().put("uid",uid).put("id","ui-distribution-"+index)
                .put("gacha_type",new String[]{"301","302","200"}[index%3]).put("rank_type",index==0?"5":"3")
                .put("name",index==0?"测试 UP":"测试物品").put("is_up",index==0?"true":"false")
                .put("time",String.format(java.util.Locale.ROOT,"2026-%02d-05 12:00:%02d",index<6?1:2,index)));
        for(String pool:new String[]{"1000","2000"})records.put(new org.json.JSONObject().put("uid",uid).put("id","ui-beyond-"+pool).put("gacha_type",pool).put("rank_type",pool.equals("2000")?"5":"4").put("name","测试颂愿").put("time","2026-01-08 12:00:00"));
        var fixture=new org.json.JSONObject().put("formatVersion",1).put("game","hk4e")
                .put("accounts",new org.json.JSONArray().put(new org.json.JSONObject().put("uid",uid))).put("records",records);
        var settings=new org.json.JSONObject().put("formatVersion",1).put("preferences",new org.json.JSONObject()
                .put("selected-account",new org.json.JSONObject().put("type","string").put("value","hk4e:"+uid))
                .put("banner-history-sources:hk4e:"+uid,new org.json.JSONObject().put("type","string-set").put("value",new org.json.JSONArray().put("record-field"))));
        var railRows=new org.json.JSONArray();String railUid="900000124";
        for(int index=0;index<3;index++)railRows.put(new org.json.JSONObject().put("uid",railUid).put("id","ui-version-"+index).put("gacha_type",new String[]{"1","11","12"}[index]).put("rank_type","3").put("name","测试版本").put("time",new String[]{"2023-11-14 12:00:00","2023-11-15 12:00:00","2026-10-01 12:00:00"}[index]));
        var rail=new org.json.JSONObject().put("formatVersion",1).put("game","hkrpg").put("accounts",new org.json.JSONArray().put(new org.json.JSONObject().put("uid",railUid))).put("records",railRows);
        ActivityScenario<MainActivity> scenario=null;
        try {
            restoreDataset(datasets,"genshin-records",fixture.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            restoreDataset(datasets,"starrail-records",rail.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            restoreDataset(datasets,"gacha-settings",settings.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            scenario=ActivityScenario.launch(MainActivity.class);HostTool tool=open(scenario,"gacha_analysis");
            script(tool,"document.querySelector('[data-view=months]').click()");
            await(tool,"document.querySelectorAll('.distribution-row').length===2");
            assertEquals("3",script(tool,"document.querySelectorAll('.distribution-row:first-child .distribution-stack i').length"));
            assertEquals("4",script(tool,"document.querySelectorAll('.distribution-resource-legend').length"));
            assertEquals("true",script(tool,"[...document.querySelectorAll('.distribution-resource-heading strong')].map(item=>item.textContent).join('|')==='相遇之缘|纠缠之缘|常驻颂愿|活动颂愿'"));
            assertEquals("0",script(tool,"document.querySelectorAll('[data-distribution-mode=up]').length"));
            capture(tool,"ui-distribution-grouped-legends.png");
            script(tool,"document.querySelector('[data-distribution-index=\"1\"]').click()");
            await(tool,"!!document.querySelector('.distribution-summary-head') && document.querySelectorAll('.distribution-day').length===31");
            assertEquals("8",script(tool,"Number(document.querySelector('.distribution-summary-metrics strong').textContent)"));
            assertEquals("4",script(tool,"document.querySelectorAll('.distribution-pie-legend>div').length"));
            assertEquals("2",script(tool,"document.querySelectorAll('.distribution-summary-five .wish-record').length"));
            assertEquals("true",script(tool,"document.querySelector('.distribution-daily').getBoundingClientRect().width<=document.querySelector('.distribution-daily-scroll').getBoundingClientRect().width+1"));
            assertEquals("true",script(tool,"(()=>{const labels=[...document.querySelectorAll('.distribution-day small')].filter(item=>item.textContent);return labels.every((item,index)=>index===0||labels[index-1].getBoundingClientRect().right<=item.getBoundingClientRect().left)})()"));
            String axisGeometry=script(tool,"(()=>{const last=document.querySelector('.distribution-day:last-child small'),range=document.createRange();range.selectNodeContents(last);const style=getComputedStyle(last);return {text:last.textContent,box:last.getBoundingClientRect().toJSON(),glyph:range.getBoundingClientRect().toJSON(),viewport:document.querySelector('.distribution-daily-scroll').getBoundingClientRect().toJSON(),style:{width:style.width,maxWidth:style.maxWidth,overflow:style.overflow,justifySelf:style.justifySelf,padding:style.padding}}})()");
            capture(tool,"ui-month-axis-inspected.png");
            assertEquals(axisGeometry,"true",script(tool,"(()=>{const last=document.querySelector('.distribution-day:last-child small'),range=document.createRange();range.selectNodeContents(last);return last.getBoundingClientRect().width>=range.getBoundingClientRect().width && range.getBoundingClientRect().right<=document.querySelector('.distribution-daily-scroll').getBoundingClientRect().right+1})()"));
            capture(tool,"ui-month-analysis.png");
            script(tool,"document.querySelector('[data-summary-chart=pools]').click()");
            await(tool,"document.querySelectorAll('.distribution-pie-legend>div').length===5");
            script(tool,"document.querySelector('.distribution-detail-menu>summary').click()");
            capture(tool,"ui-month-more-menu.png");
            scenario.onActivity(MainActivity::handleBackForUi);
            await(tool,"!!document.querySelector('.distribution-summary-head') && !document.querySelector('.distribution-detail-menu').open");
            script(tool,"document.querySelector('.distribution-detail-menu>summary').click()");
            script(tool,"document.querySelector('[data-summary-records=\"0\"]').click()");
            await(tool,"document.getElementById('record-count')?.textContent.startsWith('8 条')===true");
            scenario.onActivity(MainActivity::handleBackForUi);await(tool,"!!document.querySelector('.distribution-summary-head')");
            scenario.onActivity(MainActivity::handleBackForUi);await(tool,"document.querySelectorAll('.distribution-row').length===2");
            script(tool,"document.getElementById('open-distribution-filters').click()");await(tool,"document.getElementById('distribution-filters').open");
            script(tool,"['[data-distribution-filter=resources][value=intertwined]','[data-distribution-filter=pools][value=\"301\"]'].forEach(selector=>{const input=document.querySelector(selector);input.checked=true;input.dispatchEvent(new Event('change',{bubbles:true}));});document.getElementById('distribution-filter-apply').click()");
            await(tool,"document.querySelectorAll('.distribution-row .distribution-stack i').length===2");
            assertEquals("4",script(tool,"[...document.querySelectorAll('.distribution-row strong')].reduce((sum,value)=>sum+Number(value.textContent),0)"));
            script(tool,"document.querySelector('.distribution-row').click()");
            await(tool,"document.querySelector('.distribution-summary-metrics strong')?.textContent==='2'");
            script(tool,"document.querySelector('.distribution-detail-menu>summary').click();document.querySelector('[data-summary-records=\"0\"]').click()");
            await(tool,"document.getElementById('record-count')?.textContent.startsWith('2 条')===true && !document.getElementById('clear-filters').hidden");
            assertEquals("true",script(tool,"document.querySelector('.distribution-scope').textContent.includes('2026-02')"));
            scenario.onActivity(MainActivity::handleBackForUi);await(tool,"!!document.querySelector('.distribution-summary-head')");
            scenario.onActivity(MainActivity::handleBackForUi);await(tool,"document.querySelectorAll('.distribution-row').length===2");
            script(tool,"document.querySelector('[data-distribution-mode=version]').click()");
            await(tool,"[...document.querySelectorAll('.distribution-label')].some(label=>label.textContent==='版本 6.3')");
            scenario.onActivity(MainActivity::handleBackForUi);
            script(tool,"document.querySelector('[data-switch-game=hkrpg]').click()");
            await(tool,"document.querySelector('[data-switch-game=hkrpg]').getAttribute('aria-pressed')==='true' && !!document.querySelector('[data-view=months]')");
            script(tool,"document.querySelector('[data-view=months]').click();document.querySelector('[data-distribution-mode=version]').click()");
            await(tool,"[...document.querySelectorAll('.distribution-label')].map(item=>item.textContent).join('|')==='版本 4.6|版本 1.5|版本 1.4'");
            capture(tool,"ui-starrail-versions.png");
        } finally {
            if(scenario!=null)scenario.close();
            restoreDataset(datasets,"genshin-records",recordsBackup.toByteArray());restoreDataset(datasets,"starrail-records",railBackup.toByteArray());restoreDataset(datasets,"gacha-settings",settingsBackup.toByteArray());
        }
    }

    private static void restoreDataset(com.androidtoolsuite.app.plugin.runtime.DatasetService datasets,String id,byte[] bytes) throws Exception {
        long deadline=SystemClock.uptimeMillis()+10000;
        while(true)try{datasets.importDataset("gacha_analysis",id,"replace",new java.io.ByteArrayInputStream(bytes));return;}
        catch(java.io.IOException error){
            if(!(error.getCause() instanceof com.androidtoolsuite.app.plugin.runtime.CapabilityFailure)
                    ||!((com.androidtoolsuite.app.plugin.runtime.CapabilityFailure)error.getCause()).code.equals("CONFLICT")||SystemClock.uptimeMillis()>=deadline)throw error;
            SystemClock.sleep(80);
        }
    }

    @Test public void shizukuShowsAllConnectionStepsAndDirectPluginSettings() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            HostTool tool=open(scenario,"shizuku_auth");
            await(tool,"document.querySelectorAll('.connection-step').length===3");
            assertEquals("0",script(tool,"document.querySelectorAll('details').length"));
            assertEquals("true",script(tool,"[...document.querySelectorAll('.connection-step')].every(step=>step.getBoundingClientRect().height>0)"));
            assertNotNull(findVisible("插件设置"));assertNull(findVisible("更多"));
            capture(tool,"ui-final-shizuku.png");
        }
    }

    @Test public void accessibilityGroupsAndOptionalSearchUseTheActualConnectedServiceList() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            HostTool tool=open(scenario,"accessibility_grant");
            await(tool,"document.getElementById('connection-card').hidden && document.querySelectorAll('.service-group').length>0");
            assertEquals("true",script(tool,"document.getElementById('query-box').hidden"));
            String count=script(tool,"document.querySelectorAll('.service-row').length");
            assertEquals("true",script(tool,"[...document.querySelectorAll('.service-row')].every(row=>{const star=row.querySelector('.favorite'),restore=row.querySelector('button.restore'),enabled=row.querySelector('[data-control^=\"enabled:\"]'),a=star.getBoundingClientRect(),b=restore.getBoundingClientRect(),c=enabled.getBoundingClientRect();return ['true','false'].includes(restore.getAttribute('aria-pressed')) && a.width>=44 && b.width>=44 && Math.abs(a.top+a.height/2-b.top-b.height/2)<2 && Math.abs(b.top+b.height/2-c.top-c.height/2)<2})"));
            capture(tool,"ui-feedback-access-services.png");
            script(tool,"document.getElementById('search-toggle').click()");
            await(tool,"!document.getElementById('query-box').hidden");
            script(tool,"(()=>{const input=document.getElementById('query');input.value='no-such-service-for-ui-test';input.dispatchEvent(new Event('input',{bubbles:true}));})()");
            assertEquals("0",script(tool,"document.querySelectorAll('.service-row').length"));
            script(tool,"document.getElementById('search-close').click()");
            assertEquals(count,script(tool,"document.querySelectorAll('.service-row').length"));
            script(tool,"document.querySelector('button.service-name').click()");
            await(tool,"document.getElementById('service-detail').open");
            capture(tool,"ui-feedback-access-detail.png");
            scenario.onActivity(MainActivity::handleBackForUi);
            await(tool,"!document.getElementById('service-detail').open");
            scenario.onActivity(activity->assertEquals("accessibility_grant",activity.selectedPluginForUi().id()));
        }
    }

    private static HostTool open(ActivityScenario<MainActivity> scenario,String id) throws Exception {
        AtomicReference<HostTool> result=new AtomicReference<>();
        scenario.onActivity(activity->{activity.openPluginForUi(id);result.set(activity.selectedPluginForUi());});
        assertNotNull("Required installed acceptance plugin: "+id,result.get());
        await(result.get(),id.equals("accessibility_grant")?"!!document.getElementById('services') && !document.getElementById('content').hidden":id.equals("shizuku_auth")?"!!document.getElementById('content') && !document.getElementById('content').hidden":"!!document.querySelector('#tabs button') && !document.body.classList.contains('initializing')");
        return result.get();
    }
    private static android.view.accessibility.AccessibilityNodeInfo findVisible(String text) {
        return findNode(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),text);
    }
    private static android.view.accessibility.AccessibilityNodeInfo findNode(android.view.accessibility.AccessibilityNodeInfo node,String text) {
        if(node==null)return null;node.refresh();
        if(node.isVisibleToUser()&&(text.contentEquals(node.getText()==null?"":node.getText())||text.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())))return node;
        for(int i=0;i<node.getChildCount();i++){var found=findNode(node.getChild(i),text);if(found!=null)return found;}return null;
    }
    private static void awaitHostText(String text) {
        long end=SystemClock.uptimeMillis()+15000;do{if(findVisible(text)!=null)return;SystemClock.sleep(80);}while(SystemClock.uptimeMillis()<end);fail("Expected host UI: "+text);
    }
    private static void clickHost(String text) {
        awaitHostText(text);var node=findVisible(text);while(node!=null&&!node.isClickable())node=node.getParent();assertNotNull(node);assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
    }
    private static void captureHost(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        File file=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),name);
        try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
    private static WebView view(HostTool tool) {
        try {
            var field=tool.getClass().getDeclaredField("activeWebSession");field.setAccessible(true);
            Object session=field.get(tool);if(session==null)return null;
            field=session.getClass().getDeclaredField("webView");field.setAccessible(true);return (WebView)field.get(session);
        } catch(Exception error) { throw new AssertionError(error); }
    }
    private static String script(HostTool tool,String code) throws Exception {
        AtomicReference<String> result=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{WebView view=view(tool);if(view==null){done.countDown();return;}view.evaluateJavascript(code,value->{result.set(value);done.countDown();});});
        assertTrue(done.await(3,TimeUnit.SECONDS));return result.get();
    }
    private static void await(HostTool tool,String code) throws Exception {
        long end=SystemClock.uptimeMillis()+15000;
        do {if("true".equals(script(tool,code)))return;SystemClock.sleep(80);}while(SystemClock.uptimeMillis()<end);
        String diagnostics=(code.contains("data-switch-game")||code.contains("secondary-view")) ? script(tool,"JSON.stringify({buttons:[...document.querySelectorAll('[data-switch-game]')].map(button=>({game:button.dataset.switchGame,active:button.getAttribute('aria-pressed'),disabled:button.disabled})),notice:document.getElementById('notice')?.textContent,view:document.getElementById('view-title')?.textContent,backDisabled:document.getElementById('view-back')?.disabled,confirmOpen:document.getElementById('confirm-dialog')?.open,entries:window.widgetEntryTrace})") : "";
        fail("Expected installed plugin UI: "+code+"; "+diagnostics);
    }
    private static void capture(HostTool tool,String name) throws Exception {
        await(tool,"document.readyState==='complete'");
        script(tool,"window.uiAcceptancePainted=false;requestAnimationFrame(()=>requestAnimationFrame(()=>requestAnimationFrame(()=>window.uiAcceptancePainted=true)))");
        await(tool,"window.uiAcceptancePainted===true");
        CountDownLatch visualReady=new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->view(tool).postVisualStateCallback(SystemClock.uptimeMillis(),new WebView.VisualStateCallback(){
            @Override public void onComplete(long requestId){view(tool).postOnAnimation(visualReady::countDown);}
        }));
        assertTrue("WebView pixels must be ready before capture",visualReady.await(5,TimeUnit.SECONDS));
        // Allow the host entry transition to finish after the WebView has painted.
        SystemClock.sleep(250);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        File file=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),name);
        try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
}
