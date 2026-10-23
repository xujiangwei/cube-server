/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.store.CatalogStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 五个资源工具只操作固定到某 run 的 release 与权限。
 *
 * <p>工具面：skill.list / skill.activate / resource.list / resource.read /
 * resource.search，外加固定 release 声明的自定义资源工具（只读 manifest 固定路径）。
 * 每次调用都复查权限交集、参数 schema、激活状态与固定 release；资源能力不经激活
 * 不放行，激活本身也不增加权限。executable 工具的执行不在本类，本类只产出
 * 受信 coordinator 使用的包元数据。
 */
public final class ResourceTools {

    /**
     * 目录存储：fixed pin、active 技能与 activate 动作均经此访问。
     */
    private final CatalogStore catalog;

    /**
     * 包仓库：按 manifest 声明的 sha256 读取并复核文件内容。
     */
    private final PackageRepository repository;

    /**
     * 文件服务；为 null 时不出具文件类工具（默认关闭）。
     */
    private cube.service.harness.files.FileService files;

    /**
     * 注入文件服务（流式配置）。
     *
     * @param files 文件服务；传 {@code null} 表示不启用
     * @return 本实例，便于链式配置
     */
    public ResourceTools files(cube.service.harness.files.FileService files) {
        this.files = files;
        return this;
    }

    /**
     * 构造资源工具面。
     *
     * @param catalog    目录存储
     * @param repository 包仓库
     */
    public ResourceTools(CatalogStore catalog, PackageRepository repository) {
        this.catalog = catalog;
        this.repository = repository;
    }

    /**
     * 读取 run 的固定快照（tenant/subject 强制匹配当前身份）。
     *
     * @param identity 可信身份
     * @param run      run 标识
     * @return 固定快照 JSON
     */
    private JSONObject pin(ProfileRegistry.Identity identity, String run) {
        return catalog.pinned(identity.tenant, identity.subject, run);
    }

    /**
     * 权限三重交集：固定权限 ∩ 身份权限 ∩（manifest 声明权限，非空时）。
     *
     * @param pin        固定快照
     * @param identity   可信身份
     * @param permission 所需权限
     * @param manifest   包 manifest；无包上下文时传 {@code null}
     * @throws cube.service.harness.store.StoreException 交集不含所需权限（RESOURCE_FORBIDDEN）
     */
    private void permission(JSONObject pin, ProfileRegistry.Identity identity, String permission, JSONObject manifest) {
        Set<String> granted = RuntimeSupport.strings(pin.getJSONArray("permissions"));
        granted.retainAll(identity.permissions);
        if (manifest != null) {
            granted.retainAll(RuntimeSupport.strings(manifest.getJSONArray("permissions")));
        }
        if (!granted.contains(permission)) {
            throw RuntimeSupport.error("RESOURCE_FORBIDDEN");
        }
    }

    /**
     * 取固定 release 的 manifest，并复查权限与激活状态。
     *
     * @param identity   可信身份
     * @param run        run 标识
     * @param digest     release digest
     * @param permission 所需权限
     * @param activated  true 表示还要求该技能已激活
     * @return manifest JSON
     * @throws cube.service.harness.store.StoreException release 未固定、权限不足
     *                                                   （RESOURCE_FORBIDDEN）或技能未激活（SKILL_NOT_ACTIVE）
     */
    private JSONObject manifest(ProfileRegistry.Identity identity, String run, String digest, String permission, boolean activated) {
        JSONObject pin = pin(identity, run), release = CatalogStore.requireRelease(pin, RuntimeSupport.digest(digest));
        JSONObject manifest = release.getJSONObject("manifest");
        permission(pin, identity, permission, manifest);
        if (activated) {
            boolean active = false;
            JSONArray list = catalog.activeSkills(identity.tenant, identity.subject, run);
            for (int i = 0; i < list.length(); i++) {
                if (list.getJSONObject(i).getString("digest").equals(digest)) {
                    active = true;
                }
            }
            if (!active) {
                throw RuntimeSupport.error("SKILL_NOT_ACTIVE");
            }
        }
        return manifest;
    }

