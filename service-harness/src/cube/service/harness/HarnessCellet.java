/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import cell.api.Servable;
import cell.core.talk.*;
import cell.core.talk.dialect.ActionDialect;
import cube.common.Packet;
import cube.common.action.HarnessAction;
import cube.core.AbstractCellet;
import cube.core.Kernel;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * A normal XML-installed Cellet. Never delegates to AbstractCellet's shared executor.
 */
public final class HarnessCellet extends AbstractCellet {
    private HarnessService service;

    public HarnessCellet() {
        super(HarnessService.NAME);
    }

    public HarnessService getService() {
        return service;
    }

    @Override
    public boolean install() {
        Kernel kernel = (Kernel) getNucleus().getParameter("kernel");
        if (kernel == null) return false;
        service = new HarnessService();
        kernel.installModule(HarnessService.NAME, service);
        return true;
    }

    @Override
    public void uninstall() {
        Kernel kernel = (Kernel) getNucleus().getParameter("kernel");
        if (kernel != null) kernel.uninstallModule(HarnessService.NAME);
        service = null;
    }

    @Override
    public ExecutorService getExecutor() {
        return service == null ? null : service.executor();
    }

    @Override
    protected void execute(Runnable task) {
        ExecutorService pool = getExecutor();
        if (pool == null) throw new RejectedExecutionException();
        pool.execute(task);
    }

    private void reply(TalkContext context, JSONObject data) {
        Primitive p = new Primitive();
        p.commit(new Stuff(data.toString()));
        speak(context, p);
    }

    @Override
    public void onListened(TalkContext context, Primitive primitive) {
        super.onListened(context, primitive);
        if (primitive.numStuff() != 1 || primitive.getStuff(0).getLiteralBase() != LiteralBase.STRING) {
            onBusiness(context, primitive);
            return;
        }
        // Check the envelope before queueing or decoding its original STRING payload.
        if (primitive.numStuff() != 1 || primitive.getStuff(0).getLiteralBase() != LiteralBase.STRING || primitive.getStuff(0).getValue().length > 262144) {
            HarnessService.logWorkerRejection("INVALID_ENVELOPE", context.getSessionId());
            reply(context, HarnessService.error("", "INVALID_ENVELOPE"));
            return;
        }
        final HarnessService current = service;
        try {
            execute(() -> {
                if (current != null && context.isValid())
                    reply(context, current.process(primitive.getStuff(0).getValue(), context.getSessionId(), context::isValid));
                else
                    HarnessService.logWorkerRejection(current == null ? "HARNESS_NOT_READY" : "STALE_SESSION", context.getSessionId());
            });
        } catch (RejectedExecutionException e) {
            HarnessService.logWorkerRejection("HARNESS_BUSY", context.getSessionId());
            reply(context, HarnessService.error("", "HARNESS_BUSY"));
        }
    }

    private void replyBusiness(TalkContext context, ActionDialect request, JSONObject result) {
        ActionDialect response = new Packet(request.getParamAsLong("sn"), request.getName(), result).toDialect();
        if (request.containsParam("_performer")) response.addParam("_performer", request.getParamAsJson("_performer"));
        speak(context, response);
    }

    private void onBusiness(TalkContext context, Primitive primitive) {
        ActionDialect request;
        try {
            request = new ActionDialect(primitive);
        } catch (Exception e) {
            HarnessService.logWorkerRejection("INVALID_ENVELOPE", context.getSessionId());
            reply(context, HarnessService.error("", "INVALID_ENVELOPE"));
            return;
        }
        // Only a correlated dispatcher envelope can enter the business executor.
        if (!request.containsParam("sn") || !request.containsParam("_performer")) {
            HarnessService.logWorkerRejection("INVALID_ENVELOPE", context.getSessionId());
            reply(context, HarnessService.error("", "INVALID_ENVELOPE"));
            return;
        }
        final HarnessService current = service;
        if (primitive.length() > HarnessAction.MAX_BODY_BYTES + 65536) {
            replyBusiness(context, request, HarnessBusiness.error(413, "PAYLOAD_TOO_LARGE"));
            return;
        }
        try {
            if (current == null || current.businessExecutor() == null) throw new RejectedExecutionException();
            current.businessExecutor().execute(() -> {
                if (context.isValid()) replyBusiness(context, request,
                        current.processBusiness(request, context, name -> speakStream(context, name)));
            });
        } catch (RejectedExecutionException e) {
            replyBusiness(context, request, HarnessBusiness.error(503, "HARNESS_BUSY"));
        }
    }

    @Override
    public void onListened(TalkContext context, PrimitiveInputStream stream) {
        HarnessService current = service;
        if (current != null) current.receiveFile(context, stream);
        else try {
            stream.close();
        } catch (java.io.IOException ignored) {
        }
    }

    @Override
    public void onQuitted(TalkContext context, Servable server) {
        if (service != null) service.disconnect(context.getSessionId());
    }
}
