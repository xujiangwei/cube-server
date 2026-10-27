/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import cell.util.Utils;
import cell.util.log.Logger;
import cube.aigc.psychology.Attribute;
import cube.common.JSONable;
import jdk.nashorn.api.scripting.ScriptObjectMirror;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 量表。
 */
public class Scale extends Questionnaire implements JSONable {

    private long sn;

    private long contactId;

    private Attribute attribute;

    private File structureFile;

    private long timestamp;

    private long endTimestamp;

    private ScaleResult result;

    public Scale(File structureFile, long contactId) {
        this.sn = Utils.generateSerialNumber();
        this.contactId = contactId;
        this.structureFile = structureFile;
        this.timestamp = System.currentTimeMillis();
        this.build(this.readJsonFile(structureFile));
    }

    public Scale(JSONObject json) {
        this.build(json);

        if (json.has("sn")) {
            this.sn = json.getLong("sn");
        }
        if (json.has("contactId")) {
            this.contactId = json.getLong("contactId");
        }
        if (json.has("attribute")) {
            this.attribute = new Attribute(json.getJSONObject("attribute"));
        }
        if (json.has("timestamp")) {
            this.timestamp = json.getLong("timestamp");
        }
        else {
            this.timestamp = System.currentTimeMillis();
        }

        if (json.has("result")) {
            this.result = new ScaleResult(json.getJSONObject("result"));
        }
    }

    /**
     * 获取序列号。
     *
     * @return 序列号。
     */
    public long getSN() {
        return this.sn;
    }

    /**
     * 获取联系人 ID。
     *
     * @return 联系人 ID。
     */
    public long getContactId() {
        return this.contactId;
    }

    /**
     * 获取时间戳。
     *
     * @return 时间戳。
     */
    public long getTimestamp() {
        return this.timestamp;
    }

    /**
     * 获取结束时间戳。
     *
     * @return 结束时间戳。
     */
    public long getEndTimestamp() {
        return this.endTimestamp;
    }

    /**
     * 设置结束时间戳。
     *
     * @param value 结束时间戳。
     */
    public void setEndTimestamp(long value) {
        this.endTimestamp = value;
    }

    /**
     * 设置属性。
     *
     * @param attribute 属性。
     */
    public void setAttribute(Attribute attribute) {
        this.attribute = attribute;
    }

    /**
     * 获取属性。
     *
     * @return 属性。
     */
    public Attribute getAttribute() {
        return this.attribute;
    }

    /**
     * 提交答题卡。
     *
     * @param sheet 答题卡。
     */
    public void submitAnswer(AnswerSheet sheet) {
        for (AnswerSheet.Answer answer : sheet.answers) {
            this.chooseAnswer(answer.sn, answer.choice);
        }
    }

    /**
     * 获取结果。
     *
     * @return 结果。
     */
    public ScaleResult getResult() {
        return this.result;
    }

    /**
     * 计算量表结果。
     *
     * @return 计分结果。
     */
    public ScaleResult scoring() {
        return this.scoring(this.structureFile.getParentFile());
    }

    /**
     * 按指定文件计算量表结果。
     *
     * @param path 计分脚本文件。
     * @return 计分结果。
     */
    public ScaleResult scoring(File path) {
        Path scoringScriptFile = Paths.get(path.getAbsolutePath(), this.scoringScript);
        if (!Files.exists(scoringScriptFile)) {
            Logger.w(this.getClass(), "#scoring - Scoring script file error: "
                    + scoringScriptFile.toFile().getAbsolutePath());
            return null;
        }

        StringBuilder script = new StringBuilder();
        script.append("var FactorLevel = Java.type('cube.aigc.psychology.composition.ScaleFactorLevel');\n");
        script.append("var ScaleScore = Java.type('cube.aigc.psychology.composition.ScaleScore');\n");
        script.append("var ScalePrompt = Java.type('cube.aigc.psychology.composition.ScalePrompt');\n");
        try {
            script.append(new String(Files.readAllBytes(scoringScriptFile), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Logger.e(this.getClass(), "#scoring", e);
            return null;
        }

        ScriptObjectMirror returnVal = null;
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("js");
        try {
            engine.eval(script.toString());
            Invocable invocable = (Invocable) engine;
            returnVal = (ScriptObjectMirror) invocable.invokeFunction("scoring", this.getAllChosenAnswers());
        } catch (ScriptException | NoSuchMethodException e) {
            Logger.e(this.getClass(), "#scoring", e);
            return null;
        }

        if (null == returnVal) {
            Logger.w(this.getClass(), "#scoring - Return value is null: " + this.name);
            return null;
        }

        String content = "";
        ScaleScore scaleScore = new ScaleScore();
        ScalePrompt scalePrompt = new ScalePrompt();
        if (returnVal.containsKey("content")) {
            content = returnVal.get("content").toString();
        }
        if (returnVal.containsKey("score")) {
            scaleScore = (ScaleScore) returnVal.get("score");
        }
        if (returnVal.containsKey("prompt")) {
            scalePrompt = (ScalePrompt) returnVal.get("prompt");
        }

        this.result = new ScaleResult(content, scaleScore, scalePrompt, this);
        return this.result;
    }

    /**
     * 读取指定 JSON 格式的文件。
     * @param file file。
     * @return JSON 对象。
     */
    private JSONObject readJsonFile(File file) {
        JSONObject json = null;
        try {
            byte[] data = Files.readAllBytes(Paths.get(file.getAbsolutePath()));
            json = new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Logger.w(this.getClass(), "#readJsonFile - Read file error", e);
        }
        return json;
    }

    /**
     * 判断指定对象是否与本对象相等。
     *
     * @param object 待比较对象。
     * @return 相等返回 {@code true} 。
     */
    @Override
    public boolean equals(Object object) {
        if (object instanceof Scale) {
            Scale other = (Scale) object;
            if (other.name.equalsIgnoreCase(this.name)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 计算哈希值。
     *
     * @return 哈希值。
     */
    @Override
    public int hashCode() {
        return this.name.hashCode();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = this.toCompactJSON();
        JSONArray sections = new JSONArray();
        for (QuestionSection questionSection : this.questionSections) {
            sections.put(questionSection.toJSON());
        }
        json.put("sections", sections);
        json.put("scoringScript", this.scoringScript);
        if (null != this.result) {
            json.remove("result");
            json.put("result", this.result.toJSON());
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
        JSONObject json = new JSONObject();
        json.put("sn", this.sn);
        json.put("contactId", this.contactId);
        json.put("name", this.name);
        json.put("displayName", this.displayName);
        json.put("instruction", this.instruction);
        if (null != this.attribute) {
            json.put("attribute", this.attribute.toJSON());
        }
        json.put("timestamp", this.timestamp);
        if (null != this.result) {
            json.put("result", this.result.toCompactJSON());
        }
        return json;
    }
}
