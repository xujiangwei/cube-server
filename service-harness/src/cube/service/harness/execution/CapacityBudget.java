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
 * One snapshot's allocation of shared inference routes, including aliases and different backend IDs.
 */
final class CapacityBudget {

    private final Map<String, Integer> total = new HashMap<>(), free = new HashMap<>();

    private final Set<String> counted = new HashSet<>(), available = new HashSet<>();

    static JSONArray entries(JSONObject capacity) {
        JSONArray entries = capacity.optJSONArray("capacityGroups");
        if (entries != null || !capacity.has("capacityGroup")) return entries;
        // Providers without per-route detail may identify one aggregate shared pool.
        // Stable slot identities deduplicate that pool across models and backend IDs.
        entries = new JSONArray();
        int registered = Math.max(0, capacity.getInt("registered"));
        int available = Math.max(0, Math.min(registered, capacity.getInt("available")));
        String group = capacity.getString("capacityGroup");
        for (int i = 0; i < registered; i++)
            entries.put(new JSONObject().put("capacityGroup", group)
                    .put("route", "slot:" + i).put("registered", 1).put("available", i < available ? 1 : 0)
                    .put("groupCapacity", registered).put("groupAvailable", available));
        return entries;
    }

    void observe(JSONArray entries) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String group = entry.getString("capacityGroup");
            total.merge(group, Math.max(0, entry.getInt("groupCapacity")), Math::min);
            free.merge(group, Math.max(0, entry.getInt("groupAvailable")), Math::min);
        }
    }

    int claim(JSONArray entries, boolean idle, int limit) {
        Map<String, Integer> remaining = idle ? free : total;
        Set<String> routes = idle ? available : counted;
        int result = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String group = entry.getString("capacityGroup"), route = group + "/" + entry.getString("route");
            if (result >= limit || routes.contains(route) || remaining.get(group) <= 0
                    || entry.getInt(idle ? "available" : "registered") <= 0) continue;
            if (idle) {
                // Every available route also owns a registered slot on this backend. Reserve
                // these first, before busy aliases elsewhere can claim the same group budget.
                if (counted.contains(route) || total.get(group) <= 0 || entry.getInt("registered") <= 0) continue;
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
