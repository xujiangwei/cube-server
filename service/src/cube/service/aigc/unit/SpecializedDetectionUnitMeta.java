/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.aigc.unit;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cell.util.Utils;
import cube.aigc.SpecializedDetectionException;
import cube.common.Packet;
import cube.common.entity.AIGCChannel;
import cube.common.entity.FileLabel;
import cube.common.entity.SpecializedDetectionInput;
import cube.common.entity.SpecializedDetectionOutput;
import cube.service.aigc.AIGCService;
import cube.service.aigc.listener.SpecializedDetectionListener;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次结构化检测请求。后台会话的归属关系独立于本次调用存活。
 */
public final class SpecializedDetectionUnitMeta extends UnitMeta {
    /**
     * 请求序号。
     */
    public final long sn = Utils.generateSerialNumber();
    /**
     * 请求所属的 AIGC 通道。
     */
    public final AIGCChannel channel;
    /**
     * 检测输入。
     */
    public final SpecializedDetectionInput input;
    /**
     * 与单元通信的上下文。
     */
    public final TalkContext context;
    /**
     * 准入预留。
     */
    final SpecializedDetectionScheduler.Reservation reservation;
    /**
     * 结果监听器。
     */
    private final SpecializedDetectionListener listener;
    /**
     * 截止时刻，单位纳秒。
     */
    private final long deadline;
    /**
     * 是否已通知监听器。
     */
    private final AtomicBoolean notified = new AtomicBoolean();
    /**
     * 是否已被认领。
     */
    private final AtomicBoolean claimed = new AtomicBoolean();
    /**
     * 响应是否已被消费。
     */
    private final AtomicBoolean consumed = new AtomicBoolean();
    /**
     * 迟到响应是否已入队。
     */
    private final AtomicBoolean lateQueued = new AtomicBoolean();
    /**
     * 捕获到的响应包。
     */
    private final AtomicReference<Packet> captured = new AtomicReference<>();
    /**
     * 是否已超时。
     */
    private volatile boolean timedOut;
    /**
     * 成功结果。
     */
    private volatile SpecializedDetectionOutput output;
    /**
     * 失败结果。
     */
    private volatile SpecializedDetectionException failure;
    /**
     * 超时定时任务。
     */
    private volatile ScheduledFuture<?> expiry;
    /**
     * 是否已发送至单元。
     */
    private boolean sentToUnit;

    /**
     * 构造方法。
     *
     * @param service     宿主 AIGC 服务
     * @param reservation 准入预留
     * @param channel     请求所属的 AIGC 通道
     * @param input       检测输入
     * @param listener    结果监听器
     */
    public SpecializedDetectionUnitMeta(AIGCService service, SpecializedDetectionScheduler.Reservation reservation,
                                        AIGCChannel channel, SpecializedDetectionInput input, SpecializedDetectionListener listener) {
        super(service, reservation.unit());
        this.reservation = reservation;
        this.context = reservation.context();
        this.channel = channel;
        this.input = input;
        this.listener = listener;
        this.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(service.getSpecializedDetections().timeout(input));
    }

    /**
     * 计算距离截止时刻的剩余时间。
     *
     * @return 剩余时间，单位毫秒，可能为负值
     */
    private long remaining() {
        return TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    }

    /**
     * 判断请求是否已超时。
     *
     * @return 已超时返回 true
     */
    public boolean isTimedOut() {
        return timedOut;
    }

    /**
     * 注册超时监控。
     */
    public void armDeadline() {
        try {
            expiry = service.getSpecializedDetections().watch(this, remaining());
        } catch (SpecializedDetectionException error) {
            reject(error);
        }
    }

    /**
     * 使请求超时。排队中的请求同样会被超时，避免线程池饱和时延长调用方的等待。
     */
    public synchronized void expire() {
        if (notified.get()) {
            return;
        }
        timedOut = true;
        claimed.compareAndSet(false, true);
        if (!sentToUnit) {
            service.getSpecializedDetections().unsent(this);
        }
        notifyFailure(SpecializedDetectionException.timeout());
        drainLateResponse();
    }

    /**
     * 捕获响应，可在 IO 线程调用：迟到证据交由独立的控制线程池处理。
     *
     * @param reply 响应包
     */
    public void captureResponse(Packet reply) {
        if (!reply.sn.equals(sn) || !"specializedDetection".equals(reply.name)) {
            return;
        }
        captured.compareAndSet(null, reply);
        drainLateResponse();
    }

    /**
     * 在超时后补投已捕获的迟到响应，每个响应只入队一次。
     */
    public void drainLateResponse() {
        if (!timedOut || captured.get() == null || consumed.get() || !lateQueued.compareAndSet(false, true)) {
            return;
        }
        if (!service.submitDetectionControl(() -> {
            try {
                consume(captured.get());
            } finally {
                lateQueued.set(false);
            }
        })) {
            lateQueued.set(false);
        }
    }

    /**
     * 拒绝本次请求并通知监听器。
     *
     * @param error 失败原因
     */
    public void reject(SpecializedDetectionException error) {
        claimed.set(true);
        service.getSpecializedDetections().unsent(this);
        notifyFailure(error);
    }

    /**
     * 通知监听器失败，保证只通知一次。
     *
     * @param error 失败原因
     */
    private void notifyFailure(SpecializedDetectionException error) {
        if (notified.compareAndSet(false, true)) {
            if (expiry != null) {
                expiry.cancel(false);
            }
            service.getSpecializedDetections().forgetControl(this);
            listener.onFailed(channel, error);
        }
    }

