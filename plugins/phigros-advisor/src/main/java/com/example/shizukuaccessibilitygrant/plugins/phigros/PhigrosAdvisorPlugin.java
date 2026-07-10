package com.example.shizukuaccessibilitygrant.plugins.phigros;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.shizukuaccessibilitygrant.plugin.api.HomeWidget;
import com.example.shizukuaccessibilitygrant.plugin.api.PluginHost;
import com.example.shizukuaccessibilitygrant.plugin.api.ToolPlugin;
import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor;
import com.example.shizukuaccessibilitygrant.ui.UiKit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class PhigrosAdvisorPlugin implements ToolPlugin {
    private static final String PREFS_NAME = "phigros_advisor";
    private static final String PREF_RAW_JSON = "raw_json";
    private static final String PREF_LAST_RKS = "last_rks";
    private static final String PREF_LAST_COUNT = "last_count";
    private static final String PREF_LAST_PHI = "last_phi";
    private static final String PREF_DIFFICULTY_TSV = "difficulty_tsv";
    private static final int BEST_COUNT = 27;
    private static final int PHI_COUNT = 3;
    private static final int RKS_DIVISOR = 30;
    private static final double PERFECT_SCORE = 1000000.0;
    private static final double EPSILON = 0.00001;
    private static final String LEAN_HOST = "https://rak3ffdi.cloud.tds1.tapapis.cn";
    private static final String LEAN_APP_ID = "rAK3FfdieFob2Nn8Am";
    private static final String LEAN_APP_KEY = "Qr9AEqtuoSVS3zeD6iVbM4ZC0AtkJcQ89tywVyi0";
    private static final String TAP_CLIENT_ID = "rAK3FfdieFob2Nn8Am";
    private static final String TAP_ACCOUNTS_HOST = "https://accounts.tapapis.cn";
    private static final String TAP_OPEN_HOST = "https://open.tapapis.cn";
    private static final String DIFFICULTY_URL = "https://raw.githubusercontent.com/7aGiven/PhigrosLibrary/main/difficulty.tsv";
    private static final byte[] SAVE_KEY = new byte[] {
            (byte) 0xe8, (byte) 0x96, (byte) 0x9a, (byte) 0xd2,
            (byte) 0xa5, 0x40, 0x25, (byte) 0x9b,
            (byte) 0x97, (byte) 0x91, (byte) 0x90, (byte) 0x8b,
            (byte) 0x88, (byte) 0xe6, (byte) 0xbf, 0x03,
            0x1e, 0x6d, 0x21, (byte) 0x95,
            0x6e, (byte) 0xfa, (byte) 0xd6, (byte) 0x8a,
            0x50, (byte) 0xdd, 0x55, (byte) 0xd6,
            0x7a, (byte) 0xb0, (byte) 0x92, 0x4b
    };
    private static final byte[] SAVE_IV = new byte[] {
            0x2a, 0x4f, (byte) 0xf0, (byte) 0x8a,
            (byte) 0xc8, 0x0d, 0x63, 0x07,
            0x00, 0x57, (byte) 0xc5, (byte) 0x95,
            0x18, (byte) 0xc8, 0x32, 0x53
    };

    private final ImportedPluginDescriptor descriptor;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Activity activity;
    private SharedPreferences preferences;
    private View rootView;
    private EditText sessionTokenBox;
    private EditText importBox;
    private EditText searchBox;
    private TextView summaryText;
    private TextView statusText;
    private TextView loginLinkText;
    private LinearLayout scoreList;
    private LinearLayout adviceList;
    private LoginRequest currentLoginRequest;
    private List<Record> records = new ArrayList<>();
    private RksSnapshot snapshot = RksSnapshot.empty();

    public PhigrosAdvisorPlugin() {
        this(PhigrosAdvisorPluginDescriptor.create());
    }

    public PhigrosAdvisorPlugin(ImportedPluginDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public String id() {
        return descriptor.id;
    }

    @Override
    public String title() {
        return descriptor.title;
    }

    @Override
    public String description() {
        return descriptor.description;
    }

    @Override
    public String version() {
        return descriptor.version;
    }

    @Override
    public boolean removable() {
        return true;
    }

    @Override
    public Set<String> requestedPermissions() {
        return descriptor.requestedPermissions;
    }

    @Override
    public Set<String> dependencies() {
        return descriptor.dependencies;
    }

    @Override
    public List<HomeWidget> createHomeWidgets(Activity activity, PluginHost host) {
        return Collections.singletonList(new HomeWidget() {
            @Override
            public String id() {
                return "rks_summary";
            }

            @Override
            public String title() {
                return "Phigros RKS";
            }

            @Override
            public String pluginId() {
                return PhigrosAdvisorPlugin.this.id();
            }

            @Override
            public View createView(Activity activity, PluginHost host) {
                SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
                double rks = Double.longBitsToDouble(prefs.getLong(PREF_LAST_RKS, Double.doubleToLongBits(0)));
                int count = prefs.getInt(PREF_LAST_COUNT, 0);
                String phi = prefs.getString(PREF_LAST_PHI, "");

                LinearLayout card = UiKit.card(activity);

                TextView title = new TextView(activity);
                title.setText("Phigros 查分");
                UiKit.styleCaption(title);
                card.addView(title, new LinearLayout.LayoutParams(-1, -2));

                TextView value = new TextView(activity);
                value.setText(count == 0 ? "等待导入" : formatRks(rks));
                UiKit.styleTitle(value, 20);
                LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-1, -2);
                valueParams.topMargin = UiKit.dp(activity, 4);
                card.addView(value, valueParams);

                TextView subtitle = new TextView(activity);
                subtitle.setText(count == 0 ? "抓取云存档或粘贴 JSON 后生成建议" : count + " 条成绩" + (phi.isEmpty() ? "" : " · Phi " + phi));
                UiKit.styleBody(subtitle);
                LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
                subtitleParams.topMargin = UiKit.dp(activity, 4);
                card.addView(subtitle, subtitleParams);
                return card;
            }
        });
    }

    @Override
    public View createView(Activity activity, PluginHost host) {
        this.activity = activity;
        this.preferences = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
        if (rootView == null) {
            rootView = createContentView();
            String saved = preferences.getString(PREF_RAW_JSON, "");
            importBox.setText(saved);
            if (!saved.trim().isEmpty()) {
                parseAndRender(saved, false);
            }
        }
        return rootView;
    }

    @Override
    public void onSelected() {
    }

    @Override
    public void onHostStateChanged() {
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
    }

    private View createContentView() {
        int gap = dp(12);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(activity);
        title.setText("Phigros 查分助手");
        UiKit.styleTitle(title, 22);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(activity);
        note.setText("输入 Phigros 云存档 SessionToken 可直接抓取远程存档；也可以粘贴公开查分库、Bot 后端或手工整理出的成绩 JSON。SessionToken 不会自动保存。");
        UiKit.styleBody(note);
        note.setTextColor(UiKit.COLOR_WARN);
        note.setPadding(dp(12), dp(10), dp(12), dp(10));
        note.setBackground(UiKit.rounded(0xFFFFF7ED, 8, activity));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(10);
        root.addView(note, noteParams);

        summaryText = new TextView(activity);
        summaryText.setText("尚未导入成绩");
        summaryText.setTextColor(UiKit.COLOR_TEXT);
        summaryText.setTextSize(15);
        summaryText.setTypeface(Typeface.DEFAULT_BOLD);
        summaryText.setPadding(dp(12), dp(12), dp(12), dp(12));
        summaryText.setBackground(UiKit.roundedStroke(UiKit.COLOR_SURFACE, UiKit.COLOR_BORDER, 8, activity));
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
        summaryParams.topMargin = gap;
        root.addView(summaryText, summaryParams);

        sessionTokenBox = new EditText(activity);
        sessionTokenBox.setSingleLine(true);
        sessionTokenBox.setTextSize(13);
        sessionTokenBox.setHint("SessionToken，用于读取 TapTap/LeanCloud 云存档");
        sessionTokenBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sessionTokenBox.setPadding(dp(12), 0, dp(12), 0);
        sessionTokenBox.setBackground(UiKit.roundedStroke(UiKit.COLOR_SURFACE, UiKit.COLOR_BORDER, 8, activity));
        LinearLayout.LayoutParams tokenParams = new LinearLayout.LayoutParams(-1, dp(48));
        tokenParams.topMargin = gap;
        root.addView(sessionTokenBox, tokenParams);

        LinearLayout remoteActions = new LinearLayout(activity);
        remoteActions.setOrientation(LinearLayout.HORIZONTAL);
        remoteActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams remoteParams = new LinearLayout.LayoutParams(-1, -2);
        remoteParams.topMargin = gap;

        Button fetchButton = new Button(activity);
        fetchButton.setText("抓取云存档");
        UiKit.stylePrimaryButton(fetchButton);
        fetchButton.setOnClickListener(v -> fetchCloudSave());
        remoteActions.addView(fetchButton, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button difficultyButton = new Button(activity);
        difficultyButton.setText("更新定数");
        UiKit.styleSecondaryButton(difficultyButton);
        difficultyButton.setOnClickListener(v -> updateDifficultyTable());
        LinearLayout.LayoutParams difficultyParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        difficultyParams.leftMargin = gap;
        remoteActions.addView(difficultyButton, difficultyParams);
        root.addView(remoteActions, remoteParams);

        LinearLayout loginActions = new LinearLayout(activity);
        loginActions.setOrientation(LinearLayout.HORIZONTAL);
        loginActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams loginParams = new LinearLayout.LayoutParams(-1, -2);
        loginParams.topMargin = gap;

        Button loginButton = new Button(activity);
        loginButton.setText("获取 Token 链接");
        UiKit.styleSecondaryButton(loginButton);
        loginButton.setOnClickListener(v -> requestSessionLogin());
        loginActions.addView(loginButton, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button pollButton = new Button(activity);
        pollButton.setText("检查扫码结果");
        UiKit.styleSecondaryButton(pollButton);
        pollButton.setOnClickListener(v -> pollSessionLogin());
        LinearLayout.LayoutParams pollParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        pollParams.leftMargin = gap;
        loginActions.addView(pollButton, pollParams);
        root.addView(loginActions, loginParams);

        LinearLayout linkActions = new LinearLayout(activity);
        linkActions.setOrientation(LinearLayout.HORIZONTAL);
        linkActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(-1, -2);
        linkParams.topMargin = dp(8);

        Button copyButton = new Button(activity);
        copyButton.setText("复制链接");
        UiKit.styleSecondaryButton(copyButton);
        copyButton.setOnClickListener(v -> copyLoginLink());
        linkActions.addView(copyButton, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button openButton = new Button(activity);
        openButton.setText("浏览器打开");
        UiKit.styleSecondaryButton(openButton);
        openButton.setOnClickListener(v -> openLoginLink());
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        openParams.leftMargin = gap;
        linkActions.addView(openButton, openParams);
        root.addView(linkActions, linkParams);

        loginLinkText = new TextView(activity);
        loginLinkText.setText("SessionToken 获取参考 phi-plugin 的 TapTap 设备码流程：生成链接后用已登录 TapTap 的浏览器打开并确认授权，再回到这里检查结果。");
        UiKit.styleBody(loginLinkText);
        loginLinkText.setPadding(dp(12), dp(10), dp(12), dp(10));
        loginLinkText.setBackground(UiKit.roundedStroke(UiKit.COLOR_SURFACE, UiKit.COLOR_BORDER, 8, activity));
        LinearLayout.LayoutParams loginLinkParams = new LinearLayout.LayoutParams(-1, -2);
        loginLinkParams.topMargin = dp(8);
        root.addView(loginLinkText, loginLinkParams);

        importBox = new EditText(activity);
        importBox.setMinLines(5);
        importBox.setMaxLines(10);
        importBox.setGravity(Gravity.TOP | Gravity.START);
        importBox.setTextSize(13);
        importBox.setHint("[{\"title\":\"Spasmodic\",\"level\":\"IN\",\"difficulty\":15.8,\"score\":995000}]");
        importBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        importBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        importBox.setBackground(UiKit.roundedStroke(UiKit.COLOR_SURFACE, UiKit.COLOR_BORDER, 8, activity));
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(-1, -2);
        importParams.topMargin = gap;
        root.addView(importBox, importParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = gap;

        Button importButton = new Button(activity);
        importButton.setText("导入 JSON");
        UiKit.stylePrimaryButton(importButton);
        importButton.setOnClickListener(v -> parseAndRender(importBox.getText().toString(), true));
        actions.addView(importButton, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button exampleButton = new Button(activity);
        exampleButton.setText("填入示例");
        UiKit.styleSecondaryButton(exampleButton);
        exampleButton.setOnClickListener(v -> importBox.setText(exampleJson()));
        LinearLayout.LayoutParams exampleParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        exampleParams.leftMargin = gap;
        actions.addView(exampleButton, exampleParams);
        root.addView(actions, actionsParams);

        statusText = new TextView(activity);
        statusText.setText("");
        UiKit.styleBody(statusText);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(8);
        root.addView(statusText, statusParams);

        TextView adviceTitle = new TextView(activity);
        adviceTitle.setText("推分建议");
        UiKit.styleTitle(adviceTitle, 18);
        LinearLayout.LayoutParams adviceTitleParams = new LinearLayout.LayoutParams(-1, -2);
        adviceTitleParams.topMargin = dp(18);
        root.addView(adviceTitle, adviceTitleParams);

        adviceList = new LinearLayout(activity);
        adviceList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams adviceParams = new LinearLayout.LayoutParams(-1, -2);
        adviceParams.topMargin = dp(8);
        root.addView(adviceList, adviceParams);

        searchBox = new EditText(activity);
        searchBox.setSingleLine(true);
        searchBox.setTextSize(15);
        searchBox.setHint("搜索曲名、难度或等级");
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchBox.setPadding(dp(12), 0, dp(12), 0);
        searchBox.setBackground(UiKit.roundedStroke(UiKit.COLOR_SURFACE, UiKit.COLOR_BORDER, 8, activity));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                showScores();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(48));
        searchParams.topMargin = dp(18);
        root.addView(searchBox, searchParams);

        scoreList = new LinearLayout(activity);
        scoreList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, -2);
        listParams.topMargin = dp(8);
        root.addView(scoreList, listParams);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(false);
        scrollView.setPadding(dp(16), dp(16), dp(16), dp(24));
        scrollView.addView(root, new ScrollView.LayoutParams(-1, -2));
        return scrollView;
    }

    private void parseAndRender(String raw, boolean save) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            records = new ArrayList<>();
            snapshot = RksSnapshot.empty();
            statusText.setText("请先粘贴成绩 JSON。");
            updateSummary();
            showAdvice();
            showScores();
            return;
        }

        try {
            List<Record> parsed = parseRecords(trimmed);
            records = mergeDuplicates(parsed);
            snapshot = calculateSnapshot(records);
            if (save) {
                preferences.edit()
                        .putString(PREF_RAW_JSON, trimmed)
                        .putLong(PREF_LAST_RKS, Double.doubleToLongBits(snapshot.overallRks))
                        .putInt(PREF_LAST_COUNT, records.size())
                        .putString(PREF_LAST_PHI, snapshot.phiRecords.isEmpty() ? "" : snapshot.phiRecords.get(0).shortName())
                        .apply();
            }
            statusText.setText("已解析 " + records.size() + " 条有效成绩。");
            updateSummary();
            showAdvice();
            showScores();
        } catch (JSONException e) {
            statusText.setText("JSON 解析失败：" + e.getMessage());
        }
    }

    private void fetchCloudSave() {
        if (!ensureInternetPermission()) {
            return;
        }
        String token = sessionTokenBox == null ? "" : sessionTokenBox.getText().toString().trim();
        if (token.isEmpty()) {
            statusText.setText("请先输入 SessionToken。");
            return;
        }
        statusText.setText("正在抓取云存档...");
        executor.execute(() -> {
            try {
                Map<String, double[]> difficulty = loadDifficultyTable();
                CloudSaveInfo info = fetchLatestCloudSaveInfo(token);
                byte[] saveBytes = httpGetBytes(info.url, null);
                List<Record> cloudRecords = parseCloudSave(saveBytes, difficulty);
                if (cloudRecords.isEmpty()) {
                    throw new IOException("云存档解析完成，但没有找到可计算成绩。");
                }
                String json = recordsToJson(cloudRecords);
                activity.runOnUiThread(() -> {
                    importBox.setText(json);
                    records = mergeDuplicates(cloudRecords);
                    snapshot = calculateSnapshot(records);
                    preferences.edit()
                            .putString(PREF_RAW_JSON, json)
                            .putLong(PREF_LAST_RKS, Double.doubleToLongBits(snapshot.overallRks))
                            .putInt(PREF_LAST_COUNT, records.size())
                            .putString(PREF_LAST_PHI, snapshot.phiRecords.isEmpty() ? "" : snapshot.phiRecords.get(0).shortName())
                            .apply();
                    statusText.setText("已抓取 " + info.label + "，解析 " + records.size() + " 条成绩。");
                    updateSummary();
                    showAdvice();
                    showScores();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> statusText.setText("云存档抓取失败：" + e.getMessage()));
            }
        });
    }

    private void updateDifficultyTable() {
        if (!ensureInternetPermission()) {
            return;
        }
        statusText.setText("正在更新定数表...");
        executor.execute(() -> {
            try {
                String tsv = httpGetString(DIFFICULTY_URL, null);
                Map<String, double[]> table = parseDifficultyTable(tsv);
                if (table.isEmpty()) {
                    throw new IOException("下载到的定数表为空。");
                }
                preferences.edit().putString(PREF_DIFFICULTY_TSV, tsv).apply();
                activity.runOnUiThread(() -> statusText.setText("定数表已更新，包含 " + table.size() + " 首歌曲。"));
            } catch (Exception e) {
                activity.runOnUiThread(() -> statusText.setText("定数表更新失败：" + e.getMessage()));
            }
        });
    }

    private void requestSessionLogin() {
        if (!ensureInternetPermission()) {
            return;
        }
        statusText.setText("正在向 TapTap 请求登录链接...");
        executor.execute(() -> {
            try {
                LoginRequest request = requestTapTapLogin();
                currentLoginRequest = request;
                activity.runOnUiThread(() -> {
                    loginLinkText.setText("登录链接：" + request.loginUrl
                            + "\n有效期约 " + request.expiresIn + " 秒，建议打开后完成授权，再点“检查扫码结果”。");
                    statusText.setText("登录链接已生成。");
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> statusText.setText("生成登录链接失败：" + e.getMessage()));
            }
        });
    }

    private void pollSessionLogin() {
        if (!ensureInternetPermission()) {
            return;
        }
        LoginRequest request = currentLoginRequest;
        if (request == null) {
            statusText.setText("请先生成 Token 登录链接。");
            return;
        }
        statusText.setText("正在检查 TapTap 授权结果...");
        executor.execute(() -> {
            try {
                JSONObject tokenResult = checkTapTapLogin(request);
                if (tokenResult.optJSONObject("data") == null) {
                    String error = tokenResult.optString("error", tokenResult.optString("msg", tokenResult.toString()));
                    activity.runOnUiThread(() -> statusText.setText("尚未完成授权：" + error));
                    return;
                }
                JSONObject tokenData = tokenResult.getJSONObject("data");
                JSONObject profile = getTapTapProfile(tokenData);
                String sessionToken = loginLeanCloudWithTapTap(profile.optJSONObject("data"), tokenData);
                activity.runOnUiThread(() -> {
                    sessionTokenBox.setText(sessionToken);
                    statusText.setText("已获取 SessionToken，开始抓取云存档。");
                    fetchCloudSave();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> statusText.setText("获取 SessionToken 失败：" + e.getMessage()));
            }
        });
    }

    private void copyLoginLink() {
        if (currentLoginRequest == null) {
            statusText.setText("还没有可复制的登录链接。");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("TapTap login", currentLoginRequest.loginUrl));
            statusText.setText("登录链接已复制。");
        }
    }

    private void openLoginLink() {
        if (currentLoginRequest == null) {
            statusText.setText("还没有可打开的登录链接。");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(currentLoginRequest.loginUrl));
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            statusText.setText("无法打开浏览器：" + e.getMessage());
        }
    }

    private boolean ensureInternetPermission() {
        int result = activity.getPackageManager().checkPermission(Manifest.permission.INTERNET, activity.getPackageName());
        if (result == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        statusText.setText("宿主 App 缺少 INTERNET 权限。外部插件包无法单独获得网络权限，请安装/更新新版宿主 APK 后再试。");
        return false;
    }

    private LoginRequest requestTapTapLogin() throws IOException, JSONException {
        String deviceId = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", TAP_CLIENT_ID);
        form.put("response_type", "device_code");
        form.put("scope", "public_profile");
        form.put("version", "2.1");
        form.put("platform", "unity");
        form.put("info", "{\"device_id\":\"" + deviceId + "\"}");
        JSONObject json = new JSONObject(httpPostForm(TAP_ACCOUNTS_HOST + "/oauth2/v1/device/code", form, null));
        JSONObject data = json.optJSONObject("data");
        if (data == null) {
            data = json;
        }
        String deviceCode = data.optString("device_code", "");
        String loginUrl = data.optString("qrcode_url", "");
        int expiresIn = data.optInt("expires_in", 300);
        int interval = Math.max(1, data.optInt("interval", 2));
        if (deviceCode.isEmpty() || loginUrl.isEmpty()) {
            throw new IOException("TapTap 没有返回 device_code/qrcode_url：" + json);
        }
        return new LoginRequest(deviceId, deviceCode, loginUrl, expiresIn, interval);
    }

    private JSONObject checkTapTapLogin(LoginRequest request) throws IOException, JSONException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "device_token");
        form.put("client_id", TAP_CLIENT_ID);
        form.put("secret_type", "hmac-sha-1");
        form.put("code", request.deviceCode);
        form.put("version", "1.0");
        form.put("platform", "unity");
        form.put("info", "{\"device_id\":\"" + request.deviceId + "\"}");
        return new JSONObject(httpPostForm(TAP_ACCOUNTS_HOST + "/oauth2/v1/token", form, null));
    }

    private JSONObject getTapTapProfile(JSONObject tokenData) throws Exception {
        String url = TAP_OPEN_HOST + "/account/profile/v1?client_id=" + TAP_CLIENT_ID;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", tapAuthorization(url, "GET", tokenData.getString("kid"), tokenData.getString("mac_key")));
        return new JSONObject(httpGetString(url, headers));
    }

    private String loginLeanCloudWithTapTap(JSONObject profileData, JSONObject tokenData) throws Exception {
        if (profileData == null) {
            throw new IOException("TapTap profile 为空。");
        }
        JSONObject auth = new JSONObject();
        copyJson(profileData, auth);
        copyJson(tokenData, auth);
        JSONObject body = new JSONObject();
        body.put("authData", new JSONObject().put("taptap", auth));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-LC-Id", LEAN_APP_ID);
        headers.put("X-LC-Sign", leanSign());
        headers.put("Content-Type", "application/json");
        JSONObject response = new JSONObject(httpPostJson(LEAN_HOST + "/1.1/users", body.toString(), headers));
        String sessionToken = response.optString("sessionToken", "");
        if (sessionToken.isEmpty()) {
            throw new IOException("LeanCloud 没有返回 sessionToken：" + response);
        }
        return sessionToken;
    }

    private CloudSaveInfo fetchLatestCloudSaveInfo(String sessionToken) throws IOException, JSONException {
        Map<String, String> headers = leanHeaders(sessionToken);
        JSONObject user = new JSONObject(httpGetString(LEAN_HOST + "/1.1/users/me", headers));
        String objectId = user.optString("objectId", "");
        if (objectId.isEmpty()) {
            throw new IOException("SessionToken 无法读取 /users/me。");
        }
        String where = "{\"user\":{\"__type\":\"Pointer\",\"className\":\"_User\",\"objectId\":\"" + objectId + "\"}}";
        String queryUrl = LEAN_HOST + "/1.1/gamesaves/?skip=0&limit=100&where="
                + URLEncoder.encode(where, "UTF-8")
                + "&include=cover,gameFile";
        JSONObject root = new JSONObject(httpGetString(queryUrl, headers));
        JSONArray results = root.optJSONArray("results");
        if (results == null || results.length() == 0) {
            throw new IOException("没有找到云存档记录。");
        }
        JSONObject save = newestSave(results);
        JSONObject file = save.optJSONObject("gameFile");
        String fileUrl = file == null ? "" : file.optString("url", "");
        if (fileUrl.isEmpty()) {
            throw new IOException("云存档缺少 gameFile.url。");
        }
        String updatedAt = save.optString("updatedAt", "");
        String saveObjectId = save.optString("objectId", "");
        String label = updatedAt.isEmpty() ? saveObjectId : updatedAt;
        if (label.isEmpty()) {
            label = "最新云存档";
        }
        return new CloudSaveInfo(fileUrl, label);
    }

    private JSONObject newestSave(JSONArray results) {
        JSONObject best = results.optJSONObject(0);
        String bestTime = modifiedTime(best);
        for (int i = 1; i < results.length(); i++) {
            JSONObject candidate = results.optJSONObject(i);
            String time = modifiedTime(candidate);
            if (time.compareTo(bestTime) > 0) {
                best = candidate;
                bestTime = time;
            }
        }
        return best == null ? new JSONObject() : best;
    }

    private String modifiedTime(JSONObject save) {
        if (save == null) {
            return "";
        }
        JSONObject modifiedAt = save.optJSONObject("modifiedAt");
        String iso = modifiedAt == null ? "" : modifiedAt.optString("iso", "");
        if (iso.isEmpty()) {
            iso = save.optString("updatedAt", save.optString("createdAt", ""));
        }
        return iso == null ? "" : iso;
    }

    private Map<String, String> leanHeaders(String sessionToken) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-LC-Key", LEAN_APP_KEY);
        headers.put("X-LC-Session", sessionToken);
        headers.put("X-LC-Id", LEAN_APP_ID);
        headers.put("User-Agent", "LeanCloud-CSharp-SDK/1.0.3");
        headers.put("Accept", "application/json");
        return headers;
    }

    private Map<String, double[]> loadDifficultyTable() throws IOException {
        String cached = preferences.getString(PREF_DIFFICULTY_TSV, "");
        if (cached.trim().isEmpty()) {
            cached = httpGetString(DIFFICULTY_URL, null);
            preferences.edit().putString(PREF_DIFFICULTY_TSV, cached).apply();
        }
        Map<String, double[]> table = parseDifficultyTable(cached);
        if (table.isEmpty()) {
            throw new IOException("定数表为空，请稍后重试。");
        }
        return table;
    }

    private Map<String, double[]> parseDifficultyTable(String tsv) {
        Map<String, double[]> table = new LinkedHashMap<>();
        String[] lines = tsv.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\t");
            if (parts.length < 4) {
                continue;
            }
            double[] values = new double[] {0, 0, 0, 0};
            for (int i = 1; i < parts.length && i <= 4; i++) {
                try {
                    values[i - 1] = Double.parseDouble(parts[i]);
                } catch (NumberFormatException ignored) {
                    values[i - 1] = 0;
                }
            }
            table.put(parts[0], values);
        }
        return table;
    }

    private List<Record> parseCloudSave(byte[] zipBytes, Map<String, double[]> difficulties) throws Exception {
        byte[] encryptedRecord = readZipEntry(zipBytes, "gameRecord");
        if (encryptedRecord.length < 2) {
            throw new IOException("gameRecord 数据为空。");
        }
        byte[] plain = decryptSaveEntry(encryptedRecord);
        SaveCursor cursor = new SaveCursor(plain, 1);
        int songCount = cursor.readVarShort();
        List<Record> parsed = new ArrayList<>();
        String[] levels = new String[] {"EZ", "HD", "IN", "AT"};
        for (int i = 0; i < songCount && cursor.hasRemaining(); i++) {
            String songId = cursor.readString(2);
            int blockSize = cursor.readUnsignedByte();
            int nextOffset = Math.min(cursor.offset + blockSize, plain.length);
            int presentMask = cursor.readUnsignedByte();
            int fcMask = cursor.readUnsignedByte();
            double[] constants = difficulties.get(songId);
            for (int level = 0; level < 4; level++) {
                if ((presentMask & (1 << level)) == 0) {
                    continue;
                }
                int score = cursor.readIntLE();
                double acc = cursor.readFloatLE();
                double difficulty = constants == null || level >= constants.length ? 0 : constants[level];
                if (score > 0 && acc > 0 && difficulty > 0) {
                    parsed.add(new Record(songId, levels[level], difficulty, score, acc));
                }
                if ((fcMask & (1 << level)) != 0) {
                    // FC/AP status is carried by score and acc for current recommendations.
                }
            }
            cursor.offset = nextOffset;
        }
        return parsed;
    }

    private byte[] readZipEntry(byte[] zipBytes, String entryName) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return readAll(zip);
                }
            }
        }
        throw new IOException("存档 zip 中没有 " + entryName + "。");
    }

    private byte[] decryptSaveEntry(byte[] encrypted) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(SAVE_KEY, "AES"), new IvParameterSpec(SAVE_IV));
        byte[] decrypted = cipher.doFinal(encrypted, 1, encrypted.length - 1);
        byte[] plain = new byte[decrypted.length + 1];
        plain[0] = encrypted[0];
        System.arraycopy(decrypted, 0, plain, 1, decrypted.length);
        return plain;
    }

    private String recordsToJson(List<Record> source) throws JSONException {
        JSONArray array = new JSONArray();
        List<Record> sorted = new ArrayList<>(source);
        Collections.sort(sorted, Record.BY_RKS_DESC);
        for (Record record : sorted) {
            JSONObject json = new JSONObject();
            json.put("title", record.title);
            json.put("level", record.level);
            json.put("difficulty", record.difficulty);
            json.put("score", Math.round(record.score));
            json.put("accuracy", record.accuracy);
            array.put(json);
        }
        return array.toString(2);
    }

    private String httpGetString(String url, Map<String, String> headers) throws IOException {
        return new String(httpGetBytes(url, headers), StandardCharsets.UTF_8);
    }

    private String httpPostForm(String rawUrl, Map<String, String> form, Map<String, String> headers) throws IOException {
        String body = formEncode(form);
        Map<String, String> merged = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        merged.put("Content-Type", "application/x-www-form-urlencoded");
        return httpPost(rawUrl, body.getBytes(StandardCharsets.UTF_8), merged);
    }

    private String httpPostJson(String rawUrl, String body, Map<String, String> headers) throws IOException {
        return httpPost(rawUrl, body.getBytes(StandardCharsets.UTF_8), headers);
    }

    private String httpPost(String rawUrl, byte[] body, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(rawUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        connection.getOutputStream().write(body);
        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        byte[] response = input == null ? new byte[0] : readAll(input);
        connection.disconnect();
        String text = new String(response, StandardCharsets.UTF_8);
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + (text.isEmpty() ? "" : " " + text));
        }
        return text;
    }

    private byte[] httpGetBytes(String rawUrl, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(rawUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("GET");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        byte[] body = input == null ? new byte[0] : readAll(input);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            String message = new String(body, StandardCharsets.UTF_8);
            throw new IOException("HTTP " + code + (message.isEmpty() ? "" : " " + message));
        }
        return body;
    }

    private String formEncode(Map<String, String> form) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            builder.append('=');
            builder.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return builder.toString();
    }

    private String tapAuthorization(String requestUrl, String method, String keyId, String macKey) throws Exception {
        URL url = new URL(requestUrl);
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = randomBase64(16);
        String path = url.getPath() + (url.getQuery() == null ? "" : "?" + url.getQuery());
        String port = url.getPort() >= 0 ? String.valueOf(url.getPort()) : ("https".equals(url.getProtocol()) ? "443" : "80");
        String signBase = ts + "\n" + nonce + "\n" + method + "\n" + path + "\n" + url.getHost() + "\n" + port + "\n\n";
        String mac = hmacSha1(signBase, macKey);
        return "MAC id=\"" + keyId + "\", ts=\"" + ts + "\", nonce=\"" + nonce + "\", mac=\"" + mac + "\"";
    }

    private String leanSign() throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        return md5(timestamp + LEAN_APP_KEY) + "," + timestamp;
    }

    private String hmacSha1(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return android.util.Base64.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP);
    }

    private String randomBase64(int bytes) {
        byte[] buffer = new byte[bytes];
        new SecureRandom().nextBytes(buffer);
        return android.util.Base64.encodeToString(buffer, android.util.Base64.NO_WRAP);
    }

    private String md5(String data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        }
        return builder.toString();
    }

    private void copyJson(JSONObject from, JSONObject to) throws JSONException {
        JSONArray names = from.names();
        if (names == null) {
            return;
        }
        for (int i = 0; i < names.length(); i++) {
            String name = names.getString(i);
            to.put(name, from.get(name));
        }
    }

    private byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void updateSummary() {
        if (records.isEmpty()) {
            summaryText.setText("尚未导入成绩");
            return;
        }
        String phi = snapshot.phiRecords.isEmpty()
                ? "暂无 AP/Phi 候选"
                : "P" + snapshot.phiRecords.size() + "/P" + PHI_COUNT + " 最高 " + snapshot.phiRecords.get(0).shortName();
        summaryText.setText("估算 RKS " + formatRks(snapshot.overallRks)
                + " · B" + snapshot.bestRecords.size() + "/B" + BEST_COUNT
                + " · " + phi);
    }

    private void showAdvice() {
        adviceList.removeAllViews();
        if (records.isEmpty()) {
            addEmpty(adviceList, "导入成绩后显示最值得推的谱面。");
            return;
        }

        List<Advice> advices = buildAdvices(records, snapshot);
        if (advices.isEmpty()) {
            addEmpty(adviceList, "当前记录已接近可计算上限，暂无明显推分建议。");
            return;
        }

        int count = Math.min(10, advices.size());
        for (int i = 0; i < count; i++) {
            addAdviceCard(advices.get(i), i + 1);
        }
    }

    private void showScores() {
        scoreList.removeAllViews();
        if (records.isEmpty()) {
            addEmpty(scoreList, "导入成绩后可在这里查分。");
            return;
        }

        String query = searchBox == null ? "" : searchBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Record> visible = new ArrayList<>();
        for (Record record : records) {
            if (query.isEmpty() || record.searchText().contains(query)) {
                visible.add(record);
            }
        }
        Collections.sort(visible, Record.BY_RKS_DESC);

        if (visible.isEmpty()) {
            addEmpty(scoreList, "没有匹配的成绩。");
            return;
        }

        int limit = Math.min(60, visible.size());
        for (int i = 0; i < limit; i++) {
            addScoreCard(visible.get(i), i + 1);
        }
        if (visible.size() > limit) {
            addEmpty(scoreList, "还有 " + (visible.size() - limit) + " 条结果未显示，请继续缩小搜索范围。");
        }
    }

    private void addAdviceCard(Advice advice, int rank) {
        LinearLayout card = UiKit.card(activity);

        TextView title = new TextView(activity);
        title.setText(rank + ". " + advice.record.shortName());
        UiKit.styleTitle(title, 16);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView detail = new TextView(activity);
        detail.setText("当前 " + advice.record.displayScore()
                + " · " + advice.record.displayAcc()
                + " · 单曲 " + formatRks(advice.record.rks())
                + "\n建议冲 " + advice.targetLabel
                + "，预计总 RKS +" + formatDelta(advice.deltaOverall)
                + "，新总分约 " + formatRks(snapshot.overallRks + advice.deltaOverall));
        UiKit.styleBody(detail);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(6);
        card.addView(detail, detailParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        adviceList.addView(card, params);
    }

    private void addScoreCard(Record record, int rank) {
        LinearLayout card = UiKit.card(activity);

        TextView title = new TextView(activity);
        title.setText(rank + ". " + record.shortName());
        UiKit.styleTitle(title, 16);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView detail = new TextView(activity);
        detail.setText(record.displayScore()
                + " · " + record.displayAcc()
                + " · 定数 " + formatDecimal(record.difficulty, 2)
                + " · 单曲 RKS " + formatRks(record.rks()));
        UiKit.styleBody(detail);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(5);
        card.addView(detail, detailParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        scoreList.addView(card, params);
    }

    private void addEmpty(LinearLayout parent, String text) {
        TextView empty = new TextView(activity);
        empty.setText(text);
        UiKit.styleBody(empty);
        empty.setPadding(dp(12), dp(12), dp(12), dp(12));
        empty.setBackground(UiKit.roundedStroke(UiKit.COLOR_SURFACE, UiKit.COLOR_BORDER, 8, activity));
        parent.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    }

    private List<Advice> buildAdvices(List<Record> source, RksSnapshot baseline) {
        List<Advice> advices = new ArrayList<>();
        List<Record> sorted = new ArrayList<>(source);
        Collections.sort(sorted, Record.BY_RKS_DESC);
        double minUpRks = Math.floor(baseline.overallRks * 100) / 100 + 0.005 - baseline.overallRks;
        if (minUpRks < 0) {
            minUpRks += 0.01;
        }
        double floorRks = sorted.size() > BEST_COUNT - 1 ? sorted.get(BEST_COUNT - 1).rks() : 0;
        for (int i = 0; i < sorted.size(); i++) {
            Record record = sorted.get(i);
            if (record.accuracy >= 100 - EPSILON) {
                continue;
            }
            double baseRks = i < BEST_COUNT - 1 ? record.rks() : floorRks;
            double targetAcc = requiredAccuracy(baseRks + minUpRks * RKS_DIVISOR, record.difficulty);
            if (targetAcc > 100 && record.rks() > floorRks) {
                targetAcc = 100;
            }
            if (targetAcc <= record.accuracy + EPSILON || targetAcc > 100 + EPSILON) {
                continue;
            }
            Record improved = record.withAccuracy(Math.min(100, targetAcc));
            List<Record> simulated = replaceRecord(source, record, improved);
            RksSnapshot next = calculateSnapshot(simulated);
            double delta = next.overallRks - baseline.overallRks;
            if (delta > 0.0005) {
                String label = targetAcc >= 99.999 ? "AP" : formatDecimal(targetAcc, 2) + "%";
                advices.add(new Advice(record, label, delta));
            }
        }
        Collections.sort(advices, (left, right) -> Double.compare(right.deltaOverall, left.deltaOverall));
        return advices;
    }

    private double requiredAccuracy(double targetRks, double difficulty) {
        if (difficulty <= 0) {
            return 101;
        }
        return 45 * Math.sqrt(targetRks / difficulty) + 55;
    }

    private List<Record> replaceRecord(List<Record> source, Record oldRecord, Record newRecord) {
        List<Record> replaced = new ArrayList<>(source.size());
        for (Record record : source) {
            replaced.add(record == oldRecord ? newRecord : record);
        }
        return replaced;
    }

    private RksSnapshot calculateSnapshot(List<Record> source) {
        if (source.isEmpty()) {
            return RksSnapshot.empty();
        }

        List<Record> sorted = new ArrayList<>(source);
        Collections.sort(sorted, Record.BY_RKS_DESC);

        List<Record> phiRecords = new ArrayList<>();
        for (Record record : sorted) {
            if (record.isPerfect() && phiRecords.size() < PHI_COUNT) {
                phiRecords.add(record);
            }
        }

        List<Record> best = new ArrayList<>();
        for (Record record : sorted) {
            if (best.size() < BEST_COUNT) {
                best.add(record);
            }
        }

        double total = 0;
        for (Record record : phiRecords) {
            total += record.rks();
        }
        for (Record record : best) {
            total += record.rks();
        }
        return new RksSnapshot(phiRecords, best, total / RKS_DIVISOR);
    }

    private List<Record> parseRecords(String raw) throws JSONException {
        Object root;
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            root = new JSONArray(trimmed);
        } else {
            root = new JSONObject(trimmed);
        }
        List<Record> parsed = new ArrayList<>();
        collectRecords(root, parsed, "", "");
        if (parsed.isEmpty()) {
            throw new JSONException("未找到可用成绩。支持数组，或 records/scores/songs/data 字段。");
        }
        return parsed;
    }

    private void collectRecords(Object node, List<Record> output, String inheritedTitle, String inheritedLevel) {
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    collectRecords(value, output, inheritedTitle, inheritedLevel);
                }
            }
            return;
        }

        if (!(node instanceof JSONObject)) {
            return;
        }

        JSONObject json = (JSONObject) node;
        Record record = readRecord(json, inheritedTitle, inheritedLevel);
        if (record != null) {
            output.add(record);
            return;
        }

        String[] arrayKeys = new String[] {"records", "scores", "songs", "charts", "data", "best", "b19"};
        for (String key : arrayKeys) {
            JSONArray array = json.optJSONArray(key);
            if (array != null) {
                collectRecords(array, output, inheritedTitle, inheritedLevel);
            }
        }

        JSONArray names = json.names();
        if (names == null) {
            return;
        }
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, "");
            Object child = json.opt(key);
            if (!(child instanceof JSONObject) && !(child instanceof JSONArray)) {
                continue;
            }
            String nextTitle = inheritedTitle;
            String nextLevel = inheritedLevel;
            if (nextTitle.isEmpty() && !isStructuralKey(key)) {
                nextTitle = key;
            } else if (nextLevel.isEmpty() && !isStructuralKey(key)) {
                nextLevel = key;
            }
            collectRecords(child, output, nextTitle, nextLevel);
        }
    }

    private Record readRecord(JSONObject json, String titleFallback, String levelFallback) {
        String title = firstString(json, "title", "song", "songName", "name", "id", "chart");
        String level = firstString(json, "level", "difficultyName", "diff", "chartLevel", "rank");
        if (title.isEmpty()) {
            title = titleFallback;
        }
        if (level.isEmpty()) {
            level = levelFallback;
        }
        double difficulty = firstDouble(json, "constant", "difficulty", "rating", "ds", "levelValue", "chartConstant");
        double score = firstDouble(json, "score", "best", "record", "value");
        double accuracy = firstDouble(json, "accuracy", "acc", "rate");

        if (title.isEmpty() || difficulty <= 0) {
            return null;
        }
        if (accuracy <= 0 && score > 0) {
            accuracy = score / 10000.0;
        }
        if (accuracy > 0 && accuracy <= 1.0) {
            accuracy *= 100.0;
        }
        if (score <= 0 && accuracy > 0) {
            score = Math.round(accuracy * 10000.0);
        }
        if (accuracy <= 0) {
            return null;
        }
        accuracy = clamp(accuracy, 0, 100);
        score = clamp(score, 0, PERFECT_SCORE);
        return new Record(title, level, difficulty, score, accuracy);
    }

    private boolean isStructuralKey(String key) {
        return "records".equals(key)
                || "scores".equals(key)
                || "songs".equals(key)
                || "charts".equals(key)
                || "data".equals(key)
                || "best".equals(key)
                || "b19".equals(key);
    }

    private List<Record> mergeDuplicates(List<Record> source) {
        List<Record> merged = new ArrayList<>();
        for (Record record : source) {
            int existingIndex = -1;
            for (int i = 0; i < merged.size(); i++) {
                Record existing = merged.get(i);
                if (existing.identity().equals(record.identity())) {
                    existingIndex = i;
                    break;
                }
            }
            if (existingIndex < 0) {
                merged.add(record);
            } else if (record.rks() > merged.get(existingIndex).rks()) {
                merged.set(existingIndex, record);
            }
        }
        return merged;
    }

    private String firstString(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.optString(key, "").trim();
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return "";
    }

    private double firstDouble(JSONObject json, String... keys) {
        for (String key : keys) {
            Object value = json.opt(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            if (value instanceof String) {
                String text = ((String) value).trim().replace("%", "");
                if (!text.isEmpty()) {
                    try {
                        return Double.parseDouble(text);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return 0;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return UiKit.dp(activity, value);
    }

    private static String exampleJson() {
        return "[\n"
                + "  {\"title\":\"Spasmodic\", \"level\":\"IN\", \"difficulty\":15.8, \"score\":995000},\n"
                + "  {\"title\":\"Igallta\", \"level\":\"IN\", \"difficulty\":15.7, \"accuracy\":99.4},\n"
                + "  {\"title\":\"Rrhar'il\", \"level\":\"AT\", \"difficulty\":16.7, \"accuracy\":98.2},\n"
                + "  {\"title\":\"Distorted Fate\", \"level\":\"IN\", \"difficulty\":16.4, \"score\":1000000},\n"
                + "  {\"title\":\"DESTRUCTION 3,2,1\", \"level\":\"IN\", \"difficulty\":15.9, \"accuracy\":99.1}\n"
                + "]";
    }

    private static String formatRks(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String formatDelta(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String formatDecimal(double value, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    private static final class Record {
        static final Comparator<Record> BY_RKS_DESC = (left, right) -> Double.compare(right.rks(), left.rks());

        final String title;
        final String level;
        final double difficulty;
        final double score;
        final double accuracy;

        Record(String title, String level, double difficulty, double score, double accuracy) {
            this.title = title;
            this.level = level;
            this.difficulty = difficulty;
            this.score = score;
            this.accuracy = accuracy;
        }

        double rks() {
            if (accuracy < 55.0) {
                return 0;
            }
            double normalized = (accuracy - 55.0) / 45.0;
            return difficulty * normalized * normalized;
        }

        boolean isPerfect() {
            return score >= PERFECT_SCORE - EPSILON || accuracy >= 100.0 - EPSILON;
        }

        Record withAccuracy(double targetAccuracy) {
            double nextAcc = Math.max(accuracy, Math.min(100, targetAccuracy));
            double nextScore = Math.max(score, Math.round(nextAcc * 10000.0));
            return new Record(title, level, difficulty, nextScore, nextAcc);
        }

        String identity() {
            return (title + "|" + level + "|" + formatDecimal(difficulty, 3)).toLowerCase(Locale.ROOT);
        }

        String shortName() {
            return title + (level.isEmpty() ? "" : " [" + level + "]");
        }

        String displayScore() {
            return "成绩 " + String.format(Locale.ROOT, "%.0f", score);
        }

        String displayAcc() {
            return "ACC " + formatDecimal(accuracy, 2) + "%";
        }

        String searchText() {
            return (title + " " + level + " " + difficulty).toLowerCase(Locale.ROOT);
        }
    }

    private static final class RksSnapshot {
        final List<Record> phiRecords;
        final List<Record> bestRecords;
        final double overallRks;

        RksSnapshot(List<Record> phiRecords, List<Record> bestRecords, double overallRks) {
            this.phiRecords = Collections.unmodifiableList(new ArrayList<>(phiRecords));
            this.bestRecords = Collections.unmodifiableList(new ArrayList<>(bestRecords));
            this.overallRks = overallRks;
        }

        static RksSnapshot empty() {
            return new RksSnapshot(Collections.emptyList(), Collections.emptyList(), 0);
        }
    }

    private static final class CloudSaveInfo {
        final String url;
        final String label;

        CloudSaveInfo(String url, String label) {
            this.url = url;
            this.label = label;
        }
    }

    private static final class LoginRequest {
        final String deviceId;
        final String deviceCode;
        final String loginUrl;
        final int expiresIn;
        final int interval;

        LoginRequest(String deviceId, String deviceCode, String loginUrl, int expiresIn, int interval) {
            this.deviceId = deviceId;
            this.deviceCode = deviceCode;
            this.loginUrl = loginUrl;
            this.expiresIn = expiresIn;
            this.interval = interval;
        }
    }

    private static final class SaveCursor {
        final byte[] data;
        int offset;

        SaveCursor(byte[] data, int offset) {
            this.data = data;
            this.offset = offset;
        }

        boolean hasRemaining() {
            return offset < data.length;
        }

        int readUnsignedByte() throws IOException {
            ensure(1);
            return data[offset++] & 0xFF;
        }

        int readVarShort() throws IOException {
            int first = readUnsignedByte();
            if (first < 128) {
                return first;
            }
            int second = readUnsignedByte();
            return (first & 0x7F) ^ (second << 7);
        }

        String readString(int end) throws IOException {
            int len = readVarShort();
            int stringLen = Math.max(0, len - end);
            ensure(len);
            String value = new String(data, offset, stringLen, StandardCharsets.UTF_8);
            offset += len;
            return value;
        }

        int readIntLE() throws IOException {
            ensure(4);
            int value = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            offset += 4;
            return value;
        }

        float readFloatLE() throws IOException {
            ensure(4);
            float value = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            offset += 4;
            return value;
        }

        void ensure(int length) throws IOException {
            if (offset + length > data.length) {
                throw new IOException("存档数据不完整。");
            }
        }
    }

    private static final class Advice {
        final Record record;
        final String targetLabel;
        final double deltaOverall;

        Advice(Record record, String targetLabel, double deltaOverall) {
            this.record = record;
            this.targetLabel = targetLabel;
            this.deltaOverall = deltaOverall;
        }
    }
}
