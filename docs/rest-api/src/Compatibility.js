/**
 * @apiDefine Compatibility OpenAI / Claude 兼容
 */

/**
 * @api {post} /v1/chat/completions OpenAI Chat Completions
 * @apiName OpenAIChatCompletions
 * @apiGroup Compatibility
 * @apiVersion 1.0.0
 * @apiDescription 使用 OpenAI Chat Completions 请求格式进行文本生成、多轮对话、图片理解和客户端函数工具调用。业务基地址为 <code>http://211.157.179.37:7010</code>，使用 Cube 用户令牌认证，无需创建频道。
 *                 <p>接入时先调用<a href="#api-Compatibility-CompatibilityModels">兼容模型列表</a>获取可用模型，将返回的 <code>id</code> 填入 <code>model</code>。图片和工具调用需选用支持对应能力的模型；示例中的 <code>Baize2</code> 应替换为当前账号实际可用的模型 ID。</p>
 *                 <p>每次请求通过 <code>messages</code> 提交本轮所需的完整上下文。多轮对话时按顺序带上之前的用户消息和助手回答；需要参考资料时，将资料作为消息内容一并提供。回复文本读取 <code>choices[0].message.content</code>。</p>
 *                 <p>设置 <code>stream=true</code> 可逐段接收 SSE 文本，按顺序拼接 <code>choices[].delta.content</code>，收到 <code>[DONE]</code> 表示正常结束；设置 <code>stream_options.include_usage=true</code> 可在结束前读取用量。中途收到错误或连接异常结束时，应将本次回答标记为未完成。</p>
 *                 <p>使用 <code>tools</code> 声明应用可调用的函数。收到 <code>finish_reason=tool_calls</code> 后，由应用校验参数并执行相应函数，再将助手的工具调用消息和 <code>role=tool</code> 的执行结果加入对话，继续请求最终回答。流式工具参数可能分片返回，应按调用序号拼接后再解析 JSON。</p>
 * @apiHeader {String} Authorization Bearer &lt;Cube Token&gt;
 * @apiHeader {String} Content-Type application/json
 * @apiBody {String} model /v1/models 返回的 Cube 模型 ID。
 * @apiBody {Object[]} messages 完整对话，支持 system/developer/user/assistant/tool 角色及图片内容块。
 * @apiBody {String} messages.role 消息角色：system、developer、user、assistant 或 tool。
 * @apiBody {String/Object[]/Null} messages.content 文本字符串或内容块数组；assistant 仅携带 tool_calls 时可为 null。
 * @apiBody {String} messages.content.type 数组项类型：text 或 image_url。
 * @apiBody {String} [messages.content.text] type=text 时的文本。
 * @apiBody {Object} [messages.content.image_url] type=image_url 时的图片来源。
 * @apiBody {String} messages.content.image_url.url 可访问的 HTTP(S) 图片 URL 或 data:image/...;base64,...。
 * @apiBody {String} [messages.content.image_url.detail] 图片细节级别 auto、low 或 high。
 * @apiBody {String} [messages.name] 消息发送者名称。
 * @apiBody {String} [messages.tool_call_id] role=tool 时必须引用先前 assistant 工具调用的 ID。
 * @apiBody {Object[]} [messages.tool_calls] assistant 发起的客户端工具调用。
 * @apiBody {String} messages.tool_calls.id 工具调用 ID；提交结果时使用 tool_call_id 引用。
 * @apiBody {String} messages.tool_calls.type 固定为 function。
 * @apiBody {Object} messages.tool_calls.function 客户端函数调用。
 * @apiBody {String} messages.tool_calls.function.name 函数名。
 * @apiBody {String} messages.tool_calls.function.arguments JSON 编码的参数字符串；先解析 JSON 再按函数 Schema 校验。
 * @apiBody {Boolean} [stream=false] SSE 流式输出；成功结束发送 [DONE]。
 * @apiBody {Number} [max_tokens] 最大输出 Token 数。
 * @apiBody {Number} [max_completion_tokens] 最大生成 Token 数，不能与不同值的 max_tokens 同时使用。
 * @apiBody {Number} [temperature] 控制回答的随机性，较低值适合摘要、信息整理，较高值适合创意内容；取值须在模型支持范围内。
 * @apiBody {Number} [top_p] 累计采样概率阈值，取值范围为 [0,1]；通常与 temperature 选择一项调整。
 * @apiBody {String[]} [stop] 至多四个停止序列。
 * @apiBody {Object[]} [tools] 仅客户端 function 工具。
 * @apiBody {String} tools.type 固定为 function。
 * @apiBody {Object} tools.function 函数定义。
 * @apiBody {Boolean} [tools.function.strict] 当前仅接受 false；true 返回参数错误。
 * @apiBody {String} tools.function.name 函数名。
 * @apiBody {String} [tools.function.description] 函数用途说明。
 * @apiBody {Object} [tools.function.parameters] 函数参数的 JSON Schema；省略时按无参数函数处理。
 * @apiBody {String} [tools.function.parameters.type] 根参数类型；声明对象参数时为 object。
 * @apiBody {Object} [tools.function.parameters.properties] JSON Schema 属性映射；键为参数名，值为该参数的子 Schema，例如 {"city":{"type":"string"}}。
 * @apiBody {String[]} [tools.function.parameters.required] 必填参数名列表。
 * @apiBody {Boolean/Object} [tools.function.parameters.additionalProperties] 是否允许未声明参数，或这些参数适用的 JSON Schema。
 * @apiBody {String/Object} [tool_choice] auto/none/required 或指定函数。
 * @apiBody {String} tool_choice.type 对象形式固定为 function。字符串形式可用 auto、none、required。
 * @apiBody {Object} tool_choice.function 指定必须调用的函数。
 * @apiBody {String} tool_choice.function.name 已在 tools 声明的函数名。
 * @apiBody {Boolean} [parallel_tool_calls] 是否允许并行工具调用。
 * @apiBody {Object} [stream_options] include_usage=true 时增加用量统计消息。
 * @apiBody {Boolean} [stream_options.include_usage] 为 true 时在 SSE 结束前增加用量统计消息。
 * @apiSuccess {String} id 本次生成的响应 ID。
 * @apiSuccess {String} object 非流式为 chat.completion，流式消息为 chat.completion.chunk。
 * @apiSuccess {Number} created 响应创建时间，Unix 秒时间戳。
 * @apiSuccess {String} model 本次使用的模型 ID。
 * @apiSuccess {Object[]} choices 单个 assistant message、tool_calls 和 finish_reason。
 * @apiSuccess {Number} choices.index 当前仅返回一个候选，值为 0。
 * @apiSuccess {Object} [choices.message] 非流式响应的 assistant 消息。
 * @apiSuccess {String} choices.message.role 固定为 assistant。
 * @apiSuccess {String/Null} choices.message.content 回复文本；只有工具调用时可能为 null。
 * @apiSuccess {Object[]} [choices.message.tool_calls] 需要客户端执行的函数调用。
 * @apiSuccess {String} choices.message.tool_calls.id 工具调用 ID；提交结果时使用 tool_call_id 引用。
 * @apiSuccess {String} choices.message.tool_calls.type 固定为 function。
 * @apiSuccess {Object} choices.message.tool_calls.function 客户端函数调用。
 * @apiSuccess {String} choices.message.tool_calls.function.name 函数名。
 * @apiSuccess {String} choices.message.tool_calls.function.arguments JSON 编码的参数字符串；先解析 JSON 再按函数 Schema 校验。
 * @apiSuccess {Object} [choices.delta] 流式 chunk 的增量消息；替代完整 message。
 * @apiSuccess {String} [choices.delta.role] 首个 chunk 的消息角色。
 * @apiSuccess {String} [choices.delta.content] 增量文本。
 * @apiSuccess {Object[]} [choices.delta.tool_calls] 分片输出的工具调用。
 * @apiSuccess {Number} choices.delta.tool_calls.index 此次分片对应的工具调用序号。
 * @apiSuccess {String} [choices.delta.tool_calls.id] 工具调用 ID；通常仅首片返回。
 * @apiSuccess {String} [choices.delta.tool_calls.type] function；通常仅首片返回。
 * @apiSuccess {Object} choices.delta.tool_calls.function 函数名或参数的增量。
 * @apiSuccess {String} [choices.delta.tool_calls.function.name] 函数名。
 * @apiSuccess {String} [choices.delta.tool_calls.function.arguments] 参数 JSON 的字符串分片；需按 index 顺序拼接。
 * @apiSuccess {String/Null} choices.finish_reason 非流式或最终 chunk 的结束原因：stop、length、tool_calls、content_filter；未结束时为 null。
 * @apiSuccess {Null} choices.logprobs 当前固定为 null。
 * @apiSuccess {Object} usage 本次请求的 prompt_tokens/completion_tokens/total_tokens。
 * @apiSuccess {Number} usage.prompt_tokens 实际输入词元数。
 * @apiSuccess {Number} usage.completion_tokens 实际生成词元数。
 * @apiSuccess {Number} usage.total_tokens 实际输入与输出词元总数。
 * @apiError (错误) {Object} error 对应协议错误；400 参数，401 鉴权，404 模型，413 大小，429 繁忙，502 后端，503 不可用，504 超时。
 * @apiError (错误) {String} error.type 客户端错误为 invalid_request_error，服务端错误为 server_error。
 * @apiError (错误) {String} error.code 稳定的错误码，例如 invalid_request_error、model_not_found。
 * @apiError (错误) {String} error.message 错误说明。
 * @apiError (错误) {String/Null} error.param 出错参数名；不对应单一参数时为 null。
 *
 * @apiExample {http} 文本摘要：完整 HTTP 请求
 *     POST http://211.157.179.37:7010/v1/chat/completions
 *     Authorization: Bearer <Cube Token>
 *     Content-Type: application/json
 *
 *     {
 *         "model": "Baize2",
 *         "messages": [
 *             {
 *                 "role": "system",
 *                 "content": "你是巡检助手，请保留异常项并简洁回答。"
 *             },
 *             {
 *                 "role": "user",
 *                 "content": "请总结：东区运行正常，西区温度升高，已安排复检。"
 *             }
 *         ],
 *         "max_tokens": 256
 *     }
 *
 * @apiParamExample {json} 多轮对话：将上次回答加入 messages
 *     {
 *         "model": "Baize2",
 *         "messages": [
 *             {
 *                 "role": "user",
 *                 "content": "巡检记录：西区设备温度升高，已安排复检。请给出摘要。"
 *             },
 *             {
 *                 "role": "assistant",
 *                 "content": "西区设备出现温度异常，已安排复检。"
 *             },
 *             {
 *                 "role": "user",
 *                 "content": "请进一步整理成维护人员的待办事项。"
 *             }
 *         ],
 *         "max_tokens": 256
 *     }
 *
 * @apiParamExample {json} 图片理解：文字问题和图片放在同一用户消息中
 *     {
 *         "model": "Baize2",
 *         "messages": [
 *             {
 *                 "role": "user",
 *                 "content": [
 *                     {
 *                         "type": "text",
 *                         "text": "请描述巡检图片中可见的异常，并说明所在位置。"
 *                     },
 *                     {
 *                         "type": "image_url",
 *                         "image_url": {
 *                             "url": "https://images.example.com/inspection.jpg",
 *                             "detail": "auto"
 *                         }
 *                     }
 *                 ]
 *             }
 *         ],
 *         "max_tokens": 512
 *     }
 *
 * @apiParamExample {json} 流式回答：按增量显示文本，并在结束前读取用量
 *     {
 *         "model": "Baize2",
 *         "messages": [
 *             {
 *                 "role": "user",
 *                 "content": "请列出三项日常设备巡检注意事项。"
 *             }
 *         ],
 *         "max_tokens": 256,
 *         "stream": true,
 *         "stream_options": {
 *             "include_usage": true
 *         }
 *     }
 *
 * @apiParamExample {json} 函数工具：让模型查询西区巡检状态
 *     {
 *         "model": "Baize2",
 *         "messages": [
 *             {
 *                 "role": "user",
 *                 "content": "西区巡检有什么异常？"
 *             }
 *         ],
 *         "tools": [
 *             {
 *                 "type": "function",
 *                 "function": {
 *                     "name": "get_inspection_status",
 *                     "description": "查询指定区域的巡检状态",
 *                     "parameters": {
 *                         "type": "object",
 *                         "properties": {
 *                             "area": {
 *                                 "type": "string",
 *                                 "description": "区域名称"
 *                             }
 *                         },
 *                         "required": [
 *                             "area"
 *                         ],
 *                         "additionalProperties": false
 *                     }
 *                 }
 *             }
 *         ],
 *         "tool_choice": "auto",
 *         "max_tokens": 256
 *     }
 *
 * @apiParamExample {json} 工具往返：应用执行函数后提交结果，生成最终回答
 *     {
 *         "model": "Baize2",
 *         "messages": [
 *             {
 *                 "role": "user",
 *                 "content": "西区巡检有什么异常？"
 *             },
 *             {
 *                 "role": "assistant",
 *                 "content": null,
 *                 "tool_calls": [
 *                     {
 *                         "id": "call_001",
 *                         "type": "function",
 *                         "function": {
 *                             "name": "get_inspection_status",
 *                             "arguments": "{\"area\":\"西区\"}"
 *                         }
 *                     }
 *                 ]
 *             },
 *             {
 *                 "role": "tool",
 *                 "tool_call_id": "call_001",
 *                 "content": "{\"area\":\"西区\",\"status\":\"需复检\",\"issue\":\"设备温度升高\"}"
 *             }
 *         ],
 *         "tools": [
 *             {
 *                 "type": "function",
 *                 "function": {
 *                     "name": "get_inspection_status",
 *                     "description": "查询指定区域的巡检状态",
 *                     "parameters": {
 *                         "type": "object",
 *                         "properties": {
 *                             "area": {
 *                                 "type": "string",
 *                                 "description": "区域名称"
 *                             }
 *                         },
 *                         "required": [
 *                             "area"
 *                         ],
 *                         "additionalProperties": false
 *                     }
 *                 }
 *             }
 *         ],
 *         "tool_choice": "none",
 *         "max_tokens": 256
 *     }
 *
 * @apiSuccessExample {json} 文本回答：从 choices[0].message.content 读取
 *     HTTP/1.1 200 OK
 *     {
 *         "id": "chatcmpl-example001",
 *         "object": "chat.completion",
 *         "created": 1793322000,
 *         "model": "Baize2",
 *         "choices": [
 *             {
 *                 "index": 0,
 *                 "message": {
 *                     "role": "assistant",
 *                     "content": "东区运行正常；西区设备温度升高，已安排复检。"
 *                 },
 *                 "finish_reason": "stop",
 *                 "logprobs": null
 *             }
 *         ],
 *         "usage": {
 *             "prompt_tokens": 42,
 *             "completion_tokens": 25,
 *             "total_tokens": 67
 *         }
 *     }
 *
 * @apiSuccessExample {json} 需要调用函数：应用执行后再提交工具结果
 *     HTTP/1.1 200 OK
 *     {
 *         "id": "chatcmpl-example001",
 *         "object": "chat.completion",
 *         "created": 1793322000,
 *         "model": "Baize2",
 *         "choices": [
 *             {
 *                 "index": 0,
 *                 "message": {
 *                     "role": "assistant",
 *                     "content": null,
 *                     "tool_calls": [
 *                         {
 *                             "id": "call_001",
 *                             "type": "function",
 *                             "function": {
 *                                 "name": "get_inspection_status",
 *                                 "arguments": "{\"area\":\"西区\"}"
 *                             }
 *                         }
 *                     ]
 *                 },
 *                 "finish_reason": "tool_calls",
 *                 "logprobs": null
 *             }
 *         ],
 *         "usage": {
 *             "prompt_tokens": 42,
 *             "completion_tokens": 25,
 *             "total_tokens": 67
 *         }
 *     }
 *
 * @apiSuccessExample {text} SSE 文本流：最后一条用量消息的 choices 为空
 *     HTTP/1.1 200 OK
 *     Content-Type: text/event-stream
 *
 *     data: {"id":"chatcmpl-example001","object":"chat.completion.chunk","created":1793322000,"model":"Baize2","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null,"logprobs":null}],"usage":null}
 *
 *     data: {"id":"chatcmpl-example001","object":"chat.completion.chunk","created":1793322000,"model":"Baize2","choices":[{"index":0,"delta":{"content":"一、记录设备温度；二、检查异常噪声；三、核对告警。"},"finish_reason":null,"logprobs":null}],"usage":null}
 *
 *     data: {"id":"chatcmpl-example001","object":"chat.completion.chunk","created":1793322000,"model":"Baize2","choices":[{"index":0,"delta":{},"finish_reason":"stop","logprobs":null}]}
 *
 *     data: {"id":"chatcmpl-example001","object":"chat.completion.chunk","created":1793322000,"model":"Baize2","choices":[],"usage":{"prompt_tokens":18,"completion_tokens":26,"total_tokens":44}}
 *
 *     data: [DONE]
 *
 * @apiErrorExample {json} 模型不可用：刷新模型列表后重新选择
 *     HTTP/1.1 404 Not Found
 *     {
 *         "error": {
 *             "type": "invalid_request_error",
 *             "code": "model_not_found",
 *             "message": "Unknown or unavailable model",
 *             "param": "model"
 *         }
 *     }
 */

