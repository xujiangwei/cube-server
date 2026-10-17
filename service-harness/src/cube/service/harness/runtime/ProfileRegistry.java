/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.store.CatalogStore;
import cube.service.harness.store.JdbcRunStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * Trusted identity -> immutable Task/Model/Runtime definitions -> atomic per-run pin.
 */
public final class ProfileRegistry {
    public static final Set<String> PLATFORM_PERMISSIONS = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
            "skill.list", "skill.activate", "resource.list", "resource.read", "resource.search")));
    private final CatalogStore catalog;

    public ProfileRegistry(CatalogStore catalog) {
        this.catalog = catalog;
    }

    public static final class Identity {
        public final String tenant, subject;
        public final Set<String> permissions;

        /**
         * Only a trusted identity provider/service may construct this; never map request fields to it.
         */
        public Identity(String tenant, String subject, Set<String> permissions) {
            this.tenant = RuntimeSupport.id(tenant);
            this.subject = RuntimeSupport.id(subject);
            this.permissions = Collections.unmodifiableSet(new TreeSet<>(permissions));
        }
    }

    public JSONObject publish(String tenant, JSONObject definition) {
        String kind = definition.getString("kind");
        if (!Arrays.asList("model", "runtime", "task").contains(kind))
            throw RuntimeSupport.error("UNKNOWN_PROFILE_KIND");
        RuntimeSupport.validate(kind + "-profile-v1", definition);
        if (definition.has("capabilities")) RuntimeSupport.strings(definition.getJSONArray("capabilities"));
        if (kind.equals("task")) {
            RuntimeSupport.strings(definition.getJSONArray("skillReleases"));
            RuntimeSupport.strings(definition.getJSONArray("permissions"));
            RuntimeSupport.strings(definition.getJSONArray("requiredCapabilities"));
            for (String refKind : Arrays.asList("model", "runtime")) {
                JSONObject ref = definition.getJSONObject(refKind);
                catalog.profile(tenant, refKind, ref.getString("id"), ref.getString("version"));
            }
            for (String digest : RuntimeSupport.strings(definition.getJSONArray("skillReleases")))
                catalog.release(tenant, digest);
        }
        return catalog.putProfile(tenant, RuntimeSupport.copy(definition));
    }

    public JSONObject createRun(Identity identity, String key, String taskId, JSONObject request) {
        cube.service.harness.protocol.Contract.parse("create-run", RuntimeSupport.bytes(request));
        if (!request.getString("taskProfileId").equals(taskId)) throw RuntimeSupport.error("PROFILE_REQUEST_MISMATCH");
        if (request.getJSONObject("input").getJSONArray("attachmentIds").length() != 0)
            throw RuntimeSupport.error("ATTACHMENTS_NOT_IMPLEMENTED");
        return catalog.createRun(identity.tenant, identity.subject, key, RuntimeSupport.id(taskId), request, c -> {
            String binding = catalog.active(c, identity.tenant, "task", taskId).getString("target_ref");
            String[] ref = binding.split("@", -1);
            JSONObject task = catalog.profile(c, identity.tenant, "task", ref[0], ref[1]);
            JSONObject mr = task.getJSONObject("model"), rr = task.getJSONObject("runtime");
            JSONObject model = catalog.profile(c, identity.tenant, "model", mr.getString("id"), mr.getString("version"));
            JSONObject runtime = catalog.profile(c, identity.tenant, "runtime", rr.getString("id"), rr.getString("version"));
            Set<String> available = RuntimeSupport.strings(model.getJSONArray("capabilities"));
            available.addAll(RuntimeSupport.strings(runtime.getJSONArray("capabilities")));
            if (!available.containsAll(RuntimeSupport.strings(task.getJSONArray("requiredCapabilities"))))
                throw RuntimeSupport.error("PROFILE_CAPABILITY_MISMATCH");
            Set<String> permissions = RuntimeSupport.strings(task.getJSONArray("permissions"));
            permissions.retainAll(identity.permissions);
            permissions.retainAll(PLATFORM_PERMISSIONS);
            JSONArray releases = new JSONArray();
            Set<String> skillNames = new HashSet<>();
            for (String digest : RuntimeSupport.strings(task.getJSONArray("skillReleases"))) {
                JSONObject row = catalog.release(c, identity.tenant, digest, true);
                if (!row.getString("state").equals("ready")) throw RuntimeSupport.error("RELEASE_NOT_READY");
                JSONObject manifest = new JSONObject(row.getString("manifest_json"));
                if (!skillNames.add(manifest.getString("name"))) throw RuntimeSupport.error("PROFILE_DUPLICATE_SKILL");
                if (!JdbcRunStore.canonical(manifest.getJSONObject("runtime")).equals(JdbcRunStore.canonical(rr)))
                    throw RuntimeSupport.error("PACKAGE_RUNTIME_MISMATCH");
                releases.put(new JSONObject().put("digest", digest).put("manifest", manifest));
                JSONObject provenance = new JSONObject(row.getString("provenance_json"));
                for (String dep : RuntimeSupport.strings(provenance.optJSONArray("dependencies") == null ? new JSONArray() : provenance.getJSONArray("dependencies")))
                    if (!RuntimeSupport.strings(task.getJSONArray("skillReleases")).contains(dep))
                        throw RuntimeSupport.error("DEPENDENCY_NOT_PINNED");
            }
            return new JSONObject().put("schemaVersion", 1).put("task", task).put("model", model).put("runtime", runtime)
                    .put("releases", releases).put("permissions", new JSONArray(permissions));
        });
    }

    public JSONObject pinned(Identity identity, String runId) {
        return catalog.pinned(identity.tenant, identity.subject, runId);
    }
}
