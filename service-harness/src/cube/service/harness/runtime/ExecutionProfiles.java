/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.protocol.StrictJson;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 服务端持有的执行配置；任何定义都不属于租户。
 */
public final class ExecutionProfiles {
    /**
     * 执行主体配置。
     */
    private final JSONObject execution;

    /**
     * profile 键（kind:id@version）到定义的映射。
     */
    private final Map<String, JSONObject> definitions = new TreeMap<>();

    /**
     * kind:id 到当前生效版本的映射。
     */
    private final Map<String, String> current = new TreeMap<>();

    /**
     * 从执行配置与定义集合构造注册表。
     *
     * @param execution   执行主体配置。
     * @param definitions profile 键到定义的映射。
     */
    private ExecutionProfiles(JSONObject execution, Map<String, JSONObject> definitions) {
        this.execution = RuntimeSupport.copy(execution);
        for (Map.Entry<String, JSONObject> entry : definitions.entrySet()) {
            this.definitions.put(entry.getKey(), RuntimeSupport.copy(entry.getValue()));
        }
        JSONObject active = execution.optJSONObject("activeVersions");
        for (String kind : Arrays.asList("model", "runtime")) {
            Map<String, List<String>> versions = new TreeMap<>();
            for (JSONObject value : list(kind)) {
                versions.computeIfAbsent(value.getString("id"), k -> new ArrayList<>()).add(value.getString("version"));
            }
            JSONObject selected = active == null ? null : active.optJSONObject(kind);
            if (selected != null) {
                for (String id : selected.keySet()) {
                    resolve(kind, id + "@" + selected.getString(id));
                }
            }
            for (Map.Entry<String, List<String>> entry : versions.entrySet()) {
                String version = selected == null ? null : selected.optString(entry.getKey(), null);
                if (version == null) {
                    if (entry.getValue().size() != 1) {
                        throw RuntimeSupport.error("ACTIVE_VERSION_REQUIRED");
                    }
                    version = entry.getValue().get(0);
                }
                current.put(kind + ":" + entry.getKey(), version);
            }
        }
        select(new JSONObject());
    }

    /**
     * 从部署目录装载服务端执行配置与全部 profile 定义。
     *
     * @param properties 运行参数，execution.profiles 指定目录，execution.model/runtime 可覆盖当前版本。
     * @return 装载完成的执行配置注册表。
     * @throws IOException 目录缺失、定义非法或重复时抛出。
     */
    public static ExecutionProfiles load(Properties properties) throws IOException {
        Path directory = Paths.get(properties.getProperty("execution.profiles", "./config/harness-execution"));
        Map<String, JSONObject> definitions = new HashMap<>();
        JSONObject execution = null;
        try (Stream<Path> paths = Files.list(directory)) {
            Iterator<Path> entries = paths.sorted().iterator();
            while (entries.hasNext()) {
                Path path = entries.next();
                if (!path.getFileName().toString().endsWith(".json")) {
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Invalid execution file: " + path);
                }
                JSONObject value;
                try (InputStream input = Files.newInputStream(path)) {
                    value = StrictJson.object(RuntimeSupport.read(input, 1048576));
                }
                String kind = value.getString("kind");
                if (kind.equals("execution")) {
                    RuntimeSupport.validate("execution-profile", value);
                    if (execution != null) {
                        throw new IOException("Multiple execution definitions: " + directory);
                    }
                    execution = value;
                } else {
                    if (!Arrays.asList("model", "runtime").contains(kind)) {
                        throw new IOException("Not a server execution profile: " + path);
                    }
                    ProfileRegistry.validateDefinition(value);
                    String key = kind + ":" + value.getString("id") + "@" + value.getString("version");
                    if (definitions.put(key, value) != null) {
                        throw new IOException("Duplicate execution profile: " + key);
                    }
                }
                if (definitions.size() > 256) {
                    throw new IOException("Too many execution profiles");
                }
            }
            if (execution == null) {
                throw new IOException("Missing execution definition: " + directory);
            }
            for (String kind : Arrays.asList("model", "runtime")) {
                String override = properties.getProperty("execution." + kind);
                if (override != null) {
                    String[] ref = override.split("@", -1);
                    if (ref.length != 2) {
                        throw new IOException("execution." + kind + " requires id@version");
                    }
                    execution.put(kind, new JSONObject()
                            .put("id", RuntimeSupport.id(ref[0]))
                            .put("version", RuntimeSupport.id(ref[1])));
                }
            }
            return new ExecutionProfiles(execution, definitions);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid server execution configuration: " + directory, e);
        }
    }

