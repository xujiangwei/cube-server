/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import cell.api.Servable;
import cell.core.talk.*;
import cell.core.talk.dialect.ActionDialect;
import cube.common.Packet;
import cube.common.action.HarnessAction;
import cube.core.AbstractCellet;
import cube.core.Kernel;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 标准 XML 安装的 Harness Cellet：worker 基础/模型/runner 原始报文与 dispatcher 业务报文
 * 共用同一 Cell 长连接，但分别进入不同执行池——worker 请求走服务自有执行池，
 * 业务请求走有界业务执行池，文件字节走同连接的原生 SHM 流。
 * 从不复用 AbstractCellet 的共享执行器。
 */
public final class HarnessCellet extends AbstractCellet {
    /**
     * 已安装的 HarnessService；uninstall 后为 null。
     */
    private HarnessService service;

    /**
     * 以 Harness 服务名（{@link HarnessService#NAME}）构造 Cellet。
     */
    public HarnessCellet() {
        super(HarnessService.NAME);
    }

    /**
     * @return 当前安装的 HarnessService；尚未安装或已卸载时为 null
     */
    public HarnessService getService() {
        return service;
    }

    /**
     * 安装模块：从 Nucleus 取专用 Kernel，创建 HarnessService 并以 Harness 名注册。
     *
     * @return Kernel 存在且安装成功返回 true，否则 false
     */
    @Override
    public boolean install() {
        Kernel kernel = (Kernel) getNucleus().getParameter("kernel");
        if (kernel == null) {
            return false;
        }
        service = new HarnessService();
        kernel.installModule(HarnessService.NAME, service);
        return true;
    }

    /**
     * 卸载模块：从 Kernel 移除 Harness 模块并清空本地引用。
     */
    @Override
    public void uninstall() {
        Kernel kernel = (Kernel) getNucleus().getParameter("kernel");
        if (kernel != null) {
            kernel.uninstallModule(HarnessService.NAME);
        }
        service = null;
    }

    /**
     * @return worker 请求执行池；服务尚未安装时为 null
     */
    @Override
    public ExecutorService getExecutor() {
        return service == null ? null : service.executor();
    }

    /**
     * 提交任务到 worker 执行池；池不可用即拒绝，由调用方转 HARNESS_BUSY。
     *
     * @param task 待执行任务
     * @throws RejectedExecutionException 执行池为 null（服务未安装）或队列已满
     */
    @Override
    protected void execute(Runnable task) {
        ExecutorService pool = getExecutor();
        if (pool == null) {
            throw new RejectedExecutionException();
        }
        pool.execute(task);
    }

    /**
     * 以单 STRING Stuff 回送 worker 协议响应。
     *
     * @param context 请求方 TalkContext
     * @param data    响应 JSON
     */
    private void reply(TalkContext context, JSONObject data) {
        Primitive p = new Primitive();
        p.commit(new Stuff(data.toString()));
        speak(context, p);
    }

    /**
     * worker 侧监听入口：单个 STRING Stuff 视为 worker 原始 JSON 信封
     * （v1 基础协议、协议 2/3 模型或 tool/sandbox runner），
     * 其余形态按 dispatcher 业务报文转 onBusiness。
     *
     * @param context   TalkContext，提供 sessionId 与会话有效性
     * @param primitive 收到的原始原语
     */
    @Override
    public void onListened(TalkContext context, Primitive primitive) {
        super.onListened(context, primitive);
        if (primitive.numStuff() != 1 || primitive.getStuff(0).getLiteralBase() != LiteralBase.STRING) {
            onBusiness(context, primitive);
            return;
        }
        // 入队或解码原始 STRING 载荷前先复核信封：恰好一个 STRING Stuff 且不超过 262144 字节。
        if (primitive.numStuff() != 1 || primitive.getStuff(0).getLiteralBase() != LiteralBase.STRING || primitive.getStuff(0).getValue().length > 262144) {
            HarnessService.logWorkerRejection("INVALID_ENVELOPE", context.getSessionId());
            reply(context, HarnessService.error("", "INVALID_ENVELOPE"));
            return;
        }
        final HarnessService current = service;
        try {
            execute(() -> {
                if (current != null && context.isValid()) {
                    reply(context, current.process(primitive.getStuff(0).getValue(), context.getSessionId(), context::isValid));
                } else {
                    HarnessService.logWorkerRejection(current == null ? "HARNESS_NOT_READY" : "STALE_SESSION", context.getSessionId());
                }
            });
        } catch (RejectedExecutionException e) {
            HarnessService.logWorkerRejection("HARNESS_BUSY", context.getSessionId());
            reply(context, HarnessService.error("", "HARNESS_BUSY"));
        }
    }

