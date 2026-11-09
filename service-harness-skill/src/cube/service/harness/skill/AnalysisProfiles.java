/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.protocol.Contract;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * 业务分析档案（profile）的校验入口。
 *
 * <p>档案只是既有 Unit 选项的不可变别名，永远不指向运行任务的主模型。
 * 每个档案必须声明受支持的 unit 与 adapter 组合，并且其选项参数要能通过
 * 对应 options schema 的校验。</p>
 */
public final class AnalysisProfiles {
    /**
     * 工具类，禁止实例化。
     */
    private AnalysisProfiles() {
    }

    /**
     * 校验分析档案配置：结构合法、档案 id 唯一、unit 与 adapter 匹配、选项参数合法。
     *
     * @param config {@code analysis-profiles.json} 的内容。
     */
    public static void validate(JSONObject config) {
        Contract.validate(config, AbstractProvider.schema("capability", "analysis-profiles"));
        Set<String> seen = new HashSet<>();
        JSONArray profiles = config.getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject p = profiles.getJSONObject(i);
            if (!seen.add(p.getString("id"))) {
                throw new StoreException("ANALYSIS_PROFILE_INVALID");
            }
            String unit = p.getString("unit");
            String adapter = p.getString("adapter");
            // 只有声明的组合才映射到对应的 options schema，其余组合一律拒绝。
            String kind = optionsKind(adapter, unit);
            JSONObject options = ConnectorConfig.arguments(p.getString("optionsJson"));
            Contract.validate(options, AbstractProvider.schema("capability", kind));
            // Profile availability and slicing support are checked against live unit reports.
        }
    }

    /**
     * 把 unit 与 adapter 的组合映射到对应的 options schema 名称。
     *
     * @param adapter 适配器名称。
     * @param unit    Unit 名称。
     * @return 返回 options schema 的名称。
     */
    private static String optionsKind(String adapter, String unit) {
        if ("multimodal".equals(adapter)) {
            if ("OmniMultimodal".equals(unit)) {
                return "options-multimodal";
            }
            if ("OmniPerception".equals(unit)) {
                return "options-perception";
            }
        } else if ("specialized-detection".equals(adapter) && "SpecializedDetection".equals(unit)) {
            return "options-specialized";
        }
        throw new StoreException("ANALYSIS_PROFILE_INVALID");
    }
}
