package com.androidtoolsuite.app.host;

import com.androidtoolsuite.app.BuildConfig;
import com.androidtoolsuite.app.IShellService;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.FileProvider;
import androidx.core.splashscreen.SplashScreen;

import com.androidtoolsuite.app.plugin.store.BuiltInPluginStateStore;
import com.androidtoolsuite.app.plugin.store.ExternalPluginStore;
import com.androidtoolsuite.app.migration.HostMigrationArchive;
import com.androidtoolsuite.app.migration.MigrationTransaction;
import com.androidtoolsuite.app.plugin.runtime.ExternalToolFactory;
import com.androidtoolsuite.app.plugin.api.HomeWidget;
import com.androidtoolsuite.app.plugin.api.HomeWidgetSize;
import com.androidtoolsuite.app.plugin.api.PluginDependency;
import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor;
import com.androidtoolsuite.app.plugin.api.PluginHost;
import com.androidtoolsuite.app.plugins.builtin.shizuku.ShizukuPlugin;
import com.androidtoolsuite.app.plugin.api.ToolPlugin;
import com.androidtoolsuite.app.plugin.runtime.ToolRegistry;
import com.androidtoolsuite.app.ui.SuiteColorPreference;
import com.androidtoolsuite.app.ui.SuiteThemePreference;
import com.androidtoolsuite.app.ui.SuiteThemePreferences;
import com.androidtoolsuite.app.update.UpdateCatalog;
import com.androidtoolsuite.app.update.UpdateClient;
import com.androidtoolsuite.app.update.AppUpdatePolicy;
import com.androidtoolsuite.app.update.PluginUpdatePolicy;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final int REQUEST_IMPORT_MIGRATION = 4003;
    private static final int REQUEST_EXPORT_MIGRATION = 4004;

    private static final int SECTION_DASHBOARD = 0;
    private static final int SECTION_PLUGINS = 1;
    private static final int SECTION_MANAGER = 2;
    private static final int SECTION_STORE = 3;
    private static final int SECTION_SETTINGS = 4;
    private static final int SECTION_ABOUT = 5;

    private static final String PREFS_NAME = "main_ui";
    private static final String PREF_HIDDEN_WIDGETS = "hidden_widgets";
    private static final String PREF_HIDDEN_TOOLS = "hidden_tools";
    private static final String PREF_TOOL_ORDER = "tool_order";
    private static final String PREF_WIDGET_ORDER = "widget_order";
    private static final String PREF_FULL_WIDTH_WIDGETS = "full_width_widgets";
    private static final String PREF_WIDGET_SIZES = "widget_sizes";
    private static final String PREF_PLUGIN_REPOSITORY_CHANNEL = "plugin_repository_channel";
    private static final String PREF_THEME = "theme_preference";
    private static final String PREF_COLOR = "color_preference";
    private static final String PREF_AUTO_CHECK_UPDATES = "auto_check_updates";
    private static final String PREF_LAST_UPDATE_CHECK = "last_update_check";
    private static final String PREF_DISMISSED_UPDATE_VERSIONS = "dismissed_update_versions";
    private static final String PREF_STORE_RISK_ACKNOWLEDGED = "store_risk_acknowledged";
    private static final String PREF_UPDATE_CHECK_EXCLUDED = "update_check_excluded_plugins";

    private final List<ToolPlugin> plugins = new ArrayList<>();
    private final Map<String, ImportedPluginDescriptor> importedDescriptorCache = new LinkedHashMap<>();
    private final Set<String> optionalBuiltInPluginIds = new HashSet<>();
    private ToolPlugin selectedPlugin;
    private ExternalPluginStore externalPluginStore;
    private BuiltInPluginStateStore builtInPluginStateStore;
    private UpdateClient updateClient;
    private UpdateCatalog updateCatalog;
    private String updateStatus = "尚未检查更新";
    private UpdateCheckState updateCheckState = UpdateCheckState.IDLE;
    private String updateError = "";
    private String snackbarMessage;
    private String snackbarAction;
    private boolean updatePromptVisible;
    private ComposeDialogState composeDialog;
    private final Set<String> updateOperations = new HashSet<>();
    private SharedPreferences uiPreferences;
    private String pendingExportPluginId;
    private int currentSection = SECTION_DASHBOARD;
    private int pluginReturnSection = SECTION_PLUGINS;
    private final HostUiState composeState = new HostUiState();
    private final Map<String, int[]> composeScrollPositions = new LinkedHashMap<>();

    public enum UpdateCheckState {
        IDLE,
        CHECKING,
        UP_TO_DATE,
        AVAILABLE,
        FAILED
    }

    public static final class ComposeDialogState {
        public final String title;
        public final String message;
        public final String dismissLabel;
        public final String confirmLabel;
        private final Runnable onConfirm;

        private ComposeDialogState(
                String title,
                String message,
                String dismissLabel,
                String confirmLabel,
                Runnable onConfirm
        ) {
            this.title = title;
            this.message = message;
            this.dismissLabel = dismissLabel;
            this.confirmLabel = confirmLabel;
            this.onConfirm = onConfirm;
        }
    }

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
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // 从 ACTION_DOWN 就请求触摸升帧；Pager 进入滚动后还会对 ComposeView 继续投 HIGH 票。
            getWindow().setFrameRateBoostOnTouchEnabled(true);
        }
        splashScreen.setOnExitAnimationListener(provider -> {
            View splashView = provider.getView();
            splashView.animate()
                    .alpha(0f)
                    .scaleX(1.04f)
                    .scaleY(1.04f)
                    .setDuration(260L)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(provider::remove)
                    .start();
        });
        externalPluginStore = new ExternalPluginStore(this);
        builtInPluginStateStore = new BuiltInPluginStateStore(this);
        updateClient = new UpdateClient(this);
        uiPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        syncSuiteThemePreferences();
        // 用户看到主界面前把全部插件准备好，避免把类加载抖动摊到打开后的几秒和首次切页。
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
    protected void onStart() {
        super.onStart();
        if (autoCheckUpdatesForUi()) {
            checkForUpdates(false, false, false);
        }
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
        updateClient.close();
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
        if (selectedPlugin != null) {
            closePluginForUi();
            return true;
        }
        // 关于是设置的子页，返回回到设置；设置本身是底栏一级分区，返回直接回主页。
        if (currentSection == SECTION_ABOUT) {
            showSettingsForUi();
            return true;
        }
        if (currentSection != SECTION_DASHBOARD) {
            showDashboard();
            return true;
        }
        return false;
    }

    public boolean canHandleBackForUi() {
        return selectedPlugin != null || currentSection != SECTION_DASHBOARD;
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
        } else if ("store".equals(destination)) {
            showPluginRepositoryForUi();
        } else if ("settings".equals(destination)) {
            showSettingsForUi();
        } else if ("about".equals(destination)) {
            showAboutForUi();
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

    private void showDashboard() {
        currentSection = SECTION_DASHBOARD;
        selectedPlugin = null;
        invalidateComposeUi();
    }

    private void showPluginList() {
        currentSection = SECTION_PLUGINS;
        selectedPlugin = null;
        invalidateComposeUi();
    }

    private void openPlugin(ToolPlugin plugin) {
        pluginReturnSection = currentSection;
        selectedPlugin = plugin;
        plugin.onSelected();
        invalidateComposeUi();
    }

    private void showPluginManager() {
        currentSection = SECTION_MANAGER;
        selectedPlugin = null;
        invalidateComposeUi();
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
        importedDescriptorCache.clear();
        optionalBuiltInPluginIds.clear();
        loadBuiltInPlugins();
        plugins.addAll(createExternalPlugins(plugins));
    }

    private void loadBuiltInPlugins() {
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
            optionalBuiltInPluginIds.add(plugin.id());
            if (builtInPluginStateStore.isEnabled(plugin.id()) && areDependenciesSatisfied(plugin.dependencies(), activeVersions)) {
                plugins.add(plugin);
                activeVersions.put(plugin.id(), plugin.version());
            } else {
                plugin.onDestroy();
            }
        }
    }

    private List<ToolPlugin> createExternalPlugins(List<ToolPlugin> activePlugins) {
        List<ToolPlugin> result = new ArrayList<>();
        LinkedHashMap<String, String> activeVersions = new LinkedHashMap<>();
        for (ToolPlugin plugin : activePlugins) {
            activeVersions.put(plugin.id(), plugin.version());
        }
        List<ImportedPluginDescriptor> pendingExternalPlugins = new ArrayList<>(externalPluginStore.load());
        for (ImportedPluginDescriptor descriptor : pendingExternalPlugins) {
            importedDescriptorCache.put(descriptor.id, descriptor);
        }
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
                        result.add(plugin);
                        activeVersions.put(plugin.id(), plugin.version());
                    }
                    pendingExternalPlugins.remove(i);
                    loadedPlugin = true;
                }
            }
        } while (loadedPlugin);
        return result;
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
        if (selectedPlugin != null) {
            openPlugin(selectedPlugin);
            return;
        }
        // 装卸插件不该顺带把用户挪到别的分区：在仓库里删一个插件，之后还应该留在仓库。
        // 只有原来打开的就是插件详情、而那个插件没了，才退回工具页。
        if (currentSection == SECTION_PLUGINS) {
            showPluginList();
        } else {
            navigateForUi(currentSection);
        }
    }

    /** 重载插件集合，但不把刚安装的插件当成导航目标。 */
    private void reloadPluginsKeepingCurrentPage() {
        reloadPlugins(selectedPlugin == null ? null : selectedPlugin.id());
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

    public ToolPlugin findToolForUi(String pluginId) {
        return findPlugin(pluginId);
    }

    public boolean isToolVisibleForUi(ToolPlugin plugin) {
        return !uiPreferences.getStringSet(PREF_HIDDEN_TOOLS, new LinkedHashSet<>()).contains(plugin.id());
    }

    /**
     * 这个插件能不能被停用。
     *
     * 必需内置插件（宿主自身能力）不行；可选内置插件和外部插件都行。工具页的长按菜单据此决定
     * 要不要显示「停用插件」——把一个停不掉的开关摆出来只会让人以为功能坏了。
     */
    public boolean canDisablePluginForUi(ToolPlugin plugin) {
        return importedDescriptorCache.containsKey(plugin.id())
                || optionalBuiltInPluginIds.contains(plugin.id());
    }

    /** 停用插件，自动分派到内置或外部两条通道。依赖校验与提示由被调用方负责。 */
    public void disablePluginForUi(ToolPlugin plugin) {
        if (findImportedDescriptor(plugin.id()) != null) {
            setImportedPluginEnabled(plugin.id(), false);
        } else if (canDisablePluginForUi(plugin)) {
            setBuiltInPluginEnabled(plugin.id(), false);
        }
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

    public void restoreToolOrderForUi(List<String> ids) {
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

    public void restoreWidgetOrderForUi(List<String> keys) {
        saveOrder(PREF_WIDGET_ORDER, keys);
        invalidateComposeUi();
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
        else if (section == SECTION_MANAGER) showPluginManager();
        else if (section == SECTION_STORE) showPluginRepositoryForUi();
        else if (section == SECTION_SETTINGS) showSettingsForUi();
        else if (section == SECTION_ABOUT) showAboutForUi();
    }

    /** Pager 已经完成视觉切换时只同步返回栈语义，不再让五个缓存页面全部重组。 */
    public void setMainSectionFromPagerForUi(int section) {
        if (section < SECTION_DASHBOARD || section > SECTION_SETTINGS) return;
        currentSection = section;
        selectedPlugin = null;
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
        selectedPlugin = null;
        currentSection = pluginReturnSection;
        invalidateComposeUi();
    }

    public List<ImportedPluginDescriptor> importedDescriptorsForUi() {
        return new ArrayList<>(importedDescriptorCache.values());
    }

    public void showPluginRepositoryForUi() {
        currentSection = SECTION_STORE;
        selectedPlugin = null;
        invalidateComposeUi();
    }

    public void showSettingsForUi() {
        currentSection = SECTION_SETTINGS;
        selectedPlugin = null;
        invalidateComposeUi();
    }

    public void showAboutForUi() {
        currentSection = SECTION_ABOUT;
        selectedPlugin = null;
        invalidateComposeUi();
    }

    public UpdateCheckState updateCheckStateForUi() {
        return updateCheckState;
    }

    public String updateErrorForUi() {
        return updateError;
    }

    public int availableUpdateCountForUi() {
        if (updateCatalog == null) return 0;
        int available = appUpdateForUi() == null ? 0 : 1;
        for (UpdateCatalog.PluginRelease release : updateCatalog.plugins) {
            if (isRepositoryPluginUpdateAvailableForUi(release)) available++;
        }
        return available;
    }

    public List<UpdateCatalog.PluginRelease> availablePluginUpdatesForUi() {
        if (updateCatalog == null) return Collections.emptyList();
        List<UpdateCatalog.PluginRelease> result = new ArrayList<>();
        for (UpdateCatalog.PluginRelease release : updateCatalog.plugins) {
            if (isRepositoryPluginUpdateAvailableForUi(release)) result.add(release);
        }
        return result;
    }

    public boolean isUpdatePromptVisibleForUi() {
        return updatePromptVisible && availableUpdateCountForUi() > 0;
    }

    public void closeUpdatePromptForUi() {
        updatePromptVisible = false;
        invalidateComposeUi();
    }

    public void dismissCurrentUpdatesForUi() {
        String fingerprint = currentUpdateFingerprint();
        if (!fingerprint.isEmpty()) {
            uiPreferences.edit().putString(PREF_DISMISSED_UPDATE_VERSIONS, fingerprint).apply();
        }
        closeUpdatePromptForUi();
    }

    public void installAllUpdatesForUi() {
        updatePromptVisible = false;
        if (appUpdateForUi() != null) installAppUpdateForUi();
        installAllPluginUpdatesForUi();
        invalidateComposeUi();
    }

    public String consumeSnackbarMessageForUi() {
        String value = snackbarMessage;
        snackbarMessage = null;
        return value;
    }

    public String consumeSnackbarActionForUi() {
        String value = snackbarAction;
        snackbarAction = null;
        return value;
    }

    public void retrySnackbarActionForUi() {
        checkForUpdates(true, true, false);
    }

    public ComposeDialogState composeDialogForUi() {
        return composeDialog;
    }

    public void dismissComposeDialogForUi() {
        composeDialog = null;
        invalidateComposeUi();
    }

    public void confirmComposeDialogForUi() {
        ComposeDialogState dialog = composeDialog;
        composeDialog = null;
        invalidateComposeUi();
        if (dialog != null && dialog.onConfirm != null) dialog.onConfirm.run();
    }

    private void showComposeDialog(
            String title,
            String message,
            String dismissLabel,
            String confirmLabel,
            Runnable onConfirm
    ) {
        composeDialog = new ComposeDialogState(title, message, dismissLabel, confirmLabel, onConfirm);
        invalidateComposeUi();
    }

    public String themePreferenceForUi() {
        return uiPreferences.getString(PREF_THEME, "system");
    }

    public void setThemePreferenceForUi(String value) {
        String selected = "light".equals(value) || "dark".equals(value) ? value : "system";
        uiPreferences.edit().putString(PREF_THEME, selected).apply();
        syncSuiteThemePreferences();
        invalidateComposeUi();
    }

    public String colorPreferenceForUi() {
        return "dynamic".equals(uiPreferences.getString(PREF_COLOR, "brand")) ? "dynamic" : "brand";
    }

    public void setColorPreferenceForUi(String value) {
        uiPreferences.edit().putString(PREF_COLOR, "dynamic".equals(value) ? "dynamic" : "brand").apply();
        syncSuiteThemePreferences();
        invalidateComposeUi();
    }

    private void syncSuiteThemePreferences() {
        SuiteThemePreference theme;
        switch (themePreferenceForUi()) {
            case "light": theme = SuiteThemePreference.LIGHT; break;
            case "dark": theme = SuiteThemePreference.DARK; break;
            default: theme = SuiteThemePreference.SYSTEM; break;
        }
        SuiteColorPreference color = "dynamic".equals(colorPreferenceForUi())
                ? SuiteColorPreference.DYNAMIC
                : SuiteColorPreference.BRAND;
        SuiteThemePreferences.INSTANCE.update(theme, color);
    }

    public boolean autoCheckUpdatesForUi() {
        return uiPreferences.getBoolean(PREF_AUTO_CHECK_UPDATES, true);
    }

    public void setAutoCheckUpdatesForUi(boolean enabled) {
        uiPreferences.edit().putBoolean(PREF_AUTO_CHECK_UPDATES, enabled).apply();
        invalidateComposeUi();
    }

    /**
     * 单个插件是否参与更新检查。
     *
     * 记的是「排除集合」而不是「包含集合」：新装的插件默认跟着检查，只有用户显式关掉的才落盘。
     * 关掉之后这个插件不再计入更新角标、更新弹窗和「全部更新」，但仓库里仍然可以手动选版本安装。
     */
    public boolean isPluginUpdateCheckEnabledForUi(String pluginId) {
        return !uiPreferences.getStringSet(PREF_UPDATE_CHECK_EXCLUDED, new LinkedHashSet<>()).contains(pluginId);
    }

    public void setPluginUpdateCheckEnabledForUi(String pluginId, boolean enabled) {
        Set<String> excluded = new LinkedHashSet<>(
                uiPreferences.getStringSet(PREF_UPDATE_CHECK_EXCLUDED, new LinkedHashSet<>())
        );
        if (enabled) excluded.remove(pluginId); else excluded.add(pluginId);
        uiPreferences.edit().putStringSet(PREF_UPDATE_CHECK_EXCLUDED, excluded).apply();
        invalidateComposeUi();
    }

    public long lastUpdateCheckForUi() {
        return uiPreferences.getLong(PREF_LAST_UPDATE_CHECK, 0L);
    }

    public void checkUpdatesManuallyForUi() {
        checkForUpdates(true, true, false);
    }

    public String appVersionNameForUi() {
        return BuildConfig.VERSION_NAME;
    }

    public int appVersionCodeForUi() {
        return BuildConfig.VERSION_CODE;
    }

    public String pluginSdkVersionForUi() {
        return BuildConfig.PLUGIN_SDK_VERSION;
    }

    public String buildTypeForUi() {
        return BuildConfig.BUILD_TYPE;
    }

    public String buildCommitForUi() {
        return BuildConfig.BUILD_COMMIT_SHA;
    }

    public boolean isDebugBuildForUi() {
        return BuildConfig.DEBUG;
    }

    public String requiredAppVersionLabelForUi(int minVersionCode) {
        if (minVersionCode <= BuildConfig.VERSION_CODE) return BuildConfig.VERSION_NAME;
        if (minVersionCode == 15) return "1.5.0";
        return "更新版本";
    }

    public boolean shouldShowStoreRiskForUi() {
        return !uiPreferences.getBoolean(PREF_STORE_RISK_ACKNOWLEDGED, false);
    }

    public void acknowledgeStoreRiskForUi() {
        uiPreferences.edit().putBoolean(PREF_STORE_RISK_ACKNOWLEDGED, true).apply();
    }

    public void requestDeletePluginForUi(String pluginId) {
        ImportedPluginDescriptor descriptor = findImportedDescriptor(pluginId);
        if (descriptor == null) return;
        showComposeDialog(
                "删除 " + descriptor.title + "？",
                "插件包会从应用中移除。插件自行保存的业务数据不会自动清理。",
                "取消",
                "删除",
                () -> deleteImportedPlugin(pluginId)
        );
    }

    public void openProjectForUi() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/android-tool-suite")));
    }

    public void showOpenSourceLicensesForUi() {
        showComposeDialog(
                "开源许可",
                "Android Tool Suite 及其组件使用的开源许可随各组件源码发布。可从项目地址查看完整版权与许可文件。",
                "",
                "知道了",
                null
        );
    }

    public List<UpdateCatalog.PluginRelease> repositoryPluginsForUi() {
        return updateCatalog == null ? Collections.emptyList() : updateCatalog.plugins;
    }

    public List<UpdateCatalog.PluginRelease> repositoryPluginVersionsForUi(String pluginId) {
        return updateCatalog == null
                ? Collections.emptyList()
                : updateCatalog.versionsForPlugin(pluginId);
    }

    public String pluginRepositoryChannelForUi() {
        String fallback = BuildConfig.DEBUG
                ? UpdateCatalog.CHANNEL_DEBUG
                : UpdateCatalog.CHANNEL_RELEASE;
        String channel = uiPreferences.getString(PREF_PLUGIN_REPOSITORY_CHANNEL, fallback);
        return UpdateCatalog.CHANNEL_DEBUG.equals(channel)
                ? UpdateCatalog.CHANNEL_DEBUG
                : UpdateCatalog.CHANNEL_RELEASE;
    }

    public String pluginRepositoryChannelLabelForUi() {
        return UpdateCatalog.CHANNEL_DEBUG.equals(pluginRepositoryChannelForUi())
                ? "调试仓库"
                : "正式仓库";
    }

    public boolean isDebugPluginRepositoryForUi() {
        return UpdateCatalog.CHANNEL_DEBUG.equals(pluginRepositoryChannelForUi());
    }

    public void selectPluginRepositoryChannelForUi(String channel) {
        String selected = UpdateCatalog.CHANNEL_DEBUG.equals(channel)
                ? UpdateCatalog.CHANNEL_DEBUG
                : UpdateCatalog.CHANNEL_RELEASE;
        if (selected.equals(pluginRepositoryChannelForUi())) {
            return;
        }
        if (updateOperations.contains("__check__")) {
            showToast("请等待当前仓库刷新完成");
            return;
        }
        uiPreferences.edit().putString(PREF_PLUGIN_REPOSITORY_CHANNEL, selected).apply();
        updateCatalog = null;
        updateStatus = "正在切换到" + pluginRepositoryChannelLabelForUi() + "…";
        updateCheckState = UpdateCheckState.CHECKING;
        invalidateComposeUi();
        checkForUpdates(true, false, true);
    }

    public UpdateCatalog.AppRelease appUpdateForUi() {
        if (updateCatalog == null || updateCatalog.app == null) {
            return null;
        }
        UpdateCatalog.AppRelease release = updateCatalog.app;
        return AppUpdatePolicy.isUpdateAvailable(
                release,
                getPackageName(),
                BuildConfig.VERSION_CODE,
                BuildConfig.DEBUG,
                BuildConfig.BUILD_COMMIT_SHA
        )
                ? release
                : null;
    }

    public boolean isUpdateOperationRunningForUi(String id) {
        return updateOperations.contains(id);
    }

    public float downloadProgressForUi(String id) {
        return composeState.downloadProgress(id);
    }

    private void updateDownloadProgress(String id, long downloadedBytes, long totalBytes) {
        composeState.updateDownloadProgress(id, downloadedBytes, totalBytes);
    }

    private void clearDownloadProgress(String id) {
        composeState.clearDownloadProgress(id);
    }

    public boolean isRepositoryPluginUpdateAvailableForUi(UpdateCatalog.PluginRelease release) {
        if (!isPluginUpdateCheckEnabledForUi(release.id)) {
            return false;
        }
        ImportedPluginDescriptor installed = findImportedDescriptor(release.id);
        return PluginUpdatePolicy.isUpdateAvailable(
                release,
                installed,
                externalPluginStore.isRepositoryVerified(release.id),
                externalPluginStore.repositoryChannel(release.id),
                externalPluginStore.verifiedSha256(release.id)
        ) && isRepositoryPluginVersionSelectableForUi(release);
    }

    public boolean isRepositoryPluginCompatibleForUi(UpdateCatalog.PluginRelease release) {
        return release.minHostVersionCode <= BuildConfig.VERSION_CODE;
    }

    public boolean isRepositoryPluginVersionInstalledForUi(UpdateCatalog.PluginRelease release) {
        ImportedPluginDescriptor installed = findImportedDescriptor(release.id);
        if (installed == null || !externalPluginStore.isRepositoryVerified(release.id)) {
            return false;
        }
        return release.sha256.equalsIgnoreCase(externalPluginStore.verifiedSha256(release.id));
    }

    public String repositoryPluginTransitionLabelForUi(UpdateCatalog.PluginRelease release) {
        PluginUpdatePolicy.Transition transition = assessPluginTransition(release);
        int currentDataFormat = currentPluginDataFormatVersion(release.id);
        switch (transition) {
            case INSTALL:
                return release.hasDataCompatibilityDeclaration()
                        ? "首次安装 · 数据格式 v" + release.dataFormatVersion
                        : "首次安装 · 旧版数据格式 v0";
            case REINSTALL_COMPATIBLE:
                return "检测到保留数据 · 目标版本可读取数据格式 v" + currentDataFormat;
            case CURRENT:
                return "当前已安装此构建";
            case UPGRADE:
                return "升级到所选版本";
            case REPLACE:
                return "切换到所选构建";
            case DOWNGRADE_COMPATIBLE:
                return "可降级 · 目标版本可读取当前数据格式 v" + currentDataFormat;
            case DATA_INCOMPATIBLE:
                return "已阻止安装 · 目标版本无法读取当前数据格式 v" + currentDataFormat;
            default:
                return "";
        }
    }

    public String repositoryPluginActionLabelForUi(UpdateCatalog.PluginRelease release) {
        if (updateOperations.contains(release.id)) {
            return "正在下载…";
        }
        if (release.minHostVersionCode > BuildConfig.VERSION_CODE) {
            return "需要更新应用";
        }
        switch (assessPluginTransition(release)) {
            case INSTALL:
                return "安装";
            case REINSTALL_COMPATIBLE:
                return "重新安装";
            case CURRENT:
                return "已安装";
            case UPGRADE:
                return "升级";
            case REPLACE:
                return "切换版本";
            case DOWNGRADE_COMPATIBLE:
                return "降级";
            case DATA_INCOMPATIBLE:
                return "无法安全降级";
            default:
                return "安装";
        }
    }

    public boolean isRepositoryPluginVersionSelectableForUi(UpdateCatalog.PluginRelease release) {
        PluginUpdatePolicy.Transition transition = assessPluginTransition(release);
        return release.minHostVersionCode <= BuildConfig.VERSION_CODE
                && transition != PluginUpdatePolicy.Transition.CURRENT
                && transition != PluginUpdatePolicy.Transition.DATA_INCOMPATIBLE;
    }

    public boolean isRepositoryVerifiedForUi(String pluginId) {
        return externalPluginStore.isRepositoryVerified(pluginId);
    }

    public void refreshUpdatesForUi() {
        checkForUpdates(true, true, true);
    }

    public void installAppUpdateForUi() {
        UpdateCatalog.AppRelease release = appUpdateForUi();
        if (release == null || updateOperations.contains("__app__")) {
            return;
        }
        updateOperations.add("__app__");
        updateStatus = "正在下载应用更新…";
        invalidateComposeUi();
        String fileName = BuildConfig.DEBUG
                ? "android-tool-suite-debug.apk"
                : "android-tool-suite.apk";
        updateClient.download(release, fileName, new UpdateClient.DownloadCallback() {
            @Override
            public void onProgress(long downloadedBytes, long totalBytes) {
                updateDownloadProgress("__app__", downloadedBytes, totalBytes);
            }

            @Override
            public void onSuccess(File file) {
                updateOperations.remove("__app__");
                clearDownloadProgress("__app__");
                try {
                    verifyAppUpdate(file, release);
                    launchAppInstaller(file);
                    updateStatus = "应用更新已下载，请在系统安装器中确认";
                } catch (IOException error) {
                    updateStatus = "应用更新失败：" + error.getMessage();
                    showToast(updateStatus);
                }
                invalidateComposeUi();
            }

            @Override
            public void onError(String message) {
                updateOperations.remove("__app__");
                clearDownloadProgress("__app__");
                updateStatus = "应用更新失败：" + message;
                showToast(updateStatus);
                invalidateComposeUi();
            }
        });
    }

    public void installRepositoryPluginForUi(String pluginId) {
        if (updateCatalog == null || updateOperations.contains(pluginId)) {
            return;
        }
        UpdateCatalog.PluginRelease release = updateCatalog.findPlugin(pluginId);
        if (release == null) {
            showToast("插件仓库中不存在该插件");
            return;
        }
        installRepositoryPluginVersionForUi(release);
    }

    public void installRepositoryPluginVersionForUi(UpdateCatalog.PluginRelease release) {
        if (release == null || updateCatalog == null || updateOperations.contains(release.id)) {
            return;
        }
        List<UpdateCatalog.PluginRelease> versions = updateCatalog.versionsForPlugin(release.id);
        if (!versions.contains(release)) {
            showToast("所选插件版本已失效，请刷新仓库");
            return;
        }
        if (release.minHostVersionCode > BuildConfig.VERSION_CODE) {
            showToast("请先将应用更新到兼容版本");
            return;
        }
        PluginUpdatePolicy.Transition transition = assessPluginTransition(release);
        if (transition == PluginUpdatePolicy.Transition.CURRENT) {
            showToast("当前已安装此版本");
            return;
        }
        if (transition == PluginUpdatePolicy.Transition.DATA_INCOMPATIBLE) {
            int currentDataFormat = currentPluginDataFormatVersion(release.id);
            showBlockedPluginTransitionDialog(
                    "数据格式不兼容",
                    "当前插件可能已经写入数据格式 v" + currentDataFormat
                            + "，而目标版本仅支持 v" + release.minReadableDataFormatVersion
                            + "–v" + release.maxReadableDataFormatVersion
                            + "。继续安装可能造成数据丢失，因此已阻止。"
            );
            return;
        }
        if (transition == PluginUpdatePolicy.Transition.DOWNGRADE_COMPATIBLE
                || transition == PluginUpdatePolicy.Transition.REINSTALL_COMPATIBLE) {
            boolean downgrade = transition == PluginUpdatePolicy.Transition.DOWNGRADE_COMPATIBLE;
            showComposeDialog(
                    downgrade ? "确认降级插件" : "确认使用保留数据",
                    (downgrade
                            ? "将 " + release.title + " 降级到 " + release.versionName + "。"
                            : "重新安装 " + release.title + " " + release.versionName + "。")
                            + "目标版本声明可读取当前数据格式，但安装不会回滚或清理插件已经写入的业务数据。建议先使用插件自身的导出功能备份。",
                    "取消",
                    downgrade ? "继续降级" : "继续安装",
                    () -> performRepositoryPluginInstall(release)
            );
            return;
        }
        performRepositoryPluginInstall(release);
    }

    private void performRepositoryPluginInstall(UpdateCatalog.PluginRelease release) {
        String pluginId = release.id;
        updateOperations.add(pluginId);
        updateStatus = "正在下载 " + release.title + "…";
        invalidateComposeUi();
        updateClient.download(release, pluginId + ".atsplugin", new UpdateClient.DownloadCallback() {
            @Override
            public void onProgress(long downloadedBytes, long totalBytes) {
                updateDownloadProgress(pluginId, downloadedBytes, totalBytes);
            }

            @Override
            public void onSuccess(File file) {
                boolean installStarted = false;
                try {
                    PluginImport pluginImport = readPluginPackage(readFileBytes(file));
                    validateRepositoryPlugin(pluginImport.descriptor, release);
                    preflightPlugin(pluginImport);
                    boolean wasEnabled = externalPluginStore.isEnabled(pluginId);
                    externalPluginStore.installPlugin(
                            pluginImport.descriptor,
                            pluginImport.codeBytes,
                            release.releaseUrl,
                            release.sha256,
                            release.channel,
                            true,
                            release.dataFormatVersion
                    );
                    installStarted = true;
                    reloadPluginsKeepingCurrentPage();
                    if (wasEnabled && findPlugin(pluginId) == null) {
                        externalPluginStore.rollbackInstall(pluginId);
                        installStarted = false;
                        reloadPluginsKeepingCurrentPage();
                        throw new IOException("新版本插件无法加载，已恢复旧版本");
                    }
                    externalPluginStore.confirmInstall(pluginId);
                    updateStatus = "已安装 " + release.title + " " + release.versionName;
                    showToast(updateStatus);
                } catch (IOException | JSONException error) {
                    if (installStarted) {
                        externalPluginStore.rollbackInstall(pluginId);
                    }
                    updateStatus = "插件更新失败：" + error.getMessage();
                    showToast(updateStatus);
                } finally {
                    updateOperations.remove(pluginId);
                    clearDownloadProgress(pluginId);
                    invalidateComposeUi();
                }
            }

            @Override
            public void onError(String message) {
                updateOperations.remove(pluginId);
                clearDownloadProgress(pluginId);
                updateStatus = "插件更新失败：" + message;
                showToast(updateStatus);
                invalidateComposeUi();
            }
        });
    }

    private void showBlockedPluginTransitionDialog(String title, String message) {
        showComposeDialog(title, message, "", "知道了", null);
    }

    private PluginUpdatePolicy.Transition assessPluginTransition(UpdateCatalog.PluginRelease release) {
        ImportedPluginDescriptor installed = findImportedDescriptor(release.id);
        return PluginUpdatePolicy.assessTransition(
                release,
                installed,
                externalPluginStore.isRepositoryVerified(release.id),
                externalPluginStore.verifiedSha256(release.id),
                externalPluginStore.mayHavePluginData(release.id),
                currentPluginDataFormatVersion(release.id),
                isOlderPluginBuild(release, installed)
        );
    }

    private int currentPluginDataFormatVersion(String pluginId) {
        int stored = externalPluginStore.dataFormatVersion(pluginId);
        if (stored > 0 || updateCatalog == null) {
            return stored;
        }
        String installedSha = externalPluginStore.verifiedSha256(pluginId);
        if (installedSha.isEmpty()) {
            return 0;
        }
        for (UpdateCatalog.PluginRelease candidate : updateCatalog.versionsForPlugin(pluginId)) {
            if (candidate.sha256.equalsIgnoreCase(installedSha)) {
                return candidate.dataFormatVersion;
            }
        }
        return 0;
    }

    private boolean isOlderPluginBuild(
            UpdateCatalog.PluginRelease target,
            ImportedPluginDescriptor installed
    ) {
        if (installed == null || target.versionCode != installed.versionCode) {
            return installed != null && target.versionCode < installed.versionCode;
        }
        if (!externalPluginStore.isRepositoryVerified(target.id)) {
            return true;
        }
        String installedChannel = externalPluginStore.repositoryChannel(target.id);
        if (!target.channel.equals(installedChannel)) {
            return true;
        }
        String installedSha = externalPluginStore.verifiedSha256(target.id);
        List<UpdateCatalog.PluginRelease> versions = updateCatalog.versionsForPlugin(target.id);
        int targetIndex = versions.indexOf(target);
        int installedIndex = -1;
        for (int index = 0; index < versions.size(); index++) {
            if (versions.get(index).sha256.equalsIgnoreCase(installedSha)) {
                installedIndex = index;
                break;
            }
        }
        return targetIndex >= 0 && installedIndex >= 0 && targetIndex > installedIndex;
    }

    public void installAllPluginUpdatesForUi() {
        if (updateCatalog == null) {
            return;
        }
        for (UpdateCatalog.PluginRelease release : updateCatalog.plugins) {
            ImportedPluginDescriptor installed = findImportedDescriptor(release.id);
            if (release.minHostVersionCode <= BuildConfig.VERSION_CODE
                    && installed != null
                    && isRepositoryPluginUpdateAvailableForUi(release)) {
                installRepositoryPluginForUi(release.id);
            }
        }
    }

    private void checkForUpdates(boolean force, boolean userInitiated, boolean repositoryRefresh) {
        if (updateOperations.contains("__check__")) {
            return;
        }
        updateOperations.add("__check__");
        updateStatus = "正在检查" + pluginRepositoryChannelLabelForUi() + "更新…";
        updateCheckState = UpdateCheckState.CHECKING;
        updateError = "";
        // 后台自动检查不展示“检查中”，避免应用刚打开后让当前页和相邻页一起重组。
        if (userInitiated) invalidateComposeUi();
        String appChannel = BuildConfig.DEBUG
                ? UpdateCatalog.CHANNEL_DEBUG
                : UpdateCatalog.CHANNEL_RELEASE;
        updateClient.check(appChannel, pluginRepositoryChannelForUi(), force, new UpdateClient.CatalogCallback() {
            @Override
            public void onSuccess(UpdateCatalog catalog, boolean cached) {
                updateOperations.remove("__check__");
                updateCatalog = catalog;
                uiPreferences.edit().putLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();
                int available = availableUpdateCountForUi();
                if (available > 0) {
                    updateStatus = "发现 " + available + " 项更新"
                            + (cached ? "（缓存索引）" : "");
                    updateCheckState = UpdateCheckState.AVAILABLE;
                    updatePromptVisible = !currentUpdateFingerprint().equals(
                            uiPreferences.getString(PREF_DISMISSED_UPDATE_VERSIONS, "")
                    );
                    if (userInitiated && repositoryRefresh) {
                        enqueueSnackbar("刷新完成，发现 " + available + " 项更新", null);
                    }
                } else {
                    updateStatus = "已是最新版本"
                            + (cached ? "（缓存索引）" : "");
                    updateCheckState = UpdateCheckState.UP_TO_DATE;
                    updatePromptVisible = false;
                    if (userInitiated) {
                        enqueueSnackbar(repositoryRefresh ? "刷新完成，已是最新版本" : "已是最新版本", null);
                    }
                }
                // 只有可见结果需要刷新：发现更新要弹提示；手动检查要更新 Snackbar/状态。
                if (available > 0 || userInitiated) invalidateComposeUi();
            }

            @Override
            public void onError(String message) {
                updateOperations.remove("__check__");
                updateStatus = "检查更新失败：" + message;
                updateError = message;
                updatePromptVisible = false;
                if (userInitiated) {
                    updateCheckState = UpdateCheckState.FAILED;
                    enqueueSnackbar(repositoryRefresh ? "刷新失败：" + message : updateStatus, "重试");
                } else {
                    updateCheckState = UpdateCheckState.IDLE;
                    updateError = "";
                }
                if (userInitiated) invalidateComposeUi();
            }
        });
    }

    private String currentUpdateFingerprint() {
        if (updateCatalog == null) return "";
        List<String> versions = new ArrayList<>();
        UpdateCatalog.AppRelease appRelease = appUpdateForUi();
        if (appRelease != null) versions.add("app:" + appRelease.versionCode);
        for (UpdateCatalog.PluginRelease release : updateCatalog.plugins) {
            if (isRepositoryPluginUpdateAvailableForUi(release)) {
                versions.add(release.id + ":" + release.versionCode + ":" + release.sha256);
            }
        }
        Collections.sort(versions);
        return String.join("|", versions);
    }

    private void enqueueSnackbar(String message, String action) {
        snackbarMessage = message;
        snackbarAction = action;
    }

    private void validateRepositoryPlugin(
            ImportedPluginDescriptor descriptor,
            UpdateCatalog.PluginRelease release
    ) throws IOException {
        if (!release.id.equals(descriptor.id)) {
            throw new IOException("插件包 ID 与仓库索引不一致");
        }
        if (descriptor.versionCode != release.versionCode
                || !release.versionName.equals(descriptor.version)) {
            throw new IOException("插件包版本与仓库索引不一致");
        }
        if (descriptor.minHostVersionCode != release.minHostVersionCode) {
            throw new IOException("插件兼容性信息与仓库索引不一致");
        }
        if (descriptor.minHostVersionCode > BuildConfig.VERSION_CODE) {
            throw new IOException("当前应用版本不兼容此插件");
        }
        if (!descriptor.dependencies.equals(release.dependencies)) {
            throw new IOException("插件依赖信息与仓库索引不一致");
        }
    }

    private void preflightPlugin(PluginImport pluginImport) throws IOException {
        File directory = new File(getCacheDir(), "updates");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建插件预检目录");
        }
        File codeFile = new File(directory, pluginImport.descriptor.id + ".preflight.apk");
        try (FileOutputStream output = new FileOutputStream(codeFile)) {
            output.write(pluginImport.codeBytes);
            output.getFD().sync();
        }
        codeFile.setReadOnly();
        ToolPlugin plugin = ExternalToolFactory.create(
                this,
                pluginImport.descriptor.withCodePath(codeFile.getAbsolutePath())
        );
        codeFile.setWritable(true);
        codeFile.delete();
        if (plugin == null) {
            throw new IOException("插件入口类无法加载");
        }
        plugin.onDestroy();
    }

    private byte[] readFileBytes(File file) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void verifyAppUpdate(File apk, UpdateCatalog.AppRelease release) throws IOException {
        int signatureFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo archive = getPackageManager().getPackageArchiveInfo(
                apk.getAbsolutePath(),
                signatureFlags
        );
        if (archive == null) {
            throw new IOException("无法解析下载的 APK");
        }
        if (!getPackageName().equals(archive.packageName)
                || !getPackageName().equals(release.packageName)) {
            throw new IOException("下载 APK 的包名不匹配");
        }
        long archiveVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? archive.getLongVersionCode()
                : archive.versionCode;
        if (archiveVersion != release.versionCode
                || !AppUpdatePolicy.isDownloadedVersionValid(
                        archiveVersion,
                        BuildConfig.VERSION_CODE,
                        BuildConfig.DEBUG
                )) {
            throw new IOException("下载 APK 的版本号无效");
        }
        PackageInfo installed;
        try {
            installed = getPackageManager().getPackageInfo(getPackageName(), signatureFlags);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IOException("无法读取当前应用签名", error);
        }
        if (!sameSigners(installed, archive)) {
            throw new IOException("下载 APK 的签名与当前应用不一致");
        }
    }

    private boolean sameSigners(PackageInfo first, PackageInfo second) {
        Signature[] firstSignatures;
        Signature[] secondSignatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (first.signingInfo == null || second.signingInfo == null) {
                return false;
            }
            firstSignatures = first.signingInfo.getApkContentsSigners();
            secondSignatures = second.signingInfo.getApkContentsSigners();
        } else {
            firstSignatures = first.signatures;
            secondSignatures = second.signatures;
        }
        if (firstSignatures == null || secondSignatures == null
                || firstSignatures.length != secondSignatures.length) {
            return false;
        }
        List<Signature> remaining = new ArrayList<>(Arrays.asList(secondSignatures));
        for (Signature signature : firstSignatures) {
            if (!remaining.remove(signature)) {
                return false;
            }
        }
        return remaining.isEmpty();
    }

    private void launchAppInstaller(File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            Intent settingsIntent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(settingsIntent);
            showToast("请允许此应用安装更新，然后再次点击更新");
            return;
        }
        Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".updates",
                apk
        );
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
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

    public void importMigration() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        startActivityForResult(intent, REQUEST_IMPORT_MIGRATION);
    }

    public void exportMigration() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(
                Intent.EXTRA_TITLE,
                "android-tool-suite-" + (BuildConfig.DEBUG ? "debug" : "release") + ".atsbackup"
        );
        startActivityForResult(intent, REQUEST_EXPORT_MIGRATION);
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
        runOnUiThread(() -> {
            enqueueSnackbar(message, null);
            invalidateComposeUi();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_PLUGIN) {
            handleImportResult(resultCode, data);
        } else if (requestCode == REQUEST_EXPORT_PLUGIN) {
            handleExportResult(resultCode, data);
        } else if (requestCode == REQUEST_IMPORT_MIGRATION) {
            handleMigrationImportResult(resultCode, data);
        } else if (requestCode == REQUEST_EXPORT_MIGRATION) {
            handleMigrationExportResult(resultCode, data);
        }
    }

    private void handleImportResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        boolean installStarted = false;
        String pluginId = null;
        try {
            PluginImport pluginImport = readPluginPackage(data.getData());
            ImportedPluginDescriptor descriptor = pluginImport.descriptor;
            pluginId = descriptor.id;
            if (isBuiltInPluginId(descriptor.id)) {
                showToast("插件 ID 与内置插件冲突");
                return;
            }
            if (descriptor.minHostVersionCode > BuildConfig.VERSION_CODE) {
                showToast("当前应用版本不兼容此插件");
                return;
            }
            boolean updating = findImportedDescriptor(descriptor.id) != null;
            boolean wasEnabled = externalPluginStore.isEnabled(descriptor.id);
            preflightPlugin(pluginImport);
            externalPluginStore.installPlugin(descriptor, pluginImport.codeBytes, "", "", "", false, 0);
            installStarted = true;
            reloadPluginsKeepingCurrentPage();
            if (wasEnabled && findPlugin(descriptor.id) == null) {
                externalPluginStore.rollbackInstall(descriptor.id);
                installStarted = false;
                reloadPluginsKeepingCurrentPage();
                throw new IOException("新版本插件无法加载，已恢复旧版本");
            }
            externalPluginStore.confirmInstall(descriptor.id);
            showToast((updating ? "已更新插件：" : "已导入插件，默认停用：") + descriptor.title);
        } catch (IOException | JSONException e) {
            if (installStarted && pluginId != null) {
                externalPluginStore.rollbackInstall(pluginId);
                reloadPluginsKeepingCurrentPage();
            }
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

    private void handleMigrationExportResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
            if (output == null) {
                throw new IOException("无法写入迁移包");
            }
            HostMigrationArchive.write(output, createMigrationSnapshot());
            showToast("迁移包已导出，不包含账号凭据和插件业务数据");
        } catch (IOException | JSONException error) {
            showToast("导出迁移包失败：" + error.getMessage());
        }
    }

    private void handleMigrationImportResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try {
            HostMigrationArchive.Snapshot snapshot = HostMigrationArchive.read(
                    readBytes(data.getData(), HostMigrationArchive.MAX_ARCHIVE_BYTES)
            );
            List<PreparedMigrationPlugin> prepared = prepareMigration(snapshot);
            String message = "来源：" + snapshot.sourcePackage + " " + snapshot.sourceVersionName
                    + "\n插件：" + prepared.size() + " 个"
                    + "\n\n将迁移应用布局、仓库选择、插件包与启用状态。"
                    + "目标端独有插件会保留；账号凭据和插件业务数据不会迁移。";
            showComposeDialog(
                    "导入 Android Tool Suite 迁移包？",
                    message,
                    "取消",
                    "导入",
                    () -> applyMigration(snapshot, prepared)
            );
        } catch (IOException | JSONException error) {
            showToast("读取迁移包失败：" + error.getMessage());
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
        reloadPlugins(null);
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
        reloadPlugins(null);
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
        return pluginId == null ? null : importedDescriptorCache.get(pluginId);
    }

    private HostMigrationArchive.Snapshot createMigrationSnapshot()
            throws IOException, JSONException {
        List<HostMigrationArchive.PluginEntry> entries = new ArrayList<>();
        for (ImportedPluginDescriptor descriptor : externalPluginStore.load()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writePluginPackage(output, descriptor);
            entries.add(new HostMigrationArchive.PluginEntry(
                    descriptor.id,
                    externalPluginStore.isEnabled(descriptor.id),
                    output.toByteArray()
            ));
        }
        return new HostMigrationArchive.Snapshot(
                getPackageName(),
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                captureHostSettings(),
                builtInPluginStateStore.enabledIds(),
                entries
        );
    }

    private List<PreparedMigrationPlugin> prepareMigration(
            HostMigrationArchive.Snapshot snapshot
    ) throws IOException, JSONException {
        Set<String> knownBuiltInIds = builtInPluginIds();
        if (!knownBuiltInIds.containsAll(snapshot.builtInEnabledIds)) {
            throw new IOException("迁移包包含未知内置插件状态");
        }
        List<PreparedMigrationPlugin> prepared = new ArrayList<>();
        for (HostMigrationArchive.PluginEntry entry : snapshot.plugins) {
            PluginImport pluginImport = readPluginPackage(entry.packageBytes);
            if (!entry.id.equals(pluginImport.descriptor.id)) {
                throw new IOException("迁移记录与插件包 ID 不一致：" + entry.id);
            }
            if (isBuiltInPluginId(entry.id)) {
                throw new IOException("插件 ID 与内置插件冲突：" + entry.id);
            }
            if (pluginImport.descriptor.minHostVersionCode > BuildConfig.VERSION_CODE) {
                throw new IOException("插件要求更高版本应用：" + pluginImport.descriptor.title);
            }
            preflightPlugin(pluginImport);
            prepared.add(new PreparedMigrationPlugin(pluginImport, entry.enabled));
        }
        validateMigrationDependencies(snapshot.builtInEnabledIds, prepared);
        return prepared;
    }

    private void validateMigrationDependencies(
            Set<String> builtInEnabledIds,
            List<PreparedMigrationPlugin> prepared
    ) throws IOException {
        Map<String, ImportedPluginDescriptor> resulting = new LinkedHashMap<>();
        Set<String> enabled = new LinkedHashSet<>(externalPluginStore.enabledIds());
        for (ImportedPluginDescriptor descriptor : externalPluginStore.load()) {
            resulting.put(descriptor.id, descriptor);
        }
        for (PreparedMigrationPlugin item : prepared) {
            resulting.put(item.pluginImport.descriptor.id, item.pluginImport.descriptor);
            if (item.enabled) {
                enabled.add(item.pluginImport.descriptor.id);
            } else {
                enabled.remove(item.pluginImport.descriptor.id);
            }
        }

        Map<String, String> activeVersions = new LinkedHashMap<>();
        for (ToolPlugin plugin : ToolRegistry.createBuiltInPlugins()) {
            if (builtInEnabledIds.contains(plugin.id())) {
                activeVersions.put(plugin.id(), plugin.version());
            }
        }
        for (String id : enabled) {
            ImportedPluginDescriptor descriptor = resulting.get(id);
            if (descriptor != null) {
                activeVersions.put(id, descriptor.version);
            }
        }
        for (String id : enabled) {
            ImportedPluginDescriptor descriptor = resulting.get(id);
            if (descriptor == null) {
                continue;
            }
            for (String rawDependency : descriptor.dependencies) {
                PluginDependency dependency = PluginDependency.parse(rawDependency);
                if (!dependency.isSatisfied(activeVersions)) {
                    throw new IOException(
                            descriptor.title + " 缺少依赖 " + dependency.label()
                    );
                }
            }
        }
    }

    private void applyMigration(
            HostMigrationArchive.Snapshot snapshot,
            List<PreparedMigrationPlugin> prepared
    ) {
        List<ExternalPluginStore.PluginState> previousPlugins = new ArrayList<>();
        JSONObject previousHost;
        Set<String> previousBuiltIns = builtInPluginStateStore.enabledIds();
        try {
            previousHost = captureHostSettings();
            for (PreparedMigrationPlugin item : prepared) {
                previousPlugins.add(externalPluginStore.snapshot(item.pluginImport.descriptor.id));
            }
        } catch (IOException | JSONException error) {
            showToast("无法创建迁移回滚点：" + error.getMessage());
            return;
        }

        try {
            Set<String> migratedIds = builtInPluginIds();
            for (PreparedMigrationPlugin item : prepared) {
                migratedIds.add(item.pluginImport.descriptor.id);
            }
            JSONObject mergedHost = mergeHostSettings(previousHost, snapshot.host, migratedIds);

            List<MigrationTransaction.Operation> operations = new ArrayList<>();
            for (int index = 0; index < prepared.size(); index++) {
                PreparedMigrationPlugin item = prepared.get(index);
                ExternalPluginStore.PluginState previous = previousPlugins.get(index);
                operations.add(new MigrationTransaction.Operation() {
                    @Override
                    public void apply() throws IOException, JSONException {
                        ImportedPluginDescriptor descriptor = item.pluginImport.descriptor;
                        externalPluginStore.installPlugin(
                                descriptor,
                                item.pluginImport.codeBytes,
                                "",
                                "",
                                "",
                                false,
                                0
                        );
                        externalPluginStore.confirmInstall(descriptor.id);
                        externalPluginStore.setEnabled(descriptor.id, item.enabled);
                    }

                    @Override
                    public void rollback() throws IOException, JSONException {
                        externalPluginStore.restore(previous);
                    }
                });
            }
            operations.add(new MigrationTransaction.Operation() {
                @Override
                public void apply() throws IOException {
                    if (!builtInPluginStateStore.replaceEnabledIds(snapshot.builtInEnabledIds)) {
                        throw new IOException("无法保存内置插件状态");
                    }
                }

                @Override
                public void rollback() throws IOException {
                    if (!builtInPluginStateStore.replaceEnabledIds(previousBuiltIns)) {
                        throw new IOException("无法恢复内置插件状态");
                    }
                }
            });
            operations.add(new MigrationTransaction.Operation() {
                @Override
                public void apply() throws IOException {
                    if (!applyHostSettings(mergedHost)) {
                        throw new IOException("无法保存应用设置");
                    }
                }

                @Override
                public void rollback() throws IOException {
                    if (!applyHostSettings(previousHost)) {
                        throw new IOException("无法恢复应用设置");
                    }
                }
            });
            MigrationTransaction.execute(operations);
            reloadPlugins(null);
            updateCatalog = null;
            checkForUpdates(true, false, false);
            showToast("迁移完成：已导入 " + prepared.size() + " 个插件");
        } catch (IOException | JSONException error) {
            reloadPlugins(null);
            String rollbackStatus = error.getSuppressed().length == 0
                    ? "已恢复原状态"
                    : "回滚不完整，请勿继续操作并重新导入";
            showToast("迁移失败，" + rollbackStatus + "：" + error.getMessage());
        }
    }

    private Set<String> builtInPluginIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ToolPlugin plugin : ToolRegistry.createBuiltInPlugins()) {
            ids.add(plugin.id());
        }
        return ids;
    }

    private JSONObject captureHostSettings() throws JSONException {
        JSONObject host = new JSONObject();
        host.put("pluginRepositoryChannel", pluginRepositoryChannelForUi());
        host.put("hiddenWidgets", strings(uiPreferences.getStringSet(
                PREF_HIDDEN_WIDGETS, Collections.emptySet()
        )));
        host.put("hiddenTools", strings(uiPreferences.getStringSet(
                PREF_HIDDEN_TOOLS, Collections.emptySet()
        )));
        host.put("toolOrder", strings(lines(uiPreferences.getString(PREF_TOOL_ORDER, ""))));
        host.put("widgetOrder", strings(lines(uiPreferences.getString(PREF_WIDGET_ORDER, ""))));
        host.put("fullWidthWidgets", strings(uiPreferences.getStringSet(
                PREF_FULL_WIDTH_WIDGETS, Collections.emptySet()
        )));
        host.put("widgetSizes", strings(uiPreferences.getStringSet(
                PREF_WIDGET_SIZES, Collections.emptySet()
        )));
        return host;
    }

    private JSONObject mergeHostSettings(
            JSONObject current,
            JSONObject incoming,
            Set<String> migratedIds
    ) throws JSONException {
        JSONObject merged = new JSONObject(current.toString());
        String channel = incoming.optString("pluginRepositoryChannel", UpdateCatalog.CHANNEL_RELEASE);
        merged.put(
                "pluginRepositoryChannel",
                UpdateCatalog.CHANNEL_DEBUG.equals(channel)
                        ? UpdateCatalog.CHANNEL_DEBUG
                        : UpdateCatalog.CHANNEL_RELEASE
        );
        merged.put("hiddenTools", strings(mergeScopedSet(
                jsonStrings(current.optJSONArray("hiddenTools")),
                jsonStrings(incoming.optJSONArray("hiddenTools")),
                migratedIds,
                false
        )));
        for (String key : Arrays.asList("hiddenWidgets", "fullWidthWidgets", "widgetSizes")) {
            merged.put(key, strings(mergeScopedSet(
                    jsonStrings(current.optJSONArray(key)),
                    jsonStrings(incoming.optJSONArray(key)),
                    migratedIds,
                    true
            )));
        }
        merged.put("toolOrder", strings(mergeScopedOrder(
                jsonStrings(incoming.optJSONArray("toolOrder")),
                jsonStrings(current.optJSONArray("toolOrder")),
                migratedIds,
                false
        )));
        merged.put("widgetOrder", strings(mergeScopedOrder(
                jsonStrings(incoming.optJSONArray("widgetOrder")),
                jsonStrings(current.optJSONArray("widgetOrder")),
                migratedIds,
                true
        )));
        return merged;
    }

    private Set<String> mergeScopedSet(
            Set<String> current,
            Set<String> incoming,
            Set<String> ids,
            boolean widgetKey
    ) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : current) {
            if (!belongsTo(value, ids, widgetKey)) {
                result.add(value);
            }
        }
        for (String value : incoming) {
            if (belongsTo(value, ids, widgetKey)) {
                result.add(value);
            }
        }
        return result;
    }

    private Set<String> mergeScopedOrder(
            Set<String> incoming,
            Set<String> current,
            Set<String> ids,
            boolean widgetKey
    ) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : incoming) {
            if (belongsTo(value, ids, widgetKey)) {
                result.add(value);
            }
        }
        for (String value : current) {
            if (!belongsTo(value, ids, widgetKey)) {
                result.add(value);
            }
        }
        return result;
    }

    private boolean belongsTo(String value, Set<String> ids, boolean widgetKey) {
        if (!widgetKey) {
            return ids.contains(value);
        }
        for (String id : ids) {
            if (value.startsWith(id + ":")) {
                return true;
            }
        }
        return false;
    }

    private boolean applyHostSettings(JSONObject host) {
        SharedPreferences.Editor editor = uiPreferences.edit()
                .putString(
                        PREF_PLUGIN_REPOSITORY_CHANNEL,
                        UpdateCatalog.CHANNEL_DEBUG.equals(
                                host.optString("pluginRepositoryChannel")
                        ) ? UpdateCatalog.CHANNEL_DEBUG : UpdateCatalog.CHANNEL_RELEASE
                )
                .putStringSet(PREF_HIDDEN_WIDGETS, jsonStrings(host.optJSONArray("hiddenWidgets")))
                .putStringSet(PREF_HIDDEN_TOOLS, jsonStrings(host.optJSONArray("hiddenTools")))
                .putString(PREF_TOOL_ORDER, String.join("\n", jsonStrings(host.optJSONArray("toolOrder"))))
                .putString(PREF_WIDGET_ORDER, String.join("\n", jsonStrings(host.optJSONArray("widgetOrder"))))
                .putStringSet(PREF_FULL_WIDTH_WIDGETS, jsonStrings(host.optJSONArray("fullWidthWidgets")))
                .putStringSet(PREF_WIDGET_SIZES, jsonStrings(host.optJSONArray("widgetSizes")));
        return editor.commit();
    }

    private static JSONArray strings(Set<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private static Set<String> jsonStrings(JSONArray array) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                String value = array.optString(index, "").trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static Set<String> lines(String value) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (value != null) {
            for (String line : value.split("\\R")) {
                String clean = line.trim();
                if (!clean.isEmpty()) {
                    values.add(clean);
                }
            }
        }
        return values;
    }

    private PluginImport readPluginPackage(Uri uri) throws IOException, JSONException {
        return readPluginPackage(readBytes(uri));
    }

    private PluginImport readPluginPackage(byte[] bytes) throws IOException, JSONException {
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
        return readBytes(uri, Integer.MAX_VALUE);
    }

    private byte[] readBytes(Uri uri, int limit) throws IOException {
        InputStream inputStream = getContentResolver().openInputStream(uri);
        if (inputStream == null) {
            throw new IOException("无法读取插件文件");
        }
        try (InputStream stream = inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (output.size() + read > limit) {
                    throw new IOException("文件大小超出限制");
                }
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

    private static final class PreparedMigrationPlugin {
        final PluginImport pluginImport;
        final boolean enabled;

        PreparedMigrationPlugin(PluginImport pluginImport, boolean enabled) {
            this.pluginImport = pluginImport;
            this.enabled = enabled;
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