    /**
     * 列出固定 release 中声明了 skill.list 权限的技能。
     *
     * @param identity 可信身份
     * @param run      run 标识
     * @return {@code [{name, release}]} 数组
     * @throws cube.service.harness.store.StoreException 权限不足（RESOURCE_FORBIDDEN）
     */
    public JSONArray skills(ProfileRegistry.Identity identity, String run) {
        JSONObject pin = pin(identity, run);
        RunSelection.requireTool(pin, "skill.list");
        permission(pin, identity, "skill.list", null);
        JSONArray out = new JSONArray(), list = pin.getJSONArray("releases");
        for (int i = 0; i < list.length(); i++) {
            JSONObject release = list.getJSONObject(i), manifest = release.getJSONObject("manifest");
            if (RuntimeSupport.strings(manifest.getJSONArray("permissions")).contains("skill.list")) {
                out.put(new JSONObject().put("name", manifest.getString("name")).put("release", release.getString("digest")));
            }
        }
        return out;
    }

    /**
     * 激活一个固定 release 的技能（幂等由存储层保证）。
     *
     * @param identity 可信身份
     * @param run      run 标识
     * @param digest   release digest
     * @throws cube.service.harness.store.StoreException 权限不足（RESOURCE_FORBIDDEN）
     */
    public void activate(ProfileRegistry.Identity identity, String run, String digest) {
        RunSelection.requireTool(pin(identity, run), "skill.activate");
        manifest(identity, run, digest, "skill.activate", false);
        catalog.activate(identity.tenant, identity.subject, run, digest);
    }

    /**
     * 列出已激活 release 中 role 为 skill/resource 的文件条目。
     *
     * @param identity 可信身份
     * @param run      run 标识
     * @param digest   release digest
     * @return 文件条目数组（原样拷贝）
     * @throws cube.service.harness.store.StoreException 权限不足或未激活
     */
    public JSONArray resources(ProfileRegistry.Identity identity, String run, String digest) {
        RunSelection.requireTool(pin(identity, run), "resource.list");
        JSONObject manifest = manifest(identity, run, digest, "resource.list", true);
        JSONArray out = new JSONArray(), list = manifest.getJSONArray("files");
        for (int i = 0; i < list.length(); i++) {
            JSONObject file = list.getJSONObject(i);
            if (Arrays.asList("skill", "resource").contains(file.getString("role"))) {
                out.put(RuntimeSupport.copy(file));
            }
        }
        return out;
    }

    /**
     * 按 Unicode codepoint 分页读取 skill/resource 文件内容。
     *
     * <p>offset/limit 以 codepoint 计（limit 上限 8192）；返回 release/path/sha256/
     * offset/nextOffset/text 与可复用的 handle；读满时 nextOffset 为 -1。
     *
     * @param identity 可信身份
     * @param run      run 标识
     * @param digest   release digest
     * @param path     包内相对路径
     * @param offset   codepoint 起始偏移（不小于 0）
     * @param limit    codepoint 页长（1..8192）
     * @return 分页结果 JSON
     * @throws Exception 页码非法（INVALID_RESOURCE_PAGE）、角色不符或权限不足
     *                   （RESOURCE_FORBIDDEN）、内容读取/校验失败
     */
    public JSONObject read(ProfileRegistry.Identity identity, String run, String digest, String path, int offset, int limit) throws Exception {
        RunSelection.requireTool(pin(identity, run), "resource.read");
        return readInternal(identity, run, digest, path, offset, limit);
    }

    private JSONObject readInternal(ProfileRegistry.Identity identity, String run, String digest, String path, int offset, int limit) throws Exception {
        if (offset < 0 || limit < 1 || limit > 8192) {
            throw RuntimeSupport.error("INVALID_RESOURCE_PAGE");
        }
        JSONObject manifest = manifest(identity, run, digest, "resource.read", true), file = PackageRepository.file(manifest, path);
        if (!Arrays.asList("skill", "resource").contains(file.getString("role"))) {
            throw RuntimeSupport.error("RESOURCE_FORBIDDEN");
        }
        String text = new String(repository.readVerified(manifest, path), StandardCharsets.UTF_8);
        int chars = text.codePointCount(0, text.length());
        if (offset > chars) {
            throw RuntimeSupport.error("INVALID_RESOURCE_PAGE");
        }
        int end = Math.min(chars, offset + limit);
        return new JSONObject().put("release", digest).put("path", path).put("sha256", file.getString("sha256"))
                .put("offset", offset)
                .put("nextOffset", end == chars ? -1 : end)
                .put("text", text.substring(text.offsetByCodePoints(0, offset), text.offsetByCodePoints(0, end)))
                .put("handle", new JSONObject().put("release", digest).put("path", path).put("sha256", file.getString("sha256")));
    }

