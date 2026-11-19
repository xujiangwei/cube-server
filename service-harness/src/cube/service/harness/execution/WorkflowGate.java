/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.PackageRepository;
import cube.service.harness.runtime.ProfileRegistry;
import cube.service.harness.runtime.ResourceTools;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.ProviderStore;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 加载受信扩展，并只读取当前 run 已固定的业务资源。
 */
final class WorkflowGate implements AutoCloseable {
    /**
     * 已加载的业务完成度校验扩展；未配置时为 null。
     */
    private final CompletionGuard guard;

    /**
     * 从部署私有配置加载校验扩展。
     *
     * @param config 部署私有配置，读取 guard 类名与其配置文件绝对路径。
     * @throws Exception 配置非法、类缺失或实例化失败时抛出。
     */
    WorkflowGate(Properties config) throws Exception {
        String name = config.getProperty("broker.completion.guard.class", "");
        if (name.isEmpty()) {
            guard = null;
            return;
        }
        Path path = Paths.get(config.getProperty("broker.completion.guard.config", ""));
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("GUARD_ABSOLUTE_CONFIG_REQUIRED");
        }
        Properties privateConfig = new Properties();
        try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            privateConfig.load(in);
        }
        Class<?> type = Class.forName(name);
        if (!CompletionGuard.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("COMPLETION_GUARD_TYPE_INVALID");
        }
        guard = (CompletionGuard) type.getConstructor(Properties.class).newInstance(privateConfig);
    }

    /**
     * 判断发布清单是否声明了工作流或报告校验需求。
     *
     * @param manifest 发布清单。
     * @return 需要任一校验时返回 true。
     */
    static boolean required(JSONObject manifest) {
        boolean workflow = hasFile(manifest, CompletionGuard.FILE);
        boolean report = reportRequired(manifest);
        if (report && !workflow) {
            throw new StoreException("REPORT_CONFIG_INVALID");
        }
        return workflow || report;
    }

    /**
     * 判断发布清单是否声明了报告校验需求。
     *
     * @param manifest 发布清单。
     * @return 声明报告需求时返回 true。
     */
    static boolean reportRequired(JSONObject manifest) {
        return hasFile(manifest, CompletionGuard.REPORT_FILE);
    }

    /**
     * 判断清单文件表中是否存在指定路径的资源条目。
     *
     * @param manifest 发布清单。
     * @param path     资源相对路径。
     * @return 存在且角色为 resource 时返回 true。
     */
    private static boolean hasFile(JSONObject manifest, String path) {
        JSONArray files = manifest.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            if (path.equals(file.getString("path"))) {
                if (!"resource".equals(file.getString("role"))) {
                    throw new StoreException("WORKFLOW_CONFIG_INVALID");
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 确保校验扩展已加载。
     */
    private void available() {
        if (guard == null) {
            throw new StoreException("WORKFLOW_GUARD_UNAVAILABLE");
        }
    }

    /**
     * 确保校验扩展已加载，且声明报告需求时支持报告校验。
     *
     * @param manifest 发布清单。
     */
    private void available(JSONObject manifest) {
        available();
        if (reportRequired(manifest) && !guard.supportsReports()) {
            throw new StoreException("REPORT_GUARD_UNAVAILABLE");
        }
    }

    /**
     * 把字节读取函数适配为校验扩展的资源读取入口。
     *
     * @param read 资源路径到字节的读取函数。
     * @return 资源读取入口。
     */
    private CompletionGuard.Resources reader(Function<String, byte[]> read) {
        return new CompletionGuard.Resources() {
            public JSONObject json(String path) {
                return StrictJson.object(read.apply(path));
            }

            public byte[] bytes(String path) {
                return read.apply(path);
            }
        };
    }

    /**
     * 校验已验签业务包内的全部需求配置。
     *
     * @param checked 已验签的业务包。
     * @throws Exception 配置非法或读取失败时抛出。
     */
    void validate(PackageRepository.Validated checked) throws Exception {
        if (!required(checked.manifest)) {
            return;
        }
        available(checked.manifest);
        CompletionGuard.Resources resources = reader(path -> {
            resourceFile(checked.manifest, path);
            return checked.resource(path);
        });
        guard.validate(resources);
        if (reportRequired(checked.manifest)) {
            guard.validateReport(resources);
        }
    }

    /**
     * 预检发布包的需求配置，不触发网络或数据库递归。
     *
     * @param manifest   发布清单。
     * @param repository 包仓库，提供已验签资源读取。
     * @throws Exception 配置非法或读取失败时抛出。
     */
    void preflight(JSONObject manifest, PackageRepository repository) throws Exception {
        if (!required(manifest)) {
            return;
        }
        available(manifest);
        CompletionGuard.Resources resources = new CompletionGuard.Resources() {
            public JSONObject json(String path) throws Exception {
                return StrictJson.object(bytes(path));
            }

            public byte[] bytes(String path) throws Exception {
                resourceFile(manifest, path);
                return repository.readVerified(manifest, path);
            }
        };
        guard.validate(resources);
        if (reportRequired(manifest)) {
            guard.validateReport(resources);
        }
    }

    /**
     * 确保清单中的指定路径是资源角色，否则拒绝。
     *
     * @param manifest 发布清单。
     * @param path     资源相对路径。
     */
    private static void resourceFile(JSONObject manifest, String path) {
        if (!PackageRepository.file(manifest, path).getString("role").equals("resource")) {
            throw new StoreException("RESOURCE_FORBIDDEN");
        }
    }

    /**
     * 校验 run 的业务完成度，汇总各激活发布的工作流/报告结论。
     *
     * @param identity      调用方身份。
     * @param run           run 标识。
     * @param pin           已固定的发布配置。
     * @param active        已激活的发布列表。
     * @param resourceTools 受控资源读取工具。
     * @param metadata      提供方元数据存储。
     * @param decision      模型决策，可为 null。
     * @return 汇总后的业务检查结果。
     * @throws Exception 校验失败、证据越权或结果超限时抛出。
     */
    JSONObject check(ProfileRegistry.Identity identity, String run, JSONObject pin, JSONArray active,
                     ResourceTools resourceTools, ProviderStore metadata, JSONObject decision) throws Exception {
        final JSONObject taskInput = metadata.taskInput(identity.tenant, identity.subject, run);
        Set<String> activated = new HashSet<>();
        for (int i = 0; i < active.length(); i++) {
            activated.add(active.getJSONObject(i).getString("digest"));
        }
        JSONArray checks = new JSONArray();
        JSONArray evidence = null;
        JSONArray reports = new JSONArray();
        JSONArray retrievals = new JSONArray();
        Set<String> reportSkills = new TreeSet<>();
        JSONArray pinned = pin.getJSONArray("releases");
        for (int i = 0; i < pinned.length(); i++) {
            JSONObject release = pinned.getJSONObject(i);
            if (activated.contains(release.getString("digest")) && reportRequired(release.getJSONObject("manifest"))) {
                reportSkills.add(release.getJSONObject("manifest").getString("name"));
            }
        }
        boolean satisfied = true;
        int repairs = 2;
        JSONArray releases = pin.getJSONArray("releases");
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            JSONObject manifest = release.getJSONObject("manifest");
            if (!activated.contains(release.getString("digest")) || !required(manifest)) {
                continue;
            }
            available(manifest);
            if (evidence == null) {
                evidence = metadata.completionEvidence(identity.tenant, identity.subject, run);
            }
            String skill = manifest.getString("name");
            JSONArray scoped = new JSONArray();
            Set<String> sources = new HashSet<>();
            for (int j = 0; j < evidence.length(); j++) {
                JSONObject fact = evidence.getJSONObject(j);
                JSONObject value = fact.getJSONObject("value");
                if (!skill.equals(value.optString("skill"))) {
                    continue;
                }
                if (guard.supportsInteraction() && fact.optLong("inputRevision", 0) != taskInput.getLong("inputRevision")) {
                    continue;
                }
                scoped.put(fact);
                if (value.has("sourceRef")) {
                    sources.add(value.getString("sourceRef"));
                }
                JSONArray refs = value.optJSONArray("sourceRefs");
                if (refs != null) {
                    for (int k = 0; k < refs.length(); k++) {
                        sources.add(refs.getString(k));
                    }
                }
            }
            for (String source : sources) {
                metadata.authorize(identity.tenant, identity.subject, source);
            }
            CompletionGuard.Resources resources = new CompletionGuard.Resources() {
                public JSONObject taskInput() {
                    return new JSONObject(taskInput.toString());
                }

                public JSONObject json(String path) throws Exception {
                    return resourceTools.providerResource(identity, run, skill, path);
                }

                public byte[] bytes(String path) throws Exception {
                    return resourceTools.providerResourceBytes(identity, run, skill, path);
                }
            };
            guard.validate(resources);
            boolean report = reportRequired(manifest);
            if (report) {
                guard.validateReport(resources);
            }
            JSONObject result = report && decision != null
                    ? guard.checkFinal(resources, scoped, decision, skill, reportSkills)
                    : guard.check(resources, scoped);
            if (result.has("retrieval")) {
                JSONObject retrieval = result.getJSONObject("retrieval");
                retrieval.put("release", release.getString("digest"));
                retrievals.put(retrieval);
                result.remove("retrieval");
            }
            if (result.has("report")) {
                JSONObject normalized = result.getJSONObject("report");
                normalized.put("skill", skill)
                        .put("release", release.getString("digest"))
                        .put("requirementsDigest", PackageRepository.file(manifest, CompletionGuard.REPORT_FILE).getString("sha256"));
                reports.put(normalized);
                result.remove("report");
            }
            if (result.getInt("maxRepairs") < 0 || result.getInt("maxRepairs") > 2
                    || RuntimeSupport.bytes(result).length > 32768) {
                throw new StoreException("WORKFLOW_CHECK_INVALID");
            }
            repairs = Math.min(repairs, result.getInt("maxRepairs"));
            satisfied &= result.getBoolean("satisfied");
            checks.put(result.put("skill", skill)
                    .put("release", release.getString("digest"))
                    .put("requirementsDigest", PackageRepository.file(manifest, CompletionGuard.FILE).getString("sha256")));
        }
        JSONObject result = new JSONObject()
                .put("required", checks.length() > 0)
                .put("satisfied", satisfied)
                .put("maxRepairs", repairs)
                .put("checks", checks);
        if (RuntimeSupport.bytes(result).length > 65536) {
            throw new StoreException("WORKFLOW_CHECK_INVALID");
        }
        if (satisfied && retrievals.length() > 0) {
            JSONObject delivery = new JSONObject()
                    .put("schemaVersion", 1)
                    .put("format", "resource-retrieval-v1")
                    .put("results", retrievals);
            if (RuntimeSupport.bytes(delivery).length > 262144) {
                throw new StoreException("RETRIEVAL_RESULT_LIMIT");
            }
            result.put("retrieval", delivery);
        }
        if (decision != null && !reportSkills.isEmpty()) {
            result.put("reportRequired", true);
            if (satisfied) {
                if (reports.length() != reportSkills.size()) {
                    throw new StoreException("REPORT_CHECK_INVALID");
                }
                JSONObject delivery = new JSONObject()
                        .put("schemaVersion", 1)
                        .put("format", "evidence-report-v1")
                        .put("reports", reports);
                if (RuntimeSupport.bytes(delivery).length > 262144) {
                    throw new StoreException("REPORT_RESULT_LIMIT");
                }
                result.put("report", delivery);
            }
        }
        return result;
    }

    /**
     * 推进 run 的固定工作流，汇总唯一的宿主提案。
     *
     * @param identity 调用方身份。
     * @param run      run 标识。
     * @param pin      已固定的发布配置。
     * @param active   已激活的发布列表。
     * @param tools    受控资源读取工具。
     * @param metadata 提供方元数据存储。
     * @return 唯一宿主提案或终态；未配置扩展或多个提案时返回 null/拒绝。
     * @throws Exception 推进失败或授权失败时抛出。
     */
    JSONObject advance(ProfileRegistry.Identity identity, String run, JSONObject pin, JSONArray active,
                       ResourceTools tools, ProviderStore metadata) throws Exception {
        if (guard == null) {
            return null;
        }
        final JSONObject taskInput = metadata.taskInput(identity.tenant, identity.subject, run);
        Set<String> activated = new HashSet<>();
        for (int i = 0; i < active.length(); i++) {
            activated.add(active.getJSONObject(i).getString("digest"));
        }
        JSONArray all = metadata.completionEvidence(identity.tenant, identity.subject, run);
        JSONObject proposal = null;
        JSONArray releases = pin.getJSONArray("releases");
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            JSONObject manifest = release.getJSONObject("manifest");
            if (!activated.contains(release.getString("digest")) || !required(manifest)) {
                continue;
            }
            String skill = manifest.getString("name");
            CompletionGuard.Resources resources = new CompletionGuard.Resources() {
                public JSONObject taskInput() {
                    return new JSONObject(taskInput.toString());
                }

                public JSONObject json(String path) throws Exception {
                    return tools.providerResource(identity, run, skill, path);
                }

                public byte[] bytes(String path) throws Exception {
                    return tools.providerResourceBytes(identity, run, skill, path);
                }
            };
            guard.validate(resources);
            JSONArray scoped = new JSONArray();
            for (int f = 0; f < all.length(); f++) {
                JSONObject fact = all.getJSONObject(f);
                JSONObject value = fact.getJSONObject("value");
                if (!skill.equals(value.optString("skill"))) {
                    continue;
                }
                if (guard.supportsInteraction() && fact.optLong("inputRevision", 0) != taskInput.getLong("inputRevision")) {
                    continue;
                }
                if (value.has("sourceRef")) {
                    metadata.authorize(identity.tenant, identity.subject, value.getString("sourceRef"));
                }
                JSONArray refs = value.optJSONArray("sourceRefs");
                if (refs != null) {
                    for (int s = 0; s < refs.length(); s++) {
                        metadata.authorize(identity.tenant, identity.subject, refs.getString(s));
                    }
                }
                scoped.put(fact);
            }
            if (!guard.supportsInteraction() && resources.taskInput().getLong("inputRevision") > 0) {
                throw new StoreException("WORKFLOW_INTERACTION_UNSUPPORTED");
            }
            JSONObject next = guard.advance(resources, scoped, skill);
            if (next != null) {
                if (proposal != null) {
                    throw new StoreException("WORKFLOW_BINDING_AMBIGUOUS");
                }
                proposal = next;
            }
        }
        return proposal;
    }

    /**
     * 关闭已加载的校验扩展。
     */
    @Override
    public void close() {
        if (guard != null) {
            guard.close();
        }
    }
}
