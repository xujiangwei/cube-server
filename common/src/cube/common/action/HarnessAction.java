/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.common.action;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Harness 调度器/服务业务协议定义。
 *
 * <p>枚举每个动作的 HTTP 方法、URL 路径模板与服务端 schema 。内部控制动作
 * （StartDownload / TransferResult / AbortTransfer）不对应 REST 端点，
 * 仅供网关与服务单元之间内部流转。</p>
 *
 * <p>本协议独立于 Python worker 协议。</p>
 */
public enum HarnessAction {
    /** 查询服务能力。 */
    Capabilities("GET", "capabilities", null),
    /** 查询服务器模型、执行器和当前用户获授权的技能/工具。 */
    TaskProfiles("GET", "task-profiles", null),
    /** 创建运行任务。 */
    CreateRun("POST", "runs", "create-run"),
    /** 查询运行任务详情。 */
    ReadRun("GET", "runs/ID", null),
    /** 订阅运行任务的事件流（SSE）。 */
    Events("GET", "runs/ID/events", null),
    /** 取消运行任务。 */
    CancelRun("POST", "runs/ID/cancel", "cancel-run"),
    /** 恢复运行任务。 */
    ResumeRun("POST", "runs/ID/resume", "resume-run"),
    /** 提交运行任务的反馈。 */
    Feedback("POST", "runs/ID/feedback", "feedback"),
    /** 对指定的人工审批给出决策。 */
    DecideApproval("POST", "runs/ID/approvals/ID/decision", "approval"),
    /** 创建输入。 */
    CreateInput("POST", "inputs", "input-create"),
    /** 查询输入详情。 */
    ReadInput("GET", "inputs/ID", null),
    /** 上传输入内容。 */
    UploadInput("PUT", "inputs/ID/content", null),
    /** 确认输入内容上传完成。 */
    CompleteInput("POST", "inputs/ID/complete", "input-complete"),
    /** 查询运行任务的产物列表。 */
    Artifacts("GET", "runs/ID/artifacts", null),
    /** 查询产物元数据。 */
    ReadArtifact("GET", "artifacts/ID", null),
    /** 下载产物内容。 */
    DownloadArtifact("GET", "artifacts/ID/content", null),
    /** 获取产物的下载句柄。 */
    DownloadHandle("GET", "artifacts/ID/download-handle", null),
    /** 查询运行任务的执行轨迹。 */
    Trace("GET", "runs/ID/trace", null),
    /** 内部控制动作：通知服务单元开始下发文件数据。 */
    StartDownload(null, null, null),
    /** 内部控制动作：查询文件传输结果。 */
    TransferResult(null, null, null),
    /** 内部控制动作：中止文件传输并释放远端租约。 */
    AbortTransfer(null, null, null);

    /** Harness Cellet 名称，网关与服务单元之间通信的目标 Cellet 。 */
    public static final String CELLET = "Harness";

    /** 请求体大小上限：256 KiB 。 */
    public static final int MAX_BODY_BYTES = 262144;

    /** 响应数据大小上限：8 MiB 。 */
    public static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    /** 动作的全限定名称，前缀为 "harness." 。 */
    public final String name;

    /** 动作对应的 HTTP 方法；内部控制动作不经过 HTTP ，取值为 null 。 */
    public final String method;

    /** 动作绑定的服务端 schema 名称；无 schema 的动作取值为 null 。 */
    public final String schema;

    /** 动作 URL 路径模板的编译结果，用于匹配请求路径并提取路径参数。 */
    private final Pattern path;

    /**
     * 构造函数。
     *
     * @param method 动作对应的 HTTP 方法，内部控制动作传 null 。
     * @param path   动作的 URL 路径模板，内部控制动作传 null 。
     * @param schema 动作绑定的服务端 schema 名称，无 schema 时传 null 。
     */
    HarnessAction(String method, String path, String schema) {
        this.name = "harness." + name();
        this.method = method;
        this.schema = schema;
        // 路径模板中的 ID 占位符替换为捕获组，用于提取路径参数
        this.path = path == null ? null : Pattern.compile("/harness/v1/" + path.replace("ID", "([A-Za-z0-9_.:-]+)"));
    }

    /**
     * 用指定的请求路径匹配动作。
     *
     * @param path 请求的路径。
     * @return 返回匹配器；路径不匹配或该动作无 URL 模板时返回 null 。
     */
    public Matcher match(String path) {
        return this.path == null ? null : this.path.matcher(path);
    }

    /**
     * 按全限定名称查找动作。
     *
     * @param name 动作的全限定名称。
     * @return 返回对应的动作；名称不存在时返回 null 。
     */
    public static HarnessAction fromName(String name) {
        for (HarnessAction action : values()) {
            if (action.name.equals(name)) {
                return action;
            }
        }
        return null;
    }
}
