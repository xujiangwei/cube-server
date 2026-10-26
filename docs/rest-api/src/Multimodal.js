/**
 * @apiDefine Multimodal 多模态服务
 */


/**
 * @api {post} /multimodal/base/:token 多模态数据推理
 * @apiName 多模态数据推理
 * @apiGroup Multimodal
 * @apiVersion 1.0.0
 *
 * @apiDescription 输入文件和文本生成相应的文本内容。该接口会采用多模态模型对输入的文件和文本进行处理，并生成相应的文本内容。该接口支持多种文件类型，包括图片、音频、视频等。
 *                 <p>目前支持4大类多模态任务：</p>
 *                 <ul>
 *                     <li>Vision Grounding - 图像检测任务，单元名称：<code>OmniGround</code></li>
 *                     <li>Video Analysis - 视频分析任务，单元名称：<code>OmniVL</code></li>
 *                     <li>Vision Inference - 视觉推理任务，单元名称：<code>OmniMultimodal</code></li>
 *                     <li>Fine-Grained Perception - 细粒度图像感知任务，单元名称：<code>OmniPerception</code></li>
 *                 </ul>
 *                 <p><code>OmniGround</code> 专注于根据提示词分析和识别图像。</p>
 *                 <p><code>OmniVL</code> 专注于分析视频并提取关键信息。</p>
 *                 <p><code>OmniMultimodal</code> 专注于基于视觉信息进行推理和判断，适用于复杂场景。该模型支持使用语音进行输入，输入语音文件必须为 .wav 或 .mp3 格式。</p>
 *                 <p><code>OmniPerception</code> 专注于单张图片的细粒度目标检测、目标定位、目标计数、推理检测、区域描述、区域文字识别和视觉问答。</p>
 *                 <p> </p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} channel 通道的频道码。
 * @apiBody {Object} input 多模态输入信息。
 * @apiBody {String} [input.unit] 指定使用的多模态单元名称。
 * @apiBody {String} input.content 输入的文本内容。允许该字段为文件码，URL地址或文本内容。文本内容可以是自然语言描述、问题、指令等。使用 <code>OmniPerception</code> 时，除 <code>brief_region_caption</code> 和 <code>region_ocr</code> 外，该字段不能为空。
 * @apiBody {Array} [input.history] 输入的历史对话内容。每个历史对话包含一个用户输入和一个模型输出。该字段用于提供上下文信息，帮助模型更好地理解当前输入。
 * @apiBody {String} [input.history.query] 历史对话中的用户输入。 
 * @apiBody {String} [input.history.answer] 历史对话中的模型输出。
 * @apiBody {Array} [input.files] 输入的文件列表。文件列表可以是文件的标签码，也可以是文件的 URL 地址。
 * @apiBody {Object} [input.option] 指定的任务描述。
 * @apiBody {String} [input.option.task] 指定的任务名称。<br/>
 *                   <code>OmniGround</code> 的任务名称。<br/>
 *                   <code>detect</code> - 检测并定位所有符合描述的目标<br/>
 *                   <code>ground_single</code> - 检测并定位单个目标<br/>
 *                   <code>ground_multi</code> - 检测并定位多个目标<br/>
 *                   <code>ground_text</code> - 请找到标有指定描述的文本<br/>
 *                   <code>detect_text</code> - 检测所有文本<br/>
 *                   <code>ground_gui</code> - GUI元素检测<br/>
 *                   <code>point</code> - 点检测。
 *                   <hr/>
 *                   <code>OmniPerception</code> 的任务名称。<br/>
 *                   <code>detection</code> - 开放词汇目标检测。<br/>
 *                   <code>grounding_single</code> - 根据描述定位单个目标。<br/>
 *                   <code>counting</code> - 目标计数。<br/>
 *                   <code>reasoning_detection</code> - 结合语义推理定位目标。<br/>
 *                   <code>brief_region_caption</code> - 描述指定区域。<br/>
 *                   <code>region_ocr</code> - 识别指定区域中的文字。<br/>
 *                   <code>vqa</code> - 针对整张图片进行视觉问答。
 * @apiBody {String} [input.option.mode] 指定的任务模式。<br/><code>hybrid</code> - 默认平衡模式。<br/><code>fast</code> - 快速模式，适合低延迟或规则场景。<br/><code>slow</code> - 精准模式，适合离线高精度或复杂场景。
 * @apiBody {String} [input.option.output] 指定的GUI任务输出模式（仅在GroundGui任务中有效）。<code>box</code>表示输出边界框，<code>point</code>表示输出位置。
 * @apiBody {String} [input.option.lang=zh] <code>OmniPerception</code> 内置任务模板的语言。支持 <code>zh</code> 和 <code>en</code>。
 * @apiBody {Array} [input.option.bboxList] 候选边界框列表，每个元素使用原图像素坐标 <code>[x1, y1, x2, y2]</code>，最多 100 个。定位类任务省略时由服务端自动生成候选框；<code>brief_region_caption</code> 和 <code>region_ocr</code> 应显式提供；<code>vqa</code> 不允许提供该字段。
 * @apiBody {Number[]} [input.option.targetRegionIndexes] 需要处理的候选框索引列表，索引从 0 开始。仅用于 <code>brief_region_caption</code> 和 <code>region_ocr</code>，使用这两个任务时必填。
 *
 * 
 * @apiParamExample {json} OmniGround -一般示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "content": "定位图中所有的电脑和手机",
 *             "files": [
 *                 "unpmAGrMYphCHatmTRmoPMbrMCKGLHOlWOTHGviJSLDQyIwtSLdBTRLUpOQeMxLg"
 *             ]
 *         }
 *     }
 * 
 * @apiParamExample {json} OmniGround - detect 任务示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "content": "电脑 手机",
 *             "files": [
 *                 "unpmAGrMYphCHatmTRmoPMbrMCKGLHOlWOTHGviJSLDQyIwtSLdBTRLUpOQeMxLg"
 *             ],
 *             "option": {
 *                 "task": "detect"
 *             }
 *         }
 *     }
 * 
 * @apiParamExample {json} OmniGround - ground_text 任务示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "content": "锦江酒店",
 *             "files": [
 *                 "https://my.domain.com/images/photo.jpg"
 *             ],
 *             "option": {
 *                 "task": "ground_text"
 *             }
 *         }
 *     }
 * 
 * @apiParamExample {json} OmniVL 任务示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKX",
 *         "input": {
 *             "unit": "OmniVL",
 *             "content": "描述画面内容，不要补充无法确认的信息。\n重点记录人物、动作、镜头变化、场景切换、字幕、界面变化和物体状态变化。",
 *             "files": [
 *                 "brMCKGLHOeMxLglWOTmAGrMYphCHatmTRmoPOQMHGviJSLDQyunpIwtSLdBTRLUp"
 *             ]
 *         }
 *     }
 * 
 * @apiParamExample {json} OmniMultimodal - 任务示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniMultimodal",
 *             "content": "hCHatmTRmoPOQMHGviJSLDQyunpIwtSLdBTRLUpbrMCKGLHOeMxLglWOTmAGrMYp",
 *             "files": [
 *                 "brMCKGLHOeMxLglWOTmAGrMYphCHatmTRmoPOQMHGviJSLDQyunpIwtSLdBTRLUp"
 *             ]
 *         }
 *     }
 * 
 * @apiParamExample {json} OmniPerception - detection 目标检测示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniPerception",
 *             "content": "热水器; 水箱",
 *             "files": [
 *                 "unpmAGrMYphCHatmTRmoPMbrMCKGLHOlWOTHGviJSLDQyIwtSLdBTRLUpOQeMxLg"
 *             ],
 *             "option": {
 *                 "task": "detection",
 *                 "lang": "zh"
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniPerception - grounding_single 单目标定位示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniPerception",
 *             "content": "穿白色上衣的人",
 *             "files": [
 *                 "unpmAGrMYphCHatmTRmoPMbrMCKGLHOlWOTHGviJSLDQyIwtSLdBTRLUpOQeMxLg"
 *             ],
 *             "option": {
 *                 "task": "grounding_single",
 *                 "lang": "zh"
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniPerception - counting 目标计数示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniPerception",
 *             "content": "画面中的车辆",
 *             "files": [
 *                 "unpmAGrMYphCHatmTRmoPMbrMCKGLHOlWOTHGviJSLDQyIwtSLdBTRLUpOQeMxLg"
 *             ],
 *             "option": {
 *                 "task": "counting",
 *                 "lang": "zh"
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniPerception - reasoning_detection 推理检测示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniPerception",
 *             "content": "找出没有佩戴安全帽的施工人员",
 *             "files": [
 *                 "unpmAGrMYphCHatmTRmoPMbrMCKGLHOlWOTHGviJSLDQyIwtSLdBTRLUpOQeMxLg"
 *             ],
 *             "option": {
 *                 "task": "reasoning_detection",
 *                 "lang": "zh"
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniPerception - brief_region_caption 区域描述示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniPerception",
 *             "content": "",
 *             "files": [
 *                 "unpmAGrMYphCHatmTRmoPMbrMCKGLHOlWOTHGviJSLDQyIwtSLdBTRLUpOQeMxLg"
 *             ],
 *             "option": {
 *                 "task": "brief_region_caption",
 *                 "lang": "zh",
 *                 "bboxList": [
 *                     [120, 80, 560, 720],
 *                     [620, 140, 980, 760]
 *                 ],
 *                 "targetRegionIndexes": [0]
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniPerception - region_ocr 区域文字识别示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniPerception",
 *             "content": "",
 *             "files": [
 *                 "unpmAGrMYphCHatmTRmoPMbrMCKGLHOlWOTHGviJSLDQyIwtSLdBTRLUpOQeMxLg"
 *             ],
 *             "option": {
 *                 "task": "region_ocr",
 *                 "lang": "zh",
 *                 "bboxList": [
 *                     [220, 110, 1060, 360]
 *                 ],
 *                 "targetRegionIndexes": [0]
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniPerception - vqa 视觉问答示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniPerception",
 *             "content": "图中正在发生什么？",
 *             "files": [
 *                 "unpmAGrMYphCHatmTRmoPMbrMCKGLHOlWOTHGviJSLDQyIwtSLdBTRLUpOQeMxLg"
 *             ],
 *             "option": {
 *                 "task": "vqa",
 *                 "lang": "zh"
 *             }
 *         }
 *     }
 *
 * @apiSuccess {Number} sn 本次请求的流水号。
 * @apiSuccess {Number} timestamp 本次请求的时间戳。 
 * @apiSuccess {String} unit 指定多模态单元。
 * @apiSuccess {String} answer 多模态模型生成的文本内容。
 * @apiSuccess {String} thought 多模态模型生成的思路。
 * @apiSuccess {Object} resultPayload 经过多模态模型处理后的结果数据。
 * @apiSuccess {Array} resultPayload.refs 结果参考数据列表。
 * @apiSuccess {String} resultPayload.refs.content 结果参考数据的文本内容。
 * @apiSuccess {Array} resultPayload.refs.boxes 结果参考数据的边界框列表。
 * @apiSuccess {Array} resultPayload.refs.points 结果参考数据的点列表。
 * @apiSuccess {Number} resultPayload.width 输入图片的宽度。
 * @apiSuccess {Number} resultPayload.height 输入图片的高度。
 * @apiSuccess {Object} usage 本次请求的使用情况。
 * @apiSuccess {Number} usage.inputTokens 本次请求的输入词元数量。
 * @apiSuccess {Number} usage.outputTokens 本次请求的输出词元数量。
 * 
 * @apiSuccessExample {json} OmniGround - detect 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736204,
 *         "timestamp": 1788425020947,
 *         "unit": "OmniGround",
 *         "answer": "<ref>电脑</ref><box><382><169><522><471></box><box><827><165><999><474></box><ref>phone</ref><box><498><473><538><524></box>",
 *         "thought": "",
 *         "resultPayload": {
 *             "refs": [{
 *                 "boxes": [{
 *                     "y1": 182.52,
 *                     "x1": 733.44,
 *                     "y2": 508.68,
 *                     "x2": 1002.24
 *                 }, {
 *                     "y1": 178.2,
 *                     "x1": 1587.84,
 *                     "y2": 511.92,
 *                     "x2": 1918.08
 *                 }],
 *                 "content": "电脑",
 *                 "points": []
 *             }, {
 *                 "boxes": [{
 *                     "y1": 510.84,
 *                     "x1": 956.16,
 *                     "y2": 565.92,
 *                     "x2": 1032.96
 *                 }],
 *                 "content": "phone",
 *                 "points": []
 *             }],
 *             "width": 1920,
 *             "height": 1080
 *         }
 *     }
 * 
 * @apiSuccessExample {json} OmniPerception - detection 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 1876429194287302656,
 *         "timestamp": 1789452204127,
 *         "unit": "OmniPerception",
 *         "answer": "<ground>热水器</ground><objects><obj2></objects><ground>水箱</ground><objects><obj5></objects>",
 *         "thought": "根据屋顶区域的外形和安装位置，定位热水器与水箱。",
 *         "resultPayload": {
 *             "refs": [{
 *                 "content": "热水器",
 *                 "boxes": [{
 *                     "x1": 736.0,
 *                     "y1": 188.0,
 *                     "x2": 982.0,
 *                     "y2": 476.0
 *                 }],
 *                 "points": []
 *             }, {
 *                 "content": "水箱",
 *                 "boxes": [{
 *                     "x1": 1048.0,
 *                     "y1": 206.0,
 *                     "x2": 1286.0,
 *                     "y2": 498.0
 *                 }],
 *                 "points": []
 *             }],
 *             "width": 1920,
 *             "height": 1080
 *         },
 *         "usage": {
 *             "inputTokens": 1324,
 *             "outputTokens": 31
 *         }
 *     }
 *
 * @apiSuccessExample {json} OmniVL 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 1876039763114328072,
 *         "timestamp": 1789131416787,
 *         "unit": "OmniVL",
 *         "answer": "这个视频展示了一个城市街道场景，画面中有行人、车辆和建筑物。视频中有多个行人走在街道上，有的行人正在过马路，有的行人正在等待红绿灯。街道上有多辆汽车和摩托车在行驶，还有一些自行车。建筑物包括商店、办公楼和住宅楼。视频中还可以看到交通信号灯和路标。整体场景呈现出一个繁忙的城市街道环境。",
 *         "thought": "",
 *         "resultPayload": {},
 *         "usage": {
 *             "inputTokens": 2570,
 *             "outputTokens": 102
 *         }
 *     }
 *
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 * @apiError 404 未找到指定文件的数据。
 */


