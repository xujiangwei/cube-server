/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc;

import cube.common.entity.AICapability;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 专项检测（SpecializedDetection）节点的注册与路由判定工具。
 *
 * <p>专项检测节点复用既有 {@code AICapability.getSubtasks()} 承载能力声明：
 * 名称必须是 {@code SpecializedDetection}，首项固定为 {@code ObjectDetection}，
 * 其余各项按注册顺序作为已启用的检测 profile。其他名称的能力仍按原子任务解释，
 * 不在此处派生 profile，也不新增能力扩展字段。</p>
 *
 * <p>本类只做无状态的纯判定，不持有租约、容量或会话信息。就绪租约
 * （{@code statusExpiresAt}）由服务侧状态报告产生，service、dispatcher 与 Harness
 * 侧共用这里的方法在发送前复核，避免各处重复实现造成口径不一致。</p>
 */
public final class SpecializedDetectionCapabilities {

    /**
     * 私有构造方法，禁止实例化。
     */
    private SpecializedDetectionCapabilities() {
    }

    /**
     * 从能力的 subtasks 中解析该节点已启用的检测 profile 列表。
     *
     * <p>合法注册要求名称为 {@code SpecializedDetection}、至少两项、首项为
     * {@code ObjectDetection}，且后续各项非空、非空白、不重复、也不回退为
     * {@code ObjectDetection}。任一条件不满足时整份声明视为无效并返回空列表，
     * 以免部分 profile 被误当成可用节点；其他能力名称一律返回空列表。</p>
     *
     * @param capability 待解析的能力声明。
     * @return 按注册顺序去重后的 profile 列表；非专项检测能力或非法声明返回空列表。
     */
    public static List<String> profiles(AICapability capability) {
        List<String> tasks = capability.getSubtasks();
        if (!"SpecializedDetection".equals(capability.getName()) || tasks.size() < 2
                || !"ObjectDetection".equals(tasks.get(0))) {
            return Collections.emptyList();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String task : tasks.subList(1, tasks.size())) {
            if (task == null || task.trim().isEmpty() || "ObjectDetection".equals(task)
                    || !names.add(task)) {
                return Collections.emptyList();
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * 判断描述对象的指定数组字段中是否包含给定名称。
     *
     * <p>字段缺失、类型不符或数组为空时返回 {@code false}；数组元素通过
     * {@code name.equals(...)} 比较，非字符串元素（数字、对象等）不计为匹配，
     * 因此畸形快照既不会抛异常，也不会误命中。</p>
     *
     * @param descriptor 提供方快照，通常是能力快照里的单个 provider 对象。
     * @param field 待检查的数组字段名，例如 {@code profiles} 或 {@code slicedProfiles}。
     * @param name 待匹配的 profile 名称。
     * @return 数组中包含该名称时返回 {@code true}。
     */
    public static boolean contains(JSONObject descriptor, String field, String name) {
        JSONArray values = descriptor.optJSONArray(field);
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                if (name.equals(values.opt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断提供方是否支持指定 profile 的请求形态。
     *
     * <p>先要求 {@code profiles} 中注册了该 profile；仅当 {@code sliced} 为
     * {@code true} 时，额外要求 {@code slicedProfiles} 也包含该 profile。
     * 本方法只校验能力匹配，不检查就绪状态与剩余容量，调用方须另行复核。</p>
     *
     * @param descriptor 提供方快照。
     * @param profile 请求的检测 profile。
     * @param sliced 请求是否需要切片推理（无人机等大图场景）。
     * @return 注册 profile 匹配且切片要求满足时返回 {@code true}。
     */
    public static boolean supports(JSONObject descriptor, String profile, boolean sliced) {
        return contains(descriptor, "profiles", profile)
                && (!sliced || contains(descriptor, "slicedProfiles", profile));
    }

    /**
     * 判断提供方状态报告的就绪租约是否仍然有效。
     *
     * <p>要求 {@code ready} 为 {@code true} 且 {@code statusExpiresAt} 大于当前时间；
     * 后者是最近一次有效报告时间加 30 秒租约，取 0 表示从未报告。重新注册或重连后
     * 需要新的状态报告确认，不能沿用旧的 ready 布尔值。</p>
     *
     * @param descriptor 提供方快照。
     * @return 报告标记就绪且租约未过期时返回 {@code true}。
     */
    public static boolean ready(JSONObject descriptor) {
        return descriptor.optBoolean("ready", false)
                && descriptor.optLong("statusExpiresAt", 0) > System.currentTimeMillis();
    }
}
