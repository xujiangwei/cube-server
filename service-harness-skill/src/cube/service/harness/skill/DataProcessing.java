/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.protocol.Contract;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 有界且确定性的配置语言；不执行脚本、表达式或网络访问。
 */
public final class DataProcessing {
    /**
     * 允许的过滤操作符集合。
     */
    private static final Set<String> OPERATORS = new HashSet<>(Arrays.asList(
            "eq", "ne", "in", "exists", "gt", "gte", "lt", "lte", "before", "after", "atOrBefore", "atOrAfter"));

    /**
     * 工具类，禁止实例化。
     */
    private DataProcessing() {
    }

    /**
     * 校验数据处理配置。
     *
     * @param config 配置 JSON。
     */
    public static void validate(JSONObject config) {
        try {
            ConnectorConfig.keys(config, "version,profiles", "");
            require("1".equals(config.getString("version")));
            JSONArray profiles = config.getJSONArray("profiles");
            require(profiles.length() > 0 && profiles.length() <= 32);
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject p = profiles.getJSONObject(i);
                ConnectorConfig.keys(p, "id,connectorId,inputSchema,filters,deduplicate,groupBy,aggregates", "");
                require(ids.add(identifier(p.getString("id"))));
                identifier(p.getString("connectorId"));
                RuntimeSupport.toolSchema(p.getJSONObject("inputSchema"));
                p.getBoolean("deduplicate");
                JSONArray filters = p.getJSONArray("filters");
                JSONArray groupBy = p.getJSONArray("groupBy");
                JSONArray aggregates = p.getJSONArray("aggregates");
                require(filters.length() <= 16 && groupBy.length() <= 4 && aggregates.length() <= 8);
                Set<String> fields = new HashSet<>();
                for (int j = 0; j < groupBy.length(); j++) {
                    require(fields.add(identifier(groupBy.getString(j))));
                }
                for (int j = 0; j < filters.length(); j++) {
                    JSONObject filter = filters.getJSONObject(j);
                    ConnectorConfig.keys(filter, "field,operator", "value,parameter");
                    identifier(filter.getString("field"));
                    String operator = filter.getString("operator");
                    require(OPERATORS.contains(operator) && (filter.has("value") != filter.has("parameter")));
                    if (filter.has("parameter")) {
                        String key = identifier(filter.getString("parameter"));
                        require(p.getJSONObject("inputSchema").getJSONObject("properties").has(key));
                    } else {
                        operand(operator, filter.get("value"));
                    }
                }
                Set<String> names = new HashSet<>();
                for (int j = 0; j < aggregates.length(); j++) {
                    JSONObject a = aggregates.getJSONObject(j);
                    ConnectorConfig.keys(a, "name,operation", "field");
                    require(names.add(identifier(a.getString("name"))));
                    String op = a.getString("operation");
                    require(Arrays.asList("count", "sum", "min", "max").contains(op));
                    require(op.equals("count") != a.has("field"));
                    if (a.has("field")) {
                        identifier(a.getString("field"));
                    }
                }
            }
        } catch (RuntimeException bad) {
            throw new StoreException("DATA_PROCESS_CONFIG_INVALID");
        }
    }

    /**
     * 校验并原样返回标识符。
     *
     * @param value 待校验字符串。
     * @return 原标识符。
     */
    private static String identifier(String value) {
        require(value.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}"));
        return value;
    }

    /**
     * 条件不成立即拒绝配置。
     *
     * @param condition 待校验条件。
     */
    private static void require(boolean condition) {
        if (!condition) {
            throw new StoreException("DATA_PROCESS_CONFIG_INVALID");
        }
    }

    /**
     * 判断值是否缺失。
     *
     * @param value 待判断值。
     * @return 为 null 或 JSONObject.NULL 返回 true。
     */
    private static boolean absent(Object value) {
        return value == null || value == JSONObject.NULL;
    }

    /**
     * 把值解析为受控精度的小数。
     *
     * @param value 数值或数字字符串。
     * @return 解析后的小数。
     */
    private static BigDecimal decimal(Object value) {
        try {
            if (!(value instanceof Number || value instanceof String) || value.toString().length() > 128) {
                throw new IllegalArgumentException();
            }
            BigDecimal n = new BigDecimal(value.toString());
            if (n.precision() > 64 || Math.abs((long) n.scale()) > 32) {
                throw new IllegalArgumentException();
            }
            return n;
        } catch (RuntimeException bad) {
            throw new StoreException("DATA_PROCESS_VALUE_INVALID");
        }
    }

    /**
     * 校验并原样返回标量值。
     *
     * @param value 待校验值。
     * @return 原标量值。
     */
    private static Object scalar(Object value) {
        if (!(absent(value) || value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw new StoreException("DATA_PROCESS_VALUE_INVALID");
        }
        return value;
    }

    /**
     * 按操作符校验过滤值类型。
     *
     * @param operator 操作符。
     * @param value    过滤值。
     */
    private static void operand(String operator, Object value) {
        if (operator.equals("in")) {
            if (!(value instanceof JSONArray) || ((JSONArray) value).length() > 64) {
                DataProcessProvider.invalid();
            }
            JSONArray list = (JSONArray) value;
            for (int i = 0; i < list.length(); i++) {
                scalar(list.get(i));
            }
        } else if (operator.equals("exists")) {
            if (!(value instanceof Boolean)) {
                DataProcessProvider.invalid();
            }
        } else if (Arrays.asList("gt", "gte", "lt", "lte").contains(operator)) {
            decimal(value);
        } else if (Arrays.asList("before", "after", "atOrBefore", "atOrAfter").contains(operator)) {
            instant(value);
        } else {
            scalar(value);
        }
    }

    /**
     * 解析时间字符串为时间戳。
     *
     * @param value 时间字符串。
     * @return 时间戳。
     */
    private static Instant instant(Object value) {
        try {
            if (!(value instanceof String) || ((String) value).length() > 64) {
                throw new IllegalArgumentException();
            }
            return OffsetDateTime.parse((String) value).toInstant();
        } catch (RuntimeException invalid) {
            throw new StoreException("DATA_PROCESS_VALUE_INVALID");
        }
    }

    /**
     * 按数值语义比较两个标量。
     *
     * @param a 左值。
     * @param b 右值。
     * @return 相等返回 true。
     */
    private static boolean equal(Object a, Object b) {
        scalar(a);
        scalar(b);
        if (a instanceof Number && b instanceof Number) {
            return decimal(a).compareTo(decimal(b)) == 0;
        }
        return Objects.equals(a, b);
    }

    /**
     * 判断一行是否满足单个过滤条件。
     *
     * @param row      数据行。
     * @param f        过滤定义。
     * @param expected 期望值。
     * @param warnings 警告收集集合。
     * @return 命中返回 true。
     */
    private static boolean matches(JSONObject row, JSONObject f, Object expected, Set<String> warnings) {
        String field = f.getString("field");
        String op = f.getString("operator");
        Object value = row.opt(field);
        if (op.equals("exists")) {
            return (!absent(value)) == (Boolean) expected;
        }
        if (!row.has(field) || absent(value)) {
            warnings.add("MISSING_FILTER_VALUE");
            return false;
        }
        if (op.equals("eq")) {
            return equal(value, expected);
        }
        if (op.equals("ne")) {
            return !equal(value, expected);
        }
        if (op.equals("in")) {
            JSONArray values = (JSONArray) expected;
            for (int i = 0; i < values.length(); i++) {
                if (equal(value, values.get(i))) {
                    return true;
                }
            }
            return false;
        }
        int compared = Arrays.asList("gt", "gte", "lt", "lte").contains(op)
                ? decimal(value).compareTo(decimal(expected)) : instant(value).compareTo(instant(expected));
        if (op.equals("gt") || op.equals("after")) {
            return compared > 0;
        }
        if (op.equals("gte") || op.equals("atOrAfter")) {
            return compared >= 0;
        }
        if (op.equals("lt") || op.equals("before")) {
            return compared < 0;
        }
        return compared <= 0;
    }

    /**
     * 输入页是来自提供方的可信事实，按遍历顺序提供，绝非模型撰写的行。
     *
     * @param profile    处理档案。
     * @param parameters 调用参数。
     * @param pages      输入页数组。
     * @return 处理结果。
     */
    static JSONObject process(JSONObject profile, JSONObject parameters, JSONArray pages) {
        Contract.validate(parameters, profile.getJSONObject("inputSchema"));
        if (pages.length() == 0 || pages.length() > 64) {
            DataProcessProvider.invalid();
        }
        JSONArray filters = profile.getJSONArray("filters");
        List<Object> operands = new ArrayList<>();
        for (int i = 0; i < filters.length(); i++) {
            JSONObject f = filters.getJSONObject(i);
            Object v = f.has("parameter") ? parameters.opt(f.getString("parameter")) : f.get("value");
            if (v == null) {
                DataProcessProvider.invalid();
            }
            operand(f.getString("operator"), v);
            operands.add(v);
        }
        Set<String> warnings = new TreeSet<>();
        Set<String> sourceRefs = new TreeSet<>();
        LinkedHashMap<String, JSONObject> unique = new LinkedHashMap<>();
        Map<String, String> identities = new HashMap<>();
        JSONObject first = pages.getJSONObject(0);
        String query = first.getString("queryHash");
        String snapshot = first.getString("snapshotId");
        boolean complete = first.getString("cursor").isEmpty();
        String previousNext = "";
        int inputCount = 0;
        int filtered = 0;
        int duplicates = 0;
        for (int p = 0; p < pages.length(); p++) {
            JSONObject page = pages.getJSONObject(p);
            if (!page.getString("connectorId").equals(profile.getString("connectorId"))
                    || !page.getString("queryHash").equals(query) || !page.getString("snapshotId").equals(snapshot)) {
                throw new StoreException("DATA_PROCESS_SCOPE_MISMATCH");
            }
            if (p > 0 && (previousNext.isEmpty() || !page.getString("cursor").equals(previousNext))) {
                complete = false;
            }
            previousNext = page.getString("nextCursor");
            if (page.has("sourceRef")) {
                sourceRefs.add(page.getString("sourceRef"));
            }
            JSONArray pageWarnings = page.getJSONArray("warnings");
            for (int i = 0; i < pageWarnings.length(); i++) {
                warnings.add(pageWarnings.getString(i));
            }
            JSONArray rows = page.getJSONArray("records");
            JSONArray media = page.getJSONArray("mediaRefs");
            if (rows.length() != media.length()) {
                throw new StoreException("DATA_PROCESS_SOURCE_INVALID");
            }
            inputCount += rows.length();
            if (inputCount > 1024) {
                throw new StoreException("RESULT_BUDGET_EXCEEDED");
            }
            for (int r = 0; r < rows.length(); r++) {
                JSONObject row = new JSONObject(rows.getJSONObject(r).toString());
                JSONObject origin = new JSONObject(media.getJSONObject(r).toString())
                        .put("pageRef", page.getString("pageRef"))
                        .put("rowIndex", r)
                        .put("timeTrust", page.has("sourceRef") ? "authorized-source" : "provider-declared");
                if (!Objects.equals(row.remove("mediaRef"), origin.getString("mediaRef"))
                        || !row.getString("recordId").equals(origin.getString("recordId"))) {
                    throw new StoreException("DATA_PROCESS_SOURCE_INVALID");
                }
                String key = "row:" + p + ":" + r;
                if (profile.getBoolean("deduplicate")) {
                    if (row.has("recordVersion") && !row.isNull("recordVersion")) {
                        origin.put("recordVersion", row.getString("recordVersion"));
                        key = RuntimeSupport.sha(RuntimeSupport.bytes(new JSONArray()
                                .put(row.getString("recordId"))
                                .put(row.getString("recordVersion"))
                                .put(origin.getString("mediaId"))
                                .put(origin.getString("mediaVersion"))));
                        String fingerprint = RuntimeSupport.sha(RuntimeSupport.bytes(new JSONObject()
                                .put("record", row)
                                .put("mediaType", origin.getString("mediaType"))));
                        String previous = identities.put(key, fingerprint);
                        if (previous != null && !previous.equals(fingerprint)) {
                            throw new StoreException("DATA_PROCESS_RECORD_CONFLICT");
                        }
                    } else {
                        warnings.add("MISSING_RECORD_VERSION_NOT_DEDUPLICATED");
                    }
                }
                boolean keep = true;
                for (int f = 0; f < filters.length(); f++) {
                    if (!matches(row, filters.getJSONObject(f), operands.get(f), warnings)) {
                        keep = false;
                        break;
                    }
                }
                if (!keep) {
                    filtered++;
                    continue;
                }
                JSONObject old = unique.get(key);
                if (old != null) {
                    if (!Arrays.equals(RuntimeSupport.bytes(old.getJSONObject("record")), RuntimeSupport.bytes(row))) {
                        throw new StoreException("DATA_PROCESS_RECORD_CONFLICT");
                    }
                    old.getJSONArray("sources").put(origin);
                    duplicates++;
                } else {
                    unique.put(key, new JSONObject()
                            .put("record", row)
                            .put("sources", new JSONArray().put(origin)));
                }
            }
        }
        complete &= previousNext.isEmpty();
        if (!complete) {
            warnings.add("QUERY_PAGES_INCOMPLETE");
        }
        JSONArray records = new JSONArray();
        for (JSONObject row : unique.values()) {
            records.put(row);
        }
        JSONArray groups = aggregate(profile, records, warnings);
        return new JSONObject()
                .put("records", records)
                .put("groups", groups)
                .put("sourceRefs", new JSONArray(sourceRefs))
                .put("coverage", new JSONObject()
                        .put("queryComplete", complete)
                        .put("queryHash", query)
                        .put("snapshotId", snapshot)
                        .put("includedPages", pages.length()))
                .put("stats", new JSONObject()
                        .put("inputRecords", inputCount)
                        .put("filteredRecords", filtered)
                        .put("duplicateRecords", duplicates)
                        .put("outputRecords", records.length())
                        .put("groups", groups.length()))
                .put("warnings", new JSONArray(warnings));
    }

    /**
     * 按 groupBy 对记录分组并计算聚合值。
     *
     * @param profile  处理档案。
     * @param records  去重后的记录。
     * @param warnings 警告收集集合。
     * @return 分组结果数组。
     */
    private static JSONArray aggregate(JSONObject profile, JSONArray records, Set<String> warnings) {
        JSONArray fields = profile.getJSONArray("groupBy");
        JSONArray specs = profile.getJSONArray("aggregates");
        Map<String, JSONObject> groups = new TreeMap<>();
        if (fields.length() == 0) {
            groups.put("{}", new JSONObject()
                    .put("key", new JSONObject())
                    .put("recordIndexes", new JSONArray()));
        }
        for (int i = 0; i < records.length(); i++) {
            JSONObject row = records.getJSONObject(i).getJSONObject("record");
            JSONObject key = new JSONObject();
            for (int f = 0; f < fields.length(); f++) {
                String field = fields.getString(f);
                Object value = row.opt(field);
                if (absent(value)) {
                    warnings.add("MISSING_GROUP_VALUE");
                }
                key.put(field, value == null ? JSONObject.NULL : scalar(value));
            }
            String hash = new String(RuntimeSupport.bytes(key), StandardCharsets.UTF_8);
            JSONObject group = groups.get(hash);
            if (group == null) {
                group = new JSONObject()
                        .put("key", key)
                        .put("recordIndexes", new JSONArray());
                groups.put(hash, group);
            }
            if (groups.size() > 128) {
                throw new StoreException("RESULT_BUDGET_EXCEEDED");
            }
            group.getJSONArray("recordIndexes").put(i);
        }
        JSONArray result = new JSONArray();
        for (JSONObject group : groups.values()) {
            JSONObject values = new JSONObject();
            JSONObject missing = new JSONObject();
            JSONArray indexes = group.getJSONArray("recordIndexes");
            for (int s = 0; s < specs.length(); s++) {
                JSONObject spec = specs.getJSONObject(s);
                String op = spec.getString("operation");
                String name = spec.getString("name");
                if (op.equals("count")) {
                    values.put(name, indexes.length());
                    continue;
                }
                BigDecimal value = null;
                int skipped = 0;
                for (int i = 0; i < indexes.length(); i++) {
                    Object raw = records.getJSONObject(indexes.getInt(i)).getJSONObject("record").opt(spec.getString("field"));
                    if (absent(raw)) {
                        skipped++;
                        continue;
                    }
                    BigDecimal n = decimal(raw);
                    value = value == null ? n : op.equals("sum") ? value.add(n)
                            : op.equals("min") ? value.min(n) : value.max(n);
                }
                values.put(name, value == null ? JSONObject.NULL : value.stripTrailingZeros().toPlainString());
                missing.put(name, skipped);
                if (skipped > 0) {
                    warnings.add("MISSING_AGGREGATE_VALUE");
                }
            }
            result.put(group.put("values", values).put("missingValues", missing));
        }
        return result;
    }
}
