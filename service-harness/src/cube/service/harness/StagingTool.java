/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Collections;

/**
 * 联合发布 staging 工具：只有显式给出的 staging 目录可写，绝不改动源部署目录。
 * <p>prepare 从源部署复制 bin/libs/cellets/config 骨架并写入 .harness-staging 标记
 * （非空且无标记的目录拒绝覆盖，防止误写运行目录）；configure 在标记齐备时
 * 把 Harness Cellet 元素合入 service-with-harness.xml（已有则该条目改指 cellets，
 * 否则新增一条，保证只存在一个 Harness cellet），并拒绝 Harness jar 落到 libs。
 * staging 与源部署不得互相包含。
 */
public final class StagingTool {
    /**
     * Harness Cellet 的类名：既是 cellet 条目的识别依据，也是写入配置的字面量。
     */
    private static final String HARNESS_CELLET = "cube.service.harness.HarnessCellet";

    /**
     * 按动作执行 staging：prepare 复制骨架，configure 改写服务 XML。
     *
     * @param args 恰好三个参数：动作（prepare|configure）、源部署目录、新 staging 目录
     * @throws IllegalArgumentException 参数个数不为 3，或动作不是 prepare/configure
     * @throws IOException              staging 与源部署互相包含、staging 非空且无标记、缺少标记文件，
     *                                  或 Harness jar 误放 libs 等拒绝写入场景
     * @throws Exception                XML 解析/转换失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("prepare|configure <source-deploy> <new-stage>");
        }
        Path source = Paths.get(args[1]).toRealPath();
        Path stage = Paths.get(args[2]).toFile().getCanonicalFile().toPath();
        if (stage.startsWith(source) || source.startsWith(stage)) {
            throw new IOException("Staging must be outside the source deployment");
        }
        Path marker = stage.resolve(".harness-staging");
        if (args[0].equals("prepare")) {
            if (Files.exists(stage) && !Files.exists(marker)) {
                try (java.util.stream.Stream<Path> files = Files.list(stage)) {
                    if (files.findAny().isPresent()) {
                        throw new IOException("Refusing nonempty unmarked staging directory");
                    }
                }
            }
            Files.createDirectories(stage);
            Files.write(marker, Collections.singletonList("Harness staging; never a running deployment"));
            for (String directory : Arrays.asList("bin", "libs")) {
                Files.createDirectories(stage.resolve(directory));
                try (DirectoryStream<Path> files = Files.newDirectoryStream(source.resolve(directory), "*.jar")) {
                    for (Path file : files) {
                        // bin 全量复制；libs 只复制非 cube- 前缀 jar，Harness 只进 cellets。
                        if (directory.equals("bin") || !file.getFileName().toString().startsWith("cube-")) {
                            Files.copy(file, stage.resolve(directory).resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
            Files.createDirectories(stage.resolve("cellets"));
            Files.createDirectories(stage.resolve("config"));
            Files.copy(source.resolve("config/service.xml"), stage.resolve("config/service-with-harness.xml"), StandardCopyOption.REPLACE_EXISTING);
        } else if (args[0].equals("configure")) {
            if (!Files.exists(marker)) {
                throw new IOException("Missing staging marker");
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(stage.resolve("config/service-with-harness.xml").toFile());
            NodeList servers = doc.getElementsByTagName("server");
            String port = ((Element) servers.item(0)).getElementsByTagName("port").item(0).getTextContent().trim();
            Element cellets = (Element) doc.getElementsByTagName("cellets").item(0);
            // 主部署的 service.xml 已登记 HarnessCellet（jar 由 libs 提供，条目不带 jar 属性）。
            // staging 的 libs 不含 harness，若原样保留该条目就会加载失败；就地改写其 jar 指向
            // cellets，而不是追加第二条 —— 两条同名 cellet 会把 HarnessService 重复装进 Kernel。
            Element existing = findHarnessCellet(cellets);
            if (existing != null) {
                existing.setAttribute("jar", "cellets/cube-service-harness-3.0.jar");
            } else {
                Element cellet = doc.createElement("cellet");
                cellet.setAttribute("port", port);
                cellet.setAttribute("jar", "cellets/cube-service-harness-3.0.jar");
                Element cls = doc.createElement("class");
                cls.setTextContent(HARNESS_CELLET);
                cellet.appendChild(cls);
                cellets.appendChild(cellet);
            }
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.transform(new DOMSource(doc), new StreamResult(stage.resolve("config/service-with-harness.xml").toFile()));
            if (Files.exists(stage.resolve("libs/cube-service-harness-3.0.jar"))) {
                throw new IOException("Harness must only occur in cellets");
            }
        } else {
            throw new IllegalArgumentException("Unknown staging action");
        }
    }

    /**
     * Locate the Harness cellet entry in a &lt;cellets&gt; element.
     *
     * @param cellets the {@code <cellets>} element to scan
     * @return the entry whose first {@code <class>} is {@link #HARNESS_CELLET}, or null when absent
     */
    private static Element findHarnessCellet(Element cellets) {
        NodeList items = cellets.getElementsByTagName("cellet");
        for (int i = 0; i < items.getLength(); i++) {
            Element cellet = (Element) items.item(i);
            NodeList classes = cellet.getElementsByTagName("class");
            if (classes.getLength() > 0
                    && HARNESS_CELLET.equals(classes.item(0).getTextContent().trim())) {
                return cellet;
            }
        }
        return null;
    }
}
