/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.runtime.RuntimeSupport;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从原始用户消息构建带类型的检索任务，绝不使用模型撰写的参数值。
 */
final class RetrievalPlanner {
    /**
     * 引号包裹片段的正则。
     */
    private static final Pattern QUOTED = Pattern.compile("[“\"]([^”\"]{1,256})[”\"]");

    /**
     * 检索不支持的操作词正则。
     */
    private static final Pattern UNSUPPORTED = Pattern.compile("分析|识别|统计|剪辑|删除|修改文件|下载|播放|跟踪|告警|排除|除了|不要|不包含");

    /**
     * 内置资源类型词表。
     */
    private static final String[] BUILTIN_TYPES = {"录像", "视频", "图片", "照片", "文档", "文件", "音频", "录音"};

    /**
     * 需要从语句中剔除的噪声词。
     */
    private static final String NOISE = "请帮我|麻烦帮我|只做检索|只检索|仅检索|调出来|调出|调取|查找|查询|检索|找出|搜索|查看|列出|获取|给我|帮我|找一下|看一下|一下|其他不变|其它不变|其余不变|改成|改为|调整为|保持|按以下回答继续|关键词是|关键字是|关键词|关键字|关于|有关|明确范围|只要|所有|全部|以及|还有|并且|时间范围|区域|范围|从|到|至|在|的|内|里|中|和|及|请|把|将|是";

    /**
     * 构造一个澄清问题。
     *
     * @param id      问题标识。
     * @param prompt  问题文本。
     * @param options 可选项。
     * @return 问题对象。
     */
    static JSONObject question(String id, String prompt, JSONArray options) {
        return new JSONObject()
                .put("questionId", "retrieval." + id)
                .put("prompt", prompt)
                .put("options", options)
                .put("allowFreeText", true);
    }

    /**
     * 构造一个澄清选项。
     *
     * @param id    选项标识。
     * @param label 选项文本。
     * @return 选项对象。
     */
    static JSONObject option(String id, String label) {
        return new JSONObject()
                .put("id", id)
                .put("label", Budget.prefix(label, 120));
    }

    /**
     * 记录一个待澄清问题。
     *
     * @param issues   问题列表。
     * @param questions 澄清问题列表。
     * @param id       问题标识。
     * @param prompt   问题文本。
     * @param options  可选项。
     */
    static void issue(JSONArray issues, JSONArray questions, String id, String prompt, JSONArray options) {
        if (issues.length() >= 3) {
            return;
        }
        issues.put(new JSONObject()
                .put("field", id)
                .put("code", "RETRIEVAL_CLARIFICATION_REQUIRED")
                .put("message", prompt));
        questions.put(question(id, prompt, options));
    }

    /**
     * 收集名称及其别名。
     *
     * @param o    名称对象。
     * @param name 名称字段名。
     * @return 按长度降序的名称列表。
     */
    private static List<String> names(JSONObject o, String name) {
        List<String> result = new ArrayList<>();
        result.add(o.getString(name));
        if (o.has("aliases")) {
            for (Object a : o.getJSONArray("aliases")) {
                result.add(a.toString());
            }
        }
        result.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return result;
    }

    /**
     * 判断命中位置是否落在词边界内。
     *
     * @param text  原文。
     * @param begin 起始位置。
     * @param end   结束位置。
     * @return 在边界内返回 true。
     */
    private static boolean boundary(String text, int begin, int end) {
        return !(begin > 0 && ascii(text.charAt(begin)) && ascii(text.charAt(begin - 1)))
                && !(end < text.length() && ascii(text.charAt(end - 1)) && ascii(text.charAt(end)));
    }

    /**
     * 判断字符是否为 ASCII 词字符。
     *
     * @param c 字符。
     * @return 是 ASCII 词字符返回 true。
     */
    private static boolean ascii(char c) {
        return c < 128 && (Character.isLetterOrDigit(c) || c == '_' || c == '-');
    }

    /**
     * 找出名称在文本中的全部词边界命中区间。
     *
     * @param text 原文。
     * @param name 名称。
     * @return 命中区间列表。
     */
    private static List<int[]> occurrences(String text, String name) {
        List<int[]> result = new ArrayList<>();
        String n = RetrievalConfig.normal(name);
        for (int i = 0; (i = text.indexOf(n, i)) >= 0; i += Math.max(1, n.length())) {
            if (boundary(text, i, i + n.length())) {
                result.add(new int[]{i, i + n.length()});
            }
        }
        return result;
    }

