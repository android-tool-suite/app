package com.androidtoolsuite.app.plugin.v2;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.androidtoolsuite.app.R;
import com.androidtoolsuite.app.plugin.v2.CapabilityCall;
import com.androidtoolsuite.app.plugin.v2.CapabilityFailure;
import com.androidtoolsuite.app.plugin.v2.CapabilityProvider;
import com.androidtoolsuite.runtime.contract.GeneratedContract;
import com.androidtoolsuite.runtime.contract.ProtocolVersion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Registers host-owned capability providers through the same reversible registry as native ones. */
public final class V2HostCapabilityProviders {
    private V2HostCapabilityProviders() {
    }

    public static List<AutoCloseable> registerProcessCapabilities(
            Context context,
            V2CapabilityRouter router,
            V2StorageService storage,
            V2DatasetService datasets,
            V2SchedulerService scheduler
    ) throws CapabilityFailure {
        List<AutoCloseable> registrations = new ArrayList<>();
        registrations.add(router.register(new StorageProvider(storage, datasets), "host-bundled:storage", 100));
        registrations.add(router.register(new NetworkProvider(storage), "host-bundled:network", 100));
        registrations.add(router.register(scheduler, "host-bundled:scheduler", 100));
        return Collections.unmodifiableList(registrations);
    }

    public static List<AutoCloseable> registerActivityCapabilities(
            Context context,
            V2HostActions actions,
            V2CapabilityRouter router
    ) throws CapabilityFailure {
        List<AutoCloseable> registrations = new ArrayList<>();
        registrations.add(router.register(new AppProvider(actions), "host-activity:app", 100));
        registrations.add(router.register(new ClipboardProvider(context), "host-bundled:clipboard", 100));
        registrations.add(router.register(new FileImportProvider(actions), "host-bundled:file-import", 100));
        registrations.add(router.register(new NotificationProvider(context, actions), "host-bundled:notification", 100));
        return Collections.unmodifiableList(registrations);
    }

    private abstract static class Provider implements CapabilityProvider {
        private final String id;
        private final Set<String> methods;

        Provider(String id, String... methods) {
            this.id = id;
            this.methods = Set.of(methods);
        }

        @Override public final String capabilityId() { return id; }
        @Override public final String version() { return "1.0.0"; }
        @Override public final Set<String> methods() { return methods; }
    }

    private static final class AppProvider extends Provider {
        private final V2HostActions actions;

        AppProvider(V2HostActions actions) {
            super(GeneratedContract.Capabilities.APP,
                    GeneratedContract.Methods.APP_GETSESSION,
                    GeneratedContract.Methods.APP_OPENEXTERNAL,
                    GeneratedContract.Methods.APP_CLOSE);
            this.actions = actions;
        }

