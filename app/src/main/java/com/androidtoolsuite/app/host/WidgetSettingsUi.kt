@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.androidtoolsuite.app.host

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import kotlinx.coroutines.launch
import com.androidtoolsuite.app.ui.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidtoolsuite.app.plugin.runtime.*
import com.androidtoolsuite.app.ui.SuiteSpacing
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Restricted native configuration fields, never arbitrary plugin HTML or executable callbacks. */
object WidgetConfiguration {
    fun summary(schema:JSONObject,configuration:JSONObject):String {
        val fields=schema.getJSONArray("fields")
        return (0 until fields.length()).mapNotNull { index->
            val field=fields.getJSONObject(index);val key=field.getString("key")
            val options=field.optJSONArray("options") ?: JSONArray()
            val selected=configuration.optJSONArray(key)?.let { values->(0 until values.length()).map { values.getString(it) } } ?: listOf(configuration.optString(key))
            val labels=(0 until options.length()).map { options.getJSONObject(it) }.filter { it.getString("value") in selected }.map { it.getString("label") }
            val value=if(field.getString("type")=="toggle") if(configuration.optBoolean(key)) "开启" else "关闭" else labels.joinToString(" / ")
            if(value.isBlank())null else "${field.getString("label")}：$value"
        }.joinToString(" · ").take(240)
    }
    suspend fun read(activity: MainActivity, installed: PluginPackageStore.InstalledPlugin,
                     preset: RuntimePluginManifest.HomeWidgetContribution, instanceId: String?,
                     configuration: JSONObject, migrating: Boolean = false): JSONObject {
        if (preset.configurationSource.isEmpty()) return JSONObject().put("configuration", JSONObject()).put("fields", JSONArray())
        check(installed.enabled) { "请先启用插件" }
        val runtime = PluginRuntime.get(activity)
        val session = "widget-config-${UUID.randomUUID()}"
        try {
            val payload = JSONObject().put("widgetId", preset.id).put("instanceId", instanceId ?: "")
                .put("configuration", configuration).put("migrating", migrating)
            val result = suspendCancellableCoroutine<JSONObject> { continuation ->
                val future = runtime.capabilities().invoke(installed.manifest, installed.manifest.plugin.id, session, preset.configurationSource, payload, false, 10_000)
                continuation.invokeOnCancellation { future.cancel(true) }
                future.whenComplete { value, error -> if (continuation.isActive) {
                    if (error == null) continuation.resume(value) else continuation.resumeWithException(error.cause ?: error)
                } }
            }
            validateSchema(result)
            return result
        } finally { runtime.storage().closeSession(session); runtime.datasets().closeSession(session) }
    }
    fun validateSchema(value: JSONObject) {
        require(value.toString().length <= 65536) { "小部件设置过大" }
        val fields = value.getJSONArray("fields"); require(fields.length() <= 16)
        val keys = mutableSetOf<String>()
        for (i in 0 until fields.length()) {
            val field = fields.getJSONObject(i)
            require(field.getString("key").matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")) && keys.add(field.getString("key")))
            require(field.getString("label").length in 1..160)
            require(field.getString("type") in listOf("select","multi","toggle"))
            if (field.getString("type") != "toggle") {
                val options = field.getJSONArray("options"); require(options.length() <= 128)
                val ids = mutableSetOf<String>()
                for (j in 0 until options.length()) { val option = options.getJSONObject(j)
                    require(option.getString("value").length <= 256 && ids.add(option.getString("value")))
                    require(option.getString("label").length in 1..160)
                }
            }
        }
        require(value.getJSONObject("configuration").toString().length <= 8192)
    }
    fun validated(schema: JSONObject, configuration: JSONObject): JSONObject {
        validateSchema(schema)
        val clean = JSONObject(); val fields = schema.getJSONArray("fields")
        for (i in 0 until fields.length()) {
            val field = fields.getJSONObject(i); val key=field.getString("key"); val label=field.getString("label")
            if (field.getString("type") == "toggle") { require(configuration.opt(key) is Boolean) { "请设置$label" }; clean.put(key,configuration.getBoolean(key)); continue }
            val options=field.getJSONArray("options"); val allowed=(0 until options.length()).map { options.getJSONObject(it).getString("value") }.toSet()
            if (field.getString("type") == "select") {
                val selected=configuration.optString(key,""); require(selected in allowed && (selected.isNotEmpty() || !field.optBoolean("required",true))) { "请选择有效的$label" }; clean.put(key,selected)
            } else {
                val values=configuration.optJSONArray(key) ?: JSONArray(); val items=(0 until values.length()).map { values.getString(it) }
                require(items.size in field.optInt("minItems",1)..field.optInt("maxItems",4) && items.distinct().size == items.size && items.all { it in allowed }) { "请核对$label" }; clean.put(key,values)
            }
        }
        return clean
    }
}

@Composable
internal fun HomeWidgetSettingsDialog(activity: MainActivity) {
    hostRevision(activity)
    val requestedPlugin = activity.widgetSettingsPluginForUi() ?: return
    val requestedInstance = activity.widgetSettingsInstanceForUi()
    val installed = activity.widgetPluginsForUi()
    val instances = activity.widgetInstancesForUi().all()
    val displayedWidgets = activity.allWidgetsForUi()
    var page by remember(requestedPlugin, requestedInstance) { mutableStateOf(if (requestedInstance == null) "manager" else "editor") }
    var pluginId by remember(requestedPlugin, requestedInstance) { mutableStateOf(requestedPlugin) }
    var instanceId by remember(requestedPlugin, requestedInstance) { mutableStateOf(requestedInstance) }
    var presetId by remember(requestedPlugin, requestedInstance) { mutableStateOf(requestedInstance?.let { activity.widgetInstancesForUi().find(requestedPlugin,it)?.presetId }) }
    var draft by remember(requestedPlugin, requestedInstance) { mutableStateOf(requestedInstance?.let { activity.widgetInstancesForUi().find(requestedPlugin,it)?.configuration } ?: JSONObject()) }
    var expectedRevision by remember(requestedPlugin, requestedInstance) { mutableLongStateOf(requestedInstance?.let { activity.widgetInstancesForUi().find(requestedPlugin,it)?.revision } ?: 0) }
    var schema by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf("2x2") }
    var removing by remember { mutableStateOf<HomeWidgetInstances.Instance?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val plugin = installed.find { it.manifest.plugin.id == pluginId }
    val preset = plugin?.manifest?.homeWidgetContributions?.find { it.id == presetId }
    fun navigateBack() {
        error=null
        if(page=="editor" && requestedInstance==null) { page=if(instanceId==null) "catalog" else "manager";presetId=null;schema=null }
        else if(page=="catalog") page="manager"
        else activity.closeWidgetSettingsForUi()
    }
    fun back() { if(dirty) discard=true else navigateBack() }
    fun editInstance(item:HomeWidgetInstances.Instance) {
        pluginId=item.pluginId;instanceId=item.id;presetId=item.presetId;draft=JSONObject(item.configuration.toString())
        expectedRevision=item.revision;schema=null;generation=0;dirty=false;error=null;page="editor"
    }
    fun addPreset(id:String,type:RuntimePluginManifest.HomeWidgetContribution) {
        pluginId=id;instanceId=null;presetId=type.id;draft=JSONObject();expectedRevision=0;schema=null
        generation=0;dirty=false;error=null;size=type.sizes.first();page="editor"
    }
    androidx.activity.compose.BackHandler { back() }
    LaunchedEffect(page,pluginId,presetId,generation) {
        if(page!="editor" || plugin==null || preset==null || !plugin.enabled)return@LaunchedEffect
        busy=true;error=null
        try {
            val result=WidgetConfiguration.read(activity,plugin,preset,instanceId,draft)
            ensureActive();schema=result;draft=result.getJSONObject("configuration")
            if(size !in preset.sizes)size=preset.sizes.first()
            if(generation==0 && instanceId!=null)activity.allWidgetsForUi().find { it.pluginId()==pluginId && it.id()==instanceId }?.let {
                val stored="${activity.widgetWidthUnitsForUi(it)}x${activity.widgetHeightUnitsForUi(it)}"
                if(stored in preset.sizes)size=stored
            }
        } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
        catch(failure:Exception){error=failure.message?:"无法读取小部件配置"}
        finally{busy=false}
    }
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar={SuiteTopBar(when(page){"catalog"->"添加小部件";"editor"->if(instanceId==null) "添加小部件" else "配置小部件";else->"首页小部件"},onBack=::back) {
                if(page=="manager") TextButton(onClick={page="catalog"}){Text("添加")}
            }},
            snackbarHost={SnackbarHost(snackbar)},
            bottomBar={if(page=="editor") Surface(color=MaterialTheme.colorScheme.surfaceContainer) {
                Box(Modifier.fillMaxWidth(),contentAlignment=androidx.compose.ui.Alignment.Center) {
                Row(Modifier.widthIn(max=720.dp).navigationBarsPadding().padding(SuiteSpacing.xl).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(SuiteSpacing.md)) {
                    OutlinedButton(onClick=::back,modifier=Modifier.weight(1f)){Text("取消")}
                    Button(enabled=!busy&&schema!=null&&plugin?.enabled==true&&preset!=null,modifier=Modifier.weight(1f),onClick={
                        try {
                            check(activity.widgetPluginsForUi().any { it.manifest.plugin.id==pluginId&&it.enabled&&it.generationDirectory==plugin!!.generationDirectory }){"插件状态已变化，请重新打开配置"}
                            val config=WidgetConfiguration.validated(schema!!,draft)
                            val saved=activity.widgetInstancesForUi().save(pluginId,instanceId,preset!!,config,expectedRevision,WidgetConfiguration.summary(schema!!,config))
                            activity.widgetSavedForUi(saved,size);dirty=false
                            if(requestedInstance!=null)activity.closeWidgetSettingsForUi() else {page="manager";presetId=null;schema=null;scope.launch {snackbar.showSnackbar("小部件已保存",withDismissAction=true)}}
                        }catch(failure:Exception){error=failure.message?:"小部件保存失败"}
                    }){Text("保存")}
                }
                }
            }},
        ){padding->
            Box(Modifier.fillMaxSize().padding(padding),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max=720.dp).fillMaxSize(),contentPadding=PaddingValues(vertical=SuiteSpacing.lg),verticalArrangement=Arrangement.spacedBy(SuiteSpacing.md)) {
                    if(page=="manager") {
                        item { Box(Modifier.padding(horizontal=SuiteSpacing.xl)){SectionHeader("已添加","${instances.size} 张独立卡片")} }
                        if(instances.isEmpty()) item { EmptyState("首页还没有小部件","从已启用插件选择需要的卡片，可为同一插件添加多张。",Modifier.padding(horizontal=SuiteSpacing.xl)) }
                        instances.groupBy { it.pluginId }.forEach { (id, cards) ->
                            val owner=installed.find { it.manifest.plugin.id==id }
                            item("owner-$id") {
                                SuiteSettingsGroup(owner?.manifest?.plugin?.title?:"插件未安装") {
                                    cards.forEach { card -> key(card.id) {
                                        val visible=activity.isWidgetInstanceVisibleForUi(id,card.id)
                                        val widget=displayedWidgets.find { it.pluginId()==id&&it.id()==card.id }
                                        val dimensions=widget?.let { "${activity.widgetWidthUnitsForUi(it)}×${activity.widgetHeightUnitsForUi(it)}" }.orEmpty()
                                        val preset=owner?.manifest?.homeWidgetContributions?.find { it.id==card.presetId }
                                        val snapshot=if(card.summary.isBlank()&&card.configuration.length()>0&&owner!=null&&preset!=null) PluginRuntime.get(activity).widgetSnapshots().entry(owner,preset,card.id,card.configuration,card.revision).value else null
                                        val binding=card.summary.ifBlank { listOf(snapshot?.optString("title"),snapshot?.optString("detail")).filterNot { it.isNullOrBlank() }.joinToString(" · ") }
                                        var menu by remember { mutableStateOf(false) }
                                        SuiteSettingsRow(card.title,supportingText=listOf(binding,dimensions,if(owner?.enabled!=true)"插件不可用，配置保留" else if(!visible)"已隐藏" else "").filter { it.isNotBlank() }.joinToString(" · "),onClick=if(owner?.enabled==true)({editInstance(card)})else null,trailing={
                                            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                                                Switch(checked=visible,onCheckedChange={activity.setWidgetInstanceVisibleForUi(id,card.id,it)},modifier=Modifier.semantics { contentDescription="在首页显示 ${card.title}" })
                                                Box {
                                                    IconButton(onClick={menu=true}){Icon(Icons.Rounded.MoreVert,"管理 ${card.title}")}
                                                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                                                        DropdownMenuItem(text={Text("配置")},enabled=owner?.enabled==true,onClick={menu=false;editInstance(card)})
                                                        DropdownMenuItem(text={Text("移除小部件",color=MaterialTheme.colorScheme.error)},onClick={menu=false;removing=card})
                                                    }
                                                }
                                            }
                                        })
                                    } }
                                }
                            }
                        }
                    } else if(page=="catalog") {
                        item { Text("每张卡片单独配置账号、内容与尺寸。",Modifier.padding(horizontal=SuiteSpacing.xl),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        val owners=installed.filter { it.manifest.homeWidgetContributions.isNotEmpty() && (requestedPlugin.isEmpty()||it.manifest.plugin.id==requestedPlugin) }
                        if(owners.isEmpty())item {EmptyState("没有可添加的小部件","从仓库安装提供首页卡片的插件。",Modifier.padding(horizontal=SuiteSpacing.xl))}
                        owners.forEach { owner ->
                            item(owner.manifest.plugin.id) { Box(Modifier.padding(horizontal=SuiteSpacing.xl)){SectionHeader(owner.manifest.plugin.title,if(owner.enabled)null else "请先在插件详情启用插件")} }
                            items(owner.manifest.homeWidgetContributions,key={owner.manifest.plugin.id+":"+it.id}) { type ->
                                SuiteSettingsRow(type.title,supportingText="支持尺寸 "+type.sizes.joinToString(" / "){it.replace('x','×')},leading={IconBox(pluginIconForId(owner.manifest.plugin.id),null,dense=true)},onClick=if(owner.enabled)({addPreset(owner.manifest.plugin.id,type)})else null,trailing={Icon(Icons.Rounded.Add,"添加 ${type.title}")})
                            }
                        }
                    } else {
                        item { Box(Modifier.padding(horizontal=SuiteSpacing.xl)){SectionHeader(preset?.title?:"小部件配置",plugin?.manifest?.plugin?.title)} }
                        if(plugin?.enabled!=true||preset==null)item {Notice("此插件或预设当前不可用，原配置保留。",warning=true,modifier=Modifier.padding(horizontal=SuiteSpacing.xl))}
                        if(error!=null)item {ErrorState("无法完成配置",error!!,{generation++},Modifier.padding(horizontal=SuiteSpacing.xl))}
                        if(plugin?.enabled==true&&preset!=null) {
                            val layouts=plugin.manifest.homeWidgetContributions.flatMap { type -> type.sizes.map { layout -> Triple(type,layout,"${type.id}:$layout") } }
                            item { WidgetChoice("布局","${preset.id}:$size",layouts.map { it.third to "${it.first.title} · ${it.second.replace('x','×')}" },busy){chosen->
                                val next=layouts.first { it.third==chosen };val changed=presetId!=next.first.id
                                presetId=next.first.id;size=next.second;dirty=true
                                if(changed){schema=null;busy=true;generation++}
                            } }
                            schema?.let { spec ->
                                val fields=spec.getJSONArray("fields")
                                for(index in 0 until fields.length()) {
                                    val field=fields.getJSONObject(index);val key=field.getString("key");val label=field.getString("label")
                                    fun update(value:Any){draft=JSONObject(draft.toString()).put(key,value);dirty=true;busy=true;generation++}
                                    item("field-$key") {
                                        if(field.getString("type")=="toggle") SuiteSettingsSwitchRow(label,supportingText=field.optString("description").takeIf{it.isNotBlank()},checked=draft.optBoolean(key),onCheckedChange={update(it)},enabled=!busy)
                                        else {
                                            val options=field.getJSONArray("options").let {values->(0 until values.length()).map {values.getJSONObject(it).let {option->option.getString("value") to option.getString("label")}}}
                                            if(field.getString("type")=="select")WidgetChoice(label,draft.optString(key),options,busy){update(it)}
                                            else SuiteSettingsGroup(label) {
                                                val values=draft.optJSONArray(key)?.let {array->(0 until array.length()).map {array.getString(it)}.toSet()}?:emptySet()
                                                options.forEach { (value,text)->Row(Modifier.fillMaxWidth().padding(horizontal=SuiteSpacing.xl),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                                                    Checkbox(checked=value in values,onCheckedChange={checked->val next=values.toMutableSet();if(checked)next.add(value) else next.remove(value);update(JSONArray(next.toList()))},enabled=!busy&&(value in values||values.size<field.optInt("maxItems",4)),modifier=Modifier.semantics{contentDescription=text})
                                                    Text(text,style=MaterialTheme.typography.bodyLarge)
                                                } }
                                            }
                                            if(field.has("description"))Text(field.getString("description"),Modifier.padding(horizontal=SuiteSpacing.xl),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    removing?.let { item->AlertDialog(onDismissRequest={removing=null},title={Text("移除“${item.title}”？")},text={Text("删除这张卡片的配置与布局。插件、账号和业务数据保留，之后可重新添加。")},confirmButton={TextButton(onClick={
        try{activity.removeWidgetInstanceForUi(item.pluginId,item.id,item.revision);removing=null;scope.launch{snackbar.showSnackbar("小部件已移除",withDismissAction=true)}}catch(failure:Exception){removing=null;scope.launch{snackbar.showSnackbar(failure.message?:"移除失败",withDismissAction=true)}}
    }){Text("移除",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={removing=null}){Text("取消")}}) }
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("放弃未保存的配置？")},confirmButton={TextButton(onClick={dirty=false;discard=false;navigateBack()}){Text("放弃")}},dismissButton={TextButton(onClick={discard=false}){Text("继续编辑")}})
}

@Composable private fun WidgetChoice(title:String,value:String,options:List<Pair<String,String>>,disabled:Boolean,onChange:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SuiteSettingsRow(title,trailingText=options.find{it.first==value}?.second?:"请选择",onClick=if(disabled||options.size<=1)null else ({expanded=true}),trailing={if(options.size>1)Icon(Icons.Rounded.ExpandMore,null)})
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=360.dp)) {
            options.forEach{(key,label)->DropdownMenuItem(text={Text(label)},onClick={expanded=false;onChange(key)})}
        }
    }
}
