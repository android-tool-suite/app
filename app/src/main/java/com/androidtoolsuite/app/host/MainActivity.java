package com.androidtoolsuite.app.host;

import com.androidtoolsuite.app.BuildConfig;
import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.FileProvider;
import androidx.core.splashscreen.SplashScreen;

import com.androidtoolsuite.app.plugin.store.BuiltInPluginStateStore;
import com.androidtoolsuite.app.migration.BackupArchiveV2;
import com.androidtoolsuite.app.migration.BackupPackageProbe;
import com.androidtoolsuite.app.migration.DataPackageArchive;
import com.androidtoolsuite.app.migration.MigrationBridgeManager;
import com.androidtoolsuite.app.migration.HostMigrationArchive;
import com.androidtoolsuite.app.migration.MigrationTransaction;
import com.androidtoolsuite.app.migration.DatasetRestoreMode;
import com.androidtoolsuite.app.migration.DatasetBridge;
import com.androidtoolsuite.app.migration.DatasetDescriptor;
import com.androidtoolsuite.app.plugin.runtime.HostActions;
import com.androidtoolsuite.app.plugin.runtime.CapabilityRouter;
import com.androidtoolsuite.app.plugin.runtime.BackgroundTaskRegistry;
import com.androidtoolsuite.app.plugin.runtime.HostCapabilityProviders;
import com.androidtoolsuite.app.plugin.runtime.NativeProviderManager;
import com.androidtoolsuite.app.plugin.runtime.PluginPackageStore;
import com.androidtoolsuite.app.plugin.runtime.PluginPermissionManager;
import com.androidtoolsuite.app.plugin.runtime.PluginPackageArchive;
import com.androidtoolsuite.app.plugin.runtime.PluginRuntime;
import com.androidtoolsuite.app.plugin.runtime.PluginUiOperationRegistry;
import com.androidtoolsuite.app.plugin.runtime.SchedulerService;
import com.androidtoolsuite.app.plugin.runtime.JavaScriptWorkerEngine;
import com.androidtoolsuite.app.plugin.runtime.StorageService;
import com.androidtoolsuite.app.plugin.runtime.DatasetService;
import com.androidtoolsuite.app.plugin.runtime.DeclarativeToolPlugin;
import com.androidtoolsuite.app.plugin.runtime.MigrationToolPlugin;
import com.androidtoolsuite.app.plugin.runtime.ShizukuService;
import com.androidtoolsuite.app.plugin.runtime.WebToolPlugin;
import com.androidtoolsuite.app.plugin.runtime.CapabilityFailure;
import com.androidtoolsuite.app.plugin.runtime.HostHomeWidget;
import com.androidtoolsuite.app.plugin.api.HomeWidgetSize;
import com.androidtoolsuite.app.plugin.api.PluginDependency;
import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor;
import com.androidtoolsuite.app.plugin.runtime.HostServices;
import com.androidtoolsuite.app.plugin.runtime.HostTool;
import com.androidtoolsuite.app.plugin.runtime.ToolRegistry;
import com.androidtoolsuite.app.ui.SuiteColorPreference;
import com.androidtoolsuite.app.ui.SuiteThemePreference;
import com.androidtoolsuite.app.ui.SuiteThemePreferences;
import com.androidtoolsuite.app.update.UpdateCatalog;
import com.androidtoolsuite.app.update.UpdateClient;
import com.androidtoolsuite.app.update.AppUpdatePolicy;
import com.androidtoolsuite.app.update.PluginUpdatePolicy;
import com.androidtoolsuite.runtime.contract.ContractException;
import com.androidtoolsuite.runtime.contract.ContractLimits;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;

import rikka.shizuku.Shizuku;

public class MainActivity extends ComponentActivity implements HostServices, HostActions {
    public static final String EXTRA_DEBUG_DESTINATION = "debug_destination";
    private static WeakReference<MainActivity> debugInstance = new WeakReference<>(null);

    private static final int REQUEST_SHIZUKU = 3001;
    private static final int REQUEST_IMPORT_PLUGIN = 4001;
    private static final int REQUEST_EXPORT_PLUGIN = 4002;
    private static final int REQUEST_EXPORT_MIGRATION_BRIDGE = 4005;
    private static final int REQUEST_IMPORT_MIGRATION_BRIDGE = 4006;
    private static final int REQUEST_V2_FILE_IMPORT = 4007;
    private static final int REQUEST_V2_NOTIFICATION_PERMISSION = 4008;
    private static final int REQUEST_V2_FILE_EXPORT = 4009;

    private static final int SECTION_DASHBOARD = 0;
    private static final int SECTION_PLUGINS = 1;
    private static final int SECTION_MANAGER = 2;
    private static final int SECTION_STORE = 6;
    private static final int SECTION_SETTINGS = 3;
    private static final int SECTION_ABOUT = 4;

    private static final String PREFS_NAME = "main_ui";
    private static final String PREF_HIDDEN_WIDGETS = "hidden_widgets";
    private static final String PREF_HIDDEN_TOOLS = "hidden_tools";
    private static final String PREF_TOOL_ORDER = "tool_order";
    private static final String PREF_WIDGET_ORDER = "widget_order";
    private static final String PREF_FULL_WIDTH_WIDGETS = "full_width_widgets";
    private static final String PREF_WIDGET_SIZES = "widget_sizes";
    private static final String PREF_THEME = "theme_preference";
    private static final String PREF_COLOR = "color_preference";
    private static final String PREF_AUTO_CHECK_UPDATES = "auto_check_updates";
    private static final String PREF_AUTO_CHECK_APP_UPDATES = "auto_check_app_updates";
    private static final String PREF_LAST_UPDATE_CHECK = "last_update_check";
    private static final String PREF_DISMISSED_UPDATE_VERSIONS = "dismissed_update_versions";
    private static final String PREF_STORE_RISK_ACKNOWLEDGED = "store_risk_acknowledged";
    private static final String PREF_UPDATE_CHECK_EXCLUDED = "update_check_excluded_plugins";

    private final List<HostTool> plugins = new ArrayList<>();
    private final Set<String> invalidatedToolPages = new HashSet<>();
    private final FirstFrameGate firstFrameGate = new FirstFrameGate();
    private List<com.androidtoolsuite.app.plugin.runtime.WidgetSnapshotStore.Entry> startupWidgetEntries = Collections.emptyList();
    private final Map<String, PluginPackageStore.InstalledPlugin> runtimeInstalledCache = new LinkedHashMap<>();
    private final Set<String> optionalBuiltInPluginIds = new HashSet<>();
    private HostTool selectedPlugin;
    private PluginRuntime pluginRuntime;
    private PluginPackageStore pluginPackageStore;
    private StorageService storageService;
    private DatasetService datasetService;
    private CapabilityRouter capabilityRouter;
    private BackgroundTaskRegistry backgroundTaskRegistry;
    private PluginPermissionManager permissionManager;
    private NativeProviderManager nativeProviderManager;
    private SchedulerService schedulerService;
    private ShizukuService shizukuService;
    private AutoCloseable shizukuStateRegistration;
    private List<AutoCloseable> providerRegistrations = Collections.emptyList();
    private AutoCloseable permissionRegistration;
    private PendingRuntimeFilePick pendingRuntimeFilePick;
    private PendingRuntimeFileExport pendingRuntimeFileExport;
    private final ExecutorService runtimeFileExecutor = Executors.newSingleThreadExecutor();
    private BuiltInPluginStateStore builtInPluginStateStore;
    private UpdateClient updateClient;
    private UpdateCatalog updateCatalog;
    private String updateStatus = "尚未检查更新";
    private UpdateCheckState updateCheckState = UpdateCheckState.IDLE;
    private String updateError = "";
    private String snackbarMessage;
    private String snackbarAction;
    private Runnable snackbarCallback;
    private int snackbarRevision;
    private boolean updatePromptVisible;
    private boolean promptAppUpdates = true, promptPluginUpdates = true;
    private ComposeDialogState composeDialog;
    private final Set<String> updateOperations = new HashSet<>();
    private SharedPreferences uiPreferences;
    private HomeWidgetInstances homeWidgetInstances;
    private final Map<String, HostHomeWidget> instanceWidgetViews = new LinkedHashMap<>();
    private final Set<String> widgetMigrationAttempts = new HashSet<>();
    private String widgetSettingsPluginId, widgetSettingsInstanceId;
    private String pendingExportPluginId;
    private final ExecutorService migrationBridgeExecutor = Executors.newSingleThreadExecutor();
    private List<MigrationBridgeManager.DatasetOption> migrationBridgeExportOptions = Collections.emptyList();
    private List<HostTool> migrationBridgeOwnedPlugins = Collections.emptyList();
    private List<MigrationBridgeManager.ExportSelection> pendingMigrationBridgeExport = Collections.emptyList();
    private List<HostTool> pendingMigrationBridgeOwnedPlugins = Collections.emptyList();
    private char[] pendingMigrationBridgePassword;
    private List<MigrationBridgeManager.DatasetOption> migrationBridgeImportOptions = Collections.emptyList();
    private List<HostTool> migrationBridgeImportOwnedPlugins = Collections.emptyList();
    private Uri pendingMigrationBridgeImportUri;
    private DataPackageArchive.ReadResult pendingDataPackageInspection;
    private BackupArchiveV2.ReadResult pendingMigrationBridgeInspection;
    private BackupPackageProbe.Format pendingBackupPackageFormat;
    private char[] pendingMigrationBridgeImportPassword;
    private String pendingDataOwnerFilter;
    private List<MigrationBridgeManager.DatasetOption> migrationBridgeDeleteOptions = Collections.emptyList();
    private List<MigrationBridgeManager.DatasetOption> migrationBridgeDeleteDependencyOptions = Collections.emptyList();
    private List<HostTool> migrationBridgeDeleteOwnedPlugins = Collections.emptyList();
    private int currentSection = SECTION_DASHBOARD;
    private int pluginReturnSection = SECTION_PLUGINS;
    private final List<HostTool> relatedToolBackStack = new ArrayList<>();
    private String managedPluginId;
    private String managedPluginPage = "detail";
    private boolean discoverPlugins;
    private boolean managementReturnsToTool;
    private String dataOwnerId;
    private String dataQuery = "";
    private String selectedTaskId;
    private int taskReturnSection = SECTION_SETTINGS;
    private String taskReturnPluginId;
    private String activeDataOwner;
    private UiTask pendingDataRetry;
    private boolean initiatingDataRetry;
    private boolean replacingDataFile;
    private boolean inspectingDataFile;
    private String importFileName = "";
    private String dataReturnPluginId;
    private boolean dataOperationRunning;
    private long dataFlowGeneration;
    private String activeDataFlow = "";
    private final Map<String, String> dataOperationErrors = new LinkedHashMap<>();

    public String dataOperationErrorForUi(String kind) {
        return dataOperationErrors.getOrDefault(kind, "");
    }

    private void clearDataOperationError(String kind) {
        if (dataOperationErrors.remove(kind) != null) invalidateComposeUi();
    }

    private void showDataOperationError(String kind, String message) {
        dataOperationErrors.put(kind, message);
        invalidateComposeUi();
        showToast(message);
    }
    private long claimDataFlow(String kind) {
        if (dataOperationRunning || !migrationBridgeExportOptions.isEmpty() || !migrationBridgeImportOptions.isEmpty()
                || !migrationBridgeDeleteOptions.isEmpty() || !pendingMigrationBridgeExport.isEmpty()) {
            showToast("请先完成或关闭当前数据操作");
            return -1;
        }
        activeDataFlow = kind;
        clearDataOperationError(kind);
        if (!initiatingDataRetry) pendingDataRetry = null;
        return ++dataFlowGeneration;
    }
    private boolean isCurrentDataFlow(long generation) {
        return generation == dataFlowGeneration && !isFinishing() && !isDestroyed();
    }
    private void releaseDataFlow(String kind) {
        if (kind.equals(activeDataFlow)) { activeDataFlow = ""; dataFlowGeneration++; }
        clearDataOperationError(kind);
    }
    private final List<UiTask> uiTasks = new ArrayList<>();
    private final PluginUiOperationRegistry pluginUiOperations = new PluginUiOperationRegistry();

    /** Preserve active tasks; only the oldest completed history is evicted. */
    private void trimUiTaskHistory() {
        int completed = 0;
        for (int index = 0; index < uiTasks.size();) {
            UiTask task = uiTasks.get(index);
            if (!"running".equals(task.status) && ++completed > 40) {
                uiTasks.remove(index);
                if (task.id.equals(selectedTaskId)) selectedTaskId = null;
            } else index++;
        }
    }

    private void applyPluginUiOperation(PluginUiOperationRegistry.Operation operation) {
        UiTask task = null;
        for (UiTask existing : uiTasks) if (existing.id.equals(operation.taskId)) { task = existing; break; }
        if (task == null) {
            // An idempotent terminal report must not recreate already-evicted history.
            if (!"running".equals(operation.status)) return;
            task = new UiTask(operation.taskId, operation.label);
            task.kind = "plugin";
            task.pluginId = operation.pluginId;
            task.owner = operation.pluginId;
            uiTasks.add(0, task);
        }
        task.status = operation.status;
        task.detail = operation.detail;
        trimUiTaskHistory();
        invalidateComposeUi();
    }

    @Override
    public boolean canStartUiOperation(String pluginId, String sessionId) {
        return getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                && isActivePluginSessionForUi(pluginId, sessionId);
    }

    @Override
    public CompletableFuture<JSONObject> reportOperation(com.androidtoolsuite.app.plugin.runtime.CapabilityCall call) {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        runOnUiThread(() -> {
            if (result.isDone()) return;
            if (System.currentTimeMillis() >= call.deadlineEpochMillis) {
                result.completeExceptionally(new CapabilityFailure("TIMEOUT", "操作状态报告已过期", true));
                return;
            }
            try {
                HostTool tool = findPlugin(call.pluginId);
                PluginUiOperationRegistry.Operation operation = pluginUiOperations.report(
                        call.pluginId, call.sessionId, PluginUiOperationRegistry.Report.parse(call.payload),
                        !isFinishing() && !isDestroyed() && tool != null && tool.ownsSession(call.sessionId),
                        // WebSession binds this method's gesture bit to visibility at RPC receipt.
                        // A concurrently opened system picker must not revoke an admitted report.
                        call.userGesture, call.userGesture);
                applyPluginUiOperation(operation);
                result.complete(new JSONObject().put("taskId", operation.taskId).put("status", operation.status));
            } catch (CapabilityFailure | JSONException error) { result.completeExceptionally(error); }
            catch (RuntimeException error) {
                result.completeExceptionally(new CapabilityFailure("INTERNAL", "无法更新操作状态报告", true));
            }
        });
        return result;
    }

    public static final class UiTask {
        public final String id, title;
        public final long createdAt = System.currentTimeMillis();
        public String status = "running", detail = "正在处理";
        public final List<UiTaskRow> rows = new ArrayList<>();
        public String pluginId;
        public String kind = "install", owner;
        public List<String> keys = Collections.emptyList();
        public List<String> protectedKeys = Collections.emptyList();
        public List<String> mergeKeys = Collections.emptyList();
        public final Set<String> completedKeys = new LinkedHashSet<>();
        private Uri source;
        UiTask(String id, String title) { this.id = id; this.title = title; }
    }
    public static final class UiTaskRow {
        public final String title;
        public String status = "waiting";
        UiTaskRow(String title) { this.title = title; }
    }

    public List<UiTask> tasksForUi() { return new ArrayList<>(uiTasks); }
    private String beginUiTask(String title) {
        return beginUiTask(title, Collections.emptyList());
    }
    private String beginUiTask(String title, List<String> names) {
        String id = java.util.UUID.randomUUID().toString();
        runOnUiThread(() -> {
            UiTask task = new UiTask(id, title);
            for (String name : names) task.rows.add(new UiTaskRow(name));
            uiTasks.add(0, task);
            selectedTaskId = id;
            trimUiTaskHistory();
            invalidateComposeUi();
        });
        return id;
    }
    private void setUiTaskPlugin(String id, String pluginId) {
        for (UiTask task : uiTasks) if (task.id.equals(id)) task.pluginId = pluginId;
    }
    private void setUiTaskData(String id, String kind, List<String> keys,
                               List<String> protectedKeys, List<String> mergeKeys, Uri source) {
        for (UiTask task : uiTasks) if (task.id.equals(id)) {
            task.kind = kind;
            task.owner = activeDataOwner;
            task.keys = Collections.unmodifiableList(new ArrayList<>(keys));
            task.protectedKeys = Collections.unmodifiableList(new ArrayList<>(protectedKeys));
            task.mergeKeys = Collections.unmodifiableList(new ArrayList<>(mergeKeys));
            task.source = source;
        }
    }
    public UiTask consumeDataRetryForUi(String kind) {
        if (pendingDataRetry == null || !kind.equals(pendingDataRetry.kind)) return null;
        UiTask original = pendingDataRetry;
        pendingDataRetry = null;
        UiTask retry = new UiTask(original.id, original.title);
        retry.kind = original.kind; retry.owner = original.owner;
        retry.keys = original.keys.stream().filter(key -> !original.completedKeys.contains(key)).collect(java.util.stream.Collectors.toList());
        retry.protectedKeys = original.protectedKeys; retry.mergeKeys = original.mergeKeys;
        return retry;
    }
    public void retryUiTaskForUi(String id) {
        if (dataOperationRunning || !migrationBridgeExportOptions.isEmpty() || !migrationBridgeImportOptions.isEmpty()
                || !migrationBridgeDeleteOptions.isEmpty()) { showToast("请先关闭当前数据操作"); return; }
        for (UiTask task : uiTasks) if (task.id.equals(id)) {
            if ("running".equals(task.status)) return;
            if ("plugin".equals(task.kind)) { returnToTaskSourceForUi(id); return; }
            if (task.pluginId != null) { showPluginManagementForUi(task.pluginId, "versions"); return; }
            pendingDataRetry = task;
            initiatingDataRetry = true;
            composeState.getDataDrafts().remove(task.kind);
            try {
                if ("export".equals(task.kind)) prepareMigrationBridgeExportForOwner(task.owner);
                else if ("delete".equals(task.kind)) prepareMigrationBridgeDeleteForOwner(task.owner);
                else if ("import".equals(task.kind) && task.source != null) {
                    if (claimDataFlow("import") < 0) return;
                    activeDataOwner = task.owner;
                    pendingDataOwnerFilter = task.owner;
                    handleMigrationBridgeImportSelection(RESULT_OK, new Intent().setData(task.source));
                }
            } finally { initiatingDataRetry = false; }
            return;
        }
    }
    public void returnToTaskSourceForUi(String id) {
        for (UiTask task : uiTasks) if (task.id.equals(id)) {
            if ("plugin".equals(task.kind)) {
                HostTool tool = findPlugin(task.pluginId);
                if (tool != null) openPlugin(tool);
                else { showPluginManagementForUi(task.pluginId, "detail"); showToast("来源插件当前不可用，请检查安装与启用状态"); }
            } else if (task.pluginId != null) showPluginManagementForUi(task.pluginId, "versions");
            else showDataManagementForUi(task.owner);
            return;
        }
    }
    private void finishUiTask(String id, boolean success, String detail) {
        runOnUiThread(() -> {
            for (UiTask task : uiTasks) if (task.id.equals(id)) {
                task.status = success ? "success" : task.rows.stream().anyMatch(row -> "success".equals(row.status)) ? "partial" : "failed";
                for (UiTaskRow row : task.rows) if (!"success".equals(row.status)) row.status = success ? "success" : "failed";
                task.detail = detail;
            }
            trimUiTaskHistory();
            invalidateComposeUi();
        });
    }
    private void completeUiTaskRow(String id, String title, String key) {
        runOnUiThread(() -> {
            for (UiTask task : uiTasks) if (task.id.equals(id)) {
                task.completedKeys.add(key);
                for (int index = 0; index < task.rows.size(); index++) if (index < task.keys.size() && task.keys.get(index).equals(key)) task.rows.get(index).status = "success";
            }
            invalidateComposeUi();
        });
    }

    public static final class DataInventoryEntry {
        public final String owner, ownerTitle, name, category;
        public final long bytes;
        public final boolean sensitive;
        DataInventoryEntry(MigrationBridgeManager.DatasetOption option) {
            owner = option.pluginId; ownerTitle = option.pluginTitle; name = option.descriptor.name;
            category = option.descriptor.category.name(); bytes = option.descriptor.estimatedSize;
            sensitive = option.descriptor.sensitive;
        }
    }
    public void loadDataInventoryForUi(java.util.function.Consumer<List<DataInventoryEntry>> done,
                                       java.util.function.Consumer<String> failed) {
        List<HostTool> active = new ArrayList<>(plugins);
        migrationBridgeExecutor.execute(() -> {
            List<HostTool> owned = new ArrayList<>();
            try {
                addRuntimeMigrationPlugins(active, owned);
                List<MigrationBridgeManager.DatasetOption> options = new ArrayList<>(createHostDataExportOptions());
                options.addAll(MigrationBridgeManager.discover(this, active));
                List<DataInventoryEntry> entries = new ArrayList<>();
                for (MigrationBridgeManager.DatasetOption option : options) entries.add(new DataInventoryEntry(option));
                runOnUiThread(() -> done.accept(entries));
            } catch (IOException | JSONException | RuntimeException error) {
                runOnUiThread(() -> failed.accept(safeMessage(error)));
            } finally { runOnUiThread(() -> destroyMigrationBridgePlugins(owned)); }
        });
    }
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
        public final boolean dismissible;
        private final Runnable onConfirm;

