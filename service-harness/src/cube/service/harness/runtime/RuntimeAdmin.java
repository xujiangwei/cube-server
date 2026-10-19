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
 * Explicit offline administration. Configuration and signer keys are trusted operator inputs.
 */
public final class RuntimeAdmin {

    public static void main(String[] args) throws Exception {
        if (args.length < 3)
            throw new IllegalArgumentException("RuntimeAdmin <private.properties> <validate|upload|export|profile|bind|lifecycle> <arguments...>");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) {
            p.load(in);
        }
        ConfigPaths.normalize(p);
        StoreConfig config = new StoreConfig(p);
        CatalogStore catalog = new CatalogStore(new JdbcRunStore(config), config.mysql());
        String tenant = p.getProperty("runtime.tenant"), actor = p.getProperty("runtime.actor");
        RuntimeSupport.id(tenant);
        RuntimeSupport.id(actor);
        Map<String, PublicKey> keys = new HashMap<>();
        for (String key : p.stringPropertyNames())
            if (key.startsWith("runtime.signer."))
                keys.put(key.substring("runtime.signer.".length()), KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(p.getProperty(key)))));
        PackageRepository repo = new PackageRepository(catalog, new AssetFiles(Paths.get(p.getProperty("runtime.repository.root")), Long.parseLong(p.getProperty("runtime.repository.maxBytes")), p.getProperty("runtime.repository.identity")), keys, Boolean.parseBoolean(p.getProperty("runtime.allowUnsignedLocal", "false")), Boolean.parseBoolean(p.getProperty("runtime.allowTrustedExecutables", "false")));
        Object result;
        switch (args[1]) {
            case "validate":
                try (InputStream in = Files.newInputStream(Paths.get(args[2]))) {
                    PackageRepository.Validated v = repo.validate(in);
                    result = new JSONObject().put("digest", v.digest).put("manifest", v.manifest);
                }
                break;
            case "upload":
                if (args.length != 3 && args.length != 5)
                    throw new IllegalArgumentException("upload <zip> [keyId signature-file]");
                try (InputStream in = Files.newInputStream(Paths.get(args[2]))) {
                    result = repo.upload(tenant, actor, p.getProperty("runtime.source", "local-author"), in, args.length == 5 ? args[3] : null, args.length == 5 ? new String(Files.readAllBytes(Paths.get(args[4])), java.nio.charset.StandardCharsets.US_ASCII).trim() : null);
                }
                break;
            case "export":
                if (args.length != 4) throw new IllegalArgumentException("export <digest> <new.zip>");
                Files.write(Paths.get(args[3]), repo.export(tenant, args[2]), StandardOpenOption.CREATE_NEW);
                result = new JSONObject().put("exported", args[2]);
                break;
            case "profile":
                result = new ProfileRegistry(catalog).publish(tenant, StrictJson.object(Files.readAllBytes(Paths.get(args[2]))));
                break;
            case "bind":
                if (args.length != 6)
                    throw new IllegalArgumentException("bind <skill|task> <name> <target> <expectedVersion>");
                result = catalog.bind(tenant, args[2], args[3], args[4], Long.parseLong(args[5]));
                break;
            case "lifecycle":
                if (args.length != 5)
                    throw new IllegalArgumentException("lifecycle <digest> <expectedVersion> <state>");
                result = catalog.lifecycle(tenant, args[2], Long.parseLong(args[3]), args[4]);
                break;
            default:
                throw new IllegalArgumentException("Unknown administration command");
        }
        System.out.println(JdbcRunStore.canonical(result));
    }
}
