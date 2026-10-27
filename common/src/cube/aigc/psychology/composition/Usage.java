/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import cube.aigc.psychology.PaintingReport;
import cube.aigc.psychology.ScaleReport;
import cube.auth.AuthToken;
import cube.common.JSONable;
import org.json.JSONObject;

public class Usage implements JSONable {

    /** 查询类型绘画。 */
    public final static String QUERY_TYPE_PAINTING = "painting";

    /** 查询类型量表。 */
    public final static String QUERY_TYPE_SCALE = "scale";

    /** 联系人 ID。 */
    public long cid;

    /** 令牌。 */
    public String token;

    /** 时间戳。 */
    public long timestamp;

    /** 远端主机。 */
    public String remoteHost;

    /** 查询。 */
    public String query;

    /** 查询类型。 */
    public String queryType;

    /** 查询令牌列表。 */
    public long queryTokens;

    /** 补全消耗的 token 数。 */
    public long completionTokens;

    /** 补全任务序列号。 */
    public long completionSN;

    public Usage(AuthToken authToken, String remoteHost, PaintingReport report) {
        this.cid = authToken.getContactId();
        this.token = authToken.getCode();
        this.timestamp = report.timestamp;
        this.remoteHost = remoteHost;
        this.query = report.getFileCode();
        this.queryType = QUERY_TYPE_PAINTING;
        this.queryTokens = report.painting.getAllThings().size();
        this.completionTokens = report.getSummary().length();
        this.completionSN = report.sn;
    }

    public Usage(AuthToken authToken, String remoteHost, ScaleReport report) {
        this.cid = authToken.getContactId();
        this.token = authToken.getCode();
        this.timestamp = report.timestamp;
        this.remoteHost = remoteHost;
        this.query = "";
        this.queryType = QUERY_TYPE_SCALE;
        this.queryTokens = report.getFactors().size();
        this.completionTokens = report.getSummary().length();
        this.completionSN = report.sn;
    }

    public Usage(long cid, String token, long timestamp, String remoteHost, String query, String queryType,
                 long queryTokens, long completionTokens, long completionSN) {
        this.cid = cid;
        this.token = token;
        this.timestamp = timestamp;
        this.remoteHost = remoteHost;
        this.query = query;
        this.queryType = queryType;
        this.queryTokens = queryTokens;
        this.completionTokens = completionTokens;
        this.completionSN = completionSN;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("cid", this.cid);
        json.put("token", this.token);
        json.put("timestamp", this.timestamp);
        json.put("remoteHost", this.remoteHost);
        json.put("query", this.query);
        json.put("queryType", this.queryType);
        json.put("queryTokens", this.queryTokens);
        json.put("completionTokens", this.completionTokens);
        json.put("completionSN", this.completionSN);
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
