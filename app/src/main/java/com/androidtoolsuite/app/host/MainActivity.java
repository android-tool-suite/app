package com.androidtoolsuite.app.host;

import com.androidtoolsuite.app.BuildConfig;
import com.androidtoolsuite.app.IShellService;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import com.androidtoolsuite.app.plugin.store.BuiltInPluginStateStore;
import com.androidtoolsuite.app.plugin.store.ExternalPluginStore;
import com.androidtoolsuite.app.plugin.runtime.ExternalToolFactory;
import com.androidtoolsuite.app.plugin.api.HomeWidget;
import com.androidtoolsuite.app.plugin.api.HomeWidgetSize;
import com.androidtoolsuite.app.plugin.api.PluginDependency;
import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor;
import com.androidtoolsuite.app.plugin.api.PluginHost;
import com.androidtoolsuite.app.plugins.builtin.shizuku.ShizukuPlugin;
import com.androidtoolsuite.app.plugin.api.ToolPlugin;
import com.androidtoolsuite.app.plugin.runtime.ToolRegistry;
import com.androidtoolsuite.app.ui.UiKit;

import org.json.JSONException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import rikka.shizuku.Shizuku;

public class MainActivity extends ComponentActivity implements PluginHost {
    public static final String EXTRA_DEBUG_DESTINATION = "debug_destination";
    private static WeakReference<MainActivity> debugInstance = new WeakReference<>(null);

    private static final int REQUEST_SHIZUKU = 3001;
    private static final int REQUEST_IMPORT_PLUGIN = 4001;
    private static final int REQUEST_EXPORT_PLUGIN = 4002;

    private static final int SECTION_DASHBOARD = 0;
    private static final int SECTION_PLUGINS = 1;
    private static final int SECTION_MANAGER = 2;

    private static final String PREFS_NAME = "main_ui";
    private static final String PREF_HIDDEN_WIDGETS = "hidden_widgets";
    private static final String PREF_HIDDEN_TOOLS = "hidden_tools";
    private static final String PREF_TOOL_ORDER = "tool_order";
    private static final String PREF_WIDGET_ORDER = "widget_order";
    private static final String PREF_FULL_WIDTH_WIDGETS = "full_width_widgets";
    private static final String PREF_WIDGET_SIZES = "widget_sizes";

    private final List<ToolPlugin> plugins = new ArrayList<>();
    private final List<Button> bottomButtons = new ArrayList<>();
    private LinearLayout contentRoot;
    private LinearLayout bottomBar;
    private ToolPlugin selectedPlugin;
    private ExternalPluginStore externalPluginStore;
    private BuiltInPluginStateStore builtInPluginStateStore;
    private SharedPreferences uiPreferences;
    private String pendingExportPluginId;
    private int currentSection = SECTION_DASHBOARD;
    private int pluginReturnSection = SECTION_PLUGINS;
    private boolean interfaceManagementOpen;
    private final HostUiState composeState = new HostUiState();
    private final Map<String, int[]> composeScrollPositions = new LinkedHashMap<>();

