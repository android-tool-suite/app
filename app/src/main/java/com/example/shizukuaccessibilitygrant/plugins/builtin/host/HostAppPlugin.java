package com.example.shizukuaccessibilitygrant.plugins.builtin.host;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.shizukuaccessibilitygrant.BuildConfig;
import com.example.shizukuaccessibilitygrant.plugin.api.PluginHost;
import com.example.shizukuaccessibilitygrant.plugin.api.ToolPlugin;
import com.example.shizukuaccessibilitygrant.ui.UiKit;

public final class HostAppPlugin implements ToolPlugin {
    public static final String ID = "host_app";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "宿主应用";
    }

    @Override
    public String description() {
        return "Android Tool Suite 宿主能力和版本声明。";
    }

    @Override
    public String version() {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public boolean removable() {
        return false;
    }

    @Override
    public View createView(Activity activity, PluginHost host) {
        LinearLayout root = UiKit.card(activity);

        TextView title = new TextView(activity);
        title.setText("宿主应用 " + BuildConfig.VERSION_NAME);
        UiKit.styleTitle(title, 20);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView body = new TextView(activity);
        body.setText("外部插件可通过依赖 host_app>=版本号 来要求宿主能力，例如网络权限和新版插件运行时。");
        UiKit.styleBody(body);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = UiKit.dp(activity, 6);
        root.addView(body, bodyParams);
        return root;
    }

    @Override
    public void onSelected() {
    }

    @Override
    public void onHostStateChanged() {
    }

    @Override
    public void onDestroy() {
    }
}
