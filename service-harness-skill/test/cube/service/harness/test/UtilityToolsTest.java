package cube.service.harness.test;

import static cube.service.harness.test.RuntimeTest.*;
import cube.aigc.completion.MediaProvider;
import cube.service.harness.execution.ToolBroker;
import cube.service.harness.files.FileService;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import org.json.*;

/** Uses real file and tool stores with a deterministic physical receipt fixture. */
public final class UtilityToolsTest {
    static ToolBroker broker;
    static FileService files;
    static Map<String, String> releases = new LinkedHashMap<>();
    static boolean ready = true;
    static int dispatches;

    static JdbcRunStore.Lease run(String family, String... ids) throws Exception {
        String id = profiles.createRun(identity, key(), family + "-utility", request("fixture")
                .put("input", new JSONObject().put("instruction", "fixture")
                        .put("attachmentIds", new JSONArray(Arrays.asList(ids))))).getString("runId");
        JdbcRunStore.Lease lease = running(id, 60000);
        resources.activate(identity, id, releases.get(family));
        return lease;
    }

    static String input(String name, String type, byte[] raw) throws Exception {
        String hash = RuntimeSupport.sha(raw);
        String id = files.create(key(), new JSONObject().put("fileName", name).put("mediaType", type)
                .put("size", raw.length).put("sha256", hash)).getString("inputId");
        files.uploadInput(id, new ByteArrayInputStream(raw));
        files.complete(id, hash);
        return id;
    }

    static JSONObject read(JSONObject job, JSONObject wire, int index) throws Exception {
        return DocumentToolsTest.exchange(job, wire, new JSONObject().put("operation", "read")
                .put("offset", 0).put("inputIndex", index));
    }