        private ComposeDialogState(
                String title,
                String message,
                String dismissLabel,
                String confirmLabel,
                Runnable onConfirm,
                boolean dismissible
        ) {
            this.title = title;
            this.message = message;
            this.dismissLabel = dismissLabel;
            this.confirmLabel = confirmLabel;
            this.onConfirm = onConfirm;
            this.dismissible = dismissible;
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
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> runOnUiThread(() -> {
        if (shizukuService != null) shizukuService.ensureIfAuthorized();
        notifyHostStateChangedAfterBinderCallback();
    });
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> runOnUiThread(() -> {
        notifyHostStateChanged();
    });
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        if (requestCode == REQUEST_SHIZUKU) {
            runOnUiThread(() -> {
                if (shizukuService != null) shizukuService.ensureIfAuthorized();
                notifyHostStateChangedAfterBinderCallback();
            });
        }
    };
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long startupStartedAt = SystemClock.elapsedRealtime();
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // 从 ACTION_DOWN 就请求触摸升帧；Pager 进入滚动后还会对 ComposeView 继续投 HIGH 票。
            getWindow().setFrameRateBoostOnTouchEnabled(true);
        }
        splashScreen.setOnExitAnimationListener(provider -> {
            View splashView = provider.getView();
            // Keep the opaque overlay, not the window's pre-draw: nested ComposeViews must
            // keep receiving frames so the fetched status can reach their composition.
            new Runnable() {
                @Override public void run() {
                    if (isFinishing() || isDestroyed()) {
                        provider.remove();
                        return;
                    }
                    boolean pending = currentSection == SECTION_DASHBOARD && selectedPlugin == null
                            && startupWidgetEntries.stream().anyMatch(
                                    com.androidtoolsuite.app.plugin.runtime.WidgetSnapshotStore.Entry::isAwaitingFirstPresentation);
                    if (firstFrameGate.keepOnScreen(SystemClock.uptimeMillis(), pending)) {
                        splashView.postOnAnimation(this);
                        return;
                    }
                    splashView.animate()
                            .alpha(0f)
                            .scaleX(1.04f)
                            .scaleY(1.04f)
                            .setDuration(260L)
                            .setInterpolator(new DecelerateInterpolator())
                            .withEndAction(provider::remove)
                            .start();
                }
            }.run();
        });
        pluginRuntime = PluginRuntime.get(this);
        pluginPackageStore = pluginRuntime.packages();
        storageService = pluginRuntime.storage();
        datasetService = pluginRuntime.datasets();
        backgroundTaskRegistry = pluginRuntime.backgroundTasks();
        permissionManager = pluginRuntime.permissions();
        capabilityRouter = pluginRuntime.capabilities();
        nativeProviderManager = pluginRuntime.nativeProviders();
        schedulerService = pluginRuntime.scheduler();
        shizukuService = pluginRuntime.shizuku();
        debugStartup("plugin-runtime-ready", startupStartedAt);
        try {
            providerRegistrations = HostCapabilityProviders.registerActivityCapabilities(
                    this, this, capabilityRouter
            );
            permissionRegistration = permissionManager.addListener((pluginId, capabilityId, granted) ->
                    runOnUiThread(() -> {
                        if (!granted && "file.import".equals(capabilityId)
                                && pendingRuntimeFilePick != null
                                && pendingRuntimeFilePick.pluginId.equals(pluginId)) {
                            PendingRuntimeFilePick pending = pendingRuntimeFilePick;
                            pendingRuntimeFilePick = null;
                            pending.result.completeExceptionally(new CapabilityFailure(
                                    "PERMISSION_DENIED",
                                    "文件选择已因插件权限撤销而取消",
                                    false
                            ));
                        }
                        if (!granted && "file.export".equals(capabilityId)
                                && pendingRuntimeFileExport != null
                                && pendingRuntimeFileExport.pluginId.equals(pluginId)) {
                            PendingRuntimeFileExport pending = pendingRuntimeFileExport;
                            pendingRuntimeFileExport = null;
                            pending.result.completeExceptionally(new CapabilityFailure(
                                    "PERMISSION_DENIED",
                                    "文件保存已因插件权限撤销而取消",
                                    false
                            ));
                        }
                        notifyHostStateChanged();
                    })
            );
        } catch (CapabilityFailure error) {
            throw new IllegalStateException("插件运行时 host capability registration failed", error);
        }
        builtInPluginStateStore = new BuiltInPluginStateStore(this);
        updateClient = new UpdateClient(this);
        uiPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        homeWidgetInstances = new HomeWidgetInstances(uiPreferences);
        syncSuiteThemePreferences();
        // 用户看到主界面前把全部插件准备好，避免把类加载抖动摊到打开后的几秒和首次切页。
        loadPlugins();
        debugStartup("plugins-loaded", startupStartedAt);
        setContentView(createContentView());
        debugStartup("content-view-set", startupStartedAt);
        getOnBackPressedDispatcher().addCallback(this, appBackCallback);
        if (BuildConfig.DEBUG) {
            debugInstance = new WeakReference<>(this);
        }

        Shizuku.addBinderReceivedListener(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        shizukuStateRegistration = shizukuService.addStateListener(() -> runOnUiThread(
                this::notifyHostStateChangedAfterBinderCallback
        ));

        showDashboard();
        if (BuildConfig.DEBUG && getIntent().hasExtra(EXTRA_DEBUG_DESTINATION)) {
            getWindow().getDecorView().postDelayed(() -> {
                applyDebugDestination(getIntent());
                debugStartup("destination-applied", startupStartedAt);
            }, 500L);
        } else {
            applyDebugDestination(getIntent());
            debugStartup("destination-applied", startupStartedAt);
        }
        ensureShellServiceIfAuthorized();
        notifyHostStateChanged();
    }

    private static void debugStartup(String stage, long startedAt) {
        if (BuildConfig.DEBUG) {
            Log.d("AtsStartup", stage + " +" + (SystemClock.elapsedRealtime() - startedAt) + "ms");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ensureShellServiceIfAuthorized();
        notifyHostStateChangedAfterBinderCallback();
        // Start only visible live widget reads before Compose draws their first frame.
        if (currentSection == SECTION_DASHBOARD && selectedPlugin == null) {
            startupWidgetEntries = com.androidtoolsuite.app.plugin.runtime.RuntimeHomeWidgets.prepare(widgetsForUi());
        }
        schedulerService.onHostStartedAsync();
        if (autoCheckUpdatesForUi() || autoCheckAppUpdatesForUi()) {
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
        clearPendingMigrationBridgePassword();
        clearPendingMigrationBridgeImportPassword();
        destroyMigrationBridgePlugins(migrationBridgeOwnedPlugins);
        destroyMigrationBridgePlugins(pendingMigrationBridgeOwnedPlugins);
        destroyMigrationBridgePlugins(migrationBridgeImportOwnedPlugins);
        destroyMigrationBridgePlugins(migrationBridgeDeleteOwnedPlugins);
        migrationBridgeExecutor.shutdownNow();
        runtimeFileExecutor.shutdownNow();
        if (pendingRuntimeFilePick != null) {
            pendingRuntimeFilePick.result.completeExceptionally(
                    new CapabilityFailure("CANCELLED", "Activity was destroyed", true)
            );
            pendingRuntimeFilePick = null;
        }
        if (pendingRuntimeFileExport != null) {
            pendingRuntimeFileExport.result.completeExceptionally(
                    new CapabilityFailure("CANCELLED", "Activity was destroyed", true)
            );
            pendingRuntimeFileExport = null;
        }
        for (HostTool plugin : plugins) {
            plugin.onDestroy();
        }
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        if (shizukuStateRegistration != null) {
            try { shizukuStateRegistration.close(); } catch (Exception ignored) { }
            shizukuStateRegistration = null;
        }
        if (debugInstance.get() == this) {
            debugInstance.clear();
        }
        for (int index = providerRegistrations.size() - 1; index >= 0; index--) {
            try {
                providerRegistrations.get(index).close();
            } catch (Exception ignored) {
            }
        }
        if (permissionRegistration != null) {
            try {
                permissionRegistration.close();
            } catch (Exception ignored) {
            }
            permissionRegistration = null;
        }
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
        if (managedPluginId != null) {
            if (managementReturnsToTool) { managedPluginId = null; managementReturnsToTool = false; }
            else if (!"detail".equals(managedPluginPage)) managedPluginPage = "detail";
            else managedPluginId = null;
            invalidateComposeUi();
            return true;
        }
        if (selectedPlugin != null) {
            if (selectedPlugin.onBackRequested()) return true;
            closePluginForUi();
            return true;
        }
        // 关于是设置的子页，返回回到设置；设置本身是底栏一级分区，返回直接回主页。
        if (currentSection == 7) {
            if (selectedTaskId != null) setSelectedTaskForUi(null);
            else {
                currentSection = taskReturnSection;
                selectedPlugin = findPlugin(taskReturnPluginId);
                if (selectedPlugin != null) selectedPlugin.onSelected();
                invalidateComposeUi();
            }
            return true;
        }
        if (currentSection == 9) { showAboutForUi(); return true; }
        if (currentSection == 5 && dataOwnerId != null && dataReturnPluginId == null) {
            setDataOwnerForUi(null);
            return true;
        }
        if (currentSection == 5 && dataReturnPluginId != null) {
            managedPluginId = dataReturnPluginId;
            managedPluginPage = "detail";
            dataReturnPluginId = null;
            currentSection = SECTION_PLUGINS;
            invalidateComposeUi();
            return true;
        }
        if (currentSection == SECTION_ABOUT || (currentSection >= 5 && currentSection != SECTION_STORE)) {
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
        return managedPluginId != null || selectedPlugin != null || currentSection != SECTION_DASHBOARD;
    }

    public void handleBackForUi() {
        handleAppBack();
    }

    public static void notifyDebugStateChanged() {
        MainActivity activity = debugInstance.get();
        if (!BuildConfig.DEBUG || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        activity.runOnUiThread(() -> {
            for (HostTool plugin : activity.plugins) activity.invalidatedToolPages.add(plugin.id());
            activity.reloadPlugins(activity.selectedPlugin == null ? null : activity.selectedPlugin.id());
        });
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
            HostTool plugin = findPlugin(destination.substring("plugin:".length()));
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

    private void openPlugin(HostTool plugin) {
        relatedToolBackStack.clear();
        managedPluginId = null;
        pluginReturnSection = currentSection;
        selectedPlugin = plugin;
        plugin.onSelected();
        invalidateComposeUi();
    }

    private void showPluginManager() {
        discoverPlugins = false;
        currentSection = SECTION_PLUGINS;
        selectedPlugin = null;
        invalidateComposeUi();
    }

    private List<WidgetRegistration> collectWidgets() {
        List<WidgetRegistration> widgets = new ArrayList<>();
        List<PluginPackageStore.InstalledPlugin> installed = pluginPackageStore.load();
        homeWidgetInstances.ensureLegacy(installed);
        Set<String> runtimeIds = new HashSet<>();
        Set<String> retainedViews = new HashSet<>();
        for (PluginPackageStore.InstalledPlugin item : installed) runtimeIds.add(item.manifest.plugin.id);
        for (HomeWidgetInstances.Instance instance : homeWidgetInstances.all()) {
            PluginPackageStore.InstalledPlugin item = installed.stream().filter(p -> p.manifest.plugin.id.equals(instance.pluginId)).findFirst().orElse(null);
            if (instance.migrating && widgetMigrationAttempts.add(instance.pluginId + ":" + instance.id)) com.androidtoolsuite.app.plugin.runtime.RuntimeHomeWidgets.migrateInstance(item, instance, this);
            String cacheKey = instance.pluginId + ":" + instance.id + ":" + instance.revision + ":" + instance.migrating + ":" + (item == null ? "absent" : item.generationDirectory.getPath() + item.enabled);
            retainedViews.add(cacheKey);
            HostHomeWidget widget = instanceWidgetViews.computeIfAbsent(cacheKey, ignored -> com.androidtoolsuite.app.plugin.runtime.RuntimeHomeWidgets.createInstance(item, instance, this));
            widgets.add(new WidgetRegistration(instance.title, instance.pluginId + ":" + instance.id, widget));
        }
        for (HostTool plugin : plugins) {
            if (runtimeIds.contains(plugin.id())) continue;
            for (HostHomeWidget widget : plugin.createHomeWidgets(this, this)) {
                widgets.add(new WidgetRegistration(plugin.title(), plugin.id() + ":" + widget.id(), widget));
            }
        }
        instanceWidgetViews.keySet().retainAll(retainedViews);
        return widgets;
    }

    private List<HostTool> orderedTools(boolean includeHidden) {
        List<HostTool> ordered = new ArrayList<>(plugins);
        List<String> ids = new ArrayList<>();
        for (HostTool plugin : ordered) {
            ids.add(plugin.id());
        }
        List<String> savedOrder = readOrder(PREF_TOOL_ORDER, ids);
        ordered.sort((left, right) -> Integer.compare(savedOrder.indexOf(left.id()), savedOrder.indexOf(right.id())));
        if (includeHidden) {
            return ordered;
        }
        Set<String> hidden = uiPreferences.getStringSet(PREF_HIDDEN_TOOLS, new LinkedHashSet<>());
        List<HostTool> visible = new ArrayList<>();
        for (HostTool plugin : ordered) {
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
        Map<String, HostTool> previous = new LinkedHashMap<>();
        for (HostTool plugin : plugins) previous.put(plugin.id(), plugin);
        pluginRuntime.widgetSnapshots().reconcile(pluginPackageStore.load());
        plugins.clear();
        runtimeInstalledCache.clear();
        optionalBuiltInPluginIds.clear();
        pluginRuntime.workerProviders().sync(pluginPackageStore.load());
        loadBuiltInPlugins();
        plugins.addAll(createInstalledPlugins(plugins, previous));
        for (HostTool plugin : previous.values()) if (!plugins.contains(plugin)) plugin.onDestroy();
        invalidatedToolPages.clear();
    }

    private void loadBuiltInPlugins() {
        LinkedHashMap<String, String> activeVersions = new LinkedHashMap<>();
        for (HostTool plugin : ToolRegistry.createRequiredBuiltInPlugins()) {
            if (areDependenciesSatisfied(plugin.dependencies(), activeVersions)) {
                plugins.add(plugin);
                activeVersions.put(plugin.id(), plugin.version());
            } else {
                plugin.onDestroy();
            }
        }
        for (HostTool plugin : ToolRegistry.createOptionalBuiltInPlugins()) {
            optionalBuiltInPluginIds.add(plugin.id());
            if (builtInPluginStateStore.isEnabled(plugin.id()) && areDependenciesSatisfied(plugin.dependencies(), activeVersions)) {
                plugins.add(plugin);
                activeVersions.put(plugin.id(), plugin.version());
            } else {
                plugin.onDestroy();
            }
        }
    }

    private List<HostTool> createInstalledPlugins(List<HostTool> activePlugins, Map<String, HostTool> previous) {
        List<HostTool> result = new ArrayList<>();
        LinkedHashMap<String, String> activeVersions = new LinkedHashMap<>();
        for (HostTool plugin : activePlugins) {
            activeVersions.put(plugin.id(), plugin.version());
        }
        List<PluginPackageStore.InstalledPlugin> pendingRuntimePlugins = new ArrayList<>(pluginPackageStore.load());
        for (PluginPackageStore.InstalledPlugin installed : pendingRuntimePlugins) {
            runtimeInstalledCache.put(installed.manifest.plugin.id, installed);
        }
        boolean loadedPlugin;
        do {
            loadedPlugin = false;
            for (int index = pendingRuntimePlugins.size() - 1; index >= 0; index--) {
                PluginPackageStore.InstalledPlugin installed = pendingRuntimePlugins.get(index);
                RuntimePluginManifest manifest = installed.manifest;
                String pluginId = manifest.plugin.id;
                if (!installed.enabled
                        || activeVersions.containsKey(pluginId)) {
                    pendingRuntimePlugins.remove(index);
                } else if (!manifest.providerEntries.isEmpty()
                        && !nativeProviderManager.isActive(pluginId, installed.generationDirectory.getName())) {
                    // A Provider is active only after this exact verified generation loaded at cold start.
                    pendingRuntimePlugins.remove(index);
                } else if (manifest.capabilityContributions.stream()
                        .anyMatch(item -> !item.workerEntry.isEmpty())
                        && !pluginRuntime.workerProviders().isActive(
                                pluginId, installed.generationDirectory.getName())) {
                    pendingRuntimePlugins.remove(index);
                } else if (manifest.toolContributions.isEmpty()
                        && areRuntimeRequirementsSatisfied(manifest, activeVersions)) {
                    activeVersions.put(pluginId, manifest.plugin.version);
                    pendingRuntimePlugins.remove(index);
                    loadedPlugin = true;
                } else if (areRuntimeRequirementsSatisfied(manifest, activeVersions)) {
                    RuntimePluginManifest.UiEntry uiEntry = manifest.defaultUiEntry();
                    HostTool plugin = previous.get(pluginId);
                    PluginPackageStore.InstalledPlugin old = plugin instanceof DeclarativeToolPlugin
                            ? ((DeclarativeToolPlugin) plugin).getInstalled()
                            : plugin instanceof WebToolPlugin ? ((WebToolPlugin) plugin).getInstalled() : null;
                    if (old == null || invalidatedToolPages.contains(pluginId)
                            || !old.generationDirectory.equals(installed.generationDirectory)) {
                        plugin = "declarative".equals(uiEntry.type)
                                ? new DeclarativeToolPlugin(installed, this)
                                : new WebToolPlugin(installed, this);
                    }
                    result.add(plugin);
                    activeVersions.put(plugin.id(), plugin.version());
                    pendingRuntimePlugins.remove(index);
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
        List<String> returnIds = relatedToolBackStack.stream().map(HostTool::id).collect(java.util.stream.Collectors.toList());
        loadPlugins();
        relatedToolBackStack.clear();
        for (String id : returnIds) {
            HostTool previous = findPlugin(id);
            if (previous != null) relatedToolBackStack.add(previous);
        }
        selectedPlugin = findPlugin(preferredPluginId);
        if (selectedPlugin != null) {
            selectedPlugin.onSelected();
        } else if (preferredPluginId != null && !relatedToolBackStack.isEmpty()) {
            selectedPlugin = relatedToolBackStack.remove(relatedToolBackStack.size() - 1);
            selectedPlugin.onSelected();
        }
        // Preserve detail, discovery tab, return section and data-task context across reloads.
        invalidateComposeUi();
    }

    /** 重载插件集合，但不把刚安装的插件当成导航目标。 */
    private void reloadPluginsKeepingCurrentPage() {
        reloadPlugins(selectedPlugin == null ? null : selectedPlugin.id());
    }

    private HostTool findPlugin(String pluginId) {
        if (pluginId == null) {
            return null;
        }
        for (HostTool plugin : plugins) {
            if (plugin.id().equals(pluginId)) {
                return plugin;
            }
        }
        return null;
    }

    private void notifyHostStateChanged() {
        // Home widgets are owned by plugins too. Notify every active plugin so a dashboard widget
        // observes provider connections without requiring the user to leave and re-enter Home.
        for (HostTool plugin : new ArrayList<>(plugins)) {
            try {
                plugin.onHostStateChanged();
            } catch (RuntimeException error) {
                Log.w("AtsHostState", "Plugin state callback failed: " + plugin.id(), error);
            }
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

    public HomeWidgetInstances widgetInstancesForUi() { return homeWidgetInstances; }
    public List<PluginPackageStore.InstalledPlugin> widgetPluginsForUi() { return pluginPackageStore.load(); }
    public String widgetSettingsPluginForUi() { return widgetSettingsPluginId; }
    public String widgetSettingsInstanceForUi() { return widgetSettingsInstanceId; }
    public void openWidgetSettingsForUi(String pluginId, String instanceId) {
        if (instanceId != null && homeWidgetInstances.find(pluginId, instanceId) == null) throw new IllegalArgumentException("小部件不属于此插件");
        widgetSettingsPluginId = pluginId == null ? "" : pluginId;
        widgetSettingsInstanceId = instanceId;
        invalidateComposeUi();
    }
    public void closeWidgetSettingsForUi() { widgetSettingsPluginId = null; widgetSettingsInstanceId = null; invalidateComposeUi(); }
    public void widgetSavedForUi(HomeWidgetInstances.Instance instance, String size) {
        Set<String> sizes = new LinkedHashSet<>(uiPreferences.getStringSet(PREF_WIDGET_SIZES, Collections.emptySet()));
        sizes.removeIf(item -> item.startsWith(instance.pluginId + ":" + instance.id + "="));
        sizes.add(instance.pluginId + ":" + instance.id + "=" + size);
        uiPreferences.edit().putStringSet(PREF_WIDGET_SIZES, sizes).apply();
        invalidateComposeUi();
    }
    public boolean isWidgetInstanceVisibleForUi(String pluginId,String id) { return isWidgetVisible(pluginId+":"+id); }
    public void setWidgetInstanceVisibleForUi(String pluginId,String id,boolean visible) {
        if(homeWidgetInstances.find(pluginId,id)==null)throw new IllegalArgumentException("小部件不存在");
        setWidgetVisible(pluginId+":"+id,visible);invalidateComposeUi();
    }
    public void removeWidgetInstanceForUi(String pluginId,String id,long revision) {
        homeWidgetInstances.remove(pluginId,id,revision);
        pluginRuntime.widgetSnapshots().forgetInstance(pluginId,id);
        instanceWidgetViews.keySet().removeIf(key->key.startsWith(pluginId+":"+id+":"));
        invalidateComposeUi();
    }
    public void requestRemoveWidgetForUi(HostHomeWidget widget) {
        HomeWidgetInstances.Instance instance=homeWidgetInstances.find(widget.pluginId(),widget.id());
        if(instance==null){showToast("此卡片暂不支持删除实例");return;}
        showComposeDialog("移除“"+instance.title+"”？","只删除这张卡片的配置与布局，插件、账号和业务数据保留。之后可重新添加。","取消","移除",()->{
            try{removeWidgetInstanceForUi(instance.pluginId,instance.id,instance.revision);showToast("已移除小部件");}catch(RuntimeException error){showToast(error.getMessage());}
        });
    }
    public boolean isToolPinnedForUi(String pluginId) { return pinnedToolIdsForUi().contains(pluginId); }
    private String openCollectionFolder;
    private String collectionEditorScope;
    private String collectionEditorId;
    private String collectionMoveScope;
    private String collectionMoveMember;
    private String collectionFolderError;

    public List<HostFolders.Folder> foldersForUi(String scope) {
        try {
            List<HostFolders.Folder> folders = HostFolders.decode(new JSONArray(uiPreferences.getString("collection_folders", "[]")));
            collectionFolderError = null;
            return folders.stream().filter(folder -> folder.scope.equals(scope)).collect(java.util.stream.Collectors.toList());
        } catch (JSONException error) {
            collectionFolderError = "文件夹布局无法读取，请从数据管理恢复有效的应用设置。";
            return Collections.emptyList();
        }
    }
    public String folderErrorForUi() { return collectionFolderError; }
    private String homeGridError;
    public String gridErrorForUi() { return homeGridError; }
    public JSONObject gridPositionsForUi(String scope) {
        try {
            JSONObject layouts=new JSONObject(uiPreferences.getString("home_grid_layout","{}"));HomeGridLayout.validate(layouts);
            homeGridError=null;JSONObject cells=layouts.optJSONObject(scope);return cells==null?new JSONObject():new JSONObject(cells.toString());
        }catch(JSONException error){homeGridError="网格布局无法读取，原布局已保留，可从数据管理恢复应用设置。";return new JSONObject();}
    }
    public void saveGridPositionsForUi(String scope,JSONObject cells,boolean undo) {
        try {
            HomeGridLayout.decodeCells(cells);
            String previous=uiPreferences.getString("home_grid_layout","{}");JSONObject layouts=new JSONObject(previous);HomeGridLayout.validate(layouts);
            JSONObject merged=layouts.optJSONObject(scope)==null?new JSONObject():new JSONObject(layouts.getJSONObject(scope).toString());
            java.util.Iterator<String> keys=cells.keys();while(keys.hasNext()){String key=keys.next();merged.put(key,cells.getJSONObject(key));}
            layouts.put(scope,merged);HomeGridLayout.validate(layouts);String updated=layouts.toString();if(previous.equals(updated))return;
            if(!uiPreferences.edit().putString("home_grid_layout",updated).commit())throw new IOException("无法保存网格");
            if(undo){enqueueSnackbar("布局已调整","撤销");snackbarCallback=()->{uiPreferences.edit().putString("home_grid_layout",previous).commit();invalidateComposeUi();};}
            invalidateComposeUi();
        }catch(JSONException|IOException error){showToast("无法保存网格，原布局保留。");}
    }
    public String openFolderForUi() { return openCollectionFolder; }
    public void openFolderForUi(String id) { openCollectionFolder = id; invalidateComposeUi(); }
    public String folderEditorScopeForUi() { return collectionEditorScope; }
    public String folderEditorIdForUi() { return collectionEditorId; }
    public void editFolderForUi(String scope, String id) {
        collectionEditorScope=scope; collectionEditorId=id; invalidateComposeUi();
    }
    public String folderMoveScopeForUi() { return collectionMoveScope; }
    public String folderMoveMemberForUi() { return collectionMoveMember; }
    public void moveToFolderForUi(String scope, String member) {
        collectionMoveScope=scope; collectionMoveMember=member; invalidateComposeUi();
    }
    public void closeFolderEditorForUi() {
        collectionEditorScope=null; collectionEditorId=null; collectionMoveScope=null; collectionMoveMember=null; invalidateComposeUi();
    }
    public boolean saveFolderForUi(String scope, String id, String name, List<String> members) {
        try {
            List<HostFolders.Folder> current=HostFolders.decode(new JSONArray(uiPreferences.getString("collection_folders","[]")));
            List<HostFolders.Folder> updated=HostFolders.save(current,id,name,scope,members);
            SharedPreferences.Editor editor=uiPreferences.edit().putString("collection_folders",HostFolders.encode(updated).toString());
            if (HostFolders.TOOLS.equals(scope)) {
                Set<String> hidden=new LinkedHashSet<>(uiPreferences.getStringSet(PREF_HIDDEN_TOOLS,new LinkedHashSet<>()));
                hidden.removeAll(members);editor.putStringSet(PREF_HIDDEN_TOOLS,hidden);
            }
            if(!editor.commit()) throw new IOException("无法保存文件夹");
            closeFolderEditorForUi();invalidateComposeUi();return true;
        }catch(JSONException|IOException|IllegalArgumentException error){showToast("无法保存文件夹，请检查名称与成员。");return false;}
    }
    public void moveFolderMemberForUi(String scope, String member, String folder) {
        try {
            List<HostFolders.Folder> current=HostFolders.decode(new JSONArray(uiPreferences.getString("collection_folders","[]")));
            SharedPreferences.Editor editor=uiPreferences.edit().putString("collection_folders",HostFolders.encode(HostFolders.move(current,scope,member,folder)).toString());
            String previousFolders=HostFolders.encode(current).toString(),previousGrid=uiPreferences.getString("home_grid_layout","{}");
            Set<String> previousHidden=new LinkedHashSet<>(uiPreferences.getStringSet(PREF_HIDDEN_TOOLS,new LinkedHashSet<>()));
            if(HostFolders.HOME.equals(scope)) {
                JSONObject layouts=new JSONObject(previousGrid);HomeGridLayout.validate(layouts);
                String targetScope=folder==null?"home":"home:"+folder;
                JSONObject positions=layouts.optJSONObject(targetScope);if(positions!=null)positions.remove(member);
                editor.putString("home_grid_layout",layouts.toString());
            }
            if (HostFolders.TOOLS.equals(scope) && folder != null) {
                Set<String> hidden=new LinkedHashSet<>(uiPreferences.getStringSet(PREF_HIDDEN_TOOLS,new LinkedHashSet<>()));
                hidden.remove(member);editor.putStringSet(PREF_HIDDEN_TOOLS,hidden);
            }
            if(!editor.commit()) throw new IOException("无法保存布局");
            closeFolderEditorForUi();
            enqueueSnackbar(folder==null?"已移回主页面":"已移入文件夹","撤销");
            snackbarCallback=()->{uiPreferences.edit().putString("collection_folders",previousFolders).putString("home_grid_layout",previousGrid).putStringSet(PREF_HIDDEN_TOOLS,previousHidden).commit();invalidateComposeUi();};
            invalidateComposeUi();
        }catch(JSONException|IOException|IllegalArgumentException error){showToast("无法调整文件夹，原有布局保留。");}
    }
    public void dissolveFolderForUi(String id) {
        try {
            List<HostFolders.Folder> current=HostFolders.decode(new JSONArray(uiPreferences.getString("collection_folders","[]")));
            if(!uiPreferences.edit().putString("collection_folders",HostFolders.encode(HostFolders.dissolve(current,id)).toString()).commit()) throw new IOException("无法保存布局");
            if(id.equals(openCollectionFolder))openCollectionFolder=null;
            showToast("文件夹已解散，成员保留");invalidateComposeUi();
        }catch(JSONException|IOException error){showToast("无法调整文件夹，原有布局保留。");}
    }
    public List<String> collectionOrderForUi(String scope, List<String> available) {
        String key=HostFolders.HOME.equals(scope)?PREF_WIDGET_ORDER:PREF_TOOL_ORDER;
        List<String> result=new ArrayList<>(lines(uiPreferences.getString(key,"")));
        result.removeIf(id->!available.contains(id));
        for(String id:available)if(!result.contains(id))result.add(id);
        return result;
    }
    public void moveCollectionItemForUi(String scope, String id, String target, List<String> available) {
        List<String> order=collectionOrderForUi(scope,available);
        int from=order.indexOf(id),to=order.indexOf(target);if(from<0||to<0||from==to)return;
        order.remove(from);order.add(Math.min(to,order.size()),id);
        String key=HostFolders.HOME.equals(scope)?PREF_WIDGET_ORDER:PREF_TOOL_ORDER;
        List<String> previous=new ArrayList<>(lines(uiPreferences.getString(key,"")));
        List<String> complete=new ArrayList<>();
        java.util.Iterator<String> updated=order.iterator();
        for(String old:previous) {
            if(available.contains(old)){if(updated.hasNext())complete.add(updated.next());}
            else complete.add(old);
        }
        while(updated.hasNext())complete.add(updated.next());
        saveOrder(key,complete);invalidateComposeUi();
        offerCollectionUndoForUi(scope,previous);
    }
    public void offerCollectionUndoForUi(String scope,List<String> order) {
        enqueueSnackbar("排列已调整","撤销");
        snackbarCallback=()->{saveOrder(HostFolders.HOME.equals(scope)?PREF_WIDGET_ORDER:PREF_TOOL_ORDER,order);invalidateComposeUi();};
        invalidateComposeUi();
    }
    public List<String> pinnedToolIdsForUi() { return new ArrayList<>(lines(uiPreferences.getString("pinned_tools", ""))); }
    public List<HostTool> pinnedToolsForUi() {
        List<HostTool> result = new ArrayList<>();
        for (String id : pinnedToolIdsForUi()) { HostTool tool = findPlugin(id); if (tool != null) result.add(tool); }
        return result;
    }
    public void setToolPinnedForUi(String id, boolean pinned) {
        List<String> ids = pinnedToolIdsForUi(); ids.remove(id); if (pinned) ids.add(id);
        saveOrder("pinned_tools", ids); invalidateComposeUi();
    }
    public void movePinnedToolForUi(String id, int direction) {
        List<String> ids = pinnedToolIdsForUi(); int index = ids.indexOf(id), target = index + direction;
        if (index >= 0 && target >= 0 && target < ids.size()) { Collections.swap(ids,index,target); saveOrder("pinned_tools",ids); invalidateComposeUi(); }
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

    public HostTool selectedPluginForUi() {
        return selectedPlugin;
    }

    public List<HostTool> pluginsForUi() {
        return orderedTools(false);
    }

    public List<HostTool> allToolsForUi() {
        return orderedTools(true);
    }

    public HostTool findToolForUi(String pluginId) {
        return findPlugin(pluginId);
    }

    public boolean isToolVisibleForUi(HostTool plugin) {
        return isToolVisibleByIdForUi(plugin.id());
    }
    public boolean isToolVisibleByIdForUi(String pluginId) {
        return !uiPreferences.getStringSet(PREF_HIDDEN_TOOLS, new LinkedHashSet<>()).contains(pluginId);
    }
    public boolean hasDeclaredHomeWidgetsForUi(String pluginId) {
        PluginPackageStore.InstalledPlugin installed = findRuntimePlugin(pluginId);
        return installed != null && !installed.manifest.homeWidgetContributions.isEmpty();
    }
    public boolean isPluginHomeVisibleByIdForUi(String pluginId) {
        PluginPackageStore.InstalledPlugin installed = findRuntimePlugin(pluginId);
        return installed != null && !installed.manifest.homeWidgetContributions.isEmpty()
                && installed.manifest.homeWidgetContributions.stream().allMatch(widget -> isWidgetVisible(pluginId + ":" + widget.id));
    }
    public void setPluginHomeVisibleByIdForUi(String pluginId, boolean visible) {
        PluginPackageStore.InstalledPlugin installed = findRuntimePlugin(pluginId);
        if (installed == null) return;
        for (RuntimePluginManifest.HomeWidgetContribution widget : installed.manifest.homeWidgetContributions) setWidgetVisible(pluginId + ":" + widget.id, visible);
        invalidateComposeUi();
    }

    /**
     * 这个插件能不能被停用。
     *
     * 必需内置插件（宿主自身能力）不行；可选内置插件和外部插件都行。工具页的长按菜单据此决定
     * 要不要显示「停用插件」——把一个停不掉的开关摆出来只会让人以为功能坏了。
     */
    public boolean canDisablePluginForUi(HostTool plugin) {
        return runtimeInstalledCache.containsKey(plugin.id())
                || optionalBuiltInPluginIds.contains(plugin.id());
    }

    /** 停用插件，自动分派到内置或外部两条通道。依赖校验与提示由被调用方负责。 */
    public void disablePluginForUi(HostTool plugin) {
        if (findRuntimePlugin(plugin.id()) != null) {
            setImportedPluginEnabled(plugin.id(), false);
        } else if (canDisablePluginForUi(plugin)) {
            setBuiltInPluginEnabled(plugin.id(), false);
        }
    }

    public void setToolVisibleForUi(HostTool plugin, boolean visible) {
        setToolVisibleByIdForUi(plugin.id(), visible);
    }
    public void setToolVisibleByIdForUi(String pluginId, boolean visible) {
        Set<String> hidden = new LinkedHashSet<>(uiPreferences.getStringSet(PREF_HIDDEN_TOOLS, new LinkedHashSet<>()));
        if (visible) hidden.remove(pluginId); else hidden.add(pluginId);
        SharedPreferences.Editor editor=uiPreferences.edit().putStringSet(PREF_HIDDEN_TOOLS, hidden);
        if (!visible) try {
            List<HostFolders.Folder> current=HostFolders.decode(new JSONArray(uiPreferences.getString("collection_folders","[]")));
            editor.putString("collection_folders",HostFolders.encode(HostFolders.move(current,HostFolders.TOOLS,pluginId,null)).toString());
        } catch (JSONException | IllegalArgumentException error) { showToast("文件夹布局无法读取，原有布局保留。");return; }
        if(!editor.commit()) { showToast("无法保存工具收纳状态，请重试。");return; }
        invalidateComposeUi();
    }

    public void moveToolToUi(String pluginId, String targetPluginId) {
        List<HostTool> tools = orderedTools(true);
        int index = -1;
        int target = -1;
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).id().equals(pluginId)) index = i;
            if (tools.get(i).id().equals(targetPluginId)) target = i;
        }
        if (index < 0 || target < 0 || target == index) return;
        HostTool moved = tools.remove(index);
        tools.add(Math.max(0, Math.min(tools.size(), target)), moved);
        List<String> ids = new ArrayList<>();
        for (HostTool tool : tools) ids.add(tool.id());
        saveOrder(PREF_TOOL_ORDER, ids);
        invalidateComposeUi();
    }

    public void restoreToolOrderForUi(List<String> ids) {
        saveOrder(PREF_TOOL_ORDER, ids);
        invalidateComposeUi();
    }

    public List<HostHomeWidget> widgetsForUi() {
        List<HostHomeWidget> result = new ArrayList<>();
        for (WidgetRegistration registration : orderedWidgets(false)) {
            result.add(registration.widget);
        }
        return result;
    }

    public List<HostHomeWidget> allWidgetsForUi() {
        List<HostHomeWidget> result = new ArrayList<>();
        for (WidgetRegistration registration : orderedWidgets(true)) {
            result.add(registration.widget);
        }
        return result;
    }

    public boolean isWidgetVisibleForUi(HostHomeWidget widget) {
        return isWidgetVisible(widget.pluginId() + ":" + widget.id());
    }

    public void setWidgetVisibleForUi(HostHomeWidget widget, boolean visible) {
        setWidgetVisible(widget.pluginId() + ":" + widget.id(), visible);
        invalidateComposeUi();
    }

    public boolean hasHomeWidgetsForUi(HostTool plugin) {
        return !plugin.createHomeWidgets(this, this).isEmpty();
    }

    public boolean isPluginHomeVisibleForUi(HostTool plugin) {
        List<HostHomeWidget> widgets = allWidgetsForUi().stream().filter(w -> w.pluginId().equals(plugin.id())).collect(java.util.stream.Collectors.toList());
        if (widgets.isEmpty()) return false;
        for (HostHomeWidget widget : widgets) {
            if (!isWidgetVisible(widget.pluginId() + ":" + widget.id())) return false;
        }
        return true;
    }

    public void setPluginHomeVisibleForUi(HostTool plugin, boolean visible) {
        for (HostHomeWidget widget : allWidgetsForUi().stream().filter(w -> w.pluginId().equals(plugin.id())).collect(java.util.stream.Collectors.toList())) {
            setWidgetVisible(widget.pluginId() + ":" + widget.id(), visible);
        }
        invalidateComposeUi();
    }

    public void moveWidgetToUi(HostHomeWidget widget, HostHomeWidget targetWidget) {
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

    public int widgetWidthUnitsForUi(HostHomeWidget widget) {
        return currentWidgetSizeForUi(widget).widthUnits;
    }

    public int widgetHeightUnitsForUi(HostHomeWidget widget) {
        return currentWidgetSizeForUi(widget).heightUnits;
    }

    public void setWidgetSizeForUi(HostHomeWidget widget, int widthUnits, int heightUnits) {
        HomeWidgetSize requested = closestSupportedWidgetSize(widget, widthUnits, heightUnits);
        String key = widget.pluginId() + ":" + widget.id();
        Set<String> sizes = new LinkedHashSet<>(uiPreferences.getStringSet(PREF_WIDGET_SIZES, new LinkedHashSet<>()));
        sizes.removeIf(entry -> entry.startsWith(key + "="));
        sizes.add(key + "=" + requested.widthUnits + "x" + requested.heightUnits);
        uiPreferences.edit().putStringSet(PREF_WIDGET_SIZES, sizes).apply();
        invalidateComposeUi();
    }

    private HomeWidgetSize currentWidgetSizeForUi(HostHomeWidget widget) {
        int fallbackWidth = "@folder".equals(widget.pluginId()) ? 1 : legacyFullWidth(widget) ? 4 : 2;
        int savedWidth = widgetSizeForUi(widget, 0, fallbackWidth);
        int savedHeight = widgetSizeForUi(widget, 1, "@folder".equals(widget.pluginId()) ? 1 : 2);
        return closestSupportedWidgetSize(widget, savedWidth, savedHeight);
    }

    private HomeWidgetSize closestSupportedWidgetSize(HostHomeWidget widget, int width, int height) {
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

    private boolean legacyFullWidth(HostHomeWidget widget) {
        return uiPreferences.getStringSet(PREF_FULL_WIDTH_WIDGETS, new LinkedHashSet<>())
                .contains(widget.pluginId() + ":" + widget.id());
    }

    private int widgetSizeForUi(HostHomeWidget widget, int part, int fallback) {
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
        managedPluginId = null;
        relatedToolBackStack.clear();
        if (section == SECTION_DASHBOARD) showDashboard();
        else if (section == SECTION_PLUGINS) showPluginList();
        else if (section == SECTION_MANAGER) showPluginManager();
        else if (section == SECTION_STORE) showPluginRepositoryForUi();
        else if (section == SECTION_SETTINGS) showSettingsForUi();
        else if (section == SECTION_ABOUT) showAboutForUi();
        else if (section == 5 || section == 7 || section == 8 || section == 9) {
            currentSection = section;
            selectedPlugin = null;
            invalidateComposeUi();
        }
    }

    /** Pager 已经完成视觉切换时只同步返回栈语义，不再让五个缓存页面全部重组。 */
    public void setMainSectionFromPagerForUi(int section) {
        if(section==SECTION_MANAGER)section=SECTION_PLUGINS;
        if ((section < SECTION_DASHBOARD || section > SECTION_SETTINGS) && section != SECTION_STORE) return;
        currentSection = section;
        selectedPlugin = null;
    }

    public void openPluginForUi(HostTool plugin) {
        openPlugin(plugin);
    }

    public void openPluginForUi(String pluginId) {
        HostTool plugin = findPlugin(pluginId);
        if (plugin != null) {
            openPlugin(plugin);
        }
    }

    public void openWidgetForUi(HostHomeWidget widget) {
        HostTool plugin = findPlugin(widget.pluginId());
        if (plugin == null) {
            showPluginManagementForUi(widget.pluginId(), "detail");
            return;
        }
        HomeWidgetInstances.Instance instance = widgetInstancesForUi().find(widget.pluginId(), widget.id());
        if (instance != null) {
            try {
                plugin.onWidgetSelected(new JSONObject()
                    .put("id", java.util.UUID.randomUUID().toString())
                    .put("instanceId", instance.id).put("presetId", instance.presetId)
                    .put("configuration", new JSONObject(instance.configuration.toString())));
            } catch (org.json.JSONException error) {
                showToast("无法打开小部件内容，请重新配置");
                return;
            }
        }
        openPlugin(plugin);
    }

    public void closePluginForUi() {
        if (!relatedToolBackStack.isEmpty()) {
            HostTool previous = relatedToolBackStack.remove(relatedToolBackStack.size() - 1);
            selectedPlugin = plugins.contains(previous) ? previous : null;
            invalidateComposeUi();
            return;
        }
        selectedPlugin = null;
        currentSection = pluginReturnSection;
        invalidateComposeUi();
    }

    public boolean isRuntimeToolForUi(HostTool plugin) {
        return plugin instanceof WebToolPlugin || plugin instanceof DeclarativeToolPlugin;
    }

    public List<ImportedPluginDescriptor> importedDescriptorsForUi() {
        List<ImportedPluginDescriptor> result = new ArrayList<>();
        for (PluginPackageStore.InstalledPlugin installed : runtimeInstalledCache.values()) {
            result.add(toUiDescriptor(installed));
        }
        result.sort((left, right) -> left.title.compareToIgnoreCase(right.title));
        return result;
    }

    public void showPluginRepositoryForUi() {
        currentSection = SECTION_STORE;
        discoverPlugins = true;
        selectedPlugin = null;
        invalidateComposeUi();
    }

    public boolean discoverPluginsForUi() { return discoverPlugins; }
    public void setDiscoverPluginsForUi(boolean value) {
        discoverPlugins = value;
        invalidateComposeUi();
    }
    public String managedPluginIdForUi() { return managedPluginId; }
    public String managedPluginPageForUi() { return managedPluginPage; }
    public void showPluginManagementForUi(String pluginId, String page) {
        managementReturnsToTool = false;
        managedPluginId = pluginId;
        managedPluginPage = page;
        invalidateComposeUi();
    }
    public boolean openOwnPluginPermissionsForUi(String pluginId) {
        if (findRuntimePlugin(pluginId) != null) {
            showPluginManagementForUi(pluginId, "permissions");
            managementReturnsToTool = selectedPlugin != null;
            return true;
        }
        return false;
    }
    public boolean hasPluginReturnTargetForUi() { return !relatedToolBackStack.isEmpty(); }
    public void configurePluginBackForUi(String pluginId, String sessionId, boolean enabled) {
        HostTool tool = findPlugin(pluginId);
        if (tool != null) tool.setBackNavigation(sessionId, enabled);
    }
    public boolean isActivePluginSessionForUi(String pluginId, String sessionId) {
        return widgetSettingsPluginId == null && managedPluginId == null && selectedPlugin != null && selectedPlugin.id().equals(pluginId)
                && selectedPlugin.ownsSession(sessionId);
    }
    public void closePluginSessionForUi(String pluginId, String sessionId) {
        if (isActivePluginSessionForUi(pluginId, sessionId)) closePluginForUi();
    }
    public boolean openRelatedToolForUi(String pluginId, String toolId) {
        PluginPackageStore.InstalledPlugin installed = findRuntimePlugin(pluginId);
        if (installed != null && !installed.manifest.toolContributions.isEmpty()
                && !installed.manifest.toolContributions.get(0).id.equals(toolId)) {
            showToast("当前宿主只支持打开该插件的主工具");
            return false;
        }
        HostTool target = findPlugin(pluginId);
        if (target == null) {
            showPluginManagementForUi(pluginId, "detail");
            showToast("请先安装并启用此插件");
            return false;
        }
        if (target == selectedPlugin) return true;
        if (selectedPlugin != null) relatedToolBackStack.add(selectedPlugin);
        selectedPlugin = target;
        target.onSelected();
        invalidateComposeUi();
        return true;
    }
    public void showDataManagementForUi(String ownerId) {
        dataReturnPluginId = managedPluginId;
        dataOwnerId = null;
        navigateForUi(5);
    }
    public String dataOwnerForUi() { return dataOwnerId; }
    public void setDataOwnerForUi(String owner) { dataOwnerId = owner; invalidateComposeUi(); }
    public String dataQueryForUi() { return dataQuery; }
    public void setDataQueryForUi(String query) { dataQuery = query == null ? "" : query; invalidateComposeUi(); }
    public String selectedTaskForUi() { return selectedTaskId; }
    public void setSelectedTaskForUi(String id) { selectedTaskId = id; invalidateComposeUi(); }
    public void showTaskListForUi() { showTasksPageForUi(true); }
    private void showTasksPageForUi(boolean list) {
        if (currentSection != 7) {
            taskReturnSection = currentSection;
            taskReturnPluginId = selectedPlugin == null ? null : selectedPlugin.id();
        }
        if (list) selectedTaskId = null;
        managedPluginId = null;
        selectedPlugin = null;
        currentSection = 7;
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
        return updatePromptVisible && promptUpdateCountForUi() > 0;
    }
    public void showAvailableUpdatesForUi() {
        promptAppUpdates = false;
        promptPluginUpdates = true;
        updatePromptVisible = !availablePluginUpdatesForUi().isEmpty();
        invalidateComposeUi();
    }
    public UpdateCatalog.AppRelease promptAppUpdateForUi() { return promptAppUpdates ? appUpdateForUi() : null; }
    public List<UpdateCatalog.PluginRelease> promptPluginUpdatesForUi() { return promptPluginUpdates ? availablePluginUpdatesForUi() : Collections.emptyList(); }
    public int promptUpdateCountForUi() { return (promptAppUpdateForUi() == null ? 0 : 1) + promptPluginUpdatesForUi().size(); }
    public void installPromptUpdatesForUi() {
        updatePromptVisible = false;
        if (promptAppUpdateForUi() != null) installAppUpdateForUi();
        if (promptPluginUpdates) installAllPluginUpdatesForUi();
        invalidateComposeUi();
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
    public int snackbarRevisionForUi() { return snackbarRevision; }
    public Runnable consumeSnackbarCallbackForUi() {
        Runnable result = snackbarCallback;
        snackbarCallback = null;
        return result;
    }
    public void offerReorderUndoForUi(boolean widgets, List<String> previousOrder) {
        List<String> order = new ArrayList<>(previousOrder);
        enqueueSnackbar("排列已更新", "撤销");
        snackbarCallback = () -> {
            if (widgets) restoreWidgetOrderForUi(order); else restoreToolOrderForUi(order);
            showToast("已恢复原顺序");
        };
        invalidateComposeUi();
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
        composeDialog = new ComposeDialogState(title, message, dismissLabel, confirmLabel, onConfirm, true);
        invalidateComposeUi();
    }

    public void requestPluginRestartForUi(String pluginId) {
        PluginPackageStore.InstalledPlugin installed = findRuntimePlugin(pluginId);
        if (installed == null || !nativeProviderManager.isPendingRestart(installed)) return;
        composeDialog = new ComposeDialogState(
                "重启以启用 " + installed.manifest.plugin.title,
                "插件已启用，但系统功能需要重启当前应用后才能使用。立即重启会关闭当前页面，已保存的数据会保留。",
                "稍后重启",
                "立即重启",
                () -> AppRestartActivity.restart(this),
                false
        );
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
    public boolean autoCheckAppUpdatesForUi() {
        return !BuildConfig.DEBUG && uiPreferences.getBoolean(PREF_AUTO_CHECK_APP_UPDATES,
                uiPreferences.getBoolean(PREF_AUTO_CHECK_UPDATES, true));
    }
    public void setAutoCheckAppUpdatesForUi(boolean enabled) {
        uiPreferences.edit().putBoolean(PREF_AUTO_CHECK_APP_UPDATES, enabled).apply();
        invalidateComposeUi();
    }
    public void checkAppUpdatesForUi() {
        if (BuildConfig.DEBUG) { showToast("本地 Debug 构建通过本地安装更新"); return; }
        checkForUpdates(true, true, false, 1);
    }
    public void checkPluginUpdatesForUi() { checkForUpdates(true, true, false, 2); }

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
        if (minVersionCode == BuildConfig.VERSION_CODE) return BuildConfig.VERSION_NAME;
        if (minVersionCode == 23) return "2.0.0";
        if (minVersionCode == 15) return "1.5.0";
        if (updateCatalog != null && updateCatalog.app != null && updateCatalog.app.versionCode == minVersionCode) return updateCatalog.app.versionName;
        return "版本代码 " + minVersionCode;
    }

    public boolean shouldShowStoreRiskForUi() {
        return !uiPreferences.getBoolean(PREF_STORE_RISK_ACKNOWLEDGED, false);
    }

    public void acknowledgeStoreRiskForUi() {
        uiPreferences.edit().putBoolean(PREF_STORE_RISK_ACKNOWLEDGED, true).apply();
    }

    public void requestDeletePluginForUi(String pluginId) {
        String title = pluginTitleOrId(pluginId);
        if (findRuntimePlugin(pluginId) == null) return;
        showComposeDialog(
                "删除 " + title + "？",
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
        navigateForUi(9);
    }
    public void openSourceUrlForUi(String url) {
        Uri uri = Uri.parse(url);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (RuntimeException error) { showToast("没有可以打开此链接的应用"); }
    }

    public List<UpdateCatalog.PluginRelease> repositoryPluginsForUi() {
        return updateCatalog == null ? Collections.emptyList() : updateCatalog.plugins;
    }

    public List<UpdateCatalog.PluginRelease> repositoryPluginVersionsForUi(String pluginId) {
        return updateCatalog == null
                ? Collections.emptyList()
                : updateCatalog.versionsForPlugin(pluginId);
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
                BuildConfig.DEBUG
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
        PluginPackageStore.InstalledPlugin runtime = findRuntimePlugin(release.id);
        if (runtime != null) {
            return release.versionCode > runtime.manifest.plugin.versionCode
                    && isRepositoryPluginVersionSelectableForUi(release);
        }
        // Repository availability is not an update: only installed packages participate.
        return false;
    }

    public boolean isRepositoryPluginCompatibleForUi(UpdateCatalog.PluginRelease release) {
        return release.minHostVersionCode <= BuildConfig.VERSION_CODE
                && release.minAndroidApi <= Build.VERSION.SDK_INT;
    }

    public boolean isRepositoryPluginVersionInstalledForUi(UpdateCatalog.PluginRelease release) {
        PluginPackageStore.InstalledPlugin runtime = findRuntimePlugin(release.id);
        if (runtime != null) return runtime.repositoryVerified && runtimeMatchesRelease(runtime, release);
        return false;
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
        if (release.minAndroidApi > Build.VERSION.SDK_INT) {
            return "需要 Android " + release.minAndroidApi + "+";
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
                && release.minAndroidApi <= Build.VERSION.SDK_INT
                && transition != PluginUpdatePolicy.Transition.CURRENT
                && transition != PluginUpdatePolicy.Transition.DATA_INCOMPATIBLE;
    }

    public boolean isRepositoryVerifiedForUi(String pluginId) {
        PluginPackageStore.InstalledPlugin installed = findRuntimePlugin(pluginId);
        return installed != null && installed.repositoryVerified;
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
        if (release.minAndroidApi > Build.VERSION.SDK_INT) {
            showToast("此插件需要 Android " + release.minAndroidApi + "+");
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
        String taskId = beginUiTask("安装插件 · " + release.title);
        setUiTaskPlugin(taskId, pluginId);
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
                try {
                    byte[] packageBytes = readFileBytes(file);
                    if (PluginPackageArchive.hasFormatV3Manifest(packageBytes)) {
                        String message = installRepositoryRuntimePlugin(packageBytes, release);
                        updateStatus = message;
                        finishUiTask(taskId, true, message);
                        showToast(message);
                        return;
                    }
                    throw new IOException("仓库中的旧版 API1 插件已停止安装");
                } catch (IOException | JSONException | ContractException error) {
                    updateStatus = "插件更新失败：" + error.getMessage();
                    finishUiTask(taskId, false, updateStatus);
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
                finishUiTask(taskId, false, updateStatus);
                showToast(updateStatus);
                invalidateComposeUi();
            }
        });
    }

    private void showBlockedPluginTransitionDialog(String title, String message) {
        showComposeDialog(title, message, "", "知道了", null);
    }

    private PluginUpdatePolicy.Transition assessPluginTransition(UpdateCatalog.PluginRelease release) {
        PluginPackageStore.InstalledPlugin runtime = findRuntimePlugin(release.id);
        if (runtime != null) {
            if (runtime.repositoryVerified && runtimeMatchesRelease(runtime, release)) {
                return PluginUpdatePolicy.Transition.CURRENT;
            }
            int currentFormat = currentPluginDataFormatVersion(release.id);
            boolean downgrade = release.versionCode < runtime.manifest.plugin.versionCode
                    || (release.versionCode == runtime.manifest.plugin.versionCode
                    && isOlderRuntimeBuild(release, runtime));
            if (!release.canReadDataFormat(currentFormat)) {
                return PluginUpdatePolicy.Transition.DATA_INCOMPATIBLE;
            }
            if (downgrade) return PluginUpdatePolicy.Transition.DOWNGRADE_COMPATIBLE;
            return release.versionCode > runtime.manifest.plugin.versionCode
                    ? PluginUpdatePolicy.Transition.UPGRADE
                    : PluginUpdatePolicy.Transition.REPLACE;
        }
        return PluginUpdatePolicy.Transition.INSTALL;
    }

    private int currentPluginDataFormatVersion(String pluginId) {
        PluginPackageStore.InstalledPlugin runtime = findRuntimePlugin(pluginId);
        if (runtime != null) {
            int format = 0;
            for (RuntimePluginManifest.Dataset dataset : runtime.manifest.datasets) {
                format = Math.max(format, dataset.formatVersion);
            }
            return format;
        }
        return 0;
    }

    private boolean isOlderRuntimeBuild(
            UpdateCatalog.PluginRelease target,
            PluginPackageStore.InstalledPlugin installed
    ) {
        List<UpdateCatalog.PluginRelease> versions = updateCatalog == null
                ? Collections.emptyList() : updateCatalog.versionsForPlugin(target.id);
        int targetIndex = versions.indexOf(target);
        int installedIndex = -1;
        for (int index = 0; index < versions.size(); index++) {
            if (runtimeMatchesRelease(installed, versions.get(index))) {
                installedIndex = index;
                break;
            }
        }
        return targetIndex >= 0 && installedIndex >= 0 && targetIndex > installedIndex;
    }

    private static boolean runtimeMatchesRelease(
            PluginPackageStore.InstalledPlugin installed,
            UpdateCatalog.PluginRelease release
    ) {
        String digest = release.sha256 == null ? "" : release.sha256.toLowerCase(Locale.ROOT);
        if (digest.length() < 16) return false;
        return installed.manifest.plugin.versionCode == release.versionCode
                && installed.manifest.plugin.version.equals(release.versionName)
                && installed.generationDirectory.getName().equals(
                "v" + release.versionCode + "-" + digest.substring(0, 16)
        );
    }

    public void installAllPluginUpdatesForUi() {
        if (updateCatalog == null) {
            return;
        }
        for (UpdateCatalog.PluginRelease release : updateCatalog.plugins) {
            if (release.minHostVersionCode <= BuildConfig.VERSION_CODE
                    && release.minAndroidApi <= Build.VERSION.SDK_INT
                    && findRuntimePlugin(release.id) != null
                    && isRepositoryPluginUpdateAvailableForUi(release)) {
                installRepositoryPluginForUi(release.id);
            }
        }
    }

    private void checkForUpdates(boolean force, boolean userInitiated, boolean repositoryRefresh) {
        checkForUpdates(force, userInitiated, repositoryRefresh, repositoryRefresh ? 2 : userInitiated ? 0 : -1);
    }
    private void checkForUpdates(boolean force, boolean userInitiated, boolean repositoryRefresh, int scope) {
        if (updateOperations.contains("__check__")) {
            return;
        }
        promptAppUpdates = !BuildConfig.DEBUG && (scope == -1 ? autoCheckAppUpdatesForUi() : scope != 2);
        promptPluginUpdates = scope == -1 ? autoCheckUpdatesForUi() : scope != 1;
        updateOperations.add("__check__");
        updateStatus = "正在检查正式仓库更新…";
        updateCheckState = UpdateCheckState.CHECKING;
        updateError = "";
        // 后台自动检查不展示“检查中”，避免应用刚打开后让当前页和相邻页一起重组。
        if (userInitiated) invalidateComposeUi();
        updateClient.check(force, new UpdateClient.CatalogCallback() {
            @Override
            public void onSuccess(UpdateCatalog catalog, boolean cached) {
                updateOperations.remove("__check__");
                updateCatalog = catalog;
                uiPreferences.edit().putLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();
                int available = promptUpdateCountForUi();
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
        UpdateCatalog.AppRelease appRelease = promptAppUpdateForUi();
        if (appRelease != null) versions.add("app:" + appRelease.versionCode);
        for (UpdateCatalog.PluginRelease release : updateCatalog.plugins) {
            if (promptPluginUpdates && isRepositoryPluginUpdateAvailableForUi(release)) {
                versions.add(release.id + ":" + release.versionCode + ":" + release.sha256);
            }
        }
        Collections.sort(versions);
        return String.join("|", versions);
    }

    private void enqueueSnackbar(String message, String action) {
        snackbarMessage = message;
        snackbarAction = action;
        snackbarCallback = null;
        snackbarRevision++;
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
        PluginPackageStore.InstalledPlugin runtimePlugin = findRuntimePlugin(pluginId);
        return findPlugin(pluginId) != null || (runtimePlugin != null
                && !runtimePlugin.manifest.providerEntries.isEmpty()
                && nativeProviderManager.isActive(pluginId, runtimePlugin.generationDirectory.getName()));
    }

    public boolean isRuntimeActivationPendingForUi(String pluginId) {
        PluginPackageStore.InstalledPlugin installed = findRuntimePlugin(pluginId);
        return installed != null && nativeProviderManager.isPendingRestart(installed);
    }

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public void closeTool() {
        runOnUiThread(this::closePluginForUi);
    }

    @Override
    public void showMessage(String message) {
        showToast(message);
    }

    @Override
    public CapabilityRouter capabilityRouter() {
        return capabilityRouter;
    }

    @Override
    public void closeOperationReports(String sessionId) {
        runOnUiThread(() -> {
            for (PluginUiOperationRegistry.Operation operation : pluginUiOperations.closeSession(sessionId)) {
                applyPluginUiOperation(operation);
            }
        });
    }

    @Override
    public void closeRuntimeSession(String sessionId) {
        closeOperationReports(sessionId);
        storageService.closeSession(sessionId);
        datasetService.closeSession(sessionId);
        PendingRuntimeFilePick pending = pendingRuntimeFilePick;
        if (pending != null && pending.sessionId.equals(sessionId)) {
            pendingRuntimeFilePick = null;
            pending.result.completeExceptionally(
                    new CapabilityFailure("CANCELLED", "Runtime session closed", true)
            );
        }
        PendingRuntimeFileExport export = pendingRuntimeFileExport;
        if (export != null && export.sessionId.equals(sessionId)) {
            pendingRuntimeFileExport = null;
            export.result.completeExceptionally(
                    new CapabilityFailure("CANCELLED", "Runtime session closed", true)
            );
        }
    }

    @Override
    public CompletableFuture<JSONObject> pickFile(
            String pluginId,
            String sessionId,
            JSONArray mimeTypes,
            int maxBytes
    ) {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        runOnUiThread(() -> {
            if (pendingRuntimeFilePick != null || pendingRuntimeFileExport != null) {
                result.completeExceptionally(
                        new CapabilityFailure("PROVIDER_OFFLINE", "Another file picker is active", true)
                );
                return;
            }
            List<String> types = new ArrayList<>();
            for (int index = 0; index < mimeTypes.length(); index++) {
                String value = mimeTypes.optString(index, "").trim();
                if (!value.isEmpty()) types.add(value);
            }
            pendingRuntimeFilePick = new PendingRuntimeFilePick(pluginId, sessionId, Math.max(1, maxBytes), result);
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(types.size() == 1 ? types.get(0) : "*/*");
            if (types.size() > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
            startActivityForResult(intent, REQUEST_V2_FILE_IMPORT);
        });
        return result;
    }

    @Override
    public CompletableFuture<JSONObject> saveFile(
            String pluginId,
            String sessionId,
            String blobId,
            String fileName,
            String mimeType,
            int maxBytes
    ) {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        runOnUiThread(() -> {
            if (pendingRuntimeFilePick != null || pendingRuntimeFileExport != null) {
                result.completeExceptionally(
                        new CapabilityFailure("PROVIDER_OFFLINE", "Another file picker is active", true)
                );
                return;
            }
            try {
                JSONObject opened = storageService.blobOpenRead(pluginId, sessionId, blobId);
                if (!opened.optBoolean("found", false)) {
                    result.completeExceptionally(CapabilityFailure.invalid("Export Blob does not exist"));
                    return;
                }
                String handle = opened.getString("handle");
                long size = opened.getLong("size");
                String sha256 = opened.optString("sha256", "");
                storageService.blobClose(pluginId, sessionId, handle);
                if (size > maxBytes) {
                    result.completeExceptionally(new CapabilityFailure(
                            "RESOURCE_LIMIT",
                            "Export Blob exceeds declared maxBytes",
                            false
                    ));
                    return;
                }
                pendingRuntimeFileExport = new PendingRuntimeFileExport(
                        pluginId,
                        sessionId,
                        blobId,
                        maxBytes,
                        size,
                        sha256,
                        result
                );
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(mimeType);
                intent.putExtra(Intent.EXTRA_TITLE, ExportFileNames.forBuild(fileName,BuildConfig.DEBUG));
                startActivityForResult(intent, REQUEST_V2_FILE_EXPORT);
            } catch (JSONException | CapabilityFailure error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    public List<PluginPermissionManager.Permission> pluginPermissionsForUi(String pluginId) {
        PluginPackageStore.InstalledPlugin installed = findRuntimePlugin(pluginId);
        if (installed == null) return Collections.emptyList();
        return permissionManager.permissions(installed.manifest);
    }

    public boolean isRuntimePluginForUi(String pluginId) {
        return findRuntimePlugin(pluginId) != null;
    }

    public boolean isTrustedProviderForUi(String pluginId) {
        PluginPackageStore.InstalledPlugin installed = findRuntimePlugin(pluginId);
        return installed != null && "trusted-provider".equals(installed.manifest.plugin.kind);
    }

    public boolean hasPluginDataForUi(String pluginId, HostTool loadedPlugin) {
        PluginPackageStore.InstalledPlugin installed = findRuntimePlugin(pluginId);
        if (installed != null) return !installed.manifest.datasets.isEmpty();
        return loadedPlugin != null && loadedPlugin.datasetBridge() != null;
    }

    public void setPluginPermissionForUi(String pluginId, String capabilityId, boolean granted) {
        PluginPackageStore.InstalledPlugin installed = findRuntimePlugin(pluginId);
        if (installed == null) {
            showToast("插件不存在");
            return;
        }
        try {
            permissionManager.setGranted(installed.manifest, capabilityId, granted);
            schedulerService.syncPluginAsync(pluginId);
            showToast(granted ? "已允许此权限" : "已撤销此权限");
            invalidateComposeUi();
        } catch (IllegalArgumentException | IllegalStateException error) {
            showToast("权限修改失败：" + safeMessage(error));
        }
    }

    public String pluginPermissionScopeForUi(PluginPermissionManager.Permission permission) {
        try {
            JSONObject scopes = new JSONObject(permission.scopesJson);
            if (scopes.length() == 0) return "不申请额外范围";
            if ("network.request".equals(permission.capabilityId)) {
                String summary = "仅访问 " + jsonArraySummary(scopes.optJSONArray("hosts"), "声明的域名")
                        + " · " + jsonArraySummary(scopes.optJSONArray("methods"), "声明的请求方式");
                JSONArray headers = scopes.optJSONArray("headers");
                if (headers != null && headers.length() > 0) {
                    summary += " · 可发送 " + jsonArraySummary(headers, "声明的认证信息");
                }
                return summary;
            }
            if ("file.import".equals(permission.capabilityId)) {
                return "仅限你选择的 " + jsonArraySummary(scopes.optJSONArray("mimeTypes"), "文件类型");
            }
            if ("file.export".equals(permission.capabilityId)) {
                return "仅保存 " + jsonArraySummary(scopes.optJSONArray("mimeTypes"), "声明的文件类型");
            }
            if ("notification".equals(permission.capabilityId)) {
                return "仅使用 " + jsonArraySummary(scopes.optJSONArray("channels"), "声明的通知类别");
            }
            if ("accessibility.manage".equals(permission.capabilityId)
                    && scopes.optBoolean("allowBackground", false)) {
                return "允许按插件任务在后台执行";
            }
            return "使用插件清单中声明的受限范围";
        } catch (JSONException error) {
            return "权限范围无法读取";
        }
    }

    private static String jsonArraySummary(JSONArray values, String fallback) {
        if (values == null || values.length() == 0) return fallback;
        List<String> text = new ArrayList<>();
        for (int index = 0; index < values.length() && index < 3; index++) {
            String value = values.optString(index, "").trim();
            if (!value.isEmpty()) text.add(value);
        }
        if (text.isEmpty()) return fallback;
        String summary = String.join("、", text);
        return values.length() > text.size() ? summary + " 等" : summary;
    }

    @Override
    public void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread(() -> requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_V2_NOTIFICATION_PERMISSION
            ));
        }
    }

    @Override
    public boolean isShizukuReady() {
        return shizukuService != null && shizukuService.isReady();
    }

    @Override
    public boolean hasShizukuPermission() {
        return shizukuService != null && shizukuService.hasPermission();
    }

    @Override
    public boolean isShellServiceConnected() {
        return shizukuService != null && shizukuService.isConnected();
    }

    @Override
    public int shizukuUid() {
        return shizukuService == null ? -1 : shizukuService.uid();
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
        shizukuService.ensure();
    }

    private void ensureShellServiceIfAuthorized() {
        if (shizukuService != null) shizukuService.ensureIfAuthorized();
    }

    @Override
    public String runShellCommand(String... command) throws IOException {
        return shizukuService.run(command);
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
        PluginPackageStore.InstalledPlugin runtimePlugin = findRuntimePlugin(pluginId);
        if (runtimePlugin == null) {
            showToast("只能导出外部插件清单");
            return;
        }
        pendingExportPluginId = pluginId;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, ExportFileNames.pluginPackage(pluginId,runtimePlugin.manifest.plugin.version,BuildConfig.DEBUG));
        startActivityForResult(intent, REQUEST_EXPORT_PLUGIN);
    }

    public void importMigrationBridgeForUi() {
        importMigrationBridgeForOwner(null);
    }

    public void importMigrationBridgeForPluginUi(String pluginId) {
        importMigrationBridgeForOwner(pluginId);
    }

    private void importMigrationBridgeForOwner(String ownerId) {
        if (claimDataFlow("import") < 0) return;
        activeDataOwner = ownerId;
        pendingDataOwnerFilter = ownerId;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        startActivityForResult(intent, REQUEST_IMPORT_MIGRATION_BRIDGE);
    }

    public void prepareMigrationBridgeExportForUi() {
        prepareMigrationBridgeExportForOwner(null);
    }

    public void prepareMigrationBridgeExportForPluginUi(String pluginId) {
        prepareMigrationBridgeExportForOwner(pluginId);
    }

    private void prepareMigrationBridgeExportForOwner(String ownerId) {
        long generation = claimDataFlow("export");
        if (generation < 0) return;
        activeDataOwner = ownerId;
        destroyMigrationBridgePlugins(migrationBridgeOwnedPlugins);
        migrationBridgeOwnedPlugins = Collections.emptyList();
        migrationBridgeExportOptions = Collections.emptyList();
        List<HostTool> activePlugins = new ArrayList<>(plugins);
        showToast("正在扫描可迁移数据…");
        migrationBridgeExecutor.execute(() -> {
            List<HostTool> candidates = new ArrayList<>(activePlugins);
            List<HostTool> ownedPlugins = new ArrayList<>();
            try {
                addRuntimeMigrationPlugins(candidates, ownedPlugins);
                List<MigrationBridgeManager.DatasetOption> options =
                        MigrationBridgeManager.discover(this, candidates);
                if (ownerId == null || MigrationBridgeManager.HOST_OWNER_ID.equals(ownerId)) {
                    List<MigrationBridgeManager.DatasetOption> withHost = new ArrayList<>();
                    withHost.addAll(createHostDataExportOptions());
                    if (ownerId == null) withHost.addAll(options);
                    options = withHost;
                } else {
                    List<MigrationBridgeManager.DatasetOption> filtered = new ArrayList<>();
                    PluginPackageStore.InstalledPlugin scopedPackage = pluginPackageStore.find(ownerId);
                    if (scopedPackage != null) {
                        filtered.add(MigrationBridgeManager.hostPluginPackageExportOption(ownerId,
                                scopedPackage.manifest.plugin.title, scopedPackage.packageFile.length()));
                    }
                    for (MigrationBridgeManager.DatasetOption option : options) {
                        if (ownerId.equals(option.pluginId)) filtered.add(option);
                    }
                    options = filtered;
                }
                List<MigrationBridgeManager.DatasetOption> discovered = options;
                runOnUiThread(() -> {
                    if (!isCurrentDataFlow(generation)) { destroyMigrationBridgePlugins(ownedPlugins); return; }
                    if (discovered.isEmpty()) {
                        destroyMigrationBridgePlugins(ownedPlugins);
                        showToast("没有可导出的数据项目");
                        return;
                    }
                    migrationBridgeOwnedPlugins = Collections.unmodifiableList(ownedPlugins);
                    migrationBridgeExportOptions = Collections.unmodifiableList(discovered);
                    invalidateComposeUi();
                });
            } catch (IOException | JSONException | RuntimeException error) {
                runOnUiThread(() -> {
                    destroyMigrationBridgePlugins(ownedPlugins);
                    showToast("扫描 Bridge 数据失败：" + safeMessage(error));
                });
            }
        });
    }

    public List<MigrationBridgeManager.DatasetOption> migrationBridgeExportOptionsForUi() {
        return migrationBridgeExportOptions;
    }

    public void dismissMigrationBridgeExportForUi() {
        releaseDataFlow("export");
        composeState.getDataDrafts().remove("export");
        migrationBridgeExportOptions = Collections.emptyList();
        destroyMigrationBridgePlugins(migrationBridgeOwnedPlugins);
        migrationBridgeOwnedPlugins = Collections.emptyList();
        invalidateComposeUi();
    }

    public List<MigrationBridgeManager.DatasetOption> migrationBridgeDeleteOptionsForUi() {
        return migrationBridgeDeleteOptions;
    }

    public void prepareMigrationBridgeDeleteForUi() {
        prepareMigrationBridgeDeleteForOwner(null);
    }

    public void prepareMigrationBridgeDeleteForPluginUi(String pluginId) {
        prepareMigrationBridgeDeleteForOwner(pluginId);
    }

    private void prepareMigrationBridgeDeleteForOwner(String ownerId) {
        long generation = claimDataFlow("delete");
        if (generation < 0) return;
        activeDataOwner = ownerId;
        List<HostTool> activePlugins = new ArrayList<>(plugins);
        showToast("正在扫描可删除的数据…");
        migrationBridgeExecutor.execute(() -> {
            List<HostTool> candidates = new ArrayList<>(activePlugins);
            List<HostTool> ownedPlugins = new ArrayList<>();
            try {
                addRuntimeMigrationPlugins(candidates, ownedPlugins);
                List<MigrationBridgeManager.DatasetOption> discovered =
                        MigrationBridgeManager.discover(this, candidates);
                List<MigrationBridgeManager.DatasetOption> scoped = new ArrayList<>();
                List<MigrationBridgeManager.DatasetOption> deletable = new ArrayList<>();
                for (MigrationBridgeManager.DatasetOption option : discovered) {
                    if (ownerId != null && !ownerId.equals(option.pluginId)) continue;
                    scoped.add(option);
                    if (option.supportsDelete()) deletable.add(option);
                }
                runOnUiThread(() -> {
                    if (!isCurrentDataFlow(generation)) { destroyMigrationBridgePlugins(ownedPlugins); return; }
                    if (deletable.isEmpty()) {
                        destroyMigrationBridgePlugins(ownedPlugins);
                        showToast("当前范围没有可删除的数据");
                        return;
                    }
                    migrationBridgeDeleteOwnedPlugins = Collections.unmodifiableList(ownedPlugins);
                    migrationBridgeDeleteDependencyOptions = Collections.unmodifiableList(scoped);
                    migrationBridgeDeleteOptions = Collections.unmodifiableList(deletable);
                    invalidateComposeUi();
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    destroyMigrationBridgePlugins(ownedPlugins);
                    showToast("扫描可删除数据失败：" + safeMessage(error));
                });
            }
        });
    }

    private String installRepositoryRuntimePlugin(
            byte[] packageBytes,
            UpdateCatalog.PluginRelease release
    ) throws IOException, ContractException, JSONException {
        PluginPackageStore.InstallSession session = null;
        try {
            session = com.androidtoolsuite.app.plugin.runtime.RuntimePackageInstaller.beginRepository(
                    pluginPackageStore, packageBytes, release);
            PluginPackageStore.InstalledPlugin installed = pluginPackageStore.find(session.pluginId);
            if (installed == null) throw new IOException("安装后的插件不可读");
            RuntimePluginManifest manifest = installed.manifest;
            permissionManager.reconcile(manifest);
            boolean wasEnabled = pluginPackageStore.isEnabled(session.pluginId);
            boolean updating = !session.newInstall;
            reloadPluginsKeepingCurrentPage();
            if (wasEnabled && manifest.providerEntries.isEmpty() && findPlugin(session.pluginId) == null) {
                throw new IOException("新版本插件无法激活");
            }
            pluginPackageStore.confirmInstall(session);
            schedulerService.syncPluginAsync(session.pluginId);
            session = null;
            String message = (updating ? "已更新插件：" : "已安装插件：") + manifest.plugin.title;
            if (permissionManager.permissions(manifest).stream()
                    .anyMatch(permission -> permission.state != PluginPermissionManager.State.GRANTED)) {
                message += "；请在插件详情检查插件权限";
            }
            return message;
        } catch (IOException | ContractException | RuntimeException error) {
            if (session != null) {
                pluginPackageStore.rollbackInstall(session);
                permissionManager.reconcile(pluginPackageStore.load());
                reloadPluginsKeepingCurrentPage();
            }
            if (error instanceof IOException) throw (IOException) error;
            if (error instanceof ContractException) throw (ContractException) error;
            if (error instanceof JSONException) throw (JSONException) error;
            throw new IOException(safeMessage(error), error);
        }
    }

    public void dismissMigrationBridgeDeleteForUi() {
        releaseDataFlow("delete");
        composeState.getDataDrafts().remove("delete");
        migrationBridgeDeleteOptions = Collections.emptyList();
        migrationBridgeDeleteDependencyOptions = Collections.emptyList();
        destroyMigrationBridgePlugins(migrationBridgeDeleteOwnedPlugins);
        migrationBridgeDeleteOwnedPlugins = Collections.emptyList();
        invalidateComposeUi();
    }

    public void confirmMigrationBridgeDeleteForUi(List<String> selectedKeys) {
        if (dataOperationRunning || !"delete".equals(activeDataFlow)) return;
        clearDataOperationError("delete");
        Set<String> selected = new LinkedHashSet<>(selectedKeys);
        if (selected.isEmpty()) {
            showDataOperationError("delete", "请至少选择一个数据项目");
            return;
        }
        List<MigrationBridgeManager.DatasetOption> options = new ArrayList<>();
        for (MigrationBridgeManager.DatasetOption option : migrationBridgeDeleteOptions) {
            if (selected.contains(option.key())) options.add(option);
        }
        if (options.size() != selected.size()) {
            showDataOperationError("delete", "选择中包含不可删除的数据项目");
            return;
        }
        List<MigrationBridgeManager.DatasetOption> available = migrationBridgeDeleteDependencyOptions;
        List<HostTool> ownedPlugins = migrationBridgeDeleteOwnedPlugins;
        List<MigrationBridgeManager.DatasetOption> originalOptions = migrationBridgeDeleteOptions;
        String taskId = beginUiTask("删除插件数据 · " + options.size() + " 项",
                options.stream().map(option -> option.pluginTitle + " · " + option.descriptor.name).collect(java.util.stream.Collectors.toList()));
        setUiTaskData(taskId, "delete", options.stream().map(MigrationBridgeManager.DatasetOption::key).collect(java.util.stream.Collectors.toList()), Collections.emptyList(), Collections.emptyList(), null);
        dataOperationRunning = true;
        showTasksPageForUi(false);
        migrationBridgeDeleteOptions = Collections.emptyList();
        migrationBridgeDeleteDependencyOptions = Collections.emptyList();
        migrationBridgeDeleteOwnedPlugins = Collections.emptyList();
        invalidateComposeUi();
        migrationBridgeExecutor.execute(() -> {
            boolean completed = false;
            Set<String> deleted = new LinkedHashSet<>();
            try {
                MigrationBridgeManager.delete(this, available, options, option -> {
                    deleted.add(option.key());
                    completeUiTaskRow(taskId, option.pluginTitle + " · " + option.descriptor.name, option.key());
                });
                completed = true;
                finishUiTask(taskId, true, "所选 " + options.size() + " 项数据已删除，插件安装包保留。");
                showToast("已删除 " + options.size() + " 个数据项目");
                runOnUiThread(() -> {
                    for (MigrationBridgeManager.DatasetOption option : options) invalidatedToolPages.add(option.pluginId);
                    reloadPluginsKeepingCurrentPage();
                });
                runOnUiThread(() -> composeState.getDataDrafts().remove("delete"));
            } catch (IOException | RuntimeException error) {
                String failure = dataOperationFailureMessage("删除数据失败", error, null);
                finishUiTask(taskId, false, failure);
                runOnUiThread(() -> {
                    for (MigrationBridgeManager.DatasetOption option : options) if (deleted.contains(option.key())) invalidatedToolPages.add(option.pluginId);
                    reloadPluginsKeepingCurrentPage();
                    migrationBridgeDeleteOptions = deleted.isEmpty() ? originalOptions :
                            originalOptions.stream().filter(option -> !deleted.contains(option.key())).collect(java.util.stream.Collectors.toList());
                    migrationBridgeDeleteDependencyOptions = available.stream().filter(option -> !deleted.contains(option.key())).collect(java.util.stream.Collectors.toList());
                    migrationBridgeDeleteOwnedPlugins = ownedPlugins;
                    showDataOperationError("delete", failure);
                });
            } finally {
                boolean destroyOwned = completed;
                runOnUiThread(() -> { dataOperationRunning = false; if (destroyOwned) destroyMigrationBridgePlugins(ownedPlugins); });
            }
        });
    }

    public void confirmMigrationBridgeExportForUi(
            List<String> selectedKeys,
            List<String> passwordProtectedKeys,
            String rawPassword
    ) {
        if (dataOperationRunning || !"export".equals(activeDataFlow) || !pendingMigrationBridgeExport.isEmpty()) return;
        clearDataOperationError("export");
        Set<String> selected = new LinkedHashSet<>(selectedKeys);
        if (selected.isEmpty()) {
            showDataOperationError("export", "请至少选择一个数据项目");
            return;
        }
        Set<String> passwordProtected = new LinkedHashSet<>(passwordProtectedKeys);
        if (!selected.containsAll(passwordProtected)) {
            showDataOperationError("export", "保护方式中包含未选择的数据项目");
            return;
        }
        List<MigrationBridgeManager.ExportSelection> options = new ArrayList<>();
        for (MigrationBridgeManager.DatasetOption option : migrationBridgeExportOptions) {
            if (!selected.contains(option.key())) continue;
            for (String dependency : option.descriptor.dependencies) {
                if (!selected.contains(option.pluginId + "/" + dependency)) {
                    showDataOperationError("export", option.descriptor.name + " 需要同时导出 " + dependency);
                    return;
                }
            }
            DataPackageArchive.Protection protection = passwordProtected.contains(option.key())
                    ? DataPackageArchive.Protection.PASSWORD
                    : DataPackageArchive.Protection.NONE;
            options.add(new MigrationBridgeManager.ExportSelection(option, protection));
        }
        if (options.size() != selected.size()) {
            showDataOperationError("export", "选择中包含未知数据项目");
            return;
        }
        String password = rawPassword == null ? "" : rawPassword;
        if (!passwordProtected.isEmpty() && password.length() < 8) {
            showDataOperationError("export", "密码保护区的密码至少需要 8 位");
            return;
        }
        clearPendingMigrationBridgePassword();
        pendingMigrationBridgeExport = Collections.unmodifiableList(options);
        pendingMigrationBridgeOwnedPlugins = migrationBridgeOwnedPlugins;
        pendingMigrationBridgePassword = password.toCharArray();
        invalidateComposeUi();

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        List<String> owners = options.stream()
                .map(selection -> selection.option.isPluginPackage()
                        ? selection.option.packagedPluginId() : selection.option.pluginId)
                .distinct().collect(java.util.stream.Collectors.toList());
        String owner = owners.size() == 1 ? owners.get(0) : MigrationBridgeManager.HOST_OWNER_ID;
        intent.putExtra(Intent.EXTRA_TITLE, ExportFileNames.dataBackup(owner,new java.util.Date(),BuildConfig.DEBUG));
        startActivityForResult(intent, REQUEST_EXPORT_MIGRATION_BRIDGE);
    }

    public List<MigrationBridgeManager.DatasetOption> migrationBridgeImportOptionsForUi() {
        return migrationBridgeImportOptions;
    }
    public String migrationBridgeImportFileNameForUi() { return importFileName; }
    public boolean migrationBridgeImportBusyForUi() { return inspectingDataFile || replacingDataFile; }
    public void replaceMigrationBridgeFileForUi() {
        if (dataOperationRunning || inspectingDataFile || replacingDataFile || migrationBridgeImportOptions.isEmpty()) return;
        clearDataOperationError("import");
        replacingDataFile = true;
        pendingDataOwnerFilter = activeDataOwner;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream");
        startActivityForResult(intent, REQUEST_IMPORT_MIGRATION_BRIDGE);
    }
    private String selectedDataFileName(Uri source) {
        try (android.database.Cursor cursor = getContentResolver().query(source,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        return "已选数据包";
    }

    public boolean migrationBridgeImportEncryptedForUi() {
        if (pendingDataPackageInspection != null) {
            return pendingDataPackageInspection.hasProtection(DataPackageArchive.Protection.PASSWORD);
        }
        return pendingMigrationBridgeInspection != null && pendingMigrationBridgeInspection.encrypted;
    }

    public String migrationBridgeImportProtectionForUi(String key) {
        if (pendingBackupPackageFormat == BackupPackageProbe.Format.BRIDGE_V2
                && pendingMigrationBridgeInspection != null
                && pendingMigrationBridgeInspection.encrypted) {
            return DataPackageArchive.Protection.PASSWORD.name();
        }
        for (MigrationBridgeManager.DatasetOption option : migrationBridgeImportOptions) {
            if (option.key().equals(key)) return option.archiveProtection.name();
        }
        return DataPackageArchive.Protection.NONE.name();
    }

    public String migrationBridgeImportSourceForUi() {
        if (pendingDataPackageInspection != null) {
            return pendingDataPackageInspection.sourcePackage + " "
                    + pendingDataPackageInspection.sourceVersionName + " · 数据包 v3";
        }
        if (pendingMigrationBridgeInspection != null) {
            return pendingMigrationBridgeInspection.sourcePackage + " "
                    + pendingMigrationBridgeInspection.sourceVersionName + " · Bridge v2";
        }
        return "";
    }

    public void dismissMigrationBridgeImportForUi() {
        replacingDataFile = false;
        inspectingDataFile = false;
        releaseDataFlow("import");
        composeState.getDataDrafts().remove("import");
        migrationBridgeImportOptions = Collections.emptyList();
        pendingMigrationBridgeImportUri = null;
        pendingDataPackageInspection = null;
        pendingMigrationBridgeInspection = null;
        pendingBackupPackageFormat = null;
        clearPendingMigrationBridgeImportPassword();
        destroyMigrationBridgePlugins(migrationBridgeImportOwnedPlugins);
        migrationBridgeImportOwnedPlugins = Collections.emptyList();
        invalidateComposeUi();
    }

    public void confirmMigrationBridgeImportForUi(
            List<String> replaceKeys,
            List<String> mergeKeys,
            String rawPassword
    ) {
        if (dataOperationRunning || inspectingDataFile || replacingDataFile || !"import".equals(activeDataFlow)
                || pendingMigrationBridgeImportUri == null || migrationBridgeImportOptions.isEmpty()) return;
        clearDataOperationError("import");
        Set<String> replace = new LinkedHashSet<>(replaceKeys);
        Set<String> merge = new LinkedHashSet<>(mergeKeys);
        Set<String> selected = new LinkedHashSet<>(replace);
        if (!Collections.disjoint(replace, merge)) {
            showDataOperationError("import", "同一数据项目不能同时选择替换和合并");
            return;
        }
        selected.addAll(merge);
        if (selected.isEmpty()) {
            showDataOperationError("import", "请至少选择一个数据项目");
            return;
        }
        List<MigrationBridgeManager.ImportSelection> selections = new ArrayList<>();
        for (MigrationBridgeManager.DatasetOption option : migrationBridgeImportOptions) {
            if (!selected.contains(option.key())) continue;
            for (String dependency : option.descriptor.dependencies) {
                if (!selected.contains(option.pluginId + "/" + dependency)) {
                    showDataOperationError("import", option.descriptor.name + " 需要同时恢复 " + dependency);
                    return;
                }
            }
            DatasetRestoreMode mode = merge.contains(option.key())
                    ? DatasetRestoreMode.MERGE
                    : DatasetRestoreMode.REPLACE;
            if (!option.descriptor.supportsRestoreMode(mode)) {
                showDataOperationError("import", option.descriptor.name + " 不支持" + (mode == DatasetRestoreMode.MERGE ? "合并" : "替换"));
                return;
            }
            selections.add(new MigrationBridgeManager.ImportSelection(option, mode));
        }
        if (selections.size() != selected.size()) {
            showDataOperationError("import", "选择中包含不可恢复的数据项目");
            return;
        }
        for (MigrationBridgeManager.ImportSelection selection : selections) {
            MigrationBridgeManager.DatasetOption option = selection.option;
            if (!option.requiresBridgeResolution()) continue;
            String packageKey = MigrationBridgeManager.HOST_OWNER_ID + "/"
                    + MigrationBridgeManager.HOST_PLUGIN_PACKAGE_PREFIX + option.pluginId;
            if (!selected.contains(packageKey)) {
                showDataOperationError("import", "导入 " + option.pluginTitle + " 的数据时，需要同时导入对应插件包");
                return;
            }
        }
        String password = rawPassword == null ? "" : rawPassword;
        boolean selectedPasswordSection = pendingBackupPackageFormat == BackupPackageProbe.Format.BRIDGE_V2
                ? pendingMigrationBridgeInspection != null && pendingMigrationBridgeInspection.encrypted
                : selections.stream().anyMatch(selection ->
                selection.option.archiveProtection == DataPackageArchive.Protection.PASSWORD);
        if (selectedPasswordSection && password.length() < 8) {
            showDataOperationError("import", "所选密码保护区需要至少 8 位密码");
            return;
        }

        Uri source = pendingMigrationBridgeImportUri;
        List<MigrationBridgeManager.DatasetOption> originalOptions = migrationBridgeImportOptions;
        DataPackageArchive.ReadResult originalDataInspection = pendingDataPackageInspection;
        BackupArchiveV2.ReadResult originalBridgeInspection = pendingMigrationBridgeInspection;
        List<HostTool> ownedPlugins = new ArrayList<>(migrationBridgeImportOwnedPlugins);
        BackupPackageProbe.Format format = pendingBackupPackageFormat;
        boolean restoresHost = selections.stream().anyMatch(
                selection -> selection.option.isHostItem());
        pendingMigrationBridgeImportUri = null;
        pendingDataPackageInspection = null;
        pendingMigrationBridgeInspection = null;
        pendingBackupPackageFormat = null;
        migrationBridgeImportOptions = Collections.emptyList();
        migrationBridgeImportOwnedPlugins = Collections.emptyList();
        clearPendingMigrationBridgeImportPassword();
        pendingMigrationBridgeImportPassword = password.toCharArray();
        invalidateComposeUi();

        char[] restorePassword = pendingMigrationBridgeImportPassword.clone();
        clearPendingMigrationBridgeImportPassword();
        String taskId = beginUiTask("导入数据包 · " + selections.size() + " 项",
                selections.stream().map(selection -> selection.option.pluginTitle + " · " + selection.option.descriptor.name).collect(java.util.stream.Collectors.toList()));
        setUiTaskData(taskId, "import", selections.stream().map(selection -> selection.option.key()).collect(java.util.stream.Collectors.toList()), Collections.emptyList(), new ArrayList<>(merge), source);
        dataOperationRunning = true;
        showTasksPageForUi(false);
        migrationBridgeExecutor.execute(() -> {
            boolean completed = false;
            File staging = new File(
                    getCacheDir(),
                    "bridge-import-" + Long.toHexString(System.nanoTime())
            );
            try (HostRestoreCheckpoint hostCheckpoint = new HostRestoreCheckpoint();
                 com.androidtoolsuite.app.migration.RuntimePackageRestore packages =
                         new com.androidtoolsuite.app.migration.RuntimePackageRestore(pluginPackageStore)) {
                try (InputStream input = getContentResolver().openInputStream(source)) {
                if (input == null) throw new IOException("无法读取数据包");
                if (format == BackupPackageProbe.Format.DATA_PACKAGE_V3) {
                    Set<String> dataOwners = new LinkedHashSet<>();
                    for (MigrationBridgeManager.ImportSelection selection : selections) {
                        if (!selection.option.isHostItem()) dataOwners.add(selection.option.pluginId);
                    }
                    datasetService.withRestoreRollback(dataOwners, () -> {
                    MigrationBridgeManager.restoreDataPackage(
                            this,
                            input,
                            restorePassword,
                            selections,
                            staging,
                            (selectedItems, stagedItems) -> {
                                packages.install(selectedItems, stagedItems);
                                // Validate Dataset compatibility before changing Host settings.
                                resolveDataPackageOptionsAfterHost(selections, ownedPlugins);
                                restoreHostDataItems(selectedItems, stagedItems, packages.newPluginIds());
                            },
                            selectedOptions -> resolveDataPackageOptionsAfterHost(
                                    selectedOptions,
                                    ownedPlugins
                            )
                    );
                    });
                } else if (format == BackupPackageProbe.Format.BRIDGE_V2) {
                    MigrationBridgeManager.restore(this, input, restorePassword, selections, staging);
                } else {
                    throw new IOException("不支持的数据包格式");
                }
                }
                packages.commit();
                hostCheckpoint.commit();
                completed = true;
                finishUiTask(taskId, true, "已恢复 " + selections.size() + " 项；新装插件请检查权限并启用。");
                showToast("数据恢复完成，共 " + selections.size() + " 个项目；新装插件请在插件详情检查权限并启用");
                runOnUiThread(() -> {
                    composeState.getDataDrafts().remove("import");
                    for (MigrationBridgeManager.ImportSelection selection : selections) if (!selection.option.isHostItem()) invalidatedToolPages.add(selection.option.pluginId);
                    reloadPluginsKeepingCurrentPage();
                    if (restoresHost) {
                        updateCatalog = null;
                        checkForUpdates(true, false, false);
                    }
                });
            } catch (IOException | RuntimeException error) {
                String failure = dataOperationFailureMessage("恢复数据失败", error, restorePassword);
                finishUiTask(taskId, false, failure);
                runOnUiThread(this::reloadPluginsKeepingCurrentPage);
                runOnUiThread(() -> {
                    migrationBridgeImportOptions = originalOptions;
                    migrationBridgeImportOwnedPlugins = ownedPlugins;
                    pendingMigrationBridgeImportUri = source;
                    pendingDataPackageInspection = originalDataInspection;
                    pendingMigrationBridgeInspection = originalBridgeInspection;
                    pendingBackupPackageFormat = format;
                    showDataOperationError("import", failure);
                });
            } finally {
                Arrays.fill(restorePassword, '\0');
                boolean destroyOwned = completed;
                runOnUiThread(() -> { dataOperationRunning = false; if (destroyOwned) destroyMigrationBridgePlugins(ownedPlugins); });
            }
        });
    }

    @Override
    public void deleteImportedPlugin(String pluginId) {
        List<String> dependents = findDependentPluginTitles(pluginId);
        if (!dependents.isEmpty()) {
            showToast("无法删除，仍被依赖：" + joinNames(dependents));
            return;
        }
        try {
            schedulerService.cancelPlugin(pluginId);
            pluginPackageStore.delete(pluginId);
            nativeProviderManager.deactivate(pluginId);
            permissionManager.removePlugin(pluginId);
            showToast("已删除插件");
            reloadPlugins(selectedPlugin == null || selectedPlugin.id().equals(pluginId) ? null : selectedPlugin.id());
        } catch (IOException | ContractException e) {
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
        } else if (requestCode == REQUEST_EXPORT_MIGRATION_BRIDGE) {
            handleMigrationBridgeExportResult(resultCode, data);
        } else if (requestCode == REQUEST_IMPORT_MIGRATION_BRIDGE) {
            handleMigrationBridgeImportSelection(resultCode, data);
        } else if (requestCode == REQUEST_V2_FILE_IMPORT) {
            handleRuntimeFileImportResult(resultCode, data);
        } else if (requestCode == REQUEST_V2_FILE_EXPORT) {
            handleRuntimeFileExportResult(resultCode, data);
        }
    }

    private void handleRuntimeFileExportResult(int resultCode, Intent data) {
        PendingRuntimeFileExport pending = pendingRuntimeFileExport;
        pendingRuntimeFileExport = null;
        if (pending == null) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pending.result.completeExceptionally(
                    new CapabilityFailure("CANCELLED", "File export was cancelled", false)
            );
            return;
        }
        Uri destination = data.getData();
        runtimeFileExecutor.execute(() -> copyRuntimeBlobToUri(pending, destination));
    }

    private void copyRuntimeBlobToUri(PendingRuntimeFileExport pending, Uri destination) {
        String handle = null;
        try {
            JSONObject opened = storageService.blobOpenRead(
                    pending.pluginId,
                    pending.sessionId,
                    pending.blobId
            );
            if (!opened.optBoolean("found", false)) throw new IOException("Export Blob does not exist");
            if (opened.getLong("size") != pending.size
                    || !opened.optString("sha256", "").equalsIgnoreCase(pending.sha256)) {
                throw new IOException("Export Blob changed while the file picker was open");
            }
            if (pending.size > pending.maxBytes) throw new IOException("Export Blob exceeds declared maxBytes");
            handle = opened.getString("handle");
            try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("Cannot open export destination");
                long offset = 0L;
                while (offset < pending.size) {
                    JSONObject part = storageService.blobRead(
                            pending.pluginId,
                            pending.sessionId,
                            handle,
                            offset,
                            128 * 1024
                    );
                    byte[] bytes = Base64.decode(part.getString("bytes"), Base64.DEFAULT);
                    if (bytes.length == 0 && !part.getBoolean("eof")) {
                        throw new IOException("Export Blob read made no progress");
                    }
                    output.write(bytes);
                    offset += bytes.length;
                    if (part.getBoolean("eof")) break;
                }
                if (offset != pending.size) throw new IOException("Export Blob size changed during copy");
                output.flush();
            }
            storageService.blobClose(pending.pluginId, pending.sessionId, handle);
            handle = null;
            pending.result.complete(new JSONObject()
                    .put("saved", true)
                    .put("size", pending.size)
                    .put("sha256", pending.sha256));
        } catch (IOException | JSONException | CapabilityFailure error) {
            if (handle != null) {
                try {
                    storageService.blobClose(pending.pluginId, pending.sessionId, handle);
                } catch (CapabilityFailure ignored) {
                }
            }
            try {
                getContentResolver().delete(destination, null, null);
            } catch (RuntimeException ignored) {
            }
            pending.result.completeExceptionally(error instanceof CapabilityFailure
                    ? error
                    : new CapabilityFailure("INTERNAL", safeMessage(error), true));
        }
    }

    private void handleRuntimeFileImportResult(int resultCode, Intent data) {
        PendingRuntimeFilePick pending = pendingRuntimeFilePick;
        pendingRuntimeFilePick = null;
        if (pending == null) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pending.result.completeExceptionally(
                    new CapabilityFailure("CANCELLED", "File selection was cancelled", false)
            );
            return;
        }
        Uri uri = data.getData();
        try {
            byte[] bytes = readBytes(uri, pending.maxBytes);
            String displayName = "imported-file";
            try (Cursor cursor = getContentResolver().query(
                    uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null,
                    null,
                    null
            )) {
                if (cursor != null && cursor.moveToFirst()) {
                    String candidate = cursor.getString(0);
                    if (candidate != null && !candidate.trim().isEmpty()) displayName = candidate.trim();
                }
            }
            String blobId = "import." + java.util.UUID.randomUUID().toString().replace("-", "");
            JSONObject stored = storageService.importBlob(
                    pending.pluginId, pending.sessionId, blobId, bytes
            );
            JSONObject file = new JSONObject()
                    .put("name", displayName)
                    .put("mime", getContentResolver().getType(uri) == null
                            ? "application/octet-stream" : getContentResolver().getType(uri))
                    .put("size", bytes.length)
                    .put("blobId", blobId)
                    .put("sha256", stored.optString("sha256", ""));
            pending.result.complete(new JSONObject().put("files", new JSONArray().put(file)));
        } catch (IOException | JSONException | CapabilityFailure error) {
            pending.result.completeExceptionally(error instanceof CapabilityFailure
                    ? error
                    : new CapabilityFailure("INTERNAL", safeMessage(error), true));
        }
    }

    private void handleMigrationBridgeImportSelection(int resultCode, Intent data) {
        if (!"import".equals(activeDataFlow) || dataOperationRunning) return;
        boolean replacing = replacingDataFile;
        replacingDataFile = false;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingDataOwnerFilter = null;
            if (!replacing) releaseDataFlow("import");
            else showToast("已保留原文件与选择");
            invalidateComposeUi();
            return;
        }
        Uri source = data.getData();
        String ownerFilter = pendingDataOwnerFilter;
        pendingDataOwnerFilter = null;
        List<HostTool> previousOwned = migrationBridgeImportOwnedPlugins;
        long generation = ++dataFlowGeneration;
        clearDataOperationError("import");
        inspectingDataFile = true;
        activeDataOwner = ownerFilter;
        List<HostTool> activePlugins = new ArrayList<>(plugins);
        showToast("正在检查数据包…");
        migrationBridgeExecutor.execute(() -> {
            List<HostTool> candidates = new ArrayList<>(activePlugins);
            List<HostTool> ownedPlugins = new ArrayList<>();
            try {
                String selectedFileName = selectedDataFileName(source);
                BackupPackageProbe.Format format;
                try (InputStream input = getContentResolver().openInputStream(source)) {
                    if (input == null) throw new IOException("无法读取数据包");
                    format = BackupPackageProbe.detect(input);
                }
                if (format == BackupPackageProbe.Format.HOST_MIGRATION_V1) {
                    runOnUiThread(() -> {
                        if (isCurrentDataFlow(generation)) {
                            inspectingDataFile = false;
                            if (replacing) showDataOperationError("import", "该文件使用旧迁移格式，请关闭当前选择后单独导入。原文件与选择已保留。");
                            else handleLegacyMigrationPackageFromDataPicker(source);
                        }
                    });
                    return;
                }
                addRuntimeMigrationPlugins(candidates, ownedPlugins);
                List<MigrationBridgeManager.DatasetOption> options;
                DataPackageArchive.ReadResult dataInspection = null;
                BackupArchiveV2.ReadResult bridgeInspection = null;
                if (format == BackupPackageProbe.Format.DATA_PACKAGE_V3) {
                    try (InputStream input = getContentResolver().openInputStream(source)) {
                        if (input == null) throw new IOException("无法读取数据包");
                        dataInspection = DataPackageArchive.inspect(input);
                    }
                    boolean canInstallMissingPlugins = true;
                    Set<String> installedIds = new LinkedHashSet<>();
                    for (PluginPackageStore.InstalledPlugin installed : pluginPackageStore.load()) {
                        installedIds.add(installed.manifest.plugin.id);
                    }
                    options = MigrationBridgeManager.matchDataPackageForImport(
                            this,
                            candidates,
                            dataInspection.items,
                            canInstallMissingPlugins,
                            installedIds
                    );
                    List<MigrationBridgeManager.DatasetOption> supportedProtection = new ArrayList<>();
                    for (MigrationBridgeManager.DatasetOption option : options) {
                        if (option.archiveProtection != DataPackageArchive.Protection.ACCOUNT) {
                            supportedProtection.add(option);
                        }
                    }
                    options = supportedProtection;
                } else {
                    try (InputStream input = getContentResolver().openInputStream(source)) {
                        if (input == null) throw new IOException("无法读取 Bridge 数据包");
                        bridgeInspection = BackupArchiveV2.inspect(input);
                    }
                    options = MigrationBridgeManager.matchForImport(this, candidates, bridgeInspection.datasets);
                }
                if (ownerFilter != null) {
                    List<MigrationBridgeManager.DatasetOption> filtered = new ArrayList<>();
                    for (MigrationBridgeManager.DatasetOption option : options) {
                        if (ownerFilter.equals(option.pluginId)
                                || (option.isPluginPackage() && ownerFilter.equals(option.packagedPluginId()))) filtered.add(option);
                    }
                    options = filtered;
                }
                if (options.isEmpty()) {
                    throw new IOException("数据包中没有当前范围可恢复的数据项目");
                }
                DataPackageArchive.ReadResult finalDataInspection = dataInspection;
                BackupArchiveV2.ReadResult finalBridgeInspection = bridgeInspection;
                List<MigrationBridgeManager.DatasetOption> finalOptions = options;
                runOnUiThread(() -> {
                    if (!isCurrentDataFlow(generation)) { destroyMigrationBridgePlugins(ownedPlugins); return; }
                    inspectingDataFile = false;
                    if (replacing) composeState.getDataDrafts().remove("import");
                    destroyMigrationBridgePlugins(previousOwned);
                    importFileName = selectedFileName;
                    pendingMigrationBridgeImportUri = source;
                    pendingDataPackageInspection = finalDataInspection;
                    pendingMigrationBridgeInspection = finalBridgeInspection;
                    pendingBackupPackageFormat = format;
                    migrationBridgeImportOptions = Collections.unmodifiableList(finalOptions);
                    migrationBridgeImportOwnedPlugins = Collections.unmodifiableList(ownedPlugins);
                    invalidateComposeUi();
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    destroyMigrationBridgePlugins(ownedPlugins);
                    if (isCurrentDataFlow(generation)) {
                        inspectingDataFile = false;
                        showDataOperationError("import", dataOperationFailureMessage("读取数据包失败", error, null));
                    }
                });
            }
        });
    }

    private void handleMigrationBridgeExportResult(int resultCode, Intent data) {
        if (pendingMigrationBridgeExport.isEmpty() || dataOperationRunning || !"export".equals(activeDataFlow)) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingMigrationBridgeExport = Collections.emptyList();
            pendingMigrationBridgeOwnedPlugins = Collections.emptyList();
            clearPendingMigrationBridgePassword();
            showToast("已取消保存，选择与保护方式已保留");
            return;
        }
        Uri destination = data.getData();
        List<MigrationBridgeManager.ExportSelection> selected = pendingMigrationBridgeExport;
        List<HostTool> ownedPlugins = pendingMigrationBridgeOwnedPlugins;
        List<MigrationBridgeManager.DatasetOption> originalOptions = migrationBridgeExportOptions;
        migrationBridgeExportOptions = Collections.emptyList();
        migrationBridgeOwnedPlugins = Collections.emptyList();
        char[] password = pendingMigrationBridgePassword == null
                ? new char[0]
                : pendingMigrationBridgePassword.clone();
        pendingMigrationBridgeExport = Collections.emptyList();
        pendingMigrationBridgeOwnedPlugins = Collections.emptyList();
        clearPendingMigrationBridgePassword();
        String taskId = beginUiTask("导出数据包 · " + selected.size() + " 项",
                selected.stream().map(selection -> selection.option.pluginTitle + " · " + selection.option.descriptor.name).collect(java.util.stream.Collectors.toList()));
        setUiTaskData(taskId, "export", selected.stream().map(selection -> selection.option.key()).collect(java.util.stream.Collectors.toList()), selected.stream().filter(selection -> selection.protection == DataPackageArchive.Protection.PASSWORD).map(selection -> selection.option.key()).collect(java.util.stream.Collectors.toList()), Collections.emptyList(), null);
        dataOperationRunning = true;
        showTasksPageForUi(false);
        migrationBridgeExecutor.execute(() -> {
            boolean completed = false;
            try {
                try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("无法写入目标文件");
                MigrationBridgeManager.writeDataPackage(
                        this,
                        output,
                        getPackageName(),
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                        selected,
                        this::writeHostDataItem,
                        password
                );
                }
                finishUiTask(taskId, true, "已保存 " + selected.size() + " 项数据；本机数据未改变。");
                completed = true;
                runOnUiThread(() -> composeState.getDataDrafts().remove("export"));
                showToast("数据包已导出，共 " + selected.size() + " 个项目");
            } catch (IOException | RuntimeException error) {
                String failure = dataOperationFailureMessage("导出数据失败", error, password);
                finishUiTask(taskId, false, failure);
                runOnUiThread(() -> {
                    migrationBridgeExportOptions = originalOptions;
                    migrationBridgeOwnedPlugins = ownedPlugins;
                    showDataOperationError("export", failure);
                });
            } finally {
                Arrays.fill(password, '\0');
                boolean destroyOwned = completed;
                runOnUiThread(() -> { dataOperationRunning = false; if (destroyOwned) destroyMigrationBridgePlugins(ownedPlugins); });
            }
        });
    }

    private void clearPendingMigrationBridgePassword() {
        if (pendingMigrationBridgePassword != null) {
            Arrays.fill(pendingMigrationBridgePassword, '\0');
        }
        pendingMigrationBridgePassword = null;
    }

    private void clearPendingMigrationBridgeImportPassword() {
        if (pendingMigrationBridgeImportPassword != null) {
            Arrays.fill(pendingMigrationBridgeImportPassword, '\0');
        }
        pendingMigrationBridgeImportPassword = null;
    }

    static String dataOperationFailureMessage(String operation, Throwable error, char[] password) {
        String detail = safeMessage(error);
        if (password != null && password.length > 0) detail = detail.replace(new String(password), "[已隐藏]");
        java.util.regex.Matcher payload = java.util.regex.Pattern.compile("[\\[{]\\s*(?:[\"']|\\{|[-0-9])").matcher(detail);
        if (payload.find()) detail = detail.substring(0, payload.start()) + "[数据内容已隐藏]";
        if (detail.length() > 400) detail = detail.substring(0, 400) + "…";
        return operation + "：" + detail;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static void destroyMigrationBridgePlugins(List<HostTool> plugins) {
        for (HostTool plugin : plugins) {
            try {
                plugin.onDestroy();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void handleImportResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        String pluginId = null;
        PluginPackageStore.InstallSession runtimeSession = null;
        try {
            byte[] packageBytes = readBytes(data.getData(), (int) ContractLimits.MAX_PACKAGE_BYTES);
            if (PluginPackageArchive.hasFormatV3Manifest(packageBytes)) {
                runtimeSession = com.androidtoolsuite.app.plugin.runtime.RuntimePackageInstaller.begin(pluginPackageStore, packageBytes, null);
                pluginId = runtimeSession.pluginId;
                PluginPackageStore.InstalledPlugin installed = pluginPackageStore.find(pluginId);
                if (installed == null) throw new IOException("安装后的新版插件不可读");
                permissionManager.reconcile(installed.manifest);
                boolean wasEnabled = pluginPackageStore.isEnabled(pluginId);
                boolean updating = !runtimeSession.newInstall;
                reloadPluginsKeepingCurrentPage();
                boolean nativeProvider = !installed.manifest.providerEntries.isEmpty();
                if (wasEnabled && !nativeProvider && findPlugin(pluginId) == null) {
                    throw new IOException("新版本插件无法激活");
                }
                pluginPackageStore.confirmInstall(runtimeSession);
                schedulerService.syncPluginAsync(pluginId);
                runtimeSession = null;
                String message;
                message = (updating ? "已更新插件：" : "已导入插件，默认停用：")
                        + installed.manifest.plugin.title;
                if (permissionManager.permissions(installed.manifest).stream()
                        .anyMatch(permission -> permission.state != PluginPermissionManager.State.GRANTED)) {
                    message += "；请在插件详情检查插件权限";
                }
                if (wasEnabled && nativeProvider) message += "；重启应用后启用系统功能";
                showToast(message);
                return;
            }
            throw new IOException("旧版 API1 插件已停止安装，请选择 format v3 插件包");
        } catch (IOException | ContractException e) {
            Log.e("AtsPluginImport", "Plugin import failed", e);
            if (runtimeSession != null) {
                pluginPackageStore.rollbackInstall(runtimeSession);
                permissionManager.reconcile(pluginPackageStore.load());
                reloadPluginsKeepingCurrentPage();
                showToast("导入失败：" + e.getMessage() + "，已恢复上一代插件");
                return;
            }
            if (e.getMessage() != null && e.getMessage().contains("旧版 API1")) runOnUiThread(() -> showComposeDialog("旧插件格式不可用", e.getMessage(), "关闭", "查看迁移说明", () -> navigateForUi(8)));
            else showToast("导入失败：" + e.getMessage());
        }
    }

    private void handleExportResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null || pendingExportPluginId == null) {
            pendingExportPluginId = null;
            return;
        }
        String pluginId = pendingExportPluginId;
        PluginPackageStore.InstalledPlugin runtimePlugin = findRuntimePlugin(pluginId);
        pendingExportPluginId = null;
        if (runtimePlugin == null) {
            showToast("导出失败：插件不存在");
            return;
        }
        try (OutputStream outputStream = getContentResolver().openOutputStream(data.getData())) {
            if (outputStream == null) {
                throw new IOException("无法写入文件");
            }
            outputStream.write(pluginPackageStore.exportPackage(pluginId));
            showToast("已导出插件包");
        } catch (IOException e) {
            showToast("导出失败：" + e.getMessage());
        }
    }

    private void handleLegacyMigrationPackageFromDataPicker(Uri source) {
        try {
            HostMigrationArchive.Snapshot snapshot = HostMigrationArchive.read(
                    readBytes(source, HostMigrationArchive.MAX_ARCHIVE_BYTES)
            );
            legacyBuiltInState(snapshot);
            runOnUiThread(() -> showComposeDialog(
                    "导入旧版 Android Tool Suite 迁移包？",
                    "来源：" + snapshot.sourcePackage + " " + snapshot.sourceVersionName
                            + "\n\n仅恢复应用设置；包内的 " + snapshot.plugins.size() + " 个旧版插件不会安装或启用。"
                            + "插件业务数据请从已有的数据备份恢复。",
                    "取消",
                    "导入",
                    () -> applyMigration(snapshot)
            ));
        } catch (IOException | JSONException error) {
            showToast("读取旧版迁移包失败：" + safeMessage(error));
        }
    }

    private void restoreHostMigrationItem(InputStream input) throws IOException {
        try {
            HostMigrationArchive.Snapshot snapshot = HostMigrationArchive.read(
                    readBytes(input, HostMigrationArchive.MAX_ARCHIVE_BYTES)
            );
            applyMigrationOrThrow(snapshot);
        } catch (JSONException error) {
            throw new IOException("应用迁移快照无效", error);
        }
    }

    private final class HostRestoreCheckpoint implements AutoCloseable {
        private final JSONObject settings;
        private final Set<String> builtIns = new LinkedHashSet<>(builtInPluginStateStore.enabledIds());
        private final Map<String, Boolean> runtimeEnabled = new LinkedHashMap<>();
        private boolean committed;

        HostRestoreCheckpoint() throws IOException {
            try { settings = captureHostSettings(); }
            catch (JSONException error) { throw new IOException("无法保存恢复前的宿主设置", error); }
            for (PluginPackageStore.InstalledPlugin plugin : pluginPackageStore.load()) {
                runtimeEnabled.put(plugin.manifest.plugin.id, plugin.enabled);
            }
        }
        void commit() { committed = true; }
        @Override public void close() throws IOException {
            if (committed) return;
            if (!applyHostSettings(settings) || !builtInPluginStateStore.replaceEnabledIds(builtIns)) {
                throw new IOException("恢复失败且宿主设置回滚未完成");
            }
            for (Map.Entry<String, Boolean> entry : runtimeEnabled.entrySet()) {
                pluginPackageStore.setEnabled(entry.getKey(), entry.getValue());
            }
        }
    }

    private void restoreHostDataItems(
            List<MigrationBridgeManager.ImportSelection> selected,
            Map<String, File> staged,
            Set<String> newPluginIds
    ) throws IOException {
        JSONObject incomingSettings = null;
        DatasetRestoreMode settingsMode = null;
        JSONObject incomingPluginState = null;
        DatasetRestoreMode pluginStateMode = null;
        try {
            for (MigrationBridgeManager.ImportSelection selection : selected) {
                MigrationBridgeManager.DatasetOption option = selection.option;
                File payload = staged.get(option.key());
                if (payload == null || !payload.isFile()) {
                    throw new IOException("应用数据项目未完成暂存：" + option.descriptor.name);
                }
                if (option.kind == DataPackageArchive.ItemKind.HOST_SETTINGS) {
                    JSONObject root = new JSONObject(new String(
                            readFileBytes(payload, 2 * 1024 * 1024),
                            StandardCharsets.UTF_8
                    ));
                    if (root.optInt("formatVersion", 0) != 1 || root.optJSONObject("settings") == null) {
                        throw new IOException("应用设置项目格式无效");
                    }
                    incomingSettings = new JSONObject(root.getJSONObject("settings").toString());
                    settingsMode = selection.restoreMode;
                } else if (option.kind == DataPackageArchive.ItemKind.HOST_PLUGIN_STATE) {
                    JSONObject root = new JSONObject(new String(
                            readFileBytes(payload, 2 * 1024 * 1024),
                            StandardCharsets.UTF_8
                    ));
                    if (root.optInt("formatVersion", 0) != 1) {
                        throw new IOException("插件启用状态项目格式无效");
                    }
                    readPluginState(root, "builtIn");
                    readPluginState(root, "external");
                    incomingPluginState = root;
                    JSONArray states = root.getJSONArray("external");
                    Set<String> keepDisabled = new LinkedHashSet<>(newPluginIds);
                    for (PluginPackageStore.InstalledPlugin installed : pluginPackageStore.load()) {
                        if (!installed.enabled && "trusted-provider".equals(installed.manifest.plugin.kind)) {
                            keepDisabled.add(installed.manifest.plugin.id);
                        }
                    }
                    for (int i = 0; i < states.length(); i++) {
                        JSONObject state = states.getJSONObject(i);
                        if (keepDisabled.remove(state.optString("id"))) state.put("enabled", false);
                    }
                    // Also override historical built-in Shizuku state migrated later via putIfAbsent.
                    for (String id : keepDisabled) {
                        states.put(new JSONObject().put("id", id).put("enabled", false));
                    }
                    pluginStateMode = selection.restoreMode;
                } else if (option.kind == DataPackageArchive.ItemKind.HOST_PLUGIN_PACKAGE) {
                    // Already installed by RuntimePackageRestore using the standard local pipeline.
                    continue;
                }
            }

            JSONObject previousHost = captureHostSettings();
            Set<String> previousBuiltIns = builtInPluginStateStore.enabledIds();
            validateHostDataDependencies(incomingPluginState, pluginStateMode);

            JSONObject finalIncomingSettings = incomingSettings;
            DatasetRestoreMode finalSettingsMode = settingsMode;
            JSONObject finalPluginState = incomingPluginState;
            DatasetRestoreMode finalPluginStateMode = pluginStateMode;
            List<MigrationTransaction.Operation> operations = new ArrayList<>();
            if (finalPluginState != null) {
                operations.add(new MigrationTransaction.Operation() {
                    @Override
                    public void apply() throws IOException {
                        applyPluginState(finalPluginState, finalPluginStateMode);
                    }

                    @Override
                    public void rollback() throws IOException {
                        if (!builtInPluginStateStore.replaceEnabledIds(previousBuiltIns)) {
                            throw new IOException("无法恢复内置插件状态");
                        }
                    }
                });
            }
            if (finalIncomingSettings != null) {
                JSONObject target = finalSettingsMode == DatasetRestoreMode.MERGE
                        ? mergeHostSettings(previousHost, finalIncomingSettings,
                        referencedPluginIds(finalIncomingSettings))
                        : finalIncomingSettings;
                operations.add(new MigrationTransaction.Operation() {
                    @Override
                    public void apply() throws IOException {
                        if (!applyHostSettings(target)) throw new IOException("无法保存应用设置");
                    }

                    @Override
                    public void rollback() throws IOException {
                        if (!applyHostSettings(previousHost)) throw new IOException("无法恢复应用设置");
                    }
                });
            }
            MigrationTransaction.execute(operations);
        } catch (JSONException error) {
            throw new IOException("应用数据项目无效", error);
        }
    }

    private Map<String, Boolean> readPluginState(JSONObject root, String name) throws IOException {
        JSONArray array = root.optJSONArray(name);
        if (array == null) throw new IOException("插件启用状态缺少 " + name);
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) throw new IOException("插件启用状态记录无效");
            String id = item.optString("id", "").trim();
            if (!id.matches("[A-Za-z0-9._-]+") || result.put(id, item.optBoolean("enabled")) != null) {
                throw new IOException("插件启用状态包含无效或重复 ID");
            }
        }
        return result;
    }

    private boolean areRuntimeRequirementsSatisfied(
            RuntimePluginManifest manifest,
            Map<String, String> activeVersions
    ) {
        if (manifest.plugin.minHostVersionCode > BuildConfig.VERSION_CODE
                || manifest.plugin.minAndroidApi > Build.VERSION.SDK_INT
                || (!manifest.toolContributions.isEmpty()
                && manifest.defaultUiEntry() == null)) {
            return false;
        }
        for (RuntimePluginManifest.BackgroundEntry entry : manifest.backgroundEntries) {
            if (entry.required && !isBackgroundEntryAvailable(manifest.plugin.id, entry)) return false;
        }
        for (RuntimePluginManifest.Requirement requirement : manifest.pluginRequirements) {
            if (!requirement.optional
                    && !isVersionRequirementSatisfied(activeVersions.get(requirement.id), requirement.version)) {
                return false;
            }
        }
        for (RuntimePluginManifest.CapabilityRequirement requirement : manifest.capabilityRequirements) {
            if (!requirement.optional
                    && !capabilityRouter.canResolve(requirement.id, requirement.version)) {
                return false;
            }
        }
        return true;
    }

    private boolean isBackgroundEntryAvailable(
            String pluginId,
            RuntimePluginManifest.BackgroundEntry entry
    ) {
        if ("provider-task".equals(entry.type)) {
            return backgroundTaskRegistry.contains(pluginId, entry.entry);
        }
        if ("javascript-worker".equals(entry.type)) {
            return JavaScriptWorkerEngine.isSupported();
        }
        return false;
    }

    private static boolean isVersionRequirementSatisfied(String actual, String requirement) {
        return CapabilityRouter.versionSatisfied(actual, requirement);
    }

    private void applyPluginState(JSONObject root, DatasetRestoreMode mode) throws IOException {
        Map<String, Boolean> incomingBuiltIns = readPluginState(root, "builtIn");
        Map<String, Boolean> incomingExternal = readPluginState(root, "external");
        migrateLegacyShizukuState(incomingBuiltIns, incomingExternal);
        Set<String> knownBuiltIns = builtInPluginIds();
        if (!knownBuiltIns.containsAll(incomingBuiltIns.keySet())) {
            throw new IOException("插件启用状态包含未知内置插件");
        }
        Set<String> resultingBuiltIns = mode == DatasetRestoreMode.MERGE
                ? new LinkedHashSet<>(builtInPluginStateStore.enabledIds())
                : new LinkedHashSet<>();
        for (Map.Entry<String, Boolean> entry : incomingBuiltIns.entrySet()) {
            if (entry.getValue()) resultingBuiltIns.add(entry.getKey());
            else resultingBuiltIns.remove(entry.getKey());
        }
        if (!builtInPluginStateStore.replaceEnabledIds(resultingBuiltIns)) {
            throw new IOException("无法保存内置插件状态");
        }
        for (PluginPackageStore.InstalledPlugin installed : pluginPackageStore.load()) {
            String pluginId = installed.manifest.plugin.id;
            if (mode == DatasetRestoreMode.REPLACE || incomingExternal.containsKey(pluginId)) {
                pluginPackageStore.setEnabled(pluginId, incomingExternal.getOrDefault(pluginId, false));
                schedulerService.syncPluginAsync(pluginId);
            }
        }
    }

    private static void migrateLegacyShizukuState(
            Map<String, Boolean> builtIns,
            Map<String, Boolean> external
    ) {
        Boolean enabled = builtIns.remove("shizuku_auth");
        if (enabled != null) external.putIfAbsent("shizuku_auth", enabled);
    }

    private void validateHostDataDependencies(
            JSONObject pluginState,
            DatasetRestoreMode mode
    ) throws IOException {
        Set<String> enabledBuiltIns = new LinkedHashSet<>(builtInPluginStateStore.enabledIds());
        Set<String> enabledExternal = new LinkedHashSet<>();
        for (PluginPackageStore.InstalledPlugin installed : pluginPackageStore.load()) {
            if (installed.enabled) enabledExternal.add(installed.manifest.plugin.id);
        }
        if (pluginState != null) {
            Map<String, Boolean> incomingBuiltIns = readPluginState(pluginState, "builtIn");
            Map<String, Boolean> incomingExternal = readPluginState(pluginState, "external");
            migrateLegacyShizukuState(incomingBuiltIns, incomingExternal);
            Set<String> knownBuiltIns = builtInPluginIds();
            if (!knownBuiltIns.containsAll(incomingBuiltIns.keySet())) {
                throw new IOException("插件启用状态包含未知内置插件");
            }
            if (mode == DatasetRestoreMode.REPLACE) {
                enabledBuiltIns.clear();
                enabledExternal.clear();
            }
            applyEnabledState(enabledBuiltIns, incomingBuiltIns);
            applyEnabledState(enabledExternal, incomingExternal);
        }

        Set<String> resultingExternalIds = new LinkedHashSet<>();
        for (PluginPackageStore.InstalledPlugin installed : pluginPackageStore.load()) {
            resultingExternalIds.add(installed.manifest.plugin.id);
        }
        enabledExternal.retainAll(resultingExternalIds);

        Map<String, String> activeVersions = new LinkedHashMap<>();
        List<HostTool> builtIns = ToolRegistry.createBuiltInPlugins();
        try {
            for (HostTool plugin : builtIns) {
                if (enabledBuiltIns.contains(plugin.id())) {
                    activeVersions.put(plugin.id(), plugin.version());
                }
            }
            for (String pluginId : enabledExternal) {
                PluginPackageStore.InstalledPlugin installed = pluginPackageStore.find(pluginId);
                if (installed != null) activeVersions.put(pluginId, installed.manifest.plugin.version);
            }
            for (HostTool plugin : builtIns) {
                if (enabledBuiltIns.contains(plugin.id())
                        && !areDependenciesSatisfied(plugin.dependencies(), activeVersions)) {
                    throw new IOException(plugin.title() + " 的依赖未满足");
                }
            }
            for (PluginPackageStore.InstalledPlugin installed : pluginPackageStore.load()) {
                if (!enabledExternal.contains(installed.manifest.plugin.id)) continue;
                for (RuntimePluginManifest.Requirement requirement : installed.manifest.pluginRequirements) {
                    if (!requirement.optional
                            && !isVersionRequirementSatisfied(activeVersions.get(requirement.id), requirement.version)) {
                        throw new IOException(installed.manifest.plugin.title + " 缺少依赖 " + requirement.id);
                    }
                }
            }
        } finally {
            for (HostTool plugin : builtIns) plugin.onDestroy();
        }
    }

    private static void applyEnabledState(
            Set<String> target,
            Map<String, Boolean> incoming
    ) {
        for (Map.Entry<String, Boolean> entry : incoming.entrySet()) {
            if (entry.getValue()) target.add(entry.getKey());
            else target.remove(entry.getKey());
        }
    }

    private Set<String> referencedPluginIds(JSONObject host) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String key : Arrays.asList("hiddenTools", "toolOrder")) {
            ids.addAll(jsonStrings(host.optJSONArray(key)));
        }
        for (String key : Arrays.asList("hiddenWidgets", "widgetOrder", "fullWidthWidgets", "widgetSizes")) {
            for (String value : jsonStrings(host.optJSONArray(key))) {
                int separator = value.indexOf(':');
                if (separator > 0) ids.add(value.substring(0, separator));
            }
        }
        return ids;
    }

    private List<MigrationBridgeManager.ImportSelection> resolveDataPackageOptionsAfterHost(
            List<MigrationBridgeManager.ImportSelection> selected,
            List<HostTool> ownedPlugins
    ) throws IOException {
        List<HostTool> candidates = new ArrayList<>();
        for (HostTool plugin : plugins) {
            if (isBuiltInPluginId(plugin.id())) candidates.add(plugin);
        }
        addRuntimeMigrationPlugins(candidates, ownedPlugins);
        return MigrationBridgeManager.resolveDataPackageImportBridges(this, candidates, selected);
    }

    private void addRuntimeMigrationPlugins(List<HostTool> candidates, List<HostTool> ownedPlugins) {
        for (PluginPackageStore.InstalledPlugin installed : pluginPackageStore.load()) {
            if (installed.manifest.datasets.isEmpty()) continue;
            HostTool adapter = new MigrationToolPlugin(installed, datasetService);
            candidates.add(adapter);
            ownedPlugins.add(adapter);
        }
    }

    @Override
    public boolean isImportedPluginEnabled(String pluginId) {
        return findRuntimePlugin(pluginId) != null && pluginPackageStore.isEnabled(pluginId);
    }

    @Override
    public void setImportedPluginEnabled(String pluginId, boolean enabled) {
        PluginPackageStore.InstalledPlugin runtimePlugin = findRuntimePlugin(pluginId);
        if (runtimePlugin == null) {
            showToast("插件不存在");
            return;
        }
        if (enabled) {
            List<String> missingDependencies = findMissingRuntimeRequirements(runtimePlugin.manifest);
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
        pluginPackageStore.setEnabled(pluginId, enabled);
        schedulerService.syncPluginAsync(pluginId);
        if (!enabled && !runtimePlugin.manifest.providerEntries.isEmpty()) {
            nativeProviderManager.deactivate(pluginId);
        }
        reloadPlugins(null);
        PluginPackageStore.InstalledPlugin reloadedRuntime = findRuntimePlugin(pluginId);
        if (enabled && reloadedRuntime != null && !reloadedRuntime.manifest.providerEntries.isEmpty()
                && nativeProviderManager.isPendingRestart(reloadedRuntime)) {
            requestPluginRestartForUi(pluginId);
            return;
        }
        if (enabled && !isPluginLoadedForUi(pluginId)) {
            pluginPackageStore.setEnabled(pluginId, false);
            schedulerService.syncPluginAsync(pluginId);
            reloadPlugins(null);
            showToast("插件无法激活，已保持停用");
            return;
        }
        showToast(enabled ? "已启用插件" : "已停用插件");
    }

    @Override
    public List<HostTool> optionalBuiltInPlugins() {
        return ToolRegistry.createOptionalBuiltInPlugins();
    }

    @Override
    public List<HostTool> installedPlugins() {
        return new ArrayList<>(plugins);
    }

    @Override
    public boolean isBuiltInPluginEnabled(String pluginId) {
        return builtInPluginStateStore.isEnabled(pluginId);
    }

    @Override
    public void setBuiltInPluginEnabled(String pluginId, boolean enabled) {
        HostTool target = findBuiltInPlugin(pluginId);
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
        for (HostTool plugin : ToolRegistry.createBuiltInPlugins()) {
            if (plugin.id().equals(pluginId)) {
                return true;
            }
        }
        return false;
    }

    private HostTool findBuiltInPlugin(String pluginId) {
        for (HostTool plugin : ToolRegistry.createBuiltInPlugins()) {
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
        for (HostTool plugin : plugins) {
            activeVersions.put(plugin.id(), plugin.version());
        }
        for (PluginPackageStore.InstalledPlugin installed : pluginPackageStore.load()) {
            if (installed.enabled
                    && "trusted-provider".equals(installed.manifest.plugin.kind)
                    && nativeProviderManager.isActive(
                            installed.manifest.plugin.id,
                            installed.generationDirectory.getName())) {
                activeVersions.put(installed.manifest.plugin.id, installed.manifest.plugin.version);
            }
            if (installed.enabled
                    && installed.manifest.capabilityContributions.stream()
                    .anyMatch(item -> !item.workerEntry.isEmpty())
                    && pluginRuntime.workerProviders().isActive(
                            installed.manifest.plugin.id,
                            installed.generationDirectory.getName())) {
                activeVersions.put(installed.manifest.plugin.id, installed.manifest.plugin.version);
            }
        }
        return activeVersions;
    }

    private String pluginTitleOrId(String pluginId) {
        HostTool builtInPlugin = findBuiltInPlugin(pluginId);
        if (builtInPlugin != null) {
            return builtInPlugin.title();
        }
        PluginPackageStore.InstalledPlugin runtimePlugin = findRuntimePlugin(pluginId);
        return runtimePlugin == null ? pluginId : runtimePlugin.manifest.plugin.title;
    }

    private List<String> findDependentPluginTitles(String pluginId) {
        List<String> dependents = new ArrayList<>();
        for (HostTool plugin : ToolRegistry.createBuiltInPlugins()) {
            if (!plugin.id().equals(pluginId)
                    && builtInPluginStateStore.isEnabled(plugin.id())
                    && dependsOn(plugin.dependencies(), pluginId)) {
                dependents.add(plugin.title());
            }
        }
        for (PluginPackageStore.InstalledPlugin installed : pluginPackageStore.load()) {
            if (!installed.enabled || installed.manifest.plugin.id.equals(pluginId)) continue;
            for (RuntimePluginManifest.Requirement requirement : installed.manifest.pluginRequirements) {
                if (!requirement.optional && requirement.id.equals(pluginId)) {
                    dependents.add(installed.manifest.plugin.title);
                    break;
                }
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

    private PluginPackageStore.InstalledPlugin findRuntimePlugin(String pluginId) {
        return pluginId == null ? null : runtimeInstalledCache.get(pluginId);
    }

    private ImportedPluginDescriptor toUiDescriptor(PluginPackageStore.InstalledPlugin installed) {
        RuntimePluginManifest manifest = installed.manifest;
        LinkedHashSet<String> dependencies = new LinkedHashSet<>();
        for (RuntimePluginManifest.Requirement requirement : manifest.pluginRequirements) {
            if (!requirement.optional) dependencies.add(runtimeDependencyLabel(requirement));
        }
        return new ImportedPluginDescriptor(
                manifest.plugin.id,
                manifest.plugin.title,
                manifest.plugin.description,
                manifest.plugin.version,
                manifest.plugin.versionCode,
                manifest.plugin.minHostVersionCode,
                "plugin-runtime",
                manifest.plugin.publisher,
                "3",
                "",
                "",
                dependencies,
                Collections.emptyList()
        );
    }

    private static String runtimeDependencyLabel(RuntimePluginManifest.Requirement requirement) {
        String version = requirement.version == null ? "" : requirement.version.trim();
        if (version.isEmpty() || "*".equals(version)) return requirement.id;
        if (version.startsWith(">") || version.startsWith("<") || version.startsWith("=")) {
            return requirement.id + version;
        }
        return requirement.id + "=" + version;
    }

    private List<String> findMissingRuntimeRequirements(RuntimePluginManifest manifest) {
        Map<String, String> activeVersions = activePluginVersions();
        List<String> missing = new ArrayList<>();
        if (manifest.plugin.minHostVersionCode > BuildConfig.VERSION_CODE) {
            missing.add("Android Tool Suite " + manifest.plugin.minHostVersionCode + "+");
        }
        if (manifest.plugin.minAndroidApi > Build.VERSION.SDK_INT) {
            missing.add("Android " + manifest.plugin.minAndroidApi + "+");
        }
        if (!manifest.toolContributions.isEmpty()
                && manifest.defaultUiEntry() == null) {
            missing.add("当前版本需要 Web 或声明式工具入口");
        }
        for (RuntimePluginManifest.BackgroundEntry entry : manifest.backgroundEntries) {
            if (entry.required && !isBackgroundEntryAvailable(manifest.plugin.id, entry)) {
                missing.add("后台运行时 " + entry.type);
            }
        }
        for (RuntimePluginManifest.Requirement requirement : manifest.pluginRequirements) {
            if (!requirement.optional
                    && !isVersionRequirementSatisfied(activeVersions.get(requirement.id), requirement.version)) {
                missing.add(pluginTitleOrId(requirement.id) + "（需要 "
                        + runtimeDependencyLabel(requirement) + "）");
            }
        }
        for (RuntimePluginManifest.CapabilityRequirement requirement : manifest.capabilityRequirements) {
            if (!requirement.optional
                    && !capabilityRouter.canResolve(requirement.id, requirement.version)
                    && !providesCompatibleCapability(manifest, requirement)) {
                missing.add("能力 " + requirement.id + " " + requirement.version);
            }
        }
        return missing;
    }

    private static boolean providesCompatibleCapability(
            RuntimePluginManifest manifest,
            RuntimePluginManifest.CapabilityRequirement requirement
    ) {
        for (RuntimePluginManifest.CapabilityContribution contribution : manifest.capabilityContributions) {
            if (contribution.id.equals(requirement.id)
                    && isVersionRequirementSatisfied(contribution.version, requirement.version)) {
                return true;
            }
        }
        return false;
    }

    private List<MigrationBridgeManager.DatasetOption> createHostDataExportOptions()
            throws JSONException {
        List<MigrationBridgeManager.DatasetOption> result = new ArrayList<>();
        result.add(MigrationBridgeManager.hostSettingsExportOption(
                captureHostSettings().toString().getBytes(StandardCharsets.UTF_8).length
        ));
        result.add(MigrationBridgeManager.hostPluginStateExportOption(
                capturePluginState().toString().getBytes(StandardCharsets.UTF_8).length
        ));
        for (PluginPackageStore.InstalledPlugin installed : pluginPackageStore.load()) {
            result.add(MigrationBridgeManager.hostPluginPackageExportOption(
                    installed.manifest.plugin.id, installed.manifest.plugin.title, installed.packageFile.length()));
        }
        return result;
    }

    private JSONObject capturePluginState() throws JSONException {
        JSONObject root = new JSONObject().put("formatVersion", 1);
        JSONArray builtIns = new JSONArray();
        Set<String> enabledBuiltIns = builtInPluginStateStore.enabledIds();
        for (String id : builtInPluginIds()) {
            builtIns.put(new JSONObject().put("id", id).put("enabled", enabledBuiltIns.contains(id)));
        }
        JSONArray external = new JSONArray();
        for (PluginPackageStore.InstalledPlugin installed : pluginPackageStore.load()) {
            external.put(new JSONObject()
                    .put("id", installed.manifest.plugin.id)
                    .put("enabled", installed.enabled));
        }
        return root.put("builtIn", builtIns).put("external", external);
    }

    private void writeHostDataItem(
            MigrationBridgeManager.DatasetOption option,
            OutputStream output
    ) throws IOException {
        try {
            if (option.kind == DataPackageArchive.ItemKind.HOST_SETTINGS) {
                output.write(new JSONObject()
                        .put("formatVersion", 1)
                        .put("settings", captureHostSettings())
                        .toString()
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (option.kind == DataPackageArchive.ItemKind.HOST_PLUGIN_STATE) {
                output.write(capturePluginState().toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (option.kind == DataPackageArchive.ItemKind.HOST_PLUGIN_PACKAGE) {
                String pluginId = option.packagedPluginId();
                output.write(pluginPackageStore.exportPackage(pluginId));
                return;
            }
            throw new IOException("不支持的应用数据项目：" + option.descriptor.name);
        } catch (JSONException error) {
            throw new IOException("无法生成应用数据项目：" + option.descriptor.name, error);
        }
    }

    private Set<String> legacyBuiltInState(HostMigrationArchive.Snapshot snapshot) throws IOException {
        Set<String> enabled = new LinkedHashSet<>(snapshot.builtInEnabledIds);
        // The former built-in Shizuku tool is now a separately consented trusted provider.
        enabled.remove("shizuku_auth");
        if (!builtInPluginIds().containsAll(enabled)) {
            throw new IOException("迁移包包含未知内置插件状态");
        }
        return enabled;
    }

    private void applyMigration(
            HostMigrationArchive.Snapshot snapshot
    ) {
        try {
            applyMigrationOrThrow(snapshot);
            reloadPlugins(null);
            updateCatalog = null;
            checkForUpdates(true, false, false);
            showToast("已恢复旧版应用设置；旧版插件未安装或启用");
        } catch (IOException | JSONException error) {
            reloadPlugins(null);
            String rollbackStatus = error.getSuppressed().length == 0
                    ? "已恢复原状态"
                    : "回滚不完整，请勿继续操作并重新导入";
            showToast("迁移失败，" + rollbackStatus + "：" + error.getMessage());
        }
    }

    private void applyMigrationOrThrow(
            HostMigrationArchive.Snapshot snapshot
    ) throws IOException, JSONException {
        Set<String> enabledBuiltIns = legacyBuiltInState(snapshot);
        JSONObject previousHost = captureHostSettings();
        Set<String> previousBuiltIns = builtInPluginStateStore.enabledIds();
        JSONObject mergedHost = mergeHostSettings(previousHost, snapshot.host, builtInPluginIds());

        List<MigrationTransaction.Operation> operations = new ArrayList<>();
        operations.add(new MigrationTransaction.Operation() {
            @Override
            public void apply() throws IOException {
                if (!builtInPluginStateStore.replaceEnabledIds(enabledBuiltIns)) {
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
    }

    private Set<String> builtInPluginIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (HostTool plugin : ToolRegistry.createBuiltInPlugins()) {
            ids.add(plugin.id());
        }
        return ids;
    }

    private JSONObject captureHostSettings() throws JSONException {
        JSONObject host = new JSONObject();
        host.put("theme", themePreferenceForUi());
        host.put("color", colorPreferenceForUi());
        host.put("widgetInstances", HomeWidgetInstances.encode(homeWidgetInstances.all()));
        host.put("folders", new JSONArray(uiPreferences.getString("collection_folders","[]")));
        host.put("gridLayout",new JSONObject(uiPreferences.getString("home_grid_layout","{}")));
        host.put("widgetMigrations", strings(uiPreferences.getStringSet("widget_instances_migrated",Collections.emptySet())));
        host.put("pinnedTools", strings(new LinkedHashSet<>(pinnedToolIdsForUi())));
        host.put("autoCheckUpdates", autoCheckUpdatesForUi());
        host.put("autoCheckAppUpdates", uiPreferences.getBoolean(PREF_AUTO_CHECK_APP_UPDATES, true));
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
        merged.put("theme", incoming.optString("theme", current.optString("theme", "system")));
        merged.put("color", incoming.optString("color", current.optString("color", "brand")));
        merged.put("autoCheckAppUpdates", incoming.optBoolean("autoCheckAppUpdates", current.optBoolean("autoCheckAppUpdates", true)));
        merged.put("autoCheckUpdates", incoming.optBoolean(
                "autoCheckUpdates",
                current.optBoolean("autoCheckUpdates", true)
        ));
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
        if (incoming.has("widgetInstances")) {
            List<HomeWidgetInstances.Instance> instances = new ArrayList<>();
            for (HomeWidgetInstances.Instance item : HomeWidgetInstances.parse(current.optJSONArray("widgetInstances") == null ? new JSONArray() : current.getJSONArray("widgetInstances"))) if (!migratedIds.contains(item.pluginId)) instances.add(item);
            for (HomeWidgetInstances.Instance item : HomeWidgetInstances.parse(incoming.getJSONArray("widgetInstances"))) if (migratedIds.contains(item.pluginId)) instances.add(item);
            merged.put("widgetInstances", HomeWidgetInstances.encode(instances));
            Set<String> widgetMigrations=new LinkedHashSet<>(jsonStrings(current.optJSONArray("widgetMigrations")));
            widgetMigrations.removeAll(migratedIds);
            for(String id:jsonStrings(incoming.optJSONArray("widgetMigrations")))if(migratedIds.contains(id))widgetMigrations.add(id);
            for(HomeWidgetInstances.Instance item:instances)widgetMigrations.add(item.pluginId);
            merged.put("widgetMigrations",strings(widgetMigrations));
        }
        if (incoming.has("pinnedTools")) merged.put("pinnedTools", strings(mergeScopedOrder(jsonStrings(incoming.optJSONArray("pinnedTools")),jsonStrings(current.optJSONArray("pinnedTools")),migratedIds,false)));
        if (incoming.has("folders")) merged.put("folders",HostFolders.encode(HostFolders.merge(HostFolders.decode(current.optJSONArray("folders")),HostFolders.decode(incoming.getJSONArray("folders")))));
        if (incoming.has("folders")) {
            Set<String> folderKeys=new LinkedHashSet<>();for(HostFolders.Folder folder:HostFolders.decode(merged.getJSONArray("folders")))if(HostFolders.HOME.equals(folder.scope))folderKeys.add(folder.key());
            Set<String> sizes=new LinkedHashSet<>(jsonStrings(merged.optJSONArray("widgetSizes")));
            for(String value:jsonStrings(incoming.optJSONArray("widgetSizes"))) {
                int separator=value.indexOf('=');String key=separator<0?"":value.substring(0,separator);
                if(folderKeys.contains(key)&&sizes.stream().noneMatch(existing->existing.startsWith(key+"=")))sizes.add(value);
            }
            merged.put("widgetSizes",strings(sizes));
            Set<String> order=new LinkedHashSet<>(jsonStrings(merged.optJSONArray("widgetOrder")));
            for(String value:jsonStrings(incoming.optJSONArray("widgetOrder")))if(folderKeys.contains(value))order.add(value);
            merged.put("widgetOrder",strings(order));
        }
        if (incoming.has("gridLayout")) merged.put("gridLayout",HomeGridLayout.merge(current.optJSONObject("gridLayout")==null?new JSONObject():current.getJSONObject("gridLayout"),incoming.getJSONObject("gridLayout")));
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
        if(host.has("folders"))try { HostFolders.decode(host.getJSONArray("folders")); }catch(JSONException error){return false;}
        if(host.has("gridLayout"))try { HomeGridLayout.validate(host.getJSONObject("gridLayout")); }catch(JSONException error){return false;}
        if (host.has("widgetInstances") && !(host.opt("widgetInstances") instanceof JSONArray)) return false;
        JSONArray instances = host.optJSONArray("widgetInstances");
        if(host.has("widgetMigrations") && !(host.opt("widgetMigrations") instanceof JSONArray))return false;
        Set<String> migrated = new LinkedHashSet<>(jsonStrings(host.optJSONArray("widgetMigrations")));
        if(migrated.size()>128 || migrated.stream().anyMatch(id->!id.matches("[A-Za-z0-9._-]{1,128}")))return false;
        // An older backup with an explicit instance list is already a complete layout.
        // Do not reinterpret its empty list as a request to create legacy defaults.
        if(instances!=null && !host.has("widgetMigrations"))for(PluginPackageStore.InstalledPlugin item:pluginPackageStore.load())
            if(!item.manifest.homeWidgetContributions.isEmpty())migrated.add(item.manifest.plugin.id);
        if (instances != null) try { for (HomeWidgetInstances.Instance item : HomeWidgetInstances.parse(instances)) migrated.add(item.pluginId); }
        catch (JSONException error) { return false; }
        SharedPreferences.Editor editor = uiPreferences.edit()
                .putString(HomeWidgetInstances.KEY, instances == null ? "[]" : instances.toString())
                .putStringSet("widget_instances_migrated", migrated)
                .putString("pinned_tools", String.join("\n",jsonStrings(host.optJSONArray("pinnedTools"))))
                .putString("collection_folders",host.optJSONArray("folders")==null?"[]":host.optJSONArray("folders").toString())
                .putString("home_grid_layout",host.optJSONObject("gridLayout")==null?"{}":host.optJSONObject("gridLayout").toString())
                .putString(
                        PREF_THEME,
                        Arrays.asList("system", "light", "dark").contains(host.optString("theme"))
                                ? host.optString("theme") : "system"
                )
                .putString(PREF_COLOR, "dynamic".equals(host.optString("color")) ? "dynamic" : "brand")
                .putBoolean(PREF_AUTO_CHECK_UPDATES, host.optBoolean("autoCheckUpdates", true))
                .putBoolean(PREF_AUTO_CHECK_APP_UPDATES, host.optBoolean("autoCheckAppUpdates", host.optBoolean("autoCheckUpdates", true)))
                .putStringSet(PREF_HIDDEN_WIDGETS, jsonStrings(host.optJSONArray("hiddenWidgets")))
                .putStringSet(PREF_HIDDEN_TOOLS, jsonStrings(host.optJSONArray("hiddenTools")))
                .putString(PREF_TOOL_ORDER, String.join("\n", jsonStrings(host.optJSONArray("toolOrder"))))
                .putString(PREF_WIDGET_ORDER, String.join("\n", jsonStrings(host.optJSONArray("widgetOrder"))))
                .putStringSet(PREF_FULL_WIDTH_WIDGETS, jsonStrings(host.optJSONArray("fullWidthWidgets")))
                .putStringSet(PREF_WIDGET_SIZES, jsonStrings(host.optJSONArray("widgetSizes")));
        boolean committed = editor.commit();
        if (committed) syncSuiteThemePreferences();
        return committed;
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

    private byte[] readBytes(Uri uri) throws IOException {
        return readBytes(uri, Integer.MAX_VALUE);
    }

    private byte[] readBytes(Uri uri, int limit) throws IOException {
        InputStream inputStream = getContentResolver().openInputStream(uri);
        if (inputStream == null) {
            throw new IOException("无法读取插件文件");
        }
        try (InputStream stream = inputStream) {
            return readBytes(stream, limit);
        }
    }

    private static byte[] readBytes(InputStream stream, int limit) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
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

    private static byte[] readFileBytes(File file, int limit) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return readBytes(input, limit);
        }
    }

    private static final class WidgetRegistration {
        final String pluginTitle;
        final String key;
        final HostHomeWidget widget;

        WidgetRegistration(String pluginTitle, String key, HostHomeWidget widget) {
            this.pluginTitle = pluginTitle;
            this.key = key;
            this.widget = widget;
        }
    }

    private static final class PendingRuntimeFilePick {
        final String pluginId;
        final String sessionId;
        final int maxBytes;
        final CompletableFuture<JSONObject> result;

        PendingRuntimeFilePick(
                String pluginId,
                String sessionId,
                int maxBytes,
                CompletableFuture<JSONObject> result
        ) {
            this.pluginId = pluginId;
            this.sessionId = sessionId;
            this.maxBytes = maxBytes;
            this.result = result;
        }
    }

    private static final class PendingRuntimeFileExport {
        final String pluginId;
        final String sessionId;
        final String blobId;
        final int maxBytes;
        final long size;
        final String sha256;
        final CompletableFuture<JSONObject> result;

        PendingRuntimeFileExport(
                String pluginId,
                String sessionId,
                String blobId,
                int maxBytes,
                long size,
                String sha256,
                CompletableFuture<JSONObject> result
        ) {
            this.pluginId = pluginId;
            this.sessionId = sessionId;
            this.blobId = blobId;
            this.maxBytes = maxBytes;
            this.size = size;
            this.sha256 = sha256;
            this.result = result;
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (!file.delete()) file.deleteOnExit();
    }

}