/**
 * @api {post} /v1/messages Claude Messages
 * @apiName ClaudeMessages
 * @apiGroup Compatibility
 * @apiVersion 1.0.0
 * @apiDescription 使用 Claude Messages 请求格式进行文本对话、图片理解和客户端函数工具调用。使用 Cube 用户令牌作为 <code>x-api-key</code>，并携带 <code>anthropic-version: 2023-06-01</code>。
 *                 <p>从模型列表选择当前账号可用的模型 ID。<code>max_tokens</code> 为必填项，用于限制本次最大输出长度。系统指令放入顶层 <code>system</code>，用户与助手的完整上下文放入 <code>messages</code>；图片放在用户消息的 <code>image</code> 内容块中。</p>
 *                 <p>非流式结果读取 <code>content</code> 数组：<code>text</code> 是回复文本，<code>tool_use</code> 是需要应用执行的函数调用。执行后，将原助手消息及引用同一调用 ID 的 <code>tool_result</code> 用户消息加入上下文，再次请求最终回答。</p>
 *                 <p>设置 <code>stream=true</code> 后按 SSE 事件读取：<code>message_start</code> 开始消息，<code>content_block_delta</code> 提供文本或工具参数增量，<code>message_delta</code> 提供结束原因及用量，<code>message_stop</code> 表示正常结束。工具参数的 <code>input_json_delta</code> 需拼接为完整 JSON 后再使用。</p>
 *                 <p>下方示例中的模型 ID、图片地址和函数结果均为演示值，使用时替换为实际内容。支持的输入能力和生成参数以所选模型为准。</p>
 * @apiHeader {String} x-api-key Cube Token。
 * @apiHeader {String} anthropic-version 2023-06-01。
 * @apiHeader {String} Content-Type application/json。
 * @apiBody {String} model Cube 模型 ID。
 * @apiBody {Number} max_tokens 最大输出 Token 数。
 * @apiBody {Object[]} messages user/assistant 对话与内容块。
 * @apiBody {String} messages.role user 或 assistant。
 * @apiBody {String/Object[]} messages.content 文本字符串或按类型组织的内容块数组。
 * @apiBody {String} messages.content.type 内容块类型：text、image、tool_use 或 tool_result；各字段按类型选择。
 * @apiBody {String} [messages.content.text] type=text 时的文本。
 * @apiBody {Object} [messages.content.source] type=image 时的图片来源，仅 user 消息支持。
 * @apiBody {String} messages.content.source.type url 或 base64。
 * @apiBody {String} [messages.content.source.url] type=url 时的 HTTP(S) 图片地址。
 * @apiBody {String} [messages.content.source.media_type] type=base64 时的 MIME：image/png、image/jpeg、image/gif 或 image/webp。
 * @apiBody {String} [messages.content.source.data] type=base64 时的 Base64 图片数据。
 * @apiBody {String} [messages.content.id] type=tool_use 时的调用 ID。
 * @apiBody {String} [messages.content.name] type=tool_use 时的客户端函数名。
 * @apiBody {Object} [messages.content.input] type=tool_use 时的函数参数对象；字段名和类型由 tools.input_schema 定义，例如 {"city":"北京"}。
 * @apiBody {String} [messages.content.tool_use_id] type=tool_result 时引用的调用 ID。
 * @apiBody {String/Object[]} [messages.content.content] type=tool_result 时的文本或 text 内容块数组。
 * @apiBody {String} messages.content.content.type 工具结果数组项固定为 text。
 * @apiBody {String} messages.content.content.text 工具结果文本。
 * @apiBody {Boolean} [messages.content.is_error] type=tool_result 时是否为工具执行错误。
 * @apiBody {String/Object[]} [system] 系统文本。
 * @apiBody {String} system.type 数组形式的内容块类型，固定为 text。
 * @apiBody {String} system.text 系统指令文本。
 * @apiBody {Number} [temperature] 控制回答随机性，取值范围为 [0,1]；具体支持情况以模型为准。
 * @apiBody {Number} [top_p] 累计采样概率阈值，取值范围为 [0,1]。
 * @apiBody {Number} [top_k] 每步采样候选数量，须为非负整数，具体支持情况以模型为准。
 * @apiBody {String[]} [stop_sequences] 最多四个停止序列；生成遇到指定序列时结束。
 * @apiBody {Boolean} [stream=false] SSE 流式输出。
 * @apiBody {Object[]} [tools] name、description、input_schema 定义的客户端函数工具。
 * @apiBody {String} tools.name 客户端函数名。
 * @apiBody {String} [tools.description] 函数用途说明。
 * @apiBody {Object} tools.input_schema 函数参数的 JSON Schema。
 * @apiBody {String} [tools.input_schema.type] 根参数类型；声明对象参数时为 object。
 * @apiBody {Object} [tools.input_schema.properties] JSON Schema 属性映射；键为参数名，值为该参数的子 Schema，例如 {"city":{"type":"string"}}。
 * @apiBody {String[]} [tools.input_schema.required] 必填参数名列表。
 * @apiBody {Boolean/Object} [tools.input_schema.additionalProperties] 是否允许未声明参数，或这些参数适用的 JSON Schema。
 * @apiBody {Object} [tool_choice] auto/any/tool/none 和 disable_parallel_tool_use。
 * @apiBody {String} tool_choice.type auto、any、tool 或 none。
 * @apiBody {String} [tool_choice.name] type=tool 时必填，指定 tools 中的函数名。
 * @apiBody {Boolean} [tool_choice.disable_parallel_tool_use] 是否禁用并行工具调用。
 * @apiError {String} type 错误响应顶层类型，固定为 error。
 * @apiError {Object} error Claude 错误对象。
 * @apiError {String} error.type 错误类别，如 invalid_request_error、authentication_error、not_found_error、overloaded_error 或 api_error。
 * @apiError {String} error.message 错误说明。
 * @apiSuccess {String} id 本次生成的消息 ID。
 * @apiSuccess {String} type 非流式响应固定为 message。
 * @apiSuccess {String} role 固定为 assistant。
 * @apiSuccess {String} model 本次使用的模型 ID。
 * @apiSuccess {String/Null} stop_sequence 命中的停止序列；未命中时为 null。
 * @apiSuccess {Object[]} content text 或 tool_use 内容块。
 * @apiSuccess {String} content.type text 或 tool_use。
 * @apiSuccess {String} [content.text] type=text 时返回的文本。
 * @apiSuccess {String} [content.id] type=tool_use 时的调用 ID。
 * @apiSuccess {String} [content.name] type=tool_use 时的函数名。
 * @apiSuccess {Object} [content.input] type=tool_use 时的参数对象；子字段由请求中的 tools.input_schema 定义。
 * @apiSuccess {String} stop_reason end_turn/max_tokens/tool_use/stop_sequence/refusal。
 * @apiSuccess {Object} usage 本次请求的 input_tokens/output_tokens。
 * @apiSuccess {Number} usage.input_tokens 实际输入词元数。
 * @apiSuccess {Number} usage.output_tokens 实际生成词元数。
 *
 * @apiExample {http} 文本对话：完整 HTTP 请求
 *     POST http://211.157.179.37:7010/v1/messages
 *     x-api-key: <Cube Token>
 *     anthropic-version: 2023-06-01
 *     Content-Type: application/json
 *
 *     {
 *         "model": "Baize2",
 *         "max_tokens": 256,
 *         "system": "你是巡检助手，请简洁回答。",
 *         "messages": [
 *             {
 *                 "role": "user",
 *                 "content": "请解释如何记录设备温度异常。"
 *             }
 *         ]
 *     }
 *
 * @apiParamExample {json} 多轮对话：继续追问巡检记录格式
 *     {
 *         "model": "Baize2",
 *         "max_tokens": 256,
 *         "messages": [
 *             {
 *                 "role": "user",
 *                 "content": "温度异常应记录哪些内容？"
 *             },
 *             {
 *                 "role": "assistant",
 *                 "content": "记录设备编号、时间、温度和处置情况。"
 *             },
 *             {
 *                 "role": "user",
 *                 "content": "请给我一个简洁的记录模板。"
 *             }
 *         ]
 *     }
 *
 * @apiParamExample {json} 图片理解：使用 URL 图片内容块
 *     {
 *         "model": "Baize2",
 *         "max_tokens": 512,
 *         "messages": [
 *             {
 *                 "role": "user",
 *                 "content": [
 *                     {
 *                         "type": "text",
 *                         "text": "请描述这张巡检图片中的可见情况。"
 *                     },
 *                     {
 *                         "type": "image",
 *                         "source": {
 *                             "type": "url",
 *                             "url": "https://images.example.com/inspection.jpg"
 *                         }
 *                     }
 *                 ]
 *             }
 *         ]
 *     }
 *
 * @apiParamExample {json} 流式回答：按 SSE 事件展示内容
 *     {
 *         "model": "Baize2",
 *         "max_tokens": 256,
 *         "stream": true,
 *         "messages": [
 *             {
 *                 "role": "user",
 *                 "content": "请给出一句巡检提醒。"
 *             }
 *         ]
 *     }
 *
 * @apiParamExample {json} 函数工具：请求查询区域状态
 *     {
 *         "model": "Baize2",
 *         "max_tokens": 256,
 *         "messages": [
 *             {
 *                 "role": "user",
 *                 "content": "西区巡检有什么异常？"
 *             }
 *         ],
 *         "tools": [
 *             {
 *                 "name": "get_inspection_status",
 *                 "description": "查询指定区域的巡检状态",
 *                 "input_schema": {
 *                     "type": "object",
 *                     "properties": {
 *                         "area": {
 *                             "type": "string",
 *                             "description": "区域名称"
 *                         }
 *                     },
 *                     "required": [
 *                         "area"
 *                     ],
 *                     "additionalProperties": false
 *                 }
 *             }
 *         ],
 *         "tool_choice": {
 *             "type": "auto"
 *         }
 *     }
 *
 * @apiParamExample {json} 工具往返：tool_result 引用原 tool_use 的 ID
 *     {
 *         "model": "Baize2",
 *         "max_tokens": 256,
 *         "messages": [
 *             {
 *                 "role": "user",
 *                 "content": "西区巡检有什么异常？"
 *             },
 *             {
 *                 "role": "assistant",
 *                 "content": [
 *                     {
 *                         "type": "tool_use",
 *                         "id": "call_001",
 *                         "name": "get_inspection_status",
 *                         "input": {
 *                             "area": "西区"
 *                         }
 *                     }
 *                 ]
 *             },
 *             {
 *                 "role": "user",
 *                 "content": [
 *                     {
 *                         "type": "tool_result",
 *                         "tool_use_id": "call_001",
 *                         "content": "{\"area\":\"西区\",\"status\":\"需复检\",\"issue\":\"设备温度升高\"}"
 *                     }
 *                 ]
 *             }
 *         ],
 *         "tools": [
 *             {
 *                 "name": "get_inspection_status",
 *                 "description": "查询指定区域的巡检状态",
 *                 "input_schema": {
 *                     "type": "object",
 *                     "properties": {
 *                         "area": {
 *                             "type": "string",
 *                             "description": "区域名称"
 *                         }
 *                     },
 *                     "required": [
 *                         "area"
 *                     ],
 *                     "additionalProperties": false
 *                 }
 *             }
 *         ],
 *         "tool_choice": {
 *             "type": "none"
 *         }
 *     }
 *
 * @apiSuccessExample {json} 文本回答：遍历 content 中的 text 内容块
 *     HTTP/1.1 200 OK
 *     {
 *         "id": "msg_example001",
 *         "type": "message",
 *         "role": "assistant",
 *         "model": "Baize2",
 *         "content": [
 *             {
 *                 "type": "text",
 *                 "text": "记录设备编号、发现时间、温度读数及处置情况，并安排复检。"
 *             }
 *         ],
 *         "stop_reason": "end_turn",
 *         "stop_sequence": null,
 *         "usage": {
 *             "input_tokens": 42,
 *             "output_tokens": 25
 *         }
 *     }
 *
 * @apiSuccessExample {json} 请求应用执行函数：随后提交 tool_result
 *     HTTP/1.1 200 OK
 *     {
 *         "id": "msg_example001",
 *         "type": "message",
 *         "role": "assistant",
 *         "model": "Baize2",
 *         "content": [
 *             {
 *                 "type": "tool_use",
 *                 "id": "call_001",
 *                 "name": "get_inspection_status",
 *                 "input": {
 *                     "area": "西区"
 *                 }
 *             }
 *         ],
 *         "stop_reason": "tool_use",
 *         "stop_sequence": null,
 *         "usage": {
 *             "input_tokens": 42,
 *             "output_tokens": 25
 *         }
 *     }
 *
 * @apiSuccessExample {text} SSE 文本流：以 message_stop 判断正常结束
 *     HTTP/1.1 200 OK
 *     Content-Type: text/event-stream
 *
 *     event: message_start
 *     data: {"message":{"id":"msg_example001","type":"message","role":"assistant","model":"Baize2","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":0,"output_tokens":0}},"type":"message_start"}
 *
 *     event: content_block_start
 *     data: {"index":0,"content_block":{"type":"text","text":""},"type":"content_block_start"}
 *
 *     event: content_block_delta
 *     data: {"index":0,"delta":{"type":"text_delta","text":"巡检时请记录设备编号、温度及异常情况。"},"type":"content_block_delta"}
 *
 *     event: content_block_stop
 *     data: {"index":0,"type":"content_block_stop"}
 *
 *     event: message_delta
 *     data: {"delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"input_tokens":16,"output_tokens":20},"type":"message_delta"}
 *
 *     event: message_stop
 *     data: {"type":"message_stop"}
 *
 * @apiErrorExample {json} 版本请求头错误：使用受支持的版本
 *     HTTP/1.1 400 Bad Request
 *     {
 *         "type": "error",
 *         "error": {
 *             "type": "invalid_request_error",
 *             "message": "anthropic-version must be 2023-06-01"
 *         }
 *     }
 */

