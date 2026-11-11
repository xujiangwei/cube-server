package cube.service.harness.execution;

import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.PackageRepository;
import cube.service.harness.runtime.ProfileRegistry;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.runtime.ResourceTools;
import cube.service.harness.store.ProviderStore;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/** Loads the trusted extension and reads only the run's pinned business resources. */
final class WorkflowGate implements AutoCloseable {
    private final CompletionGuard guard;

    WorkflowGate(Properties config) throws Exception {
        String name = config.getProperty("broker.completion.guard.class", "");
        if (name.isEmpty()) { guard = null; return; }
        Path path = Paths.get(config.getProperty("broker.completion.guard.config", ""));
        if (!path.isAbsolute()) throw new IllegalArgumentException("GUARD_ABSOLUTE_CONFIG_REQUIRED");
        Properties privateConfig = new Properties();
        try (java.io.Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { privateConfig.load(in); }
        Class<?> type = Class.forName(name);
        if (!CompletionGuard.class.isAssignableFrom(type)) throw new IllegalArgumentException("COMPLETION_GUARD_TYPE_INVALID");
        guard = (CompletionGuard) type.getConstructor(Properties.class).newInstance(privateConfig);
    }

    static boolean required(JSONObject manifest) {
        boolean workflow = hasFile(manifest, CompletionGuard.FILE), report = reportRequired(manifest);
        if (report && !workflow) throw new StoreException("REPORT_CONFIG_INVALID");
        return workflow || report;
    }

    static boolean reportRequired(JSONObject manifest) { return hasFile(manifest, CompletionGuard.REPORT_FILE); }

    private static boolean hasFile(JSONObject manifest, String path) {
        JSONArray files = manifest.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            if (path.equals(file.getString("path"))) {
                if (!"resource".equals(file.getString("role"))) throw new StoreException("WORKFLOW_CONFIG_INVALID");
                return true;
            }
        }
        return false;
    }

    private void available() {
        if (guard == null) throw new StoreException("WORKFLOW_GUARD_UNAVAILABLE");
    }

    private void available(JSONObject manifest) {
        available();
        if (reportRequired(manifest) && !guard.supportsReports()) throw new StoreException("REPORT_GUARD_UNAVAILABLE");
    }

    private CompletionGuard.Resources reader(java.util.function.Function<String, byte[]> read) {
        return new CompletionGuard.Resources() {
            public JSONObject json(String path) { return StrictJson.object(read.apply(path)); }
            public byte[] bytes(String path) { return read.apply(path); }
        };
    }

    void validate(PackageRepository.Validated checked) throws Exception {
        if (!required(checked.manifest)) return;
        available(checked.manifest);
        CompletionGuard.Resources resources = reader(path -> {
            resourceFile(checked.manifest, path);
            return checked.resource(path);
        });
        guard.validate(resources);
        if (reportRequired(checked.manifest)) guard.validateReport(resources);
    }

    void preflight(JSONObject manifest, PackageRepository repository) throws Exception {
        if (!required(manifest)) return;
        available(manifest);
        CompletionGuard.Resources resources = new CompletionGuard.Resources() {
            public JSONObject json(String path) throws Exception { return StrictJson.object(bytes(path)); }
            public byte[] bytes(String path) throws Exception { resourceFile(manifest, path); return repository.readVerified(manifest, path); }
        };
        guard.validate(resources);
        if (reportRequired(manifest)) guard.validateReport(resources);
    }

    private static void resourceFile(JSONObject manifest, String path) {
        if (!PackageRepository.file(manifest, path).getString("role").equals("resource")) throw new StoreException("RESOURCE_FORBIDDEN");
    }

