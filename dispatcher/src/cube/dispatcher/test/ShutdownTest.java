package cube.dispatcher.test;

import cell.api.Nucleus;
import cell.api.Speakable;
import cell.core.talk.dialect.ActionDialect;
import cube.dispatcher.Director;
import cube.dispatcher.DispatcherListener;
import cube.dispatcher.Performer;
import cube.dispatcher.Scope;
import cube.dispatcher.aigc.StreamProcessor;
import cube.dispatcher.filestorage.FileChunk;
import cube.dispatcher.filestorage.FileChunkStorage;
import cube.dispatcher.stream.StreamServer;
import cube.util.HttpClientFactory;
import cube.util.HttpServer;
import cube.report.ReportService;
import cube.report.JVMReport;
import org.eclipse.jetty.client.HttpClient;

import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** JDK 8 shutdown regressions. Run in a separate JVM; no external services required. */
public class ShutdownTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Nucleus nucleus = new Nucleus();
        try {
            testHttpClients();
            testReports();
            testStreamProcessor(nucleus);
            testPartialUpload();
            testPerformer(nucleus);
            System.out.println("ShutdownTest: " + assertions + " assertions passed");
        } finally {
            nucleus.destroy();
        }
    }

    private static void testHttpClients() throws Exception {
        Constructor<HttpClientFactory> constructor = HttpClientFactory.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        HttpClientFactory factory = constructor.newInstance();
        HttpClient idle = factory.borrowHttpClient();
        HttpClient borrowed = factory.borrowHttpClient();
        factory.returnHttpClient(idle);
        try {
            factory.close();
            check(idle.isStopped(), "idle HTTP client stopped");
            check(borrowed.isStopped(), "borrowed HTTP client stopped");
            check(factory.getBorrowedCount() == 0, "borrowed clients released");
            factory.returnHttpClient(borrowed);
            factory.close();
            try {
                factory.borrowHttpClient();
                throw new AssertionError("closed factory accepted borrow");
            } catch (IllegalStateException expected) {
                check(true, "closed factory rejects new clients");
            }
        } finally {
            factory.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void testStreamProcessor(Nucleus nucleus) throws Exception {
        AtomicInteger networkCalls = new AtomicInteger();
        Performer performer = new Performer(nucleus) {
            @Override public ActionDialect syncTransmit(String name, ActionDialect request) {
                networkCalls.incrementAndGet();
                throw new AssertionError("shutdown must not wait for remote file deletion");
            }
        };
        StreamProcessor processor = new StreamProcessor(performer, null);
        ExecutorService executor = (ExecutorService) get(processor, "executorService");
        CountDownLatch started = new CountDownLatch(1);
        executor.submit(() -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        });
        check(started.await(2, TimeUnit.SECONDS), "stream worker running");
        processor.register("active", null);
        ((Map<String, Object>) get(processor, "streamFileCodeMap")).put("active", Arrays.asList("file1", "file2"));
        processor.stop();
        processor.stop();
        processor.register("late", null);
        check(executor.awaitTermination(2, TimeUnit.SECONDS), "stream executor interrupted and terminated");
        check(((Map<?, ?>) get(processor, "registerMap")).isEmpty(), "stream registrations cleared and late registration ignored");
        check(networkCalls.get() == 0, "stop performs no synchronous downstream cleanup");
    }

    private static void testReports() throws Exception {
        Constructor<ReportService> constructor = ReportService.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        ReportService service = constructor.newInstance();
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            Thread console = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    accepted.countDown();
                    release.await(); // A console that accepts connections but never replies.
                } catch (Exception ignored) { }
            });
            console.setDaemon(true);
            console.start();
            service.addHost("127.0.0.1", server.getLocalPort());
            service.submitReport(new JVMReport("shutdown-test", System.currentTimeMillis()));
            check(accepted.await(3, TimeUnit.SECONDS), "report HTTP request awaiting console response");
            service.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!((java.util.Set<?>) get(service, "submitThreads")).isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            check(((java.util.Set<?>) get(service, "submitThreads")).isEmpty(), "report submission interrupted and its HTTP client stopped");
            service.submitReport(new JVMReport("late", System.currentTimeMillis()));
            check(service.getQueueLength() == 0, "reporting rejects submissions after stop");
        } finally {
            release.countDown();
            service.close();
        }
    }

    private static void testPartialUpload() throws Exception {
        Path directory = Files.createTempDirectory("cube-chunk-shutdown-");
        FileChunkStorage storage = new FileChunkStorage(directory.toString());
        storage.open(null, null);
        ExecutorService executor = (ExecutorService) get(storage, "executor");
        try {
            Class<?> storeClass = Class.forName("cube.dispatcher.filestorage.FileChunkStorage$FileChunkStore");
            Constructor<?> storeConstructor = storeClass.getDeclaredConstructor(FileChunkStorage.class, String.class, String.class);
            storeConstructor.setAccessible(true);
            Object store = storeConstructor.newInstance(storage, "partial", "token");
            Method add = storeClass.getDeclaredMethod("add", FileChunk.class);
            add.setAccessible(true);
            add.invoke(store, new FileChunk(1, "test", "token", "partial.bin", 2, 0, 0, 1, new byte[]{42}));
            Class<?> streamClass = Class.forName("cube.dispatcher.filestorage.FileChunkStorage$ChunkInputStream");
            Constructor<?> streamConstructor = streamClass.getDeclaredConstructor(FileChunkStorage.class, storeClass);
            streamConstructor.setAccessible(true);
            InputStream stream = (InputStream) streamConstructor.newInstance(storage, store);
            check(stream.read() == 42, "first upload chunk readable");
            CountDownLatch reading = new CountDownLatch(1);
            Future<Boolean> blocked = executor.submit(() -> {
                reading.countDown();
                try { return stream.read() == -1; }
                catch (InterruptedIOException expected) { return Thread.currentThread().isInterrupted(); }
            });
            check(reading.await(2, TimeUnit.SECONDS), "partial upload reader entered");
            Thread.sleep(150);
            check(!blocked.isDone(), "reader waits for missing second chunk");
            storage.close();
            check(blocked.get(2, TimeUnit.SECONDS), "shutdown releases missing-chunk wait immediately");
            check(executor.awaitTermination(2, TimeUnit.SECONDS), "file chunk executor terminated");
            storage.close();
            stream.close();
        } finally {
            storage.close();
            Files.deleteIfExists(directory);
        }
    }

    private static void testPerformer(Nucleus nucleus) throws Exception {
        Performer performer = new Performer(nucleus);
        ExecutorService executor = Executors.newFixedThreadPool(1);
        set(performer, "executor", executor);
        AtomicInteger httpStops = new AtomicInteger();
        set(performer, "httpServer", new HttpServer() {
            @Override public void stop() { httpStops.incrementAndGet(); }
        });
        set(performer, "streamServer", new StreamServer() {
            @Override public void stop() { throw new IllegalStateException("injected stream stop failure"); }
        });
        Scope scope = new Scope() {{ this.cellets.add("Auth"); }};
        Director director = performer.addDirector("127.0.0.1", 1, "127.0.0.1", 2, scope);
        CountDownLatch sent = new CountDownLatch(1);
        director.speaker = (Speakable) Proxy.newProxyInstance(Speakable.class.getClassLoader(), new Class<?>[]{Speakable.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isReady")) return true;
                    if (method.getName().equals("speak")) { sent.countDown(); return true; }
                    throw new UnsupportedOperationException(method.getName());
                });
        Future<ActionDialect> pending = executor.submit(() -> performer.syncTransmit(director, "Auth", new ActionDialect("pending"), 480000));
        check(sent.await(2, TimeUnit.SECONDS), "eight minute downstream request pending");
        nucleus.setParameter("performer", performer);
        DispatcherListener listener = new DispatcherListener();
        listener.cellPredestroy(nucleus);
        check(pending.get(2, TimeUnit.SECONDS) == null, "predestroy cancels pending downstream request");
        check(executor.isTerminated(), "performer worker terminated");
        check(httpStops.get() == 1, "HTTP cleanup continues after stream cleanup failure");
        check(!performer.tryTransmit(director, "Auth", new ActionDialect("late")), "new downstream requests refused after stop");
        listener.cellDestroyed(nucleus);
        check(httpStops.get() == 1, "destroy after predestroy is idempotent");
        new Performer(nucleus).stop();
        check(true, "partially initialized performer can stop");
    }

    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = Performer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        ++assertions;
    }
}
