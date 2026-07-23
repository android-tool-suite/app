package com.androidtoolsuite.app.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.androidtoolsuite.app.BuildConfig;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UpdateClient {
    private static final String PREFS_NAME = "update_client";
    private static final String PREF_LAST_SUCCESS = "last_success";
    private static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_INDEX_BYTES = 2 * 1024 * 1024;
    private static final long MAX_ASSET_BYTES = 150L * 1024L * 1024L;

    private final Context context;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final File cacheDirectory;
    private final PublicKey publicKey;

    public UpdateClient(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        cacheDirectory = new File(this.context.getCacheDir(), "updates");
        publicKey = decodePublicKey(BuildConfig.UPDATE_INDEX_PUBLIC_KEY);
    }

    public void check(boolean force, CatalogCallback callback) {
        executor.execute(() -> {
            try {
                long lastSuccess = preferences.getLong(PREF_LAST_SUCCESS, 0L);
                if (!force && System.currentTimeMillis() - lastSuccess < CHECK_INTERVAL_MS) {
                    UpdateCatalog cached = readCachedCatalog();
                    if (cached != null) {
                        deliverCatalog(callback, cached, true);
                        return;
                    }
                }

                byte[] indexBytes = readUrl(BuildConfig.UPDATE_INDEX_URL, MAX_INDEX_BYTES);
                byte[] signatureBytes = readUrl(BuildConfig.UPDATE_INDEX_URL + ".sig", 64 * 1024);
                verifySignature(indexBytes, signatureBytes, publicKey);
                UpdateCatalog catalog = UpdateCatalog.parse(
                        new String(indexBytes, StandardCharsets.UTF_8)
                );
                ensureCacheDirectory();
                writeAtomically(new File(cacheDirectory, "index-v1.json"), indexBytes);
                writeAtomically(new File(cacheDirectory, "index-v1.json.sig"), signatureBytes);
                preferences.edit()
                        .putLong(PREF_LAST_SUCCESS, System.currentTimeMillis())
                        .apply();
                deliverCatalog(callback, catalog, false);
            } catch (IOException | GeneralSecurityException | JSONException error) {
                UpdateCatalog cached = readCachedCatalog();
                if (cached != null) {
                    deliverCatalog(callback, cached, true);
                } else {
                    deliverError(callback, readableMessage(error));
                }
            }
        });
    }

    public void download(UpdateCatalog.ReleaseAsset release, String fileName, DownloadCallback callback) {
        executor.execute(() -> {
            File pendingFile = null;
            try {
                ensureCacheDirectory();
                File finalFile = new File(cacheDirectory, safeName(fileName));
                pendingFile = new File(cacheDirectory, safeName(fileName) + ".pending");
                if (pendingFile.exists() && !pendingFile.delete()) {
                    throw new IOException("无法清理旧下载临时文件");
                }
                downloadToFile(release.downloadUrl, release.size, pendingFile);
                String actualHash = sha256(pendingFile);
                if (!release.sha256.equalsIgnoreCase(actualHash)) {
                    throw new IOException("下载文件 SHA-256 校验失败");
                }
                if (finalFile.exists() && !finalFile.delete()) {
                    throw new IOException("无法替换旧下载文件");
                }
                if (!pendingFile.renameTo(finalFile)) {
                    throw new IOException("无法完成下载文件替换");
                }
                File delivered = finalFile;
                mainHandler.post(() -> callback.onSuccess(delivered));
            } catch (IOException error) {
                if (pendingFile != null) {
                    pendingFile.delete();
                }
                String message = readableMessage(error);
                mainHandler.post(() -> callback.onError(message));
            }
        });
    }

    public void close() {
        executor.shutdownNow();
    }

    public static void verifySignature(byte[] content, byte[] signatureBytes, PublicKey key)
            throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(content);
        if (!verifier.verify(signatureBytes)) {
            throw new GeneralSecurityException("更新索引签名无效");
        }
    }

    public static PublicKey decodePublicKey(String encoded) {
        try {
            byte[] keyBytes = Base64.decode(encoded, Base64.DEFAULT);
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("更新索引公钥无效", error);
        }
    }

    public static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] result = digest.digest();
            StringBuilder builder = new StringBuilder(result.length * 2);
            for (byte value : result) {
                builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return builder.toString();
        } catch (GeneralSecurityException error) {
            throw new IOException("当前系统不支持 SHA-256", error);
        }
    }

    private UpdateCatalog readCachedCatalog() {
        try {
            File indexFile = new File(cacheDirectory, "index-v1.json");
            File signatureFile = new File(cacheDirectory, "index-v1.json.sig");
            if (!indexFile.isFile() || !signatureFile.isFile()) {
                return null;
            }
            byte[] indexBytes = readFile(indexFile, MAX_INDEX_BYTES);
            byte[] signatureBytes = readFile(signatureFile, 64 * 1024);
            verifySignature(indexBytes, signatureBytes, publicKey);
            return UpdateCatalog.parse(new String(indexBytes, StandardCharsets.UTF_8));
        } catch (IOException | GeneralSecurityException | JSONException ignored) {
            return null;
        }
    }

    private void downloadToFile(String url, long expectedSize, File outputFile) throws IOException {
        if (expectedSize <= 0L || expectedSize > MAX_ASSET_BYTES) {
            throw new IOException("下载文件大小超出限制");
        }
        HttpURLConnection connection = open(url);
        try {
            long announcedSize = connection.getContentLengthLong();
            if (announcedSize > MAX_ASSET_BYTES) {
                throw new IOException("服务器返回的文件过大");
            }
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[16 * 1024];
                long total = 0L;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_ASSET_BYTES || total > expectedSize) {
                        throw new IOException("下载文件大小与索引不一致");
                    }
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
                if (total != expectedSize) {
                    throw new IOException("下载文件不完整");
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private byte[] readUrl(String url, int limit) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream()) {
            return readStream(input, limit);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json, application/octet-stream");
        connection.setRequestProperty("User-Agent", "AndroidToolSuite/" + BuildConfig.VERSION_NAME);
        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            connection.disconnect();
            throw new IOException("更新服务器返回 HTTP " + responseCode);
        }
        return connection;
    }

    private void ensureCacheDirectory() throws IOException {
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw new IOException("无法创建更新缓存目录");
        }
    }

    private void writeAtomically(File file, byte[] bytes) throws IOException {
        File pending = new File(file.getParentFile(), file.getName() + ".pending");
        try (FileOutputStream output = new FileOutputStream(pending)) {
            output.write(bytes);
            output.getFD().sync();
        } catch (IOException error) {
            pending.delete();
            throw error;
        }
        if (file.exists() && !file.delete()) {
            pending.delete();
            throw new IOException("无法替换更新索引缓存");
        }
        if (!pending.renameTo(file)) {
            pending.delete();
            throw new IOException("无法提交更新索引缓存");
        }
    }

    private static byte[] readFile(File file, int limit) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return readStream(input, limit);
        }
    }

    private static byte[] readStream(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > limit) {
                throw new IOException("响应内容超出限制");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String safeName(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "update.bin" : safe;
    }

    private static String readableMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message.trim();
    }

    private void deliverCatalog(CatalogCallback callback, UpdateCatalog catalog, boolean cached) {
        mainHandler.post(() -> callback.onSuccess(catalog, cached));
    }

    private void deliverError(CatalogCallback callback, String message) {
        mainHandler.post(() -> callback.onError(message));
    }

    public interface CatalogCallback {
        void onSuccess(UpdateCatalog catalog, boolean cached);

        void onError(String message);
    }

    public interface DownloadCallback {
        void onSuccess(File file);

        void onError(String message);
    }
}
