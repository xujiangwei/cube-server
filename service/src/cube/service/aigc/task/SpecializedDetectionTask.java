/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.task;

import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.SpecializedDetectionException;
import cube.benchmark.ResponseTime;
import cube.common.Packet;
import cube.common.entity.AIGCChannel;
import cube.common.entity.SpecializedDetectionInput;
import cube.common.entity.SpecializedDetectionOutput;
import cube.service.ServiceTask;
import cube.service.aigc.AIGCCellet;
import cube.service.aigc.listener.SpecializedDetectionListener;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Authenticated service endpoint; detector errors survive the dispatcher hop.
 */
public final class SpecializedDetectionTask extends ServiceTask {
    private final AtomicBoolean responded = new AtomicBoolean();

    public SpecializedDetectionTask(AIGCCellet cellet, TalkContext context, Primitive primitive, ResponseTime time) {
        super(cellet, context, primitive, time);
    }

    @Override
    public void run() {
        ActionDialect dialect = new ActionDialect(primitive);
        Packet packet = new Packet(dialect);
        try {
            String token = getTokenCode(dialect);
            if (token == null) throw SpecializedDetectionException.unit(22);
            String channel = packet.data.getString("channel");
            if (channel.trim().isEmpty()) throw SpecializedDetectionException.invalid("channel is required");
            SpecializedDetectionInput input = new SpecializedDetectionInput(packet.data.getJSONObject("input"));
            ((AIGCCellet) cellet).getService().executeSpecializedDetection(token, channel, input, new SpecializedDetectionListener() {
                @Override
                public void onResponse(AIGCChannel channel, SpecializedDetectionOutput output) {
                    reply(dialect, packet, 0, output.toJSON());
                }

                @Override
                public void onFailed(AIGCChannel channel, SpecializedDetectionException error) {
                    fail(dialect, packet, error);
                }
            });
        } catch (SpecializedDetectionException error) {
            fail(dialect, packet, error);
        } catch (org.json.JSONException | IllegalArgumentException error) {
            fail(dialect, packet, SpecializedDetectionException.invalid("Invalid detection request"));
        } catch (RuntimeException error) {
            fail(dialect, packet, SpecializedDetectionException.unit(31));
        }
    }

    private void fail(ActionDialect dialect, Packet packet, SpecializedDetectionException error) {
        reply(dialect, packet, error.code, new JSONObject().put("error", error.toJSON()));
    }

    private void reply(ActionDialect dialect, Packet packet, int code, JSONObject data) {
        if (responded.compareAndSet(false, true)) {
            cellet.speak(talkContext, makeResponse(dialect, packet, code, data));
            markResponseTime();
        }
    }
}
