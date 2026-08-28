package com.androidtoolsuite.app.plugin.v2;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.androidtoolsuite.app.BuildConfig;
import com.androidtoolsuite.app.IShellService;
import com.androidtoolsuite.app.host.ShellUserService;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;

/** Process-lifetime Shizuku transport used by both compatibility APIs and Runtime v2 Providers. */
public final class V2ShizukuService {
    private static final String TAG = "AtsV2Shizuku";
    private static final int REQUEST_PERMISSION = 6104;

    private final Context context;
    private volatile IShellService shellService;
    private volatile boolean binding;
    private Shizuku.UserServiceArgs serviceArgs;
    private final List<Runnable> stateListeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable reconnectUserService = this::reconnectUserService;

    private void reconnectUserService() {
        synchronized (V2ShizukuService.this) {
            if (shellService != null || !isReady() || !hasPermission()) return;
            if (serviceArgs != null) {
                try {
                    // Detach the stale connection record without removing the newly restarted
                    // process, then bind the same verified service again.
                    Shizuku.unbindUserService(serviceArgs, connection, false);
                } catch (Throwable error) {
                    Log.w(TAG, "Cannot detach stale Shizuku UserService binding", error);
                }
            }
            binding = false;
        }
        ensure();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mainHandler.removeCallbacks(reconnectUserService);
            shellService = IShellService.Stub.asInterface(service);
            binding = false;
            notifyStateChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            shellService = null;
            binding = false;
            notifyStateChanged();
            // A killed UserService should recover while the user remains on the dashboard. Notify
            // the transient state first, then request a fresh binding; onServiceConnected emits the
            // final ready state and refreshes widgets again.
            mainHandler.removeCallbacks(reconnectUserService);
            mainHandler.postDelayed(reconnectUserService, 250L);
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> {
        ensureIfAuthorized();
        notifyStateChanged();
    };
    private final Shizuku.OnBinderDeadListener binderDead = () -> {
        mainHandler.removeCallbacks(reconnectUserService);
        shellService = null;
        binding = false;
        notifyStateChanged();
    };
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (requestCode, grantResult) -> {
        if (requestCode == REQUEST_PERMISSION) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) ensure();
            notifyStateChanged();
        }
    };

    public V2ShizukuService(Context context) {
        this.context = context.getApplicationContext();
        Shizuku.addBinderReceivedListener(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        ensureIfAuthorized();
    }

    public String state() {
        if (!isReady()) return "disconnected";
        if (!hasPermission()) return "unauthorized";
        if (!isConnected()) return "connecting";
        return "ready";
    }

    public boolean isReady() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isConnected() {
        return shellService != null;
    }

    public int uid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public void requestPermission() {
        if (!isReady() || hasPermission()) return;
        Shizuku.requestPermission(REQUEST_PERMISSION);
    }

    public synchronized void ensure() {
        if (binding || shellService != null || !hasPermission()) return;
        binding = true;
        ComponentName component = new ComponentName(context.getPackageName(), ShellUserService.class.getName());
        serviceArgs = new Shizuku.UserServiceArgs(component)
                .daemon(false)
                .debuggable(BuildConfig.DEBUG)
                .processNameSuffix("shell")
                .tag("shell")
                .version(1);
        try {
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Throwable error) {
            binding = false;
            Log.e(TAG, "Cannot bind Shizuku UserService", error);
            notifyStateChanged();
        }
    }

    public void ensureIfAuthorized() {
        if (isReady() && hasPermission()) ensure();
    }

    public String run(String... command) throws IOException {
        IShellService service = shellService;
        if (service == null) throw new IOException("Shizuku 系统服务未连接");
        try {
            return service.run(command);
        } catch (RemoteException error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    public String readSecureSetting(String name) throws IOException {
        validateSetting(name);
        return run("/system/bin/settings", "get", "secure", name).trim();
    }

    public void writeSecureSetting(String name, String value) throws IOException {
        validateSetting(name);
        run("/system/bin/settings", "put", "secure", name, value == null ? "" : value);
    }

    /** Observes the complete transport state, including the later UserService connection callback. */
    public AutoCloseable addStateListener(Runnable listener) {
        stateListeners.add(listener);
        return () -> stateListeners.remove(listener);
    }

    private void notifyStateChanged() {
        for (Runnable listener : stateListeners) listener.run();
    }

    private static void validateSetting(String name) throws IOException {
        if (name == null || !name.matches("[a-z0-9_]{1,80}")) throw new IOException("设置项名称无效");
    }
}
