/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.core.net.Endpoint;
import cell.util.Utils;
import cube.auth.AuthToken;
import cube.common.Domain;
import cube.common.Language;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AIGC 分配给用户的通道。
 */
public class AIGCChannel extends Entity {

    private int historyLengthLimit = 2 * 1024;

    private AuthToken authToken;

    private Language language;

    private String participant;

    private long creationTime;

    private String code;

    private long activeTimestamp;

    // 倒序存储历史记录
    private final LinkedList<GeneratingRecord> history;

    private final AtomicBoolean processing;

    private long processingTimestamp;

    private int totalQueryWords = 0;

    private int totalAnswerWords = 0;

    private Endpoint httpEndpoint;
    private Endpoint httpsEndpoint;

    /**
     * 对话轮次记录。
     */
    private AtomicInteger rounds;

    /**
     * 最近一次任务的 SN 。
     */
    private long lastUnitMetaSn;

    public AIGCChannel(AuthToken authToken, String participant) {
        this(authToken, participant, Utils.randomString(16), Language.Chinese);
    }

    public AIGCChannel(AuthToken authToken, String participant, String channelCode, Language language) {
        this.domain = new Domain(authToken.getDomain());
        this.authToken = authToken;
        this.language = language;
        this.participant = participant;
        this.creationTime = System.currentTimeMillis();
        this.code = channelCode;
        this.history = new LinkedList<>();
        this.processing = new AtomicBoolean(false);
        this.activeTimestamp = this.creationTime;
        this.rounds = new AtomicInteger(0);
    }

    public AIGCChannel(JSONObject json) {
        this.code = json.getString("code");
        this.creationTime = json.getLong("creationTime");
        this.participant = json.getString("participant");

        if (json.has("authToken")) {
            this.authToken = new AuthToken(json.getJSONObject("authToken"));
            this.domain = new Domain(this.authToken.getDomain());
        }

        if (json.has("language")) {
            this.language = Language.parse(json.getString("language"));
        }
        else {
            this.language = Language.Chinese;
        }

        this.history = new LinkedList<>();

        this.processing = new AtomicBoolean(json.has("processing") && json.getBoolean("processing"));
        this.activeTimestamp = json.has("activeTimestamp") ? json.getLong("activeTimestamp") :  this.creationTime;
        this.rounds = new AtomicInteger(json.has("rounds") ? json.getInt("rounds") : 0);
        this.totalQueryWords = json.has("totalQueryWords") ? json.getInt("totalQueryWords") : 0;
        this.totalAnswerWords = json.has("totalAnswerWords") ? json.getInt("totalAnswerWords") : 0;

        this.lastUnitMetaSn = json.has("lastMetaSn") ? json.getLong("lastMetaSn") : 0;

        if (json.has("lastRecord")) {
            GeneratingRecord record = new GeneratingRecord(json.getJSONObject("lastRecord"));
            this.history.addFirst(record);
        }
    }

    /**
     * 获取通道的鉴权令牌。
     *
     * @return 创建通道时绑定的令牌。
     */
    public AuthToken getAuthToken() {
        return this.authToken;
    }

    /**
     * 获取通道使用的语言。
     *
     * @return 语言枚举。
     */
    public Language getLanguage() {
        return this.language;
    }

    /**
     * 获取通道码。
     *
     * <p>通道码在创建时生成，用于跨端识别同一会话通道。</p>
     *
     * @return 通道码。
     */
    public String getCode() {
        return this.code;
    }

    /**
     * 获取通道创建时间。
     *
     * @return 创建时间戳，单位毫秒。
     */
    public long getCreationTime() {
        return this.creationTime;
    }

    /**
     * 获取通道参与者标识。
     *
     * <p>通常是参与者联系人的 ID 或设备标识，由调用方定义。</p>
     *
     * @return 参与者标识。
     */
    public String getParticipant() {
        return this.participant;
    }

    /**
     * 获取最近一次活动时间。
     *
     * @return 活动时间戳，单位毫秒；新通道等于创建时间。
     */
    public long getActiveTimestamp() {
        return this.activeTimestamp;
    }

    /**
     * 更新最近活动时间。
     *
     * @param time 活动时间戳，单位毫秒。
     */
    public void setActiveTimestamp(long time) {
        this.activeTimestamp = time;
    }

    /**
     * 设置通道的服务端点。
     *
     * @param http  HTTP 端点。
     * @param https HTTPS 端点。
     */
    public void setEndpoint(Endpoint http, Endpoint https) {
        this.httpEndpoint = http;
        this.httpsEndpoint = https;
    }

