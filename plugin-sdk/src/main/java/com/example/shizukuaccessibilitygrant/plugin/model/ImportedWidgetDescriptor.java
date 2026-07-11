package com.example.shizukuaccessibilitygrant.plugin.model;

import com.example.shizukuaccessibilitygrant.plugin.api.HomeWidgetSize;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ImportedWidgetDescriptor {
    public final String id;
    public final String title;
    public final String subtitle;
    public final String value;
    public final List<HomeWidgetSize> sizes;

    public ImportedWidgetDescriptor(String id, String title, String subtitle, String value, List<HomeWidgetSize> sizes) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.value = value;
        this.sizes = Collections.unmodifiableList(new ArrayList<>(sizes));
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
        List<HomeWidgetSize> sizes = new ArrayList<>();
        JSONArray sizeJson = json.optJSONArray("sizes");
        if (sizeJson != null) {
            for (int index = 0; index < sizeJson.length(); index++) {
                String[] parts = sizeJson.optString(index).toLowerCase().split("x");
                if (parts.length != 2) continue;
                try {
                    HomeWidgetSize size = new HomeWidgetSize(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                    if (!sizes.contains(size)) sizes.add(size);
                } catch (IllegalArgumentException ignored) {
                    // Invalid sizes are rejected below when no usable option remains.
                }
            }
        }
        if (sizes.isEmpty()) {
            sizes.add(new HomeWidgetSize(2, 2));
            sizes.add(new HomeWidgetSize(4, 2));
        }
        return new ImportedWidgetDescriptor(
                id,
                title,
                clean(json.optString("subtitle", "插件信息")),
                clean(json.optString("value", "已安装")),
                sizes
        );
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("title", title);
        json.put("subtitle", subtitle);
        json.put("value", value);
        JSONArray sizeJson = new JSONArray();
        for (HomeWidgetSize size : sizes) sizeJson.put(size.widthUnits + "x" + size.heightUnits);
        json.put("sizes", sizeJson);
        return json;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
