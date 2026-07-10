package com.example.shizukuaccessibilitygrant.host;

import com.example.shizukuaccessibilitygrant.BuildConfig;
import com.example.shizukuaccessibilitygrant.IShellService;
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

import com.example.shizukuaccessibilitygrant.plugin.store.BuiltInPluginStateStore;
import com.example.shizukuaccessibilitygrant.plugin.store.ExternalPluginStore;
import com.example.shizukuaccessibilitygrant.plugin.runtime.ExternalToolFactory;
import com.example.shizukuaccessibilitygrant.plugin.api.HomeWidget;
import com.example.shizukuaccessibilitygrant.plugin.api.PluginDependency;
import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor;
import com.example.shizukuaccessibilitygrant.plugin.api.PluginHost;
import com.example.shizukuaccessibilitygrant.host.management.PluginManagerPlugin;
import com.example.shizukuaccessibilitygrant.plugins.builtin.shizuku.ShizukuPlugin;
import com.example.shizukuaccessibilitygrant.plugin.api.ToolPlugin;
import com.example.shizukuaccessibilitygrant.plugin.runtime.ToolRegistry;
import com.example.shizukuaccessibilitygrant.ui.UiKit;

import org.json.JSONException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

public class MainActivity extends Activity implements PluginHost {
    private static final int REQUEST_SHIZUKU = 3001;
    private static final int REQUEST_IMPORT_PLUGIN = 4001;
    private static final int REQUEST_EXPORT_PLUGIN = 4002;

    private static final int SECTION_DASHBOARD = 0;
    private static final int SECTION_PLUGINS = 1;
    private static final int SECTION_MANAGER = 2;

    private static final String PREFS_NAME = "main_ui";
    private static final String PREF_HIDDEN_WIDGETS = "hidden_widgets";

    private final List<ToolPlugin> plugins = new ArrayList<>();
    private final List<Button> bottomButtons = new ArrayList<>();
    private final PluginManagerPlugin pluginManagerPlugin = new PluginManagerPlugin();

    private LinearLayout contentRoot;
    private LinearLayout bottomBar;
    private ToolPlugin selectedPlugin;
    private ExternalPluginStore externalPluginStore;
    private BuiltInPluginStateStore builtInPluginStateStore;
    private SharedPreferences uiPreferences;
    private String pendingExportPluginId;
    private int currentSection = SECTION_DASHBOARD;

