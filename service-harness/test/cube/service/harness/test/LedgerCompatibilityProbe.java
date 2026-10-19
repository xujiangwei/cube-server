package cube.service.harness.test;

import cube.service.harness.store.*;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import org.json.*;

/** Compiled against the public ledger API; also executed with archived v6/v7 jars first. */
public final class LedgerCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(args[1]))) {
            p.load(in);
        }
        JdbcRunStore store = new JdbcRunStore(new StoreConfig(p));
        if (args[0].equals("seed")) {
            JSONObject receipt =
                    store.create(
                            "n16-tenant",
                            "n16-subject",
                            "n16-upgrade-" + Paths.get(args[2]).getFileName(),
                            "upgrade-probe",
                            new JSONObject().put("instruction", "preserve me"));
            Files.write(
                    Paths.get(args[2]),
                    receipt.toString(2).getBytes("UTF-8"),
                    StandardOpenOption.CREATE_NEW);
        } else {
            String run =
                    new JSONObject(new String(Files.readAllBytes(Paths.get(args[2])), "UTF-8"))
                            .getString("runId");
            JSONObject row = store.read("n16-tenant", "n16-subject", run);
            if (args[0].equals("finish")) {
                long version = row.getJSONObject("snapshot").getLong("version");
                JdbcRunStore.Lease lease =
                        store.acquire(
                                "n16-tenant",
                                "n16-subject",
                                run,
                                "compatible-recovery-node",
                                version,
                                60000);
                version =
                        store.read("n16-tenant", "n16-subject", run)
                                .getJSONObject("snapshot")
                                .getLong("version");
                store.transition(lease, version, "running", new JSONObject());
                version =
                        store.read("n16-tenant", "n16-subject", run)
                                .getJSONObject("snapshot")
                                .getLong("version");
                store.finish(
                        lease,
                        version,
                        "succeeded",
                        "confirmed",
                        new JSONObject().put("preserved", true),
                        new JSONArray());
            } else if (!row.getJSONObject("snapshot").getString("state").equals("succeeded"))
                throw new AssertionError("final missing");
        }
        System.out.println("PASS: " + args[0] + " schema=" + StoreMigrations.VERSION);
    }
}
