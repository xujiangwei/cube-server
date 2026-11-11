package cube.service.harness.skill;

import cube.service.harness.execution.CompletionGuard;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.PackageRepository;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** Read-only validation of final release ZIPs using the actual Java package/guard validators. */
public final class BusinessPackageValidationTest {
    public static void main(String[] args) throws Exception {
        PackageRepository repository = new PackageRepository(null, null, Collections.emptyMap(), false);
        WorkflowGuard guard = new WorkflowGuard(new Properties());
        for (String name : args) {
            final PackageRepository.Validated checked;
            try (InputStream in = Files.newInputStream(Paths.get(name))) { checked = repository.validate(in); }
            CompletionGuard.Resources resources = new CompletionGuard.Resources() {
                public JSONObject json(String path) { return StrictJson.object(bytes(path)); }
                public byte[] bytes(String path) {
                    if (!PackageRepository.file(checked.manifest, path).getString("role").equals("resource")) throw new AssertionError("resource role: " + path);
                    return checked.resource(path);
                }
            };
            guard.validate(resources); guard.validateReport(resources);
            System.out.println("PASS release package " + name + " " + checked.digest);
        }
    }
}
