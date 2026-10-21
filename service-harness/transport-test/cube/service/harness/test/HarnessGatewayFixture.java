package cube.service.harness.test;

import cell.api.Nucleus;
import cube.dispatcher.Director;
import cube.dispatcher.Performer;
import cube.dispatcher.Scope;
import cube.dispatcher.harness.HarnessGateway;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Harness 传输测试与 Python smoke 脚本共用的 dispatcher 夹具进程。
 *
 * <p>以真实 Performer 连接独立部署的 Harness service Cell，并在本地 Jetty 上
 * 挂载 {@link HarnessGateway}：业务请求先经 REST 进入网关，再通过 Cell SHM
 * 长连接同步送达服务单元；健康与运维探针仍直达 service HTTP 。</p>
 *
 * <p>启动就绪后输出 HARNESS_DISPATCHER_READY ，随后轮询停止文件；文件出现即
 * 停止服务器、销毁 Nucleus 并退出。</p>
 */
public final class HarnessGatewayFixture {

    /**
     * 测试用导演机 scope 。
     *
     * <p>仅包含 Harness cellet，确保导演机只匹配 Harness 服务单元。</p>
     */
    private static final class HarnessScope extends Scope {

        /**
         * 构造函数：把 Harness 加入 scope 的 cellet 集合。
         */
        HarnessScope() {
            cellets.add("Harness");
        }
    }

    /**
     * 夹具入口：按配置文件连接服务单元并启动本地 HTTP 网关，等待停止文件后退出。
     *
     * @param args 命令行参数：args[0] 为夹具配置文件路径，args[1] 为停止文件路径。
     * @throws Exception 当配置读取、服务启动或停止过程失败时抛出。
     */
    public static void main(String[] args) throws Exception {
        // 读取夹具配置：service.host / service.port / http.port
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) {
            p.load(in);
        }
        // 建立与 Harness service Cell 的 SHM 长连接
        Nucleus nucleus = new Nucleus();
        Performer performer = new Performer(nucleus);
        nucleus.getTalkService().addListener(performer);
        String serviceHost = p.getProperty("service.host", "127.0.0.1");
        Director director = performer.addDirector(serviceHost, Integer.parseInt(p.getProperty("service.port")),
                "127.0.0.1", 0, new HarnessScope());
        director.speaker = nucleus.getTalkService().call(serviceHost, director.endpoint.getPort());
        // 本地 Jetty 承载 REST 端点，网关把请求转交 Performer 发往服务单元
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(Integer.parseInt(p.getProperty("http.port")));
        server.addConnector(connector);
        server.setHandler(new HarnessGateway(performer));
        try {
            server.start();
            // 向调用方（测试驱动或 Python smoke）宣告 dispatcher 已就绪
            System.out.println("HARNESS_DISPATCHER_READY");
            // 轮询停止文件，出现即进入退出流程
            while (!Files.exists(Paths.get(args[1]))) {
                Thread.sleep(100);
            }
        }
        finally {
            server.stop();
            nucleus.destroy();
        }
    }
}
