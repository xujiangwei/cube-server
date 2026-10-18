/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.CatalogStore;
import cube.service.harness.store.JdbcRunStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable content-addressed releases. Trusted CPU execution requires explicit opt-in. */
public final class PackageRepository {

  private final CatalogStore catalog;

  private final AssetFiles files;

  private final Map<String, PublicKey> trustedKeys;

  private final boolean unsignedLocalDevelopment;

  private final boolean trustedExecutables;

  public PackageRepository(
      CatalogStore catalog,
      AssetFiles files,
      Map<String, PublicKey> keys,
      boolean unsignedLocalDevelopment) {
    this(catalog, files, keys, unsignedLocalDevelopment, false);
  }

  public PackageRepository(
      CatalogStore catalog,
      AssetFiles files,
      Map<String, PublicKey> keys,
      boolean unsignedLocalDevelopment,
      boolean trustedExecutables) {
    this.catalog = catalog;
    this.files = files;
    this.trustedKeys = new HashMap<>(keys);
    this.unsignedLocalDevelopment = unsignedLocalDevelopment;
    this.trustedExecutables = trustedExecutables;
  }

  private static int u16(byte[] b, int p) {
    return (b[p] & 255) | ((b[p + 1] & 255) << 8);
  }

  private static long u32(byte[] b, int p) {
    return ((long) u16(b, p)) | ((long) u16(b, p + 2) << 16);
  }