    private IShellService shellService;
    private Shizuku.UserServiceArgs shellServiceArgs;
    private boolean shellServiceBinding;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> runOnUiThread(this::notifyHostStateChanged);
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> runOnUiThread(() -> {
        shellService = null;
        shellServiceBinding = false;
        notifyHostStateChanged();
    });
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        if (requestCode == REQUEST_SHIZUKU) {
            runOnUiThread(this::notifyHostStateChanged);
        }
    };
    private final ServiceConnection shellConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            shellService = IShellService.Stub.asInterface(service);
            shellServiceBinding = false;
            runOnUiThread(MainActivity.this::notifyHostStateChanged);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            shellService = null;
            shellServiceBinding = false;
            runOnUiThread(MainActivity.this::notifyHostStateChanged);
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

        Shizuku.addBinderReceivedListener(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        showDashboard();
        notifyHostStateChanged();
    }

    @Override
    protected void onDestroy() {
        for (ToolPlugin plugin : plugins) {
            plugin.onDestroy();
        }
        pluginManagerPlugin.onDestroy();
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        unbindShellService();
        super.onDestroy();
    }

    private View createContentView() {
        int horizontal = dp(16);
        int topPadding = dp(10);
        int bottomPadding = dp(10);
        int background = UiKit.COLOR_BACKGROUND;

        Window window = getWindow();
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setPadding(horizontal, topPadding, horizontal, bottomPadding);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(horizontal, topPadding + insets.getSystemWindowInsetTop(), horizontal, bottomPadding + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        contentRoot = new LinearLayout(this);
        contentRoot.setOrientation(LinearLayout.VERTICAL);
        root.addView(contentRoot, new LinearLayout.LayoutParams(-1, 0, 1));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bottomBar.setBackground(UiKit.roundedStroke(0xF7FFFFFF, UiKit.COLOR_BORDER, 28, this));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(70));
        barParams.topMargin = dp(10);
        root.addView(bottomBar, barParams);

        addBottomButton("主页", SECTION_DASHBOARD);
        addBottomButton("插件", SECTION_PLUGINS);
        addBottomButton("管理", SECTION_MANAGER);
        return root;
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
        contentRoot.removeAllViews();
        contentRoot.addView(createDashboardView(), new LinearLayout.LayoutParams(-1, -1));
        updateBottomButtons();
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
        contentRoot.removeAllViews();
        contentRoot.addView(createPluginListView(), new LinearLayout.LayoutParams(-1, -1));
        updateBottomButtons();
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
                + " · 权限 " + plugin.requestedPermissions().size()
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
        currentSection = SECTION_PLUGINS;
        selectedPlugin = plugin;
        contentRoot.removeAllViews();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(8), 0, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        Button back = new Button(this);
        back.setText("返回");
        UiKit.styleSecondaryButton(back);
        back.setOnClickListener(v -> showPluginList());
        header.addView(back, new LinearLayout.LayoutParams(dp(88), dp(44)));

        TextView title = new TextView(this);
        title.setText(plugin.title());
        UiKit.styleTitle(title, 22);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.leftMargin = dp(12);
        header.addView(title, titleParams);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        View pluginView = plugin.createView(this, this);
        detachFromParent(pluginView);
        LinearLayout.LayoutParams pluginParams = new LinearLayout.LayoutParams(-1, 0, 1);
        pluginParams.topMargin = dp(14);
        root.addView(pluginView, pluginParams);
        contentRoot.addView(root, new LinearLayout.LayoutParams(-1, -1));

        updateBottomButtons();
        plugin.onSelected();
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
        contentRoot.removeAllViews();
        contentRoot.addView(pluginManagerPlugin.createView(this, this), new LinearLayout.LayoutParams(-1, -1));
        pluginManagerPlugin.onSelected();
        updateBottomButtons();
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
                    plugins.add(plugin);
                    activeVersions.put(plugin.id(), plugin.version());
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
        if (currentSection == SECTION_DASHBOARD && contentRoot != null) {
            showDashboard();
        }
        if (selectedPlugin != null) {
            selectedPlugin.onHostStateChanged();
        }
        if (currentSection == SECTION_MANAGER) {
            pluginManagerPlugin.onHostStateChanged();
        }
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
            if (pluginImport.codeBytes != null) {
                externalPluginStore.savePluginCode(descriptor.id, pluginImport.codeBytes);
            }
            externalPluginStore.save(descriptor);
            showToast("已导入插件，默认停用：" + descriptor.title);
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
    public void setImportedPluginPermission(String pluginId, String permission, boolean granted) {
        try {
            externalPluginStore.setPermission(pluginId, permission, granted);
            showToast(granted ? "已授权：" + permission : "已撤销：" + permission);
            reloadPlugins(selectedPlugin == null ? pluginId : selectedPlugin.id());
        } catch (JSONException e) {
            showToast("权限更新失败：" + e.getMessage());
        }
    }

    @Override
    public boolean hasImportedPluginPermission(String pluginId, String permission) {
        return externalPluginStore.hasPermission(pluginId, permission);
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
        String manifestJson;
        byte[] codeBytes = null;
        if (isZip(bytes)) {
            ZipPluginPackage zipPackage = readPackageFromZip(bytes);
            manifestJson = zipPackage.manifestJson;
            codeBytes = zipPackage.codeBytes;
        } else {
            manifestJson = new String(bytes, StandardCharsets.UTF_8);
        }
        return new PluginImport(ImportedPluginDescriptor.fromJson(manifestJson), codeBytes);
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