    private final OnBackPressedCallback appBackCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if (handleAppBack()) {
                return;
            }
            // Let ComponentActivity perform its normal finish behavior only from the dashboard.
            setEnabled(false);
            getOnBackPressedDispatcher().onBackPressed();
            setEnabled(true);
        }
    };
    private IShellService shellService;
    private Shizuku.UserServiceArgs shellServiceArgs;
    private boolean shellServiceBinding;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> runOnUiThread(() -> {
        ensureShellServiceIfAuthorized();
        notifyHostStateChangedAfterBinderCallback();
    });
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> runOnUiThread(() -> {
        shellService = null;
        shellServiceBinding = false;
        notifyHostStateChanged();
    });
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        if (requestCode == REQUEST_SHIZUKU) {
            runOnUiThread(() -> {
                ensureShellServiceIfAuthorized();
                notifyHostStateChangedAfterBinderCallback();
            });
        }
    };
    private final ServiceConnection shellConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            shellService = IShellService.Stub.asInterface(service);
            shellServiceBinding = false;
            runOnUiThread(MainActivity.this::notifyHostStateChangedAfterBinderCallback);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            shellService = null;
            shellServiceBinding = false;
            runOnUiThread(MainActivity.this::notifyHostStateChangedAfterBinderCallback);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        externalPluginStore = new ExternalPluginStore(this);
        builtInPluginStateStore = new BuiltInPluginStateStore(this);
        uiPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadPlugins();
        setContentView(createContentView());
        getOnBackPressedDispatcher().addCallback(this, appBackCallback);
        if (BuildConfig.DEBUG) {
            debugInstance = new WeakReference<>(this);
        }

        Shizuku.addBinderReceivedListener(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        showDashboard();
        applyDebugDestination(getIntent());
        ensureShellServiceIfAuthorized();
        notifyHostStateChanged();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (BuildConfig.DEBUG) {
            reloadPlugins(null);
            applyDebugDestination(intent);
        }
    }

    @Override
    protected void onDestroy() {
        for (ToolPlugin plugin : plugins) {
            plugin.onDestroy();
        }
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        if (debugInstance.get() == this) {
            debugInstance.clear();
        }
        unbindShellService();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (handleAppBack()) {
            return;
        }
        super.onBackPressed();
    }

    private boolean handleAppBack() {
        if (interfaceManagementOpen) {
            closeInterfaceManagementForUi();
            return true;
        }
        if (selectedPlugin != null) {
            closePluginForUi();
            return true;
        }
        if (currentSection == SECTION_MANAGER || currentSection == SECTION_PLUGINS) {
            showDashboard();
            return true;
        }
        return false;
    }

    public boolean canHandleBackForUi() {
        return interfaceManagementOpen
                || selectedPlugin != null
                || currentSection == SECTION_MANAGER
                || currentSection == SECTION_PLUGINS;
    }

    public void handleBackForUi() {
        handleAppBack();
    }

    public static void notifyDebugStateChanged() {
        MainActivity activity = debugInstance.get();
        if (!BuildConfig.DEBUG || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        activity.runOnUiThread(() -> activity.reloadPlugins(
                activity.selectedPlugin == null ? null : activity.selectedPlugin.id()
        ));
    }

    private void applyDebugDestination(Intent intent) {
        if (!BuildConfig.DEBUG || intent == null) {
            return;
        }
        String destination = intent.getStringExtra(EXTRA_DEBUG_DESTINATION);
        if (destination == null || destination.isEmpty() || "dashboard".equals(destination)) {
            return;
        }
        if ("plugins".equals(destination)) {
            showPluginList();
        } else if ("manager".equals(destination)) {
            showPluginManager();
        } else if (destination.startsWith("plugin:")) {
            ToolPlugin plugin = findPlugin(destination.substring("plugin:".length()));
            if (plugin != null) {
                openPlugin(plugin);
            } else {
                showPluginList();
            }
        }
    }

    private View createContentView() {
        return HostAppUiKt.createHostAppView(this);
    }

    private void addBottomButton(String text, int section) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(v -> {
            if (section == SECTION_DASHBOARD) {
                showDashboard();
            } else if (section == SECTION_PLUGINS) {
                showPluginList();
            } else {
                showPluginManager();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        if (!bottomButtons.isEmpty()) {
            params.leftMargin = dp(6);
        }
        bottomBar.addView(button, params);
        bottomButtons.add(button);
    }

    private void showDashboard() {
        currentSection = SECTION_DASHBOARD;
        selectedPlugin = null;
        interfaceManagementOpen = false;
        invalidateComposeUi();
    }

    private View createDashboardView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(8), 0, dp(8));
        scrollView.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        TextView date = new TextView(this);
        date.setText(new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(new Date()));
        UiKit.styleCaption(date);
        header.addView(date, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("工具台");
        UiKit.styleTitle(title, 30);
        header.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText(String.format(Locale.US, "%d 个插件 · %d 个主页小部件", plugins.size(), collectWidgets().size()));
        UiKit.styleBody(subtitle);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(2);
        header.addView(subtitle, subtitleParams);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout quick = UiKit.card(this);
        quick.setBackground(UiKit.roundedStroke(0xFFEAF6F4, 0xFFD2E8E4, 8, this));
        LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(-1, -2);
        quickParams.topMargin = dp(18);

        TextView quickTitle = new TextView(this);
        quickTitle.setText("运行概览");
        UiKit.styleTitle(quickTitle, 18);
        quick.addView(quickTitle, new LinearLayout.LayoutParams(-1, -2));

        TextView quickText = new TextView(this);
        quickText.setText(buildDashboardSummary());
        UiKit.styleBody(quickText);
        LinearLayout.LayoutParams quickTextParams = new LinearLayout.LayoutParams(-1, -2);
        quickTextParams.topMargin = dp(6);
        quick.addView(quickText, quickTextParams);
        root.addView(quick, quickParams);

        addSectionLabel(root, "主页小部件", dp(20));
        List<WidgetRegistration> widgets = collectWidgets();
        boolean hasVisibleWidget = false;
        for (WidgetRegistration registration : widgets) {
            if (isWidgetVisible(registration.key)) {
                LinearLayout.LayoutParams widgetParams = new LinearLayout.LayoutParams(-1, -2);
                widgetParams.bottomMargin = dp(10);
                root.addView(registration.widget.createView(this, this), widgetParams);
                hasVisibleWidget = true;
            }
        }
        if (!hasVisibleWidget) {
            root.addView(createEmptyCard("还没有启用主页小部件。你可以在下方自由组合插件的小部件。"), new LinearLayout.LayoutParams(-1, -2));
        }

        addSectionLabel(root, "自定义主页", dp(12));
        LinearLayout customizer = UiKit.card(this);
        for (WidgetRegistration registration : widgets) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(registration.widget.title() + " · " + registration.pluginTitle);
            checkBox.setTextSize(14);
            checkBox.setTextColor(UiKit.COLOR_TEXT);
            checkBox.setChecked(isWidgetVisible(registration.key));
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                setWidgetVisible(registration.key, isChecked);
                showDashboard();
            });
            customizer.addView(checkBox, new LinearLayout.LayoutParams(-1, -2));
        }
        if (widgets.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("插件还没有注册主页小部件。");
            UiKit.styleBody(empty);
            customizer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        root.addView(customizer, new LinearLayout.LayoutParams(-1, -2));
        return scrollView;
    }

    private void showPluginList() {
        currentSection = SECTION_PLUGINS;
        selectedPlugin = null;
        interfaceManagementOpen = false;
        invalidateComposeUi();
    }

    private View createPluginListView() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(8), 0, dp(8));
        scrollView.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("插件");
        UiKit.styleTitle(title, 30);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("每个插件都有自己的页面。点开插件即可进入它的功能界面。");
        UiKit.styleBody(subtitle);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(4);
        root.addView(subtitle, subtitleParams);

        for (ToolPlugin plugin : plugins) {
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.topMargin = dp(12);
            root.addView(createPluginRow(plugin), rowParams);
        }
        return scrollView;
    }

    private View createPluginRow(ToolPlugin plugin) {
        LinearLayout row = UiKit.card(this);
        row.setOnClickListener(v -> openPlugin(plugin));

        TextView title = new TextView(this);
        title.setText(plugin.title());
        UiKit.styleTitle(title, 18);
        row.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(this);
        description.setText(plugin.description());
        UiKit.styleBody(description);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(4);
        row.addView(description, descriptionParams);

        TextView meta = new TextView(this);
        meta.setText((plugin.removable() ? "外部插件" : "内置插件")
                + " · 版本 " + plugin.version()
                + " · 依赖 " + plugin.dependencies().size()
                + " · 小部件 " + plugin.createHomeWidgets(this, this).size());
        UiKit.styleCaption(meta);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
        metaParams.topMargin = dp(8);
        row.addView(meta, metaParams);

        Button open = new Button(this);
        open.setText("打开");
        UiKit.styleSecondaryButton(open);
        open.setOnClickListener(v -> openPlugin(plugin));
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(-1, dp(44));
        openParams.topMargin = dp(10);
        row.addView(open, openParams);
        return row;
    }

    private void openPlugin(ToolPlugin plugin) {
        pluginReturnSection = currentSection == SECTION_DASHBOARD
                ? SECTION_DASHBOARD
                : SECTION_PLUGINS;
        selectedPlugin = plugin;
        plugin.onSelected();
        invalidateComposeUi();
    }

    private void detachFromParent(View view) {
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(view);
        }
    }

    private void showPluginManager() {
        currentSection = SECTION_MANAGER;
        selectedPlugin = null;
        interfaceManagementOpen = false;
        invalidateComposeUi();
    }

    private void updateBottomButtons() {
        for (int i = 0; i < bottomButtons.size(); i++) {
            UiKit.styleTab(bottomButtons.get(i), i == currentSection);
        }
    }

    private void addSectionLabel(LinearLayout root, String text, int topMargin) {
        TextView label = new TextView(this);
        label.setText(text);
        UiKit.styleTitle(label, 18);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = topMargin;
        params.bottomMargin = dp(8);
        root.addView(label, params);
    }

    private View createEmptyCard(String message) {
        LinearLayout card = UiKit.card(this);
        TextView text = new TextView(this);
        text.setText(message);
        text.setGravity(Gravity.CENTER);
        UiKit.styleBody(text);
        card.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private String buildDashboardSummary() {
        int external = 0;
        for (ToolPlugin plugin : plugins) {
            if (plugin.removable()) {
                external++;
            }
        }
        return "内置插件 " + (plugins.size() - external)
                + " 个，外部插件 " + external
                + " 个。插件管理负责导入、导出、删除和权限授予。";
    }

    private List<WidgetRegistration> collectWidgets() {
        List<WidgetRegistration> widgets = new ArrayList<>();
        for (ToolPlugin plugin : plugins) {
            for (HomeWidget widget : plugin.createHomeWidgets(this, this)) {
                widgets.add(new WidgetRegistration(plugin.title(), plugin.id() + ":" + widget.id(), widget));
            }
        }
        return widgets;
    }

    private List<ToolPlugin> orderedTools(boolean includeHidden) {
        List<ToolPlugin> ordered = new ArrayList<>(plugins);
        List<String> ids = new ArrayList<>();
        for (ToolPlugin plugin : ordered) {
            ids.add(plugin.id());
        }
        List<String> savedOrder = readOrder(PREF_TOOL_ORDER, ids);
        ordered.sort((left, right) -> Integer.compare(savedOrder.indexOf(left.id()), savedOrder.indexOf(right.id())));
        if (includeHidden) {
            return ordered;
        }
        Set<String> hidden = uiPreferences.getStringSet(PREF_HIDDEN_TOOLS, new LinkedHashSet<>());
        List<ToolPlugin> visible = new ArrayList<>();
        for (ToolPlugin plugin : ordered) {
            if (!hidden.contains(plugin.id())) {
                visible.add(plugin);
            }
        }
        return visible;
    }

    private List<WidgetRegistration> orderedWidgets(boolean includeHidden) {
        List<WidgetRegistration> widgets = collectWidgets();
        List<String> keys = new ArrayList<>();
        for (WidgetRegistration registration : widgets) {
            keys.add(registration.key);
        }
        List<String> savedOrder = readOrder(PREF_WIDGET_ORDER, keys);
        widgets.sort((left, right) -> Integer.compare(savedOrder.indexOf(left.key), savedOrder.indexOf(right.key)));
        if (includeHidden) {
            return widgets;
        }
        List<WidgetRegistration> visible = new ArrayList<>();
        for (WidgetRegistration registration : widgets) {
            if (isWidgetVisible(registration.key)) {
                visible.add(registration);
            }
        }
        return visible;
    }

    private List<String> readOrder(String preferenceKey, List<String> keys) {
        List<String> ordered = new ArrayList<>();
        String saved = uiPreferences.getString(preferenceKey, "");
        if (!saved.isEmpty()) {
            for (String key : saved.split("\\n")) {
                if (keys.contains(key) && !ordered.contains(key)) {
                    ordered.add(key);
                }
            }
        }
        for (String key : keys) {
            if (!ordered.contains(key)) {
                ordered.add(key);
            }
        }
        return ordered;
    }

    private void saveOrder(String preferenceKey, List<String> keys) {
        uiPreferences.edit().putString(preferenceKey, String.join("\n", keys)).apply();
    }

    private boolean isWidgetVisible(String key) {
        Set<String> hidden = uiPreferences.getStringSet(PREF_HIDDEN_WIDGETS, new LinkedHashSet<>());
        return !hidden.contains(key);
    }

    private void setWidgetVisible(String key, boolean visible) {
        Set<String> hidden = new LinkedHashSet<>(uiPreferences.getStringSet(PREF_HIDDEN_WIDGETS, new LinkedHashSet<>()));
        if (visible) {
            hidden.remove(key);
        } else {
            hidden.add(key);
        }
        uiPreferences.edit().putStringSet(PREF_HIDDEN_WIDGETS, hidden).apply();
    }

    private void loadPlugins() {
        plugins.clear();
        LinkedHashMap<String, String> activeVersions = new LinkedHashMap<>();
        for (ToolPlugin plugin : ToolRegistry.createRequiredBuiltInPlugins()) {
            if (areDependenciesSatisfied(plugin.dependencies(), activeVersions)) {
                plugins.add(plugin);
                activeVersions.put(plugin.id(), plugin.version());
            } else {
                plugin.onDestroy();
            }
        }
        for (ToolPlugin plugin : ToolRegistry.createOptionalBuiltInPlugins()) {
            if (builtInPluginStateStore.isEnabled(plugin.id()) && areDependenciesSatisfied(plugin.dependencies(), activeVersions)) {
                plugins.add(plugin);
                activeVersions.put(plugin.id(), plugin.version());
            } else {
                plugin.onDestroy();
            }
        }
        List<ImportedPluginDescriptor> pendingExternalPlugins = new ArrayList<>(externalPluginStore.load());
        boolean loadedPlugin;
        do {
            loadedPlugin = false;
            for (int i = pendingExternalPlugins.size() - 1; i >= 0; i--) {
                ImportedPluginDescriptor descriptor = pendingExternalPlugins.get(i);
                if (!externalPluginStore.isEnabled(descriptor.id)) {
                    pendingExternalPlugins.remove(i);
                } else if (areDependenciesSatisfied(descriptor.dependencies, activeVersions)) {
                    ToolPlugin plugin = ExternalToolFactory.create(this, descriptor);
                    if (plugin != null) {
                        plugins.add(plugin);
                        activeVersions.put(plugin.id(), plugin.version());
                    }
                    pendingExternalPlugins.remove(i);
                    loadedPlugin = true;
                }
            }
        } while (loadedPlugin);
    }

    private boolean areDependenciesSatisfied(Set<String> dependencies, Map<String, String> activeVersions) {
        for (String dependency : dependencies) {
            if (!PluginDependency.parse(dependency).isSatisfied(activeVersions)) {
                return false;
            }
        }
        return true;
    }

    private void reloadPlugins(String preferredPluginId) {
        for (ToolPlugin plugin : plugins) {
            plugin.onDestroy();
        }
        loadPlugins();
        selectedPlugin = findPlugin(preferredPluginId);
        if (currentSection == SECTION_DASHBOARD) {
            showDashboard();
        } else if (currentSection == SECTION_MANAGER) {
            showPluginManager();
        } else if (selectedPlugin != null) {
            openPlugin(selectedPlugin);
        } else {
            showPluginList();
        }
    }

    private ToolPlugin findPlugin(String pluginId) {
        if (pluginId == null) {
            return null;
        }
        for (ToolPlugin plugin : plugins) {
            if (plugin.id().equals(pluginId)) {
                return plugin;
            }
        }
        return null;
    }

    private void notifyHostStateChanged() {
        if (selectedPlugin != null) {
            selectedPlugin.onHostStateChanged();
        }
        invalidateComposeUi();
    }

    private void notifyHostStateChangedAfterBinderCallback() {
        notifyHostStateChanged();
        getWindow().getDecorView().postDelayed(this::notifyHostStateChanged, 200L);
    }

    public void invalidateComposeUi() {
        composeState.captureScrollPositions(this);
        composeState.bump();
    }

    public HostUiState uiStateForUi() {
        return composeState;
    }

    @Override
    public int hostStateRevision() {
        return composeState.getRevision();
    }

    public int scrollIndexForUi(String page) {
        int[] position = composeScrollPositions.get(page);
        return position == null ? 0 : position[0];
    }

    public int scrollOffsetForUi(String page) {
        int[] position = composeScrollPositions.get(page);
        return position == null ? 0 : position[1];
    }

    public void saveScrollPositionForUi(String page, int index, int offset) {
        composeScrollPositions.put(page, new int[]{Math.max(0, index), Math.max(0, offset)});
    }

    public void rebuildComposeUi() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        setContentView(createContentView());
    }

    public int currentSectionForUi() {
        return currentSection;
    }

    public ToolPlugin selectedPluginForUi() {
        return selectedPlugin;
    }

    public List<ToolPlugin> pluginsForUi() {
        return orderedTools(false);
    }

    public List<ToolPlugin> allToolsForUi() {
        return orderedTools(true);
    }

    public boolean isToolVisibleForUi(ToolPlugin plugin) {
        return !uiPreferences.getStringSet(PREF_HIDDEN_TOOLS, new LinkedHashSet<>()).contains(plugin.id());
    }

    public void setToolVisibleForUi(ToolPlugin plugin, boolean visible) {
        Set<String> hidden = new LinkedHashSet<>(uiPreferences.getStringSet(PREF_HIDDEN_TOOLS, new LinkedHashSet<>()));
        if (visible) hidden.remove(plugin.id()); else hidden.add(plugin.id());
        uiPreferences.edit().putStringSet(PREF_HIDDEN_TOOLS, hidden).apply();
        invalidateComposeUi();
    }

    public void moveToolToUi(String pluginId, String targetPluginId) {
        List<ToolPlugin> tools = orderedTools(true);
        int index = -1;
        int target = -1;
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).id().equals(pluginId)) index = i;
            if (tools.get(i).id().equals(targetPluginId)) target = i;
        }
        if (index < 0 || target < 0 || target == index) return;
        ToolPlugin moved = tools.remove(index);
        tools.add(Math.max(0, Math.min(tools.size(), target)), moved);
        List<String> ids = new ArrayList<>();
        for (ToolPlugin tool : tools) ids.add(tool.id());
        saveOrder(PREF_TOOL_ORDER, ids);
        invalidateComposeUi();
    }

    public List<HomeWidget> widgetsForUi() {
        List<HomeWidget> result = new ArrayList<>();
        for (WidgetRegistration registration : orderedWidgets(false)) {
            result.add(registration.widget);
        }
        return result;
    }

    public List<HomeWidget> allWidgetsForUi() {
        List<HomeWidget> result = new ArrayList<>();
        for (WidgetRegistration registration : orderedWidgets(true)) {
            result.add(registration.widget);
        }
        return result;
    }

    public boolean isWidgetVisibleForUi(HomeWidget widget) {
        return isWidgetVisible(widget.pluginId() + ":" + widget.id());
    }

    public void setWidgetVisibleForUi(HomeWidget widget, boolean visible) {
        setWidgetVisible(widget.pluginId() + ":" + widget.id(), visible);
        invalidateComposeUi();
    }

    public boolean hasHomeWidgetsForUi(ToolPlugin plugin) {
        return !plugin.createHomeWidgets(this, this).isEmpty();
    }

    public boolean isPluginHomeVisibleForUi(ToolPlugin plugin) {
        List<HomeWidget> widgets = plugin.createHomeWidgets(this, this);
        if (widgets.isEmpty()) return false;
        for (HomeWidget widget : widgets) {
            if (!isWidgetVisible(widget.pluginId() + ":" + widget.id())) return false;
        }
        return true;
    }

    public void setPluginHomeVisibleForUi(ToolPlugin plugin, boolean visible) {
        for (HomeWidget widget : plugin.createHomeWidgets(this, this)) {
            setWidgetVisible(widget.pluginId() + ":" + widget.id(), visible);
        }
        invalidateComposeUi();
    }

    public void moveWidgetToUi(HomeWidget widget, HomeWidget targetWidget) {
        List<WidgetRegistration> widgets = orderedWidgets(true);
        String key = widget.pluginId() + ":" + widget.id();
        String targetKey = targetWidget.pluginId() + ":" + targetWidget.id();
        int index = -1;
        int target = -1;
        for (int i = 0; i < widgets.size(); i++) {
            if (widgets.get(i).key.equals(key)) index = i;
            if (widgets.get(i).key.equals(targetKey)) target = i;
        }
        if (index < 0 || target < 0 || target == index) return;
        WidgetRegistration moved = widgets.remove(index);
        widgets.add(Math.max(0, Math.min(widgets.size(), target)), moved);
        List<String> keys = new ArrayList<>();
        for (WidgetRegistration registration : widgets) keys.add(registration.key);
        saveOrder(PREF_WIDGET_ORDER, keys);
        invalidateComposeUi();
    }

    public boolean isWidgetFullWidthForUi(HomeWidget widget) {
        return widgetWidthUnitsForUi(widget) == 4;
    }

    public void setWidgetFullWidthForUi(HomeWidget widget, boolean fullWidth) {
        setWidgetSizeForUi(widget, fullWidth ? 4 : 2, widgetHeightUnitsForUi(widget));
    }

    public int widgetWidthUnitsForUi(HomeWidget widget) {
        return currentWidgetSizeForUi(widget).widthUnits;
    }

    public int widgetHeightUnitsForUi(HomeWidget widget) {
        return currentWidgetSizeForUi(widget).heightUnits;
    }

    public void setWidgetSizeForUi(HomeWidget widget, int widthUnits, int heightUnits) {
        HomeWidgetSize requested = closestSupportedWidgetSize(widget, widthUnits, heightUnits);
        String key = widget.pluginId() + ":" + widget.id();
        Set<String> sizes = new LinkedHashSet<>(uiPreferences.getStringSet(PREF_WIDGET_SIZES, new LinkedHashSet<>()));
        sizes.removeIf(entry -> entry.startsWith(key + "="));
        sizes.add(key + "=" + requested.widthUnits + "x" + requested.heightUnits);
        uiPreferences.edit().putStringSet(PREF_WIDGET_SIZES, sizes).apply();
        invalidateComposeUi();
    }

    public int widgetSizeIndexForUi(HomeWidget widget) {
        return widget.supportedSizes().indexOf(currentWidgetSizeForUi(widget));
    }

    public int widgetSizeCountForUi(HomeWidget widget) {
        return widget.supportedSizes().size();
    }

    public void changeWidgetSizeForUi(HomeWidget widget, int direction) {
        List<HomeWidgetSize> supported = widget.supportedSizes();
        if (supported.isEmpty()) return;
        int current = Math.max(0, supported.indexOf(currentWidgetSizeForUi(widget)));
        int target = Math.max(0, Math.min(supported.size() - 1, current + direction));
        HomeWidgetSize size = supported.get(target);
        setWidgetSizeForUi(widget, size.widthUnits, size.heightUnits);
    }

    private HomeWidgetSize currentWidgetSizeForUi(HomeWidget widget) {
        int fallbackWidth = legacyFullWidth(widget) ? 4 : 2;
        int savedWidth = widgetSizeForUi(widget, 0, fallbackWidth);
        int savedHeight = widgetSizeForUi(widget, 1, 2);
        return closestSupportedWidgetSize(widget, savedWidth, savedHeight);
    }

    private HomeWidgetSize closestSupportedWidgetSize(HomeWidget widget, int width, int height) {
        List<HomeWidgetSize> supported = widget.supportedSizes();
        if (supported.isEmpty()) {
            throw new IllegalStateException("Widget must provide at least one supported size: " + widget.pluginId() + ":" + widget.id());
        }
        HomeWidgetSize closest = supported.get(0);
        int closestDistance = Integer.MAX_VALUE;
        for (HomeWidgetSize size : supported) {
            int distance = Math.abs(size.widthUnits - width) + Math.abs(size.heightUnits - height);
            if (distance < closestDistance) {
                closest = size;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private boolean legacyFullWidth(HomeWidget widget) {
        return uiPreferences.getStringSet(PREF_FULL_WIDTH_WIDGETS, new LinkedHashSet<>())
                .contains(widget.pluginId() + ":" + widget.id());
    }

    private int widgetSizeForUi(HomeWidget widget, int part, int fallback) {
        String key = widget.pluginId() + ":" + widget.id() + "=";
        for (String entry : uiPreferences.getStringSet(PREF_WIDGET_SIZES, new LinkedHashSet<>())) {
            if (!entry.startsWith(key)) continue;
            String[] values = entry.substring(key.length()).split("x");
            if (values.length != 2) continue;
            try {
                return Math.max(1, Math.min(4, Integer.parseInt(values[part])));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public void navigateForUi(int section) {
        if (section == SECTION_DASHBOARD) showDashboard();
        else if (section == SECTION_PLUGINS) showPluginList();
        else showPluginManager();
    }

    public void openPluginForUi(ToolPlugin plugin) {
        openPlugin(plugin);
    }

    public void openPluginForUi(String pluginId) {
        ToolPlugin plugin = findPlugin(pluginId);
        if (plugin != null) {
            openPlugin(plugin);
        }
    }

    public void closePluginForUi() {
        if (pluginReturnSection == SECTION_DASHBOARD) {
            showDashboard();
        } else {
            showPluginList();
        }
    }

    public List<ImportedPluginDescriptor> importedDescriptorsForUi() {
        return externalPluginStore.load();
    }

    public boolean isInterfaceManagementOpenForUi() {
        return interfaceManagementOpen;
    }

    public void showInterfaceManagementForUi() {
        currentSection = SECTION_MANAGER;
        selectedPlugin = null;
        interfaceManagementOpen = true;
        invalidateComposeUi();
    }

    public void closeInterfaceManagementForUi() {
        interfaceManagementOpen = false;
        invalidateComposeUi();
    }

    public boolean isPluginLoadedForUi(String pluginId) {
        return findPlugin(pluginId) != null;
    }

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean hasShizukuPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean isShellServiceConnected() {
        return shellService != null;
    }

    @Override
    public int shizukuUid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    @Override
    public void requestShizukuPermission() {
        if (!isShizukuReady()) {
            showToast("Shizuku 未连接");
            return;
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            showToast("你之前拒绝了 Shizuku 授权，请在 Shizuku 应用里手动允许");
            return;
        }
        Shizuku.requestPermission(REQUEST_SHIZUKU);
    }

    @Override
    public void ensureShellService() {
        if (shellServiceBinding || shellService != null || !hasShizukuPermission()) {
            return;
        }
        shellServiceBinding = true;
        ComponentName componentName = new ComponentName(getPackageName(), ShellUserService.class.getName());
        shellServiceArgs = new Shizuku.UserServiceArgs(componentName)
                .daemon(false)
                .debuggable(BuildConfig.DEBUG)
                .processNameSuffix("shell")
                .tag("shell")
                .version(1);
        try {
            Shizuku.bindUserService(shellServiceArgs, shellConnection);
        } catch (Throwable e) {
            shellServiceBinding = false;
            showToast("连接 UserService 失败：" + e.getMessage());
        }
    }

    private void ensureShellServiceIfAuthorized() {
        if (isShizukuReady() && hasShizukuPermission()) {
            ensureShellService();
        }
    }

    @Override
    public String runShellCommand(String... command) throws IOException {
        IShellService service = shellService;
        if (service == null) {
            throw new IOException("Shizuku UserService 未连接");
        }
        try {
            return service.run(command);
        } catch (RemoteException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    public void importPlugin() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT_PLUGIN);
    }

    @Override
    public void exportPlugin(String pluginId) {
        ImportedPluginDescriptor descriptor = findImportedDescriptor(pluginId);
        if (descriptor == null) {
            showToast("只能导出外部插件清单");
            return;
        }
        pendingExportPluginId = pluginId;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, descriptor.id + ".atsplugin");
        startActivityForResult(intent, REQUEST_EXPORT_PLUGIN);
    }

    @Override
    public void deleteImportedPlugin(String pluginId) {
        List<String> dependents = findDependentPluginTitles(pluginId);
        if (!dependents.isEmpty()) {
            showToast("无法删除，仍被依赖：" + joinNames(dependents));
            return;
        }
        try {
            externalPluginStore.delete(pluginId);
            showToast("已删除插件");
            reloadPlugins(selectedPlugin == null || selectedPlugin.id().equals(pluginId) ? null : selectedPlugin.id());
        } catch (JSONException e) {
            showToast("删除失败：" + e.getMessage());
        }
    }

    @Override
    public void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_PLUGIN) {
            handleImportResult(resultCode, data);
        } else if (requestCode == REQUEST_EXPORT_PLUGIN) {
            handleExportResult(resultCode, data);
        }
    }

    private void handleImportResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try {
            PluginImport pluginImport = readPluginPackage(data.getData());
            ImportedPluginDescriptor descriptor = pluginImport.descriptor;
            if (isBuiltInPluginId(descriptor.id)) {
                showToast("插件 ID 与内置插件冲突");
                return;
            }
            boolean updating = findImportedDescriptor(descriptor.id) != null;
            externalPluginStore.savePluginCode(descriptor.id, pluginImport.codeBytes);
            externalPluginStore.save(descriptor);
            showToast((updating ? "已更新插件：" : "已导入插件，默认停用：") + descriptor.title);
            reloadPlugins(descriptor.id);
        } catch (IOException | JSONException e) {
            showToast("导入失败：" + e.getMessage());
        }
    }

    private void handleExportResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null || pendingExportPluginId == null) {
            pendingExportPluginId = null;
            return;
        }
        ImportedPluginDescriptor descriptor = findImportedDescriptor(pendingExportPluginId);
        pendingExportPluginId = null;
        if (descriptor == null) {
            showToast("导出失败：插件不存在");
            return;
        }
        try (OutputStream outputStream = getContentResolver().openOutputStream(data.getData())) {
            if (outputStream == null) {
                throw new IOException("无法写入文件");
            }
            writePluginPackage(outputStream, descriptor);
            showToast("已导出插件包");
        } catch (IOException | JSONException e) {
            showToast("导出失败：" + e.getMessage());
        }
    }

    @Override
    public boolean isImportedPluginEnabled(String pluginId) {
        return externalPluginStore.isEnabled(pluginId);
    }

    @Override
    public void setImportedPluginEnabled(String pluginId, boolean enabled) {
        ImportedPluginDescriptor descriptor = findImportedDescriptor(pluginId);
        if (descriptor == null) {
            showToast("插件不存在");
            return;
        }
        if (enabled) {
            List<String> missingDependencies = findMissingDependencyTitles(descriptor.dependencies);
            if (!missingDependencies.isEmpty()) {
                showToast("无法启用，依赖未满足：" + joinNames(missingDependencies));
                return;
            }
        } else {
            List<String> dependents = findDependentPluginTitles(pluginId);
            if (!dependents.isEmpty()) {
                showToast("无法停用，仍被依赖：" + joinNames(dependents));
                return;
            }
        }
        externalPluginStore.setEnabled(pluginId, enabled);
        showToast(enabled ? "已启用插件" : "已停用插件");
        reloadPlugins(enabled ? pluginId : null);
    }

    @Override
    public List<ToolPlugin> optionalBuiltInPlugins() {
        return ToolRegistry.createOptionalBuiltInPlugins();
    }

    @Override
    public List<ToolPlugin> installedPlugins() {
        return new ArrayList<>(plugins);
    }

    @Override
    public boolean isBuiltInPluginEnabled(String pluginId) {
        return builtInPluginStateStore.isEnabled(pluginId);
    }

    @Override
    public void setBuiltInPluginEnabled(String pluginId, boolean enabled) {
        ToolPlugin target = findBuiltInPlugin(pluginId);
        if (target == null) {
            showToast("内置插件不存在");
            return;
        }
        if (enabled) {
            List<String> missingDependencies = findMissingDependencyTitles(target.dependencies());
            if (!missingDependencies.isEmpty()) {
                showToast("无法启用，依赖未满足：" + joinNames(missingDependencies));
                return;
            }
        } else {
            List<String> dependents = findDependentPluginTitles(pluginId);
            if (!dependents.isEmpty()) {
                showToast("无法停用，仍被依赖：" + joinNames(dependents));
                return;
            }
        }
        builtInPluginStateStore.setEnabled(pluginId, enabled);
        showToast(enabled ? "已启用插件" : "已停用插件");
        reloadPlugins(enabled ? pluginId : null);
    }

    private boolean isBuiltInPluginId(String pluginId) {
        if ("plugin_manager".equals(pluginId)) {
            return true;
        }
        for (ToolPlugin plugin : ToolRegistry.createBuiltInPlugins()) {
            if (plugin.id().equals(pluginId)) {
                return true;
            }
        }
        return ShizukuPlugin.ID.equals(pluginId);
    }

    private ToolPlugin findBuiltInPlugin(String pluginId) {
        for (ToolPlugin plugin : ToolRegistry.createBuiltInPlugins()) {
            if (plugin.id().equals(pluginId)) {
                return plugin;
            }
        }
        return null;
    }

    private List<String> findMissingDependencyTitles(Set<String> dependencies) {
        Map<String, String> activeVersions = activePluginVersions();
        List<String> missing = new ArrayList<>();
        for (String dependency : dependencies) {
            PluginDependency requirement = PluginDependency.parse(dependency);
            if (!requirement.isSatisfied(activeVersions)) {
                missing.add(pluginTitleOrId(requirement.id) + "（需要 " + requirement.label() + "）");
            }
        }
        return missing;
    }

    private Map<String, String> activePluginVersions() {
        LinkedHashMap<String, String> activeVersions = new LinkedHashMap<>();
        for (ToolPlugin plugin : plugins) {
            activeVersions.put(plugin.id(), plugin.version());
        }
        return activeVersions;
    }

    private String pluginTitleOrId(String pluginId) {
        ToolPlugin builtInPlugin = findBuiltInPlugin(pluginId);
        if (builtInPlugin != null) {
            return builtInPlugin.title();
        }
        ImportedPluginDescriptor descriptor = findImportedDescriptor(pluginId);
        return descriptor == null ? pluginId : descriptor.title;
    }

    private List<String> findDependentPluginTitles(String pluginId) {
        List<String> dependents = new ArrayList<>();
        for (ToolPlugin plugin : ToolRegistry.createBuiltInPlugins()) {
            if (!plugin.id().equals(pluginId)
                    && builtInPluginStateStore.isEnabled(plugin.id())
                    && dependsOn(plugin.dependencies(), pluginId)) {
                dependents.add(plugin.title());
            }
        }
        for (ImportedPluginDescriptor descriptor : externalPluginStore.load()) {
            if (externalPluginStore.isEnabled(descriptor.id) && dependsOn(descriptor.dependencies, pluginId)) {
                dependents.add(descriptor.title);
            }
        }
        return dependents;
    }

    private boolean dependsOn(Set<String> dependencies, String pluginId) {
        for (String dependency : dependencies) {
            if (PluginDependency.parse(dependency).id.equals(pluginId)) {
                return true;
            }
        }
        return false;
    }

    private String joinNames(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append("、");
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private ImportedPluginDescriptor findImportedDescriptor(String pluginId) {
        if (pluginId == null) {
            return null;
        }
        for (ImportedPluginDescriptor descriptor : externalPluginStore.load()) {
            if (descriptor.id.equals(pluginId)) {
                return descriptor;
            }
        }
        return null;
    }

    private PluginImport readPluginPackage(Uri uri) throws IOException, JSONException {
        byte[] bytes = readBytes(uri);
        if (!isZip(bytes)) {
            throw new IOException("只支持包含 manifest.json 和 plugin.apk 的完整 .atsplugin 插件包");
        }
        ZipPluginPackage zipPackage = readPackageFromZip(bytes);
        ImportedPluginDescriptor descriptor = ImportedPluginDescriptor.fromJson(zipPackage.manifestJson);
        if (descriptor.entryClass.isEmpty()) {
            throw new IOException("插件包清单缺少 plugin.entryClass");
        }
        if (zipPackage.codeBytes == null || zipPackage.codeBytes.length == 0) {
            throw new IOException("插件包缺少 plugin.apk");
        }
        return new PluginImport(descriptor, zipPackage.codeBytes);
    }

    private byte[] readBytes(Uri uri) throws IOException {
        InputStream inputStream = getContentResolver().openInputStream(uri);
        if (inputStream == null) {
            throw new IOException("无法读取插件文件");
        }
        try (InputStream stream = inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private boolean isZip(byte[] bytes) {
        return bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    private ZipPluginPackage readPackageFromZip(byte[] bytes) throws IOException {
        String manifestJson = null;
        byte[] codeBytes = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && "manifest.json".equals(entry.getName())) {
                    manifestJson = new String(readZipEntry(zip), StandardCharsets.UTF_8);
                } else if (!entry.isDirectory() && isPluginCodeEntry(entry.getName())) {
                    codeBytes = readZipEntry(zip);
                }
                zip.closeEntry();
            }
        }
        if (manifestJson == null) {
            throw new IOException("插件包缺少 manifest.json");
        }
        return new ZipPluginPackage(manifestJson, codeBytes);
    }

    private byte[] readZipEntry(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = zip.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private boolean isPluginCodeEntry(String name) {
        return "plugin.apk".equals(name) || name.endsWith("/plugin.apk");
    }

    private void writePluginPackage(OutputStream outputStream, ImportedPluginDescriptor descriptor) throws IOException, JSONException {
        try (ZipOutputStream zip = new ZipOutputStream(outputStream)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(descriptor.toJson().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            if (!descriptor.codePath.isEmpty()) {
                File codeFile = new File(descriptor.codePath);
                if (codeFile.exists()) {
                    zip.putNextEntry(new ZipEntry("plugin.apk"));
                    try (FileInputStream input = new FileInputStream(codeFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            zip.write(buffer, 0, read);
                        }
                    }
                    zip.closeEntry();
                }
            }
        }
    }

    private void unbindShellService() {
        if (shellServiceArgs == null) {
            return;
        }
        try {
            Shizuku.unbindUserService(shellServiceArgs, shellConnection, true);
        } catch (Throwable ignored) {
        } finally {
            shellService = null;
            shellServiceBinding = false;
            shellServiceArgs = null;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class WidgetRegistration {
        final String pluginTitle;
        final String key;
        final HomeWidget widget;

        WidgetRegistration(String pluginTitle, String key, HomeWidget widget) {
            this.pluginTitle = pluginTitle;
            this.key = key;
            this.widget = widget;
        }
    }

    private static final class PluginImport {
        final ImportedPluginDescriptor descriptor;
        final byte[] codeBytes;

        PluginImport(ImportedPluginDescriptor descriptor, byte[] codeBytes) {
            this.descriptor = descriptor;
            this.codeBytes = codeBytes;
        }
    }

    private static final class ZipPluginPackage {
        final String manifestJson;
        final byte[] codeBytes;

        ZipPluginPackage(String manifestJson, byte[] codeBytes) {
            this.manifestJson = manifestJson;
            this.codeBytes = codeBytes;
        }
    }
}
