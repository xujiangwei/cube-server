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
 * Only explicit staging directories are writable. Never mutates the source deployment.
 */
public final class StagingTool {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("prepare|configure <source-deploy> <new-stage>");
        Path source = Paths.get(args[1]).toRealPath(), stage = Paths.get(args[2]).toFile().getCanonicalFile().toPath();
        if (stage.startsWith(source) || source.startsWith(stage))
            throw new IOException("Staging must be outside the source deployment");
        Path marker = stage.resolve(".harness-staging");
        if (args[0].equals("prepare")) {
            if (Files.exists(stage) && !Files.exists(marker)) {
                try (java.util.stream.Stream<Path> files = Files.list(stage)) {
                    if (files.findAny().isPresent())
                        throw new IOException("Refusing nonempty unmarked staging directory");
                }
            }
            Files.createDirectories(stage);
            Files.write(marker, Collections.singletonList("Harness staging; never a running deployment"));
            for (String directory : Arrays.asList("bin", "libs")) {
                Files.createDirectories(stage.resolve(directory));
                try (DirectoryStream<Path> files = Files.newDirectoryStream(source.resolve(directory), "*.jar")) {
                    for (Path file : files)
                        if (directory.equals("bin") || !file.getFileName().toString().startsWith("cube-"))
                            Files.copy(file, stage.resolve(directory).resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.createDirectories(stage.resolve("cellets"));
            Files.createDirectories(stage.resolve("config"));
            Files.copy(source.resolve("config/service.xml"), stage.resolve("config/service-with-harness.xml"), StandardCopyOption.REPLACE_EXISTING);
        } else if (args[0].equals("configure")) {
            if (!Files.exists(marker)) throw new IOException("Missing staging marker");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(stage.resolve("config/service-with-harness.xml").toFile());
            NodeList servers = doc.getElementsByTagName("server");
            String port = ((Element) servers.item(0)).getElementsByTagName("port").item(0).getTextContent().trim();
            Element cellet = doc.createElement("cellet");
            cellet.setAttribute("port", port);
            cellet.setAttribute("jar", "cellets/cube-service-harness-3.0.jar");
            Element cls = doc.createElement("class");
            cls.setTextContent("cube.service.harness.HarnessCellet");
            cellet.appendChild(cls);
            doc.getElementsByTagName("cellets").item(0).appendChild(cellet);
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.transform(new DOMSource(doc), new StreamResult(stage.resolve("config/service-with-harness.xml").toFile()));
            if (Files.exists(stage.resolve("libs/cube-service-harness-3.0.jar")))
                throw new IOException("Harness must only occur in cellets");
        } else throw new IllegalArgumentException("Unknown staging action");
    }
}
