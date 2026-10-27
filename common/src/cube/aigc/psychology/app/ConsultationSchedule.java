/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology.app;

import cube.aigc.psychology.consultation.ConsultationTheme;
import cube.aigc.psychology.consultation.ConsultationMethod;
import cube.aigc.psychology.consultation.ConsultationScheduleState;
import cube.common.JSONable;
import cube.util.ConfigUtils;
import org.json.JSONObject;

/**
 * 咨询日程。
 */
public class ConsultationSchedule implements JSONable {

    /** ID。 */
    public final long id;

    /** 客户 ID。 */
    public final long customerId;

    /** 预约时间。 */
    public long appointmentTime;

    /** 预约时长。 */
    public long appointmentDuration;

    /** 预约主题。 */
    public ConsultationTheme appointmentTheme;

    /** 预约咨询。 */
    public ConsultationMethod appointmentConsultation;

    /** 状态。 */
    public ConsultationScheduleState state;

    /** 咨询时间。 */
    public long counselingTime;

    /** 咨询时长。 */
    public long counselingDuration;

    /** 咨询主题。 */
    public ConsultationTheme counselingTheme;

    /** 咨询咨询。 */
    public ConsultationMethod counselingConsultation;

    /** 录音名称。 */
    public String recordingName;

    /** 录音文件码。 */
    public String recordingFileCode;

    /** 声音语音分离 ID。 */
    public long voiceDiarizationId;

    public ConsultationSchedule(long id, long customerId) {
        this.id = id;
        this.customerId = customerId;
    }

    public ConsultationSchedule(ConsultationSchedule src) {
        this.id = ConfigUtils.generateSerialNumber();
        this.customerId = src.customerId;
        this.appointmentTime = src.appointmentTime;
        this.appointmentDuration = src.appointmentDuration;
        this.appointmentTheme = src.appointmentTheme;
        this.appointmentConsultation = src.appointmentConsultation;
        this.state = src.state;
        this.counselingTime = src.counselingTime;
        this.counselingDuration = src.counselingDuration;
        this.counselingTheme = src.counselingTheme;
        this.counselingConsultation = src.counselingConsultation;
        this.recordingName = src.recordingName;
        this.recordingFileCode = src.recordingFileCode;
        this.voiceDiarizationId = src.voiceDiarizationId;
    }

    public ConsultationSchedule(JSONObject json) {
        this.id = json.getLong("id");
        this.customerId = json.getLong("customerId");
        this.appointmentTime = json.getLong("appointmentTime");
        this.appointmentDuration = json.getLong("appointmentDuration");
        this.appointmentTheme = ConsultationTheme.parse(json.getString("appointmentTheme"));
        this.appointmentConsultation = ConsultationMethod.parse(json.getInt("appointmentConsultation"));
        this.state = ConsultationScheduleState.parse(json.getInt("state"));
        this.counselingTime = json.getLong("counselingTime");
        this.counselingDuration = json.getLong("counselingDuration");
        this.counselingTheme = ConsultationTheme.parse(json.getString("counselingTheme"));
        this.counselingConsultation = ConsultationMethod.parse(json.getInt("counselingConsultation"));
        this.recordingName = json.has("recordingName") ? json.getString("recordingName") : null;
        this.recordingFileCode = json.has("recordingFileCode") ? json.getString("recordingFileCode") : null;
        this.voiceDiarizationId = json.has("voiceDiarizationId") ? json.getLong("voiceDiarizationId") : 0;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("id", this.id);
        json.put("customerId", this.customerId);
        json.put("appointmentTime", this.appointmentTime);
        json.put("appointmentDuration", this.appointmentDuration);
        json.put("appointmentTheme", this.appointmentTheme.code);
        json.put("appointmentConsultation", this.appointmentConsultation.code);
        json.put("state", this.state.code);
        json.put("counselingTime", this.counselingTime);
        json.put("counselingDuration", this.counselingDuration);
        json.put("counselingTheme", this.counselingTheme.code);
        json.put("counselingConsultation", this.counselingConsultation.code);
        if (null != this.recordingName) {
            json.put("recordingName", this.recordingName);
        }
        if (null != this.recordingFileCode) {
            json.put("recordingFileCode", this.recordingFileCode);
        }
        if (0 != this.voiceDiarizationId) {
            json.put("voiceDiarizationId", this.voiceDiarizationId);
        }
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>用于传输体积敏感的场合，字段较完整序列化更少。</p>
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
