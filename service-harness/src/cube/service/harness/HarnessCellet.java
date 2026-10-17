/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import cell.api.Servable;
import cell.core.talk.LiteralBase;
import cell.core.talk.Primitive;
import cell.core.talk.Stuff;
import cell.core.talk.TalkContext;
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
        // Check the envelope before queueing or decoding its original STRING payload.
        if (primitive.numStuff() != 1 || primitive.getStuff(0).getLiteralBase() != LiteralBase.STRING || primitive.getStuff(0).getValue().length > 262144) {
            reply(context, HarnessService.error("", "INVALID_ENVELOPE"));
            return;
        }
        final HarnessService current = service;
        try {
            execute(() -> {
                if (current != null && context.isValid())
                    reply(context, current.process(primitive.getStuff(0).getValue(), context.getSessionId(), context::isValid));
            });
        } catch (RejectedExecutionException e) {
            reply(context, HarnessService.error("", "HARNESS_BUSY"));
        }
    }

    @Override
    public void onQuitted(TalkContext context, Servable server) {
        if (service != null) service.disconnect(context.getSessionId());
    }
}
