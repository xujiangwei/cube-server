package cube.service.test;

import cell.core.talk.dialect.ActionDialect;
import cube.service.aigc.Responder;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** JDK 8，无网络验证应答、结束和中断信号在等待前后到达的行为。 */
public final class ResponderTest {

    private static int assertions;

    public static void main(String[] args) throws Exception {
        ActionDialect request = new ActionDialect("request");
        Responder early = new Responder(1, request);
        ActionDialect response = new ActionDialect("response");
        response.addParam(Responder.NotifierKey, request.getParamAsJson(Responder.NotifierKey));
        early.notifyResponse(response);
        check(promptly(() -> early.waitingFor(10000)) == response, "reply before wait returns immediately");
        check(!response.containsParam(Responder.NotifierKey), "internal notifier removed");
        early.notifyResponse(new ActionDialect("duplicate"));
        early.finish();
        check(promptly(() -> early.waitingFor(10000)) == response, "first response remains stable");

        Responder finished = new Responder(2, new ActionDialect("request"));
        finished.finish();
        check(promptly(() -> finished.waitingFor(0)) == null, "finish before unlimited wait returns immediately");
        finished.notifyResponse(new ActionDialect("late"));
        check(promptly(() -> finished.waitingFor(10000)) == null, "finish is terminal");

        testWaiting(false);
        testWaiting(true);

        Responder timeout = new Responder(4, new ActionDialect("request"));
        check(promptly(() -> timeout.waitingFor(30)) == null, "no reply times out");
        timeout.notifyResponse(response);
        check(promptly(() -> timeout.waitingFor(10000)) == response, "late completion is visible to subsequent wait");

        AtomicBoolean interrupted = new AtomicBoolean();
        Responder cancelled = new Responder(5, new ActionDialect("request"));
        check(promptly(() -> {
            Thread.currentThread().interrupt();
            ActionDialect result = cancelled.waitingFor(10000);
            interrupted.set(Thread.currentThread().isInterrupted());
            return result;
        }) == null, "interrupted wait returns no response");
        check(interrupted.get(), "interrupted status preserved");

        try {
            timeout.waitingFor(-1);
            throw new AssertionError("negative timeout accepted");
        } catch (IllegalArgumentException expected) {
            check(true, "negative timeout rejected");
        }
        System.out.println("ResponderTest: " + assertions + " assertions passed");
    }

    private static void testWaiting(boolean finish) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Responder responder = new Responder(3, new ActionDialect("request"));
        CountDownLatch ready = new CountDownLatch(2);
        Callable<ActionDialect> wait = () -> {
            ready.countDown();
            return responder.waitingFor(0);
        };
        try {
            Future<ActionDialect> first = executor.submit(wait);
            Future<ActionDialect> second = executor.submit(wait);
            check(ready.await(2, TimeUnit.SECONDS), "waiters started");
            ActionDialect response = finish ? null : new ActionDialect("response");
            if (finish) responder.finish();
            else responder.notifyResponse(response);
            check(first.get(2, TimeUnit.SECONDS) == response, "first waiter released");
            check(second.get(2, TimeUnit.SECONDS) == response, "all waiters released");
        } finally {
            executor.shutdownNow();
            check(executor.awaitTermination(2, TimeUnit.SECONDS), "waiters stopped");
        }
    }

    private static ActionDialect promptly(Callable<ActionDialect> call) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(call).get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        ++assertions;
    }
}
