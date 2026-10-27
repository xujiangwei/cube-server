/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file.misc;

import org.json.JSONObject;

/**
 * 媒体流属性描述。
 */
public class StreamAttribute {

    /** 索引。 */
    public int index;

    /** 编解码器名称。 */
    public String codecName;

    /** 编解码器长名称。 */
    public String codecLongName;

    /** 音视频参数：侧写。 */
    public String profile;

    /** 编解码器类型。 */
    public String codecType;

    /** 编解码器标签字符串。 */
    public String codecTagString;

    /** 编解码器标签。 */
    public String codecTag;

    /** 视频参数：宽度。 */
    public int width;

    /** 视频参数：高度。 */
    public int height;

    /** 视频参数：编码宽度。 */
    public int codedWidth;

    /** 视频参数：编码高度。 */
    public int codedHeight;

    /** 视频参数：隐藏字幕。 */
    public int closedCaptions;

    /** 视频参数：胶片颗粒。 */
    public int filmGrain;

    /** 视频参数：采样宽高比。 */
    public String sampleAspectRatio;

    /** 视频参数：显示宽高比。 */
    public String displayAspectRatio;

    /** 视频参数：像素格式。 */
    public String pixFmt;

    /** 视频参数：编码级别。 */
    public int level;

    /** 视频参数：色彩范围。 */
    public String colorRange;

    /** 视频参数：色彩空间。 */
    public String colorSpace;

    /** 视频参数：色彩传递特性。 */
    public String colorTransfer;

    /** 视频参数：色彩原色。 */
    public String colorPrimaries;

    /** 视频参数：色度位置。 */
    public String chromaLocation;

    /** 视频参数：场序。 */
    public String fieldOrder;

    /** 视频参数：参考帧数。 */
    public int refs;

    /** 视频参数：是否为 AVC（H.264）编码。 */
    public boolean isAvc;

    /** 视频参数：NAL 长度字段大小。 */
    public int nalLengthSize;

    /** 视频参数：标识。 */
    public String id;

    /** 音频参数：采样格式。 */
    public String sampleFmt;

    /** 音频参数：采样率。 */
    public int sampleRate;

    /** 音频参数：声道数。 */
    public int channels;

    /** 音频参数：声道布局。 */
    public String channelLayout;

    /** 音频参数：位深。 */
    public int bitsPerSample;

    /** 帧率的有理数表示，如 {@code 30/1} 。 */
    public String rFrameRate;

    /** 平均帧率。 */
    public String avgFrameRate;

    /** 时间基。 */
    public String timeBase;

    /** 音视频参数：起始显示时间戳。 */
    public int startPts = -1;

    /** 音视频参数：起始时间。 */
    public double startTime = -1;

    /** 时长（时间基）。 */
    public long durationTS;

    /** 时长。 */
    public double duration;

    /** 码率。 */
    public long bitRate;

    /** 视频参数：原始采样位深。 */
    public int bitsPerRawSample;

    /** 音视频参数：帧数。 */
    public int numFrames = 0;

    /** 音视频参数：附加数据大小。 */
    public int extradataSize = 0;

    /** 处置标志。 */
    public JSONObject disposition;

    /** 仅音频具有的标签集合。 */
    public JSONObject tags;

