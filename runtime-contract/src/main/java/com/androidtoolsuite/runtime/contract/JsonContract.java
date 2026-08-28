package com.androidtoolsuite.runtime.contract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

final class JsonContract {
    private JsonContract() {
    }

    static JSONObject requireObject(JSONObject parent, String name) throws ContractException {
        JSONObject value = parent.optJSONObject(name);
        if (value == null) {
            throw new ContractException("缺少对象字段 " + name);
        }
        return value;
    }

    static JSONObject requireObject(JSONArray parent, int index, String name) throws ContractException {
        JSONObject value = parent.optJSONObject(index);
        if (value == null) {
            throw new ContractException(name + "[" + index + "] 必须是对象");
        }
        return value;
    }

    static JSONArray requireArray(JSONObject parent, String name) throws ContractException {
        JSONArray value = parent.optJSONArray(name);
        if (value == null) {
            throw new ContractException("缺少数组字段 " + name);
        }
        return value;
    }

    static int requirePositiveInt(JSONObject parent, String name) throws ContractException {
        if (!parent.has(name) || parent.isNull(name)) {
            throw new ContractException("缺少整数字段 " + name);
        }
        Object raw = parent.opt(name);
        if (!(raw instanceof Number)) {
            throw new ContractException(name + " 必须是正整数");
        }
        Number number = (Number) raw;
        long value = number.longValue();
        if (value < 1 || value > Integer.MAX_VALUE || number.doubleValue() != (double) value) {
            throw new ContractException(name + " 必须是正整数");
        }
        return (int) value;
    }

    static boolean requireBoolean(JSONObject parent, String name) throws ContractException, JSONException {
        if (!parent.has(name) || parent.isNull(name)) {
            throw new ContractException("缺少布尔字段 " + name);
        }
        Object value = parent.get(name);
        if (!(value instanceof Boolean)) {
            throw new ContractException(name + " 必须是布尔值");
        }
        return (Boolean) value;
    }

    static List<String> stringList(
            JSONArray array,
            String name,
            int minItems,
            int maxItems,
            int maxLength
    ) throws ContractException {
        if (array.length() < minItems || array.length() > maxItems) {
            throw new ContractException(name + " 数量超出范围");
        }
        List<String> values = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            Object item = array.opt(index);
            if (!(item instanceof String)) {
                throw new ContractException(name + " 必须只包含字符串");
            }
            String value = ((String) item).trim();
            if (value.isEmpty() || value.length() > maxLength) {
                throw new ContractException(name + " 包含空值或超长值");
            }
            if (!unique.add(value)) {
                throw new ContractException(name + " 包含重复值：" + value);
            }
            values.add(value);
        }
        return values;
    }

    static void requireOnlyKeys(JSONObject value, String... allowed) throws ContractException {
        Set<String> names = Set.of(allowed);
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!names.contains(key)) {
                throw new ContractException("未知字段：" + key);
            }
        }
    }

    static void requireDepth(Object value, int maxDepth) throws ContractException {
        requireDepth(value, 1, maxDepth);
    }

    private static void requireDepth(Object value, int depth, int maxDepth) throws ContractException {
        if (depth > maxDepth) {
            throw new ContractException("JSON 嵌套层级超出限制");
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                requireDepth(object.opt(keys.next()), depth + 1, maxDepth);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                requireDepth(array.opt(index), depth + 1, maxDepth);
            }
        }
    }
}