/**
 * @api {post} /multimodal/stream/:token 流式多模态推理
 * @apiName 流式多模态推理
 * @apiGroup Multimodal
 * @apiVersion 1.0.0
 *
 * @apiDescription 对持续输入的实时视频流执行多模态推理。与 <code>/multimodal/base/:token</code> 的一次性请求-应答不同，
 *                 本接口由服务端拉取输入视频流并建立长驻的推理任务，推理结果通过输出视频流或回调地址持续推送，直到收到停止指令。
 *                 <p>目前支持3类流式任务：</p>
 *                 <ul>
 *                     <li>流式分类（目标定位）任务，单元名称：<code>OmniGround</code>。对视频逐帧执行 grounding，并把标注后的画面重新编码为一路输出视频流。</li>
 *                     <li>流式自然语言推理任务，单元名称：<code>OmniVL</code>。把视频切分为片段，逐段生成自然语言描述，并按片段回调推送结果。</li>
 *                     <li>命中图片回调任务，单元名称：<code>OmniPerception</code>。抽取 RTSP 原帧定位提示词描述的目标，命中后以 PNG 二进制 POST 回调。</li>
 *                 </ul>
 *                 <p>启动、查询、更新提示词及停止都由本接口承载，用 <code>input.option.operation</code> 区分：启动任务后，应答中的 <code>resultPayload.streamId</code> 即任务 ID；
 *                 停止任务时把该 ID 作为 <code>input.option.streamId</code> 传入。应答中的 <code>resultPayload</code> 是任务的状态快照，
 *                 包含任务状态、输入/输出视频流信息以及解码、推理、缓冲、时延等运行指标。</p>
 *                 <p>字段说明：与输入/输出视频流、帧计数、推理时延相关的指标仅流式分类（<code>OmniGround</code>）任务返回；
 *                 与视频片段、回调相关的指标仅流式自然语言推理（<code>OmniVL</code>）任务返回。因此这些字段在应答中标为可选。</p>
 *                 <p><code>OmniVL</code> 任务的推理结果通过 <code>input.option.callbackUrl</code> 回调推送，回调数据中各字段含义如下：
 *                 <code>sequence</code> - 回调序号；<code>streamId</code> - 任务 ID；<code>response</code> - 该片段的推理结果文本；
 *                 <code>timestamp</code> - 回调时间戳；<code>timeRange</code> - 该片段在视频中的时间范围
 *                 （<code>startOffset</code>/<code>endOffset</code> 为相对视频起点的偏移秒数，<code>startTimestamp</code>/<code>endTimestamp</code> 为绝对时间戳）；
 *                 <code>performance</code> - 该片段的耗时与词元消耗（<code>elapsed</code>/<code>inputTokens</code>/<code>outputTokens</code>）。
 *                 具体格式见下方 <code>callbackUrl 回调数据</code> 示例。</p>
 *                 <p><code>OmniPerception</code> 仅支持 detection、grounding_single、reasoning_detection、counting 流式任务，以非空目标框判定命中。
 *                 未命中时最多每秒检测一帧；命中后从推理完成起至少等待 <code>intervalSeconds</code> 秒再检测，持续命中则继续上报。
 *                 只保留最新待处理帧，不排队。回调请求体是原尺寸 PNG，Content-Type 为 <code>image/png</code>，不是 multipart 或 JSON；
 *                 PNG 的 iTXt 扩展键 <code>vlm_meta</code> 包含 UTF-8 JSON。20 MiB 以上的 PNG 跳过回调。
 *                 网络错误、408、429、5xx 最多重试三次且不跟随重定向；接收方应按 <code>eventId</code> 去重。</p>
 *                 <p>同一路径同时接受 <code>GET ?channel={频道码}&amp;sn={streamId}</code> 查询任务状态，
 *                 便于不构造请求体的客户端轮询；认证方式与应答结构一致。</p>
 *                 <p> </p>
 *
 * @apiParam {String} token 授权令牌。
 *
 * @apiQuery {String} [channel] 查询任务状态时使用。与 <code>sn</code> 一起走 <code>GET</code> 查询通道，不提交请求体。
 * @apiQuery {Number} [sn] 查询任务状态时使用，取值为 <code>resultPayload.streamId</code>。
 *
 * @apiBody {String} channel 通道的频道码。
 * @apiBody {Object} input 多模态输入信息。
 * @apiBody {String} input.unit 指定使用的多模态单元名称。<br/><code>OmniGround</code> - 流式分类。<br/><code>OmniVL</code> - 流式自然语言推理。<br/><code>OmniPerception</code> - 命中原帧 PNG 回调。
 * @apiBody {String} [input.content] 输入的文本内容，作为本次推理任务的指令或提示词。启动 <code>OmniGround</code> 任务时可传空字符串；启动 <code>OmniVL</code> 任务时用于描述画面中需要重点记录的内容。
 * @apiBody {Array} input.files 输入的文件列表。流式任务此处填写实时视频流地址（如 <code>rtsp://</code> 开头的 URL）；停止任务时传空数组。
 * @apiBody {Object} input.option 流式任务的选项。
 * @apiBody {String} [input.option.operation] 任务操作。<br/><code>start</code> - 启动。<br/><code>status</code> - 查询状态。<br/><code>update</code> - 更新推理指令：<code>OmniVL</code>、<code>OmniPerception</code> 用 <code>input.content</code> 替换提示词，<code>OmniGround</code> 用 <code>input.option.categories</code> 更新类别。<br/><code>stop</code> - 停止。省略该字段时按启动处理；后三种操作必须传 <code>streamId</code>、空 <code>files</code>（<code>files</code> 传 <code>[]</code> 即可）。
 * @apiBody {String} [input.option.outputUrl] <code>OmniGround</code> 任务的输出视频流地址。服务端会把标注后的画面重新编码并推送到该地址，可从应答的 <code>resultPayload.output</code> 查看该输出流的状态。
 * @apiBody {String} [input.option.callbackUrl] <code>OmniVL</code> 的 JSON 结果回调地址，或 <code>OmniPerception</code> 的 PNG 回调地址（后者启动时必填）。
 * @apiBody {String} [input.option.task=detection] <code>OmniPerception</code> 流任务类型：detection、grounding_single、reasoning_detection、counting；启动后不可修改。
 * @apiBody {Number} [input.option.intervalSeconds=5] <code>OmniPerception</code> 命中后的检测间隔，不小于 1 的整数；只可在启动时指定，覆盖静态配置。
 * @apiBody {String} [input.option.streamId] 启动任务时返回的 ID；查询、更新和停止时必填。
 *
 * @apiParamExample {json} OmniPerception - 启动任务
 *     {"channel":"mnxRSaLdPuHEVaKY","input":{"unit":"OmniPerception","content":"屋顶上的水箱","files":["rtsp://camera/live"],"option":{"operation":"start","task":"reasoning_detection","intervalSeconds":5,"callbackUrl":"https://receiver.example.com/vlm"}}}
 *
 * @apiParamExample {json} OmniPerception - 更新提示词
 *     {"channel":"mnxRSaLdPuHEVaKY","input":{"unit":"OmniPerception","content":"屋顶上的热水器","files":[],"option":{"operation":"update","streamId":"启动应答中的 streamId"}}}
 *
 * @apiParamExample {json} OmniPerception - 查询或停止
 *     {"channel":"mnxRSaLdPuHEVaKY","input":{"unit":"OmniPerception","content":"","files":[],"option":{"operation":"status","streamId":"启动应答中的 streamId"}}}
 *     停止时仅将 operation 改为 stop。
 *
 * @apiParamExample {json} OmniGround - 启动流式分类任务示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniGround",
 *             "content": "",
 *             "files": [
 *                 "rtsp://jdzrobot:jdzrobot@211.157.179.38:8554/apexnova/ANSFCAMERANN0001/video"
 *             ],
 *             "option": {
 *                 "operation": "start",
 *                 "outputUrl": "rtsp://jdzrobot:jdzrobot@211.157.179.38:8554/apexnova/ANSFCAMERANN0001/video/grounding"
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniGround - 停止流式分类任务示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKX",
 *         "input": {
 *             "unit": "OmniGround",
 *             "content": "",
 *             "files": [],
 *             "option": {
 *                 "operation": "stop",
 *                 "streamId": "e1007214-9734-448c-a514-ad4a2f22a69b"
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniVL - 启动流式自然语言推理任务示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKX",
 *         "input": {
 *             "unit": "OmniVL",
 *             "content": "描述画面内容，不要补充无法确认的信息，不要重复相同的内容或描述。\n重点记录人物、动作、镜头变化、场景切换、字幕、界面变化和物体状态变化。",
 *             "files": [
 *                 "rtsp://jdzrobot:jdzrobot@211.157.179.38:8554/apexnova/ANSFCAMERANN0001/video"
 *             ],
 *             "option": {
 *                 "callbackUrl": "http://211.157.179.38:9410/omni/vl/callback"
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniVL - 更新推理指令示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKX",
 *         "input": {
 *             "unit": "OmniVL",
 *             "content": "只记录画面中的车辆与车牌，不要描述其他物体。",
 *             "files": [],
 *             "option": {
 *                 "operation": "update",
 *                 "streamId": "e1f77d65-dffd-4ffe-a0a1-7a80ccc0fdff"
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniVL - 停止流式自然语言推理任务示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKX",
 *         "input": {
 *             "unit": "OmniVL",
 *             "content": "",
 *             "files": [],
 *             "option": {
 *                 "operation": "stop",
 *                 "streamId": "e1f77d65-dffd-4ffe-a0a1-7a80ccc0fdff"
 *             }
 *         }
 *     }
 *
 * @apiSuccess {Number} sn 本次请求的流水号。
 * @apiSuccess {Number} timestamp 本次请求的时间戳（毫秒）。
 * @apiSuccess {String} unit 本次任务使用的多模态单元名称。
 * @apiSuccess {String} answer 多模态模型生成的文本内容。流式任务的启动/停止应答中为空字符串，识别结果通过输出视频流或 <code>callbackUrl</code> 持续推送。
 * @apiSuccess {String} thought 多模态模型生成的思路。流式任务的启动/停止应答中为空字符串。
 * @apiSuccess {Object} resultPayload 流式任务的状态快照，随任务推进而变化。
 * @apiSuccess {String} resultPayload.streamId 流式任务 ID。停止任务时作为 <code>input.option.streamId</code> 传入。
 * @apiSuccess {String} resultPayload.status 任务状态。<code>starting</code> - 启动中；<code>running</code> - 运行中；<code>reconnecting</code> - 输入流断开后重连中；<code>updating</code> - 推理指令更新中（新指令已生效，等待下一帧/片段应用）；<code>stopping</code> - 停止中；<code>stopped</code> - 已停止。
 * @apiSuccess {Number} resultPayload.startedAt 任务启动时间戳（毫秒）。
 * @apiSuccess {Number} [resultPayload.stoppedAt] 任务停止时间戳（毫秒）。任务尚未停止时为 <code>null</code>。
 * @apiSuccess {String} [resultPayload.lastError] 任务最近一次的错误信息。无错误时为 <code>null</code>。
 * @apiSuccess {Object} usage 本次请求的使用情况。
 * @apiSuccess {Number} usage.elapsed 本次请求的服务端耗时（毫秒）。
 * @apiSuccess {String} usage.model 本次任务使用的模型名称。
 * @apiSuccess {Number} usage.inputTokens 本次请求的输入词元数量。
 * @apiSuccess {Number} usage.outputTokens 本次请求的输出词元数量。
 * @apiSuccess {Object} [resultPayload.input] 输入视频流信息。
 * @apiSuccess {String} resultPayload.input.url 输入视频流地址，即请求 <code>input.files</code> 中传入的地址。应答中的地址会隐去账号密码。
 * @apiSuccess {String} resultPayload.input.status 输入视频流的连接状态，取值同 <code>resultPayload.status</code>。
 * @apiSuccess {Number} [resultPayload.input.width] 输入画面宽度。尚未探测到时为 <code>null</code>。
 * @apiSuccess {Number} [resultPayload.input.height] 输入画面高度。尚未探测到时为 <code>null</code>。
 * @apiSuccess {Number} [resultPayload.input.fps] 输入视频流帧率。尚未探测到时为 <code>null</code>。
 * @apiSuccess {Object} [resultPayload.output] 标注后输出视频流的信息（仅 <code>OmniGround</code> 任务返回）。
 * @apiSuccess {String} resultPayload.output.url 输出视频流的推送地址，即请求 <code>input.option.outputUrl</code> 中传入的地址。应答中的地址会隐去账号密码。
 * @apiSuccess {String} resultPayload.output.status 输出视频流的发布状态，取值同 <code>resultPayload.status</code>。
 * @apiSuccess {String} [resultPayload.output.codec] 输出视频流的编码格式，例如 <code>libx264</code>。尚未确定时为 <code>null</code>。
 * @apiSuccess {Number} [resultPayload.output.width] 输出画面宽度。尚未确定时为 <code>null</code>。
 * @apiSuccess {Number} [resultPayload.output.height] 输出画面高度。尚未确定时为 <code>null</code>。
 * @apiSuccess {Number} [resultPayload.output.fps] 输出视频流帧率。尚未确定时为 <code>null</code>。
 * @apiSuccess {Number} [resultPayload.decodedFrames] 已从输入流解码的帧数。
 * @apiSuccess {Number} [resultPayload.inferredFrames] 已完成推理的帧数。
 * @apiSuccess {Number} [resultPayload.publishedFrames] 已发布到输出视频流的帧数。
 * @apiSuccess {Number} [resultPayload.pendingInferenceFrames] 已下发但尚未返回结果的推理帧数。
 * @apiSuccess {Number} [resultPayload.skippedInferenceFrames] 按策略跳过推理的帧数。
 * @apiSuccess {Number} [resultPayload.droppedFrames] 因缓冲溢出等原因丢弃的帧数。
 * @apiSuccess {Number} [resultPayload.inferenceFailures] 推理失败的累计次数。
 * @apiSuccess {Number} [resultPayload.catchupEvents] 缓冲区加速追赶（追帧）的累计次数。
 * @apiSuccess {Number} [resultPayload.bufferedFrames] 当前缓冲区中缓存的帧数。
 * @apiSuccess {Number} [resultPayload.bufferedSeconds] 当前缓冲时长（秒）。
 * @apiSuccess {Number} [resultPayload.targetBufferSeconds] 缓冲区的目标时长（秒）。
 * @apiSuccess {Number} [resultPayload.maxBufferSeconds] 缓冲区允许的最大时长（秒）。
 * @apiSuccess {Number} [resultPayload.effectiveFps] 实际处理的帧率。尚未统计时为 <code>null</code>。
 * @apiSuccess {Number} [resultPayload.queryVersion] 推理指令的版本号，每次更新推理指令后递增。
 * @apiSuccess {Number} [resultPayload.activeQueryVersion] 当前生效的推理指令版本号。
 * @apiSuccess {Array} [resultPayload.categories] 流式分类任务使用的类别列表。
 * @apiSuccess {Object} [resultPayload.pipelineLatencyMs] 端到端流水线时延统计（毫秒）。
 * @apiSuccess {Number} [resultPayload.pipelineLatencyMs.last] 最近一次的统计值。
 * @apiSuccess {Number} [resultPayload.pipelineLatencyMs.p95] P95 统计值。
 * @apiSuccess {Object} [resultPayload.modelLatencyMs] 模型推理时延统计（毫秒），字段同 <code>pipelineLatencyMs</code>。
 * @apiSuccess {Number} [resultPayload.modelLatencyMs.last] 最近一次的统计值。
 * @apiSuccess {Number} [resultPayload.modelLatencyMs.p95] P95 统计值。
 * @apiSuccess {Number} [resultPayload.capturedSegments] <code>OmniVL</code> 任务已从输入流捕获的视频片段数。
 * @apiSuccess {Number} [resultPayload.processedSegments] <code>OmniVL</code> 任务已完成推理的视频片段数。
 * @apiSuccess {Number} [resultPayload.droppedSegments] <code>OmniVL</code> 任务因积压等原因丢弃的视频片段数。
 * @apiSuccess {Number} [resultPayload.lastSegmentEndAt] <code>OmniVL</code> 任务最近一个已完成片段的结束时间戳（毫秒）。
 * @apiSuccess {Number} [resultPayload.callbackFailures] 回调任务推送失败的累计事件数。
 * @apiSuccess {String} [resultPayload.task] OmniPerception 流式定位任务类型。
 * @apiSuccess {Number} [resultPayload.intervalSeconds] OmniPerception 命中后的检测间隔。
 * @apiSuccess {Number} [resultPayload.matchedFrames] OmniPerception 命中帧数。
 * @apiSuccess {Number} [resultPayload.postedFrames] OmniPerception 成功上传 PNG 的帧数。
 * @apiSuccess {Number} [resultPayload.lastMatchedAt] 最近命中帧的捕获时间戳，未命中时为 null。
 *
 * @apiSuccessExample {json} OmniGround - 启动流式分类应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "unit": "OmniGround",
 *         "answer": "",
 *         "thought": "",
 *         "resultPayload": {
 *             "bufferedSeconds": 0,
 *             "lastError": null,
 *             "streamId": "e1007214-9734-448c-a514-ad4a2f22a69b",
 *             "startedAt": 1789263748740,
 *             "decodedFrames": 0,
 *             "queryVersion": 1,
 *             "publishedFrames": 0,
 *             "output": {
 *                 "codec": null,
 *                 "width": null,
 *                 "fps": null,
 *                 "url": "rtsp://***@211.157.179.38:8554/apexnova/ANSFCAMERANN0001/video/grounding",
 *                 "status": "starting",
 *                 "height": null
 *             },
 *             "pipelineLatencyMs": {
 *                 "last": null,
 *                 "p95": null
 *             },
 *             "activeQueryVersion": 0,
 *             "categories": [],
 *             "maxBufferSeconds": 6,
 *             "pendingInferenceFrames": 0,
 *             "inferenceFailures": 0,
 *             "targetBufferSeconds": 4,
 *             "inferredFrames": 0,
 *             "catchupEvents": 0,
 *             "input": {
 *                 "width": null,
 *                 "fps": null,
 *                 "url": "rtsp://***@211.157.179.38:8554/apexnova/ANSFCAMERANN0001/video",
 *                 "status": "starting",
 *                 "height": null
 *             },
 *             "stoppedAt": null,
 *             "droppedFrames": 0,
 *             "bufferedFrames": 0,
 *             "effectiveFps": null,
 *             "skippedInferenceFrames": 0,
 *             "modelLatencyMs": {
 *                 "last": null,
 *                 "p95": null
 *             },
 *             "status": "starting"
 *         },
 *         "usage": {
 *             "elapsed": 0,
 *             "model": "OmniBaize",
 *             "inputTokens": 0,
 *             "outputTokens": 0
 *         },
 *         "sn": 1876178864400498693,
 *         "timestamp": 1789263748849
 *     }
 *
 * @apiSuccessExample {json} OmniGround - 停止流式分类应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "unit": "OmniGround",
 *         "answer": "",
 *         "thought": "",
 *         "resultPayload": {
 *             "bufferedSeconds": 4.041,
 *             "lastError": null,
 *             "streamId": "e1007214-9734-448c-a514-ad4a2f22a69b",
 *             "startedAt": 1789263748740,
 *             "decodedFrames": 11143,
 *             "queryVersion": 1,
 *             "publishedFrames": 11077,
 *             "output": {
 *                 "codec": "libx264",
 *                 "width": 640,
 *                 "fps": 18,
 *                 "url": "rtsp://***@211.157.179.38:8554/apexnova/ANSFCAMERANN0001/video/grounding",
 *                 "status": "running",
 *                 "height": 480
 *             },
 *             "pipelineLatencyMs": {
 *                 "last": 4015,
 *                 "p95": 4031
 *             },
 *             "activeQueryVersion": 1,
 *             "categories": [],
 *             "maxBufferSeconds": 6,
 *             "pendingInferenceFrames": 0,
 *             "inferenceFailures": 0,
 *             "targetBufferSeconds": 4,
 *             "inferredFrames": 11142,
 *             "catchupEvents": 0,
 *             "input": {
 *                 "width": 640,
 *                 "fps": 30,
 *                 "url": "rtsp://***@211.157.179.38:8554/apexnova/ANSFCAMERANN0001/video",
 *                 "status": "running",
 *                 "height": 480
 *             },
 *             "stoppedAt": null,
 *             "droppedFrames": 0,
 *             "bufferedFrames": 66,
 *             "effectiveFps": 18,
 *             "skippedInferenceFrames": 0,
 *             "modelLatencyMs": {
 *                 "last": 45,
 *                 "p95": 59
 *             },
 *             "status": "stopping"
 *         },
 *         "usage": {
 *             "elapsed": 0,
 *             "model": "OmniBaize",
 *             "inputTokens": 0,
 *             "outputTokens": 0
 *         },
 *         "sn": 1876178864400498695,
 *         "timestamp": 1789264377639
 *     }
 *
 * @apiSuccessExample {json} OmniVL - 启动流式自然语言推理应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "unit": "OmniVL",
 *         "answer": "",
 *         "thought": "",
 *         "resultPayload": {
 *             "streamId": "e1f77d65-dffd-4ffe-a0a1-7a80ccc0fdff",
 *             "status": "starting"
 *         },
 *         "usage": {
 *             "elapsed": 0,
 *             "model": "OmniBaize",
 *             "inputTokens": 0,
 *             "outputTokens": 0
 *         },
 *         "sn": 1876025504971096000,
 *         "timestamp": 1789121353649
 *     }
 *
 * @apiSuccessExample {json} OmniVL - 停止流式自然语言推理应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "unit": "OmniVL",
 *         "answer": "",
 *         "thought": "",
 *         "resultPayload": {
 *             "lastError": "OmniVL returned no usable stream description",
 *             "streamId": "e1f77d65-dffd-4ffe-a0a1-7a80ccc0fdff",
 *             "capturedSegments": 22,
 *             "lastSegmentEndAt": 1789121458104,
 *             "startedAt": 1789121353854,
 *             "processedSegments": 5,
 *             "callbackFailures": 0,
 *             "droppedSegments": 13,
 *             "status": "stopping"
 *         },
 *         "usage": {
 *             "elapsed": 0,
 *             "model": "OmniBaize",
 *             "inputTokens": 0,
 *             "outputTokens": 0
 *         },
 *         "sn": 1876025504971096000,
 *         "timestamp": 1789121491146
 *     }
 *
 * @apiSuccessExample {json} OmniVL - callbackUrl 回调数据
 *     {
 *         "sequence": 5,
 *         "performance": {
 *             "elapsed": "5581",
 *             "inputTokens": 2526,
 *             "outputTokens": 9
 *         },
 *         "streamId": "4a605e54-e7d0-4b00-af4d-7c6764e1335a",
 *         "response": "无明显变化",
 *         "timestamp": 1789131476537,
 *         "timeRange": {
 *             "endOffset": 53.594,
 *             "startOffset": 45.594,
 *             "endTimestamp": 1789131470677,
 *             "startTimestamp": 1789131462677
 *         }
 *     }
 *
 * @apiSuccessExample {text} OmniPerception - callbackUrl 原始请求
 *     POST /vlm HTTP/1.1
 *     Content-Type: image/png
 *     X-VLM-Event-Id: stream-uuid:2:42
 *
 *     <PNG 二进制；iTXt 的 vlm_meta 值见下例>
 *
 * @apiSuccessExample {json} OmniPerception - PNG 的 vlm_meta JSON
 *     {
 *       "eventId":"stream-uuid:2:42","streamId":"stream-uuid","queryVersion":2,
 *       "timestamp":1789131476537,"task":"reasoning_detection","prompt":"屋顶上的水箱",
 *       "response":"<ground>水箱</ground><objects><obj0></objects>",
 *       "resultPayload":{"width":1920,"height":1080,"refs":[{"content":"水箱","boxes":[{"x1":200,"y1":100,"x2":400,"y2":300}],"points":[]}]},
 *       "performance":{"elapsed":"1250","inputTokens":100,"outputTokens":40}
 *     }
 *
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 */
