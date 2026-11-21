/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.store.CatalogStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 在请求的能力子集内做解析，不授予任何额外权限。
 */
public final class RunSelection {
    /**
     * 目录存储。
     */
    private final CatalogStore catalog;

    /**
     * 是否启用了受控文件访问能力。
     */
    private final boolean files;

    /**
     * 构造 run 选择器。
     *
     * @param catalog 目录存储。
     * @param files   是否启用文件访问。
     */
    public RunSelection(CatalogStore catalog, boolean files) {
        this.catalog = catalog;
        this.files = files;
    }

    /**
     * 解析策略声明的全部发布项，校验就绪状态与技能名唯一性。
     *
     * @param c        数据库连接，可为 null 表示使用普通查询。
     * @param identity 调用方身份。
     * @param policy   租户策略。
     * @return 已就绪的发布项数组。
     * @throws Exception 发布项未就绪或 schema 非法时抛出。
     */
    public JSONArray releases(Connection c, ProfileRegistry.Identity identity, JSONObject policy) throws Exception {
        JSONArray out = new JSONArray();
        Set<String> names = new HashSet<>();
        for (String digest : RuntimeSupport.strings(policy.getJSONArray("skillReleases"))) {
            JSONObject row = c == null ? catalog.release(identity.tenant, digest) : catalog.release(c, identity.tenant, digest, true);
            if (!row.getString("state").equals("ready")) {
                throw RuntimeSupport.error("RELEASE_NOT_READY");
            }
            JSONObject manifest = new JSONObject(row.getString("manifest_json"));
            RuntimeSupport.validate("package-manifest", manifest);
            if (!names.add(manifest.getString("name"))) {
                throw RuntimeSupport.error("PROFILE_DUPLICATE_SKILL");
            }
            JSONObject provenance = new JSONObject(row.getString("provenance_json"));
            out.put(new JSONObject()
                    .put("digest", digest)
                    .put("manifest", manifest)
                    .put("dependencies", provenance.optJSONArray("dependencies") == null ? new JSONArray() : provenance.getJSONArray("dependencies")));
        }
        return out;
    }

    /**
     * 求请求策略与受信身份、已声明能力三者的权限交集。
     *
     * @param identity 调用方身份。
     * @param policy   租户策略。
     * @param releases 已就绪的发布项数组。
     * @return 实际授予的权限集合。
     */
    public static Set<String> permissions(ProfileRegistry.Identity identity, JSONObject policy, JSONArray releases) {
        Set<String> granted = RuntimeSupport.strings(policy.getJSONArray("permissions"));
        Set<String> declared = new TreeSet<>(ProfileRegistry.PLATFORM_PERMISSIONS);
        granted.retainAll(identity.permissions);
        for (int i = 0; i < releases.length(); i++) {
            declared.addAll(RuntimeSupport.strings(releases.getJSONObject(i).getJSONObject("manifest").getJSONArray("permissions")));
        }
        granted.retainAll(declared);
        return granted;
    }

