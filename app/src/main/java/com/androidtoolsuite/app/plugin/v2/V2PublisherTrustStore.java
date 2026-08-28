package com.androidtoolsuite.app.plugin.v2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.androidtoolsuite.app.BuildConfig;
import com.androidtoolsuite.runtime.contract.ContractException;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Publisher trust roots for same-process Native Provider generations. */
public final class V2PublisherTrustStore {
    private static final String PREFS = "runtime_v2_publisher_trust";
    private static final String PREF_DEVELOPER_KEYS = "developer_keys";
    private static final Set<String> OFFICIAL_PUBLISHERS = Set.of(
            "android_tool_suite",
            "android_tool_suite.providers"
    );

    private final SharedPreferences preferences;

    public V2PublisherTrustStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String verify(String publisher, byte[] signedPayload, byte[] signatureBytes)
            throws ContractException {
        List<PublicKey> keys = keysFor(publisher);
        if (keys.isEmpty()) {
            throw new ContractException("Native Provider publisher 未受信任：" + publisher);
        }
        for (PublicKey key : keys) {
            try {
                Signature verifier = Signature.getInstance("SHA256withECDSA");
                verifier.initVerify(key);
                verifier.update(signedPayload);
                if (verifier.verify(signatureBytes)) return fingerprint(key.getEncoded());
            } catch (Exception ignored) {
            }
        }
        throw new ContractException("Native Provider publisher 签名验证失败");
    }

    /** Debug-only hook for an explicit future Developer Mode UI; no caller silently adds keys. */
    public void addDeveloperKey(String publisher, String x509Base64) throws ContractException {
        if (!BuildConfig.DEBUG) throw new ContractException("Release 构建不允许添加开发者 Provider key");
        decodeKey(x509Base64);
        String entry = publisher.trim() + "|" + x509Base64.trim();
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>(
                preferences.getStringSet(PREF_DEVELOPER_KEYS, Collections.emptySet())
        );
        values.add(entry);
        if (!preferences.edit().putStringSet(PREF_DEVELOPER_KEYS, values).commit()) {
            throw new ContractException("无法保存开发者 Provider key");
        }
    }

    private List<PublicKey> keysFor(String publisher) throws ContractException {
        List<PublicKey> result = new ArrayList<>();
        if (OFFICIAL_PUBLISHERS.contains(publisher)) {
            result.add(decodeKey(BuildConfig.UPDATE_INDEX_PUBLIC_KEY));
        }
        if (BuildConfig.DEBUG) {
            String prefix = publisher + "|";
            for (String entry : preferences.getStringSet(PREF_DEVELOPER_KEYS, Collections.emptySet())) {
                if (entry.startsWith(prefix)) result.add(decodeKey(entry.substring(prefix.length())));
            }
        }
        return result;
    }

    private static PublicKey decodeKey(String encoded) throws ContractException {
        try {
            return KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.decode(encoded, Base64.DEFAULT))
            );
        } catch (Exception error) {
            throw new ContractException("Provider publisher key 无效", error);
        }
    }

    private static String fingerprint(byte[] bytes) throws ContractException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder();
            for (byte item : digest) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return value.toString();
        } catch (Exception error) {
            throw new ContractException("无法计算 publisher key 指纹", error);
        }
    }
}