    /**
     * 有界字面量搜索：needle 不超过 256 字符、最多返回 limit（不超过 100）条匹配。
     *
     * <p>只搜 skill/resource 角色文件；每条命中含 path、codepoint offset、sha256
     * 与最多 128 字符的预览；达到 limit 即停止。
     *
     * @param identity 可信身份
     * @param run      run 标识
     * @param digest   release digest
     * @param needle   字面量（非空，不超过 256 字符）
     * @param limit    返回条数上限（1..100）
     * @return 命中数组
     * @throws Exception 参数非法（INVALID_RESOURCE_SEARCH）、权限不足或未激活
     */
    public JSONArray search(ProfileRegistry.Identity identity, String run, String digest, String needle, int limit) throws Exception {
        RunSelection.requireTool(pin(identity, run), "resource.search");
        if (needle == null || needle.isEmpty() || needle.length() > 256 || limit < 1 || limit > 100) {
            throw RuntimeSupport.error("INVALID_RESOURCE_SEARCH");
        }
        JSONObject manifest = manifest(identity, run, digest, "resource.search", true);
        JSONArray out = new JSONArray();
        for (int i = 0; i < manifest.getJSONArray("files").length(); i++) {
            JSONObject f = manifest.getJSONArray("files").getJSONObject(i);
            if (!Arrays.asList("skill", "resource").contains(f.getString("role"))) {
                continue;
            }
            String text = new String(repository.readVerified(manifest, f.getString("path")), StandardCharsets.UTF_8);
            int from = 0, match;
            while ((match = text.indexOf(needle, from)) >= 0 && out.length() < limit) {
                out.put(new JSONObject().put("path", f.getString("path")).put("offset", text.codePointCount(0, match)).put("sha256", f.getString("sha256"))
                        .put("preview", text.substring(match, Math.min(text.length(), match + 128))));
                from = match + needle.length();
            }
            if (out.length() == limit) {
                break;
            }
        }
        return out;
    }

