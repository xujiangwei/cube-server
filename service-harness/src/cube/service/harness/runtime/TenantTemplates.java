/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.JdbcRunStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Validated, immutable startup snapshot; templates never depend on worker availability. */
public final class TenantTemplates {
    private final List<String> definitions;
    public final String digest;

    private TenantTemplates(SortedMap<String, JSONObject> profiles) {
        List<String> values = new ArrayList<>();
        JSONArray content = new JSONArray();
        for (JSONObject profile : profiles.values()) {
            values.add(JdbcRunStore.canonical(profile));
            content.put(profile);
        }
        definitions = Collections.unmodifiableList(values);
        digest = RuntimeSupport.sha(RuntimeSupport.bytes(content));
    }

    public static TenantTemplates load(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(root)) throw new IOException("Missing tenant template directory: " + root);
            SortedMap<String, JSONObject> profiles = new TreeMap<>();
            Set<String> tasks = new HashSet<>();
            try (Stream<Path> entries = Files.list(root)) {
                Iterator<Path> files = entries.sorted().iterator();
                while (files.hasNext()) {
                    Path file = files.next();
                    if (!file.getFileName().toString().endsWith(".json")) continue;
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a template file: " + file);
                    JSONObject profile;
                    try (InputStream in = Files.newInputStream(file)) {
                        profile = StrictJson.object(RuntimeSupport.read(in, 1048576));
                        ProfileRegistry.validateDefinition(profile);
                    } catch (Exception e) { throw new IOException("Invalid tenant template: " + file, e); }
                    String key = key(profile.getString("kind"), profile.getString("id"), profile.getString("version"));
                    if (profiles.put(key, profile) != null) throw new IOException("Duplicate tenant template: " + key);
                    if (profile.getString("kind").equals("task")) {
                        if (!tasks.add(profile.getString("id"))) throw new IOException("Multiple versions of task template: " + profile.getString("id"));
                        if (profile.getJSONArray("skillReleases").length() != 0) throw new IOException("Tenant templates cannot reference skill releases: " + file);
                    }
                    if (profiles.size() > 256) throw new IOException("Too many tenant templates: " + root);
                }
            }
            if (tasks.isEmpty() || tasks.size() > 64) throw new IOException("Tenant templates require 1..64 tasks: " + root);
            for (JSONObject task : profiles.values()) {
                if (!task.getString("kind").equals("task")) continue;
                Set<String> available = new HashSet<>();
                for (String kind : Arrays.asList("model", "runtime")) {
                    JSONObject ref = task.getJSONObject(kind);
                    JSONObject profile = profiles.get(key(kind, ref.getString("id"), ref.getString("version")));
                    if (profile == null) throw new IOException("Missing " + kind + " reference in task template: " + task.getString("id"));
                    available.addAll(RuntimeSupport.strings(profile.getJSONArray("capabilities")));
                }
                if (!available.containsAll(RuntimeSupport.strings(task.getJSONArray("requiredCapabilities")))) {
                    throw new IOException("Capability mismatch in task template: " + task.getString("id"));
                }
            }
            return new TenantTemplates(profiles);
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Invalid tenant templates: " + root, e); }
    }

    public List<JSONObject> profiles() {
        List<JSONObject> out = new ArrayList<>();
        for (String definition : definitions) out.add(new JSONObject(definition));
        return out;
    }

    private static String key(String kind, String id, String version) { return kind + "/" + id + "@" + version; }
}
