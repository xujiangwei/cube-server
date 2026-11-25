/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.aigc.unit;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cell.util.Utils;
import cell.util.log.Logger;
import cube.aigc.Usage;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.*;
import cube.common.state.AIGCStateCode;
import cube.service.aigc.AIGCService;
import cube.service.aigc.event.EventCenter;
import cube.service.aigc.listener.MultimodalListener;
import cube.util.FileType;
import cube.util.FileUtils;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 多模态单元任务。
 *
 * <p>把文本与图像等混合输入交给多模态单元处理，结果按通道回写。</p>
 */
public class MultimodalUnitMeta extends UnitMeta implements Runnable {

    /**
     * 任务序列号。
     */
    public final long sn;

    private final AIGCChannel channel;

    private final MultimodalInput input;

    private final MultimodalListener listener;
    private final boolean ownsChannel;
    private final long streamDeadline;
    private final String reservedStreamId;
    private volatile boolean streamConfirmed;

    private MultimodalOutput output;
    private final String streamMeterId = java.util.UUID.randomUUID().toString();

    /**
     * 在每次物理流推理之前，由拥有单位同步调用。
     */
    public JSONObject beginSegment(JSONObject payload) {
        long sequence = payload.getLong("sequence");
        if (sequence < 0) throw new IllegalArgumentException("INVALID_SEGMENT_SEQUENCE");
        this.confirmStream();
        String id = streamMeterId + ":" + sequence;
        cube.aigc.usage.UsageWriter writer = service.getStorage().getUsageWriter();
        writer.begin(usageContext, id, unit.getCapability().getName(), unit.getQueryKey(), System.currentTimeMillis());
        return writer.ledger.transaction(c -> cube.aigc.usage.UsageLedger.one(c,
                "SELECT meter_id,started_at FROM aigc_ai_usage_call WHERE meter_id=?", id));
    }

    public MultimodalUnitMeta(AIGCService service, AIGCUnit unit, AIGCChannel channel,
                              MultimodalInput input, MultimodalListener listener) {
        this(service, unit, channel, input, listener, true, 0, null);
    }

    public MultimodalUnitMeta(AIGCService service, AIGCUnit unit, AIGCChannel channel,
                              MultimodalInput input, MultimodalListener listener,
                              boolean ownsChannel, long streamDeadline, String reservedStreamId) {
        super(service, unit);
        this.sn = Utils.generateSerialNumber();
        this.channel = channel;
        this.input = input;
        this.listener = listener;
        this.ownsChannel = ownsChannel;
        this.streamDeadline = streamDeadline;
        this.reservedStreamId = reservedStreamId;
    }

    public void confirmStream() {
        this.streamConfirmed = true;
    }

    public boolean reservationExpired(long now) {
        return this.reservedStreamId != null && !this.streamConfirmed
                && now - this.streamDeadline >= 5 * 60 * 1000;
    }

    public void reject(AIGCStateCode code) {
        this.listener.onFailed(this.channel, code);
    }

    @Override
    public void run() {
        this.process();
    }

    /**
     * 获取所属通道。
     *
     * @return 对话通道。
     */
    public AIGCChannel getChannel() {
        return this.channel;
    }

