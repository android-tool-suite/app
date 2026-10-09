package com.androidtoolsuite.app.plugin.runtime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Applies manifest rules within one Dataset. Activation and rollback remain owned by DatasetService. */
final class DeclarativeDatasetMerger {
    private DeclarativeDatasetMerger() { }

    static void merge(File current, File incoming, JSONObject policy, int maxBytes, OutputStream output) throws IOException {
        try {
            if ("json".equals(policy.getString("format"))) {
                Object local = current == null ? null : parse(read(current, maxBytes));
                Object imported = parse(read(incoming, maxBytes));
                Object value = value(local, imported, policy.getJSONObject("rule"), 0);
                byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > maxBytes) throw new IOException("合并后数据超出该项容量限制");
                output.write(bytes);
            } else {
                mergeZip(current, incoming, policy, maxBytes, output);
            }
        } catch (JSONException | IllegalArgumentException error) {
            // JSON parser messages may contain credentials from a sensitive payload.
            throw new IOException("数据结构不符合此项合并规则，原有数据已保留");
        }
    }

    static Object value(Object current, Object incoming, JSONObject rule, int depth) throws JSONException, IOException {
        if (depth > 64) throw new IOException("数据结构层级过深");
        String kind = rule == null ? "default" : rule.getString("kind");
        if ("current".equals(kind)) return copy(present(current) ? current : incoming);
        if ("or".equals(kind)) {
            if (present(current) && !(current instanceof Boolean) || present(incoming) && !(incoming instanceof Boolean)) {
                throw new IOException("合并字段类型不匹配");
            }
            return Boolean.TRUE.equals(current) || Boolean.TRUE.equals(incoming);
        }
        if ("newer".equals(kind)) {
            long localTime = timestamp(field(current, rule.getString("timestamp")));
            long importTime = timestamp(field(incoming, rule.getString("timestamp")));
            return copy(!present(current) || importTime > localTime ? incoming : current);
        }
        if ("array".equals(kind)) return array(current, incoming, rule, depth);
        if ("set".equals(kind)) {
            JSONArray local = asArray(current), imported = asArray(incoming), result = new JSONArray();
            Map<String, Object> unique = new LinkedHashMap<>();
            for (JSONArray source : List.of(local, imported)) for (int i = 0; i < source.length(); i++) {
                Object item = source.get(i); unique.putIfAbsent(canonical(item), item);
            }
            for (Object item : unique.values()) result.put(copy(item));
            return result;
        }
        if ("object".equals(kind) || "map".equals(kind) || current instanceof JSONObject && incoming instanceof JSONObject) {
            JSONObject local = asObject(current), imported = asObject(incoming);
            if (rule != null) { local = legacy(local, rule); imported = legacy(imported, rule); }
            JSONObject fields = rule == null ? null : rule.optJSONObject("fields"), result = new JSONObject();
            TreeSet<String> keys = new TreeSet<>();
            keys.addAll(jsonKeys(local)); keys.addAll(jsonKeys(imported));
            for (String key : keys) result.put(key, value(local.opt(key), imported.opt(key),
                    "map".equals(kind) ? rule.optJSONObject("items") : fields == null ? null : fields.optJSONObject(key), depth + 1));
            JSONObject constants = rule == null ? null : rule.optJSONObject("constants");
            if (constants != null) for (String key : jsonKeys(constants)) result.put(key, copy(constants.get(key)));
            return result;
        }
        return copy(present(current) ? current : incoming);
    }

    private static JSONObject legacy(JSONObject source, JSONObject rule) throws JSONException {
        JSONObject result = new JSONObject(source.toString()), mappings = rule.optJSONObject("legacySets");
        if (mappings != null) for (String key : jsonKeys(mappings)) {
            if (result.has(key)) continue;
            JSONObject mapping = mappings.getJSONObject(key);
            Object fallback = Boolean.TRUE.equals(field(source, mapping.getString("when")))
                    ? field(source, mapping.getString("source")) : null;
            result.put(key, fallback instanceof JSONArray ? copy(fallback) : new JSONArray());
        }
        return result;
    }

    private static JSONArray array(Object current, Object incoming, JSONObject rule, int depth) throws JSONException, IOException {
        Map<String, Object> local = index(asArray(current), rule), imported = index(asArray(incoming), rule);
        LinkedHashSet<String> ids = new LinkedHashSet<>(rule.optBoolean("incomingFirst") ? imported.keySet() : local.keySet());
        ids.addAll(rule.optBoolean("incomingFirst") ? local.keySet() : imported.keySet());
        List<Object> values = new ArrayList<>();
        for (String id : ids) values.add(value(local.get(id), imported.get(id), rule.optJSONObject("items"), depth + 1));
        String order = rule.optString("sortBy", "");
        if (!order.isEmpty()) values.sort((left, right) -> {
            int comparison = compare(field(left, order), field(right, order));
            return rule.optBoolean("descending") ? -comparison : comparison;
        });
        JSONArray result = new JSONArray();
        for (Object item : values) result.put(item);
        return result;
    }

    private static Map<String, Object> index(JSONArray array, JSONObject rule) throws JSONException, IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        JSONArray paths = rule.getJSONArray("keys");
        JSONObject aliases = rule.optJSONObject("aliases");
        for (int i = 0; i < array.length(); i++) {
            Object item = array.get(i); JSONArray identity = new JSONArray(); boolean any = false;
            for (int k = 0; k < paths.length(); k++) {
                String path = paths.getString(k); Object key = field(item, path);
                if (!present(key) && rule.optBoolean("requireAllKeys")) throw new IOException("合并记录缺少必要的标识字段");
                if (present(key)) {
                    any = true;
                    JSONObject mappings = aliases == null ? null : aliases.optJSONObject(path);
                    if (mappings != null) key = mappings.optString(String.valueOf(key), String.valueOf(key));
                    else if (key instanceof Number) key = key.toString();
                }
                identity.put(present(key) ? key : JSONObject.NULL);
            }
            if (!any) throw new IOException("合并记录缺少稳定标识，原有数据已保留");
            String key = canonical(identity);
            if (!result.containsKey(key)) result.put(key, item);
            else result.put(key, value(result.get(key), item, rule.optJSONObject("items"), 0));
        }
        return result;
    }

    private static Object field(Object source, String path) {
        for (String alternative : path.split("\\|")) {
            Object value = source;
            for (String part : alternative.split("\\.")) {
                value = value instanceof JSONObject ? ((JSONObject) value).opt(part) : null;
            }
            if (present(value)) return value;
        }
        return null;
    }

    private static long timestamp(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (!present(value)) return Long.MIN_VALUE;
        String text = String.valueOf(value);
        try { return Long.parseLong(text); } catch (NumberFormatException ignored) { }
        try { return Instant.parse(text).toEpochMilli(); } catch (RuntimeException ignored) { }
        try { return OffsetDateTime.parse(text).toInstant().toEpochMilli(); } catch (RuntimeException ignored) { }
        return Long.MIN_VALUE;
    }

    private static int compare(Object left, Object right) {
        if (!present(left)) return present(right) ? -1 : 0;
        if (!present(right)) return 1;
        String a = left.toString(), b = right.toString();
        if (a.matches("-?\\d+") && b.matches("-?\\d+")) return new BigInteger(a).compareTo(new BigInteger(b));
        return a.compareTo(b);
    }

    private static boolean present(Object value) { return value != null && value != JSONObject.NULL; }
    private static JSONObject asObject(Object value) throws IOException {
        if (!present(value)) return new JSONObject();
        if (value instanceof JSONObject) return (JSONObject) value;
        throw new IOException("合并需要对象格式的数据");
    }
    private static JSONArray asArray(Object value) throws IOException {
        if (!present(value)) return new JSONArray();
        if (value instanceof JSONArray) return (JSONArray) value;
        throw new IOException("合并需要列表格式的数据");
    }
    private static Object copy(Object value) throws JSONException {
        if (!present(value)) return JSONObject.NULL;
        if (value instanceof JSONObject) return new JSONObject(value.toString());
        if (value instanceof JSONArray) return new JSONArray(value.toString());
        return value;
    }
    private static Object parse(byte[] bytes) throws JSONException {
        JSONTokener parser = new JSONTokener(new String(bytes, StandardCharsets.UTF_8));
        Object value = parser.nextValue();
        if (parser.nextClean() != 0) throw new JSONException("Trailing data");
        return value;
    }
    private static String canonical(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            StringBuilder result = new StringBuilder("{");
            for (String key : new TreeSet<>(jsonKeys((JSONObject) value))) {
                if (result.length() > 1) result.append(',');
                result.append(JSONObject.quote(key)).append(':').append(canonical(((JSONObject) value).get(key)));
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < ((JSONArray) value).length(); i++) {
                if (i > 0) result.append(',');
                result.append(canonical(((JSONArray) value).get(i)));
            }
            return result.append(']').toString();
        }
        return !present(value) ? "null" : value instanceof String ? JSONObject.quote((String) value) : value.toString();
    }

    private static byte[] read(File file, int max) throws IOException {
        try (InputStream input = new FileInputStream(file)) { return read(input, max); }
    }

    private static List<String> jsonKeys(JSONObject value) {
        List<String> result = new ArrayList<>();
        java.util.Iterator<String> iterator = value.keys();
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }
    private static byte[] read(InputStream input, int max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transfer(input, output, max);
        return output.toByteArray();
    }
    private static long transfer(InputStream input, OutputStream output, long max) throws IOException {
        byte[] buffer = new byte[64 * 1024]; long size = 0; int length;
        while ((length = input.read(buffer)) != -1) {
            size += length;
            if (size > max) throw new IOException("合并数据超出该项容量限制");
            output.write(buffer, 0, length);
        }
        return size;
    }

    private static Map<String, ZipEntry> entries(ZipFile file) throws IOException {
        Map<String, ZipEntry> entries = new LinkedHashMap<>();
        if (file == null) return entries;
        Enumeration<? extends ZipEntry> values = file.entries();
        while (values.hasMoreElements()) {
            ZipEntry entry = values.nextElement(); String name = entry.getName();
            if (name.startsWith("/") || name.contains("\\") || name.contains(":") ||
                    java.util.Arrays.asList(name.split("/")).contains("..") || name.length() > 256) {
                throw new IOException("归档条目名称无效");
            }
            if (entry.isDirectory()) continue;
            if (entries.putIfAbsent(name, entry) != null || entries.size() > 16384) throw new IOException("归档存在重复或过多条目");
        }
        return entries;
    }

    private static JSONObject entryRule(JSONObject policy, String name) throws JSONException {
        JSONArray rules = policy.getJSONArray("entries");
        for (int i = 0; i < rules.length(); i++) {
            JSONObject entry = rules.getJSONObject(i);
            StringBuilder expression = new StringBuilder("^");
            for (char ch : entry.getString("pattern").toCharArray()) {
                expression.append(ch == '*' ? ".*" : ch == '?' ? "." : Pattern.quote(String.valueOf(ch)));
            }
            if (name.matches(expression.append('$').toString())) return entry.getJSONObject("rule");
        }
        return null;
    }

    private static void mergeZip(File current, File incoming, JSONObject policy, int max, OutputStream output) throws IOException, JSONException {
        try (ZipFile local = current == null ? null : new ZipFile(current);
             ZipFile imported = new ZipFile(incoming);
             ZipOutputStream merged = new ZipOutputStream(output)) {
            Map<String, ZipEntry> a = entries(local), b = entries(imported);
            TreeSet<String> names = new TreeSet<>(a.keySet()); names.addAll(b.keySet());
            long expanded = 0;
            for (String name : names) {
                ZipEntry entry = new ZipEntry(name); entry.setTime(0); merged.putNextEntry(entry);
                JSONObject rule = entryRule(policy, name);
                if (rule != null && a.containsKey(name) && b.containsKey(name)) {
                    Object first, second;
                    try (InputStream stream = local.getInputStream(a.get(name))) { first = parse(read(stream, max)); }
                    try (InputStream stream = imported.getInputStream(b.get(name))) { second = parse(read(stream, max)); }
                    byte[] data = value(first, second, rule, 0).toString().getBytes(StandardCharsets.UTF_8);
                    expanded += data.length;
                    if (expanded > max) throw new IOException("合并归档展开后超出容量限制");
                    merged.write(data);
                } else {
                    ZipFile source = a.containsKey(name) ? local : imported;
                    ZipEntry selected = a.containsKey(name) ? a.get(name) : b.get(name);
                    try (InputStream stream = source.getInputStream(selected)) {
                        expanded += transfer(stream, merged, max - expanded);
                    }
                }
                merged.closeEntry();
            }
        }
    }
}