/**
 * @api {get} /v1/models 兼容模型列表
 * @apiName CompatibilityModels
 * @apiGroup Compatibility
 * @apiVersion 1.0.0
 * @apiDescription 查询当前账号可用于兼容 API 的模型，取得后续生成请求所需的 <code>model</code> 值。建议在应用初始化或用户切换模型时调用。
 *                 <p>使用 <code>Authorization: Bearer &lt;Cube Token&gt;</code> 时按 OpenAI 格式返回；使用 <code>x-api-key</code> 并携带 <code>anthropic-version: 2023-06-01</code> 时按 Claude 格式返回。</p>
 *                 <p>OpenAI 格式中的 <code>capabilities</code> 可帮助选择支持图片、工具或流式输出的模型；对象为空时表示未提供能力信息，应先向服务提供方确认。将 <code>data[].id</code> 原样用于请求，不要使用其他平台的模型名替代。列表可能随账号授权或服务状态变化，空列表表示当前无可用模型。</p>
 * @apiHeader {String} [Authorization] OpenAI：Bearer &lt;Cube Token&gt;。
 * @apiHeader {String} [x-api-key] Claude：Cube Token。
 * @apiHeader {String} [anthropic-version] Claude：2023-06-01。
 * @apiSuccess {String} [object] OpenAI 列表格式固定为 list。
 * @apiSuccess {Boolean} [has_more] Claude 列表格式当前为 false。
 * @apiSuccess {String/Null} [first_id] Claude 列表中的第一个模型 ID；空列表时为 null。
 * @apiSuccess {String/Null} [last_id] Claude 列表中的最后一个模型 ID；空列表时为 null。
 * @apiSuccess {Object[]} data 可用模型。
 * @apiSuccess {String} data.id Cube 模型 ID。
 * @apiSuccess {String} [data.object] OpenAI 格式固定为 model。
 * @apiSuccess {Number} [data.created] OpenAI 格式的创建时间，Unix 秒时间戳。
 * @apiSuccess {String} [data.owned_by] OpenAI 格式固定为 cube。
 * @apiSuccess {Object} [data.capabilities] OpenAI 格式的模型能力；未报告能力时为 {}，报告后包含下列字段。
 * @apiSuccess {Boolean} [data.capabilities.text] 是否支持文本输入。
 * @apiSuccess {Boolean} [data.capabilities.images] 是否支持图片输入。
 * @apiSuccess {Boolean} [data.capabilities.tools] 是否支持客户端函数工具。
 * @apiSuccess {Boolean} [data.capabilities.stream] 是否支持 SSE 流式输出。
 * @apiSuccess {String[]} [data.capabilities.parameters] 支持的生成参数名，如 temperature、top_p、max_tokens。
 * @apiSuccess {String} [data.type] Claude 格式固定为 model。
 * @apiSuccess {String} [data.display_name] Claude 格式的显示名。
 * @apiSuccess {String} [data.created_at] Claude 格式的 ISO 8601 创建时间。
 *
 * @apiExample {http} OpenAI 格式：查询可用模型
 *     GET http://211.157.179.37:7010/v1/models
 *     Authorization: Bearer <Cube Token>
 *
 * @apiExample {http} Claude 格式：携带 x-api-key 和版本请求头
 *     GET http://211.157.179.37:7010/v1/models
 *     x-api-key: <Cube Token>
 *     anthropic-version: 2023-06-01
 *
 * @apiSuccessExample {json} OpenAI 列表：选择支持图片和工具调用的模型
 *     HTTP/1.1 200 OK
 *     {
 *         "object": "list",
 *         "data": [
 *             {
 *                 "id": "Baize2",
 *                 "object": "model",
 *                 "created": 0,
 *                 "owned_by": "cube",
 *                 "capabilities": {
 *                     "text": true,
 *                     "images": true,
 *                     "tools": true,
 *                     "stream": true,
 *                     "parameters": [
 *                         "temperature",
 *                         "top_p",
 *                         "max_tokens"
 *                     ]
 *                 }
 *             }
 *         ]
 *     }
 *
 * @apiSuccessExample {json} Claude 列表：使用 id 发起 Messages 请求
 *     HTTP/1.1 200 OK
 *     {
 *         "data": [
 *             {
 *                 "type": "model",
 *                 "id": "Baize2",
 *                 "display_name": "Baize2",
 *                 "created_at": "1970-01-01T00:00:00Z"
 *             }
 *         ],
 *         "has_more": false,
 *         "first_id": "Baize2",
 *         "last_id": "Baize2"
 *     }
 *
 * @apiSuccessExample {json} 账号当前没有可用模型
 *     HTTP/1.1 200 OK
 *     {
 *         "object": "list",
 *         "data": []
 *     }
 */

