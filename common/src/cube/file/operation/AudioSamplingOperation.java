/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file.operation;

import cube.file.AudioOperation;
import cube.util.FileType;
import org.json.JSONObject;

/**
 * 音频文件重新采样。
 */
public class AudioSamplingOperation extends AudioOperation {

    public final static String Operation = "AudioSampling";

    private final static String KEY_OUTPUT_TYPE = "outputType";

    private final static String KEY_CHANNEL = "channel";

    private final static String KEY_RATE = "rate";

    /** 输出类型。 */
    public FileType outputType;

    /** 通道。 */
    public int channel = 1;

    /** 比率。 */
    public int rate = 16000;

    public AudioSamplingOperation(int channel, int rate) {
        super();
        this.outputType = FileType.WAV;
        this.channel = channel;
        this.rate = rate;
    }

    public AudioSamplingOperation(int channel, int rate, FileType outputType) {
        super();
        this.outputType = outputType;
        this.channel = channel;
        this.rate = rate;
    }

    public AudioSamplingOperation(JSONObject json) {
        super();
        this.outputType = FileType.matchExtension(json.getString(KEY_OUTPUT_TYPE));
        this.channel = json.getInt(KEY_CHANNEL);
        this.rate = json.getInt(KEY_RATE);
    }

    @Override
    public String getOperation() {
        return AudioSamplingOperation.Operation;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put(KEY_OUTPUT_TYPE, this.outputType.getPreferredExtension());
        json.put(KEY_CHANNEL, this.channel);
        json.put(KEY_RATE, this.rate);
        return json;
    }
}
