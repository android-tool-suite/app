package com.androidtoolsuite.runtime.contract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Set;

/** Declarative data-only merge rules. Rules never execute plugin code or address filesystem paths. */
public final class DatasetMergePolicy {
    private DatasetMergePolicy() { }

    public static JSONObject validate(JSONObject policy, String validator) throws ContractException {
        if (policy == null) return null;
        try {
            JsonContract.requireOnlyKeys(policy, "format", "rule", "entries");
            String format = policy.getString("format");
            if (!Set.of("json", "zip").contains(format) || !format.equals(validator)) {
                throw new ContractException("Dataset merge policy must match its JSON or ZIP validator");
            }
            if ("json".equals(format)) {
                if (policy.has("entries")) throw new ContractException("JSON merge policy cannot contain archive entries");
                rule(JsonContract.requireObject(policy, "rule"), 0);
            } else {
                if (policy.has("rule")) throw new ContractException("ZIP merge policy uses entry rules");
                JSONArray entries = JsonContract.requireArray(policy, "entries");
                if (entries.length() > 32) throw new ContractException("Too many ZIP merge rules");
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = JsonContract.requireObject(entries, i, "mergePolicy.entries");
                    JsonContract.requireOnlyKeys(entry, "pattern", "rule");
                    String pattern = entry.getString("pattern");
                    if (!pattern.matches("[A-Za-z0-9_./*?\\-]{1,160}") || pattern.contains("..") || pattern.startsWith("/")) {
                        throw new ContractException("Invalid archive merge pattern");
                    }
                    rule(JsonContract.requireObject(entry, "rule"), 0);
                }
            }
            return new JSONObject(policy.toString());
        } catch (JSONException error) { throw new ContractException("Invalid Dataset merge policy", error); }
    }

    private static void rule(JSONObject value, int depth) throws JSONException, ContractException {
        if (depth > 12) throw new ContractException("Dataset merge rule nesting is too deep");
        JsonContract.requireOnlyKeys(value, "kind", "fields", "keys", "aliases", "items", "sortBy",
                "descending", "incomingFirst", "timestamp", "legacySets", "constants", "requireAllKeys");
        String kind = value.getString("kind");
        if (!Set.of("object", "map", "array", "set", "newer", "or", "current").contains(kind)) {
            throw new ContractException("Unknown Dataset merge rule kind");
        }
        JSONObject fields = value.has("fields") ? JsonContract.requireObject(value, "fields") : null;
        if (fields != null) {
            if (!"object".equals(kind) || fields.length() > 128) throw new ContractException("Invalid merge fields");
            for (String key : keys(fields)) rule(fields.getJSONObject(key), depth + 1);
        }
        if ("array".equals(kind)) {
            JSONArray keys = JsonContract.requireArray(value, "keys");
            if (keys.length() < 1 || keys.length() > 8) throw new ContractException("Invalid merge identity");
            for (int i = 0; i < keys.length(); i++) path(keys.getString(i));
        }
        if (value.has("items")) {
            if (!Set.of("array", "map").contains(kind)) throw new ContractException("Invalid merge item rule");
            rule(JsonContract.requireObject(value, "items"), depth + 1);
        }
        if ("newer".equals(kind)) path(value.getString("timestamp"));
        if (value.has("sortBy")) path(value.getString("sortBy"));
        for (String flag : Set.of("descending", "incomingFirst", "requireAllKeys")) {
            if (value.has(flag)) JsonContract.requireBoolean(value, flag);
        }
        if (value.has("constants")) JsonContract.requireObject(value, "constants");
        JSONObject aliases = value.has("aliases") ? JsonContract.requireObject(value, "aliases") : null;
        if (aliases != null) for (String key : keys(aliases)) {
            path(key);
            JSONObject mappings = aliases.getJSONObject(key);
            if (mappings.length() > 64) throw new ContractException("Too many merge aliases");
            for (String alias : keys(mappings)) {
                Object target = mappings.get(alias);
                if (alias.isBlank() || alias.length() > 128 || !(target instanceof String)
                        || ((String) target).isBlank() || ((String) target).length() > 128) throw new ContractException("Invalid merge alias");
            }
        }
        JSONObject legacy = value.has("legacySets") ? JsonContract.requireObject(value, "legacySets") : null;
        if (legacy != null) for (String key : keys(legacy)) {
            JSONObject mapping = legacy.getJSONObject(key);
            JsonContract.requireOnlyKeys(mapping, "source", "when");
            path(mapping.getString("source")); path(mapping.getString("when"));
        }
    }

    private static void path(String value) throws ContractException {
        if (!value.matches("[A-Za-z0-9_.|\\-]{1,160}")) throw new ContractException("Invalid merge field path");
    }

    private static java.util.List<String> keys(JSONObject value) {
        java.util.List<String> result = new java.util.ArrayList<>();
        java.util.Iterator<String> iterator = value.keys();
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }
}