    public StreamAttribute(JSONObject json) {
        this.index = json.getInt("index");
        this.codecName = json.getString("codec_name");
        this.codecLongName = json.getString("codec_long_name");

        if (json.has("profile")) {
            this.profile = json.getString("profile");
        }

        this.codecType = json.getString("codec_type");
        this.codecTagString = json.getString("codec_tag_string");
        this.codecTag = json.getString("codec_tag");

        if (json.has("width")) {
            this.width = json.getInt("width");
        }
        if (json.has("height")) {
            this.height = json.getInt("height");
        }
        if (json.has("coded_width")) {
            this.codedWidth = json.getInt("coded_width");
        }
        if (json.has("coded_height")) {
            this.codedHeight = json.getInt("coded_height");
        }
        if (json.has("closed_captions")) {
            this.closedCaptions = json.getInt("closed_captions");
        }
        if (json.has("film_grain")) {
            this.filmGrain = json.getInt("film_grain");
        }
        if (json.has("sample_aspect_ratio")) {
            this.sampleAspectRatio = json.getString("sample_aspect_ratio");
        }
        if (json.has("display_aspect_ratio")) {
            this.displayAspectRatio = json.getString("display_aspect_ratio");
        }
        if (json.has("pix_fmt")) {
            this.pixFmt = json.getString("pix_fmt");
        }
        if (json.has("level")) {
            this.level = json.getInt("level");
        }
        if (json.has("color_range")) {
            this.colorRange = json.getString("color_range");
        }
        if (json.has("color_space")) {
            this.colorSpace = json.getString("color_space");
        }
        if (json.has("color_transfer")) {
            this.colorTransfer = json.getString("color_transfer");
        }
        if (json.has("color_primaries")) {
            this.colorPrimaries = json.getString("color_primaries");
        }
        if (json.has("chroma_location")) {
            this.chromaLocation = json.getString("chroma_location");
        }
        if (json.has("field_order")) {
            this.fieldOrder = json.getString("field_order");
        }
        if (json.has("refs")) {
            this.refs = json.getInt("refs");
        }
        if (json.has("is_avc")) {
            this.isAvc = json.getString("is_avc").equalsIgnoreCase("true");
        }
        if (json.has("nal_length_size")) {
            this.nalLengthSize = Integer.parseInt(json.getString("nal_length_size"));
        }
        if (json.has("id")) {
            this.id = json.getString("id");
        }

        if (json.has("sample_fmt")) {
            this.sampleFmt = json.getString("sample_fmt");
        }
        if (json.has("sample_rate")) {
            this.sampleRate = Integer.parseInt(json.getString("sample_rate"));
        }
        if (json.has("channels")) {
            this.channels = json.getInt("channels");
        }
        if (json.has("channel_layout")) {
            this.channelLayout = json.getString("channel_layout");
        }
        if (json.has("bits_per_sample")) {
            this.bitsPerSample = json.getInt("bits_per_sample");
        }

        this.rFrameRate = json.getString("r_frame_rate");
        this.avgFrameRate = json.getString("avg_frame_rate");
        this.timeBase = json.getString("time_base");

        if (json.has("start_pts")) {
            this.startPts = json.getInt("start_pts");
        }
        if (json.has("start_time")) {
            this.startTime = Double.parseDouble(json.getString("start_time"));
        }

        this.durationTS = json.getLong("duration_ts");
        this.duration = Double.parseDouble(json.getString("duration"));
        this.bitRate = Long.parseLong(json.getString("bit_rate"));

        if (json.has("bits_per_raw_sample")) {
            this.bitsPerRawSample = Integer.parseInt(json.getString("bits_per_raw_sample"));
        }
        if (json.has("nb_frames")) {
            this.numFrames = Integer.parseInt(json.getString("nb_frames"));
        }
        if (json.has("extradata_size")) {
            this.extradataSize = json.getInt("extradata_size");
        }

        if (json.has("disposition")) {
            this.disposition = json.getJSONObject("disposition");
        }

        if (json.has("tags")) {
            this.tags = json.getJSONObject("tags");
        }
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("index", this.index);
        json.put("codec_name", this.codecName);
        json.put("codec_long_name", this.codecLongName);
        json.put("codec_type", this.codecType);
        json.put("codec_tag_string", this.codecTagString);
        json.put("codec_tag", this.codecTag);

        if (null != this.profile) {
            json.put("profile", this.profile);
        }

        if (this.codecType.equalsIgnoreCase("video")) {
            json.put("width", this.width);
            json.put("height", this.height);
            json.put("coded_width", this.codedWidth);
            json.put("coded_height", this.codedHeight);
            json.put("closed_captions", this.closedCaptions);
            json.put("film_grain", this.filmGrain);
            json.put("sample_aspect_ratio", this.sampleAspectRatio);
            json.put("display_aspect_ratio", this.displayAspectRatio);
            json.put("pix_fmt", this.pixFmt);
            json.put("level", this.level);
            json.put("color_range", this.colorRange);
            json.put("color_space", this.colorSpace);
            json.put("color_transfer", this.colorTransfer);
            json.put("color_primaries", this.colorPrimaries);
            json.put("chroma_location", this.chromaLocation);
            json.put("field_order", this.fieldOrder);
            json.put("refs", this.refs);
            json.put("is_avc", this.isAvc ? "true" : "false");
            json.put("nal_length_size", Integer.toString(this.nalLengthSize));
            json.put("bits_per_raw_sample", Integer.toString(this.bitsPerRawSample));
        }
        else if (this.codecType.equalsIgnoreCase("audio")) {
            json.put("sample_fmt", this.sampleFmt);
            json.put("sample_rate", Integer.toString(this.sampleRate));
            json.put("channels", this.channels);
            json.put("bits_per_sample", this.bitsPerSample);

            if (null != this.channelLayout) {
                json.put("channel_layout", this.channelLayout);
            }
        }

        if (null != this.id) {
            json.put("id", this.id);
        }

        if (this.startPts >= 0) {
            json.put("start_pts", this.startPts);
            json.put("start_time", Double.toString(this.startTime));
        }

        if (this.numFrames > 0) {
            json.put("nb_frames", Integer.toString(this.numFrames));
        }
        if (this.extradataSize > 0) {
            json.put("extradata_size", this.extradataSize);
        }

        json.put("r_frame_rate", this.rFrameRate);
        json.put("avg_frame_rate", this.avgFrameRate);
        json.put("time_base", this.timeBase);
        json.put("duration_ts", this.durationTS);
        json.put("duration", Double.toString(this.duration));
        json.put("bit_rate", Long.toString(this.bitRate));

        if (null != this.disposition) {
            json.put("disposition", this.disposition);
        }

        if (null != this.tags) {
            json.put("tags", this.tags);
        }

        return json;
    }
}
