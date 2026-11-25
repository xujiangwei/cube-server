/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.core.talk.TalkContext;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AIGC 服务单元。
 *
 * <p>描述一个服务者具备某项 AI 能力的运行实例，维护调度权重、运行状态与调用失败记录。</p>
 */
public class AIGCUnit extends Entity {

    /**
     * 单元查询键，由联系人与能力标识拼装而成。
     */
    private String key;

    /**
     * 单元所属联系人。
     */
    private Contact contact;

    /**
     * 单元提供的 AI 能力。
     */
    private AICapability capability;

    /**
     * 单元的会话上下文。
     */
    private volatile TalkContext context;

    /**
     * 单元所属进程标识。
     */
    private final String processId;

    /**
     * 单元是否已退役。
     */
    private volatile boolean retired;

    /**
     * 调度权重，越大越容易被调度器选中。
     */
    private double weight;

    /**
     * 运行计数，用于平滑短时并发下的运行状态判断。
     */
    private AtomicInteger runningCounter;

    /**
     * 最近一次运行标记的时间戳。
     */
    private AtomicLong lastRunningTimestamp = new AtomicLong(System.currentTimeMillis());

    /**
     * 调用失败记录队列。
     */
    private ConcurrentLinkedQueue<Failure> failures;

    /**
     * 创建单元。
     *
     * <p>进程标识默认为空字符串。</p>
     *
     * @param contact    单元所属联系人。
     * @param capability 单元提供的 AI 能力。
     * @param context    单元的会话上下文。
     */
    public AIGCUnit(Contact contact, AICapability capability, TalkContext context) {
        this(contact, capability, context, "");
    }

    /**
     * 创建单元。
     *
     * @param contact    单元所属联系人。
     * @param capability 单元提供的 AI 能力。
     * @param context    单元的会话上下文。
     * @param processId  单元所属进程标识。
     */
    public AIGCUnit(Contact contact, AICapability capability, TalkContext context, String processId) {
        super(contact.id, contact.domain.getName());
        this.key = AIGCUnit.makeQueryKey(contact, capability);
        this.contact = contact;
        this.capability = capability;
        this.context = context;
        this.processId = processId;
        this.weight = 5.0;
        this.runningCounter = new AtomicInteger(0);
        this.failures = new ConcurrentLinkedQueue<>();
    }

    /**
     * 由 JSON 重建单元。
     *
     * <p>读取能力、权重、运行状态与失败记录；缺失字段取默认值。</p>
     *
     * @param json 单元 JSON 对象。
     */
    public AIGCUnit(JSONObject json) {
        super(json);
        this.processId = "";
        this.capability = new AICapability(json.getJSONObject("capability"));
        this.weight = json.has("weight") ? json.getDouble("weight") : 5.0;
        this.runningCounter = new AtomicInteger(json.getInt("runningCounter"));
        this.failures = new ConcurrentLinkedQueue<>();
        if (json.has("failures")) {
            JSONArray array = json.getJSONArray("failures");
            for (int i = 0; i < array.length(); ++i) {
                Failure failure = new Failure(array.getJSONObject(i));
                this.failures.add(failure);
            }
        }
    }

    /**
     * 获取单元查询键。
     *
     * <p>查询键由联系人 ID 与能力标识拼装，用于在单元表中定位同一服务者的同一能力。</p>
     *
     * @return 单元查询键。
     */
    public String getQueryKey() {
        return this.key;
    }

    /**
     * 获取单元所属联系人。
     *
     * @return 联系人。
     */
    public Contact getContact() {
        return this.contact;
    }

    /**
     * 获取单元的 AI 能力描述。
     *
     * @return 能力描述。
     */
    public AICapability getCapability() {
        return this.capability;
    }

    /**
     * 获取单元的会话上下文。
     *
     * @return 会话上下文。
     */
    public TalkContext getContext() {
        return this.context;
    }

    /**
     * 获取单元所属进程标识。
     *
     * @return 进程标识；未指定时为空字符串。
     */
    public String getProcessId() {
        return this.processId;
    }

    /**
     * 判断单元是否已退役。
     *
     * @return 已退役返回 {@code true} 。
     */
    public boolean isRetired() {
        return this.retired;
    }

    /**
     * 将单元标记为已退役。
     */
    public void retire() {
        this.retired = true;
    }

    /**
     * 设置单元的会话上下文。
     *
     * @param context 会话上下文。
     */
    public void setContext(TalkContext context) {
        this.context = context;
    }

    /**
     * 获取调度权重。
     *
     * <p>权重越大越容易被调度器选中。</p>
     *
     * @return 调度权重。
     */
    public double getWeight() {
        return this.weight;
    }

