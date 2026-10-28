/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 能力集。
 */
public class AICapability implements JSONable {

    /** 多模态任务。 */
    public final static String MultimodalTask = "Multimodal";

    /** 自然语言处理任务。 */
    public final static String NaturalLanguageProcessingTask = "NaturalLanguageProcessing";

    /** 计算机视觉任务。 */
    public final static String ComputerVisionTask = "ComputerVision";

    /** 音频处理任务。 */
    public final static String AudioProcessingTask = "AudioProcessing";

    /** 数据处理任务。 */
    public final static String DataProcessingTask = "DataProcessing";

    /** 其他处理任务。 */
    public final static String OtherProcessingTask = "OtherProcessing";

    private final String name;

    private final String task;

    private final String version;

    private final List<String> subtasks;

    private final String description;

    /**
     * 可选的协议能力声明。
     *
     * <p>旧版本单元不声明该字段，默认是空对象。</p>
     */
    private volatile JSONObject chatCompletion = new JSONObject();
    private volatile int usageProtocol;

    public int getUsageProtocol() { return usageProtocol; }
    public void setUsageProtocol(int version) { usageProtocol = version; }

    public AICapability(String name, String task, String version, List<String> subtasks, String description) {
        this.name = name;
        this.task = task;
        this.version = version;
        this.subtasks = subtasks;
        this.description = description;
    }

    public AICapability(JSONObject json) {
        this.name = json.getString("name");
        this.task = json.getString("task");
        this.version = json.has("version") ? json.getString("version") : "1.0";

        this.subtasks = new ArrayList<>();
        if (json.has("subtask")) {
            this.subtasks.add(json.getString("subtask"));
        }
        else if (json.has("subtasks")) {
            JSONArray array = json.getJSONArray("subtasks");
            for (int i = 0; i < array.length(); ++i) {
                this.subtasks.add(array.getString(i));
            }
        }

        this.description = json.getString("description");
        this.usageProtocol = json.optInt("usageProtocol", 0);
        if (json.optJSONObject("chatCompletion") != null) {
            this.chatCompletion = new JSONObject(json.getJSONObject("chatCompletion").toString());
        }
    }

    /**
     * 获取名称。
     *
     * @return 名称。
     */
    public String getName() {
        return this.name;
    }

    /**
     * 获取任务。
     *
     * @return 任务。
     */
    public String getTask() {
        return this.task;
    }

    /**
     * 获取版本。
     *
     * @return 版本。
     */
    public String getVersion() {
        return this.version;
    }

    /**
     * 获取主子任务。
     *
     * @return 主子任务。
     */
    public String getPrimarySubtask() {
        return this.subtasks.get(0);
    }

    /**
     * 判断是否包含子任务。
     *
     * @param subtask 子任务。
     * @return 子任务。
     */
    public boolean containsSubtask(String subtask) {
        return this.subtasks.contains(subtask);
    }

    /**
     * 获取描述。
     *
     * @return 描述。
     */
    public String getDescription() {
        return this.description;
    }

    /**
     * 获取兼容层生成。
     *
     * @return 兼容层生成。
     */
    public JSONObject getChatCompletion() {
        return new JSONObject(this.chatCompletion.toString());
    }

    /**
     * 设置兼容层生成。
     *
     * @param capability 兼容层生成。
     */
    public void setChatCompletion(JSONObject capability) {
        this.chatCompletion = new JSONObject(capability.toString());
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        if (usageProtocol > 0) json.put("usageProtocol", usageProtocol);
        json.put("name", this.name);
        json.put("task", this.task);
        json.put("version", this.version);

        if (this.subtasks.size() == 1) {
            json.put("subtask", this.subtasks.get(0));
        }
        else {
            JSONArray array = new JSONArray();
            for (String subtask : this.subtasks) {
                array.put(subtask);
            }
            json.put("subtasks", array);
        }

        json.put("description", this.description);
        if (this.chatCompletion.length() != 0) {
            json.put("chatCompletion", getChatCompletion());
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


    public static class Multimodal {

        public final static String TextToImage = "TextToImage";

        public final static String ImageToText = "ImageToText";

        public final static String TextToVideo = "TextToVideo";

        public final static String VisualQuestionAnswering = "VisualQuestionAnswering";

        public final static String DocumentQuestionAnswering = "DocumentQuestionAnswering";

        public final static String FeatureExtraction = "FeatureExtraction";

        public final static String ImageTextToText = "ImageTextToText";

        private Multimodal() {
        }
    }


    public static class NaturalLanguageProcessing {

        public final static String Segmentation = "Segmentation";

        public final static String TextClassification = "TextClassification";

        public final static String TokenClassification = "TokenClassification";

        public final static String TableQuestionAnswering = "TableQuestionAnswering";

        public final static String QuestionAnswering = "QuestionAnswering";

        public final static String ZeroShotClassification = "ZeroShotClassification";

        public final static String Translation = "Translation";

        public final static String Summarization = "Summarization";

        public final static String Conversational = "Conversational";

        public final static String TextGeneration = "TextGeneration";

        public final static String Text2TextGeneration = "Text2TextGeneration";

        public final static String FillMask = "FillMask";

        public final static String SentenceSimilarity = "SentenceSimilarity";

        public final static String SentimentAnalysis = "SentimentAnalysis";

        public final static String ExtractKeywords = "ExtractKeywords";

        public final static String MultiTask = "MultiTask";

        public final static String KnowledgeComprehension = "KnowledgeComprehension";

        public final static String SemanticSearch = "SemanticSearch";

        public final static String ParaphraseMining = "ParaphraseMining";

        public final static String RetrieveReRank = "RetrieveReRank";

        private NaturalLanguageProcessing() {
        }
    }


    public static class ComputerVision {

        public final static String DepthEstimation = "DepthEstimation";

        public final static String ImageClassification = "ImageClassification";

        public final static String ObjectDetection = "ObjectDetection";

        public final static String ImageSegmentation = "ImageSegmentation";

        public final static String ImageToImage = "ImageToImage";

        public final static String UnconditionalImageGeneration = "UnconditionalImageGeneration";

        public final static String VideoClassification = "VideoClassification";

        public final static String ZeroShotImageClassification = "ZeroShotImageClassification";

        public final static String FacialExpressionRecognition = "FacialExpressionRecognition";

        public final static String PoseEstimation = "PoseEstimation";

        private ComputerVision() {
        }
    }


    public static class AudioProcessing {

        public final static String TextToSpeech = "TextToSpeech";

        public final static String AutomaticSpeechRecognition = "AutomaticSpeechRecognition";

        public final static String AudioToAudio = "AudioToAudio";

        public final static String AudioClassification = "AudioClassification";

        public final static String VoiceActivityDetection = "VoiceActivityDetection";

        /**
         * 说话人验证。
         */
        public final static String SpeakerVerification = "SpeakerVerification";

        /**
         * 说话人识别。
         */
        public final static String SpeakerIdentification = "SpeakerIdentification";

        /**
         * 说话人分割聚类、说话人分离。
         */
        public final static String SpeakerDiarization = "SpeakerDiarization";

        private AudioProcessing() {
        }
    }


    public static class DataProcessing {

        public final static String ExtractURLContent = "ExtractURLContent";

        private DataProcessing() {
        }
    }

    public static class PsychologyProcessing {

        public final static String GeneratePaintingTemplateArticle = "GeneratePaintingTemplateArticle";

        private PsychologyProcessing() {
        }
    }

    public static class OtherProcessing {

        public final static String Psychology = "Psychology";

        private OtherProcessing() {
        }
    }
}
