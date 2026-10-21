package cube.service.harness.test;

import cell.core.cellet.Cellet;
import cell.core.talk.*;
import cube.core.Kernel;

/**
 * 测试专用合部署 Cellet；不进入正式 Cellet jar。
 *
 * <p>与 HarnessCellet 同容器安装，提供定向停止能力：收到首个 Stuff 为
 * "stopHarness" 的请求时停止 Kernel 中的 Harness 模块，用于验证同 JVM
 * 下单元级停机不影响其他模块；任何请求都以 "sentinel-alive" 回应，
 * 证明本 Cellet 仍在服务。
 */
public final class SentinelCellet extends Cellet {
    /**
     * 构造器：以固定名 "Sentinel" 注册本 Cellet。
     */
    public SentinelCellet() {
        super("Sentinel");
    }

    /**
     * 安装：无条件成功。
     *
     * @return 固定返回 true
     */
    public boolean install() {
        return true;
    }

    /**
     * 卸载：无资源需要释放。
     */
    public void uninstall() {
    }

    /**
     * 处理监听请求：命中 "stopHarness" 时停止 Harness 模块，并一律回应
     * "sentinel-alive"。
     *
     * @param context 传输上下文，用于回包
     * @param primitive 请求原语；取第一个 Stuff 的字符串值判定指令
     */
    public void onListened(TalkContext context, Primitive primitive) {
        if (primitive.getStuff(0).getValueAsString().equals("stopHarness")) {
            ((Kernel) getNucleus().getParameter("kernel")).getModule("Harness").stop();
        }
        Primitive reply = new Primitive();
        reply.commit(new Stuff("sentinel-alive"));
        speak(context, reply);
    }
}
