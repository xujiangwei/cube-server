package cube.service.test;

import cell.api.Nucleus;
import cell.core.talk.*;
import cell.core.talk.dialect.ActionDialect;
import cube.common.Packet;
import cube.common.entity.*;
import cube.service.aigc.AIGCCellet;
import cube.service.aigc.AIGCService;
import cube.service.aigc.event.EventCenter;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * 可选的真实 SHM 联调测试。参数依次为：Python 可执行文件、Python 工程根目录、桥接脚本。
 */
public final class SpecializedDetectionShmTest {
    /**
     * 测试入口。
     *
     * @param args 依次为 Python 可执行文件、Python 工程根目录、桥接脚本
     * @throws Exception 启动或联调失败时抛出
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("python Python-project-root bridge.py");
        }
        int port; try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Nucleus nucleus = new Nucleus(); Bridge cellet = new Bridge(); Process python = null;
        Path log = Files.createTempFile("specialized-shm-", ".log");
        try {
            cube.core.AbstractCellet.initialize(null);
            SpecializedDetectionTest.check(nucleus.getCelletService().installCellet(cellet), "cellet installed");
            SpecializedDetectionTest.check(nucleus.getTalkService().startServer("127.0.0.1", port) != null, "SHM listening");
            SpecializedDetectionTest.check(nucleus.getCelletService().activateCellet(port, cellet), "cellet activated");
            // Cell 的接收器在启动后的三秒安全锁内会拒绝连接。
            Thread.sleep(3200);
            python = new ProcessBuilder(args[0], "-u", args[2], args[1], Integer.toString(port))
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            SpecializedDetectionTest.check(cellet.ready.await(15, TimeUnit.SECONDS), "dummy Python connected: " + log);
            EventCenter.getInstance().start(cellet.service);
            for (String profile : new String[]{"fire-smoke", "uav-fire-smoke"}) {
                SpecializedDetectionTest.Reply reply = invoke(cellet, SpecializedDetectionTest.image(profile)); reply.success();
                SpecializedDetectionTest.check(reply.output.resultPayload.getInt("width") == 32
                        && reply.output.resultPayload.getJSONArray("refs").length() == 0, "real Python empty image result");
            }
            SpecializedDetectionTest.Reply start = invoke(cellet, SpecializedDetectionTest.start()); start.success();
            invoke(cellet, SpecializedDetectionTest.control("status", start.id())).success();
            SpecializedDetectionTest.Reply stop = invoke(cellet, SpecializedDetectionTest.control("stop", start.id())); stop.success();
            SpecializedDetectionTest.check("stopped".equals(stop.output.resultPayload.getString("status")), "dummy stop terminal");
            SpecializedDetectionTest.Reply repeat = invoke(cellet, SpecializedDetectionTest.control("stop", start.id())); repeat.success();
            cellet.speak(cellet.context, new Packet("bridgeDone", new org.json.JSONObject()).toDialect());
            SpecializedDetectionTest.check(python.waitFor(10, TimeUnit.SECONDS) && python.exitValue() == 0, "Python clean shutdown: " + log);
            System.out.println("SpecializedDetectionShmTest: " + SpecializedDetectionTest.assertions + " assertions passed; dummy only, no weights/camera");
        } finally {
            if (python != null && python.isAlive()) {
                python.destroyForcibly();
            }
            EventCenter.getInstance().stop();cellet.service.getSpecializedDetections().close();
            cellet.service.executor.shutdown();cellet.service.storage.close();nucleus.destroy();cube.core.AbstractCellet.halt();
        }
    }

    /**
     * 发起一次检测调用。
     *
     * @param cellet 桥接 Cellet
     * @param input  检测输入 JSON
     * @return 可等待的结果对象
     */
    private static SpecializedDetectionTest.Reply invoke(Bridge cellet, org.json.JSONObject input) {
        SpecializedDetectionTest.Reply reply = new SpecializedDetectionTest.Reply();
        cellet.service.executeSpecializedDetection("token", "channel", new SpecializedDetectionInput(input), reply); return reply;
    }

    /**
     * 把单元连接转接到假服务的桥接 Cellet。
     */
    static final class Bridge extends AIGCCellet {
        /** 假服务实例。 */
        final SpecializedDetectionTest.FakeService service;
        /** 桥接就绪信号。 */
        final CountDownLatch ready = new CountDownLatch(1);
        /** 最近一次通信上下文。 */
        volatile TalkContext context;

        /**
         * 构造方法。
         *
         * @throws Exception 反射注入服务失败时抛出
         */
        Bridge() throws Exception {
            service = new SpecializedDetectionTest.FakeService(this);
            Field field = AIGCCellet.class.getDeclaredField("service");field.setAccessible(true);field.set(this, service);
        }
        @Override public boolean install() { return true; }
        @Override public void uninstall() { }
        @Override public void onListened(TalkContext context, Primitive primitive) {
            ActionDialect dialect = new ActionDialect(primitive);
            if ("bridgeReady".equals(dialect.getName())) {
                this.context = context;
                service.units.add(new AIGCUnit(new Contact(100L, "domain"), new AICapability("SpecializedDetection",
                        "ComputerVision", "1.0", Collections.singletonList("ObjectDetection"), "dummy"), context));
                ready.countDown();
            } else super.onListened(context, primitive);
        }
    }
}
