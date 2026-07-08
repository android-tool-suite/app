package com.example.shizukuaccessibilitygrant;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.example.shizukuaccessibilitygrant.plugins.BuiltInPluginStateStore;
import com.example.shizukuaccessibilitygrant.plugins.ExternalPluginStore;
import com.example.shizukuaccessibilitygrant.plugins.ExternalToolFactory;
import com.example.shizukuaccessibilitygrant.plugins.ImportedPluginDescriptor;
import com.example.shizukuaccessibilitygrant.plugins.PluginHost;
import com.example.shizukuaccessibilitygrant.plugins.ToolRegistry;
import com.example.shizukuaccessibilitygrant.plugins.ToolPlugin;

import org.json.JSONException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity implements PluginHost {
    private static final int REQUEST_SHIZUKU = 3001;
    private static final int REQUEST_IMPORT_PLUGIN = 4001;

    private final List<ToolPlugin> plugins = new ArrayList<>();
    private final List<Button> pluginButtons = new ArrayList<>();

    private LinearLayout pluginBar;
    private LinearLayout pluginContainer;
    private TextView hostStatusView;
    private TextView pluginCountView;
    private ToolPlugin selectedPlugin;
    private ExternalPluginStore externalPluginStore;
    private BuiltInPluginStateStore builtInPluginStateStore;
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
        seedBundledExternalPlugins();
        builtInPluginStateStore = new BuiltInPluginStateStore(this);
        loadPlugins();
        setContentView(createContentView());

        Shizuku.addBinderReceivedListener(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        selectPlugin(plugins.get(0));
        notifyHostStateChanged();
    }

    @Override
    protected void onDestroy() {
        for (ToolPlugin plugin : plugins) {
            plugin.onDestroy();
        }
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        unbindShellService();
        super.onDestroy();
    }

    private View createContentView() {
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
        root.setBackgroundColor(background);
        root.setPadding(horizontal, topPadding, horizontal, dp(12));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(horizontal, topPadding + insets.getSystemWindowInsetTop(), horizontal, dp(12));
            return insets.consumeSystemWindowInsets();
        });

        TextView title = new TextView(this);
        title.setText("安卓工具合集");
        title.setTextSize(26);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF10201D);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        pluginCountView = new TextView(this);
        pluginCountView.setTextSize(13);
        pluginCountView.setTextColor(0xFF64706D);
        pluginCountView.setPadding(0, dp(4), 0, 0);
        root.addView(pluginCountView, new LinearLayout.LayoutParams(-1, -2));

        hostStatusView = new TextView(this);
        hostStatusView.setTextSize(14);
        hostStatusView.setTextColor(0xFF0F3B35);
        hostStatusView.setPadding(0, dp(8), 0, dp(10));
        root.addView(hostStatusView, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView pluginScroll = new HorizontalScrollView(this);
        pluginScroll.setHorizontalScrollBarEnabled(false);
        pluginBar = new LinearLayout(this);
        pluginBar.setOrientation(LinearLayout.HORIZONTAL);
        pluginScroll.addView(pluginBar, new HorizontalScrollView.LayoutParams(-2, -2));
        root.addView(pluginScroll, new LinearLayout.LayoutParams(-1, dp(54)));

        pluginContainer = new LinearLayout(this);
        pluginContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(-1, 0, 1);
        containerParams.topMargin = dp(10);
        root.addView(pluginContainer, containerParams);

        renderPluginButtons();
        return root;
    }

    private void loadPlugins() {
        plugins.clear();
        plugins.addAll(ToolRegistry.createRequiredBuiltInPlugins());
        for (ToolPlugin plugin : ToolRegistry.createOptionalBuiltInPlugins()) {
            if (builtInPluginStateStore.isEnabled(plugin.id())) {
                plugins.add(plugin);
            } else {
                plugin.onDestroy();
            }
        }
        for (ImportedPluginDescriptor descriptor : externalPluginStore.load()) {
            plugins.add(ExternalToolFactory.create(descriptor));
        }
    }

    private void seedBundledExternalPlugins() {
        try {
            externalPluginStore.seedAccessibilityGrantPluginIfNeeded();
        } catch (JSONException e) {
            Toast.makeText(this, "初始化外部插件失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void reloadPlugins(String preferredPluginId) {
        for (ToolPlugin plugin : plugins) {
            plugin.onDestroy();
        }
        loadPlugins();
        if (pluginBar != null) {
            renderPluginButtons();
        }
        updatePluginCount();

        if (pluginContainer == null || plugins.isEmpty()) {
            return;
        }

        ToolPlugin next = findPlugin(preferredPluginId);
        if (next == null) {
            next = plugins.get(0);
        }
        selectPlugin(next);
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

    private void renderPluginButtons() {
        pluginBar.removeAllViews();
        pluginButtons.clear();
        for (ToolPlugin plugin : plugins) {
            Button button = new Button(this);
            button.setText(plugin.title());
            button.setAllCaps(false);
            button.setOnClickListener(v -> selectPlugin(plugin));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(164), dp(48));
            params.rightMargin = dp(8);
            pluginBar.addView(button, params);
            pluginButtons.add(button);
        }
        updatePluginCount();
    }

    private void selectPlugin(ToolPlugin plugin) {
        selectedPlugin = plugin;
        pluginContainer.removeAllViews();
        pluginContainer.addView(plugin.createView(this, this), new LinearLayout.LayoutParams(-1, -1));
        updatePluginButtons();
        plugin.onSelected();
    }

    private void updatePluginButtons() {
        for (int i = 0; i < plugins.size(); i++) {
            Button button = pluginButtons.get(i);
            boolean selected = plugins.get(i) == selectedPlugin;
            button.setEnabled(!selected);
            button.setGravity(Gravity.CENTER);
        }
    }

    private void notifyHostStateChanged() {
        hostStatusView.setText(buildHostStatus());
        updatePluginCount();
        if (selectedPlugin != null) {
            selectedPlugin.onHostStateChanged();
        }
    }

    private void updatePluginCount() {
        if (pluginCountView == null) {
            return;
        }
        int external = 0;
        int optional = 0;
        for (ToolPlugin plugin : plugins) {
            if (plugin.removable()) {
                external++;
            }
            if (isOptionalBuiltInPluginId(plugin.id())) {
                optional++;
            }
        }
        pluginCountView.setText(String.format(Locale.US, "%d 个工具 · %d 个可选插件 · %d 个外部插件", plugins.size(), optional, external));
    }

    private String buildHostStatus() {
        if (!isShizukuReady()) {
            return "Shizuku 未连接。打开需要 Shizuku 的工具前，请先启动 Shizuku。";
        }
        if (!hasShizukuPermission()) {
            return "Shizuku 已连接，尚未授权本合集。";
        }
        if (!isShellServiceConnected()) {
            return String.format(Locale.US, "Shizuku 已授权，等待 UserService，当前 UID: %d", Shizuku.getUid());
        }
        return String.format(Locale.US, "Shizuku 已授权，UserService 已连接，当前 UID: %d", Shizuku.getUid());
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
    public void deleteImportedPlugin(String pluginId) {
        try {
            externalPluginStore.delete(pluginId);
            showToast("已删除插件");
            String nextId = selectedPlugin == null || selectedPlugin.id().equals(pluginId) ? "plugin_manager" : selectedPlugin.id();
            reloadPlugins(nextId);
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
        if (requestCode != REQUEST_IMPORT_PLUGIN || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        try {
            ImportedPluginDescriptor descriptor = readPluginDescriptor(data.getData());
            if (isBuiltInPluginId(descriptor.id)) {
                showToast("插件 ID 与内置插件冲突");
                return;
            }
            externalPluginStore.save(descriptor);
            showToast("已导入插件：" + descriptor.title);
            reloadPlugins(descriptor.id);
        } catch (IOException | JSONException e) {
            showToast("导入失败：" + e.getMessage());
        }
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
    public boolean isBuiltInPluginEnabled(String pluginId) {
        return builtInPluginStateStore.isEnabled(pluginId);
    }

    @Override
    public void setBuiltInPluginEnabled(String pluginId, boolean enabled) {
        builtInPluginStateStore.setEnabled(pluginId, enabled);
        showToast(enabled ? "已启用插件" : "已停用插件");
        String currentId = selectedPlugin == null ? "plugin_manager" : selectedPlugin.id();
        String nextId = enabled ? pluginId : (currentId.equals(pluginId) ? "plugin_manager" : currentId);
        reloadPlugins(nextId);
    }

    private boolean isBuiltInPluginId(String pluginId) {
        for (ToolPlugin plugin : ToolRegistry.createBuiltInPlugins()) {
            if (plugin.id().equals(pluginId)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOptionalBuiltInPluginId(String pluginId) {
        for (ToolPlugin plugin : ToolRegistry.createOptionalBuiltInPlugins()) {
            if (plugin.id().equals(pluginId)) {
                return true;
            }
        }
        return false;
    }

    private ImportedPluginDescriptor readPluginDescriptor(Uri uri) throws IOException, JSONException {
        byte[] bytes = readBytes(uri);
        String manifestJson;
        if (isZip(bytes)) {
            manifestJson = readManifestFromZip(bytes);
        } else {
            manifestJson = new String(bytes, StandardCharsets.UTF_8);
        }
        return ImportedPluginDescriptor.fromJson(manifestJson);
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

    private String readManifestFromZip(byte[] bytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && "manifest.json".equals(entry.getName())) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                    return new String(output.toByteArray(), StandardCharsets.UTF_8);
                }
                zip.closeEntry();
            }
        }
        throw new IOException("插件包缺少 manifest.json");
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
}
