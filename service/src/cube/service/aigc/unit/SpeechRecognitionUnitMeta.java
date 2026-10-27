/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.unit;

import cell.core.talk.dialect.ActionDialect;
import cell.util.log.Logger;
import cube.auth.AuthToken;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.AICapability;
import cube.common.entity.AIGCUnit;
import cube.common.entity.FileLabel;
import cube.common.entity.SpeechRecognitionInfo;
import cube.common.state.AIGCStateCode;
import cube.service.aigc.AIGCHook;
import cube.service.aigc.AIGCPluginContext;
import cube.service.aigc.AIGCService;
import cube.service.aigc.listener.AutomaticSpeechRecognitionListener;
import org.json.JSONObject;

/**
 * 语音识别单元任务。
 *
 * <p>把音频文件转写为文本，并把识别结果回调给监听器。</p>
 */
public class SpeechRecognitionUnitMeta extends UnitMeta {

//        protected final static String PROMPT = "合理使用标点符号给以下文本断句并修正错别字：%s";

    /** 待识别的音频文件。 */
    protected FileLabel file;

    /** 访问令牌，决定识别结果的归属域。 */
    protected AuthToken authToken;

    /** 识别结果监听器。 */
    protected AutomaticSpeechRecognitionListener listener;

    public SpeechRecognitionUnitMeta(AIGCService service, AIGCUnit unit, AuthToken authToken, FileLabel file,
                                     AutomaticSpeechRecognitionListener listener) {
        super(service, unit);
        this.authToken = authToken;
        this.file = file;
        this.listener = listener;
    }

    /**
     * 执行语音识别。
     *
     * <p>组装识别载荷并调用单元，结果通过监听器回调。</p>
     */
    @Override
    public void process() {
        JSONObject data = new JSONObject();
        data.put("fileLabel", this.file.toJSON());
        Packet request = new Packet(AIGCAction.AutomaticSpeechRecognition.name, data);
        ActionDialect dialect = this.service.getCellet().transmit(this.unit.getContext(),
                request.toDialect(), 8 * 60 * 1000);
        if (null == dialect) {
            Logger.w(this.getClass(), "#process - Speech unit error: " + this.file.getFileCode());
            // 回调错误
            this.listener.onFailed(this.file, AIGCStateCode.UnitError);
            return;
        }

        Packet response = new Packet(dialect);
        if (AIGCStateCode.Ok.code != Packet.extractCode(response)) {
            Logger.w(this.getClass(), "#process - Speech unit failed: " + this.file.getFileCode());
            this.listener.onFailed(this.file, AIGCStateCode.Failure);
            return;
        }

        JSONObject payload = Packet.extractDataPayload(response);
        final SpeechRecognitionInfo info = new SpeechRecognitionInfo(payload.getJSONObject("result"));

        if (Logger.isDebugLevel()) {
            Logger.d(this.getClass(), "#process SR result -\nfile: " + info.file.getFileCode() +
                    "\nelapsed: " + info.elapsed +
                    "\nlang: " + info.lang +
                    "\ntext: " + info.text +
                    "\nduration: " + info.durationInSeconds);
        }

        this.listener.onCompleted(this.file, info);

        this.service.getExecutor().execute(new Runnable() {
            @Override
            public void run() {
                int inputTokens = 0;
                int outputTokens = 0;
                try {
                    inputTokens = (int) Math.ceil(info.durationInSeconds * 25.0);
                    outputTokens = service.segmentText(info.text).size() + info.text.length();
                } catch (Exception e) {
                    Logger.w(this.getClass(), "", e);
                }

                AIGCPluginContext pluginContext = new AIGCPluginContext(authToken,
                        AICapability.AudioProcessing.AutomaticSpeechRecognition);
                pluginContext.setInputTokens(inputTokens);
                pluginContext.setOutputTokens(outputTokens);
                pluginContext.addFileLabel(file);
                pluginContext.setUnit(unit);
                AIGCHook hook = service.getPluginSystem().getTaskProcessingHook();
                hook.apply(pluginContext);

                // 删除时长较短的语音文件
                if (info.durationInSeconds <= 60) {
                    service.deleteFile(authToken.getDomain(), file.getFileCode());
                }
            }
        });
    }
}