    /**
     * 把指定区间用空格遮蔽。
     *
     * @param text  原文。
     * @param start 起始位置。
     * @param end   结束位置。
     * @return 遮蔽后的文本。
     */
    private static String blank(String text, int start, int end) {
        StringBuilder b = new StringBuilder(text);
        for (int i = start; i < end; i++) {
            b.setCharAt(i, ' ');
        }
        return b.toString();
    }

    /**
     * 名称命中集合及其剩余文本。
     */
    private static final class Matches {
        /**
         * 命中的条目标识。
         */
        final Set<String> ids = new LinkedHashSet<>();

        /**
         * 剔除命中片段后的剩余文本。
         */
        String remainder;

        /**
         * 在文本中匹配全部候选名称。
         *
         * @param text    文本。
         * @param entries 候选条目数组。
         * @param name    名称字段名。
         */
        Matches(String text, JSONArray entries, String name) {
            remainder = text;
            List<Object[]> found = new ArrayList<>();
            for (Object entry : entries) {
                JSONObject object = (JSONObject) entry;
                for (String alias : names(object, name)) {
                    for (int[] span : occurrences(text, alias)) {
                        found.add(new Object[]{object.getString("id"), span[0], span[1]});
                    }
                }
            }
            found.sort((a, b) -> Integer.compare((Integer) b[2] - (Integer) b[1], (Integer) a[2] - (Integer) a[1]));
            List<int[]> kept = new ArrayList<>();
            for (Object[] hit : found) {
                int start = (Integer) hit[1];
                int end = (Integer) hit[2];
                boolean contained = false;
                for (int[] prior : kept) {
                    if (start >= prior[0] && end <= prior[1] && (start != prior[0] || end != prior[1])) {
                        contained = true;
                    }
                }
                if (contained) {
                    continue;
                }
                ids.add((String) hit[0]);
                kept.add(new int[]{start, end});
                remainder = blank(remainder, start, end);
            }
        }
    }

