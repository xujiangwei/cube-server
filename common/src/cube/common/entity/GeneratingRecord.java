/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.util.Utils;
import cube.aigc.Usage;
import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * AIGC 内容生成记录。
 */
public class GeneratingRecord implements JSONable {

    /**
     * 记录序列号。
     *
     * <p>同一次生成请求的唯一标识，客户端据此关联异步结果与反馈。</p>
     */
    public final long sn;

    /**
     * 用户提问原文。
     */
    public String query;

    /**
     * 模型回答内容。
     */
    public String answer;

    /**
     * 模型的思考过程。
     *
     * <p>仅推理类模型会填充，普通模型为空字符串。</p>
     */
    public String thought = "";

    /**
     * 结构化结果载荷。
     *
     * <p>由具体单元定义，用于承载除纯文本回答以外的结果，例如图表、评分。</p>
     */
    public JSONObject resultPayload;

    /**
     * 提问携带的文件标签。
     */
    public List<FileLabel> queryFileLabels;

    /**
     * 提问的附加文本片段。
     *
     * <p>客户端以附件方式补充的文本，与 {@link #query} 一起构成完整提问。</p>
     */
    public List<String> queryAdditions;

    /**
     * 回答产生的文件标签。
     *
     * <p>模型输出文件时（例如生成长图、文档）在此登记产物。</p>
     */
    public List<FileLabel> answerFileLabels;

    /**
     * 生成该记录的单元名称。
     *
     * <p>用于追溯是哪个 AI 单元产出了这条回答。</p>
     */
    public String unit;

    /**
     * 记录生成时间。
     *
     * <p>时间戳，单位毫秒。</p>
     */
    public long timestamp;

    /**
     * 本次生成的 token 用量。
     *
     * <p>可能为 {@code null} ，取决于单元是否上报用量。</p>
     */
    public Usage usage;

    /**
     * 结构化上下文。
     *
     * <p>携带本轮对话的业务上下文，可能为 {@code null} 。</p>
     */
    public ComplexContext context;

    /**
     * 用户反馈值。
     *
     * <p>0 表示未反馈，1 表示差评。带差评的记录不会被选入对话上下文。</p>
     */
    public int feedback = 0;