    /**
     * 执行本次检测请求：校验文件归属、下发指令、等待并消费响应。
     */
    @Override
    public void process() {
        if (!claimed.compareAndSet(false, true)) {
            return;
        }
        AtomicInteger counter = service.increaseUnitCounter(SpecializedDetectionInput.UNIT);
        boolean sent = false;
        try {
            if (remaining() <= 0) {
                reject(SpecializedDetectionException.timeout());
                return;
            }
            if (!service.getSpecializedDetections().valid(reservation)) {
                reject(SpecializedDetectionException.unit(30));
                return;
            }
            FileLabel label = null;
            if (input.source != null && !input.isRtsp()) {
                try {
                    label = service.getFile(usageContext.domain, input.source);
                } catch (RuntimeException error) {
                    throw SpecializedDetectionException.unit(32);
                }
                if (label == null) {
                    reject(SpecializedDetectionException.unit(3));
                    return;
                }
                if (label.getDomain() == null || !usageContext.domain.equals(label.getDomain().getName())
                        || label.getOwnerId() == null || label.getOwnerId().longValue() != usageContext.contactId) {
                    reject(SpecializedDetectionException.unit(25));
                    return;
                }
            }
            if (remaining() <= 0) {
                reject(SpecializedDetectionException.timeout());
                return;
            }
            Packet request = new Packet(sn, "specializedDetection", input.toUnitJSON(label));
            if ("detect".equals(input.operation)) {
                meterBegin();
            }
            synchronized (this) {
                if (remaining() <= 0 || notified.get()) {
                    reject(SpecializedDetectionException.timeout());
                    return;
                }
                service.getSpecializedDetections().sent(this);
                sentToUnit = true;
                sent = true;
            }
            ActionDialect dialect = service.getCellet().transmit(context, request.toDialect(), Math.max(1, remaining()), sn);
            if (dialect != null) {
                captureResponse(new Packet(dialect));
            }
            Packet reply = captured.get();
            if (reply == null) {
                timedOut = true;
                notifyFailure(SpecializedDetectionException.timeout());
                drainLateResponse();
                return;
            }
            consume(reply);
        } catch (SpecializedDetectionException error) {
            if (!sent) {
                service.getSpecializedDetections().unsent(this);
            } else {
                timedOut = true;
                drainLateResponse();
            }
            notifyFailure(error);
        } catch (IllegalStateException error) {
            if (!sent) {
                service.getSpecializedDetections().unsent(this);
            } else {
                timedOut = true;
                drainLateResponse();
            }
            notifyFailure(new SpecializedDetectionException(sent ? 31 : 34, sent ? 502 : 503,
                    sent ? "DETECTION_FAILED" : "USAGE_UNAVAILABLE", "Detection invocation unavailable"));
        } catch (RuntimeException error) {
            if (!sent) {
                service.getSpecializedDetections().unsent(this);
            } else {
                timedOut = true;
                drainLateResponse();
            }
            notifyFailure(new SpecializedDetectionException(31, 502, "DETECTION_FAILED", "Detection invocation failed"));
        } finally {
            counter.decrementAndGet();
            synchronized (this) {
                try {
                    meterUnknown();
                } catch (RuntimeException error) {
                    cell.util.log.Logger.w(getClass(), "Detection usage finalization failed", error);
                }
            }
        }
    }

    /**
     * 消费响应，校验关联关系与载荷结构，并结算会话与用量。
     *
     * @param reply 响应包
     */
    private synchronized void consume(Packet reply) {
        if (reply == null || !consumed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!reply.sn.equals(sn) || !"specializedDetection".equals(reply.name)
                    || !service.getSpecializedDetections().valid(reservation)) {
                throw new IllegalArgumentException("Invalid detection response correlation");
            }
            int code = Packet.extractCode(reply);
            JSONObject payload = Packet.extractDataPayload(reply);
            if (code == 0) {
                SpecializedDetectionOutput result = new SpecializedDetectionOutput(sn, payload);
                JSONObject body = result.resultPayload;
                if (input.isControl() || "start".equals(input.operation)) {
                    if (body.getString("streamId").trim().isEmpty()
                            || !Arrays.asList("starting", "running", "reconnecting", "stopping", "stopped", "failed").contains(body.getString("status"))
                            || (input.isControl() && !input.streamId.equals(body.getString("streamId")))) {
                        throw new IllegalArgumentException("Invalid stream response");
                    }
                } else if (!input.profile.equals(body.getString("profile")) || body.getInt("width") < 1
                        || body.getInt("height") < 1 || body.optJSONArray("refs") == null) {
                    throw new IllegalArgumentException("Invalid image detection response");
                }
                service.getSpecializedDetections().completed(this, code, body);
                output = result;
            } else {
                service.getSpecializedDetections().completed(this, code, new JSONObject());
                failure = SpecializedDetectionException.unit(code);
            }
            try {
                meterFinish(payload, code == 0 ? "succeeded" : "failed");
            } catch (RuntimeException error) {
                cell.util.log.Logger.w(getClass(), "Detection usage evidence failed", error);
            }
        } catch (RuntimeException error) {
            if ("detect".equals(input.operation)) {
                service.getSpecializedDetections().completed(this, 31, new JSONObject());
            }
            failure = new SpecializedDetectionException(31, 502, "INVALID_UNIT_RESPONSE", "Invalid detection response");
        }
        if (timedOut) {
            return;
        }
        if (failure != null) {
            notifyFailure(failure);
        } else if (output != null && notified.compareAndSet(false, true)) {
            if (expiry != null) {
                expiry.cancel(false);
            }
            listener.onResponse(channel, output);
        }
    }
}
