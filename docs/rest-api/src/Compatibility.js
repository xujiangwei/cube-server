/**
 * @apiDefine Compatibility OpenAI / Claude 兼容
 */

/**
 * @api {post} /v1/chat/completions OpenAI Chat Completions
 * @apiName OpenAIChatCompletions
 * @apiGroup Compatibility
 * @apiVersion 1.0.0
 * @apiDescription dispatcher 业务 REST 接口。使用 Cube 模型 ID 和 Cube Token，无需频道。完整 SDK、图片、工具往返及配置示例参见源码目录 Compatibility.md。服务不注入 SKILL、知识库或历史，不执行客户端函数工具。
 * @apiHeader {String} Authorization Bearer &lt;Cube Token&gt;
 * @apiHeader {String} Content-Type application/json
 * @apiBody {String} model /v1/models 返回的 Cube 模型 ID。
 * @apiBody {Object[]} messages 完整对话，支持 system/developer/user/assistant/tool 角色及图片内容块。
 * @apiBody {Boolean} [stream=false] 真实 SSE；成功结束发送 [DONE]。
 * @apiBody {Number} [max_tokens] 最大输出 Token 数。
 * @apiBody {Number} [max_completion_tokens] 最大生成 Token 数，不能与不同值的 max_tokens 同时使用。
 * @apiBody {Number} [temperature] 温度。
 * @apiBody {Number} [top_p] 采样概率。
 * @apiBody {String[]} [stop] 至多四个停止序列。
 * @apiBody {Object[]} [tools] 仅客户端 function 工具。
 * @apiBody {String/Object} [tool_choice] auto/none/required 或指定函数。
 * @apiBody {Boolean} [parallel_tool_calls] 是否允许并行工具调用。
 * @apiBody {Object} [stream_options] include_usage=true 时增加真实 usage chunk。
 * @apiSuccess {String} id Completion ID。
 * @apiSuccess {Object[]} choices 单个 assistant message、tool_calls 和 finish_reason。
 * @apiSuccess {Object} usage 真实 prompt_tokens/completion_tokens/total_tokens。
 * @apiError (错误) {Object} error 对应协议错误；400 参数，401 鉴权，404 模型，413 大小，429 繁忙，502 后端，503 不可用，504 超时。
 */

/**
 * @api {post} /v1/messages Claude Messages
 * @apiName ClaudeMessages
 * @apiGroup Compatibility
 * @apiVersion 1.0.0
 * @apiDescription 使用 Cube 模型 ID，支持 text/image/tool_use/tool_result，工具由客户端执行。SSE 提供完整 message/content-block 生命周期及 input_json_delta。Baize2 支持图片与工具；Baize 仅支持文本。详细 SDK 和 curl 示例参见 Compatibility.md。
 * @apiHeader {String} x-api-key Cube Token。
 * @apiHeader {String} anthropic-version 2023-06-01。
 * @apiHeader {String} Content-Type application/json。
 * @apiBody {String} model Cube 模型 ID。
 * @apiBody {Number} max_tokens 最大输出 Token 数。
 * @apiBody {Object[]} messages user/assistant 对话与内容块。
 * @apiBody {String/Object[]} [system] 系统文本。
 * @apiBody {Boolean} [stream=false] 真实 SSE。
 * @apiBody {Object[]} [tools] name、description、input_schema 定义的客户端函数工具。
 * @apiBody {Object} [tool_choice] auto/any/tool/none 和 disable_parallel_tool_use。
 * @apiSuccess {String} id Message ID。
 * @apiSuccess {Object[]} content text 或 tool_use 内容块。
 * @apiSuccess {String} stop_reason end_turn/max_tokens/tool_use/stop_sequence/refusal。
 * @apiSuccess {Object} usage 真实 input_tokens/output_tokens。
 */

/**
 * @api {get} /v1/models 兼容模型列表
 * @apiName CompatibilityModels
 * @apiGroup Compatibility
 * @apiVersion 1.0.0
 * @apiDescription 按账号模型配置和注册 Unit 能力过滤。带 anthropic-version 时使用 Claude 认证与列表格式，否则使用 OpenAI 格式。仅使用现有 Cube 模型 ID，不设置模型别名或静默回退。
 * @apiHeader {String} [Authorization] OpenAI：Bearer &lt;Cube Token&gt;。
 * @apiHeader {String} [x-api-key] Claude：Cube Token。
 * @apiHeader {String} [anthropic-version] Claude：2023-06-01。
 * @apiSuccess {Object[]} data 可用模型。
 */

/**
 * @api {get} /v1/models/:model 兼容模型详情
 * @apiName CompatibilityModel
 * @apiGroup Compatibility
 * @apiVersion 1.0.0
 * @apiDescription 鉴权与格式选择同 /v1/models；未知或账号不可用的模型返回标准 404 错误。
 * @apiParam {String} model Cube 模型 ID。
 * @apiSuccess {String} id 模型 ID。
 */