    /**
     * 从原始用户消息构建带类型的检索任务。
     *
     * @param config     检索配置。
     * @param regions    区域条目数组。
     * @param input      原始输入。
     * @param selections 用户已确认的选择。
     * @param now        当前时刻。
     * @return 检索任务与待澄清问题。
     */
    static JSONObject plan(JSONObject config, JSONArray regions, JSONObject input, JSONArray selections, Instant now) {
        RetrievalConfig.validate(config);
        RetrievalConfig.catalog(regions);
        JSONArray profiles = config.getJSONArray("profiles");
        JSONArray messages = input.getJSONArray("messages");
        RetrievalTime time = new RetrievalTime(config.getString("zone"));
        Set<String> profileIds = new LinkedHashSet<>();
        Set<String> regionIds = new LinkedHashSet<>();
        Map<String, String> filters = new TreeMap<>();
        String keywords = "";
        String unparsed = "";
        String intentIssue = "";
        boolean explicitType = false;
        List<String> remainders = new ArrayList<>();
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.getJSONObject(index);
            String original = Normalizer.normalize(message.getJSONObject("input").getString("instruction"), Normalizer.Form.NFKC).trim();
            String text = RetrievalConfig.normal(original);
            JSONObject selection = index < selections.length() ? selections.optJSONObject(index) : null;
            if (selection != null && selection.optBoolean("resetScope")) {
                unparsed = "";
                remainders.clear();
                intentIssue = "";
                filters.clear();
                keywords = "";
                profileIds.clear();
                regionIds.clear();
                explicitType = false;
                time = new RetrievalTime(config.getString("zone"));
            }
            Matcher quoted = QUOTED.matcher(original);
            List<String> terms = new ArrayList<>();
            String masked = original;
            while (quoted.find()) {
                terms.add(quoted.group(1));
                masked = blank(masked, quoted.start(), quoted.end());
            }
            masked = RetrievalConfig.normal(masked);
            if (masked.contains("只检索") || masked.contains("仅检索") || masked.contains("只做检索")) {
                intentIssue = "";
            }
            Matcher operation = UNSUPPORTED.matcher(masked);
            if (operation.find()) {
                intentIssue = "当前检索工具不能执行“" + operation.group() + "”。请重新提供完整检索条件，只包含已提供的检索能力。";
            }
            if (!terms.isEmpty()) {
                keywords = String.join(" ", terms);
                if (keywords.length() > 256) {
                    unparsed = "关键词过长，请缩小至256字符以内。";
                }
            }
            if (masked.contains("取消关键词") || masked.contains("清除关键词")) {
                keywords = "";
                masked = masked.replace("取消关键词", "").replace("清除关键词", "");
            }
            String timed = time.apply(masked, message.getLong("receivedAt"));
            Matches types = new Matches(timed, profiles, "label");
            if (!types.ids.isEmpty()) {
                profileIds = types.ids;
                explicitType = true;
            } else {
                for (String type : BUILTIN_TYPES) {
                    if (timed.contains(type)) {
                        profileIds.clear();
                        explicitType = true;
                    }
                }
            }
            Matches locations = new Matches(types.remainder, regions, "name");
            if (!locations.ids.isEmpty()) {
                regionIds = locations.ids;
            }
            masked = locations.remainder;
            // 已知但不可用的类型由显式档案问题消解。
            if (types.ids.isEmpty()) {
                for (String type : BUILTIN_TYPES) {
                    masked = masked.replace(type, "");
                }
            }
            for (Object entry : profiles) {
                JSONObject p = (JSONObject) entry;
                JSONArray fs = p.optJSONArray("filters");
                if (fs == null) {
                    continue;
                }
                for (Object f : fs) {
                    JSONObject filter = (JSONObject) f;
                    Matches values = new Matches(masked, filter.getJSONArray("values"), "label");
                    if (!values.ids.isEmpty()) {
                        filters.put(filter.getString("id"), values.ids.size() == 1 ? values.ids.iterator().next() : "!");
                        masked = values.remainder;
                    }
                }
            }
            if (selection != null) {
                if (selection.has("profileId")) {
                    profileIds = new LinkedHashSet<>(Collections.singleton(selection.getString("profileId")));
                    explicitType = true;
                }
                if (selection.has("regionId")) {
                    regionIds = new LinkedHashSet<>(Collections.singleton(selection.getString("regionId")));
                }
                JSONObject selectedFilters = selection.optJSONObject("filters");
                if (selectedFilters != null) {
                    for (String key : selectedFilters.keySet()) {
                        filters.put(key, selectedFilters.getString(key));
                    }
                }
            }
            // 业务词表只描述路由，不能引入新的谓词或代码。
            for (Object entry : profiles) {
                JSONArray words = ((JSONObject) entry).optJSONArray("vocabulary");
                if (words != null) {
                    for (Object word : words) {
                        masked = masked.replace(RetrievalConfig.normal(word.toString()), "");
                    }
                }
            }
            masked = masked.replaceAll(NOISE, " ").replaceAll("[\\s\\p{Punct}，。！？；：、（）【】～—]+", "");
            if (!masked.isEmpty()) {
                remainders.add(masked);
            }
        }
        if (profileIds.isEmpty() && !explicitType && profiles.length() == 1) {
            profileIds.add(profiles.getJSONObject(0).getString("id"));
        }
        JSONObject task = new JSONObject()
                .put("schemaVersion", 1)
                .put("intent", "retrieve")
                .put("inputRevision", input.getLong("inputRevision"))
                .put("inputDigest", RuntimeSupport.sha(RuntimeSupport.bytes(input)))
                .put("keywords", keywords)
                .put("filters", new JSONObject());
        JSONArray issues = new JSONArray();
        JSONArray questions = new JSONArray();
        JSONArray sourceRefs = new JSONArray();
        JSONObject regionConfig = config.getJSONObject("regions");
        if (regionConfig.getString("kind").equals("http")) {
            sourceRefs.put(regionConfig.getString("sourceRef"));
        }
        JSONObject profile = null;
        if (profileIds.size() != 1) {
            JSONArray options = new JSONArray();
            for (Object entry : profiles) {
                JSONObject p = (JSONObject) entry;
                options.put(option(p.getString("id"), p.getString("label")));
            }
            issue(issues, questions, "profile", "请选择一种已配置的检索资源类型。", options);
        } else {
            profile = RetrievalConfig.profile(config, profileIds.iterator().next());
            sourceRefs.put(profile.getString("sourceRef"));
            task.put("profileId", profile.getString("id")).put("resourceType", profile.getString("resourceType"));
            JSONObject interval = time.resolve(profile.getJSONObject("time"), now);
            if (interval != null) {
                task.put("time", interval);
            }
            if (!time.issue.isEmpty()) {
                issue(issues, questions, "time", time.issue, new JSONArray());
            }
            if (regionIds.size() > 1 || profile.getBoolean("regionRequired") && regionIds.isEmpty()) {
                JSONArray options = new JSONArray();
                for (Object entry : regions) {
                    JSONObject r = (JSONObject) entry;
                    if (regionIds.isEmpty() || regionIds.contains(r.getString("id"))) {
                        options.put(option(r.getString("id"), r.getString("name") + " (" + r.getString("id") + ")"));
                    }
                }
                if (options.length() > 8) {
                    options = new JSONArray();
                }
                issue(issues, questions, "region", regionIds.size() > 1 ? "区域名称匹配多个业务区域，请选择确切区域。" : "请提供要检索的业务区域。", options);
            } else if (regionIds.size() == 1) {
                String selected = regionIds.iterator().next();
                JSONObject region = null;
                for (Object entry : regions) {
                    if (((JSONObject) entry).getString("id").equals(selected)) {
                        region = (JSONObject) entry;
                    }
                }
                if (region == null) {
                    issue(issues, questions, "region", "该区域当前不可用，请重新选择获准区域。", new JSONArray());
                } else {
                    task.put("region", new JSONObject()
                            .put("id", selected)
                            .put("name", region.getString("name"))
                            .put("scope", profile.getString("regionScope"))
                            .put("allowedIds", new JSONArray(RetrievalConfig.descendants(regions, selected, profile.getString("regionScope").equals("subtree")))));
                }
            }
            Set<String> mapped = new HashSet<>();
            for (Object value : profile.getJSONObject("request").getJSONObject("query").toMap().values()) {
                mapped.add(value.toString());
            }
            if (!keywords.isEmpty() && !mapped.contains("${args.query}")) {
                unparsed = "该检索档案不支持关键词筛选，不能忽略这个条件。";
            }
            if (regionIds.size() == 1 && !mapped.contains("${args.regionId}")) {
                unparsed = "该检索档案不支持区域筛选，不能忽略这个条件。";
            }
            JSONArray fs = profile.optJSONArray("filters");
            Set<String> allowedFilters = new HashSet<>();
            if (fs != null) {
                for (Object f : fs) {
                    JSONObject filter = (JSONObject) f;
                    String fid = filter.getString("id");
                    allowedFilters.add(fid);
                    String selected = filters.get(fid);
                    if (selected == null && !filter.getBoolean("required")) {
                        continue;
                    }
                    JSONObject chosen = null;
                    JSONArray options = new JSONArray();
                    for (Object v : filter.getJSONArray("values")) {
                        JSONObject value = (JSONObject) v;
                        options.put(option(value.getString("id"), value.getString("label")));
                        if (value.getString("id").equals(selected)) {
                            chosen = value;
                        }
                    }
                    if (chosen == null) {
                        issue(issues, questions, "filter_" + fid, "请选择“" + filter.getString("label") + "”的值。", options.length() <= 8 ? options : new JSONArray());
                    } else if (!mapped.contains("${args.filter_" + fid + "}")) {
                        unparsed = "检索档案未映射筛选参数：" + fid;
                    } else {
                        task.getJSONObject("filters").put(fid, selected);
                    }
                }
            }
            for (String fid : filters.keySet()) {
                if (!allowedFilters.contains(fid)) {
                    unparsed = "当前资源类型不支持此前的筛选条件，请重新明确完整检索范围。";
                }
            }
        }
        if (!intentIssue.isEmpty()) {
            issue(issues, questions, "scope", intentIssue, new JSONArray());
        } else {
            if (unparsed.isEmpty() && !remainders.isEmpty()) {
                unparsed = "以下条件尚不能可靠解析：" + Budget.prefix(String.join("；", remainders), 160) + "。请用明确的日期、时段、区域、资源类型及带引号的关键词重新描述。";
            }
            if (!unparsed.isEmpty()) {
                issue(issues, questions, "scope", unparsed, new JSONArray());
            }
        }
        return new JSONObject()
                .put("status", issues.length() == 0 ? "ready" : "needs_input")
                .put("task", task)
                .put("issues", issues)
                .put("questions", questions)
                .put("question", issues.length() == 0 ? "检索范围已校验。" : "请补充或确认检索条件。")
                .put("sourceRefs", sourceRefs);
    }
}
