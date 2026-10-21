/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.files;

import cube.service.harness.store.JdbcRunStore;
import org.json.JSONObject;

import java.util.*;

/**
 * 文件服务的配置快照：读取并校验 properties 中的时长与限额，秒值统一换算为
 * 毫秒后以不可变字段保存，并生成代表策略的规范化哈希。
 *
 * <p>所有时限在构造时一次性换算，运行期不再重新读取；policy 是全部受管配置键
 * （含缺省值）加 rootId 的哈希，用于检测多 JVM 共享同一文件 root 时的策略分歧
 * （对应 FILE_POLICY_CONFLICT）。取值非法、相互矛盾或出现未知的受管前缀键都会
 * 在构造期直接失败，不留到运行期。
 */
public final class FileConfig {

    /**
     * 清理扫描总开关；false 只暂停物理删除，不改变到期语义。
     */
    public final boolean cleanup;

    /**
     * 清理扫描间隔（毫秒）；同时决定策略租约下限 max(120000, 3*scan)。
     */
    public final long scan;

    /**
     * temp 类文件完成后的宽限保留时长（毫秒）。
     */
    public final long grace;

    /**
     * 未完成上传（uploading）的到期上限（毫秒）；超期即不可再写。
     */
    public final long upload;

    /**
     * input 文件就绪后的保留时长（毫秒）。
     */
    public final long input;

    /**
     * artifact 文件就绪后的保留时长（毫秒）。
     */
    public final long artifact;

    /**
     * 下载租约时长（毫秒）；下载 pin 与签名句柄的有效期上限。
     */
    public final long download;

    /**
     * 单次删除 claim 的持有时长（毫秒）。
     */
    public final long claim;

    /**
     * 清理失败重试的初始退避（毫秒）；按 attempts 指数放大。
     */
    public final long retry;

    /**
     * 清理失败重试退避的上限（毫秒）。
     */
    public final long retryMax;

    /**
     * 单文件最大字节数（默认 10 MiB，上限 64 MiB）。
     */
    public final long maxFile;

    /**
     * 整个文件 root 的配额上限（字节，默认 1 GiB）。
     */
    public final long maxBytes;

    /**
     * 清理扫描单批处理的文件条数（1..10000）。
     */
    public final int batch;

    /**
     * 文件 root 身份标识；必须匹配 [A-Za-z0-9_.:-]{1,128}，未配置（空串）视为失败。
     */
    public final String rootId;

    /**
     * 策略哈希：全部受管键的有效值与 rootId 的规范化摘要。
     */
    public final String policy;

    /**
     * 已登记的受管配置键；用于识别未知的 cleanup./retention./file.download. 键。
     */
    private final Set<String> keys = new HashSet<>();

    /**
     * 构造时传入的配置源；之后只读。
     */
    private final Properties p;

