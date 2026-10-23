/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.ConfigPaths;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.CatalogStore;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreConfig;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 显式离线管理 CLI。配置与签名公钥都是受信的操作员输入。
 *
 * <p>用法：{@code RuntimeAdmin <private.properties> <command> <arguments...>}。
 * 命令：validate / upload / export / profile / bind / lifecycle。私有 properties
 * 提供 store 连接、租户/发布者身份、资产根与配额、签名公钥（runtime.signer.*）
 * 及本地未签名开发开关；本 CLI 不做网络暴露，全部写操作经 CatalogStore 事务提交。
 */
public final class RuntimeAdmin {

    /**
     * CLI 入口：装载私有配置，构造仓库与目录存储，按子命令分派并打印规范化结果。
     *
     * @param args {@code <private.properties> <command> <arguments...>}；
     *             参数少于 3 个或命令未知时抛 IllegalArgumentException
     * @throws Exception 配置读取、签名/上传/导出等任一环节失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("RuntimeAdmin <private.properties> <validate|upload|export|profile|bind|lifecycle> <arguments...>");
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) {
            p.load(in);
        }
        ConfigPaths.normalize(p);
        StoreConfig config = new StoreConfig(p);
        CatalogStore catalog = new CatalogStore(new JdbcRunStore(config), config.mysql());
        String tenant = p.getProperty("runtime.tenant"), actor = p.getProperty("runtime.actor");
        RuntimeSupport.id(tenant);
        if (args[1].equals("upload")) RuntimeSupport.id(actor);
        PackageRepository repo = null;
        if (java.util.Arrays.asList("validate", "upload", "export").contains(args[1])) {
            // runtime.signer.<keyId> 为 Base64 编码的 X.509 DER RSA 公钥。
            Map<String, PublicKey> keys = new HashMap<>();
            for (String key : p.stringPropertyNames()) {
                if (key.startsWith("runtime.signer.")) {
                    keys.put(
                            key.substring("runtime.signer.".length()),
                            KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(p.getProperty(key)))));
                }
            }
            repo =
                    new PackageRepository(
                            catalog,
                            new AssetFiles(
                                    Paths.get(p.getProperty("runtime.repository.root")),
                                    Long.parseLong(p.getProperty("runtime.repository.maxBytes")),
                                    p.getProperty("runtime.repository.identity")),
                            keys,
                            Boolean.parseBoolean(p.getProperty("runtime.allowUnsignedLocal", "false")),
                            Boolean.parseBoolean(p.getProperty("runtime.allowTrustedExecutables", "false")));
        }
        Object result;
        switch (args[1]) {
            // 只做静态校验，不落库。
            case "validate":
                try (InputStream in = Files.newInputStream(Paths.get(args[2]))) {
                    PackageRepository.Validated v = repo.validate(in);
                    result = new JSONObject().put("digest", v.digest).put("manifest", v.manifest);
                }
                break;
            // 签名或本地未签名发布；keyId/signature-file 成对出现。
            case "upload":
                if (args.length != 3 && args.length != 5) {
                    throw new IllegalArgumentException("upload <zip> [keyId signature-file]");
                }
                try (InputStream in = Files.newInputStream(Paths.get(args[2]))) {
                    result = repo.upload(tenant, actor, p.getProperty("runtime.source", "local-author"), in,
                            args.length == 5 ? args[3] : null,
                            args.length == 5
                                    ? new String(Files.readAllBytes(Paths.get(args[4])), java.nio.charset.StandardCharsets.US_ASCII).trim()
                                    : null);
                }
                break;
            // 重新打包导出为确定性 ZIP，目标必须不存在（CREATE_NEW）。
            case "export":
                if (args.length != 4) {
                    throw new IllegalArgumentException("export <digest> <new.zip>");
                }
                Files.write(Paths.get(args[3]), repo.export(tenant, args[2]), StandardOpenOption.CREATE_NEW);
                result = new JSONObject().put("exported", args[2]);
                break;
            // 发布租户技能、权限和业务配额策略。
            case "profile":
                JSONObject policy = StrictJson.object(Files.readAllBytes(Paths.get(args[2])));
                if (!policy.getString("kind").equals("policy")) throw new IllegalArgumentException("Only tenant business policies can be published; configure models and runtimes on the server");
                result = new ProfileRegistry(catalog).publish(tenant, policy);
                break;
            // 原子 CAS 绑定 skill/policy 指针；expectedVersion 为首版 0。
            case "bind":
                if (args.length != 6) {
                    throw new IllegalArgumentException("bind <skill|policy> <name> <target> <expectedVersion>");
                }
                if (!java.util.Arrays.asList("skill", "policy").contains(args[2])) throw new IllegalArgumentException("Only skill and policy bindings belong to tenants");
                result = catalog.bind(tenant, args[2], args[3], args[4], Long.parseLong(args[5]));
                break;
            // 生命周期流转：ready/draining/retired，带期望版本 CAS。
            case "lifecycle":
                if (args.length != 5) {
                    throw new IllegalArgumentException("lifecycle <digest> <expectedVersion> <state>");
                }
                result = catalog.lifecycle(tenant, args[2], Long.parseLong(args[3]), args[4]);
                break;
            default:
                throw new IllegalArgumentException("Unknown administration command");
        }
        System.out.println(JdbcRunStore.canonical(result));
    }
}