    /**
     * 获取 HTTP 端点。
     *
     * @return HTTP 端点，未设置时可能为 {@code null} 。
     */
    public Endpoint getHttpEndpoint() {
        return this.httpEndpoint;
    }

    /**
     * 获取 HTTPS 端点。
     *
     * @return HTTPS 端点，未设置时可能为 {@code null} 。
     */
    public Endpoint getHttpsEndpoint() {
        return this.httpsEndpoint;
    }

    /**
     * 标记通道进入处理中状态。
     *
     * <p>置位的同时记录处理时间戳。处理中的通道不应被重复投递任务。</p>
     *
     * @param value 为 {@code true} 表示开始处理，{@code false} 表示结束处理。
     */
    public void setProcessing(boolean value) {
        this.processingTimestamp = System.currentTimeMillis();
        this.processing.set(value);
    }

    /**
     * 判断通道是否正在处理任务。
     *
     * @return 处理中返回 {@code true} 。
     */
    public boolean isProcessing() {
        return this.processing.get();
    }

    /**
     * 获取进入处理中状态的时间。
     *
     * @return 处理时间戳，单位毫秒；从未进入处理态时为 0 。
     */
    public long getProcessingTimestamp() {
        return this.processingTimestamp;
    }

    /**
     * 获取对话轮次。
     *
     * @return 该通道已完成的对话轮数。
     */
    public int getRounds() {
        return this.rounds.get();
    }

    /**
     * 记录最近一次任务的单元 SN 。
     *
     * @param sn 任务单元 SN 。
     */
    public void setLastUnitMetaSn(long sn) {
        this.lastUnitMetaSn = sn;
    }

    /**
     * 获取最近一次任务的单元 SN 。
     *
     * @return 单元 SN ；从未设置时为 0 。
     */
    public long getLastUnitMetaSn() {
        return this.lastUnitMetaSn;
    }

    /**
     * 获取最近一条生成记录。
     *
     * @return 最近一条记录；无历史时返回 {@code null} 。
     */
    public GeneratingRecord getLastRecord() {
        synchronized (this.history) {
            if (this.history.isEmpty()) {
                return null;
            }

            return this.history.getFirst();
        }
    }

    /**
     * 按 SN 查找生成记录。
     *
     * @param sn 记录 SN 。
     * @return 匹配的记录；未找到返回 {@code null} 。
     */
    public GeneratingRecord getRecord(long sn) {
        synchronized (this.history) {
            for (GeneratingRecord record : this.history) {
                if (record.sn == sn) {
                    return record;
                }
            }
        }
        return null;
    }

    /**
     * 追加一条生成记录。
     *
     * <p>同时累计提问与回答字数、递增对话轮次，并把新记录插入队首。</p>
     *
     * @param sn            记录 SN 。
     * @param unit          生成该记录的单元名称。
     * @param query         用户提问。
     * @param answer        模型回答。
     * @param thought       思考过程，可为 {@code null} 。
     * @param resultPayload 结构化结果载荷，可为 {@code null} 。
     * @param context       结构化上下文，可为 {@code null} 。
     * @return 新建的生成记录。
     */
    public GeneratingRecord appendRecord(long sn, String unit, String query,
                                         String answer, String thought, JSONObject resultPayload,
                                         ComplexContext context) {
        this.activeTimestamp = System.currentTimeMillis();

        this.totalQueryWords += query.length();
        this.totalAnswerWords += answer.length();

        this.rounds.incrementAndGet();

        GeneratingRecord record = new GeneratingRecord(sn, unit, query, answer, thought, resultPayload,
                this.activeTimestamp, context);
        synchronized (this.history) {
            this.history.addFirst(record);
        }
        return record;
    }

    /**
     * 追加一条以文件为产物的生成记录。
     *
     * @param sn        记录 SN 。
     * @param unit      生成该记录的单元名称。
     * @param query     用户提问。
     * @param fileLabel 产物文件标签。
     * @return 新建的生成记录。
     */
    public GeneratingRecord appendRecord(long sn, String unit, String query, FileLabel fileLabel) {
        this.activeTimestamp = System.currentTimeMillis();

        this.totalQueryWords += query.length();
        this.totalAnswerWords += fileLabel.getFileCode().length();

        this.rounds.incrementAndGet();

        GeneratingRecord record = new GeneratingRecord(sn, unit, query, fileLabel, this.activeTimestamp);
        synchronized (this.history) {
            this.history.addFirst(record);
        }
        return record;
    }

