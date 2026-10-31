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
 * One structured detector request. Background session ownership outlives this invocation.
 */
public final class SpecializedDetectionUnitMeta extends UnitMeta {
    public final long sn = Utils.generateSerialNumber();
    public final AIGCChannel channel;
    public final SpecializedDetectionInput input;
    public final TalkContext context;
    final SpecializedDetectionScheduler.Reservation reservation;
    private final SpecializedDetectionListener listener;
    private final long deadline;
    private final AtomicBoolean notified = new AtomicBoolean();
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final AtomicBoolean consumed = new AtomicBoolean();
    private final AtomicBoolean lateQueued = new AtomicBoolean();
    private final AtomicReference<Packet> captured = new AtomicReference<>();
    private volatile boolean timedOut;
    private volatile SpecializedDetectionOutput output;
    private volatile SpecializedDetectionException failure;
    private volatile ScheduledFuture<?> expiry;
    private boolean sentToUnit;

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

    private long remaining() {
        return TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public void armDeadline() {
        try {
            expiry = service.getSpecializedDetections().watch(this, remaining());
        } catch (SpecializedDetectionException error) {
            reject(error);
        }
    }

    /**
     * Also expires queued requests, so a saturated pool cannot extend the caller's deadline.
     */
    public synchronized void expire() {
        if (notified.get()) return;
        timedOut = true;
        claimed.compareAndSet(false, true);
        if (!sentToUnit) service.getSpecializedDetections().unsent(this);
        notifyFailure(SpecializedDetectionException.timeout());
        drainLateResponse();
    }

    /**
     * Safe for an IO thread: late evidence is consumed on the independent control executor.
     */
    public void captureResponse(Packet reply) {
        if (!reply.sn.equals(sn) || !"specializedDetection".equals(reply.name)) return;
        captured.compareAndSet(null, reply);
        drainLateResponse();
    }

    public void drainLateResponse() {
        if (!timedOut || captured.get() == null || consumed.get() || !lateQueued.compareAndSet(false, true)) return;
        if (!service.submitDetectionControl(() -> {
            try {
                consume(captured.get());
            } finally {
                lateQueued.set(false);
            }
        })) lateQueued.set(false);
    }

    public void reject(SpecializedDetectionException error) {
        claimed.set(true);
        service.getSpecializedDetections().unsent(this);
        notifyFailure(error);
    }

    private void notifyFailure(SpecializedDetectionException error) {
        if (notified.compareAndSet(false, true)) {
            if (expiry != null) expiry.cancel(false);
            service.getSpecializedDetections().forgetControl(this);
            listener.onFailed(channel, error);
        }
    }

    @Override
    public void process() {
        if (!claimed.compareAndSet(false, true)) return;
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
            if ("detect".equals(input.operation)) meterBegin();
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
            if (dialect != null) captureResponse(new Packet(dialect));
            Packet reply = captured.get();
            if (reply == null) {
                timedOut = true;
                notifyFailure(SpecializedDetectionException.timeout());
                drainLateResponse();
                return;
            }
            consume(reply);
        } catch (SpecializedDetectionException error) {
            if (!sent) service.getSpecializedDetections().unsent(this);
            else {
                timedOut = true;
                drainLateResponse();
            }
            notifyFailure(error);
        } catch (IllegalStateException error) {
            if (!sent) service.getSpecializedDetections().unsent(this);
            else {
                timedOut = true;
                drainLateResponse();
            }
            notifyFailure(new SpecializedDetectionException(sent ? 31 : 34, sent ? 502 : 503,
                    sent ? "DETECTION_FAILED" : "USAGE_UNAVAILABLE", "Detection invocation unavailable"));
        } catch (RuntimeException error) {
            if (!sent) service.getSpecializedDetections().unsent(this);
            else {
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

    private synchronized void consume(Packet reply) {
        if (reply == null || !consumed.compareAndSet(false, true)) return;
        try {
            if (!reply.sn.equals(sn) || !"specializedDetection".equals(reply.name)
                    || !service.getSpecializedDetections().valid(reservation))
                throw new IllegalArgumentException("Invalid detection response correlation");
            int code = Packet.extractCode(reply);
            JSONObject payload = Packet.extractDataPayload(reply);
            if (code == 0) {
                SpecializedDetectionOutput result = new SpecializedDetectionOutput(sn, payload);
                JSONObject body = result.resultPayload;
                if (input.isControl() || "start".equals(input.operation)) {
                    if (body.getString("streamId").trim().isEmpty()
                            || !Arrays.asList("starting", "running", "reconnecting", "stopping", "stopped", "failed").contains(body.getString("status"))
                            || (input.isControl() && !input.streamId.equals(body.getString("streamId"))))
                        throw new IllegalArgumentException("Invalid stream response");
                } else if (!input.profile.equals(body.getString("profile")) || body.getInt("width") < 1
                        || body.getInt("height") < 1 || body.optJSONArray("refs") == null)
                    throw new IllegalArgumentException("Invalid image detection response");
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
            if ("detect".equals(input.operation))
                service.getSpecializedDetections().completed(this, 31, new JSONObject());
            failure = new SpecializedDetectionException(31, 502, "INVALID_UNIT_RESPONSE", "Invalid detection response");
        }
        if (timedOut) return;
        if (failure != null) notifyFailure(failure);
        else if (output != null && notified.compareAndSet(false, true)) {
            if (expiry != null) expiry.cancel(false);
            listener.onResponse(channel, output);
        }
    }
}