    public static void main(String[] args) throws Exception {
        Path directory = Paths.get(args[0]), packages = Paths.get(args[1]), manifest = Paths.get(args[2]);
        TEST_PERMISSIONS.addAll(Arrays.asList("pdf.process", "image.process", "table.process", "report.process",
                "ocr.recognize", "transcription.recognize"));
        DocumentToolsTest.prepare(directory, packages, manifest);
        files = DocumentToolsTest.files;
        Properties settings = DocumentToolsTest.settings;
        for (String family : Arrays.asList("image", "table", "report")) {
            String release = upload(Files.readAllBytes(packages.resolve(family + "-v1.zip"))).getString("digest");
            catalog.lifecycle(T, release, 1, "ready");
        }
        settings.setProperty("broker.providers", "pdf,ocr,transcription");
        for (String family : settings.getProperty("broker.providers").split(",")) {
            boolean pdf = family.equals("pdf");
            Properties config = new Properties();
            config.setProperty("family", family);
            if (pdf) config.setProperty("environment.manifest", manifest.toAbsolutePath().toString());
            Path configPath = directory.resolve(family + ".properties");
            Files.write(configPath, bytes(ExecutionTest.properties(config)));
            settings.setProperty("broker.provider." + family + ".config", configPath.toString());
            settings.setProperty("broker.provider." + family + ".class", "cube.service.harness.skill."
                    + (pdf ? "FileUtilityProvider" : "RecognitionProvider"));
            String release = upload(Files.readAllBytes(packages.resolve(family + "-v1.zip"))).getString("digest");
            catalog.lifecycle(T, release, 1, "ready");
            releases.put(family, release);
            JSONObject task = task("1", release, "scripted", 8192).put("id", family + "-utility")
                    .put("runtime", ref("trusted-cpu")).put("toolSetVersion", "broker-tools")
                    .put("requiredCapabilities", new JSONArray().put("resources").put("tool.execute"));
            profiles.publish(T, task);
            profiles.bind(T, "task", family + "-utility", family + "-utility@1", 0);
        }
        MediaProvider bridge = new MediaProvider() {
            public JSONArray mediaUnits() {
                JSONArray units = new JSONArray();
                for (String unit : Arrays.asList("Speech", "OmniPerception")) units.put(new JSONObject()
                        .put("unit", unit).put("instanceId", unit + "-1").put("ready", ready).put("capacity", 1));
                return units;
            }
            public void invokeMedia(JSONObject execution, byte[] data, Consumer<JSONObject> reply) {
                dispatches++;
                check(execution.getString("sha256").equals(RuntimeSupport.sha(data)), "attachment digest");
                boolean speech = execution.getString("unit").equals("Speech");
                if (!speech) {
                    JSONObject option = execution.getJSONObject("option");
                    check(option.getString("task").equals("region_ocr"), "actual OCR operation");
                    check(option.getJSONObject("parameters").getJSONArray("bboxList").getJSONArray(0)
                            .toString().equals("[0,0,32,20]"), "full image region");
                } else check(execution.getString("adapter").equals("speech-recognition"), "Speech adapter");
                JSONObject receipt = new JSONObject().put("requestId", execution.getString("requestId"))
                        .put("instanceId", execution.getString("instanceId")).put("actualFinished", false)
                        .put("stage", "physical-dispatch").put("dispatchedAt", System.currentTimeMillis())
                        .put("serviceSn", execution.getLong("serviceSn"));
                reply.accept(receipt);
                reply.accept(new JSONObject(receipt.toString()).put("actualFinished", true).put("success", true)
                        .put("finishedAt", System.currentTimeMillis()).put("payload", speech
                                ? new JSONObject().put("text", "语音样本").put("lang", "zh").put("audioDurationSeconds", 1.25)
                                : new JSONObject().put("response", "图片样本")));
            }
            public void cancelMedia(String id) {}
        };
        broker = new ToolBroker(new ToolJobStore(store, false), identity, settings).files(files)
                .context(resources, new ProviderStore(store, false), () -> bridge);
        DocumentToolsTest.broker = MediaToolsTest.broker = broker;
        MediaToolsTest.longWait = true;
        try {
            multiInput();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(32, 20, BufferedImage.TYPE_INT_RGB), "png", png);
            String image = input("photo.png", "image/png", png.toByteArray());
            JSONObject ocr = new JSONObject().put("inputId", image).put("mediaType", "image/png");
            JSONObject unavailable = MediaToolsTest.call(run("ocr"), "common.ocr.recognize", ocr);
            check(unavailable.getString("errorCode").equals("FILE_NOT_BOUND"), unavailable.toString());
            JSONObject recognized = MediaToolsTest.call(run("ocr", image), "common.ocr.recognize", ocr);
            check(recognized.getString("text").equals("图片样本"), recognized.toString());
            try (FileService.Download content = files.openDownload(recognized.getJSONObject("artifact").getString("artifactId"), "")) {
                check(Arrays.equals(RuntimeSupport.read(content.input, 100), bytes("图片样本")), "OCR artifact download");
            }
            byte[] wav = bytes("RIFF....WAVEfmt fixture");
            String audio = input("sample.wav", "audio/wav", wav);
            JSONObject speech = MediaToolsTest.call(run("transcription", audio), "common.transcription.recognize",
                    new JSONObject().put("inputId", audio).put("mediaType", "audio/wav"));
            check(speech.getString("text").equals("语音样本") && speech.getDouble("durationSeconds") == 1.25
                    && !speech.has("timestamps"), speech.toString());
            ready = false;
            JSONObject stopped = MediaToolsTest.call(run("ocr", image), "common.ocr.recognize", ocr);
            check(stopped.getString("errorCode").equals("CAPABILITY_NOT_READY") && dispatches == 2, stopped.toString());
        } finally {
            broker.close();
            files.close();
        }
        pass("ordered file exchange, unbound attachment refusal, Unit readiness and recognition artifacts");
    }

    static void multiInput() throws Exception {
        byte[] a = bytes("%PDF-1.7\nfirst\n%%EOF\n"), b = bytes("%PDF-1.7\nsecond\n%%EOF\n");
        String first = input("first.pdf", "application/pdf", a), second = input("second.pdf", "application/pdf", b);
        for (boolean bound : new boolean[] {true, false}) {
            JdbcRunStore.Lease lease = bound ? run("pdf", first, second) : run("pdf", first);
            String name = "common.pdf.process", id = key();
            JSONObject args = new JSONObject().put("action", "merge").put("inputIds", new JSONArray().put(first).put(second));
            intent(lease, "call", id, new JSONObject().put("proposalId", id).put("tool", name).put("argumentsJson", args.toString()));
            JSONObject job = broker.enqueue(lease, row(lease.runId, "call", id), resources.executable(identity, lease.runId, name));
            JSONObject wire = DocumentToolsTest.assign(job, "multi-" + bound);
            check(RuntimeSupport.sha(a).equals(read(job, wire, 0).getString("sha256")), "first input");
            if (bound) check(RuntimeSupport.sha(b).equals(read(job, wire, 1).getString("sha256")), "second input");
            else fail("FILE_NOT_BOUND", () -> read(job, wire, 1));
            fail("FILE_INPUT_INDEX_INVALID", () -> read(job, wire, 2));
        }
    }
}
