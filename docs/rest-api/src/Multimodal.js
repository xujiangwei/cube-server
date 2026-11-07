/**
 * @apiDefine Multimodal 多模态服务
 */


/**
 * @api {post} /multimodal/base/:token 多模态数据推理
 * @apiName 多模态数据推理
 * @apiGroup Multimodal
 * @apiVersion 1.0.0
 *
 * @apiDescription 分析图片、音频或视频，返回文字回答或目标位置。先创建频道并保存频道码；上传文件并确认可查询后，将文件码放入 <code>input.files</code>。也可使用服务端可访问的文件 URL。用 <code>input.content</code> 描述问题或目标，并显式选择 <code>input.unit</code>。
 *                 <div class="table-scroll" tabindex="0" role="region" aria-label="多模态模型选择"><table><thead><tr><th>模型</th><th>输入与任务选择</th><th>结果读取</th></tr></thead><tbody>
 *                 <tr><td>OmniGround</td><td>一张图片；用 task 选择目标、文字或 GUI 元素定位，mode 仅适用于该模型。</td><td>resultPayload.refs 中的 boxes 或 points。</td></tr>
 *                 <tr><td>OmniVL</td><td>一个视频文件；content 描述需要总结的内容。</td><td>answer 中的视频分析文字。</td></tr>
 *                 <tr><td>OmniMultimodal</td><td>图片等媒体与问题；可用 history 提供文字上下文。语音问题使用已上传的 WAV/MP3 文件码作为 content。</td><td>answer 中的回答。</td></tr>
 *                 <tr><td>OmniPerception</td><td>一张图片；task 选择检测、计数、区域描述、区域 OCR 或视觉问答。</td><td>answer 中的文字，以及 resultPayload.refs 中的目标框。</td></tr>
 *                 </tbody></table></div>
 *                 <p>REST 应答的文字字段为 <code>answer</code>。绘制目标框、定位点时读取 <code>resultPayload</code>，坐标以原图像素为单位，缩放显示时按返回的 width、height 换算。空 refs 表示没有返回目标位置；OCR、问答等任务仍可在 answer 中返回文字。计数结果由 answer 描述，不提供独立的 count 字段。</p>
 *                 <p>扩展参数统一放在 <code>option.parameters</code>；仅支持新任务名和字段结构，旧的 detect、ground_gui、平铺 lang/mode 等字段会被拒绝。GUI 定位选择 <code>task="gui_grounding"</code>：parameters.outputType 默认为 box，从 refs[].boxes 读取边界框；设为 point 时从 refs[].points 读取定位点。接口只返回位置，不执行界面操作。</p>
 *                 <p>区域描述与 OCR 应同时提供 bboxList 和从 0 开始的 targetRegionIndexes；vqa 直接对整图提问，省略这两个字段。只发送所选模型和任务支持的选项。下方应答展示代表性结果，文字与坐标以实际输入为准。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} channel 通道的频道码。
 * @apiBody {Object} input 多模态输入信息。
 * @apiBody {String} [input.unit=OmniGround] 模型名称；建议显式指定，取值见上方模型表。
 * @apiBody {String} [input.content] 当前提示词，省略时为 ""；不会自动下载此字段中的 URL。OmniMultimodal 还可使用语音文件码。OmniPerception 除区域任务外必须非空；OmniGround 的 text_detection 或使用 parameters.categories 的 detection 可省略；OmniVL 为空时使用内置描述提示词。
 * @apiBody {Object[]} [input.history] OmniMultimodal 的文字对话上下文，每项包含 query 和 answer；后续请求按需提交完整上下文。
 * @apiBody {String} input.history.query 每条历史对话中的用户输入。
 * @apiBody {String} input.history.answer 每条历史对话中的模型输出。
 * @apiBody {String[]} [input.files] 文件码或文件 URL 列表。OmniGround、OmniPerception 必须提供一张图片，OmniVL 必须提供一个视频；OmniMultimodal 按需提供媒体附件。
 * @apiBody {Object} [input.option] 指定的任务描述。
 * @apiBody {String} [input.option.operation=analyze] 同步分析使用 analyze；RTSP 输入默认 start，参见流式接口。
 * @apiBody {Object} [input.option.parameters] 图片任务的扩展参数；OmniVL、OmniMultimodal 仅接受空对象。
 * @apiBody {String[]} [input.option.parameters.categories] 仅用于 OmniGround 的 detection，非空类别数组，优先于 content。
 * @apiBody {String} [input.option.task] 指定的任务名称。<br/>
 *                   <code>OmniGround</code> 的任务名称。<br/>
 *                   <code>detection</code> - 检测并定位所有符合描述的目标<br/>
 *                   <code>grounding_single</code> - 检测并定位单个目标<br/>
 *                   <code>grounding_multi</code> - 检测并定位多个目标<br/>
 *                   <code>text_grounding</code> - 请找到标有指定描述的文本<br/>
 *                   <code>text_detection</code> - 检测所有文本<br/>
 *                   <code>gui_grounding</code> - 根据描述定位界面截图中的 GUI 元素，支持边界框或定位点输出<br/>
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
 * @apiBody {String} [input.option.parameters.mode] 仅用于 <code>OmniGround</code> 的图片任务。<br/><code>hybrid</code> - 默认平衡模式。<br/><code>fast</code> - 快速模式，适合低延迟或规则场景。<br/><code>slow</code> - 精准模式，适合离线高精度或复杂场景。
 * @apiBody {String} [input.option.parameters.outputType=box] <code>OmniGround</code> 的 <code>gui_grounding</code> 任务输出模式。<code>box</code> 表示输出边界框（<code>resultPayload.refs[].boxes</code>），<code>point</code> 表示输出定位点（<code>resultPayload.refs[].points</code>）。
 * @apiBody {String} [input.option.parameters.lang] <code>OmniPerception</code> 的任务语言。支持 <code>zh</code> 和 <code>en</code>，省略时使用服务端配置。
 * @apiBody {Array} [input.option.parameters.bboxList] 仅用于 <code>OmniPerception</code> 的候选边界框列表，每个元素使用原图像素坐标 <code>[x1, y1, x2, y2]</code>，最多 100 个。定位类任务可省略；<code>brief_region_caption</code> 和 <code>region_ocr</code> 应显式提供；<code>vqa</code> 不允许提供该字段。
 * @apiBody {Number[]} [input.option.parameters.targetRegionIndexes] 需要处理的候选框索引列表，索引从 0 开始且必须小于 bboxList 的长度。仅用于 <code>brief_region_caption</code> 和 <code>region_ocr</code>，使用这两个任务时必填。
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
 * @apiParamExample {json} OmniGround - detection 任务示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "content": "电脑 手机",
 *             "files": [
 *                 "unpmAGrMYphCHatmTRmoPMbrMCKGLHOlWOTHGviJSLDQyIwtSLdBTRLUpOQeMxLg"
 *             ],
 *             "option": {
 *                 "task": "detection"
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniGround - text_grounding 任务示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "content": "锦江酒店",
 *             "files": [
 *                 "https://my.domain.com/images/photo.jpg"
 *             ],
 *             "option": {
 *                 "task": "text_grounding"
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniGround - gui_grounding 边界框示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniGround",
 *             "content": "界面右上角的搜索按钮",
 *             "files": [
 *                 "https://my.domain.com/images/screenshot.png"
 *             ],
 *             "option": {
 *                 "task": "gui_grounding",
 *                 "parameters": {
 *                     "outputType": "box"
 *                 }
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniGround - gui_grounding 定位点示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniGround",
 *             "content": "界面右上角的搜索按钮",
 *             "files": [
 *                 "https://my.domain.com/images/screenshot.png"
 *             ],
 *             "option": {
 *                 "task": "gui_grounding",
 *                 "parameters": {
 *                     "outputType": "point"
 *                 }
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
 * @apiParamExample {json} OmniMultimodal - 语音提问与媒体附件
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
 * @apiParamExample {json} OmniMultimodal - 图片问答与文字上下文
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniMultimodal",
 *             "content": "根据这张工位照片，给出两条整理建议。",
 *             "files": [
 *                 "https://my.domain.com/images/desk.jpg"
 *             ],
 *             "history": [
 *                 {
 *                     "query": "建议尽量简洁，并说明原因。",
 *                     "answer": "好的，我会结合图片给出简短建议。"
 *                 }
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
 *                 "parameters": {
 *                     "lang": "zh"
 *                 }
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
 *                 "parameters": {
 *                     "lang": "zh"
 *                 }
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
 *                 "parameters": {
 *                     "lang": "zh"
 *                 }
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
 *                 "parameters": {
 *                     "lang": "zh"
 *                 }
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
 *                 "parameters": {
 *                     "lang": "zh",
 *                     "bboxList": [
 *                         [
 *                             120,
 *                             80,
 *                             560,
 *                             720
 *                         ],
 *                         [
 *                             620,
 *                             140,
 *                             980,
 *                             760
 *                         ]
 *                     ],
 *                     "targetRegionIndexes": [
 *                         0
 *                     ]
 *                 }
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
 *                 "parameters": {
 *                     "lang": "zh",
 *                     "bboxList": [
 *                         [
 *                             220,
 *                             110,
 *                             1060,
 *                             360
 *                         ]
 *                     ],
 *                     "targetRegionIndexes": [
 *                         0
 *                     ]
 *                 }
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
 *                 "parameters": {
 *                     "lang": "zh"
 *                 }
 *             }
 *         }
 *     }
 *
 * @apiSuccess {Number} sn 本次请求的流水号。
 * @apiSuccess {Number} timestamp 本次请求的时间戳（毫秒）。
 * @apiSuccess {String} unit 指定多模态单元。
 * @apiSuccess {String} answer 多模态模型生成的文本内容。
 * @apiSuccess {String} thought 多模态模型生成的思路。
 * @apiSuccess {Object} [resultPayload] 结构化结果；定位任务返回图片尺寸及目标位置，纯文字任务可能为空对象或省略。
 * @apiSuccess {Object[]} [resultPayload.refs] 目标分组列表；没有匹配位置时为空数组。
 * @apiSuccess {String} resultPayload.refs.content 结果参考数据的文本内容。<code>OmniGround</code> 输出未包含文本标签时为空字符串。
 * @apiSuccess {Object[]} resultPayload.refs.boxes 结果参考数据的边界框列表，每项为原图像素坐标 <code>{x1,y1,x2,y2}</code>，分别表示左上角和右下角。<code>gui_grounding</code> 的 <code>box</code> 模式从此字段读取定位框。
 * @apiSuccess {Number} resultPayload.refs.boxes.x1 左上角横坐标，原图像素。
 * @apiSuccess {Number} resultPayload.refs.boxes.y1 左上角纵坐标，原图像素。
 * @apiSuccess {Number} resultPayload.refs.boxes.x2 右下角横坐标，原图像素。
 * @apiSuccess {Number} resultPayload.refs.boxes.y2 右下角纵坐标，原图像素。
 * @apiSuccess {Object[]} resultPayload.refs.points 结果参考数据的点列表，每项为原图像素坐标 <code>{x,y}</code>。<code>gui_grounding</code> 的 <code>point</code> 模式从此字段读取定位点。
 * @apiSuccess {Number} resultPayload.refs.points.x 点横坐标，原图像素。
 * @apiSuccess {Number} resultPayload.refs.points.y 点纵坐标，原图像素。
 * @apiSuccess {Number} [resultPayload.width] 输入图片宽度，单位像素。
 * @apiSuccess {Number} [resultPayload.height] 输入图片高度，单位像素。
 * @apiSuccess {Object} [usage] 本次请求的使用情况，存在时读取。
 * @apiSuccess {Number} usage.inputTokens 本次请求的输入词元数量。
 * @apiSuccess {Number} usage.outputTokens 本次请求的输出词元数量。
 * @apiSuccess {String} usage.model 模型名称。
 * @apiSuccess {Number} usage.elapsed 服务端耗时，单位毫秒。
 * 
 * @apiSuccessExample {json} OmniGround - detection 应答示例
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
 * @apiSuccessExample {json} OmniGround - gui_grounding 边界框应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736205,
 *         "timestamp": 1788425020947,
 *         "unit": "OmniGround",
 *         "answer": "<box><800><100><900><200></box>",
 *         "thought": "",
 *         "resultPayload": {
 *             "refs": [{
 *                 "content": "",
 *                 "boxes": [{
 *                     "x1": 1536.0,
 *                     "y1": 108.0,
 *                     "x2": 1728.0,
 *                     "y2": 216.0
 *                 }],
 *                 "points": []
 *             }],
 *             "width": 1920,
 *             "height": 1080
 *         }
 *     }
 *
 * @apiSuccessExample {json} OmniGround - gui_grounding 定位点应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736206,
 *         "timestamp": 1788425020947,
 *         "unit": "OmniGround",
 *         "answer": "<box><850><150></box>",
 *         "thought": "",
 *         "resultPayload": {
 *             "refs": [{
 *                 "content": "",
 *                 "boxes": [],
 *                 "points": [{
 *                     "x": 1632.0,
 *                     "y": 162.0
 *                 }]
 *             }],
 *             "width": 1920,
 *             "height": 1080
 *         }
 *     }
 *
 * @apiSuccessExample {json} OmniPerception - detection 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736220,
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
 *         "sn": 887736221,
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
 * @apiSuccessExample {json} OmniPerception - counting 计数与目标框
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736210,
 *         "timestamp": 1789131476537,
 *         "unit": "OmniPerception",
 *         "answer": "共找到2辆车。<ground>车辆</ground><objects><obj0><obj1></objects>",
 *         "thought": "",
 *         "resultPayload": {
 *             "width": 1920,
 *             "height": 1080,
 *             "refs": [
 *                 {
 *                     "content": "车辆",
 *                     "boxes": [
 *                         {
 *                             "x1": 160,
 *                             "y1": 380,
 *                             "x2": 520,
 *                             "y2": 760
 *                         },
 *                         {
 *                             "x1": 820,
 *                             "y1": 400,
 *                             "x2": 1160,
 *                             "y2": 780
 *                         }
 *                     ],
 *                     "points": []
 *                 }
 *             ]
 *         }
 *     }
 *
 * @apiSuccessExample {json} OmniPerception - region_ocr 区域文字识别
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736211,
 *         "timestamp": 1789131476537,
 *         "unit": "OmniPerception",
 *         "answer": "安全出口",
 *         "thought": "",
 *         "resultPayload": {
 *             "width": 1920,
 *             "height": 1080,
 *             "refs": []
 *         }
 *     }
 *
 * @apiSuccessExample {json} OmniPerception - brief_region_caption 区域描述
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736212,
 *         "timestamp": 1789131476537,
 *         "unit": "OmniPerception",
 *         "answer": "一名穿白色上衣的人站在设备旁。",
 *         "thought": "",
 *         "resultPayload": {
 *             "width": 1920,
 *             "height": 1080,
 *             "refs": []
 *         }
 *     }
 *
 * @apiSuccessExample {json} OmniPerception - vqa 整图问答
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736213,
 *         "timestamp": 1789131476537,
 *         "unit": "OmniPerception",
 *         "answer": "画面中有人在检查设备。",
 *         "thought": "",
 *         "resultPayload": {
 *             "width": 1920,
 *             "height": 1080,
 *             "refs": []
 *         }
 *     }
 *
 * @apiSuccessExample {json} OmniPerception - detection 未检出目标
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736214,
 *         "timestamp": 1789131476537,
 *         "unit": "OmniPerception",
 *         "answer": "未发现符合描述的目标。",
 *         "thought": "",
 *         "resultPayload": {
 *             "width": 1920,
 *             "height": 1080,
 *             "refs": []
 *         }
 *     }
 *
 * @apiSuccessExample {json} OmniMultimodal - 图片问答
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736215,
 *         "timestamp": 1789131476537,
 *         "unit": "OmniMultimodal",
 *         "answer": "1. 将桌面线缆集中固定，减少缠绕。2. 将常用物品放在手边，便于取用。",
 *         "thought": ""
 *     }
 *
 * @apiError 400 请求执行失败。检查频道是否可用、模型与任务是否匹配、文件是否上传完成及参数限制；确认无误后仍失败时联系服务提供方。响应体可能为空，先判断 HTTP 状态码。
 * @apiError 401 令牌无效或设备信息不匹配；检查令牌及关联的设备请求头。
 * @apiError 403 请求体格式错误或缺少必填字段；检查 channel、input 及字符串类型的 input.content。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/multimodal/base/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
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
 */