  private static String utf8(byte[] b) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(b))
          .toString();
    } catch (CharacterCodingException e) {
      throw RuntimeSupport.error("INVALID_ASSET_UTF8");
    }
  }

  /** Central metadata is checked because ZipInputStream alone hides Unix symlink attributes. */
  private static Set<String> central(byte[] zip) {
    int end = -1;
    for (int p = zip.length - 22; p >= Math.max(0, zip.length - 65557); p--)
      if (u32(zip, p) == 0x06054b50L && p + 22 + u16(zip, p + 20) == zip.length) {
        end = p;
        break;
      }
    if (end < 0
        || u16(zip, end + 4) != 0
        || u16(zip, end + 6) != 0
        || u16(zip, end + 8) != u16(zip, end + 10))
      throw RuntimeSupport.error("UNSUPPORTED_PACKAGE_ZIP");
    int count = u16(zip, end + 10);
    long offset = u32(zip, end + 16), size = u32(zip, end + 12);
    if (count < 1 || count > 65 || offset + size != end || offset > Integer.MAX_VALUE)
      throw RuntimeSupport.error("PACKAGE_ZIP_LIMIT");
    int p = (int) offset;
    Set<String> paths = new HashSet<>(), folded = new HashSet<>();
    long total = 0;
    for (int i = 0; i < count; i++) {
      if (p + 46 > end || u32(zip, p) != 0x02014b50L)
        throw RuntimeSupport.error("INVALID_PACKAGE_ZIP");
      int flags = u16(zip, p + 8),
          method = u16(zip, p + 10),
          len = u16(zip, p + 28),
          extra = u16(zip, p + 30),
          comment = u16(zip, p + 32);
      long compressed = u32(zip, p + 20), expanded = u32(zip, p + 24), attrs = u32(zip, p + 38);
      int mode = (int) (attrs >>> 16) & 0170000;
      if ((flags & 1) != 0
          || (method != 0 && method != 8)
          || mode == 0120000
          || (mode != 0 && mode != 0100000)
          || (attrs & 0x10) != 0
          || p + 46 + len + extra + comment > end)
        throw RuntimeSupport.error("UNSAFE_PACKAGE_ENTRY");
      String name = RuntimeSupport.path(utf8(Arrays.copyOfRange(zip, p + 46, p + 46 + len)));
      if (!paths.add(name) || !folded.add(name.toLowerCase(Locale.ROOT)))
        throw RuntimeSupport.error("DUPLICATE_PACKAGE_PATH");
      total += expanded;
      if (expanded > 262144
          || total > 2097152
          || (expanded > 1024 && expanded > Math.max(1, compressed) * 100))
        throw RuntimeSupport.error("PACKAGE_ZIP_LIMIT");
      p += 46 + len + extra + comment;
    }
    if (p != end) throw RuntimeSupport.error("INVALID_PACKAGE_ZIP");
    return paths;
  }

  public static final class Validated {
    public final String digest;
    public final JSONObject manifest;
    final Map<String, byte[]> contents;

    Validated(JSONObject manifest, Map<String, byte[]> contents) {
      this.manifest = manifest;
      this.contents = contents;
      digest = JdbcRunStore.hash(manifest);
    }
  }

  public Validated validate(InputStream input) throws Exception {
    byte[] zip = RuntimeSupport.read(input, 8388608);
    Set<String> central = central(zip);
    Map<String, byte[]> entries = new TreeMap<>();
    long total = 0;
    try (ZipInputStream in =
        new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        String name = RuntimeSupport.path(entry.getName());
        if (!central.contains(name) || entries.containsKey(name))
          throw RuntimeSupport.error("INVALID_PACKAGE_ZIP");
        byte[] data = RuntimeSupport.read(in, 262144);
        total += data.length;
        if (total > 2097152) throw RuntimeSupport.error("PACKAGE_ZIP_LIMIT");
        entries.put(name, data);
      }
    }
    if (!entries.keySet().equals(central) || !entries.containsKey("manifest.json"))
      throw RuntimeSupport.error("PACKAGE_MANIFEST_MISSING");
    JSONObject manifest = StrictJson.object(entries.remove("manifest.json"));
    int schemaVersion = manifest.getInt("schemaVersion");
    if (schemaVersion < 1 || schemaVersion > 6)
      throw RuntimeSupport.error("UNKNOWN_PACKAGE_SCHEMA");
    if (schemaVersion == 3) throw RuntimeSupport.error("LEGACY_BUILTIN_PACKAGE_DISABLED");
    RuntimeSupport.validate("manifest-v" + schemaVersion, manifest);
    if (schemaVersion >= 2 && (!trustedExecutables || total > 65536))
      throw RuntimeSupport.error("EXECUTABLE_RUNTIME_NOT_CERTIFIED");
    Set<String> declared = new HashSet<>();
    int skills = 0, locks = 0;
    JSONArray list = manifest.getJSONArray("files");
    Map<String, byte[]> blobs = new TreeMap<>();
    for (int i = 0; i < list.length(); i++) {
      JSONObject file = list.getJSONObject(i);
      String name = RuntimeSupport.path(file.getString("path"));
      if (!declared.add(name)) throw RuntimeSupport.error("DUPLICATE_PACKAGE_PATH");
      byte[] data = entries.get(name);
      if (data == null
          || data.length != file.getLong("size")
          || !RuntimeSupport.sha(data).equals(file.getString("sha256")))
        throw RuntimeSupport.error("PACKAGE_CONTENT_MISMATCH");
      utf8(data);
      blobs.put(file.getString("sha256"), data);
      if (file.getString("role").equals("skill")) {
        skills++;
        if (!name.equals("SKILL.md")) throw RuntimeSupport.error("PACKAGE_SKILL_INVALID");
      }
      if (file.getString("role").equals("lock")) {
        locks++;
        if (!name.equals("dependencies.lock.json"))
          throw RuntimeSupport.error("PACKAGE_LOCK_INVALID");
      }
      if (file.getString("role").equals("schema"))
        RuntimeSupport.toolSchema(StrictJson.object(data));
      if (file.getString("role").equals("entrypoint")
          && (!trustedExecutables || schemaVersion < 2 || !name.endsWith(".py")))
        throw RuntimeSupport.error("EXECUTABLE_RUNTIME_NOT_CERTIFIED");
    }
    if (!declared.equals(entries.keySet()) || skills != 1 || locks != 1)
      throw RuntimeSupport.error("UNDECLARED_PACKAGE_FILE");
    JSONObject dependencies =
        RuntimeSupport.parse("dependencies-v1", entries.get("dependencies.lock.json"));
    if (schemaVersion >= 2 && dependencies.getJSONArray("dependencies").length() != 0)
      throw RuntimeSupport.error("RUNNER_STDLIB_ONLY");
    RuntimeSupport.strings(manifest.getJSONArray("permissions"));
    Set<String> tools = new HashSet<>();
    for (int i = 0; i < manifest.getJSONArray("tools").length(); i++) {
      JSONObject tool = manifest.getJSONArray("tools").getJSONObject(i);
      String name = tool.getString("name");
      if (!name.startsWith(manifest.getString("name").replace('/', '.') + ".") || !tools.add(name))
        throw RuntimeSupport.error("RESERVED_TOOL_NAME");
      boolean executable = tool.getString("kind").equals("executable");
      if (executable && (!trustedExecutables || schemaVersion < 2))
        throw RuntimeSupport.error("EXECUTABLE_RUNTIME_NOT_CERTIFIED");
      for (String field : Arrays.asList("inputSchema", "outputSchema")) {
        String path = RuntimeSupport.path(tool.getString(field));
        if (!declared.contains(path)) throw RuntimeSupport.error("PACKAGE_SCHEMA_MISSING");
        if (!file(manifest, path).getString("role").equals("schema"))
          throw RuntimeSupport.error("PACKAGE_SCHEMA_MISSING");
        RuntimeSupport.toolSchema(StrictJson.object(entries.get(path)));
      }
      if (executable) {
        if (tool.has("resourcePath")
            || !tool.has("entrypoint")
            || !declared.contains(RuntimeSupport.path(tool.getString("entrypoint")))
            || !file(manifest, tool.getString("entrypoint")).getString("role").equals("entrypoint")
            || !tool.getString("effect").equals("read-only")
            || (schemaVersion == 2
                && (!tool.getString("permission").equals("console.status.read")
                    || !tool.getString("brokerOperation").equals("mockconsole.query_status"))))
          throw RuntimeSupport.error("PACKAGE_EXECUTABLE_INVALID");
      } else {
        if (schemaVersion != 1
            || !tool.has("resourcePath")
            || !declared.contains(RuntimeSupport.path(tool.getString("resourcePath")))
            || tool.has("entrypoint")) throw RuntimeSupport.error("PACKAGE_RESOURCE_TOOL_INVALID");
        if (!Arrays.asList("skill", "resource")
                .contains(file(manifest, tool.getString("resourcePath")).getString("role"))
            || !tool.getString("permission").equals("resource.read"))
          throw RuntimeSupport.error("PACKAGE_RESOURCE_TOOL_INVALID");
      }
      if (!RuntimeSupport.strings(manifest.getJSONArray("permissions"))
          .contains(tool.getString("permission")))
        throw RuntimeSupport.error("PACKAGE_PERMISSION_UNDECLARED");
    }
    return new Validated(manifest, blobs);
  }

  /** Actor/source come from trusted administration, never from the uploaded manifest. */
  public JSONObject upload(
      String tenant, String actor, String source, InputStream input, String keyId, String signature)
      throws Exception {
    RuntimeSupport.id(tenant);
    RuntimeSupport.id(actor);
    Validated checked = validate(input);
    boolean signed = keyId != null && !keyId.isEmpty();
    if (signed) {
      PublicKey key = trustedKeys.get(keyId);
      if (key == null) throw RuntimeSupport.error("UNTRUSTED_PACKAGE_SIGNER");
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(key);
      verifier.update(checked.digest.getBytes(StandardCharsets.US_ASCII));
      if (!verifier.verify(Base64.getDecoder().decode(signature)))
        throw RuntimeSupport.error("PACKAGE_SIGNATURE_INVALID");
    } else if (!unsignedLocalDevelopment || !source.equals("local-author"))
      throw RuntimeSupport.error("PACKAGE_SIGNATURE_REQUIRED");
    if (source.length() > 1024) throw RuntimeSupport.error("PACKAGE_SOURCE_INVALID");
    JSONObject runtime = checked.manifest.getJSONObject("runtime");
    catalog.profile(tenant, "runtime", runtime.getString("id"), runtime.getString("version"));
    JSONObject lock =
        RuntimeSupport.parse(
            "dependencies-v1",
            checked.contents.get(
                file(checked.manifest, "dependencies.lock.json").getString("sha256")));
    JSONArray dependencyDigests = new JSONArray();
    for (int i = 0; i < lock.getJSONArray("dependencies").length(); i++) {
      JSONObject dep = lock.getJSONArray("dependencies").getJSONObject(i),
          release = catalog.release(tenant, dep.getString("sha256"));
      if (!release.getString("skill_name").equals(dep.getString("name"))
          || !release.getString("state").equals("ready"))
        throw RuntimeSupport.error("DEPENDENCY_NOT_READY");
      dependencyDigests.put(dep.getString("sha256"));
    }
    files.put(
        checked.contents); // Failed DB commit leaves only verified, quota-accounted orphan blobs.
    JSONObject provenance =
        new JSONObject()
            .put("actor", actor)
            .put("source", source)
            .put("signed", signed)
            .put("keyId", signed ? keyId : "")
            .put("signature", signed ? signature : "")
            .put("algorithm", signed ? "SHA256withRSA" : "development-unsigned")
            .put("dependencies", dependencyDigests);
    return catalog.putRelease(tenant, checked.digest, checked.manifest, provenance);
  }

  public static JSONObject file(JSONObject manifest, String path) {
    RuntimeSupport.path(path);
    JSONArray files = manifest.getJSONArray("files");
    for (int i = 0; i < files.length(); i++)
      if (files.getJSONObject(i).getString("path").equals(path)) return files.getJSONObject(i);
    throw RuntimeSupport.error("RESOURCE_NOT_FOUND");
  }

  public byte[] readVerified(JSONObject manifest, String path) throws Exception {
    return files.read(file(manifest, path).getString("sha256"), 262144);
  }

  public byte[] export(String tenant, String digest) throws Exception {
    JSONObject manifest =
        new JSONObject(catalog.release(tenant, digest).getString("manifest_json"));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      Map<String, byte[]> entries = new TreeMap<>();
      entries.put("manifest.json", RuntimeSupport.bytes(manifest));
      JSONArray declared = manifest.getJSONArray("files");
      for (int i = 0; i < declared.length(); i++) {
        String path = declared.getJSONObject(i).getString("path");
        entries.put(path, readVerified(manifest, path));
      }
      for (Map.Entry<String, byte[]> e : entries.entrySet()) {
        ZipEntry z = new ZipEntry(e.getKey());
        z.setTime(0);
        zip.putNextEntry(z);
        zip.write(e.getValue());
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }
}