    /**
     * 执行多模态处理。
     *
     * <p>调用单元后释放通道的处理占用；无论成功失败都会解除占用，避免通道被永久锁定。</p>
     */
    @Override
    public void process() {
        try {
            boolean processingReleased = !this.ownsChannel;
            try {
                if (this.streamDeadline != 0 && this.streamDeadline <= System.currentTimeMillis()) {
                    this.reject(AIGCStateCode.Timeout);
                    return;
                }
                Logger.d(this.getClass(), "#process - Model: " + this.unit.getCapability().getName());

                // Omni 的 content 是提示词；旧插件的超链接内容仍先下载。
                if (!this.input.isOmniUnit() && (this.input.content.toLowerCase().startsWith("http://")
                        || this.input.content.toLowerCase().startsWith("https://"))) {
                    FileLabel fileLabel = this.service.downloadFile(this.channel.getAuthToken(), this.input.content);
                    if (null != fileLabel) {
                        if (fileLabel.getFileType() == FileType.TEXT || fileLabel.getFileType() == FileType.MD) {
                            // 文本文件，读取内容进行替换
                            File file = this.service.loadFile(this.channel.getAuthToken().getDomain(), fileLabel.getFileCode());
                            if (null != file) {
                                String fileContent = FileUtils.readTextFile(file.getAbsolutePath());
                                // 删除临时文件
                                this.service.deleteFile(this.channel.getAuthToken().getDomain(), fileLabel.getFileCode());
                                if (null != fileContent) {
                                    this.input.content = fileContent;
                                } else {
                                    this.listener.onFailed(this.channel, AIGCStateCode.FileError);
                                    return;
                                }
                            } else {
                                this.listener.onFailed(this.channel, AIGCStateCode.FileError);
                                return;
                            }
                        } else {
                            // 其他文件，则直接传给 Unit
                            this.input.content = fileLabel.getFileCode();
                        }
                    } else {
                        this.listener.onFailed(this.channel, AIGCStateCode.FileError);
                        return;
                    }
                }

                // 处理文件
                List<FileLabel> fileLabelList = new ArrayList<>();
                for (String fileItem : this.input.fileCodes) {
                    if (fileItem.startsWith("http")) {
                        // 下载文件
                        FileLabel fileLabel = this.service.downloadFile(this.channel.getAuthToken(), fileItem);
                        if (null != fileLabel) {
                            fileLabelList.add(fileLabel);
                        } else {
                            this.listener.onFailed(this.channel, AIGCStateCode.FileError);
                            return;
                        }
                    } else if (fileItem.regionMatches(true, 0, "rtsp", 0, 4)
                            || fileItem.regionMatches(true, 0, "rtmp", 0, 4)) {
                        // RTSP 或 RTMP 视频流
                        fileLabelList = null;
                        break;
                    } else {
                        // 加载文件
                        FileLabel fileLabel = this.service.getFile(this.channel.getAuthToken().getDomain(), fileItem);
                        if (null != fileLabel) {
                            fileLabelList.add(fileLabel);
                        } else {
                            this.listener.onFailed(this.channel, AIGCStateCode.NotFound);
                            return;
                        }
                    }
                }

                // 设置文件标签清单
                this.input.fileLabels = fileLabelList;

                Packet request = new Packet(AIGCAction.Multimodal.name, this.input.toUnitJson());
                boolean streamControl = this.input.isStreamControl();
                boolean streamStart = this.input.hasRtspSource() && !streamControl;
                if (streamStart && "OmniVL".equals(unit.getCapability().getName())) {
                    if (unit.getCapability().getUsageProtocol() < 1)
                        throw new IllegalStateException("AI_USAGE_UNIT_UPGRADE_REQUIRED");
                    service.getStorage().getUsageWriter().checkAvailable();
                } else if (!streamControl) meterBegin();
                TalkContext context = this.unit.getContext();
                if (this.streamDeadline != 0 && (context == null || !context.isValid() || this.unit.isRetired())) {
                    this.reject(AIGCStateCode.UnitNoReady);
                    return;
                }
                ActionDialect outgoing = request.toDialect();
                long timeout = 3 * 60 * 1000;
                if (this.streamDeadline != 0) {
                    long remaining = this.streamDeadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        this.reject(AIGCStateCode.Timeout);
                        return;
                    }
                    timeout = remaining + 1000;
                    outgoing.addParam("streamDeadline", this.streamDeadline);
                    if (this.reservedStreamId != null) {
                        EventCenter.getInstance().putUnitMeta(this.reservedStreamId, this);
                        outgoing.addParam("reservedStreamId", this.reservedStreamId);
                    }
                }
                ActionDialect dialect = this.service.getCellet().transmit(context, outgoing, timeout, this.sn);
                // 处理单元返回
                if (null == dialect) {
                    Logger.w(this.getClass(), "#process - return null: " + this.unit.getCapability().getName());
                    this.reject(this.streamDeadline != 0 ? AIGCStateCode.Timeout : AIGCStateCode.UnitError);
                    return;
                }

                Packet response = new Packet(dialect);
                if (Packet.extractCode(response) != AIGCStateCode.Ok.code) {
                    if (this.reservedStreamId != null) {
                        EventCenter.getInstance().removeUnitMeta(this.reservedStreamId);
                    }
                    Logger.w(this.getClass(), "#process - unit failed: " + Packet.extractCode(response));
                    meterFinish(Packet.extractDataPayload(response), "failed");
                    // 回调错误
                    this.listener.onFailed(this.channel, AIGCStateCode.parse(Packet.extractCode(response)));
                    return;
                }

                JSONObject payload = Packet.extractDataPayload(response);
                meterFinish(payload, "succeeded");
                String responseText = payload.getString("response");
                String thoughtText = payload.has("thought") ? payload.getString("thought") : "";
                JSONObject resultPayload = payload.has("resultPayload") ? payload.getJSONObject("resultPayload") : null;
                if (this.reservedStreamId != null && (resultPayload == null
                        || !this.reservedStreamId.equals(resultPayload.optString("streamId")))) {
                    EventCenter.getInstance().removeUnitMeta(this.reservedStreamId);
                    this.reject(AIGCStateCode.UnitError);
                    return;
                }
                Usage usage = null;
                if (payload.has("performance")) {
                    usage = new Usage(payload.getJSONObject("performance"));
                } else if (payload.has("usage")) {
                    usage = new Usage(payload.getJSONObject("usage"));
                }
                this.output = new MultimodalOutput(this.sn, this.unit.getCapability().getName(),
                        responseText, thoughtText, resultPayload);
                // 关联用量信息
                this.output.usage = usage;
                // 在应答前同步登记路由，不依赖用量信息或 starting 状态。
                if (null != resultPayload && resultPayload.has("streamId")) {
                    String streamId = resultPayload.getString("streamId");
                    EventCenter center = EventCenter.getInstance();
                    center.putUnitMeta(streamId, this);
                    UnitMeta owner = center.getUnitMeta(streamId);
                    if (owner instanceof MultimodalUnitMeta) {
                        ((MultimodalUnitMeta) owner).confirmStream();
                    }
                    String status = resultPayload.optString("status", "");
                    if ("stopped".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
                        center.markStreamStopped(streamId);
                    }
                }
                // 客户端收到应答即可提交下一次控制请求。
                if (!processingReleased) {
                    this.channel.setProcessing(false);
                    processingReleased = true;
                }
                this.listener.onResponse(this.channel, this.output);

                // 更新用量
                if (null != this.output.usage) {
                    this.service.getExecutor().execute(new Runnable() {
                        @Override
                        public void run() {
                            if (output.usage.inputTokens != 0 && output.usage.outputTokens != 0) {
                                Logger.d(MultimodalUnitMeta.class, "Update token usage: " +
                                        output.usage.inputTokens + "/" + output.usage.outputTokens);
                                service.getStorage().updateUsage(channel.getAuthToken().getContactId(),
                                        unit.getCapability().getName(),
                                        output.usage.outputTokens,
                                        output.usage.inputTokens);
                            }
                        }
                    });
                }
            } catch (IllegalStateException unavailable) {
                Logger.e(this.getClass(), "AI usage storage or protocol unavailable", unavailable);
                this.listener.onFailed(this.channel, AIGCStateCode.UsageUnavailable);
            } catch (Exception e) {
                Logger.e(this.getClass(), "#process", e);
                this.listener.onFailed(this.channel, AIGCStateCode.IllegalOperation);
            } finally {
                if (!processingReleased) {
                    this.channel.setProcessing(false);
                }
            }

        } finally {
            meterUnknown();
        }
    }
}
