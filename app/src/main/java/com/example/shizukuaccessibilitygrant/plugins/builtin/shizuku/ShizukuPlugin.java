package com.example.shizukuaccessibilitygrant.plugins.builtin.shizuku;

import com.example.shizukuaccessibilitygrant.plugin.api.HomeWidget;
import com.example.shizukuaccessibilitygrant.plugin.api.PluginHost;
import com.example.shizukuaccessibilitygrant.plugin.api.PluginPermissionCatalog;
import com.example.shizukuaccessibilitygrant.plugin.api.ToolPlugin;
import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.shizukuaccessibilitygrant.ui.UiKit;
import com.example.shizukuaccessibilitygrant.plugins.ComposePluginUiKt;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class ShizukuPlugin implements ToolPlugin {
    public static final String ID = "shizuku_auth";

    private Activity activity;
    private PluginHost host;
    private TextView statusView;
    private TextView detailView;
    private Button authButton;
    private Button connectButton;
    private View rootView;
    private Runnable composeInvalidator;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Shizuku 授权";
    }

    @Override
    public String description() {
        return "宿主内置能力，为插件提供 Shizuku 授权和 UserService 连接。";
    }

    @Override
    public boolean removable() {
        return false;
    }

    @Override
    public Set<String> requestedPermissions() {
        return Collections.singleton(PluginPermissionCatalog.SHIZUKU);
    }

    @Override
    public List<HomeWidget> createHomeWidgets(Activity activity, PluginHost host) {
        return Collections.singletonList(new HomeWidget() {
            @Override
            public String id() {
                return "status";
            }

            @Override
            public String title() {
                return "Shizuku 状态";
            }

            @Override
            public String pluginId() {
                return ShizukuPlugin.ID;
            }

            @Override
            public List<com.example.shizukuaccessibilitygrant.plugin.api.HomeWidgetSize> supportedSizes() {
                return java.util.Arrays.asList(
                        new com.example.shizukuaccessibilitygrant.plugin.api.HomeWidgetSize(2, 2),
                        new com.example.shizukuaccessibilitygrant.plugin.api.HomeWidgetSize(4, 2),
                        new com.example.shizukuaccessibilitygrant.plugin.api.HomeWidgetSize(4, 3)
                );
            }

            @Override
            public View createView(Activity activity, PluginHost host) {
                return ComposePluginUiKt.createShizukuWidgetView(activity, host);
            }
        });
    }

    @Override
    public View createView(Activity activity, PluginHost host) {
        this.activity = activity;
        this.host = host;
        if (rootView == null) {
            rootView = ComposePluginUiKt.createShizukuPluginView(activity, this, host);
        }
        return rootView;
    }

    @Override
    public void onSelected() {
        invalidateComposeUi();
    }

    @Override
    public void onHostStateChanged() {
        invalidateComposeUi();
    }

    @Override
    public void onDestroy() {
        composeInvalidator = null;
    }

    public void setComposeInvalidator(Runnable invalidator) {
        composeInvalidator = invalidator;
    }

    private void invalidateComposeUi() {
        if (composeInvalidator != null) composeInvalidator.run();
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(activity);
        title.setText("Shizuku 授权");
        UiKit.styleTitle(title, 24);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(activity);
        description.setText("这里负责获取 Shizuku 权限并连接内部 UserService。其他插件需要在插件管理中获得权限后才能调用这些能力。");
        UiKit.styleBody(description);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(6);
        root.addView(description, descriptionParams);

        LinearLayout card = UiKit.card(activity);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(16);

        statusView = new TextView(activity);
        UiKit.styleTitle(statusView, 18);
        card.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        detailView = new TextView(activity);
        UiKit.styleBody(detailView);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(6);
        card.addView(detailView, detailParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(14);

        authButton = new Button(activity);
        authButton.setText("请求授权");
        UiKit.stylePrimaryButton(authButton);
        authButton.setOnClickListener(v -> host.requestShizukuPermission());
        actions.addView(authButton, new LinearLayout.LayoutParams(0, dp(46), 1));

        connectButton = new Button(activity);
        connectButton.setText("连接服务");
        UiKit.styleSecondaryButton(connectButton);
        connectButton.setOnClickListener(v -> host.ensureShellService());
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        connectParams.leftMargin = dp(10);
        actions.addView(connectButton, connectParams);

        card.addView(actions, actionsParams);
        root.addView(card, cardParams);
        return root;
    }

    private static View createStatusWidget(Activity activity, PluginHost host) {
        LinearLayout card = UiKit.card(activity);
        card.setBackground(UiKit.roundedStroke(0xFFEAF6F4, 0xFFD2E8E4, 8, activity));

        TextView title = new TextView(activity);
        title.setText("Shizuku");
        UiKit.styleCaption(title);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView state = new TextView(activity);
        state.setText(statusTitle(host));
        state.setTextSize(22);
        state.setTextColor(UiKit.COLOR_PRIMARY_DARK);
        state.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-1, -2);
        stateParams.topMargin = UiKit.dp(activity, 4);
        card.addView(state, stateParams);

        TextView detail = new TextView(activity);
        detail.setText(statusDetail(host));
        UiKit.styleBody(detail);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = UiKit.dp(activity, 4);
        card.addView(detail, detailParams);
        return card;
    }

    private void refresh() {
        if (statusView == null || host == null) {
            return;
        }
        statusView.setText(statusTitle(host));
        detailView.setText(statusDetail(host));
        UiKit.setEnabledVisual(authButton, host.isShizukuReady() && !host.hasShizukuPermission());
        UiKit.setEnabledVisual(connectButton, host.hasShizukuPermission() && !host.isShellServiceConnected());
    }

    private static String statusTitle(PluginHost host) {
        if (!host.isShizukuReady()) {
            return "未连接";
        }
        if (!host.hasShizukuPermission()) {
            return "等待授权";
        }
        if (!host.isShellServiceConnected()) {
            return "等待服务";
        }
        return "运行正常";
    }

    private static String statusDetail(PluginHost host) {
        if (!host.isShizukuReady()) {
            return "请先启动 Shizuku。";
        }
        if (!host.hasShizukuPermission()) {
            return "Shizuku 已连接，尚未授权本工具合集。";
        }
        if (!host.isShellServiceConnected()) {
            return "已授权，UserService 尚未连接，当前 UID: " + host.shizukuUid();
        }
        return "已授权并连接 UserService，当前 UID: " + host.shizukuUid();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
