package com.androidtoolsuite.app.host;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Folder membership is independent of widget configuration, plugin state and visibility. */
public final class HostFolders {
    private HostFolders() { }
    public static final String HOME = "home";
    public static final String TOOLS = "tools";
    public static final String PREFIX = "@folder:";

    public static final class Folder {
        public final String id;
        public final String name;
        public final String scope;
        public final List<String> members;

        public Folder(String id, String name, String scope, List<String> members) {
            this.id = text(id, 128);
            this.name = text(name, 80);
            if (!HOME.equals(scope) && !TOOLS.equals(scope)) throw new IllegalArgumentException("Unknown folder scope");
            this.scope = scope;
            LinkedHashSet<String> checked = new LinkedHashSet<>();
            for (String member : members) {
                String key = text(member, 256);
                if (key.startsWith(PREFIX)) throw new IllegalArgumentException("Folders cannot be nested");
                checked.add(key);
            }
            if (checked.size() > 128) throw new IllegalArgumentException("Invalid folder member count");
            this.members = Collections.unmodifiableList(new ArrayList<>(checked));
        }

        public String key() { return PREFIX + id; }
    }

    private static String text(String value, int max) {
        if (value == null) throw new IllegalArgumentException("Missing folder value");
        String result = value.trim();
        if (result.isEmpty() || result.length() > max || result.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid folder value");
        }
        return result;
    }

    public static List<Folder> decode(JSONArray array) throws JSONException {
        if (array == null) return Collections.emptyList();
        if (array.length() > 64) throw new JSONException("Too many folders");
        List<Folder> result = new ArrayList<>();
        Set<String> ids = new HashSet<>(), memberships = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            JSONArray values = item.getJSONArray("members");
            List<String> members = new ArrayList<>();
            for (int position = 0; position < values.length(); position++) members.add(values.getString(position));
            Folder folder;
            try { folder = new Folder(item.getString("id"), item.getString("name"), item.getString("scope"), members); }
            catch (IllegalArgumentException error) { throw new JSONException("Invalid folder structure"); }
            if (!ids.add(folder.id)) throw new JSONException("Duplicate folder");
            for (String member : folder.members) {
                if (!memberships.add(folder.scope + "\n" + member)) throw new JSONException("Duplicate folder membership");
            }
            result.add(folder);
        }
        return Collections.unmodifiableList(result);
    }

    public static JSONArray encode(List<Folder> folders) {
        JSONArray result = new JSONArray();
        for (Folder folder : folders) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", folder.id).put("name", folder.name).put("scope", folder.scope)
                        .put("members", new JSONArray(folder.members));
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            result.put(item);
        }
        return result;
    }

    public static Folder find(List<Folder> folders, String id) {
        for (Folder folder : folders) if (folder.id.equals(id)) return folder;
        return null;
    }

    public static Folder containing(List<Folder> folders, String scope, String member) {
        for (Folder folder : folders) if (folder.scope.equals(scope) && folder.members.contains(member)) return folder;
        return null;
    }

    public static List<Folder> save(List<Folder> folders, String id, String name, String scope, List<String> members) {
        String targetId = id == null ? UUID.randomUUID().toString() : id;
        Folder target = new Folder(targetId, name, scope, members);
        Folder existing = find(folders, targetId);
        if (existing != null && !existing.scope.equals(scope)) throw new IllegalArgumentException("Folder scope cannot change");
        List<Folder> result = new ArrayList<>();
        for (Folder folder : folders) {
            if (folder.id.equals(targetId)) continue;
            List<String> remaining = new ArrayList<>(folder.members);
            if (folder.scope.equals(scope)) remaining.removeAll(target.members);
            result.add(new Folder(folder.id, folder.name, folder.scope, remaining));
        }
        result.add(target);
        if (result.size() > 64) throw new IllegalArgumentException("Too many folders");
        return result;
    }

    public static List<Folder> move(List<Folder> folders, String scope, String member, String targetId) {
        Folder target = targetId == null ? null : find(folders, targetId);
        if (targetId != null && (target == null || !target.scope.equals(scope))) {
            throw new IllegalArgumentException("Folder is unavailable");
        }
        List<Folder> result = new ArrayList<>();
        for (Folder folder : folders) {
            if (!folder.scope.equals(scope)) { result.add(folder); continue; }
            List<String> members = new ArrayList<>(folder.members);
            members.remove(member);
            if (folder.id.equals(targetId)) members.add(member);
            result.add(new Folder(folder.id, folder.name, folder.scope, members));
        }
        return result;
    }

    public static List<Folder> dissolve(List<Folder> folders, String id) {
        List<Folder> result = new ArrayList<>(folders);
        result.removeIf(folder -> folder.id.equals(id));
        return result;
    }

    /** Current membership wins; incoming folders supplement unassigned members. */
    public static List<Folder> merge(List<Folder> current, List<Folder> incoming) {
        List<Folder> result = new ArrayList<>(current);
        for (Folder folder : incoming) {
            Folder matching = find(result, folder.id);
            if (matching != null && !matching.scope.equals(folder.scope)) continue;
            List<String> members = new ArrayList<>(matching == null ? Collections.emptyList() : matching.members);
            for (String member : folder.members) {
                if (containing(result, folder.scope, member) == null && !members.contains(member)) members.add(member);
            }
            result.removeIf(item -> item.id.equals(folder.id));
            result.add(new Folder(folder.id, matching == null ? folder.name : matching.name, folder.scope, members));
        }
        return result;
    }
}
