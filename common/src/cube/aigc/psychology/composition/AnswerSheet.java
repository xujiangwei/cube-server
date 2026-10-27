/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 问卷答题卡。
 */
public class AnswerSheet {

    /** 量表序列号。 */
    public final long scaleSn;

    /** 回答列表。 */
    public final List<Answer> answers;

    public AnswerSheet(long scaleSn) {
        this.scaleSn = scaleSn;
        this.answers = new ArrayList<>();
    }

    public AnswerSheet(JSONObject json) {
        this.scaleSn = json.getLong("sn");
        this.answers = new ArrayList<>();
        JSONArray array = json.getJSONArray("answers");
        for (int i = 0; i < array.length(); ++i) {
            Answer answer = new Answer(array.getJSONObject(i));
            this.answers.add(answer);
        }

        Collections.sort(this.answers, new Comparator<Answer>() {
            @Override
            public int compare(Answer a1, Answer a2) {
                return a1.sn - a2.sn;
            }
        });
    }

    /**
     * 统计回答列表。
     *
     * @return 数量。
     */
    public int numAnswers() {
        return this.answers.size();
    }

    /**
     * 记录一题的作答。
     *
     * @param sn     题目序号。
     * @param choice 选项内容。
     */
    public void submit(int sn, String choice) {
        this.answers.add(new Answer(sn, choice));

        Collections.sort(this.answers, new Comparator<Answer>() {
            @Override
            public int compare(Answer a1, Answer a2) {
                return a1.sn - a2.sn;
            }
        });
    }

    /**
     * 获取回答列表。
     *
     * @return 回答列表。
     */
    public List<Answer> getAnswers() {
        return this.answers;
    }

    /**
     * 判断是否有效。
     *
     * @return 有效。
     */
    public boolean isValid() {
        if (this.answers.isEmpty()) {
            return false;
        }

        HashMap<String, AtomicInteger> countMap = new HashMap<>();
        for (Answer answer : this.answers) {
            AtomicInteger value = countMap.get(answer.choice);
            if (null == value) {
                value = new AtomicInteger(0);
                countMap.put(answer.choice, value);
            }
            value.incrementAndGet();
        }

        if (countMap.size() == 1) {
            // 所有答案都是同一个选项
            return false;
        }

        return true;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("sn", this.scaleSn);

        JSONArray array = new JSONArray();
        for (Answer answer : this.answers) {
            array.put(answer.toJSON());
        }
        json.put("answers", array);
        return json;
    }

    public class Answer {

        public final int sn;

        public final String choice;

        public Answer(int sn, String choice) {
            this.sn = sn;
            this.choice = choice;
        }

        public Answer(JSONObject json) {
            this.sn = json.getInt("sn");
            this.choice = json.getString("choice");
        }

        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("sn", this.sn);
            json.put("choice", this.choice);
            return json;
        }
    }
}
