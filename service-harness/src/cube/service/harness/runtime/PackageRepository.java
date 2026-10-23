/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.CatalogStore;
import cube.service.harness.store.JdbcRunStore;
import org.json.JSONArray;
import org.json.JSONObject;

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

/**
 * 不可变的内容寻址 release 仓库：ZIP 校验、签名发布与跨节点可复现导出。
 *
 * <p>上传包先做结构防御（中心目录逐项核对：拒绝加密、符号链接/特殊属性、目录项、
 * 穿越与大小写冲突路径，限制 65 项、每项 256 KiB、总解压 2 MiB、压缩包 8 MiB、
 * 超过 1 KiB 项压缩比不超过 100），再做 manifest schemaVersion=6 与文件清单
 * SHA256/长度核对，最后按需做 RSA 签名校验。release digest 是规范化 manifest 的哈希。
 *
 * <p>可信 CPU 执行（executable 工具）默认关闭，只有显式启用 trustedExecutables 才接受，
 * 且包体不超过 64 KiB、依赖锁必须为空（仅标准库）。actor/source/keyId 一律来自可信
 * 管理面，绝不取上传包内的声明。同 digest 重复发布保留首次 provenance，不覆盖。
 */
public final class PackageRepository {

    /**
     * 目录存储：profile/release 读写、依赖就绪状态与绑定均经此访问。
     */
    private final CatalogStore catalog;

    /**
     * 资产文件根：release 内容以 blob 形式落盘，put/read 均带摘要与配额校验。
     */
    private final AssetFiles files;

    /**
     * keyId 到可信 RSA 公钥的映射；构造时复制，运行期不感知外部修改。
     */
    private final Map<String, PublicKey> trustedKeys;

    /**
     * 是否允许本地作者开发的未签名发布；为 true 时仍要求 source 为 local-author。
     */
    private final boolean unsignedLocalDevelopment;

    /**
     * 是否启用可信 executable 工具包；默认 false，显式 opt-in 后才接受 executable 声明。
     */
    private final boolean trustedExecutables;

    /**
     * 构造仓库（不接受可信 executable 包）。
     *
     * @param catalog                  目录存储
     * @param files                    资产文件根
     * @param keys                     keyId 到可信 RSA 公钥的映射
     * @param unsignedLocalDevelopment 是否允许本地作者未签名发布
     */
    public PackageRepository(
            CatalogStore catalog,
            AssetFiles files,
            Map<String, PublicKey> keys,
            boolean unsignedLocalDevelopment) {
        this(catalog, files, keys, unsignedLocalDevelopment, false);
    }

    /**
     * 构造仓库。
     *
     * @param catalog                  目录存储
     * @param files                    资产文件根
     * @param keys                     keyId 到可信 RSA 公钥的映射
     * @param unsignedLocalDevelopment 是否允许本地作者未签名发布
     * @param trustedExecutables       是否显式启用可信 executable 工具包
     */
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

    /**
     * 按小端序读取无符号 16 位整数（ZIP 定长字段）。
     *
     * @param b 字节数组
     * @param p 起始偏移
     * @return {@code b[p..p+1]} 构成的无符号 16 位值
     */
    private static int u16(byte[] b, int p) {
        return (b[p] & 255) | ((b[p + 1] & 255) << 8);
    }

    /**
     * 按小端序读取无符号 32 位整数（ZIP 定长字段）。
     *
     * @param b 字节数组
     * @param p 起始偏移
     * @return {@code b[p..p+3]} 构成的无符号 32 位值（以 long 承载避免符号扩展）
     */
    private static long u32(byte[] b, int p) {
        return ((long) u16(b, p)) | ((long) u16(b, p + 2) << 16);
    }

    /**
     * 严格 UTF-8 解码：畸形输入直接报错，用于保证清单内文件均为合法文本。
     *
     * @param b 待解码字节
     * @return 解码后的字符串
     * @throws cube.service.harness.store.StoreException 输入不是合法 UTF-8（INVALID_ASSET_UTF8）
     */
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

