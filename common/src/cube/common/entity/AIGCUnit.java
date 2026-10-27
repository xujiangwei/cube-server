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
 */
public class AIGCUnit extends Entity {

    private String key;

    private Contact contact;

    private AICapability capability;

    private TalkContext context;

    private double weight;

    private AtomicInteger runningCounter;

    private AtomicLong lastRunningTimestamp = new AtomicLong(System.currentTimeMillis());

    private ConcurrentLinkedQueue<Failure> failures;

    public AIGCUnit(Contact contact, AICapability capability, TalkContext context) {
        super(contact.id, contact.domain.getName());
        this.key = AIGCUnit.makeQueryKey(contact, capability);
        this.contact = contact;
        this.capability = capability;
        this.context = context;
        this.weight = 5.0;
        this.runningCounter = new AtomicInteger(0);
        this.failures = new ConcurrentLinkedQueue<>();
    }

    public AIGCUnit(JSONObject json) {
        super(json);
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
     */
    public String getQueryKey() {
        return this.key;
    }

    /** 获取单元所属联系人。 */
    public Contact getContact() {
        return this.contact;
    }

    /** 获取单元的 AI 能力描述。 */
    public AICapability getCapability() {
        return this.capability;
    }

    /** 获取单元的会话上下文。 */
    public TalkContext getContext() {
        return this.context;
    }

    /** 设置单元的会话上下文。 */
    public void setContext(TalkContext context) {
        this.context = context;
    }

    /**
     * 获取调度权重。
     *
     * <p>权重越大越容易被调度器选中。</p>
     */
    public double getWeight() {
        return this.weight;
    }

    /** 设置调度权重。 */
    public void setWeight(double value) {
        this.weight = value;
    }

    /**
     * 标记单元的运行状态。
     *
     * <p>同时刷新最近运行时间戳，供 {@link #isRunning()} 判断是否仍活跃。</p>
     */
    public synchronized void setRunning(boolean value) {
        this.lastRunningTimestamp.set(System.currentTimeMillis());
        if (value) {
            this.runningCounter.incrementAndGet();
        }
        else {
            if (this.runningCounter.decrementAndGet() < 0) {
                this.runningCounter.set(0);
            }
        }
    }

    /**
     * 判断单元是否正在运行。
     *
     * <p>最近 60 秒内有活动，或运行计数器大于 0 ，即视为运行中。</p>
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

    /** 获取最近一次运行时间戳。 */
    public long getLastRunningTimestamp() {
        return this.lastRunningTimestamp.get();
    }

    /** 记录一次调用失败。 */
    public void markFailure(int code, long timestamp, long contactId) {
        this.failures.add(new Failure(code, timestamp, contactId));
    }

    /** 统计失败次数。 */
    public int numFailure() {
        return this.failures.size();
    }

    /** 序列化为完整 JSON 。 */
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

    /** 序列化为精简 JSON 。 */
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

    public class Failure {

        public final int code;

        public final long timestamp;

        public long contactId;

        public Failure(int code, long timestamp, long contactId) {
            this.code = code;
            this.timestamp = timestamp;
            this.contactId = contactId;
        }

        public Failure(JSONObject json) {
            this.code = json.getInt("code");
            this.timestamp = json.getLong("timestamp");
            this.contactId = json.getLong("contactId");
        }

        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("code", this.code);
            json.put("timestamp", this.timestamp);
            json.put("contactId", this.contactId);
            return json;
        }
    }
}