    /**
     * 以已有记录为模板追加生成记录。
     *
     * <p>用于在同一轮对话中延续上下文，仅替换 SN 。</p>
     *
     * @param sn     新记录 SN 。
     * @param source 源记录。
     * @return 新建的生成记录。
     */
    public GeneratingRecord appendRecord(long sn, GeneratingRecord source) {
        this.activeTimestamp = System.currentTimeMillis();

        this.totalQueryWords += source.query.length();
        this.totalAnswerWords += source.answer.length();

        this.rounds.incrementAndGet();

        GeneratingRecord record = new GeneratingRecord(sn, source.unit, source.query,
                source.answer, source.thought, source.resultPayload,
                this.activeTimestamp, source.context);
        synchronized (this.history) {
            this.history.addFirst(record);
        }
        return record;
    }

    /**
     * 获取全部生成记录。
     *
     * <p>返回内部列表本身，调用方不应修改。返回顺序为新到旧。</p>
     *
     * @return 生成记录列表。
     */
    public List<GeneratingRecord> getHistories() {
        return this.history;
    }

    /**
     * 获取最近的若干条生成记录。
     *
     * <p>从新到旧筛选，跳过长度超过历史上限的记录与收到差评的记录，
     * 取满 {@code num} 条后反转为时间正序，便于直接拼进对话上下文。</p>
     *
     * @param num 期望条数。
     * @return 最多 {@code num} 条记录，按时间正序排列。
     */
    public List<GeneratingRecord> getLastHistory(int num) {
        List<GeneratingRecord> list = new ArrayList<>(num);

        synchronized (this.history) {
            for (GeneratingRecord record : this.history) {
                if (record.totalWords() > this.historyLengthLimit) {
                    // 过滤掉词多的问答
                    continue;
                }

                // 过滤低分项
                if (record.feedback == 1) {
                    // 跳过差评
                    continue;
                }

                list.add(record);
                if (list.size() >= num) {
                    break;
                }
            }
        }

        // 调换顺序，成为时间正序
        Collections.reverse(list);
        return list;
    }

    /**
     * 为指定记录登记反馈。
     *
     * @param sn       记录 SN 。
     * @param feedback 反馈值，取值由业务层约定。
     */
    public void feedbackRecord(long sn, int feedback) {
        synchronized (this.history) {
            for (GeneratingRecord record : this.history) {
                if (record.sn == sn) {
                    record.feedback = feedback;
                    break;
                }
            }
        }
    }

    /**
     * 统计向 AI 提问的总字数。
     *
     * @return
     */
    public int totalQueryWords() {
        return this.totalQueryWords;
    }

    /**
     * 统计 AI 回答的总字数。
     *
     * @return
     */
    public int totalAnswerWords() {
        return this.totalAnswerWords;
    }

    /**
     * 序列化为通道信息 JSON 。
     *
     * <p>在完整 JSON 基础上补充汇总字段，供列表类接口使用。</p>
     *
     * @return 通道信息 JSON 对象。
     */
    public JSONObject toInfo() {
        JSONObject json = this.toJSON();
        json.put("activeTimestamp", this.activeTimestamp);
        json.put("rounds", this.rounds.get());
        json.put("processing", this.processing.get());
        json.put("totalQueryWords", this.totalQueryWords());
        json.put("totalAnswerWords", this.totalAnswerWords());
        json.put("lastMetaSn", this.lastUnitMetaSn);

        GeneratingRecord record = this.getLastRecord();
        if (null != record) {
            json.put("lastRecord", record.toCompactJSON());
        }
        return json;
    }

    /**
     * 序列化为完整 JSON 。
     *
     * <p>包含通道全部状态与历史记录，可用于持久化与跨端重建。</p>
     *
     * @return 通道 JSON 对象。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("code", this.code);
        json.put("creationTime", this.creationTime);
        json.put("participant", this.participant);

        if (null != this.authToken) {
            json.put("authToken", this.authToken.toJSON());
        }
        json.put("language", this.language.simplified);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>当前实现与 {@link #toJSON()} 输出一致。</p>
     *
     * @return 通道 JSON 对象。
     */
    @Override
    public JSONObject toCompactJSON() {
        JSONObject json = this.toJSON();
        if (json.has("authToken")) {
            json.remove("authToken");
        }
        return json;
    }
}