/**
 * @api {post} /multimodal/stream/:token 流式多模态推理
 * @apiName 流式多模态推理
 * @apiGroup Multimodal
 * @apiVersion 1.0.0
 *
 * @apiDescription 持续分析一个 RTSP(S) 视频源。先创建频道，准备可用的视频地址和结果接收地址；所有启动、查询、更新和停止操作均向本路径发送 POST，用 <code>input.option.operation</code> 区分。
 *                 <div class="table-scroll" tabindex="0" role="region" aria-label="流式模型选择"><table><thead><tr><th>模型</th><th>启动条件</th><th>结果形式</th></tr></thead><tbody>
 *                 <tr><td>OmniGround</td><td>必须提供可接收 RTSP 推流的 output.url；content 可为空。</td><td>output.url 中带目标标注的视频，当前自动检测目标，不提供自定义类别筛选。</td></tr>
 *                 <tr><td>OmniVL</td><td>content 描述关注内容；建议显式提供 output.url。</td><td>向 output.url POST application/json，按片段返回文字 response。</td></tr>
 *                 <tr><td>OmniPerception</td><td>非空 content、HTTP(S) output.url；task 支持 detection、grounding_single、reasoning_detection、counting。</td><td>命中目标时向 output.url POST image/png；从 PNG 的 vlm_meta 读取 JSON。</td></tr>
 *                 </tbody></table></div>
 *                 <p>接入顺序：start 后保存 <code>resultPayload.streamId</code> → status 确认运行状态 → 接收结果 → 按需 update 提示词 → stop → 继续 status，直到 stopped 或 failed。启动返回 starting 只表示已创建会话，stopping 表示仍在停止。不要用未收到回调判断会话是否结束；检查 status、lastError 和回调计数。sn 是本次请求的流水号，不能代替字符串 streamId。</p>
 *                 <p>后续操作使用创建会话的用户身份、频道和模型，同一频道按顺序发送请求。status、stop 的 content 可省略或传空字符串，files/history 可省略或传 []；OmniVL、OmniPerception 的 update 用非空 content 替换提示词。启动时 option 使用 operation、task 和 output 对象，output 中使用 url、headers、intervalSeconds；控制请求的 option 仅使用 operation、streamId。流请求 parameters 仅接受空对象；控制请求不能携带 task、parameters 或 output。更新后用 queryVersion、activeQueryVersion 判断新提示词是否已应用。</p>
 *                 <p>OmniVL 的 JSON 回调包含 sequence、streamId、response、timestamp、timeRange 和 performance；timeRange 的 Offset 为秒，Timestamp 为毫秒，performance.elapsed 为毫秒。按 streamId、sequence 关联片段。</p>
 *                 <p>OmniPerception 仅在返回非空目标框时发送 PNG；未命中时最多每秒检测一帧，命中后至少间隔 intervalSeconds 秒再检测。接收端按二进制读取图片，vlm_meta 为 UTF-8 JSON，含 eventId、streamId、queryVersion、timestamp、task、prompt、response、resultPayload、performance。以 X-VLM-Event-Id 或元数据中的 eventId 去重；成功保存后返回 2xx。20 MiB 以上 PNG 不推送。网络错误、408、429、5xx 最多尝试3次（含首次），不跟随重定向。下方示例展示同一会话的完整控制与回调。</p>
 *
 * @apiParam {String} token 授权令牌。
 *
 * @apiBody {String} channel 通道的频道码。
 * @apiBody {Object} input 多模态输入信息。
 * @apiBody {String} input.unit 指定使用的多模态单元名称。<br/><code>OmniGround</code> - 流式分类。<br/><code>OmniVL</code> - 流式自然语言推理。<br/><code>OmniPerception</code> - 命中原帧 PNG 回调。
 * @apiBody {String} [input.content] 提示词，省略时为 ""。OmniPerception 的 start、update 必须非空；OmniVL 的 update 必须非空；status、stop 应省略或为空字符串。
 * @apiBody {String[]} [input.files] 启动时填写且仅填写一个 RTSP(S) 地址；status、update、stop 时应省略或传空数组 []。
 * @apiBody {Object} input.option 流式任务的选项。
 * @apiBody {String} [input.option.operation=start] start 创建会话；status 查询状态；stop 停止会话；OmniVL、OmniPerception 还支持 update 替换提示词。status、update、stop 必须同时提供 streamId，input.files 传 []。
 * @apiBody {Object} [input.option.output] 仅用于 start 的输出配置；不再支持平铺的 callbackUrl、callbackHeaders、outputUrl。
 * @apiBody {String} [input.option.output.url] OmniGround 启动必填 RTSP 输出地址；OmniPerception 启动必填 HTTP(S) PNG 回调地址；OmniVL 可选 HTTP(S) JSON 回调地址，省略时使用静态配置及服务事件通道。
 * @apiBody {Object} [input.option.output.headers] OmniVL、OmniPerception 的回调请求头，键和值均为字符串且禁止换行。
 * @apiBody {String} [input.option.task] OmniGround 仅 detection；OmniVL 仅 understanding；OmniPerception 支持 detection、grounding_single、reasoning_detection、counting，默认 detection；启动后不可修改。
 * @apiBody {Number} [input.option.output.intervalSeconds] <code>OmniPerception</code> 的回调间隔，不小于 1 的整数；省略时使用静态配置，只可在启动时指定。
 * @apiBody {String} [input.option.streamId] 启动任务时返回的 ID；查询、更新和停止时必填。
 *
 * @apiParamExample {json} OmniPerception - 第一步：启动会话
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniPerception",
 *             "content": "屋顶上的水箱",
 *             "files": [
 *                 "rtsp://211.157.179.37:8554/live"
 *             ],
 *             "option": {
 *                 "operation": "start",
 *                 "task": "reasoning_detection",
 *                 "output": {
 *                     "url": "https://receiver.example.com/vlm",
 *                     "intervalSeconds": 5
 *                 }
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniPerception - 第二步：POST 查询状态
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniPerception",
 *             "content": "",
 *             "files": [],
 *             "option": {
 *                 "operation": "status",
 *                 "streamId": "8f03be6f-0dbd-4b3a-9d8c-3f189bb57f31"
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniPerception - 按需更新提示词
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniPerception",
 *             "content": "屋顶上的热水器",
 *             "files": [],
 *             "option": {
 *                 "operation": "update",
 *                 "streamId": "8f03be6f-0dbd-4b3a-9d8c-3f189bb57f31"
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniPerception - 停止会话后继续查询终态
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniPerception",
 *             "content": "",
 *             "files": [],
 *             "option": {
 *                 "operation": "stop",
 *                 "streamId": "8f03be6f-0dbd-4b3a-9d8c-3f189bb57f31"
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniGround - 启动流式分类任务示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniGround",
 *             "content": "",
 *             "files": [
 *                 "rtsp://211.157.179.37:8554/live"
 *             ],
 *             "option": {
 *                 "operation": "start",
 *                 "output": {
 *                     "url": "rtsp://211.157.179.37:8554/live/grounding"
 *                 }
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniGround - POST 查询会话状态
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniGround",
 *             "content": "",
 *             "files": [],
 *             "option": {
 *                 "operation": "status",
 *                 "streamId": "e1007214-9734-448c-a514-ad4a2f22a69b"
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} OmniGround - 停止流式分类任务示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
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
 *                 "rtsp://211.157.179.37:8554/live"
 *             ],
 *             "option": {
 *                 "output": {
 *                     "url": "http://211.157.179.37:9410/omni/vl/callback"
 *                 }
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
 * @apiParamExample {json} OmniVL - POST 查询会话状态
 *     {
 *         "channel": "mnxRSaLdPuHEVaKX",
 *         "input": {
 *             "unit": "OmniVL",
 *             "content": "",
 *             "files": [],
 *             "option": {
 *                 "operation": "status",
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
 * @apiSuccess {String} answer 多模态模型生成的文本内容。流式任务的启动/停止应答中为空字符串，识别结果通过输出视频流或 <code>output.url</code> 持续推送。
 * @apiSuccess {String} thought 多模态模型生成的思路。流式任务的启动/停止应答中为空字符串。
 * @apiSuccess {Object} resultPayload 流式任务的状态快照，随任务推进而变化。
 * @apiSuccess {String} resultPayload.streamId 流式会话 ID，按字符串保存；status、update、stop 时作为 input.option.streamId 传入。
 * @apiSuccess {String} resultPayload.status 会话状态：starting 启动中；buffering 准备画面中；running 运行中；reconnecting 重连中；updating 等待应用新提示词；stopping 停止中；stopped 已停止；failed 失败。仅 stopped、failed 为终态，其他状态继续查询。
 * @apiSuccess {Number} resultPayload.startedAt 任务启动时间戳（毫秒）。
 * @apiSuccess {Number} [resultPayload.stoppedAt] 任务停止时间戳（毫秒）。任务尚未停止时为 <code>null</code>。
 * @apiSuccess {String} [resultPayload.lastError] 任务最近一次的错误信息。无错误时为 <code>null</code>。
 * @apiSuccess {Object} [usage] 本次请求的使用情况，存在时读取。
 * @apiSuccess {Number} usage.elapsed 本次请求的服务端耗时（毫秒）。
 * @apiSuccess {String} usage.model 本次任务使用的模型名称。
 * @apiSuccess {Number} usage.inputTokens 本次请求的输入词元数量。
 * @apiSuccess {Number} usage.outputTokens 本次请求的输出词元数量。
 * @apiSuccess {Object} [resultPayload.input] 输入视频流信息。
 * @apiSuccess {String} resultPayload.input.url 输入视频流地址，即请求 <code>input.files</code> 中传入的地址。应答中的地址会隐去账号密码。
 * @apiSuccess {String} resultPayload.input.status 输入视频连接状态，例如 starting、probing、connecting、running、reconnecting、stopped；会话是否结束应以外层 resultPayload.status 为准。
 * @apiSuccess {Number} [resultPayload.input.width] 输入画面宽度。尚未探测到时为 <code>null</code>。
 * @apiSuccess {Number} [resultPayload.input.height] 输入画面高度。尚未探测到时为 <code>null</code>。
 * @apiSuccess {Number} [resultPayload.input.fps] 输入视频流帧率。尚未探测到时为 <code>null</code>。
 * @apiSuccess {Object} [resultPayload.output] 标注后输出视频流的信息（仅 <code>OmniGround</code> 任务返回）。
 * @apiSuccess {String} resultPayload.output.url 输出视频流的推送地址，即请求 <code>input.option.output.url</code> 中传入的地址。应答中的地址会隐去账号密码。
 * @apiSuccess {String} resultPayload.output.status 输出视频发布状态，例如 starting、running、reconnecting、stopped；会话是否结束应以外层 resultPayload.status 为准。
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
 * @apiSuccess {Array} [resultPayload.categories] OmniGround 当前返回空数组，不用于自定义类别筛选。
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
 * @apiSuccess {Number} [resultPayload.intervalSeconds] OmniPerception 命中后的检测间隔，单位秒。
 * @apiSuccess {Number} [resultPayload.matchedFrames] OmniPerception 命中帧数。
 * @apiSuccess {Number} [resultPayload.postedFrames] OmniPerception 成功上传 PNG 的帧数。
 * @apiSuccess {Number} [resultPayload.lastMatchedAt] OmniPerception 最近命中帧的捕获时间戳（毫秒），未命中时为 null。
 * @apiSuccess {Number} [resultPayload.idleTimeoutSeconds] 持续未收到视频数据时自动结束会话的时限，单位秒。
 * @apiSuccess {Number} [resultPayload.lastDataAt] 最近收到视频数据的时间戳（毫秒），尚未收到时为 null。
 * @apiSuccess {String} [resultPayload.stopReason] 结束原因，例如 requested（主动停止）、idleTimeout（长时间无视频数据）、failed（失败）；尚未结束时可能为 null。
 *
 * @apiSuccessExample {json} OmniPerception - 启动应答：保存 streamId
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736230,
 *         "timestamp": 1789131470000,
 *         "unit": "OmniPerception",
 *         "answer": "",
 *         "thought": "",
 *         "resultPayload": {
 *             "streamId": "8f03be6f-0dbd-4b3a-9d8c-3f189bb57f31",
 *             "status": "starting",
 *             "queryVersion": 1,
 *             "activeQueryVersion": 0,
 *             "task": "reasoning_detection",
 *             "intervalSeconds": 5,
 *             "idleTimeoutSeconds": 10,
 *             "stopReason": null,
 *             "startedAt": 1789131470000,
 *             "stoppedAt": null,
 *             "lastDataAt": null,
 *             "lastError": null,
 *             "input": {
 *                 "url": "rtsp://211.157.179.37:8554/live",
 *                 "status": "starting",
 *                 "width": null,
 *                 "height": null,
 *                 "fps": null
 *             },
 *             "effectiveFps": null,
 *             "decodedFrames": 0,
 *             "droppedFrames": 0,
 *             "inferredFrames": 0,
 *             "matchedFrames": 0,
 *             "postedFrames": 0,
 *             "callbackFailures": 0,
 *             "inferenceFailures": 0,
 *             "lastMatchedAt": null
 *         }
 *     }
 *
 * @apiSuccessExample {json} OmniPerception - status 应答：运行中且已发送命中图片
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736231,
 *         "timestamp": 1789131478000,
 *         "unit": "OmniPerception",
 *         "answer": "",
 *         "thought": "",
 *         "resultPayload": {
 *             "streamId": "8f03be6f-0dbd-4b3a-9d8c-3f189bb57f31",
 *             "status": "running",
 *             "queryVersion": 1,
 *             "activeQueryVersion": 1,
 *             "task": "reasoning_detection",
 *             "intervalSeconds": 5,
 *             "idleTimeoutSeconds": 10,
 *             "stopReason": null,
 *             "startedAt": 1789131470000,
 *             "stoppedAt": null,
 *             "lastDataAt": 1789131477900,
 *             "lastError": null,
 *             "input": {
 *                 "url": "rtsp://211.157.179.37:8554/live",
 *                 "status": "running",
 *                 "width": 1920,
 *                 "height": 1080,
 *                 "fps": 25
 *             },
 *             "effectiveFps": 25,
 *             "decodedFrames": 180,
 *             "droppedFrames": 0,
 *             "inferredFrames": 2,
 *             "matchedFrames": 1,
 *             "postedFrames": 1,
 *             "callbackFailures": 0,
 *             "inferenceFailures": 0,
 *             "lastMatchedAt": 1789131476537
 *         }
 *     }
 *
 * @apiSuccessExample {json} OmniPerception - update 应答：等待应用第2版提示词
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736232,
 *         "timestamp": 1789131480000,
 *         "unit": "OmniPerception",
 *         "answer": "",
 *         "thought": "",
 *         "resultPayload": {
 *             "streamId": "8f03be6f-0dbd-4b3a-9d8c-3f189bb57f31",
 *             "status": "updating",
 *             "queryVersion": 2,
 *             "activeQueryVersion": 1,
 *             "task": "reasoning_detection",
 *             "intervalSeconds": 5,
 *             "idleTimeoutSeconds": 10,
 *             "stopReason": null,
 *             "startedAt": 1789131470000,
 *             "stoppedAt": null,
 *             "lastDataAt": 1789131477900,
 *             "lastError": null,
 *             "input": {
 *                 "url": "rtsp://211.157.179.37:8554/live",
 *                 "status": "running",
 *                 "width": 1920,
 *                 "height": 1080,
 *                 "fps": 25
 *             },
 *             "effectiveFps": 25,
 *             "decodedFrames": 180,
 *             "droppedFrames": 0,
 *             "inferredFrames": 2,
 *             "matchedFrames": 1,
 *             "postedFrames": 1,
 *             "callbackFailures": 0,
 *             "inferenceFailures": 0,
 *             "lastMatchedAt": 1789131476537
 *         }
 *     }
 *
 * @apiSuccessExample {json} OmniPerception - stop 应答：停止中，仍须查询
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736233,
 *         "timestamp": 1789131490000,
 *         "unit": "OmniPerception",
 *         "answer": "",
 *         "thought": "",
 *         "resultPayload": {
 *             "streamId": "8f03be6f-0dbd-4b3a-9d8c-3f189bb57f31",
 *             "status": "stopping",
 *             "queryVersion": 2,
 *             "activeQueryVersion": 2,
 *             "task": "reasoning_detection",
 *             "intervalSeconds": 5,
 *             "idleTimeoutSeconds": 10,
 *             "stopReason": "requested",
 *             "startedAt": 1789131470000,
 *             "stoppedAt": null,
 *             "lastDataAt": 1789131477900,
 *             "lastError": null,
 *             "input": {
 *                 "url": "rtsp://211.157.179.37:8554/live",
 *                 "status": "running",
 *                 "width": 1920,
 *                 "height": 1080,
 *                 "fps": 25
 *             },
 *             "effectiveFps": 25,
 *             "decodedFrames": 180,
 *             "droppedFrames": 0,
 *             "inferredFrames": 2,
 *             "matchedFrames": 1,
 *             "postedFrames": 1,
 *             "callbackFailures": 0,
 *             "inferenceFailures": 0,
 *             "lastMatchedAt": 1789131476537
 *         }
 *     }
 *
 * @apiSuccessExample {json} OmniPerception - status 应答：已停止
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736234,
 *         "timestamp": 1789131492000,
 *         "unit": "OmniPerception",
 *         "answer": "",
 *         "thought": "",
 *         "resultPayload": {
 *             "streamId": "8f03be6f-0dbd-4b3a-9d8c-3f189bb57f31",
 *             "status": "stopped",
 *             "queryVersion": 2,
 *             "activeQueryVersion": 2,
 *             "task": "reasoning_detection",
 *             "intervalSeconds": 5,
 *             "idleTimeoutSeconds": 10,
 *             "stopReason": "requested",
 *             "startedAt": 1789131470000,
 *             "stoppedAt": 1789131491000,
 *             "lastDataAt": 1789131477900,
 *             "lastError": null,
 *             "input": {
 *                 "url": "rtsp://211.157.179.37:8554/live",
 *                 "status": "stopped",
 *                 "width": 1920,
 *                 "height": 1080,
 *                 "fps": 25
 *             },
 *             "effectiveFps": 25,
 *             "decodedFrames": 180,
 *             "droppedFrames": 0,
 *             "inferredFrames": 2,
 *             "matchedFrames": 1,
 *             "postedFrames": 1,
 *             "callbackFailures": 0,
 *             "inferenceFailures": 0,
 *             "lastMatchedAt": 1789131476537
 *         }
 *     }
 *
 * @apiSuccessExample {json} OmniPerception - status 应答：失败终态（另一种结束情况）
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 887736235,
 *         "timestamp": 1789131492000,
 *         "unit": "OmniPerception",
 *         "answer": "",
 *         "thought": "",
 *         "resultPayload": {
 *             "streamId": "8f03be6f-0dbd-4b3a-9d8c-3f189bb57f31",
 *             "status": "failed",
 *             "queryVersion": 1,
 *             "activeQueryVersion": 0,
 *             "task": "reasoning_detection",
 *             "intervalSeconds": 5,
 *             "idleTimeoutSeconds": 10,
 *             "stopReason": "failed",
 *             "startedAt": 1789131470000,
 *             "stoppedAt": 1789131491000,
 *             "lastDataAt": null,
 *             "lastError": "Unable to open input stream",
 *             "input": {
 *                 "url": "rtsp://211.157.179.37:8554/live",
 *                 "status": "stopped",
 *                 "width": null,
 *                 "height": null,
 *                 "fps": null
 *             },
 *             "effectiveFps": null,
 *             "decodedFrames": 0,
 *             "droppedFrames": 0,
 *             "inferredFrames": 0,
 *             "matchedFrames": 0,
 *             "postedFrames": 0,
 *             "callbackFailures": 0,
 *             "inferenceFailures": 0,
 *             "lastMatchedAt": null
 *         }
 *     }
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
 *                 "url": "rtsp://211.157.179.37:8554/live/grounding",
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
 *                 "url": "rtsp://211.157.179.37:8554/live",
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
 *         "sn": 887736222,
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
 *                 "url": "rtsp://211.157.179.37:8554/live/grounding",
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
 *                 "url": "rtsp://211.157.179.37:8554/live",
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
 *         "sn": 887736223,
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
 *         "sn": 887736224,
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
 *         "sn": 887736224,
 *         "timestamp": 1789121491146
 *     }
 *
 * @apiSuccessExample {json} OmniVL - output.url 回调数据
 *     {
 *         "sequence": 5,
 *         "performance": {
 *             "elapsed": "5581",
 *             "inputTokens": 2526,
 *             "outputTokens": 9
 *         },
 *         "streamId": "e1f77d65-dffd-4ffe-a0a1-7a80ccc0fdff",
 *         "response": "无明显变化",
 *         "timestamp": 1789121413029,
 *         "timeRange": {
 *             "endOffset": 53.594,
 *             "startOffset": 45.594,
 *             "endTimestamp": 1789121407448,
 *             "startTimestamp": 1789121399448
 *         }
 *     }
 *
 * @apiSuccessExample {text} OmniPerception - output.url 原始请求
 *     POST /vlm HTTP/1.1
 *     Content-Type: image/png
 *     X-VLM-Event-Id: 8f03be6f-0dbd-4b3a-9d8c-3f189bb57f31:1:1
 *
 *     <PNG 二进制；iTXt 的 vlm_meta 值见下例>
 *
 * @apiSuccessExample {json} OmniPerception - PNG 的 vlm_meta JSON
 *     {
 *         "eventId": "8f03be6f-0dbd-4b3a-9d8c-3f189bb57f31:1:1",
 *         "streamId": "8f03be6f-0dbd-4b3a-9d8c-3f189bb57f31",
 *         "queryVersion": 1,
 *         "timestamp": 1789131476537,
 *         "task": "reasoning_detection",
 *         "prompt": "屋顶上的水箱",
 *         "response": "<ground>水箱</ground><objects><obj0></objects>",
 *         "resultPayload": {
 *             "width": 1920,
 *             "height": 1080,
 *             "refs": [
 *                 {
 *                     "content": "水箱",
 *                     "boxes": [
 *                         {
 *                             "x1": 200,
 *                             "y1": 100,
 *                             "x2": 400,
 *                             "y2": 300
 *                         }
 *                     ],
 *                     "points": []
 *                 }
 *             ]
 *         },
 *         "performance": {
 *             "elapsed": "1250",
 *             "inputTokens": 100,
 *             "outputTokens": 40
 *         }
 *     }
 *
 * @apiSuccessExample {python} OmniPerception - 读取已保存的回调 PNG（需要 Pillow）
 *     import json
 *     from PIL import Image
 *
 *     # 接收端先以二进制保存 HTTP 请求体为 callback.png。
 *     # 实际服务按 eventId 持久化去重，成功保存后返回 2xx。
 *     with Image.open("callback.png") as image:
 *         image.load()
 *         meta = json.loads(image.info["vlm_meta"])
 *         print(meta["eventId"], meta["streamId"], meta["queryVersion"])
 *         print(meta["response"])
 *         for ref in meta["resultPayload"].get("refs", []):
 *             for box in ref.get("boxes", []):
 *                 print(ref["content"], box["x1"], box["y1"], box["x2"], box["y2"])
 *
 * @apiError 400 请求执行失败。启动时检查频道、视频源与结果接收地址是否可用；控制时检查 streamId、创建会话的用户身份及参数限制。响应体可能为空，先判断 HTTP 状态码；持续失败时联系服务提供方。
 * @apiError 401 令牌无效或设备信息不匹配；检查令牌及关联的设备请求头。
 * @apiError 403 请求体格式错误或缺少必填字段；检查 channel、input 及字符串类型的 input.content。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/multimodal/stream/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "channel": "mnxRSaLdPuHEVaKY",
 *         "input": {
 *             "unit": "OmniPerception",
 *             "content": "屋顶上的水箱",
 *             "files": [
 *                 "rtsp://211.157.179.37:8554/live"
 *             ],
 *             "option": {
 *                 "operation": "start",
 *                 "task": "reasoning_detection",
 *                 "output": {
 *                     "url": "https://receiver.example.com/vlm",
 *                     "intervalSeconds": 5
 *                 }
 *             }
 *         }
 *     }
 *
 */
