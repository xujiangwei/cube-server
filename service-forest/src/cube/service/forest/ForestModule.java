/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.forest;

import cube.aigc.spi.*;
import cube.common.state.AIGCStateCode;
import cube.service.forest.scene.ForestDetectionService;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Forest business module. No storage migrations, model imports or sibling-module dependencies.
 */
public final class ForestModule implements ActionModule {
    public static final String NAME = "forest", DETECT = "forestDetect", CAPABILITY = "forestGetCapability";
    private volatile ForestDetectionService detection;
    private volatile ThreadPoolExecutor executor;
    private long timeoutMs;

    public String getName() {
        return NAME;
    }

    public static List<String> declareActionNames() {
        return Arrays.asList(DETECT, CAPABILITY);
    }

    public ModuleDescriptor getDescriptor() {
        return new ModuleDescriptor(NAME, "1.0.0", AIGCSPI.VERSION, Collections.singletonList("forest"),
                Collections.singletonList("/aigc/forest"), Collections.singletonList(ForestConfig.UNIT),
                Collections.emptyList(), 5000, true);
    }

    public List<ActionBinding> getActions() {
        return Arrays.asList(new ActionBinding(DETECT, this::detect), new ActionBinding(CAPABILITY, this::capability));
    }

    public void setup(AIGCHost host) throws ModuleException {
        try {
            ForestConfig config = ForestConfig.read(host);
            timeoutMs = config.timeoutMs;
            detection = new ForestDetectionService(host, config);
            executor = new ThreadPoolExecutor(config.concurrency, config.concurrency, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(config.queueSize), job -> {
                Thread thread = new Thread(job, "forest-detection");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        } catch (RuntimeException e) {
            throw new ModuleException("Invalid forest configuration", e);
        }
    }

    private AIGCStateCode capability(ActionContext ctx) {
        ForestDetectionService current = detection;
        if (current == null) return AIGCStateCode.ModuleNotLoaded;
        ctx.respond(AIGCStateCode.Ok, current.capability());
        return AIGCStateCode.Ok;
    }

    private AIGCStateCode detect(ActionContext ctx) {
        ThreadPoolExecutor pool = executor;
        ForestDetectionService current = detection;
        if (pool == null || current == null) return AIGCStateCode.ModuleNotLoaded;
        final long submitted = System.nanoTime(), budget = timeoutMs;
        ctx.deferResponse();
        try {
            pool.execute(() -> {
                try {
                    ctx.respond(AIGCStateCode.Ok, current.detect(ctx.getToken(), ctx.getRequest().data,
                            budget - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - submitted)));
                } catch (PluginCallException e) {
                    ctx.respond(e.state, new JSONObject().put("httpStatus", e.httpStatus).put("message", e.getMessage()));
                } catch (Exception e) {
                    ctx.respond(AIGCStateCode.UnitError, new JSONObject().put("message", "Forest detection failed"));
                }
            });
        } catch (RejectedExecutionException e) {
            ctx.respond(AIGCStateCode.Processing, new JSONObject().put("message", "Forest request queue is full or stopping"));
        }
        return AIGCStateCode.Ok;
    }

    public void teardown() {
        ThreadPoolExecutor pool = executor;
        executor = null;
        detection = null;
        if (pool != null) pool.shutdown();
    }
}
