/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.usage;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 用量计量表的结构定义。
 *
 * <p>AIGC 与 Harness 在自己的库里使用同一套结构，只在表名前缀上区分：
 * {@code aigc_ai_usage_} 或 {@code harness_ai_usage_} 。建表由 modules 侧（service / service-harness）
 * 在启动时执行，Console 只读，绝不建表或升级结构。</p>
 *
 * <p>四类表的分工：</p>
 * <ul>
 *     <li>{@code store}：单行元数据，保存随机库身份、结构版本、采集起点与最后更新时间；</li>
 *     <li>{@code call}：按 meter 逐条记录归属、状态、质量、Token 与修订号；</li>
 *     <li>{@code revision}：稳定证据摘要与修订审计，用于幂等与冲突判定；</li>
 *     <li>{@code hourly}：按 UTC 整小时预聚合，供趋势与排行查询。</li>
 * </ul>
 */
public final class UsageSchema {

    private UsageSchema() {
    }

    /**
     * 校验表名前缀。
     *
     * <p>只接受两个已知前缀，避免把任意字符串拼进 DDL 造成越权建表。</p>
     *
     * @param p 待校验的前缀。
     * @return 原样返回的前缀。
     * @throws IllegalArgumentException 前缀不是 {@code aigc_ai_usage_} 或 {@code harness_ai_usage_} 。
     */
    public static String prefix(String p) {
        if (!"aigc_ai_usage_".equals(p) && !"harness_ai_usage_".equals(p)) {
            throw new IllegalArgumentException("INVALID_USAGE_PREFIX");
        }
        return p;
    }

    /**
     * 生成建表与建索引语句。
     *
     * <p>返回顺序即执行顺序：先四张表，再各自的索引。MySQL 目标会追加 InnoDB 引擎与
     * {@code utf8mb4_bin} 排序规则，SQLite 目标不加表尾选项。</p>
     *
     * @param prefix 表名前缀，取值受 {@link #prefix(String)} 约束。
     * @param mysql  目标是否为 MySQL。
     * @return 按执行顺序排列的 DDL 列表。
     * @throws IllegalArgumentException 前缀非法。
     */
    public static List<String> statements(String prefix, boolean mysql) {
        String p = prefix(prefix);
        String tail = mysql ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin" : "";
        List<String> s = new ArrayList<>();
        s.add("CREATE TABLE " + p + "store (singleton INTEGER NOT NULL,store_id VARCHAR(64) NOT NULL,schema_version INTEGER NOT NULL,collected_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,PRIMARY KEY(singleton))" + tail);
        s.add("CREATE TABLE " + p + "call (meter_id VARCHAR(128) NOT NULL,domain VARCHAR(128) NOT NULL,contact_id BIGINT NOT NULL,module VARCHAR(64) NOT NULL,model VARCHAR(128) NOT NULL,unit_id VARCHAR(128) NOT NULL,run_id VARCHAR(128) NOT NULL,request_id VARCHAR(128) NOT NULL,started_at BIGINT NOT NULL,finished_at BIGINT NULL,status VARCHAR(32) NOT NULL,quality VARCHAR(16) NOT NULL,input_tokens BIGINT NULL,output_tokens BIGINT NULL,revision BIGINT NOT NULL,PRIMARY KEY(meter_id))" + tail);
        s.add("CREATE INDEX " + p + "contact_time ON " + p + "call (domain,contact_id,started_at)");
        s.add("CREATE INDEX " + p + "time ON " + p + "call (started_at,meter_id)");
        s.add("CREATE TABLE " + p + "revision (meter_id VARCHAR(128) NOT NULL,revision BIGINT NOT NULL,evidence_hash VARCHAR(64) NOT NULL,evidence_json TEXT NOT NULL,recorded_at BIGINT NOT NULL,PRIMARY KEY(meter_id,revision))" + tail);
        s.add("CREATE TABLE " + p + "hourly (bucket_id VARCHAR(64) NOT NULL,hour_at BIGINT NOT NULL,domain VARCHAR(128) NOT NULL,contact_id BIGINT NOT NULL,module VARCHAR(64) NOT NULL,model VARCHAR(128) NOT NULL,unit_id VARCHAR(128) NOT NULL,calls BIGINT NOT NULL,actual_calls BIGINT NOT NULL,estimated_calls BIGINT NOT NULL,unknown_calls BIGINT NOT NULL,pending_calls BIGINT NOT NULL,input_tokens BIGINT NOT NULL,output_tokens BIGINT NOT NULL,estimated_input BIGINT NOT NULL,estimated_output BIGINT NOT NULL,PRIMARY KEY(bucket_id))" + tail);
        s.add("CREATE INDEX " + p + "hour_contact ON " + p + "hourly (domain,contact_id,hour_at)");
        return s;
    }
}