    /**
     * 出具当前 run 可用的全部工具定义（平台资源工具 + 文件工具 + 激活包的声明工具）。
     *
     * <p>平台工具按「固定权限 ∩ 身份权限」过滤 skill./resource. 前缀权限生成，
     * 参数 schema 与 read/search 的运行时校验一致；文件服务启用时追加
     * FileService.tools；自定义工具只从已激活 release 的 manifest 读取，
     * 且工具权限须在交集内，inputSchema/outputSchema 从包内读取并复核。
     *
     * @param identity 可信身份
     * @param run      run 标识
     * @return 工具定义数组（name/permission/description/parameters[/resultSchema]）
     * @throws Exception 包内容读取或 schema 校验失败
     */
    public JSONArray tools(ProfileRegistry.Identity identity, String run) throws Exception {
        JSONObject pin = pin(identity, run);
        Set<String> permissions = RuntimeSupport.strings(pin.getJSONArray("permissions"));
        permissions.retainAll(identity.permissions);
        JSONArray out = new JSONArray();
        for (String name : ProfileRegistry.PLATFORM_PERMISSIONS) {
            if (permissions.contains(name) && (name.startsWith("skill.") || name.startsWith("resource."))) {
                JSONObject properties = new JSONObject();
                JSONArray required = new JSONArray();
                if (!name.equals("skill.list")) {
                    properties.put("release", new JSONObject().put("type", "string").put("pattern", "^[a-f0-9]{64}$").put("maxLength", 64));
                    required.put("release");
                }
                if (name.equals("resource.read")) {
                    properties.put("path", new JSONObject().put("type", "string").put("maxLength", 256));
                    properties.put("offset", new JSONObject().put("type", "integer").put("minimum", 0));
                    properties.put("limit", new JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 8192));
                    required.put("path").put("offset").put("limit");
                }
                if (name.equals("resource.search")) {
                    properties.put("query", new JSONObject().put("type", "string").put("maxLength", 256));
                    properties.put("limit", new JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 100));
                    required.put("query").put("limit");
                }
                out.put(new JSONObject().put("name", name).put("permission", name).put("description", "Read pinned skill resources: " + name)
                        .put("parameters", new JSONObject().put("type", "object")
                                .put("properties", properties).put("required", required).put("additionalProperties", false)));
            }
        }
        if (files != null) {
            JSONArray extra = cube.service.harness.files.FileService.tools(permissions);
            for (int i = 0; i < extra.length(); i++) {
                out.put(extra.get(i));
            }
        }
        Set<String> active = new HashSet<>();
        JSONArray activeRows = catalog.activeSkills(identity.tenant, identity.subject, run);
        for (int i = 0; i < activeRows.length(); i++) {
            active.add(activeRows.getJSONObject(i).getString("digest"));
        }
        for (int i = 0; i < pin.getJSONArray("releases").length(); i++) {
            JSONObject release = pin.getJSONArray("releases").getJSONObject(i), manifest = release.getJSONObject("manifest");
            if (!active.contains(release.getString("digest"))) {
                continue;
            }
            Set<String> allowed = new HashSet<>(permissions);
            allowed.retainAll(RuntimeSupport.strings(manifest.getJSONArray("permissions")));
            JSONArray declared = manifest.getJSONArray("tools");
            for (int j = 0; j < declared.length(); j++) {
                JSONObject tool = declared.getJSONObject(j);
                if (!allowed.contains(tool.getString("permission"))) {
                    continue;
                }
                out.put(new JSONObject().put("name", tool.getString("name")).put("permission", tool.getString("permission"))
                        .put("description", tool.getString("kind").equals("resource")
                                ? "Read immutable resource " + tool.getString("resourcePath")
                                : "Execute the activated SKILL tool through its authorized provider")
                        .put("resultSchema", cube.service.harness.protocol.StrictJson.object(repository.readVerified(manifest, tool.getString("outputSchema"))))
                        .put("parameters", cube.service.harness.protocol.StrictJson.object(repository.readVerified(manifest, tool.getString("inputSchema")))));
            }
        }
        JSONArray selected = new JSONArray();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < out.length(); i++) {
            JSONObject tool = out.getJSONObject(i);
            if (!RunSelection.allowed(pin, tool.getString("name"))) continue;
            if (!names.add(tool.getString("name"))) throw RuntimeSupport.error("TOOL_NAME_COLLISION");
            selected.put(tool);
        }
        return selected;
    }

    /**
     * 由受信 coordinator 在准入之后调用；本方法只执行资源读取，不运行任何代码。
     *
     * @param identity  可信身份
     * @param run       run 标识
     * @param name      工具名
     * @param arguments 工具参数
     * @return 工具结果（JSON 值）
     * @throws Exception 工具不存在或参数/schema/激活/权限任一审验失败
     */
    public Object invoke(ProfileRegistry.Identity identity, String run, String name, JSONObject arguments) throws Exception {
        return invoke(identity, run, name, arguments, null);
    }

    /**
     * 带租约的工具调用：activate 在携带 lease 时走租约路径并核对租约归属。
     *
     * <p>每次调用都从 tools() 重取定义（权限/激活/固定 release 的最新快照），
     * 参数按定义 parameters 校验后分派；自定义资源工具只读固定路径并复验
     * 输出 schema，executable 类工具在此路径一律拒绝（EXECUTABLE_REQUIRES_RUNNER）。
     *
     * @param identity  可信身份
     * @param run       run 标识
     * @param name      工具名
     * @param arguments 工具参数
     * @param lease     run 租约；可为 {@code null}
     * @return 工具结果（JSON 值）
     * @throws Exception 工具未授权（RESOURCE_FORBIDDEN）、参数 schema 不符、
     *                   租约归属不符、需要 Runner（EXECUTABLE_REQUIRES_RUNNER）等
     */
    public Object invoke(ProfileRegistry.Identity identity, String run, String name, JSONObject arguments,
                         cube.service.harness.store.JdbcRunStore.Lease lease) throws Exception {
        JSONArray available = tools(identity, run);
        JSONObject definition = null;
        for (int i = 0; i < available.length(); i++) {
            if (available.getJSONObject(i).getString("name").equals(name)) {
                definition = available.getJSONObject(i);
            }
        }
        if (definition == null) {
            throw RuntimeSupport.error("RESOURCE_FORBIDDEN");
        }
        cube.service.harness.protocol.Contract.validate(arguments, definition.getJSONObject("parameters"));
        if (name.equals("skill.list")) {
            return skills(identity, run);
        }
        String digest = arguments.optString("release");
        if (name.equals("skill.activate")) {
            if (lease == null) {
                activate(identity, run, digest);
            } else {
                // 租约路径：lease 的 tenant/subject/run 必须与当前调用一致。
                if (!lease.tenant.equals(identity.tenant) || !lease.subject.equals(identity.subject) || !lease.runId.equals(run)) {
                    throw RuntimeSupport.error("RESOURCE_FORBIDDEN");
                }
                manifest(identity, run, digest, "skill.activate", false);
                catalog.activate(lease, digest);
            }
            return new JSONObject().put("activated", digest);
        }
        if (name.equals("resource.list")) {
            return resources(identity, run, digest);
        }
        if (name.equals("resource.read")) {
            return read(identity, run, digest, arguments.getString("path"), arguments.getInt("offset"), arguments.getInt("limit"));
        }
        if (name.equals("resource.search")) {
            return search(identity, run, digest, arguments.getString("query"), arguments.getInt("limit"));
        }
        JSONObject pin = pin(identity, run);
        JSONArray releases = pin.getJSONArray("releases");
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i), manifest = release.getJSONObject("manifest");
            JSONArray declared = manifest.getJSONArray("tools");
            for (int j = 0; j < declared.length(); j++) {
                if (declared.getJSONObject(j).getString("name").equals(name)) {
                    JSONObject tool = declared.getJSONObject(j);
                    if (!tool.getString("kind").equals("resource")) {
                        throw RuntimeSupport.error("EXECUTABLE_REQUIRES_RUNNER");
                    }
                    JSONObject result = readInternal(identity, run, release.getString("digest"),
                            tool.getString("resourcePath"), arguments.optInt("offset", 0), arguments.optInt("limit", 1024));
                    cube.service.harness.protocol.Contract.validate(result, definition.getJSONObject("resultSchema"));
                    return result;
                }
            }
        }
        throw RuntimeSupport.error("RESOURCE_FORBIDDEN");
    }

    /**
     * 受信 coordinator 元数据；绝不作为模型选择的执行参数下发。
     *
     * <p>按名字查找固定 release 中 kind=executable 的工具：复查权限与激活后，
     * 打包 manifest 与全部文件内容（Base64）为 bundleJson，超过 120000 字节拒绝
     * （EXECUTABLE_PACKAGE_TOO_LARGE），并附带参数/结果 schema 与固定 runtime。
     *
     * @param identity 可信身份
     * @param run      run 标识
     * @param name     工具名
     * @return 包元数据 JSON；找不到返回 {@code null}
     * @throws Exception 权限不足/未激活（RESOURCE_FORBIDDEN）或包体超限
     */
    public JSONObject executable(ProfileRegistry.Identity identity, String run, String name) throws Exception {
        JSONObject pin = pin(identity, run);
        RunSelection.requireTool(pin, name);
        Set<String> active = new HashSet<>();
        JSONArray activeRows = catalog.activeSkills(identity.tenant, identity.subject, run);
        for (int i = 0; i < activeRows.length(); i++) {
            active.add(activeRows.getJSONObject(i).getString("digest"));
        }
        for (int i = 0; i < pin.getJSONArray("releases").length(); i++) {
            JSONObject release = pin.getJSONArray("releases").getJSONObject(i), manifest = release.getJSONObject("manifest");
            for (int n = 0; n < manifest.getJSONArray("tools").length(); n++) {
                JSONObject tool = manifest.getJSONArray("tools").getJSONObject(n);
                if (!tool.getString("name").equals(name) || !tool.getString("kind").equals("executable")) {
                    continue;
                }
                manifest(identity, run, release.getString("digest"), tool.getString("permission"), true);
                if (!active.contains(release.getString("digest"))) {
                    throw RuntimeSupport.error("RESOURCE_FORBIDDEN");
                }
                JSONArray files = new JSONArray();
                for (int f = 0; f < manifest.getJSONArray("files").length(); f++) {
                    JSONObject entry = manifest.getJSONArray("files").getJSONObject(f);
                    files.put(new JSONObject().put("path", entry.getString("path"))
                            .put("data", java.util.Base64.getEncoder().encodeToString(repository.readVerified(manifest, entry.getString("path")))));
                }
                JSONObject bundle = new JSONObject().put("manifest", manifest).put("files", files);
                if (RuntimeSupport.bytes(bundle).length > 120000) {
                    throw RuntimeSupport.error("EXECUTABLE_PACKAGE_TOO_LARGE");
                }
                return new JSONObject().put("release", release.getString("digest")).put("tool", tool)
                        .put("bundleJson", cube.service.harness.store.JdbcRunStore.canonical(bundle))
                        .put("parameters", cube.service.harness.protocol.StrictJson.object(repository.readVerified(manifest, tool.getString("inputSchema"))))
                        .put("resultSchema", cube.service.harness.protocol.StrictJson.object(repository.readVerified(manifest, tool.getString("outputSchema"))))
                        .put("runtime", pin.getJSONObject("runtime"));
            }
        }
        return null;
    }
}
