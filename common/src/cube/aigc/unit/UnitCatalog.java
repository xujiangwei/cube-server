package cube.aigc.unit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Installed contracts, independent of current model readiness and tenant authorization. */
public final class UnitCatalog {
    private final Map<String, UnitOperationDescriptor> operations = new LinkedHashMap<>();
    private final Map<String, UnitOperationDescriptor> brokers = new LinkedHashMap<>();
    public final String catalogDigest;

    public UnitCatalog(JSONObject installed) {
        JSONObject catalog = UnitJson.copy(installed);
        if (catalog.getInt("schemaVersion") != 1) throw new IllegalArgumentException("UNIT_CATALOG_VERSION_UNSUPPORTED");
        catalogDigest = catalog.getString("catalogDigest");
        catalog.remove("catalogDigest");
        if (!catalogDigest.equals(UnitJson.digest(catalog))) throw new IllegalArgumentException("UNIT_CATALOG_DIGEST_MISMATCH");
        Map<String, String> units = new LinkedHashMap<>();
        JSONArray unitRows = catalog.getJSONArray("units");
        if (unitRows.length() == 0 || unitRows.length() > 1024) throw new IllegalArgumentException("UNIT_CATALOG_SIZE_INVALID");
        for (int i = 0; i < unitRows.length(); i++) {
            JSONObject unit = unitRows.getJSONObject(i);
            if (units.put(UnitJson.id(unit.getString("key")), UnitJson.id(unit.getString("name"))) != null)
                throw new IllegalArgumentException("UNIT_CATALOG_DUPLICATE_UNIT");
        }
        JSONArray rows = catalog.getJSONArray("operations");
        if (rows.length() == 0 || rows.length() > 4096) throw new IllegalArgumentException("UNIT_CATALOG_SIZE_INVALID");
        java.util.Set<String> tools = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            UnitOperationDescriptor descriptor = new UnitOperationDescriptor(rows.getJSONObject(i));
            if (!descriptor.unitName.equals(units.get(rows.getJSONObject(i).getString("unitKey"))))
                throw new IllegalArgumentException("UNIT_OPERATION_UNKNOWN_UNIT");
            if (operations.put(descriptor.operationId, descriptor) != null
                    || brokers.put(descriptor.brokerOperation, descriptor) != null || !tools.add(descriptor.toolName))
                throw new IllegalArgumentException("UNIT_OPERATION_DUPLICATE");
        }
        java.util.Set<String> visiting = new java.util.HashSet<>(), visited = new java.util.HashSet<>();
        for (String id : operations.keySet()) checkDependencies(id, visiting, visited);
    }

    private void checkDependencies(String id, java.util.Set<String> visiting, java.util.Set<String> visited) {
        if (visited.contains(id)) return;
        if (!operations.containsKey(id)) throw new IllegalArgumentException("UNIT_DEPENDENCY_MISSING");
        if (!visiting.add(id)) throw new IllegalArgumentException("UNIT_DEPENDENCY_CYCLE");
        JSONArray dependencies = operations.get(id).toJSON().getJSONArray("dependencies");
        for (int i = 0; i < dependencies.length(); i++) checkDependencies(dependencies.getString(i), visiting, visited);
        visiting.remove(id); visited.add(id);
    }

    public static UnitCatalog installed() {
        try (InputStream in = UnitCatalog.class.getResourceAsStream("unit-operations.json")) {
            if (in == null) throw new IllegalStateException("UNIT_CATALOG_NOT_INSTALLED");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) != -1;) {
                out.write(buffer, 0, count);
                if (out.size() > 4194304) throw new IllegalStateException("UNIT_CATALOG_SIZE_INVALID");
            }
            return new UnitCatalog(new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)));
        } catch (IOException failure) { throw new IllegalStateException("UNIT_CATALOG_READ_FAILED", failure); }
    }

    public UnitOperationDescriptor operation(String id) {
        UnitOperationDescriptor value = operations.get(id);
        if (value == null) throw new IllegalArgumentException("UNIT_OPERATION_UNSUPPORTED");
        return value;
    }

    public UnitOperationDescriptor brokerOperation(String id) {
        UnitOperationDescriptor value = brokers.get(id);
        if (value == null) throw new IllegalArgumentException("UNIT_OPERATION_UNSUPPORTED");
        return value;
    }

    public List<UnitOperationDescriptor> operations() {
        return Collections.unmodifiableList(new ArrayList<>(operations.values()));
    }
}