    /**
     * 设置调度权重。
     *
     * @param value 调度权重。
     */
    public void setWeight(double value) {
        this.weight = value;
    }

    /**
     * 标记单元的运行状态。
     *
     * <p>同时刷新最近运行时间戳，供 {@link #isRunning()} 判断是否仍活跃。</p>
     *
     * @param value 为 {@code true} 表示开始运行，{@code false} 表示结束运行。
     */
    public synchronized void setRunning(boolean value) {
        this.lastRunningTimestamp.set(System.currentTimeMillis());
        if (value) {
            this.runningCounter.incrementAndGet();
        } else {
            if (this.runningCounter.decrementAndGet() < 0) {
                this.runningCounter.set(0);
            }
        }
    }

    /**
     * 判断单元是否正在运行。
     *
     * <p>最近 60 秒内有活动，或运行计数器大于 0 ，即视为运行中。</p>
     *
     * @return 运行中返回 {@code true} 。
     */
    public boolean isRunning() {
        // 如果注册时间小于60秒，标记为正在运行
        return (System.currentTimeMillis() - this.timestamp <= 60 * 1000) || (this.runningCounter.get() > 0);
    }

    /**
     * 重置长时间空闲的运行状态。
     *
     * <p>仅在超过 10 分钟没有任何运行标记时生效，避免误清正在处理任务的单元。</p>
     */
    public void resetRunning() {
        if (System.currentTimeMillis() - this.lastRunningTimestamp.get() > 10 * 60 * 1000) {
            this.runningCounter.set(0);
        }
    }

    /**
     * 获取最近一次运行时间戳。
     *
     * @return 时间戳，单位毫秒。
     */
    public long getLastRunningTimestamp() {
        return this.lastRunningTimestamp.get();
    }

    /**
     * 记录一次调用失败。
     *
     * @param code      失败码。
     * @param timestamp 失败时间，单位毫秒。
     * @param contactId 关联的联系人 ID 。
     */
    public void markFailure(int code, long timestamp, long contactId) {
        this.failures.add(new Failure(code, timestamp, contactId));
    }

    /**
     * 统计失败次数。
     *
     * @return 失败记录条数。
     */
    public int numFailure() {
        return this.failures.size();
    }

    /**
     * 序列化为完整 JSON 。
     *
     * @return 单元 JSON 对象。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("capability", this.capability.toJSON());
        json.put("weight", this.weight);
        json.put("running", this.runningCounter.get() > 0);
        json.put("runningCounter", this.runningCounter.get());

        JSONArray array = new JSONArray();
        for (Failure failure : this.failures) {
            array.put(failure.toJSON());
        }
        json.put("failures", array);

        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>当前实现与 {@link #toJSON()} 输出一致。</p>
     *
     * @return 单元 JSON 对象。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }

    /**
     * 生成单元查询键。
     *
     * @param contact    单元所属联系人。
     * @param capability 单元能力。
     * @return 由联系人 ID 与能力标识拼成的查询键。
     */
    public static String makeQueryKey(Contact contact, AICapability capability) {
        StringBuilder buf = new StringBuilder(contact.getId().toString());
        buf.append("_");
        buf.append(contact.domain.getName());
        buf.append("_");
        buf.append(capability.getName());
        buf.append("_");
        buf.append(capability.getPrimarySubtask());
        buf.append("_");
        buf.append(capability.getDescription());
        return buf.toString();
    }

    /**
     * 单元调用失败记录。
     */
    public final class Failure {

        /**
         * 失败码。
         */
        public final int code;

        /**
         * 失败时间，单位毫秒。
         */
        public final long timestamp;

        /**
         * 关联的联系人 ID 。
         */
        public long contactId;

        /**
         * 创建失败记录。
         *
         * @param code      失败码。
         * @param timestamp 失败时间，单位毫秒。
         * @param contactId 关联的联系人 ID 。
         */
        public Failure(int code, long timestamp, long contactId) {
            this.code = code;
            this.timestamp = timestamp;
            this.contactId = contactId;
        }

        /**
         * 由 JSON 重建失败记录。
         *
         * @param json 失败记录 JSON 对象。
         */
        public Failure(JSONObject json) {
            this.code = json.getInt("code");
            this.timestamp = json.getLong("timestamp");
            this.contactId = json.getLong("contactId");
        }

        /**
         * 序列化为 JSON 。
         *
         * @return 失败记录 JSON 对象。
         */
        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("code", this.code);
            json.put("timestamp", this.timestamp);
            json.put("contactId", this.contactId);
            return json;
        }
    }
}
