/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import cube.aigc.psychology.ComprehensiveReport;
import cube.aigc.psychology.Report;
import cube.common.JSONable;
import cube.common.entity.Article;
import cube.common.entity.User;
import org.json.JSONObject;

/**
 * 报告数据关系描述。
 */
public class ConversationRelation implements JSONable {

    /** 名称。 */
    public final String name;

    private long reportSn = 0;

    private Report report;

    private long articleSn = 0;

    private ReportArticle article;

    private long comprehensiveReportSn = 0;

    private ComprehensiveReport comprehensiveReport;

    private long uid = 0;

    private User user;

    public ConversationRelation() {
        this.name = "Anonymous";
    }

    public ConversationRelation(JSONObject json) {
        this.name = json.getString("name");
        if (json.has("reportSn")) {
            this.reportSn = json.getLong("reportSn");
        }
        if (json.has("articleSn")) {
            this.articleSn = json.getLong("articleSn");
        }
        if (json.has("comprehensiveReportSn")) {
            this.comprehensiveReportSn = json.getLong("comprehensiveReportSn");
        }
        if (json.has("uid")) {
            this.uid = json.getLong("uid");
        }
    }

    /**
     * 判断是否报告。
     *
     * @return 报告。
     */
    public boolean hasReport() {
        return this.reportSn > 0;
    }

    /**
     * 获取报告序列号。
     *
     * @return 报告序列号。
     */
    public long getReportSn() {
        return this.reportSn;
    }

    /**
     * 设置报告。
     *
     * @param report 报告。
     */
    public void setReport(Report report) {
        this.report = report;
    }

    /**
     * 获取报告。
     *
     * @return 报告。
     */
    public Report getReport() {
        return this.report;
    }

    /**
     * 判断是否文章。
     *
     * @return 文章。
     */
    public boolean hasArticle() {
        return this.articleSn > 0;
    }

    /**
     * 获取文章序列号。
     *
     * @return 文章序列号。
     */
    public long getArticleSn() {
        return this.articleSn;
    }

    /**
     * 设置文章。
     *
     * @param article 文章。
     */
    public void setArticle(ReportArticle article) {
        this.article = article;
    }

    /**
     * 获取文章。
     *
     * @return 文章。
     */
    public Article getArticle() {
        return this.article;
    }

    /**
     * 判断是否综合报告。
     *
     * @return 综合报告。
     */
    public boolean hasComprehensiveReport() {
        return this.comprehensiveReportSn > 0;
    }

    /**
     * 获取综合报告序列号。
     *
     * @return 综合报告序列号。
     */
    public long getComprehensiveReportSn() {
        return this.comprehensiveReportSn;
    }

    /**
     * 设置综合报告。
     *
     * @param comprehensiveReport 综合报告。
     */
    public void setComprehensiveReport(ComprehensiveReport comprehensiveReport) {
        this.comprehensiveReport = comprehensiveReport;
    }

    /**
     * 获取综合报告。
     *
     * @return 综合报告。
     */
    public ComprehensiveReport getComprehensiveReport() {
        return this.comprehensiveReport;
    }

    /**
     * 判断是否用户。
     *
     * @return 用户。
     */
    public boolean hasUser() {
        return this.uid > 0;
    }

    /**
     * 获取用户 ID。
     *
     * @return 用户 ID。
     */
    public long getUid() {
        return this.uid;
    }

    /**
     * 设置用户。
     *
     * @param user 用户。
     */
    public void setUser(User user) {
        this.user = user;
    }

    /**
     * 获取用户。
     *
     * @return 用户。
     */
    public User getUser() {
        return this.user;
    }

    /**
     * 判断是否有效用户。
     *
     * @return 有效用户。
     */
    public boolean isValidUser() {
        if (null != this.user) {
            return this.user.isRegistered();
        }

        return false;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("name", this.name);
        if (this.reportSn > 0) {
            json.put("reportSn", this.reportSn);
        }
        if (this.articleSn > 0) {
            json.put("articleSn", this.articleSn);
        }
        if (this.comprehensiveReportSn > 0) {
            json.put("comprehensiveReportSn", this.comprehensiveReportSn);
        }
        if (this.uid > 0) {
            json.put("uid", this.uid);
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
