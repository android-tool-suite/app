package com.example.shizukuaccessibilitygrant;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQUEST_SHIZUKU = 3001;
    private static final String PREFS_NAME = "accessibility_grant";
    private static final String PREF_FAVORITES = "favorites";
    private static final String PREF_AUTO_GRANT = "auto_grant_favorites";
    private static final String SETTINGS_COMMAND = "/system/bin/settings";
    private static final String ENABLED_ACCESSIBILITY_SERVICES = "enabled_accessibility_services";
    private static final String ACCESSIBILITY_ENABLED = "accessibility_enabled";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SharedPreferences preferences;
    private Set<String> favoriteComponents = new LinkedHashSet<>();
    private List<AccessibilityServiceEntry> allServices = new ArrayList<>();
    private TextView statusView;
    private LinearLayout serviceList;
    private Button permissionButton;
    private Button refreshButton;
    private EditText searchBox;
    private CheckBox favoritesOnlyCheckBox;
    private CheckBox autoGrantCheckBox;
    private ProgressBar progressBar;
    private IShellService shellService;
    private Shizuku.UserServiceArgs shellServiceArgs;
    private boolean shellServiceBinding;
    private boolean autoGrantAttempted;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> runOnUiThread(this::refreshState);
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> runOnUiThread(() -> {
        shellService = null;
        shellServiceBinding = false;
        statusView.setText("Shizuku 未连接，请确认 Shizuku 正在运行");
        serviceList.removeAllViews();
    });
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        if (requestCode == REQUEST_SHIZUKU) {
            runOnUiThread(this::refreshState);
        }
    };
    private final ServiceConnection shellConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            shellService = IShellService.Stub.asInterface(service);
            shellServiceBinding = false;
            runOnUiThread(MainActivity.this::refreshState);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            shellService = null;
            shellServiceBinding = false;
            runOnUiThread(() -> {
                statusView.setText("Shizuku UserService 已断开");
                serviceList.removeAllViews();
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        favoriteComponents = new LinkedHashSet<>(preferences.getStringSet(PREF_FAVORITES, Collections.emptySet()));
        setContentView(createContentView());

        Shizuku.addBinderReceivedListener(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        refreshState();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        unbindShellService();
        executor.shutdownNow();
        super.onDestroy();
    }

    private View createContentView() {
        int gap = dp(12);
        int horizontal = dp(16);
        int topPadding = dp(18);
        int background = 0xFFF8FAF9;

        Window window = getWindow();
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(horizontal, topPadding, horizontal, dp(12));
        root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(horizontal, topPadding + insets.getSystemWindowInsetTop(), horizontal, dp(12));
            return insets.consumeSystemWindowInsets();
        });

        TextView title = new TextView(this);
        title.setText("Shizuku 无障碍授权");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF10201D);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView warning = new TextView(this);
        warning.setText("只给你完全信任的应用开启无障碍权限。该权限可读取屏幕内容并执行点击、滑动等操作。");
        warning.setTextSize(14);
        warning.setTextColor(0xFF5B6663);
        LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(-1, -2);
        warningParams.topMargin = dp(6);
        root.addView(warning, warningParams);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTextColor(0xFF0F3B35);
        statusView.setPadding(0, gap, 0, gap);
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        permissionButton = new Button(this);
        permissionButton.setText("授权 Shizuku");
        permissionButton.setOnClickListener(v -> requestShizukuPermission());
        actions.addView(permissionButton, new LinearLayout.LayoutParams(0, dp(46), 1));

        refreshButton = new Button(this);
        refreshButton.setText("刷新");
        refreshButton.setOnClickListener(v -> refreshState());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        refreshParams.leftMargin = gap;
        actions.addView(refreshButton, refreshParams);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setTextSize(15);
        searchBox.setHint("搜索应用、服务或包名");
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchBox.setPadding(dp(12), 0, dp(12), 0);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                showServices(allServices);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(48));
        searchParams.topMargin = gap;
        root.addView(searchBox, searchParams);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(0, dp(4), 0, 0);

        favoritesOnlyCheckBox = new CheckBox(this);
        favoritesOnlyCheckBox.setText("仅显示收藏");
        favoritesOnlyCheckBox.setTextSize(14);
        favoritesOnlyCheckBox.setTextColor(0xFF344541);
        favoritesOnlyCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> showServices(allServices));
        options.addView(favoritesOnlyCheckBox, new LinearLayout.LayoutParams(-1, -2));

        autoGrantCheckBox = new CheckBox(this);
        autoGrantCheckBox.setText("启动时自动启用收藏服务");
        autoGrantCheckBox.setTextSize(14);
        autoGrantCheckBox.setTextColor(0xFF344541);
        autoGrantCheckBox.setChecked(preferences.getBoolean(PREF_AUTO_GRANT, false));
        autoGrantCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preferences.edit().putBoolean(PREF_AUTO_GRANT, isChecked).apply();
            if (isChecked) {
                autoGrantAttempted = false;
                refreshState();
            }
        });
        options.addView(autoGrantCheckBox, new LinearLayout.LayoutParams(-1, -2));
        root.addView(options, new LinearLayout.LayoutParams(-1, -2));

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = gap;
        root.addView(progressBar, progressParams);

        ScrollView scrollView = new ScrollView(this);
        serviceList = new LinearLayout(this);
        serviceList.setOrientation(LinearLayout.VERTICAL);
        serviceList.setPadding(0, gap, 0, 0);
        scrollView.addView(serviceList, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollParams.topMargin = dp(4);
        root.addView(scrollView, scrollParams);

        return root;
    }

    private void refreshState() {
        boolean binderAlive = isShizukuReady();
        boolean granted = binderAlive && hasShizukuPermission();

        permissionButton.setEnabled(binderAlive && !granted);
        refreshButton.setEnabled(granted);

        if (!binderAlive) {
            statusView.setText("Shizuku 未连接。请先安装并启动 Shizuku。");
            serviceList.removeAllViews();
            return;
        }

        if (!granted) {
            statusView.setText("Shizuku 已连接，尚未授权本 App。");
            serviceList.removeAllViews();
            return;
        }

        if (shellService == null) {
            statusView.setText(String.format(Locale.US, "Shizuku 已授权，正在连接 UserService，当前身份 UID: %d", Shizuku.getUid()));
            serviceList.removeAllViews();
            bindShellService();
            return;
        }

        statusView.setText(String.format(Locale.US, "Shizuku 已授权，UserService 已连接，当前身份 UID: %d", Shizuku.getUid()));
        loadAccessibilityServices();
    }

    private boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasShizukuPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void requestShizukuPermission() {
        if (!isShizukuReady()) {
            toast("Shizuku 未连接");
            return;
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            toast("你之前拒绝了 Shizuku 授权，请在 Shizuku 应用里手动允许");
            return;
        }
        Shizuku.requestPermission(REQUEST_SHIZUKU);
    }

    private void bindShellService() {
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
            toast("连接 UserService 失败：" + e.getMessage());
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

    private void loadAccessibilityServices() {
        setLoading(true);
        Set<String> favoriteSnapshot = getFavoriteComponentsSnapshot();
        boolean shouldAutoGrant = preferences.getBoolean(PREF_AUTO_GRANT, false) && !autoGrantAttempted;
        if (shouldAutoGrant) {
            autoGrantAttempted = true;
        }
        executor.execute(() -> {
            try {
                Set<String> enabled = readEnabledServices();
                List<AccessibilityServiceEntry> services = queryAccessibilityServices(enabled, favoriteSnapshot);
                int autoGranted = 0;
                if (shouldAutoGrant) {
                    autoGranted = autoGrantFavorites(enabled, services, favoriteSnapshot);
                    if (autoGranted > 0) {
                        enabled = readEnabledServices();
                        services = queryAccessibilityServices(enabled, favoriteSnapshot);
                    }
                }
                int finalAutoGranted = autoGranted;
                List<AccessibilityServiceEntry> finalServices = services;
                runOnUiThread(() -> {
                    allServices = finalServices;
                    showServices(allServices);
                    if (finalAutoGranted > 0) {
                        toast("已自动启用 " + finalAutoGranted + " 个收藏服务");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    serviceList.removeAllViews();
                    addMessage("读取无障碍服务失败：" + e.getMessage());
                });
            } finally {
                runOnUiThread(() -> setLoading(false));
            }
        });
    }

    private List<AccessibilityServiceEntry> queryAccessibilityServices(Set<String> enabled, Set<String> favorites) {
        PackageManager pm = getPackageManager();
        Map<String, AccessibilityServiceEntry> entries = new LinkedHashMap<>();

        android.view.accessibility.AccessibilityManager accessibilityManager =
                (android.view.accessibility.AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (accessibilityManager != null) {
            List<AccessibilityServiceInfo> installedServices = accessibilityManager.getInstalledAccessibilityServiceList();
            for (AccessibilityServiceInfo info : installedServices) {
                ResolveInfo resolveInfo = info.getResolveInfo();
                if (resolveInfo != null && resolveInfo.serviceInfo != null) {
                    addServiceEntry(pm, entries, resolveInfo, enabled, favorites);
                }
            }
        }

        Intent intent = new Intent(AccessibilityService.SERVICE_INTERFACE);
        List<ResolveInfo> resolveInfos;
        int flags = PackageManager.GET_META_DATA
                | PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolveInfos = pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(flags));
        } else {
            resolveInfos = pm.queryIntentServices(intent, flags);
        }

        for (ResolveInfo resolveInfo : resolveInfos) {
            addServiceEntry(pm, entries, resolveInfo, enabled, favorites);
        }

        List<AccessibilityServiceEntry> sorted = new ArrayList<>(entries.values());
        Collections.sort(sorted, (a, b) -> {
            int app = a.appLabel.compareToIgnoreCase(b.appLabel);
            return app != 0 ? app : a.serviceLabel.compareToIgnoreCase(b.serviceLabel);
        });
        return sorted;
    }

    private void addServiceEntry(
            PackageManager pm,
            Map<String, AccessibilityServiceEntry> entries,
            ResolveInfo resolveInfo,
            Set<String> enabled,
            Set<String> favorites
    ) {
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        if (serviceInfo == null || !Manifest.permission.BIND_ACCESSIBILITY_SERVICE.equals(serviceInfo.permission)) {
            return;
        }

        ComponentName componentName = new ComponentName(serviceInfo.packageName, serviceInfo.name);
        String component = componentName.flattenToString();
        CharSequence serviceLabel = resolveInfo.loadLabel(pm);
        CharSequence appLabel = serviceInfo.applicationInfo.loadLabel(pm);
        entries.put(component, new AccessibilityServiceEntry(
                appLabel == null ? serviceInfo.packageName : appLabel.toString(),
                serviceLabel == null ? serviceInfo.name : serviceLabel.toString(),
                component,
                enabled.contains(component),
                favorites.contains(component)
        ));
    }

    private void showServices(List<AccessibilityServiceEntry> services) {
        serviceList.removeAllViews();
        List<AccessibilityServiceEntry> visibleServices = filterServices(services);
        if (visibleServices.isEmpty()) {
            addMessage(services.isEmpty() ? "没有找到已安装的无障碍服务。" : "没有匹配的服务。");
            return;
        }

        for (AccessibilityServiceEntry entry : visibleServices) {
            serviceList.addView(createServiceRow(entry), new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private List<AccessibilityServiceEntry> filterServices(List<AccessibilityServiceEntry> services) {
        String query = searchBox == null ? "" : searchBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean favoritesOnly = favoritesOnlyCheckBox != null && favoritesOnlyCheckBox.isChecked();
        List<AccessibilityServiceEntry> filtered = new ArrayList<>();
        for (AccessibilityServiceEntry entry : services) {
            if (favoritesOnly && !entry.favorite) {
                continue;
            }
            if (!query.isEmpty() && !entry.matches(query)) {
                continue;
            }
            filtered.add(entry);
        }
        return filtered;
    }

    private View createServiceRow(AccessibilityServiceEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundColor(0xFFFFFFFF);

        TextView appName = new TextView(this);
        appName.setText(entry.appLabel);
        appName.setTextColor(0xFF10201D);
        appName.setTextSize(16);
        appName.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(appName, new LinearLayout.LayoutParams(-1, -2));

        TextView serviceName = new TextView(this);
        serviceName.setText(entry.serviceLabel);
        serviceName.setTextColor(0xFF344541);
        serviceName.setTextSize(14);
        row.addView(serviceName, new LinearLayout.LayoutParams(-1, -2));

        TextView component = new TextView(this);
        component.setText(entry.component);
        component.setTextColor(0xFF687572);
        component.setTextSize(12);
        component.setSingleLine(false);
        row.addView(component, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(-1, -2);
        bottomParams.topMargin = dp(10);

        TextView state = new TextView(this);
        state.setText(entry.enabled ? "已启用" : "未启用");
        state.setTextColor(entry.enabled ? 0xFF047857 : 0xFF7C2D12);
        state.setTextSize(14);
        bottom.addView(state, new LinearLayout.LayoutParams(0, -2, 1));

        Button favorite = new Button(this);
        favorite.setText(entry.favorite ? "取消收藏" : "收藏");
        favorite.setOnClickListener(v -> toggleFavorite(entry.component));
        bottom.addView(favorite, new LinearLayout.LayoutParams(dp(120), dp(44)));

        Button action = new Button(this);
        action.setText(entry.enabled ? "停用" : "启用");
        action.setOnClickListener(v -> setServiceEnabled(entry.component, !entry.enabled));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(dp(96), dp(44));
        actionParams.leftMargin = dp(8);
        bottom.addView(action, actionParams);
        row.addView(bottom, bottomParams);

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(0, 0, 0, dp(10));
        wrapper.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return wrapper;
    }

    private void toggleFavorite(String component) {
        if (favoriteComponents.contains(component)) {
            favoriteComponents.remove(component);
            toast("已取消收藏");
        } else {
            favoriteComponents.add(component);
            toast("已收藏");
        }
        preferences.edit().putStringSet(PREF_FAVORITES, new LinkedHashSet<>(favoriteComponents)).apply();
        refreshFavoriteState();
        showServices(allServices);
    }

    private void refreshFavoriteState() {
        List<AccessibilityServiceEntry> updated = new ArrayList<>();
        for (AccessibilityServiceEntry entry : allServices) {
            updated.add(entry.withFavorite(favoriteComponents.contains(entry.component)));
        }
        allServices = updated;
    }

    private Set<String> getFavoriteComponentsSnapshot() {
        return new LinkedHashSet<>(favoriteComponents);
    }

    private void setServiceEnabled(String component, boolean enabled) {
        setLoading(true);
        executor.execute(() -> {
            try {
                Set<String> services = readEnabledServices();
                if (enabled) {
                    services.add(component);
                } else {
                    services.remove(component);
                }
                writeEnabledServices(services);
                runOnUiThread(() -> {
                    toast(enabled ? "已启用" : "已停用");
                    loadAccessibilityServices();
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("操作失败：" + e.getMessage()));
            } finally {
                runOnUiThread(() -> setLoading(false));
            }
        });
    }

    private int autoGrantFavorites(
            Set<String> enabled,
            List<AccessibilityServiceEntry> services,
            Set<String> favorites
    ) throws IOException, InterruptedException {
        if (favorites.isEmpty()) {
            return 0;
        }

        Set<String> availableFavorites = new LinkedHashSet<>();
        for (AccessibilityServiceEntry entry : services) {
            if (favorites.contains(entry.component)) {
                availableFavorites.add(entry.component);
            }
        }

        int changed = 0;
        for (String component : availableFavorites) {
            if (enabled.add(component)) {
                changed++;
            }
        }
        if (changed > 0) {
            writeEnabledServices(enabled);
        }
        return changed;
    }

    private Set<String> readEnabledServices() throws IOException, InterruptedException {
        String output = runCommand(SETTINGS_COMMAND, "get", "secure", ENABLED_ACCESSIBILITY_SERVICES).trim();
        Set<String> result = new LinkedHashSet<>();
        if (output.isEmpty() || "null".equalsIgnoreCase(output)) {
            return result;
        }
        for (String item : output.split(":")) {
            if (!TextUtils.isEmpty(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private void writeEnabledServices(Set<String> services) throws IOException, InterruptedException {
        String joined = TextUtils.join(":", services);
        runCommand(SETTINGS_COMMAND, "put", "secure", ENABLED_ACCESSIBILITY_SERVICES, joined);
        runCommand(SETTINGS_COMMAND, "put", "secure", ACCESSIBILITY_ENABLED, services.isEmpty() ? "0" : "1");
    }

    private String runCommand(String... command) throws IOException, InterruptedException {
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

    private void addMessage(String message) {
        TextView textView = new TextView(this);
        textView.setText(message);
        textView.setTextColor(0xFF5B6663);
        textView.setTextSize(14);
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(0, dp(32), 0, 0);
        serviceList.addView(textView, new LinearLayout.LayoutParams(-1, -2));
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        refreshButton.setEnabled(!loading && hasShizukuPermission());
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class AccessibilityServiceEntry {
        final String appLabel;
        final String serviceLabel;
        final String component;
        final boolean enabled;
        final boolean favorite;

        AccessibilityServiceEntry(String appLabel, String serviceLabel, String component, boolean enabled, boolean favorite) {
            this.appLabel = appLabel;
            this.serviceLabel = serviceLabel;
            this.component = component;
            this.enabled = enabled;
            this.favorite = favorite;
        }

        AccessibilityServiceEntry withFavorite(boolean favorite) {
            return new AccessibilityServiceEntry(appLabel, serviceLabel, component, enabled, favorite);
        }

        boolean matches(String query) {
            return appLabel.toLowerCase(Locale.ROOT).contains(query)
                    || serviceLabel.toLowerCase(Locale.ROOT).contains(query)
                    || component.toLowerCase(Locale.ROOT).contains(query);
        }
    }
}
