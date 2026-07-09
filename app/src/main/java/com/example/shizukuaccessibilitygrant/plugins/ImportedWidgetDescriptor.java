package com.example.shizukuaccessibilitygrant.plugins;

import org.json.JSONException;
import org.json.JSONObject;

public final class ImportedWidgetDescriptor {
    public final String id;
    public final String title;
    public final String subtitle;
    public final String value;

    public ImportedWidgetDescriptor(String id, String title, String subtitle, String value) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.value = value;
    }

    public static ImportedWidgetDescriptor fromJson(JSONObject json) throws JSONException {
        String id = clean(json.optString("id"));
        String title = clean(json.optString("title"));
        if (id.isEmpty()) {
            throw new JSONException("小部件缺少 id");
        }
        if (title.isEmpty()) {
            throw new JSONException("小部件缺少 title");
        }
        return new ImportedWidgetDescriptor(
                id,
                title,
                clean(json.optString("subtitle", "插件信息")),
                clean(json.optString("value", "已安装"))
        );
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("title", title);
        json.put("subtitle", subtitle);
        json.put("value", value);
        return json;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
