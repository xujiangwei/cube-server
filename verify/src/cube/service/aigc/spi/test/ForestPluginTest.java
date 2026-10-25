package cube.service.aigc.spi.test;

import cube.aigc.spi.*;
import cube.auth.AuthToken;
import cube.common.Packet;
import cube.common.entity.FileLabel;
import cube.common.state.AIGCStateCode;
import cube.service.forest.*;
import cube.service.forest.scene.ForestDetectionService;
import org.json.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Forest plugin contract, file isolation and existing SPI compatibility. */
public final class ForestPluginTest {
    static int assertions;
    static void check(boolean value, String text) { if (!value) throw new AssertionError(text); assertions++; }
    static void failure(int http, Runnable call) {
        try { call.run(); throw new AssertionError("Expected failure"); }
        catch (PluginCallException e) { check(e.httpStatus == http, "HTTP status " + http); }
    }
    static JSONObject result() {
        return new JSONObject().put("response", "No trees").put("thought", "").put("performance", new JSONObject())
                .put("resultPayload", new JSONObject().put("width", 100).put("height", 80).put("count", 0).put("refs", new JSONArray()));
    }
    public static void main(String[] args) throws Exception {
        File image = Files.createTempFile("forest-plugin-", ".png").toFile();
        try {
            ImageIO.write(new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB), "png", image);
            String code = String.join("", Collections.nCopies(64, "a"));
            FileLabel label = new FileLabel("test", code, 123L, image);
            AuthToken token = new AuthToken("token", "test", "app", 123L, 0L, Long.MAX_VALUE, false);
            AIGCHost host = (AIGCHost) Proxy.newProxyInstance(AIGCHost.class.getClassLoader(), new Class<?>[]{AIGCHost.class},
                    (proxy, method, values) -> {
                        switch (method.getName()) {
                            case "getFile": return label;
                            case "loadFile": return image;
                            case "readModuleResource": return null;
                            case "countPluginUnits": return 0;
                            case "invokePluginUnit":
                                check(ForestConfig.UNIT.equals(values[0]), "exact plugin unit");
                                JSONObject input = (JSONObject) values[1];
                                check(input.getJSONArray("files").getJSONObject(0).getLong("ownerId") == 123L, "authorized label forwarded");
                                check(!input.getJSONObject("option").getBoolean("wait"), "model calls fail fast on contention");
                                return new Packet("pluginInvoke", new JSONObject().put("code", 0).put("data", result())).toDialect();
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            ForestConfig config = ForestConfig.read(host);
            ForestDetectionService detection = new ForestDetectionService(host, config);
            JSONObject request = new JSONObject().put("fileCode", code);
            long modified = image.lastModified(), length = image.length();
            check(detection.detect(token, request).getJSONObject("resultPayload").getInt("count") == 0, "empty result is successful");
            check(image.length() == length && image.lastModified() == modified, "source image not changed");
            check(!detection.capability().getBoolean("online"), "capability query works without providers");
            failure(401, () -> detection.detect(null, request));
            failure(403, () -> detection.detect(new AuthToken("t", "test", "app", 999L, 0L, Long.MAX_VALUE, false), request));
            failure(400, () -> detection.detect(token, new JSONObject().put("fileCode", code).put("unit", "other")));
            failure(400, () -> detection.detect(token, new JSONObject().put("fileCode", code).put("scoreThreshold", "0.3")));
            failure(400, () -> detection.detect(token, new JSONObject().put("fileCode", code).put("scoreThreshold", 2)));
            failure(504, () -> detection.detect(token, request, 0));
            failure(403, () -> detection.detect(new AuthToken("t", "other-domain", "app", 123L, 0L, Long.MAX_VALUE, false), request));
            JSONObject wrongCount = result(); wrongCount.getJSONObject("resultPayload").put("count", 1);
            failure(502, () -> ForestDetectionService.validateResult(wrongCount, config.maxPixels));
            ForestModule module = new ForestModule();
            check(module.getDescriptor().optional, "forest is optional");
            check(AIGCSPI.isCompatible(25) && AIGCSPI.isCompatible(26) && !AIGCSPI.isCompatible(24), "v25 compatibility is explicit");
            check(ForestModule.declareActionNames().size() == module.getActions().size(), "action declarations agree");
            module.setup(host);
            try {
                CountDownLatch completed = new CountDownLatch(1);
                boolean[] deferred = {false};
                ActionContext context = (ActionContext) Proxy.newProxyInstance(ActionContext.class.getClassLoader(), new Class<?>[]{ActionContext.class},
                        (proxy, method, values) -> {
                            switch (method.getName()) {
                                case "getToken": return token;
                                case "getRequest": return new Packet(ForestModule.DETECT, request);
                                case "deferResponse": deferred[0] = true; return null;
                                case "respond": check(values[0] == AIGCStateCode.Ok, "async action result"); completed.countDown(); return null;
                                default: throw new UnsupportedOperationException(method.getName());
                            }
                        });
                module.getActions().get(0).task.handle(context);
                check(deferred[0] && completed.await(3, TimeUnit.SECONDS), "single deferred response completes");
            } finally { module.teardown(); }
            testDeferredRunner(module);
            System.out.println("ForestPluginTest: " + assertions + " assertions passed");
        } finally { Files.deleteIfExists(image.toPath()); }
    }

    private static void testDeferredRunner(ForestModule module) {
        final cell.api.Nucleus testNucleus = new cell.api.Nucleus();
        final AtomicInteger responses = new AtomicInteger();
        cube.service.aigc.AIGCCellet cellet = new cube.service.aigc.AIGCCellet() {
            @Override public cell.api.Nucleus getNucleus() { return testNucleus; }
            @Override public boolean speak(cell.core.talk.TalkContext context, cell.core.talk.Primitive value) {
                responses.incrementAndGet(); return true;
            }
        };
        final ActionContext[] delayed = {null};
        ActionBinding binding = new ActionBinding("deferredTest", context -> {
            context.deferResponse(); delayed[0] = context; return AIGCStateCode.Ok;
        }, false, null);
        cube.service.aigc.spi.ActionRunner runner = new cube.service.aigc.spi.ActionRunner(cellet, null,
                new Packet("deferredTest", new JSONObject()).toDialect(), null, null, binding, module);
        runner.run();
        check(responses.get() == 0 && !runner.isResponded(), "deferred runner does not send premature empty success");
        delayed[0].respond(AIGCStateCode.Ok, result());
        delayed[0].respondEmpty(AIGCStateCode.UnitError);
        check(responses.get() == 1 && runner.isResponded(), "deferred response retains duplicate guard");
        ActionBinding ordinary = new ActionBinding("ordinaryTest", context -> AIGCStateCode.Ok, false, null);
        new cube.service.aigc.spi.ActionRunner(cellet, null,
                new Packet("ordinaryTest", new JSONObject()).toDialect(), null, null, ordinary, module).run();
        check(responses.get() == 2, "ordinary actions retain automatic empty response");
    }
}