    /**
     * 解析 ZIP 中心目录并逐项做属性/限额检查，返回中心目录声明的路径集合。
     *
     * <p>中心目录必须核对，因为 ZipInputStream 单独使用会隐藏 Unix symlink 属性：
     * 拒绝加密项（flags bit 0）、非 store/deflate 压缩、symlink（mode 0120000）与
     * 非普通文件模式、DOS 目录属性位，以及额外字段/注释越界；同时拒绝重复路径与
     * 大小写冲突路径。EOCD 签名必须在文件尾部 22 + 65535 字节窗口内自洽。
     *
     * @param zip 完整 ZIP 字节
     * @return 中心目录声明的全部条目路径（互不相同，含大小写去重）
     * @throws cube.service.harness.store.StoreException 结构非法（UNSUPPORTED_PACKAGE_ZIP、
     *                                                   INVALID_PACKAGE_ZIP）、条目不安全（UNSAFE_PACKAGE_ENTRY）、路径重复
     *                                                   （DUPLICATE_PACKAGE_PATH）或超出限额（PACKAGE_ZIP_LIMIT）
     */
    private static Set<String> central(byte[] zip) {
        int end = -1;
        for (int p = zip.length - 22; p >= Math.max(0, zip.length - 65557); p--) {
            if (u32(zip, p) == 0x06054b50L && p + 22 + u16(zip, p + 20) == zip.length) {
                end = p;
                break;
            }
        }
        if (end < 0
                || u16(zip, end + 4) != 0
                || u16(zip, end + 6) != 0
                || u16(zip, end + 8) != u16(zip, end + 10)) {
            throw RuntimeSupport.error("UNSUPPORTED_PACKAGE_ZIP");
        }
        int count = u16(zip, end + 10);
        long offset = u32(zip, end + 16), size = u32(zip, end + 12);
        if (count < 1 || count > 65 || offset + size != end || offset > Integer.MAX_VALUE) {
            throw RuntimeSupport.error("PACKAGE_ZIP_LIMIT");
        }
        int p = (int) offset;
        Set<String> paths = new HashSet<>(), folded = new HashSet<>();
        long total = 0;
        for (int i = 0; i < count; i++) {
            if (p + 46 > end || u32(zip, p) != 0x02014b50L) {
                throw RuntimeSupport.error("INVALID_PACKAGE_ZIP");
            }
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
                    || p + 46 + len + extra + comment > end) {
                throw RuntimeSupport.error("UNSAFE_PACKAGE_ENTRY");
            }
            String name = RuntimeSupport.path(utf8(Arrays.copyOfRange(zip, p + 46, p + 46 + len)));
            if (!paths.add(name) || !folded.add(name.toLowerCase(Locale.ROOT))) {
                throw RuntimeSupport.error("DUPLICATE_PACKAGE_PATH");
            }
            total += expanded;
            if (expanded > 262144
                    || total > 2097152
                    || (expanded > 1024 && expanded > Math.max(1, compressed) * 100)) {
                throw RuntimeSupport.error("PACKAGE_ZIP_LIMIT");
            }
            p += 46 + len + extra + comment;
        }
        if (p != end) {
            throw RuntimeSupport.error("INVALID_PACKAGE_ZIP");
        }
        return paths;
    }

    /**
     * 校验通过的发布包：规范化 manifest、内容 blob 与派生 digest。
     */
    public static final class Validated {
        /**
         * release digest：规范化 manifest 的 SHA256（64 位小写十六进制）。
         */
        public final String digest;

        /**
         * 规范化后的 manifest 对象；已通过 package-manifest schema 校验。
         */
        public final JSONObject manifest;

        /**
         * sha256(hex) 到文件内容的映射；仅含 manifest 声明且校验通过的文件。
         */
        final Map<String, byte[]> contents;

        /**
         * 组装校验结果并计算 release digest。
         *
         * @param manifest 已校验的 manifest
         * @param contents 文件内容寻址映射
         */
        Validated(JSONObject manifest, Map<String, byte[]> contents) {
            this.manifest = manifest;
            this.contents = contents;
            digest = JdbcRunStore.hash(manifest);
        }
    }

    /**
     * 校验上传流：ZIP 结构、manifest schema、文件清单与工具声明的全量静态检查。
     *
     * <p>解压结果必须与中心目录路径集合完全一致（防偷换/隐藏条目），且必须包含
     * manifest.json；manifest schemaVersion 必须为 6。文件角色约束：skill 只能叫
     * SKILL.md 且恰一个，lock 只能叫 dependencies.lock.json 且恰一个；role=schema
     * 的文件额外过工具 schema 词表校验。工具名必须以包命名空间开头且互不重复；
     * resource 工具只读 skill/resource 角色文件并固定 resource.read 权限；
     * executable 工具需显式启用、entrypoint 为 .py、effect 为 read-only，
     * 且 executable 包的依赖锁必须为空（仅标准库）。
     *
     * @param input ZIP 包输入流；上限 8 MiB
     * @return 校验通过的 {@link Validated}
     * @throws Exception 任一结构/内容/声明不合法均抛出对应 StoreException 错误码
     */
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
                if (!central.contains(name) || entries.containsKey(name)) {
                    throw RuntimeSupport.error("INVALID_PACKAGE_ZIP");
                }
                byte[] data = RuntimeSupport.read(in, 262144);
                total += data.length;
                if (total > 2097152) {
                    throw RuntimeSupport.error("PACKAGE_ZIP_LIMIT");
                }
                entries.put(name, data);
            }
        }
        if (!entries.keySet().equals(central) || !entries.containsKey("manifest.json")) {
            throw RuntimeSupport.error("PACKAGE_MANIFEST_MISSING");
        }
        JSONObject manifest = StrictJson.object(entries.remove("manifest.json"));
        int schemaVersion = manifest.getInt("schemaVersion");
        if (schemaVersion != 6) {
            throw RuntimeSupport.error("UNKNOWN_PACKAGE_SCHEMA");
        }
        RuntimeSupport.validate("package-manifest", manifest);
        boolean executablePackage = false;
        JSONArray declaredTools = manifest.getJSONArray("tools");
        for (int i = 0; i < declaredTools.length(); i++) {
            executablePackage |= declaredTools.getJSONObject(i).getString("kind").equals("executable");
        }
        if (executablePackage && (!trustedExecutables || total > 65536)) {
            throw RuntimeSupport.error("EXECUTABLE_RUNTIME_NOT_CERTIFIED");
        }
        Set<String> declared = new HashSet<>();
        int skills = 0, locks = 0;
        JSONArray list = manifest.getJSONArray("files");
        Map<String, byte[]> blobs = new TreeMap<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject file = list.getJSONObject(i);
            String name = RuntimeSupport.path(file.getString("path"));
            if (!declared.add(name)) {
                throw RuntimeSupport.error("DUPLICATE_PACKAGE_PATH");
            }
            byte[] data = entries.get(name);
            if (data == null
                    || data.length != file.getLong("size")
                    || !RuntimeSupport.sha(data).equals(file.getString("sha256"))) {
                throw RuntimeSupport.error("PACKAGE_CONTENT_MISMATCH");
            }
            utf8(data);
            blobs.put(file.getString("sha256"), data);
            if (file.getString("role").equals("skill")) {
                skills++;
                if (!name.equals("SKILL.md")) {
                    throw RuntimeSupport.error("PACKAGE_SKILL_INVALID");
                }
            }
            if (file.getString("role").equals("lock")) {
                locks++;
                if (!name.equals("dependencies.lock.json")) {
                    throw RuntimeSupport.error("PACKAGE_LOCK_INVALID");
                }
            }
            if (file.getString("role").equals("schema")) {
                RuntimeSupport.toolSchema(StrictJson.object(data));
            }
            if (file.getString("role").equals("entrypoint")
                    && (!trustedExecutables || !executablePackage || !name.endsWith(".py"))) {
                throw RuntimeSupport.error("EXECUTABLE_RUNTIME_NOT_CERTIFIED");
            }
        }
        if (!declared.equals(entries.keySet()) || skills != 1 || locks != 1) {
            throw RuntimeSupport.error("UNDECLARED_PACKAGE_FILE");
        }
        JSONObject dependencies =
                RuntimeSupport.parse(
                        "dependencies",
                        entries.get("dependencies.lock.json"));
        if (executablePackage && dependencies.getJSONArray("dependencies").length() != 0) {
            throw RuntimeSupport.error("RUNNER_STDLIB_ONLY");
        }
        RuntimeSupport.strings(manifest.getJSONArray("permissions"));
        Set<String> tools = new HashSet<>();
        for (int i = 0; i < manifest.getJSONArray("tools").length(); i++) {
            JSONObject tool = manifest.getJSONArray("tools").getJSONObject(i);
            String name = tool.getString("name");
            if (!name.startsWith(manifest.getString("name").replace('/', '.') + ".") || !tools.add(name)) {
                throw RuntimeSupport.error("RESERVED_TOOL_NAME");
            }
            boolean executable = tool.getString("kind").equals("executable");
            if (executable && !trustedExecutables) {
                throw RuntimeSupport.error("EXECUTABLE_RUNTIME_NOT_CERTIFIED");
            }
            for (String field : Arrays.asList("inputSchema", "outputSchema")) {
                String path = RuntimeSupport.path(tool.getString(field));
                if (!declared.contains(path)) {
                    throw RuntimeSupport.error("PACKAGE_SCHEMA_MISSING");
                }
                if (!file(manifest, path).getString("role").equals("schema")) {
                    throw RuntimeSupport.error("PACKAGE_SCHEMA_MISSING");
                }
                RuntimeSupport.toolSchema(StrictJson.object(entries.get(path)));
            }
            if (executable) {
                if (tool.has("resourcePath")
                        || !tool.has("entrypoint")
                        || !declared.contains(RuntimeSupport.path(tool.getString("entrypoint")))
                        || !file(manifest, tool.getString("entrypoint")).getString("role").equals("entrypoint")
                        || !tool.getString("effect").equals("read-only")) {
                    throw RuntimeSupport.error("PACKAGE_EXECUTABLE_INVALID");
                }
            } else {
                if (!tool.has("resourcePath")
                        || !declared.contains(RuntimeSupport.path(tool.getString("resourcePath")))
                        || tool.has("entrypoint")) {
                    throw RuntimeSupport.error("PACKAGE_RESOURCE_TOOL_INVALID");
                }
                if (!Arrays.asList("skill", "resource")
                        .contains(file(manifest, tool.getString("resourcePath")).getString("role"))
                        || !tool.getString("permission").equals("resource.read")) {
                    throw RuntimeSupport.error("PACKAGE_RESOURCE_TOOL_INVALID");
                }
            }
            if (!RuntimeSupport.strings(manifest.getJSONArray("permissions"))
                    .contains(tool.getString("permission"))) {
                throw RuntimeSupport.error("PACKAGE_PERMISSION_UNDECLARED");
            }
        }
        return new Validated(manifest, blobs);
    }

    /**
     * 签名发布：actor/source 来自可信管理面，绝不取上传 manifest 内的声明。
     *
     * <p>提供 keyId 时用受信公钥以 SHA256withRSA 校验对 64 字节 ASCII release digest
     * 的 Base64 签名；未签名仅在 unsignedLocalDevelopment 且 source 为 local-author
     * 时放行。发布前确认依赖锁中每个依赖
     * 都是同 tenant 下 name 匹配且 ready 的 release。DB 提交失败只留下已验证、
     * 已计额的孤儿 blob。provenance 记录 actor/source/签名/算法与依赖摘要，
     * 同 digest 重复发布由存储层保留首次记录。
     *
     * @param tenant    租户标识
     * @param actor     发布者标识；来自可信管理配置
     * @param source    来源标识；本地作者开发为 local-author
     * @param input     ZIP 包输入流
     * @param keyId     受信签名 keyId；未签名发布传 {@code null}
     * @param signature Base64 签名；未签名发布传 {@code null}
     * @return 存储层写入结果（含 digest 与 provenance）
     * @throws Exception 签名缺失/不受信/校验失败（PACKAGE_SIGNATURE_REQUIRED、
     *                   UNTRUSTED_PACKAGE_SIGNER、PACKAGE_SIGNATURE_INVALID）、source 过长
     *                   （PACKAGE_SOURCE_INVALID）、runtime 未注册或依赖不就绪等
     */
    public JSONObject upload(
            String tenant, String actor, String source, InputStream input, String keyId, String signature)
            throws Exception {
        RuntimeSupport.id(tenant);
        RuntimeSupport.id(actor);
        Validated checked = validate(input);
        boolean signed = keyId != null && !keyId.isEmpty();
        if (signed) {
            PublicKey key = trustedKeys.get(keyId);
            if (key == null) {
                throw RuntimeSupport.error("UNTRUSTED_PACKAGE_SIGNER");
            }
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(checked.digest.getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(Base64.getDecoder().decode(signature))) {
                throw RuntimeSupport.error("PACKAGE_SIGNATURE_INVALID");
            }
        } else if (!unsignedLocalDevelopment || !source.equals("local-author")) {
            throw RuntimeSupport.error("PACKAGE_SIGNATURE_REQUIRED");
        }
        if (source.length() > 1024) {
            throw RuntimeSupport.error("PACKAGE_SOURCE_INVALID");
        }
        // Runtime compatibility is checked against server deployment when a run is created.
        // Publishing a tenant skill never creates or requires tenant-owned execution configuration.
        JSONObject lock =
                RuntimeSupport.parse(
                        "dependencies",
                        checked.contents.get(
                                file(checked.manifest, "dependencies.lock.json").getString("sha256")));
        JSONArray dependencyDigests = new JSONArray();
        for (int i = 0; i < lock.getJSONArray("dependencies").length(); i++) {
            JSONObject dep = lock.getJSONArray("dependencies").getJSONObject(i),
                    release = catalog.release(tenant, dep.getString("sha256"));
            if (!release.getString("skill_name").equals(dep.getString("name"))
                    || !release.getString("state").equals("ready")) {
                throw RuntimeSupport.error("DEPENDENCY_NOT_READY");
            }
            dependencyDigests.put(dep.getString("sha256"));
        }
        files.put(
                checked.contents); // 失败的 DB 提交只会留下已验证、已计额的孤儿 blob。
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

    /**
     * 在 manifest 文件清单中按路径查找文件条目。
     *
     * @param manifest 包 manifest
     * @param path     包内相对路径；先过路径安全校验
     * @return 匹配的文件条目（含 sha256/size/role）
     * @throws cube.service.harness.store.StoreException 路径非法（UNSAFE_ASSET_PATH）或
     *                                                   清单中不存在该路径（RESOURCE_NOT_FOUND）
     */
    public static JSONObject file(JSONObject manifest, String path) {
        RuntimeSupport.path(path);
        JSONArray files = manifest.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            if (files.getJSONObject(i).getString("path").equals(path)) {
                return files.getJSONObject(i);
            }
        }
        throw RuntimeSupport.error("RESOURCE_NOT_FOUND");
    }

    /**
     * 读取并复核包内文件内容：按 manifest 声明的 sha256 取 blob，返回前由资产根复核摘要。
     *
     * @param manifest 包 manifest
     * @param path     包内相对路径
     * @return 文件内容字节（上限 256 KiB）
     * @throws Exception 条目缺失（RESOURCE_NOT_FOUND）、blob 不可用或摘要不符等
     */
    public byte[] readVerified(JSONObject manifest, String path) throws Exception {
        return files.read(file(manifest, path).getString("sha256"), 262144);
    }

    /**
     * 导出 release 为确定性 ZIP：manifest 加全部声明文件，条目时间统一置 0。
     *
     * <p>时间戳置零保证同一 digest 在任何节点导出字节一致，供跨节点复制与对账。
     *
     * @param tenant 租户标识
     * @param digest release digest
     * @return ZIP 包字节
     * @throws Exception release 不存在或任一文件读取/校验失败
     */
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