    JSONObject check(ProfileRegistry.Identity identity, String run, JSONObject pin, JSONArray active,
                     ResourceTools resourceTools, ProviderStore metadata, JSONObject decision) throws Exception {
        Set<String> activated = new HashSet<>();
        for (int i = 0; i < active.length(); i++) activated.add(active.getJSONObject(i).getString("digest"));
        JSONArray checks = new JSONArray(), evidence = null, reports = new JSONArray();
        Set<String> reportSkills = new java.util.TreeSet<>();
        JSONArray pinned = pin.getJSONArray("releases");
        for (int i = 0; i < pinned.length(); i++) {
            JSONObject release = pinned.getJSONObject(i);
            if (activated.contains(release.getString("digest")) && reportRequired(release.getJSONObject("manifest")))
                reportSkills.add(release.getJSONObject("manifest").getString("name"));
        }
        boolean satisfied = true;
        int repairs = 2;
        JSONArray releases = pin.getJSONArray("releases");
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i), manifest = release.getJSONObject("manifest");
            if (!activated.contains(release.getString("digest")) || !required(manifest)) continue;
            available(manifest);
            if (evidence == null) evidence = metadata.completionEvidence(identity.tenant, identity.subject, run);
            String skill = manifest.getString("name");
            JSONArray scoped = new JSONArray();
            Set<String> sources = new HashSet<>();
            for (int j = 0; j < evidence.length(); j++) {
                JSONObject fact = evidence.getJSONObject(j), value = fact.getJSONObject("value");
                if (!skill.equals(value.optString("skill"))) continue;
                scoped.put(fact);
                if (value.has("sourceRef")) sources.add(value.getString("sourceRef"));
                JSONArray refs = value.optJSONArray("sourceRefs");
                if (refs != null) for (int k = 0; k < refs.length(); k++) sources.add(refs.getString(k));
            }
            for (String source : sources) metadata.authorize(identity.tenant, identity.subject, source);
            CompletionGuard.Resources resources = new CompletionGuard.Resources() {
                public JSONObject json(String path) throws Exception { return resourceTools.providerResource(identity, run, skill, path); }
                public byte[] bytes(String path) throws Exception { return resourceTools.providerResourceBytes(identity, run, skill, path); }
            };
            guard.validate(resources);
            boolean report = reportRequired(manifest);
            if (report) guard.validateReport(resources);
            JSONObject result = report && decision != null ? guard.checkFinal(resources, scoped, decision, skill, reportSkills) : guard.check(resources, scoped);
            if (result.has("report")) {
                JSONObject normalized = result.getJSONObject("report");
                normalized.put("skill", skill).put("release", release.getString("digest"))
                        .put("requirementsDigest", PackageRepository.file(manifest, CompletionGuard.REPORT_FILE).getString("sha256"));
                reports.put(normalized);
                result.remove("report");
            }
            if (result.getInt("maxRepairs") < 0 || result.getInt("maxRepairs") > 2
                    || RuntimeSupport.bytes(result).length > 32768) throw new StoreException("WORKFLOW_CHECK_INVALID");
            repairs = Math.min(repairs, result.getInt("maxRepairs"));
            satisfied &= result.getBoolean("satisfied");
            checks.put(result.put("skill", skill).put("release", release.getString("digest"))
                    .put("requirementsDigest", PackageRepository.file(manifest, CompletionGuard.FILE).getString("sha256")));
        }
        JSONObject result = new JSONObject().put("required", checks.length() > 0).put("satisfied", satisfied)
                .put("maxRepairs", repairs).put("checks", checks);
        if (RuntimeSupport.bytes(result).length > 65536) throw new StoreException("WORKFLOW_CHECK_INVALID");
        if (decision != null && !reportSkills.isEmpty()) {
            result.put("reportRequired", true);
            if (satisfied) {
                if (reports.length() != reportSkills.size()) throw new StoreException("REPORT_CHECK_INVALID");
                JSONObject delivery = new JSONObject().put("schemaVersion", 1).put("format", "evidence-report-v1").put("reports", reports);
                if (RuntimeSupport.bytes(delivery).length > 262144) throw new StoreException("REPORT_RESULT_LIMIT");
                result.put("report", delivery);
            }
        }
        return result;
    }

    public void close() { if (guard != null) guard.close(); }
}
