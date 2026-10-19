package cube.service.harness.test;

import cell.api.Nucleus;
import cell.carpet.CellListener;
import cube.core.Kernel;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Properties;

/** 独立进程测试使用的可信令牌源；生产配置与 jar 不提供此入口。 */
public final class FixtureAuthCarpet implements CellListener {
    private Kernel kernel;
    private CellListener delegate;

    public void cellPreinitialize(Nucleus nucleus) {
        try {
            String listener = System.getProperty("fixture.cell.listener", "");
            if (!listener.isEmpty()) {
                delegate = (CellListener) Class.forName(listener).newInstance();
                delegate.cellPreinitialize(nucleus);
                kernel = (Kernel) nucleus.getParameter("kernel");
            } else {
                kernel = new Kernel(nucleus);
                nucleus.setParameter("kernel", kernel);
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(Paths.get(System.getProperty("harness.config", "config/harness.properties")))) {
                p.load(in);
            }
            FixtureAuth auth = new FixtureAuth();
            for (String role : new String[]{"user", "other", "foreign", "approver", "trace", "expired"}) {
                String code = p.getProperty("fixture." + role + ".token", "");
                if (code.isEmpty()) continue;
                auth.add(code, p.getProperty("fixture." + role + ".domain", p.getProperty("fixture.user.domain", "example.com")),
                        Long.parseLong(p.getProperty("fixture." + role + ".contact", "10001")),
                        role.equals("expired") ? -1000 : 3600000);
            }
            kernel.installModule("Auth", auth);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public void cellInitialized(Nucleus nucleus) { if (delegate != null) delegate.cellInitialized(nucleus); else kernel.startup(); }
    public void cellPredestroy(Nucleus nucleus) { if (delegate != null) delegate.cellPredestroy(nucleus); else kernel.dispose(); }
    public void cellDestroyed(Nucleus nucleus) { if (delegate != null) delegate.cellDestroyed(nucleus); else kernel.shutdown(); }
}
