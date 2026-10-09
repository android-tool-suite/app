package com.androidtoolsuite.app.host;

import android.content.SharedPreferences;
import com.androidtoolsuite.app.plugin.runtime.PluginPackageStore;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.*;

/** Host-owned configurations. Plugin business datasets never store another instance's selection. */
public final class HomeWidgetInstances {
    public static final String KEY = "widget_instances_v1";
    private final SharedPreferences preferences;
    public HomeWidgetInstances(SharedPreferences preferences) { this.preferences = preferences; }
    public static final class Instance {
        public final String pluginId, id, presetId, title, summary;
        public final JSONObject configuration;
        public final long revision;
        public final boolean migrating;
        public Instance(JSONObject json) throws JSONException {
            pluginId = json.getString("pluginId"); id = json.getString("id");
            presetId = json.getString("presetId"); title = json.optString("title", presetId); summary = json.optString("summary", "");
            configuration = new JSONObject(json.optJSONObject("configuration") == null ? "{}" : json.getJSONObject("configuration").toString());
            revision = json.optLong("revision", 0); migrating = json.optBoolean("migrating", false);
            if (!pluginId.matches("[A-Za-z0-9._-]{1,128}") || !id.matches("[A-Za-z0-9._-]{1,128}") || !presetId.matches("[A-Za-z0-9._-]{1,64}") || title.length()>160 || summary.length()>240 || revision<0 || configuration.toString().length() > 8192) throw new JSONException("小部件配置无效");
        }
        public JSONObject json() {
            try { return new JSONObject().put("pluginId",pluginId).put("id",id).put("presetId",presetId).put("title",title).put("summary",summary).put("configuration",configuration).put("revision",revision).put("migrating",migrating); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }
    public synchronized List<Instance> all() {
        try { return parse(new JSONArray(preferences.getString(KEY,"[]"))); }
        catch (JSONException error) { throw new IllegalStateException("无法读取主页配置，请恢复应用设置", error); }
    }
    public static List<Instance> parse(JSONArray array) throws JSONException {
        if (array.length() > 128) throw new JSONException("主页小部件过多");
        List<Instance> result=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(int i=0;i<array.length();i++){ Instance item=new Instance(array.getJSONObject(i)); if(!ids.add(item.pluginId+":"+item.id))throw new JSONException("小部件实例重复"); result.add(item); }
        return result;
    }
    public synchronized Instance find(String pluginId,String id) { return all().stream().filter(x->x.pluginId.equals(pluginId)&&x.id.equals(id)).findFirst().orElse(null); }
    public synchronized void ensureLegacy(List<PluginPackageStore.InstalledPlugin> installed) {
        Set<String> migrated=new LinkedHashSet<>(preferences.getStringSet("widget_instances_migrated",Collections.emptySet()));
        List<Instance> list=all(); boolean changed=false;
        for(PluginPackageStore.InstalledPlugin plugin:installed){
            String id=plugin.manifest.plugin.id;
            if(migrated.contains(id)||plugin.manifest.homeWidgetContributions.isEmpty())continue;
            migrated.add(id);changed=true;
            if(list.stream().anyMatch(x->x.pluginId.equals(id)))continue;
            RuntimePluginManifest.HomeWidgetContribution preset=plugin.manifest.homeWidgetContributions.get(0);
            try { list.add(new Instance(new JSONObject().put("pluginId",id).put("id",preset.id).put("presetId",preset.id).put("title",preset.title).put("configuration",new JSONObject()).put("migrating",!preset.configurationSource.isEmpty()))); }
            catch(JSONException impossible){throw new IllegalStateException(impossible);}
        }
        if(changed&&!preferences.edit().putString(KEY,encode(list).toString()).putStringSet("widget_instances_migrated",migrated).commit())throw new IllegalStateException("主页配置保存失败");
    }
    public synchronized Instance save(String pluginId,String instanceId,RuntimePluginManifest.HomeWidgetContribution preset,JSONObject config,long expectedRevision) {
        return save(pluginId,instanceId,preset,config,expectedRevision,"");
    }
    public synchronized Instance save(String pluginId,String instanceId,RuntimePluginManifest.HomeWidgetContribution preset,JSONObject config,long expectedRevision,String summary) {
        List<Instance> list=all(); Instance previous=instanceId==null?null:find(pluginId,instanceId);
        if(instanceId!=null&&(previous==null||previous.revision!=expectedRevision))throw new IllegalStateException("小部件已变化，请重新打开配置");
        if(previous==null&&list.size()>=128)throw new IllegalStateException("主页小部件数量已达到上限");
        try {
            Instance next=new Instance(new JSONObject().put("pluginId",pluginId).put("id",previous==null?UUID.randomUUID().toString():instanceId).put("presetId",preset.id).put("title",preset.title).put("summary",summary).put("configuration",config).put("revision",previous==null?1:previous.revision+1));
            if(previous==null)list.add(next);else for(int i=0;i<list.size();i++)if(list.get(i).pluginId.equals(pluginId)&&list.get(i).id.equals(instanceId)){list.set(i,next);break;}
            if(!preferences.edit().putString(KEY,encode(list).toString()).commit())throw new IllegalStateException("小部件配置保存失败");
            return next;
        }catch(JSONException error){throw new IllegalArgumentException("小部件配置无效",error);}
    }
    /** Remove one configuration and its layout metadata in the same preferences commit. */
    public synchronized void remove(String pluginId,String id,long expectedRevision) {
        List<Instance> list=all();Instance previous=list.stream().filter(x->x.pluginId.equals(pluginId)&&x.id.equals(id)).findFirst().orElse(null);
        if(previous==null||previous.revision!=expectedRevision)throw new IllegalStateException("小部件已变化，请重新打开管理");
        String oldOrder=preferences.getString("widget_order","");
        Set<String> oldHidden=new LinkedHashSet<>(preferences.getStringSet("hidden_widgets",Collections.emptySet()));
        Set<String> oldSizes=new LinkedHashSet<>(preferences.getStringSet("widget_sizes",Collections.emptySet()));
        Set<String> oldFullWidth=new LinkedHashSet<>(preferences.getStringSet("full_width_widgets",Collections.emptySet()));
        Set<String> oldMigrated=new LinkedHashSet<>(preferences.getStringSet("widget_instances_migrated",Collections.emptySet()));
        String key=pluginId+":"+id;String before=encode(list).toString();list.remove(previous);
        Set<String> hidden=new LinkedHashSet<>(preferences.getStringSet("hidden_widgets",Collections.emptySet()));hidden.remove(key);
        Set<String> sizes=new LinkedHashSet<>(preferences.getStringSet("widget_sizes",Collections.emptySet()));sizes.removeIf(x->x.startsWith(key+"="));
        Set<String> fullWidth=new LinkedHashSet<>(preferences.getStringSet("full_width_widgets",Collections.emptySet()));fullWidth.remove(key);
        Set<String> migrated=new LinkedHashSet<>(preferences.getStringSet("widget_instances_migrated",Collections.emptySet()));migrated.add(pluginId);
        List<String> order=new ArrayList<>(Arrays.asList(preferences.getString("widget_order","").split("\\n")));order.removeIf(x->x.equals(key)||x.isEmpty());
        if(!preferences.edit().putString(KEY,encode(list).toString()).putString("widget_order",String.join("\n",order))
                .putStringSet("hidden_widgets",hidden).putStringSet("widget_sizes",sizes).putStringSet("full_width_widgets",fullWidth)
                .putStringSet("widget_instances_migrated",migrated).commit()) {
            // SharedPreferences also changes its in-memory map when disk commit fails.
            // Restore only our owned keys before reporting failure.
            preferences.edit().putString(KEY,before).putString("widget_order",oldOrder)
                    .putStringSet("hidden_widgets",oldHidden).putStringSet("widget_sizes",oldSizes)
                    .putStringSet("full_width_widgets",oldFullWidth).putStringSet("widget_instances_migrated",oldMigrated).commit();
            throw new IllegalStateException("小部件移除失败，原配置已保留");
        }
    }
    public static JSONArray encode(List<Instance> list){JSONArray array=new JSONArray();for(Instance instance:list)array.put(instance.json());return array;}
}
