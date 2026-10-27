/*
 * This source file is part of Cube.
 * Copyright (c) 2023-2026 Ambrose Xu.
 */
package cube.console.mgmt;

import cube.report.UnitReport;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** 按单元名称归并一次查询中的有效节点快照，不依赖联系人统计库。 */
public final class UnitDeploymentSnapshot {

    public static final long TTL = 5L * 60 * 1000L;

    private final String domain;
    private final long queryTime;
    private long reportTime;
    private boolean truncated;
    private final Map<String, Map<Long, Deployment>> groups = new TreeMap<>();

    public UnitDeploymentSnapshot(String domain, long queryTime) {
        this.domain = domain;
        this.queryTime = queryTime;
    }

    /** receiveTime 使用 console 接收时间；过期报告连同截断标识一起忽略。 */
    public void append(String reporter, UnitReport report, long receiveTime) {
        if (this.queryTime - receiveTime > TTL) {
            return;
        }
        this.reportTime = Math.max(this.reportTime, receiveTime);
        // 截断可能恰好丢掉所选域的全部条目，不能只检查已收到的域。
        this.truncated |= report.isTruncated();
        for (JSONObject item : report.getUnits()) {
            if (!this.domain.equals(item.optString("domain"))) {
                continue;
            }
            if (item.has("connected") && !item.optBoolean("connected")) {
                continue;
            }
            JSONObject capability = item.optJSONObject("capability");
            long id = item.optLong("id", 0);
            if (capability == null || capability.optString("name").isEmpty() || id <= 0) {
                continue;
            }
            String name = capability.getString("name");
            Map<Long, Deployment> deployments = this.groups.get(name);
            if (deployments == null) {
                deployments = new TreeMap<>();
                this.groups.put(name, deployments);
            }
            Deployment deployment = deployments.get(id);
            if (deployment == null) {
                deployment = new Deployment(id);
                deployments.put(id, deployment);
            }
            deployment.append(reporter, item, capability, receiveTime);
        }
    }

    public JSONObject toJSON() {
        JSONArray array = new JSONArray();
        for (Map.Entry<String, Map<Long, Deployment>> group : this.groups.entrySet()) {
            JSONArray deployments = new JSONArray();
            Map<String, JSONObject> capabilities = new TreeMap<>();
            for (Deployment deployment : group.getValue().values()) {
                deployments.put(deployment.toJSON());
                capabilities.putAll(deployment.capabilities);
            }
            array.put(new JSONObject().put("name", group.getKey())
                    .put("deploymentCount", deployments.length())
                    .put("capabilities", new JSONArray(capabilities.values()))
                    .put("deployments", deployments));
        }
        return new JSONObject().put("domain", this.domain).put("queryTime", this.queryTime)
                .put("unitReportTime", this.reportTime).put("truncated", this.truncated)
                .put("groups", array);
    }

    /** JSON 对象的字段顺序不影响能力去重；数组顺序保留原协议含义。 */
    private static String canonical(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            StringBuilder result = new StringBuilder("{");
            for (String key : new TreeSet<>(object.keySet())) {
                result.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))).append(',');
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); ++i) {
                result.append(canonical(array.get(i))).append(',');
            }
            return result.append(']').toString();
        }
        return String.valueOf(JSONObject.valueToString(value));
    }

    private static final class Deployment {
        final long id;
        String name = "";
        String device = "";
        String platform = "";
        long reportedAt;
        final Set<String> reporters = new TreeSet<>();
        final Set<String> addresses = new TreeSet<>();
        final Map<String, JSONObject> capabilities = new TreeMap<>();

        Deployment(long id) {
            this.id = id;
        }

        void append(String reporter, JSONObject item, JSONObject capability, long receiveTime) {
            this.reporters.add(reporter);
            String address = item.optString("address").trim();
            if (!address.isEmpty()) {
                this.addresses.add(address);
            }
            if (receiveTime >= this.reportedAt) {
                this.name = item.optString("name");
                this.device = item.optString("device");
                this.platform = item.optString("platform");
                this.reportedAt = receiveTime;
            }
            this.capabilities.put(canonical(capability), capability);
        }

        JSONObject toJSON() {
            return new JSONObject().put("id", this.id).put("name", this.name)
                    .put("device", this.device).put("platform", this.platform)
                    .put("reportedAt", this.reportedAt).put("reporters", new JSONArray(this.reporters))
                    .put("addresses", new JSONArray(this.addresses))
                    .put("capabilities", new JSONArray(this.capabilities.values()));
        }
    }
}