    /**
     * 从配置源构造并校验文件配置；所有秒值统一换算为毫秒保存。
     *
     * <p>失败均以 IllegalArgumentException 报告：键值不是 true/false、数字不是
     * 1-16 位十进制或超出 [min,max]、rootId 不符合字符集、retry &gt; retryMax、
     * maxFile &gt; maxBytes 时报 "INVALID_FILE_CONFIG"；出现未知的
     * cleanup./retention./file.download. 前缀键时报 "UNKNOWN_FILE_CONFIG"。
     *
     * @param p 配置源（harness.properties 加载结果）
     * @throws IllegalArgumentException 配置取值非法、相互矛盾或含未知受管键
     */
    public FileConfig(Properties p) {
        this.p = p;
        cleanup = bool("cleanup.enabled", true);
        scan = seconds("cleanup.scan.interval.seconds", 60, 5, 3600);
        batch = (int) number("cleanup.scan.batch.size", 100, 1, 10000);
        grace = seconds("cleanup.temp.grace.seconds", 60, 0, 86400);
        upload = seconds("cleanup.upload.ttl.seconds", 86400, 60, 604800);
        input = seconds("retention.input.ttl.seconds", 604800, 60, 31536000);
        artifact = seconds("retention.artifact.ttl.seconds", 2592000, 60, 31536000);
        download = seconds("file.download.lease.seconds", 300, 1, 3600);
        claim = seconds("cleanup.claim.lease.seconds", 120, 30, 3600);
        retry = seconds("cleanup.retry.initial.seconds", 30, 1, 3600);
        retryMax = seconds("cleanup.retry.max.seconds", 3600, 1, 86400);
        maxFile = number("files.max.file.bytes", 10485760, 1, 67108864);
        maxBytes = number("files.max.bytes", 1073741824, 1, 1099511627776L);
        rootId = p.getProperty("files.root.id", "");
        if (!rootId.matches("[A-Za-z0-9_.:-]{1,128}") || retry > retryMax || maxFile > maxBytes) {
            throw new IllegalArgumentException("INVALID_FILE_CONFIG");
        }
        JSONObject settings = new JSONObject();
        for (String key : keys) {
            settings.put(key, p.getProperty(key, defaultValue(key)));
        }
        policy = JdbcRunStore.hash(settings.put("rootId", rootId));
        // 只对受管前缀做闭合检查：其他键（如 files.root）不干预。
        for (String key : p.stringPropertyNames()) {
            if ((key.startsWith("cleanup.")
                    || key.startsWith("retention.")
                    || key.startsWith("file.download."))
                    && !keys.contains(key)) {
                throw new IllegalArgumentException("UNKNOWN_FILE_CONFIG");
            }
        }
    }

    /**
     * 各受管键的默认值（字符串形式）；仅参与 policy 哈希，不单独暴露。
     */
    private final Map<String, String> defaults = new HashMap<>();

    /**
     * 取某个受管键登记的默认值。
     *
     * @param key 配置键名
     * @return 默认值字符串；未登记的键返回 null
     */
    private String defaultValue(String key) {
        return defaults.get(key);
    }

    /**
     * 读取布尔配置；仅接受 "true"/"false" 两个字面量。
     *
     * @param k 配置键名
     * @param d 键缺失时使用的默认值
     * @return 解析后的布尔值
     * @throws IllegalArgumentException 取值不是 true/false，错误码 "INVALID_FILE_CONFIG"
     */
    private boolean bool(String k, boolean d) {
        keys.add(k);
        defaults.put(k, "" + d);
        String s = p.getProperty(k, "" + d);
        if (!s.equals("true") && !s.equals("false")) {
            throw new IllegalArgumentException("INVALID_FILE_CONFIG");
        }
        return Boolean.parseBoolean(s);
    }

    /**
     * 读取数值配置；必须是 1-16 位十进制数字，且落在 [min,max] 闭区间。
     *
     * @param k   配置键名
     * @param d   键缺失时使用的默认值
     * @param min 允许最小值（含）
     * @param max 允许最大值（含）
     * @return 解析后的数值
     * @throws IllegalArgumentException 不是纯十进制数字或越界，错误码 "INVALID_FILE_CONFIG"
     */
    private long number(String k, long d, long min, long max) {
        keys.add(k);
        defaults.put(k, "" + d);
        String s = p.getProperty(k, "" + d);
        if (!s.matches("[0-9]{1,16}")) {
            throw new IllegalArgumentException("INVALID_FILE_CONFIG");
        }
        long n = Long.parseLong(s);
        if (n < min || n > max) {
            throw new IllegalArgumentException("INVALID_FILE_CONFIG");
        }
        return n;
    }

    /**
     * 读取数值配置并换算为毫秒。
     *
     * @param k   配置键名
     * @param d   键缺失时使用的默认值（秒）
     * @param min 允许最小秒数（含）
     * @param max 允许最大秒数（含）
     * @return 秒数乘以 1000 后的毫秒值
     * @throws IllegalArgumentException 同 {@link #number(String, long, long, long)}
     */
    private long seconds(String k, long d, long min, long max) {
        return Math.multiplyExact(number(k, d, min, max), 1000L);
    }
}
