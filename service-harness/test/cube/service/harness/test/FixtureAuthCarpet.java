package cube.service.harness.test;

import cell.api.Nucleus;
import cell.carpet.CellListener;
import cube.core.Kernel;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Properties;

/**
 * 独立进程测试使用的可信令牌源；生产配置与 jar 不提供此入口。
 *
 * <p>作为 CellListener 挂到测试 Cell 启动链上：若设置
 * {@code fixture.cell.listener} 系统属性则转发给指定监听器并复用其
 * Kernel，否则自建 Kernel 并写入 nucleus 参数；随后从
 * {@code harness.config} 指向的私有配置读取各角色 fixture 令牌，装入
 * {@link FixtureAuth} 并以 "Auth" 模块安装，使独立 Cell 具备 Cube Auth
 * 能力。启动失败一律包成 IllegalStateException 抛出。
 */
public final class FixtureAuthCarpet implements CellListener {
    /** 自建或复用得到的 Kernel；安装 Auth 模块与后续启动都依赖它。 */
    private Kernel kernel;

    /** 被转发的外部 CellListener；为 null 表示本类自建 Kernel 并自行驱动生命周期。 */
    private CellListener delegate;

    /**
     * Cell 预初始化：解析监听器转发、构建 Kernel、装载 fixture Auth 模块。
     *
     * @param nucleus Cell 内核句柄，用于读写 kernel 参数
     * @throws IllegalStateException 监听器实例化、配置读取或模块安装失败
     */
    public void cellPreinitialize(Nucleus nucleus) {
        try {
            String listener = System.getProperty("fixture.cell.listener", "");
            if (!listener.isEmpty()) {
                // 指定了外部监听器：其实例化并转发预初始化，复用其已创建的 Kernel。
                delegate = (CellListener) Class.forName(listener).newInstance();
                delegate.cellPreinitialize(nucleus);
                kernel = (Kernel) nucleus.getParameter("kernel");
            } else {
                // 未指定外部监听器：自建 Kernel 并登记到 nucleus。
                kernel = new Kernel(nucleus);
                nucleus.setParameter("kernel", kernel);
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(Paths.get(System.getProperty("harness.config", "config/harness.properties")))) {
                p.load(in);
            }
            FixtureAuth auth = new FixtureAuth();
            // 六个角色令牌：user/other/foreign/approver/trace 为有效期 1 小时的正常令牌，
            // expired 为已过期令牌；未配置 token 键的角色跳过。
            for (String role : new String[]{"user", "other", "foreign", "approver", "trace", "expired"}) {
                String code = p.getProperty("fixture." + role + ".token", "");
                if (code.isEmpty()) {
                    continue;
                }
                auth.add(code, p.getProperty("fixture." + role + ".domain", p.getProperty("fixture.user.domain", "example.com")),
                        Long.parseLong(p.getProperty("fixture." + role + ".contact", "10001")),
                        role.equals("expired") ? -1000 : 3600000);
            }
            kernel.installModule("Auth", auth);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Cell 初始化完成：转发外部监听器，或启动自建 Kernel。
     *
     * @param nucleus Cell 内核句柄
     */
    public void cellInitialized(Nucleus nucleus) {
        if (delegate != null) {
            delegate.cellInitialized(nucleus);
        } else {
            kernel.startup();
        }
    }

    /**
     * Cell 预销毁：转发外部监听器，或销毁自建 Kernel。
     *
     * @param nucleus Cell 内核句柄
     */
    public void cellPredestroy(Nucleus nucleus) {
        if (delegate != null) {
            delegate.cellPredestroy(nucleus);
        } else {
            kernel.dispose();
        }
    }

    /**
     * Cell 销毁完成：转发外部监听器，或关闭自建 Kernel。
     *
     * @param nucleus Cell 内核句柄
     */
    public void cellDestroyed(Nucleus nucleus) {
        if (delegate != null) {
            delegate.cellDestroyed(nucleus);
        } else {
            kernel.shutdown();
        }
    }
}
