/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.JdbcRunStore;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 租户默认配置只包含业务策略；执行定义一律拒绝。
 */
public final class TenantTemplates {

    /**
     * 默认策略的规范化定义。
     */
    private final String definition;

    /**
     * 默认策略摘要。
     */
    public final String digest;

    /**
     * 从默认策略构造模板。
     *
     * @param policy 默认租户策略。
     */
    private TenantTemplates(JSONObject policy) {
        definition = JdbcRunStore.canonical(policy);
        digest = RuntimeSupport.sha(RuntimeSupport.bytes(policy));
    }

    /**
     * 从目录装载唯一的默认租户策略。
     *
     * @param directory 策略文件目录。
     * @return 装载完成的模板。
     * @throws IOException 文件非法、策略不合规或默认策略缺失/重复时抛出。
     */
    public static TenantTemplates load(Path directory) throws IOException {
        JSONObject policy = null;
        try (Stream<Path> paths = Files.list(directory)) {
            Iterator<Path> entries = paths.sorted().iterator();
            while (entries.hasNext()) {
                Path path = entries.next();
                if (!path.getFileName().toString().endsWith(".json")) {
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Invalid tenant policy file: " + path);
                }
                JSONObject value;
                try (InputStream input = Files.newInputStream(path)) {
                    value = StrictJson.object(RuntimeSupport.read(input, 1048576));
                }
                RuntimeSupport.validate("tenant-policy", value);
                RuntimeSupport.strings(value.getJSONArray("permissions"));
                RuntimeSupport.strings(value.getJSONArray("skillReleases"));
                if (!value.getString("id").equals("default") || value.getJSONArray("skillReleases").length() != 0) {
                    throw new IOException("Tenant default policy must use id=default and no skill releases");
                }
                if (policy != null) {
                    throw new IOException("Exactly one default tenant policy is required");
                }
                policy = value;
            }
            if (policy == null) {
                throw new IOException("Missing default tenant policy: " + directory);
            }
            return new TenantTemplates(policy);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid tenant policy configuration: " + directory, e);
        }
    }

    /**
     * 返回默认策略的 profile 列表。
     *
     * @return 仅含默认策略的列表。
     */
    public List<JSONObject> profiles() {
        return Collections.singletonList(new JSONObject(definition));
    }
}