    /**
     * 生成 profile 的 id/version 引用对象。
     *
     * @param value profile 定义。
     * @return 引用对象。
     */
    public static JSONObject ref(JSONObject value) {
        return new JSONObject()
                .put("id", value.getString("id"))
                .put("version", value.getString("version"));
    }

    /**
     * 列出指定类型的全部 profile 定义副本。
     *
     * @param kind profile 类型，model 或 runtime。
     * @return 定义副本列表。
     */
    public List<JSONObject> list(String kind) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject value : definitions.values()) {
            if (value.getString("kind").equals(kind)) {
                out.add(RuntimeSupport.copy(value));
            }
        }
        return out;
    }

    /**
     * 按 id@version 选择器解析 profile，缺省版本取当前生效版本。
     *
     * @param kind     profile 类型。
     * @param selector id 或 id@version。
     * @return profile 定义副本。
     */
    public JSONObject resolve(String kind, String selector) {
        String[] parts = selector.split("@", -1);
        if (parts.length > 2) {
            throw RuntimeSupport.error("INVALID_PROFILE_REF");
        }
        String id = RuntimeSupport.id(parts[0]);
        String version = parts.length == 2 ? RuntimeSupport.id(parts[1]) : current.get(kind + ":" + id);
        JSONObject value = definitions.get(kind + ":" + id + "@" + version);
        if (value == null) {
            throw RuntimeSupport.error(kind.toUpperCase(Locale.ROOT) + "_NOT_FOUND");
        }
        return RuntimeSupport.copy(value);
    }

    /**
     * 按请求选择 model/runtime 并校验能力约束。
     *
     * @param request 选择请求，可含 model/runtime 覆盖。
     * @return 含 execution/model/runtime 的选择结果。
     */
    public JSONObject select(JSONObject request) {
        JSONObject selected = execution();
        JSONObject model = request.has("model") ? resolve("model", request.getString("model")) : model();
        JSONObject runtime = request.has("runtime") ? resolve("runtime", request.getString("runtime")) : runtime();
        Set<String> capabilities = RuntimeSupport.strings(model.getJSONArray("capabilities"));
        capabilities.addAll(RuntimeSupport.strings(runtime.getJSONArray("capabilities")));
        if (!capabilities.containsAll(RuntimeSupport.strings(selected.getJSONArray("requiredCapabilities")))) {
            throw RuntimeSupport.error("EXECUTION_CAPABILITY_MISMATCH");
        }
        selected.put("model", ref(model))
                .put("runtime", ref(runtime))
                .put("toolSetVersion", runtime.getString("toolSetVersion"));
        selected.remove("activeVersions");
        return new JSONObject()
                .put("execution", selected)
                .put("model", model)
                .put("runtime", runtime);
    }

    /**
     * 返回默认 model/runtime 引用。
     *
     * @return 默认引用对象。
     */
    public JSONObject defaults() {
        return new JSONObject()
                .put("model", ref(model()))
                .put("runtime", ref(runtime()));
    }

    /**
     * 判断给定 profile 是否处于当前生效版本。
     *
     * @param value profile 定义。
     * @return 是当前版本返回 true。
     */
    public boolean current(JSONObject value) {
        return value.getString("version").equals(current.get(value.getString("kind") + ":" + value.getString("id")));
    }

    /**
     * 返回执行主体配置副本。
     *
     * @return 执行主体配置。
     */
    public JSONObject execution() {
        return RuntimeSupport.copy(execution);
    }

    /**
     * 返回当前生效的 model 定义。
     *
     * @return model 定义副本。
     */
    public JSONObject model() {
        JSONObject ref = execution.getJSONObject("model");
        return resolve("model", ref.getString("id") + "@" + ref.getString("version"));
    }

    /**
     * 返回当前生效的 runtime 定义。
     *
     * @return runtime 定义副本。
     */
    public JSONObject runtime() {
        JSONObject ref = execution.getJSONObject("runtime");
        return resolve("runtime", ref.getString("id") + "@" + ref.getString("version"));
    }

    /**
     * 读取 run 创建时固定的服务端配置。
     *
     * @param pin run 配置 pin。
     * @return 固定的执行配置。
     */
    public static JSONObject execution(JSONObject pin) {
        return pin.getJSONObject("execution");
    }
}