    /**
     * 仅用于客户端向服务器以附件方式传递数据。
     *
     * @param queryAdditions 询问的附加内容。
     */
    public GeneratingRecord(String[] queryAdditions) {
        this.sn = Utils.generateSerialNumber();
        this.queryAdditions = Arrays.asList(queryAdditions);
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 仅用于客户端向服务器以附件方式传递数据。
     *
     * @param queryFileLabels 询问附加的文件清单。
     */
    public GeneratingRecord(List<FileLabel> queryFileLabels) {
        this.sn = Utils.generateSerialNumber();
        this.queryFileLabels = queryFileLabels;
        this.timestamp = System.currentTimeMillis();
    }

    public GeneratingRecord(String unit, String query, String answer) {
        this.sn = Utils.generateSerialNumber();
        this.unit = unit;
        this.query = query;
        this.answer = answer;
        this.timestamp = System.currentTimeMillis();
    }

    public GeneratingRecord(long sn, String unit, String query,
                            String answer, String thought, JSONObject resultPayload,
                            long timestamp, ComplexContext context) {
        this.sn = sn;
        this.unit = unit;
        this.query = query;
        this.answer = answer;
        this.thought = thought;
        this.resultPayload = resultPayload;
        this.timestamp = timestamp;
        this.context = context;
    }

    public GeneratingRecord(long sn, String unit, String query, FileLabel answerFileLabel, long timestamp) {
        this.sn = sn;
        this.unit = unit;
        this.query = query;
        this.answerFileLabels = new ArrayList<>();
        this.answerFileLabels.add(answerFileLabel);
        this.timestamp = timestamp;
    }

    public GeneratingRecord(String query) {
        this.sn = Utils.generateSerialNumber();
        this.query = query;
        this.timestamp = System.currentTimeMillis();
    }

    public GeneratingRecord(String query, FileLabel queryFileLabel) {
        this.sn = Utils.generateSerialNumber();
        this.query = query;
        this.queryFileLabels = new ArrayList<>();
        this.queryFileLabels.add(queryFileLabel);
        this.timestamp = System.currentTimeMillis();
    }

    public GeneratingRecord(long sn, String unit, String query, String answer, String thought) {
        this.sn = sn;
        this.unit = unit;
        this.query = query;
        this.answer = answer;
        this.thought = thought;
        this.timestamp = System.currentTimeMillis();
    }

    public GeneratingRecord(JSONObject json) {
        if (json.has("sn")) {
            this.sn = json.getLong("sn");
        }
        else {
            this.sn = Utils.generateSerialNumber();
        }

        if (json.has("unit")) {
            this.unit = json.getString("unit");
        }

        if (json.has("answer")) {
            this.answer = json.getString("answer");
        }

        if (json.has("query")) {
            this.query = json.getString("query");
        }

        if (json.has("thought")) {
            this.thought = json.getString("thought");
        }

        if (json.has("resultPayload")) {
            this.resultPayload = json.getJSONObject("resultPayload");
        }

        if (json.has("answerFileLabels")) {
            this.answerFileLabels = new ArrayList<>();
            JSONArray array = json.getJSONArray("answerFileLabels");
            for (int i = 0; i < array.length(); ++i) {
                FileLabel fileLabel = new FileLabel(array.getJSONObject(i));
                this.answerFileLabels.add(fileLabel);
            }
        }

        if (json.has("queryAdditions")) {
            JSONArray array = json.getJSONArray("queryAdditions");
            this.queryAdditions = new ArrayList<>();
            for (int i = 0; i < array.length(); ++i) {
                String text = array.getString(i);
                this.queryAdditions.add(text);
            }
        }

        if (json.has("queryFileLabels")) {
            this.queryFileLabels = new ArrayList<>();
            JSONArray array = json.getJSONArray("queryFileLabels");
            for (int i = 0; i < array.length(); ++i) {
                FileLabel fileLabel = new FileLabel(array.getJSONObject(i));
                this.queryFileLabels.add(fileLabel);
            }
        }

        if (json.has("timestamp")) {
            this.timestamp = json.getLong("timestamp");
        }
        else {
            this.timestamp = System.currentTimeMillis();
        }

        if (json.has("context")) {
            this.context = new ComplexContext(json.getJSONObject("context"));
        }
    }

    /**
     * 判断是否存在附加提问片段。
     *
     * @return 存在非空的附加片段时返回 {@code true} 。
     */
    public boolean hasQueryAddition() {
        return (null != this.queryAdditions && !this.queryAdditions.isEmpty());
    }

    /**
     * 判断提问是否携带文件。
     *
     * @return 提问文件标签非空时返回 {@code true} 。
     */
    public boolean hasQueryFile() {
        return (null != this.queryFileLabels && !this.queryFileLabels.isEmpty());
    }

    /**
     * 统计问答总字数。
     *
     * <p>用于筛选过长记录，避免挤占对话上下文窗口。</p>
     *
     * @return 提问字数与回答字数之和；提问为空时返回 0 。
     */
    public int totalWords() {
        if (null == this.query) {
            return 0;
        }

        return this.query.length() + ((null != this.answer) ? this.answer.length() : 0);
    }

    /**
     * 追加一个回答文件标签。
     *
     * <p>首次调用时惰性创建列表。</p>
     *
     * @param fileLabel 产物文件标签。
     */
    public void addAnswerFileLabel(FileLabel fileLabel) {
        if (null == this.answerFileLabels) {
            this.answerFileLabels = new ArrayList<>();
        }
        this.answerFileLabels.add(fileLabel);
    }

    /**
     * 将回答文件标签序列化为 JSON 数组。
     *
     * @return 文件标签数组；无标签时返回 {@code null} 。
     */
    public JSONArray outputAnswerFileLabelArray() {
        if (null == this.answerFileLabels) {
            return null;
        }

        JSONArray array = new JSONArray();
        for (FileLabel fileLabel : this.answerFileLabels) {
            array.put(fileLabel.toJSON());
        }
        return array;
    }

    /**
     * 将提问文件标签序列化为 JSON 数组。
     *
     * @return 文件标签数组；无标签时返回 {@code null} 。
     */
    public JSONArray outputQueryFileLabelArray() {
        if (null == this.queryFileLabels) {
            return null;
        }

        JSONArray array = new JSONArray();
        for (FileLabel fileLabel : this.queryFileLabels) {
            array.put(fileLabel.toJSON());
        }
        return array;
    }

    /**
     * 输出便于日志阅读的简短表示。
     *
     * @return 形如 {@code 提问 -> 回答} 的字符串，缺失部分留空。
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        if (null != this.query) {
            buf.append(this.query);
        }
        buf.append(" -> ");
        if (null != this.answer) {
            buf.append(this.answer);
        }
        return buf.toString();
    }

    /**
     * 序列化为完整 JSON 。
     *
     * @return 生成记录 JSON 对象。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("sn", this.sn);

        if (null != this.unit) {
            json.put("unit", this.unit);
        }

        if (null != this.query) {
            json.put("query", this.query);
        }

        if (null != this.answer) {
            json.put("answer", this.answer);
        }

        if (null != this.thought) {
            json.put("thought", this.thought);
        }

        if (null != this.resultPayload) {
            json.put("resultPayload", this.resultPayload);
        }

        if (null != this.answerFileLabels) {
            JSONArray array = new JSONArray();
            for (FileLabel fileLabel : this.answerFileLabels) {
                array.put(fileLabel.toJSON());
            }
            json.put("answerFileLabels", array);

            // 兼容旧版本
            json.put("fileLabels", array);
        }

        if (null != this.queryAdditions) {
            JSONArray array = new JSONArray();
            for (String text : this.queryAdditions) {
                array.put(text);
            }
            json.put("queryAdditions", array);
        }

        if (null != this.queryFileLabels) {
            JSONArray array = new JSONArray();
            for (FileLabel fileLabel : this.queryFileLabels) {
                array.put(fileLabel.toJSON());
            }
            json.put("queryFileLabels", array);
        }

        json.put("timestamp", this.timestamp);

        if (null != this.context) {
            json.put("context", this.context.toJSON());
        }
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>当前实现与 {@link #toJSON()} 输出一致。</p>
     *
     * @return 生成记录 JSON 对象。
     */
    @Override
    public JSONObject toCompactJSON() {
        JSONObject json = this.toJSON();
        if (json.has("query")) {
            json.remove("query");
        }
        return json;
    }

    /**
     * 序列化为链路追踪 JSON 。
     *
     * <p>在完整 JSON 基础上附加用于埋点与排障的字段。</p>
     *
     * @return 追踪用 JSON 对象。
     */
    public JSONObject toTraceJSON() {
        JSONObject json = this.toJSON();
        if (json.has("query")) {
            json.remove("query");
        }
        if (json.has("unit")) {
            json.remove("unit");
        }
        json.put("unit", "AiXinLi");
        json.put("company", "白泽京智");

        json.put("date", Utils.gsDateFormat.format(new Date(this.timestamp)));
        return json;
    }
}
