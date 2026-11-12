package cube.service.test;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.listener.VoiceDiarizationListener;
import cube.auth.AuthToken;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.*;
import cube.common.state.AIGCStateCode;
import cube.service.aigc.AIGCCellet;
import cube.service.aigc.AIGCService;
import cube.service.aigc.unit.AudioUnitMeta;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.concurrent.*;

/** Verifies Python audio failures and legacy zero-duration results at the host boundary. */
public final class AudioUnitFailureTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        FakeCellet cellet = new FakeCellet();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AIGCService service = new AIGCService(cellet) {
            @Override public ExecutorService getExecutor() { return executor; }
        };
        FileLabel file = new FileLabel(42L, "test", "audio-file", 42L, "voice.wav", 128L, 1L, 1L, 0L);
        AuthToken token = new AuthToken("test-token", "test", "app", 42L, 1L, Long.MAX_VALUE, false);
        AIGCUnit unit = new AIGCUnit(new Contact(42L, "test", "Audio"), new AICapability("Audio",
                "AudioProcessing", "2.0", Collections.singletonList("SpeakerDiarization"), "test"), null);
        try {
            for (AIGCStateCode state : new AIGCStateCode[]{AIGCStateCode.FileError,
                    AIGCStateCode.UnitNoReady, AIGCStateCode.Failure}) {
                cellet.state = state; cellet.payload = new JSONObject();
                Capture capture = run(service, unit, token, file);
                check(capture.failed == state && capture.result == null, "preserve worker error " + state);
            }
            cellet.response = false;
            check(run(service, unit, token, file).failed == AIGCStateCode.UnitError, "timeout is UnitError");
            cellet.response = true; cellet.state = AIGCStateCode.Ok;
            for (double duration : new double[]{0, -1}) {
                cellet.payload = result(file, duration);
                Capture capture = run(service, unit, token, file);
                check(capture.failed == AIGCStateCode.FileError && capture.result == null,
                        "invalid duration cannot call success");
            }
            cellet.payload = result(file, 2.0);
            Capture capture = run(service, unit, token, file);
            check(capture.failed == null && capture.result.duration == 2.0 && capture.result.tracks.isEmpty(),
                    "valid silence remains successful");
            System.out.println("AudioUnitFailureTest: " + assertions + " assertions passed");
        } finally { executor.shutdownNow(); }
    }

    private static JSONObject result(FileLabel file, double duration) {
        return new JSONObject().put("result", new JSONObject().put("file", file.toJSON())
                .put("duration", duration).put("elapsed", 1).put("tracks", new JSONArray()));
    }

    private static Capture run(AIGCService service, AIGCUnit unit, AuthToken token, FileLabel file) throws Exception {
        AudioUnitMeta meta = new AudioUnitMeta(service, unit, token, AIGCAction.SpeechDiarization,
                file, true, false, true);
        Capture capture = new Capture();
        meta.voiceDiarizationListener = capture;
        meta.process();
        check(capture.done.await(3, TimeUnit.SECONDS), "terminal callback arrives");
        return capture;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }

    private static final class Capture implements VoiceDiarizationListener {
        final CountDownLatch done = new CountDownLatch(1);
        VoiceDiarization result;
        AIGCStateCode failed;
        @Override public void onCompleted(FileLabel source, VoiceDiarization diarization) {
            result = diarization; done.countDown();
        }
        @Override public void onFailed(FileLabel source, AIGCStateCode state) {
            failed = state; done.countDown();
        }
    }

    private static final class FakeCellet extends AIGCCellet {
        AIGCStateCode state;
        JSONObject payload;
        boolean response = true;
        @Override public ActionDialect transmit(TalkContext context, ActionDialect request, long timeout) {
            return response ? new Packet(request.getParamAsLong("sn"), request.getName(),
                    new JSONObject().put("code", state.code).put("data", payload)).toDialect() : null;
        }
    }
}