    /**
     * 判断单个发布项是否与给定 runtime 兼容。
     *
     * @param release 发布项。
     * @param runtime runtime 定义。
     * @return 兼容返回 true。
     */
    public static boolean compatible(JSONObject release, JSONObject runtime) {
        JSONArray tools = release.getJSONObject("manifest").getJSONArray("tools");
        for (int i = 0; i < tools.length(); i++) {
            if (tools.getJSONObject(i).getString("kind").equals("executable")
                    && (!runtime.getString("mode").equals("trusted-python-v1") || !RuntimeSupport.strings(runtime.getJSONArray("capabilities")).contains("tool.execute"))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 递归判断发布项及其依赖是否与 runtime 兼容，检测环与未固定依赖。
     *
     * @param digest   发布项摘要。
     * @param all      摘要到发布项的映射。
     * @param runtime  runtime 定义。
     * @param visiting 当前递归路径，用于环检测。
     * @return 整棵依赖树兼容返回 true。
     */
    private static boolean compatibleTree(String digest, Map<String, JSONObject> all, JSONObject runtime, Set<String> visiting) {
        JSONObject release = all.get(digest);
        if (release == null) {
            throw RuntimeSupport.error("DEPENDENCY_NOT_PINNED");
        }
        if (!visiting.add(digest)) {
            throw RuntimeSupport.error("DEPENDENCY_CYCLE");
        }
        boolean compatible = compatible(release, runtime);
        for (String dependency : RuntimeSupport.strings(release.getJSONArray("dependencies"))) {
            compatible &= compatibleTree(dependency, all, runtime, visiting);
        }
        visiting.remove(digest);
        return compatible;
    }

    /**
     * 判断发布项及其全部可用依赖是否与 runtime 兼容。
     *
     * @param release  发布项。
     * @param available 可用发布项数组。
     * @param runtime  runtime 定义。
     * @return 兼容返回 true。
     */
    public static boolean compatibleWithDependencies(JSONObject release, JSONArray available, JSONObject runtime) {
        Map<String, JSONObject> all = new HashMap<>();
        for (int i = 0; i < available.length(); i++) {
            all.put(available.getJSONObject(i).getString("digest"), available.getJSONObject(i));
        }
        return compatibleTree(release.getString("digest"), all, runtime, new HashSet<>());
    }

    /**
     * 把发布项及其依赖闭包加入选中集合。
     *
     * @param digest   发布项摘要。
     * @param all      摘要到发布项的映射。
     * @param selected 已选中的摘要集合。
     */
    private static void include(String digest, Map<String, JSONObject> all, Set<String> selected) {
        if (!selected.add(digest)) {
            return;
        }
        JSONObject release = all.get(digest);
        if (release == null) {
            throw RuntimeSupport.error("DEPENDENCY_NOT_PINNED");
        }
        for (String dependency : RuntimeSupport.strings(release.getJSONArray("dependencies"))) {
            include(dependency, all, selected);
        }
    }

    /**
     * 列出所有获授权的候选工具，包含尚未激活的技能中的工具。
     */
    public JSONArray tools(Set<String> permissions, JSONArray releases) {
        JSONArray out = new JSONArray();
        if (permissions.contains("resource.read")) {
            out.put(new JSONObject()
                    .put("name", "context.read_result")
                    .put("permission", "resource.read")
                    .put("kind", "platform"));
        }
        for (String name : ProfileRegistry.PLATFORM_PERMISSIONS) {
            if (permissions.contains(name)
                    && (files || (!name.equals("input.read_text") && !name.equals("artifact.write_text")))) {
                out.put(new JSONObject()
                        .put("name", name)
                        .put("permission", name)
                        .put("kind", "platform"));
            }
        }
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            JSONObject manifest = release.getJSONObject("manifest");
            Set<String> declared = RuntimeSupport.strings(manifest.getJSONArray("permissions"));
            for (int j = 0; j < manifest.getJSONArray("tools").length(); j++) {
                JSONObject tool = manifest.getJSONArray("tools").getJSONObject(j);
                if (!permissions.contains(tool.getString("permission")) || !declared.contains(tool.getString("permission"))) {
                    continue;
                }
                JSONObject item = new JSONObject()
                        .put("name", tool.getString("name"))
                        .put("kind", tool.getString("kind"))
                        .put("permission", tool.getString("permission"))
                        .put("skill", manifest.getString("name"))
                        .put("release", release.getString("digest"));
                if (tool.has("runnerType")) {
                    item.put("runnerType", tool.getString("runnerType"));
                }
                out.put(item);
            }
        }
        return out;
    }

    /**
     * 解析请求：筛选兼容技能、展开依赖闭包、校验工具与权限并固定到 pin。
     *
     * @param identity  调用方身份。
     * @param policy    租户策略。
     * @param available 可用发布项数组。
     * @param request   创建请求。
     * @param pin       待固定的 run 配置，原地更新。
     * @return 固定后的 run 配置。
     */
    public JSONObject resolve(ProfileRegistry.Identity identity, JSONObject policy, JSONArray available, JSONObject request, JSONObject pin) {
        JSONObject runtime = pin.getJSONObject("runtime");
        Map<String, JSONObject> all = new TreeMap<>();
        Map<String, JSONObject> byName = new TreeMap<>();
        for (int i = 0; i < available.length(); i++) {
            JSONObject release = available.getJSONObject(i);
            all.put(release.getString("digest"), release);
            byName.put(release.getJSONObject("manifest").getString("name"), release);
        }
        Set<String> selected = new TreeSet<>();
        Set<String> initial = new TreeSet<>();
        Set<String> requestedSkills = request.has("skills") ? RuntimeSupport.strings(request.getJSONArray("skills")) : null;
        for (String name : requestedSkills == null ? byName.keySet() : requestedSkills) {
            JSONObject release = byName.get(name);
            if (release == null) {
                throw RuntimeSupport.error("SKILL_FORBIDDEN");
            }
            if (!compatibleTree(release.getString("digest"), all, runtime, new HashSet<>())) {
                if (requestedSkills != null) {
                    throw RuntimeSupport.error("SKILL_RUNTIME_MISMATCH");
                }
                continue;
            }
            include(release.getString("digest"), all, selected);
        }
        JSONArray releases = new JSONArray();
        JSONArray skills = new JSONArray();
        for (String digest : selected) {
            JSONObject release = all.get(digest);
            JSONObject manifest = release.getJSONObject("manifest");
            releases.put(release);
            skills.put(new JSONObject()
                    .put("name", manifest.getString("name"))
                    .put("version", manifest.getString("version"))
                    .put("digest", digest));
        }
        Set<String> granted = permissions(identity, policy, releases);
        Map<String, List<JSONObject>> candidates = new TreeMap<>();
        JSONArray options = tools(granted, releases);
        for (int i = 0; i < options.length(); i++) {
            JSONObject tool = options.getJSONObject(i);
            candidates.computeIfAbsent(tool.getString("name"), k -> new ArrayList<>()).add(tool);
        }
        Set<String> allowedTools = candidates.keySet();
        Set<String> runnerTypes = new TreeSet<>();
        if (requestedSkills != null) {
            initial.addAll(selected);
        }
        for (String name : allowedTools) {
            List<JSONObject> matches = candidates.get(name);
            if (matches.size() != 1) {
                throw RuntimeSupport.error("TOOL_NAME_COLLISION");
            }
            JSONObject tool = matches.get(0);
            if (tool.has("runnerType")) {
                runnerTypes.add(tool.getString("runnerType"));
            }
        }
        for (String digest : initial) {
            JSONObject manifest = all.get(digest).getJSONObject("manifest");
            if (!granted.contains("skill.activate") || !RuntimeSupport.strings(manifest.getJSONArray("permissions")).contains("skill.activate")) {
                throw RuntimeSupport.error("SKILL_FORBIDDEN");
            }
        }
        JSONObject selection = new JSONObject()
                .put("model", ExecutionProfiles.ref(pin.getJSONObject("model")))
                .put("runtime", ExecutionProfiles.ref(runtime))
                .put("skills", skills)
                .put("tools", new JSONArray(allowedTools));
        return pin.put("schemaVersion", 3)
                .put("tenantPolicy", policy)
                .put("releases", releases)
                .put("permissions", new JSONArray(granted))
                .put("selection", selection)
                .put("initialSkills", new JSONArray(initial))
                .put("requiredRunnerTypes", new JSONArray(runnerTypes));
    }

    /**
     * 判断工具是否在已固定的选择结果中被允许。
     *
     * @param pin  已固定的 run 配置。
     * @param tool 工具名。
     * @return 无选择结果或工具在允许列表中返回 true。
     */
    public static boolean allowed(JSONObject pin, String tool) {
        JSONObject selection = pin.optJSONObject("selection");
        return selection == null || RuntimeSupport.strings(selection.getJSONArray("tools")).contains(tool);
    }

    /**
     * 要求工具已被允许，否则拒绝。
     *
     * @param pin  已固定的 run 配置。
     * @param tool 工具名。
     */
    public static void requireTool(JSONObject pin, String tool) {
        if (!allowed(pin, tool)) {
            throw RuntimeSupport.error("RESOURCE_FORBIDDEN");
        }
    }
}
