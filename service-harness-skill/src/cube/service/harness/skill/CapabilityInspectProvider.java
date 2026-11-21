/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.aigc.SpecializedDetectionCapabilities;
import cube.service.harness.execution.ProviderInvocation;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 发现已授权的逻辑分析 profile，不占用容量也不执行推理。
 */
public final class CapabilityInspectProvider extends AbstractProvider {
    /**
     * 允许分析的能力单元集合。
     */
    private final Set<String> allowed;

    /**
     * 构造能力 inspection provider。
     *
     * @param properties 部署私有配置。
     */
    public CapabilityInspectProvider(Properties properties) {
        super("capabilities", "common.capabilities.inspect", "capability.read", properties);
        allowed = new HashSet<>();
        for (String unit : properties.getProperty("capability.allowed.units", "").split(",")) {
            if (!unit.trim().isEmpty()) {
                allowed.add(unit.trim());
            }
        }
    }

    /**
     * 返回本 provider 依赖的配置文件列表。
     *
     * @return 配置文件名集合。
     */
    @Override
    public Set<String> configurationFiles() {
        return Collections.singleton("analysis-profiles.json");
    }

    /**
     * 校验分析 profile 配置。
     *
     * @param path   配置文件路径。
     * @param config 配置 JSON。
     */
    @Override
    public void validateConfiguration(String path, JSONObject config) {
        AnalysisProfiles.validate(config);
    }

    /**
     * 读取资源并汇总各 profile 的可用状态。
     *
     * @param context 调用上下文。
     * @param args    调用参数。
     * @return 可用性汇总 JSON。
     * @throws Exception 资源读取或解析失败。
     */
    @Override
    protected JSONObject execute(ProviderInvocation context, JSONObject args) throws Exception {
        return inspect(context.resource(args.getString("skill"), "analysis-profiles.json"), context.mediaUnits());
    }

    /**
     * 按已授权单元与实例就绪状态计算各 profile 的可用性。
     *
     * @param config 分析 profile 配置。
     * @param units  媒体单元实例数组。
     * @return 可用性汇总 JSON。
     */
    JSONObject inspect(JSONObject config, JSONArray units) {
        AnalysisProfiles.validate(config);
        JSONArray result = new JSONArray();
        JSONArray profiles = config.getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.getJSONObject(i);
            String unit = profile.getString("unit");
            String status = "unavailable";
            if (!allowed.contains(unit)) {
                status = "disabled";
            } else {
                JSONObject option = CapabilityInvokeProvider.unitOptions(profile);
                for (int j = 0; j < units.length(); j++) {
                    JSONObject instance = units.getJSONObject(j);
                    if (!unit.equals(instance.optString("unit")) || !instance.optBoolean("ready")) {
                        continue;
                    }
                    if (unit.equals("SpecializedDetection") && (!SpecializedDetectionCapabilities.ready(instance)
                            || !SpecializedDetectionCapabilities.supports(instance, option.getString("profile"), option.optBoolean("sliced")))) {
                        continue;
                    }
                    if (instance.optInt("capacity") > 0) {
                        status = "ready";
                    }
                }
            }
            result.put(new JSONObject()
                    .put("profileId", profile.getString("id"))
                    .put("mediaTypes", profile.getJSONArray("mediaTypes"))
                    .put("status", status));
        }
        return new JSONObject()
                .put("profiles", result)
                .put("availability", "readiness-only-not-capacity");
    }
}
