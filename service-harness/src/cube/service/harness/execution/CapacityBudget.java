/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 单次容量快照内共享推理路由的分配账本：按 capacityGroup 汇总跨别名与不同 backendId 的路由。
 *
 * <p>容量条目来自后端上报的 capacityGroups；对只报告单一聚合池（仅有 capacityGroup 聚合键、
 * 无逐路由明细）的 provider，{@link #entries} 会按 registered 数量合成稳定的 slot 路由身份，
 * 使同一池子跨模型/backendId 别名只被计算一次。
 *
 * <p>并发不变量：total/free 按 group 记录剩余额度，counted/available 记录已认领的
 * group/route 复合键；同一路由在两个账本中各自最多认领一次，额度扣减与认领同步完成。
 * 本类非线程安全，由 ModelDispatcher.capacity 的快照构建单线程使用。
 */
final class CapacityBudget {

    /**
     * 各 capacityGroup 的已注册路由总量（跨条目取最小值）；非空闲认领的剩余账本。
     */
    private final Map<String, Integer> total = new HashMap<>();

    /**
     * 各 capacityGroup 的可用路由数量（跨条目取最小值）；空闲认领的剩余账本。
     */
    private final Map<String, Integer> free = new HashMap<>();

    /**
     * 已占用 registered 份额的 group/route 复合键集合，与 total 账本对应。
     */
    private final Set<String> counted = new HashSet<>();

    /**
     * 已占用 available 份额的 group/route 复合键集合，与 free 账本对应。
     */
    private final Set<String> available = new HashSet<>();

    /**
     * 从后端容量描述符提取容量组条目数组；无逐路由明细时按注册量合成稳定 slot 条目。
     *
     * <p>合成条目每槽 registered=1，前 available 个槽 available=1；route 取 "slot:" + 序号，
     * 身份稳定，可在别名间去重合并同一聚合池。
     *
     * @param capacity 后端容量描述符，含 capacityGroups 数组或 capacityGroup/registered/available 聚合字段
     * @return 容量组条目数组；两者都不存在时返回 null
     */
    static JSONArray entries(JSONObject capacity) {
        JSONArray entries = capacity.optJSONArray("capacityGroups");
        if (entries != null || !capacity.has("capacityGroup")) {
            return entries;
        }
        // 无逐路由明细的 provider 只标识一个聚合共享池；稳定的 slot 身份使该池子
        // 跨模型与 backendId 别名去重后只计入一次。
        entries = new JSONArray();
        int registered = Math.max(0, capacity.getInt("registered"));
        int available = Math.max(0, Math.min(registered, capacity.getInt("available")));
        String group = capacity.getString("capacityGroup");
        for (int i = 0; i < registered; i++) {
            entries.put(new JSONObject().put("capacityGroup", group)
                    .put("route", "slot:" + i).put("registered", 1).put("available", i < available ? 1 : 0)
                    .put("groupCapacity", registered).put("groupAvailable", available));
        }
        return entries;
    }

    /**
     * 观察一组容量条目，按 group 合并注册/可用额度；多条目间取最小值（最保守值）。
     *
     * @param entries 容量组条目数组，每项含 capacityGroup、groupCapacity、groupAvailable
     */
    void observe(JSONArray entries) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String group = entry.getString("capacityGroup");
            total.merge(group, Math.max(0, entry.getInt("groupCapacity")), Math::min);
            free.merge(group, Math.max(0, entry.getInt("groupAvailable")), Math::min);
        }
    }

    /**
     * 从容量条目中认领最多 limit 条路由；空闲认领会同步预留对应的 registered 份额。
     *
     * <p>idle=true 认领空闲（available）路由：每条空闲路由同时占用该 backend 的一个 registered
     * 份额，先经 counted/total 预留，防止别处忙别名抢先消耗同一 group 预算；idle=false 仅认领
     * registered 份额。group/route 复合键去重，额度耗尽或达到 limit 即停止。
     *
     * @param entries 容量组条目数组
     * @param idle    true 认领空闲路由并预留 registered 份额；false 仅认领 registered 份额
     * @param limit   本次认领的最大路由数
     * @return 实际认领的路由数量
     */
    int claim(JSONArray entries, boolean idle, int limit) {
        Map<String, Integer> remaining = idle ? free : total;
        Set<String> routes = idle ? available : counted;
        int result = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String group = entry.getString("capacityGroup"), route = group + "/" + entry.getString("route");
            if (result >= limit || routes.contains(route) || remaining.get(group) <= 0
                    || entry.getInt(idle ? "available" : "registered") <= 0) {
                continue;
            }
            if (idle) {
                // 每条空闲路由同时拥有该 backend 的一个 registered 份额；先预留这些份额，
                // 避免别处忙别名抢先认领同一 group 的预算。
                if (counted.contains(route) || total.get(group) <= 0 || entry.getInt("registered") <= 0) {
                    continue;
                }
                counted.add(route);
                total.put(group, total.get(group) - 1);
            }
            routes.add(route);
            remaining.put(group, remaining.get(group) - 1);
            result++;
        }
        return result;
    }
}