    /**
     * 回送 dispatcher 业务响应：保留 sn 关联并复制 _performer 参数。
     *
     * @param context 请求方 TalkContext
     * @param request 业务请求，提供 sn 与 _performer
     * @param result  响应体 JSON
     */
    private void replyBusiness(TalkContext context, ActionDialect request, JSONObject result) {
        ActionDialect response = new Packet(request.getParamAsLong("sn"), request.getName(), result).toDialect();
        if (request.containsParam("_performer")) {
            response.addParam("_performer", request.getParamAsJson("_performer"));
        }
        speak(context, response);
    }

    /**
     * dispatcher 业务报文入口：仅接受带 sn 与 _performer 的关联信封，
     * 超大报文直接 413；正常请求提交业务执行池异步处理，池满返回 503/HARNESS_BUSY。
     *
     * @param context   TalkContext
     * @param primitive 收到的原始原语，携带 ActionDialect
     */
    private void onBusiness(TalkContext context, Primitive primitive) {
        ActionDialect request;
        try {
            request = new ActionDialect(primitive);
        } catch (Exception e) {
            HarnessService.logWorkerRejection("INVALID_ENVELOPE", context.getSessionId());
            reply(context, HarnessService.error("", "INVALID_ENVELOPE"));
            return;
        }
        // 只有带 sn 与 _performer 的关联 dispatcher 信封才允许进入业务执行池。
        if (!request.containsParam("sn") || !request.containsParam("_performer")) {
            HarnessService.logWorkerRejection("INVALID_ENVELOPE", context.getSessionId());
            reply(context, HarnessService.error("", "INVALID_ENVELOPE"));
            return;
        }
        final HarnessService current = service;
        if (primitive.length() > HarnessAction.MAX_BODY_BYTES + 65536) {
            replyBusiness(context, request, HarnessBusiness.error(413, "PAYLOAD_TOO_LARGE"));
            return;
        }
        try {
            if (current == null || current.businessExecutor() == null) {
                throw new RejectedExecutionException();
            }
            current.businessExecutor().execute(() -> {
                if (context.isValid()) {
                    replyBusiness(context, request,
                            current.processBusiness(request, context, name -> speakStream(context, name)));
                }
            });
        } catch (RejectedExecutionException e) {
            replyBusiness(context, request, HarnessBusiness.error(503, "HARNESS_BUSY"));
        }
    }

    /**
     * 文件上传流入口：按流名（transferId）转业务侧接收；服务不可用时直接关闭流。
     *
     * @param context TalkContext
     * @param stream  客户端经 SHM 送达的字节流
     */
    @Override
    public void onListened(TalkContext context, PrimitiveInputStream stream) {
        HarnessService current = service;
        if (current != null) {
            current.receiveFile(context, stream);
        } else {
            try {
                stream.close();
            } catch (java.io.IOException ignored) {
            }
        }
    }

    /**
     * 连接断开回调：清理该 transport 上的传输、注册与执行侧记录。
     *
     * @param context 断开的 TalkContext
     * @param server  断开的 Servable
     */
    @Override
    public void onQuitted(TalkContext context, Servable server) {
        if (service != null) {
            service.disconnect(context.getSessionId());
        }
    }
}