        @Override
        public JSONObject call(CapabilityCall call) throws CapabilityFailure {
            try {
                switch (call.method) {
                    case GeneratedContract.Methods.APP_GETSESSION:
                        return new JSONObject()
                                .put("pluginId", call.pluginId)
                                .put("sessionId", call.sessionId)
                                .put("protocol", ProtocolVersion.CURRENT.toString())
                                .put("platform", new JSONObject()
                                        .put("id", "android")
                                        .put("api", Build.VERSION.SDK_INT));
                    case GeneratedContract.Methods.APP_CLOSE:
                        actions.closeTool();
                        return new JSONObject().put("closed", true);
                    case GeneratedContract.Methods.APP_OPENEXTERNAL:
                        requireGesture(call);
                        String raw = requiredString(call.payload, "url", 2_048);
                        Uri uri = Uri.parse(raw);
                        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                                || uri.getHost() == null) {
                            throw CapabilityFailure.invalid("Only http/https URLs can be opened externally");
                        }
                        actions.activity().runOnUiThread(() -> {
                            try {
                                actions.activity().startActivity(new Intent(Intent.ACTION_VIEW, uri));
                            } catch (RuntimeException error) {
                                actions.showMessage("没有可打开此链接的应用");
                            }
                        });
                        return new JSONObject().put("opened", true);
                    default:
                        throw CapabilityFailure.invalid("Unsupported app method");
                }
            } catch (JSONException error) {
                throw internal(error);
            }
        }
    }

    private static final class StorageProvider extends Provider {
        private final V2StorageService storage;
        private final V2DatasetService datasets;

        StorageProvider(V2StorageService storage, V2DatasetService datasets) {
            super(GeneratedContract.Capabilities.STORAGE,
                    GeneratedContract.Methods.STORAGE_KV_GET,
                    GeneratedContract.Methods.STORAGE_KV_SET,
                    GeneratedContract.Methods.STORAGE_KV_DELETE,
                    GeneratedContract.Methods.STORAGE_BLOB_OPENREAD,
                    GeneratedContract.Methods.STORAGE_BLOB_OPENWRITE,
                    GeneratedContract.Methods.STORAGE_BLOB_READ,
                    GeneratedContract.Methods.STORAGE_BLOB_WRITE,
                    GeneratedContract.Methods.STORAGE_BLOB_CLOSE,
                    GeneratedContract.Methods.STORAGE_BLOB_DELETE,
                    GeneratedContract.Methods.STORAGE_SECRET_GET,
                    GeneratedContract.Methods.STORAGE_SECRET_SET,
                    GeneratedContract.Methods.STORAGE_SECRET_DELETE,
                    GeneratedContract.Methods.STORAGE_DATASET_OPENREAD,
                    GeneratedContract.Methods.STORAGE_DATASET_OPENWRITE,
                    GeneratedContract.Methods.STORAGE_DATASET_READ,
                    GeneratedContract.Methods.STORAGE_DATASET_WRITE,
                    GeneratedContract.Methods.STORAGE_DATASET_COMMIT,
                    GeneratedContract.Methods.STORAGE_DATASET_ABORT,
                    GeneratedContract.Methods.STORAGE_DATASET_DELETE);
            this.storage = storage;
            this.datasets = datasets;
        }

        @Override
        public JSONObject call(CapabilityCall call) throws CapabilityFailure {
            switch (call.method) {
                case GeneratedContract.Methods.STORAGE_KV_GET:
                    return storage.kvGet(call.pluginId, requiredString(call.payload, "key", 128));
                case GeneratedContract.Methods.STORAGE_KV_SET:
                    if (!call.payload.has("value")) throw CapabilityFailure.invalid("Missing value");
                    return storage.kvSet(call.pluginId, requiredString(call.payload, "key", 128), call.payload.opt("value"));
                case GeneratedContract.Methods.STORAGE_KV_DELETE:
                    return storage.kvDelete(call.pluginId, requiredString(call.payload, "key", 128));
                case GeneratedContract.Methods.STORAGE_BLOB_OPENREAD:
                    return storage.blobOpenRead(call.pluginId, call.sessionId, requiredString(call.payload, "id", 128));
                case GeneratedContract.Methods.STORAGE_BLOB_OPENWRITE:
                    return storage.blobOpenWrite(call.pluginId, call.sessionId, requiredString(call.payload, "id", 128));
                case GeneratedContract.Methods.STORAGE_BLOB_READ:
                    return storage.blobRead(call.pluginId, call.sessionId,
                            requiredString(call.payload, "handle", 128),
                            requiredLong(call.payload, "offset", 0, Long.MAX_VALUE),
                            requiredInt(call.payload, "maxBytes", 1, 192 * 1024));
                case GeneratedContract.Methods.STORAGE_BLOB_WRITE:
                    return storage.blobWrite(call.pluginId, call.sessionId,
                            requiredString(call.payload, "handle", 128),
                            requiredString(call.payload, "bytes", 256 * 1024));
                case GeneratedContract.Methods.STORAGE_BLOB_CLOSE:
                    return storage.blobClose(call.pluginId, call.sessionId,
                            requiredString(call.payload, "handle", 128));
                case GeneratedContract.Methods.STORAGE_BLOB_DELETE:
                    return storage.blobDelete(call.pluginId, requiredString(call.payload, "id", 128));
                case GeneratedContract.Methods.STORAGE_SECRET_GET:
                    return datasets.secretGet(
                            call.pluginId,
                            requiredString(call.payload, "datasetId", 64),
                            requiredString(call.payload, "key", 128)
                    );
                case GeneratedContract.Methods.STORAGE_SECRET_SET:
                    if (!call.payload.has("value")) throw CapabilityFailure.invalid("Missing value");
                    return datasets.secretSet(
                            call.pluginId,
                            requiredString(call.payload, "datasetId", 64),
                            requiredString(call.payload, "key", 128),
                            call.payload.opt("value")
                    );
                case GeneratedContract.Methods.STORAGE_SECRET_DELETE:
                    return datasets.secretDelete(
                            call.pluginId,
                            requiredString(call.payload, "datasetId", 64),
                            requiredString(call.payload, "key", 128)
                    );
                case GeneratedContract.Methods.STORAGE_DATASET_OPENREAD:
                    return datasets.openRead(
                            call.pluginId, call.sessionId, requiredString(call.payload, "datasetId", 64)
                    );
                case GeneratedContract.Methods.STORAGE_DATASET_OPENWRITE:
                    return datasets.openWrite(
                            call.pluginId, call.sessionId, requiredString(call.payload, "datasetId", 64)
                    );
                case GeneratedContract.Methods.STORAGE_DATASET_READ:
                    return datasets.read(
                            call.pluginId,
                            call.sessionId,
                            requiredString(call.payload, "handle", 128),
                            requiredLong(call.payload, "offset", 0, Long.MAX_VALUE),
                            requiredInt(call.payload, "maxBytes", 1, 192 * 1024)
                    );
                case GeneratedContract.Methods.STORAGE_DATASET_WRITE:
                    return datasets.write(
                            call.pluginId,
                            call.sessionId,
                            requiredString(call.payload, "handle", 128),
                            requiredString(call.payload, "bytes", 256 * 1024)
                    );
                case GeneratedContract.Methods.STORAGE_DATASET_COMMIT:
                    return datasets.commit(
                            call.pluginId, call.sessionId, requiredString(call.payload, "handle", 128)
                    );
                case GeneratedContract.Methods.STORAGE_DATASET_ABORT:
                    return datasets.abort(
                            call.pluginId, call.sessionId, requiredString(call.payload, "handle", 128)
                    );
                case GeneratedContract.Methods.STORAGE_DATASET_DELETE:
                    return datasets.delete(
                            call.pluginId, requiredString(call.payload, "datasetId", 64)
                    );
                default:
                    throw CapabilityFailure.invalid("Unsupported storage method");
            }
        }
    }

    private static final class ClipboardProvider extends Provider {
        private final Context context;
        private final ClipboardManager clipboard;

        ClipboardProvider(Context context) {
            super(GeneratedContract.Capabilities.CLIPBOARD,
                    GeneratedContract.Methods.CLIPBOARD_READ,
                    GeneratedContract.Methods.CLIPBOARD_WRITE);
            this.context = context.getApplicationContext();
            clipboard = (ClipboardManager) this.context.getSystemService(Context.CLIPBOARD_SERVICE);
        }

        @Override
        public JSONObject call(CapabilityCall call) throws CapabilityFailure {
            requireGesture(call);
            try {
                if (GeneratedContract.Methods.CLIPBOARD_READ.equals(call.method)) {
                    ClipData data = clipboard.getPrimaryClip();
                    CharSequence text = data == null || data.getItemCount() == 0
                            ? "" : data.getItemAt(0).coerceToText(context);
                    return new JSONObject().put("text", text == null ? "" : text.toString());
                }
                String text = requiredString(call.payload, "text", 256 * 1024);
                clipboard.setPrimaryClip(ClipData.newPlainText("ATS tool", text));
                return new JSONObject().put("written", true);
            } catch (JSONException | RuntimeException error) {
                throw internal(error);
            }
        }
    }

    private static final class FileImportProvider extends Provider {
        private final V2HostActions actions;

        FileImportProvider(V2HostActions actions) {
            super(GeneratedContract.Capabilities.FILE_IMPORT, GeneratedContract.Methods.FILE_IMPORT_PICK);
            this.actions = actions;
        }

        @Override
        public JSONObject call(CapabilityCall call) throws CapabilityFailure {
            requireGesture(call);
            JSONArray mimeTypes = call.payload.optJSONArray("mimeTypes");
            if (mimeTypes == null || mimeTypes.length() < 1 || mimeTypes.length() > 16) {
                throw CapabilityFailure.invalid("mimeTypes must contain 1-16 entries");
            }
            JSONArray allowedMimeTypes = call.scopes.optJSONArray("mimeTypes");
            for (int index = 0; index < mimeTypes.length(); index++) {
                if (!allowedMimeType(allowedMimeTypes, mimeTypes.optString(index, ""))) {
                    throw new CapabilityFailure("CAPABILITY_UNDECLARED", "MIME type is outside declared scope", false);
                }
            }
            int maxBytes = Math.min(
                    call.scopes.optInt("maxBytes", (int) V2StorageService.MAX_BLOB_BYTES),
                    (int) V2StorageService.MAX_BLOB_BYTES
            );
            long remaining = Math.max(1L, call.deadlineEpochMillis - System.currentTimeMillis());
            try {
                return actions.pickFile(call.pluginId, call.sessionId, mimeTypes, maxBytes)
                        .get(remaining, TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                throw new CapabilityFailure("TIMEOUT", "File picker timed out", true);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CapabilityFailure("CANCELLED", "File picker interrupted", true);
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof CapabilityFailure) throw (CapabilityFailure) cause;
                throw internal(cause == null ? error : cause);
            }
        }
    }

    private static final class NotificationProvider extends Provider {
        private final Context context;
        private final V2HostActions actions;
        private final NotificationManager manager;

        NotificationProvider(Context context, V2HostActions actions) {
            super(GeneratedContract.Capabilities.NOTIFICATION,
                    GeneratedContract.Methods.NOTIFICATION_POST,
                    GeneratedContract.Methods.NOTIFICATION_CANCEL);
            this.context = context.getApplicationContext();
            this.actions = actions;
            manager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        }

        @Override
        public JSONObject call(CapabilityCall call) throws CapabilityFailure {
            if (GeneratedContract.Methods.NOTIFICATION_CANCEL.equals(call.method)) {
                manager.cancel(call.pluginId, requiredInt(call.payload, "id", Integer.MIN_VALUE, Integer.MAX_VALUE));
                return object("cancelled", true);
            }
            if (Build.VERSION.SDK_INT >= 33
                    && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                if (call.userGesture) actions.requestNotificationPermission();
                throw new CapabilityFailure("CONSENT_REQUIRED", "Notification permission is required", true);
            }
            String logicalChannel = requiredString(call.payload, "channel", 64);
            if (!allowedString(call.scopes.optJSONArray("channels"), logicalChannel)) {
                throw new CapabilityFailure("CAPABILITY_UNDECLARED", "Notification channel is outside declared scope", false);
            }
            String channelId = "ats-v2-" + Integer.toHexString((call.pluginId + ":" + logicalChannel).hashCode());
            String title = requiredString(call.payload, "title", 120);
            String body = optionalString(call.payload, "body", 512);
            int id = call.payload.optInt("id", (call.pluginId + ":" + logicalChannel).hashCode());
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(new NotificationChannel(
                        channelId,
                        "ATS · " + logicalChannel,
                        NotificationManager.IMPORTANCE_DEFAULT
                ));
            }
            manager.notify(call.pluginId, id, new NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setAutoCancel(true)
                    .build());
            try {
                return object("posted", true).put("id", id);
            } catch (JSONException error) {
                throw internal(error);
            }
        }
    }

    private static final class NetworkProvider extends Provider {
        private final V2StorageService storage;

        NetworkProvider(V2StorageService storage) {
            super(GeneratedContract.Capabilities.NETWORK_REQUEST, GeneratedContract.Methods.NETWORK_REQUEST);
            this.storage = storage;
        }

        @Override
        public JSONObject call(CapabilityCall call) throws CapabilityFailure {
            String rawUrl = requiredString(call.payload, "url", 4_096);
            String method = requiredString(call.payload, "method", 16).toUpperCase(Locale.ROOT);
            URI uri;
            try {
                uri = new URI(rawUrl);
            } catch (URISyntaxException error) {
                throw CapabilityFailure.invalid("Invalid request URL");
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw CapabilityFailure.invalid("network.request only supports HTTPS URLs without user info");
            }
            if (!allowedHost(call.scopes.optJSONArray("hosts"), uri.getHost())) {
                throw new CapabilityFailure("CAPABILITY_UNDECLARED", "Network host is outside declared scope", false);
            }
            if (!allowedString(call.scopes.optJSONArray("methods"), method)) {
                throw new CapabilityFailure("CAPABILITY_UNDECLARED", "HTTP method is outside declared scope", false);
            }
            int maxBytes = Math.min(call.scopes.optInt("maxResponseBytes", 2 * 1024 * 1024), 8 * 1024 * 1024);
            if (maxBytes < 1) throw CapabilityFailure.invalid("maxResponseBytes must be positive");
            int remaining = (int) Math.max(1L, Math.min(60_000L,
                    call.deadlineEpochMillis - System.currentTimeMillis()));
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(rawUrl).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(remaining);
                connection.setReadTimeout(remaining);
                connection.setRequestProperty("Accept-Encoding", "identity");
                JSONObject headers = call.payload.optJSONObject("headers");
                if (headers != null) {
                    java.util.Iterator<String> names = headers.keys();
                    while (names.hasNext()) {
                        String name = names.next();
                        if (!name.matches("[A-Za-z0-9-]{1,64}") || blockedHeader(name)) {
                            throw CapabilityFailure.invalid("Unsafe request header: " + name);
                        }
                        connection.setRequestProperty(name, requiredString(headers, name, 4_096));
                    }
                }
                String body = call.payload.optString("bodyBase64", "");
                if (!body.isEmpty()) {
                    byte[] bytes = android.util.Base64.decode(body, android.util.Base64.NO_WRAP);
                    if (bytes.length > 2 * 1024 * 1024) throw resource("Request body exceeds 2 MiB");
                    connection.setDoOutput(true);
                    try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    throw new CapabilityFailure("NOT_SUPPORTED", "Redirect blocked: " + (location == null ? "" : location), false);
                }
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                byte[] response = stream == null ? new byte[0] : readBounded(stream, maxBytes);
                JSONObject responseHeaders = new JSONObject();
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                    if (header.getKey() != null && !blockedResponseHeader(header.getKey())) {
                        responseHeaders.put(header.getKey().toLowerCase(Locale.ROOT), new JSONArray(header.getValue()));
                    }
                }
                JSONObject result = new JSONObject()
                        .put("status", status)
                        .put("headers", responseHeaders);
                if (response.length <= 128 * 1024) {
                    result.put("body", android.util.Base64.encodeToString(response, android.util.Base64.NO_WRAP));
                } else {
                    String blobId = "network." + java.util.UUID.randomUUID().toString().replace("-", "");
                    JSONObject blob = storage.importBlob(call.pluginId, call.sessionId, blobId, response);
                    result.put("body", JSONObject.NULL).put("bodyBlob", blob);
                }
                return result;
            } catch (CapabilityFailure failure) {
                throw failure;
            } catch (IOException | JSONException | IllegalArgumentException error) {
                throw new CapabilityFailure("PROVIDER_OFFLINE", "Network request failed: " + safeMessage(error), true);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException, CapabilityFailure {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (output.size() + read > limit) throw resource("Network response exceeds declared limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void requireGesture(CapabilityCall call) throws CapabilityFailure {
        if (!call.userGesture) throw new CapabilityFailure("CONSENT_REQUIRED", "A recent user gesture is required", true);
    }

    private static String requiredString(JSONObject value, String name, int maxLength) throws CapabilityFailure {
        Object raw = value.opt(name);
        if (!(raw instanceof String)) throw CapabilityFailure.invalid("Missing string field " + name);
        String text = ((String) raw).trim();
        if (text.isEmpty() || text.length() > maxLength) throw CapabilityFailure.invalid("Invalid field " + name);
        return text;
    }

    private static String optionalString(JSONObject value, String name, int maxLength) throws CapabilityFailure {
        if (!value.has(name)) return "";
        Object raw = value.opt(name);
        if (!(raw instanceof String) || ((String) raw).length() > maxLength) {
            throw CapabilityFailure.invalid("Invalid field " + name);
        }
        return (String) raw;
    }

    private static int requiredInt(JSONObject value, String name, int min, int max) throws CapabilityFailure {
        Object raw = value.opt(name);
        if (!(raw instanceof Number)) throw CapabilityFailure.invalid("Missing integer field " + name);
        long number = ((Number) raw).longValue();
        if (((Number) raw).doubleValue() != (double) number || number < min || number > max) {
            throw CapabilityFailure.invalid("Invalid integer field " + name);
        }
        return (int) number;
    }

    private static long requiredLong(JSONObject value, String name, long min, long max) throws CapabilityFailure {
        Object raw = value.opt(name);
        if (!(raw instanceof Number)) throw CapabilityFailure.invalid("Missing integer field " + name);
        long number = ((Number) raw).longValue();
        if (((Number) raw).doubleValue() != (double) number || number < min || number > max) {
            throw CapabilityFailure.invalid("Invalid integer field " + name);
        }
        return number;
    }

    private static boolean allowedString(JSONArray values, String target) {
        if (values == null) return false;
        for (int index = 0; index < values.length(); index++) {
            if (target.equalsIgnoreCase(values.optString(index, ""))) return true;
        }
        return false;
    }

    private static boolean allowedMimeType(JSONArray values, String target) {
        if (values == null || target == null || !target.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+*\\-]+")) {
            return false;
        }
        String candidate = target.toLowerCase(Locale.ROOT);
        int slash = candidate.indexOf('/');
        String major = slash < 0 ? "" : candidate.substring(0, slash);
        for (int index = 0; index < values.length(); index++) {
            String allowed = values.optString(index, "").toLowerCase(Locale.ROOT);
            if (candidate.equals(allowed) || "*/*".equals(allowed) || (major + "/*").equals(allowed)) return true;
        }
        return false;
    }

    private static boolean allowedHost(JSONArray values, String host) {
        if (values == null) return false;
        String candidate = host.toLowerCase(Locale.ROOT);
        for (int index = 0; index < values.length(); index++) {
            String allowed = values.optString(index, "").toLowerCase(Locale.ROOT);
            if (candidate.equals(allowed)) return true;
            if (allowed.startsWith("*.") && candidate.endsWith(allowed.substring(1))
                    && candidate.length() > allowed.length() - 1) return true;
        }
        return false;
    }

    private static boolean blockedHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return Set.of("host", "connection", "content-length", "cookie", "authorization", "proxy-authorization")
                .contains(lower);
    }

    private static boolean blockedResponseHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return "set-cookie".equals(lower) || "set-cookie2".equals(lower);
    }

    private static JSONObject object(String key, Object value) throws CapabilityFailure {
        try {
            return new JSONObject().put(key, value);
        } catch (JSONException error) {
            throw internal(error);
        }
    }

    private static CapabilityFailure resource(String message) {
        return new CapabilityFailure("RESOURCE_LIMIT", message, false);
    }

    private static CapabilityFailure internal(Throwable error) {
        return new CapabilityFailure("INTERNAL", safeMessage(error), true);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
