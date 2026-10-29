/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.completion;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 进程内结构化推理。
 *
 * <p>无需 dispatcher 连接或 HTTP 会话，直接由宿主进程内的实现完成推理。</p>
 */
public interface CompletionProvider {
    /**
     * Trusted in-process delegation. Old providers must not silently charge a backend account.
     */
    default void submitCompletion(String token, String requestId, JSONObject request, long timeoutMillis,
                                  cube.aigc.usage.UsageContext accounting, Listener listener) {
        throw new UnsupportedOperationException("PROVIDER_USAGE_CONTEXT_REQUIRED");
    }

    interface Listener {
        /**
         * 接收推理事件。
         *
         * <p>事件按 started、delta、completed、error 四类顺序推送；
         * {@code actualFinished} 字段表示本次执行是否真正结束。</p>
         *
         * @param event 事件数据。
         */
        void onEvent(JSONObject event);
    }

    JSONArray completionModels(String token);

    /**
     * 查询容量。
     *
     * <p>返回已授权且健康的兼容单元与当前空闲的推理槽位，与编排 worker 无关。
     * 可选 {@code capacityGroup} 标识一个聚合共享池，其计数描述该池；
     * {@code capacityGroups} 中的条目标识共享预算（含 capacityGroup、route、registered、available、
     * groupCapacity、groupAvailable 等字段）。统计时每条路由只计一次，并按组在模型与后端之间封顶。</p>
     *
     * @param token       访问令牌。
     * @param model       模型标识。
     * @param nativeTools 是否按原生工具调用能力计算。
     * @return 容量信息。
     */
    JSONObject completionCapacity(String token, String model, boolean nativeTools);

    /**
     * 提交一次推理请求。
     *
     * <p>立即返回，实际推理异步进行；请求体使用 {@link CompletionRequest} 的规范表示。</p>
     *
     * @param token         访问令牌。
     * @param requestId     请求 ID 。
     * @param request       请求载荷。
     * @param timeoutMillis 超时时间，单位毫秒。
     * @param listener      事件回调。
     */
    void submitCompletion(String token, String requestId, JSONObject request, long timeoutMillis, Listener listener);
}
