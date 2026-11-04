package cube.service.test;

import cube.common.entity.Message;
import cube.hub.Type;
import cube.hub.event.AckEvent;
import cube.hub.event.Event;
import cube.hub.signal.Signal;
import cube.plugin.PluginContext;
import cube.service.hub.EventController;
import cube.service.hub.HubService;
import cube.service.hub.SignalController;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** JDK 8，无服务依赖验证 Hub 异常隔离、拒绝执行恢复及并发消费。 */
public final class HubMessageQueueTest {

    private static int assertions;

    public static void main(String[] args) throws Exception {
        testMessageFailures();
        testRejectedSubmission();
        testConcurrentPushes();
        System.out.println("HubMessageQueueTest: " + assertions + " assertions passed");
    }

    private static void testMessageFailures() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        HubService hub = new HubService(null, executor);
        List<String> received = new ArrayList<>();
        set(hub, "eventController", new EventController(hub, hub.getWorkPath()) {
            @Override public void receive(Event event) {
                if (event.getSerialNumber() == 14) throw new IllegalStateException("event handler failed");
                received.add("event:" + event.getSerialNumber());
            }
        });
        set(hub, "signalController", new SignalController(null) {
            @Override public Signal receive(Signal signal) {
                if (signal.getSerialNumber() == 12) throw new IllegalStateException("signal handler failed");
                received.add("signal:" + signal.getSerialNumber());
                return signal;
            }
        });

        push(hub, new JSONObject().put("type", Type.Event).put("event", new JSONObject()));
        push(hub, new JSONObject().put("type", Type.Event).put("event",
                new JSONObject().put("product", "WeChat").put("name", "unknown")));
        push(hub, event(11));
        push(hub, signal(12));
        push(hub, signal(13));
        push(hub, event(14));
        push(hub, event(15));
        push(hub, new JSONObject().put("type", Type.Signal).put("signal", new JSONObject()));
        push(hub, new JSONObject().put("type", Type.Signal).put("signal", new JSONObject().put("name", "unknown")));
        push(hub, signal(16));
        check(executor.tasks.size() == 1, "only one consumer scheduled");
        executor.tasks.remove().run();
        check(received.equals(Arrays.asList("event:11", "signal:13", "event:15", "signal:16")),
                "valid messages survive parsing and handler failures in order");
        checkIdle(hub);

        push(hub, signal(17));
        check(executor.tasks.size() == 1, "idle queue starts a new consumer");
        executor.tasks.remove().run();
        check(received.get(received.size() - 1).equals("signal:17"), "subsequent message consumed");
        checkIdle(hub);
    }

    private static void testRejectedSubmission() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        HubService hub = new HubService(null, executor);
        AtomicInteger received = new AtomicInteger();
        set(hub, "signalController", new SignalController(null) {
            @Override public Signal receive(Signal signal) { received.incrementAndGet(); return signal; }
        });
        executor.reject = true;
        try {
            push(hub, signal(1));
            throw new AssertionError("submission rejection hidden");
        } catch (RejectedExecutionException expected) {
            checkIdle(hub);
        }
        executor.reject = false;
        push(hub, signal(2));
        check(executor.tasks.size() == 1, "consumer can be scheduled after rejection");
        executor.tasks.remove().run();
        check(received.get() == 1, "only accepted message processed");
        checkIdle(hub);
    }

    private static void testConcurrentPushes() throws Exception {
        ExecutorService consumers = Executors.newFixedThreadPool(4);
        ExecutorService producers = Executors.newFixedThreadPool(4);
        HubService hub = new HubService(null, consumers);
        int count = 2000;
        CountDownLatch delivered = new CountDownLatch(count);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        List<Long> received = Collections.synchronizedList(new ArrayList<>());
        set(hub, "signalController", new SignalController(null) {
            @Override public Signal receive(Signal signal) {
                int running = active.incrementAndGet();
                maximum.accumulateAndGet(running, Math::max);
                try {
                    received.add(signal.getSerialNumber());
                    Thread.yield();
                    return signal;
                } finally {
                    active.decrementAndGet();
                    delivered.countDown();
                }
            }
        });
        try {
            List<Future<?>> submissions = new ArrayList<>();
            for (int producer = 0; producer < 4; ++producer) {
                final int start = producer * (count / 4);
                submissions.add(producers.submit(() -> {
                    for (int i = start; i < start + count / 4; ++i) {
                        try { push(hub, signal(i)); }
                        catch (Exception e) { throw new RuntimeException(e); }
                        Thread.yield();
                    }
                }));
            }
            for (Future<?> submission : submissions) submission.get(5, TimeUnit.SECONDS);
            check(delivered.await(5, TimeUnit.SECONDS), "concurrent pushes leave no stranded messages");
            consumers.shutdown();
            check(consumers.awaitTermination(5, TimeUnit.SECONDS), "consumer drained and stopped");
            check(received.size() == count && new HashSet<>(received).size() == count, "each message delivered once");
            check(maximum.get() == 1, "message processing remains serial");
            for (int producer = 0; producer < 4; ++producer) {
                long last = -1;
                for (long sn : received) {
                    if (sn / (count / 4) == producer) {
                        check(sn > last, "per-producer order preserved");
                        last = sn;
                    }
                }
            }
            checkIdle(hub);
        } finally {
            producers.shutdownNow();
            consumers.shutdownNow();
            producers.awaitTermination(5, TimeUnit.SECONDS);
            consumers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static JSONObject event(long sn) {
        return new JSONObject().put("type", Type.Event).put("event", new AckEvent(sn).toJSON());
    }

    private static JSONObject signal(long sn) {
        return new JSONObject().put("type", Type.Signal).put("signal", new JSONObject().put("name", "Ack").put("sn", sn));
    }

    private static void push(HubService hub, JSONObject payload) throws Exception {
        Message message = new Message("test", 1L, 1L, 2L, 0L, 1L, 0L, 0L, 0, 0, null, payload, null);
        PluginContext context = new PluginContext() {
            @Override public Object get(String key) { return "message".equals(key) ? message : null; }
            @Override public void set(String key, Object value) { }
        };
        Method push = HubService.class.getDeclaredMethod("onMessagingPush", PluginContext.class);
        push.setAccessible(true);
        try { push.invoke(hub, context); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
            throw e;
        }
    }

    private static void set(HubService hub, String name, Object value) throws Exception {
        Field field = HubService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(hub, value);
    }

    private static void checkIdle(HubService hub) throws Exception {
        Field queue = HubService.class.getDeclaredField("messageQueue"); queue.setAccessible(true);
        synchronized (queue.get(hub)) {
            Field processing = HubService.class.getDeclaredField("queueProcessing"); processing.setAccessible(true);
            check(!processing.getBoolean(hub), "consumer ownership released");
            check(((Queue<?>) queue.get(hub)).isEmpty(), "queue empty");
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        ++assertions;
    }

    private static final class RecordingExecutor extends AbstractExecutorService {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean reject;
        @Override public void execute(Runnable task) {
            if (reject) throw new RejectedExecutionException("test rejection");
            tasks.add(task);
        }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
    }
}