/**
 * @api {get} /v1/models/:model 兼容模型详情
 * @apiName CompatibilityModel
 * @apiGroup Compatibility
 * @apiVersion 1.0.0
 * @apiDescription 查询指定模型的标识、创建时间及能力，适合在模型选择页展示详情或核对所需能力。
 *                 <p>路径参数使用模型列表返回的 <code>id</code>，认证方式和响应格式与“兼容模型列表”相同。OpenAI 格式可读取 <code>capabilities</code> 判断图片、工具及流式能力；Claude 格式返回模型的名称与创建时间。</p>
 *                 <p>模型不存在或当前账号不可用时返回 HTTP <code>404</code>。此时应刷新模型列表并选择有效 ID。</p>
 * @apiParam {String} model 模型列表返回的模型 ID。
 * @apiHeader {String} [Authorization] OpenAI 格式：Bearer &lt;Cube Token&gt;。
 * @apiHeader {String} [x-api-key] Claude 格式：Cube Token。
 * @apiHeader {String} [anthropic-version] Claude 格式：2023-06-01。
 * @apiSuccess {String} id Cube 模型 ID。
 * @apiSuccess {String} [object] OpenAI 格式固定为 model。
 * @apiSuccess {Number} [created] OpenAI 格式的创建时间，Unix 秒时间戳。
 * @apiSuccess {String} [owned_by] OpenAI 格式固定为 cube。
 * @apiSuccess {Object} [capabilities] OpenAI 格式的模型能力；未报告能力时为 {}，报告后包含下列字段。
 * @apiSuccess {Boolean} [capabilities.text] 是否支持文本输入。
 * @apiSuccess {Boolean} [capabilities.images] 是否支持图片输入。
 * @apiSuccess {Boolean} [capabilities.tools] 是否支持客户端函数工具。
 * @apiSuccess {Boolean} [capabilities.stream] 是否支持 SSE 流式输出。
 * @apiSuccess {String[]} [capabilities.parameters] 支持的生成参数名，如 temperature、top_p、max_tokens。
 * @apiSuccess {String} [type] Claude 格式固定为 model。
 * @apiSuccess {String} [display_name] Claude 格式的显示名。
 * @apiSuccess {String} [created_at] Claude 格式的 ISO 8601 创建时间。
 *
 * @apiExample {http} OpenAI 格式：查询 Baize2 能力
 *     GET http://211.157.179.37:7010/v1/models/Baize2
 *     Authorization: Bearer <Cube Token>
 *
 * @apiExample {http} Claude 格式：查询模型详情
 *     GET http://211.157.179.37:7010/v1/models/Baize2
 *     x-api-key: <Cube Token>
 *     anthropic-version: 2023-06-01
 *
 * @apiSuccessExample {json} OpenAI 模型详情
 *     HTTP/1.1 200 OK
 *     {
 *         "id": "Baize2",
 *         "object": "model",
 *         "created": 0,
 *         "owned_by": "cube",
 *         "capabilities": {
 *             "text": true,
 *             "images": true,
 *             "tools": true,
 *             "stream": true,
 *             "parameters": [
 *                 "temperature",
 *                 "top_p",
 *                 "max_tokens"
 *             ]
 *         }
 *     }
 *
 * @apiSuccessExample {json} Claude 模型详情
 *     HTTP/1.1 200 OK
 *     {
 *         "type": "model",
 *         "id": "Baize2",
 *         "display_name": "Baize2",
 *         "created_at": "1970-01-01T00:00:00Z"
 *     }
 */
