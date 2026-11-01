/**
 * @apiDefine Audio_Processing 音频处理
 */

/**
 * @api {post} /aigc/speech/recognition/:token 自动语音识别
 * @apiName 自动语音识别
 * @apiGroup Audio_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 将音频转换为文字，适用于语音转写、访谈整理和字幕内容提取。先上传音频取得文件码，或提供服务端可访问的音频 URL；<code>fileCode</code> 与 <code>fileUrl</code> 二选一。
 *                 <p>默认异步提交。保存应答中的 <code>queryCode</code>，调用“自动语音识别结果查询”，将其填入查询参数 <code>qc</code>。<code>stateCode=7</code> 时继续等待，<code>0</code> 时读取 <code>result.text</code>；其他值按任务异常处理。</p>
 *                 <p>需要本次请求等待结果时设置 <code>sync=true</code>，并为长音频预留更长的客户端超时时间；仍应检查返回状态。同一文件需要重新识别时设置 <code>reset=true</code>。</p>
 *
 * @apiParam {String} token 授权令牌。
 *
 * @apiBody {String} [fileCode] 音频文件的文件码。与<code>fileUrl</code>参数二选一。
 * @apiBody {String} [fileUrl] 音频文件的链接。与<code>fileCode</code>参数二选一。
 * @apiBody {Boolean} [sync] 是否同步返回识别结果。默认值：<code>false</code>。
 * @apiBody {Boolean} [reset] 当该参数设置为<code>true</code>时将忽略之前该文件的识别数据，重新执行识别。默认值：<code>false</code>。
 *
 *
 * @apiParamExample {json} 请求示例（使用文件码）
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx"
 *     }
 * @apiParamExample {json} 请求示例（使用文件链接）
 *     {
 *         "fileUrl": "https://mysite.com/files/2025/10/speech_8731.mp3"
 *     }
 *
 *
 * @apiParamExample {json} 同步请求示例（使用文件码）
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *         "sync": true
 *     }
 * @apiSuccess {String} [queryCode] 操作查询码。同步模式下不返回该字段。
 * @apiSuccess {Number} stateCode 操作状态码。
 * @apiSuccess {Number} timestamp 提交任务执行时的时间戳。
 * @apiSuccess {Object} [result] 识别结果。
 * @apiSuccess {String} [result.text] 识别结果的文本内容。
 * @apiSuccess {Number} [result.duration] 以秒为单位的音频时长。
 * @apiSuccess {String} [result.lang] 音频文件的语言标识。例如：中文标识为<code>zh</code>，英文标识为<code>en</code> 。
 * @apiSuccess {Array}  [result.words] 识别文本内容的分词数组。
 * @apiSuccess {Number} [result.elapsed] 以毫秒为单位的识别耗时。
 * @apiSuccess {Object} [result.file] 音频文件标签。
 * @apiSuccess {Number} result.file.id 文件标签 ID。
 * @apiSuccess {String} result.file.domain 文件所属域。
 * @apiSuccess {String} result.file.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} result.file.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} result.file.fileName 原始文件名。
 * @apiSuccess {Number} result.file.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} result.file.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.file.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.file.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} result.file.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [result.file.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [result.file.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [result.file.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [result.file.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [result.file.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [result.file.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [result.file.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 *
 * @apiSuccessExample {json} 应答示例（正在识别）
 *     HTTP/1.1 200 OK
 *     {
 *         "queryCode": "GmrPWzCNNlbWlIipqBhdNIkpNgbicouP",
 *         "stateCode": 7,
 *         "timestamp": 1683613816824
 *     }
 * 
 *
 *
 * @apiSuccessExample {json} 应答示例（识别结果）
 *     HTTP/1.1 200 OK
 *     {
 *         "stateCode": 0,
 *         "timestamp": 1683613816824,
 *         "result": {
 *             "text": "与用户的思维、想法有关。",
 *             "duration": 172.3,
 *             "lang": "zh",
 *             "words": [
 *                 "与",
 *                 "用户",
 *                 "的",
 *                 "思维",
 *                 "想法",
 *                 "有关"
 *             ],
 *             "elapsed": 2547,
 *             "file": {
 *                 "id": 2237345138,
 *                 "ownerId": 22003303,
 *                 "domain": "default_domain",
 *                 "fileName": "consultation_01.mp3",
 *                 "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *                 "fileSize": 7702093,
 *                 "lastModified": 1774939042000,
 *                 "fileType": "mp3",
 *                 "completedTime": 1774939043013,
 *                 "expiryTime": 3351739043013,
 *                 "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=mp3",
 *                 "md5": "bbb1288d78a11a06f6dda7a1daea8801",
 *                 "sha1": "786cd3aedb41b934960889c1bfbf9158bb8841a0"
 *             }
 *         },
 *         "queryCode": "GmrPWzCNNlbWlIipqBhdNIkpNgbicouP"
 *     }
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 * @apiError 404 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/speech/recognition/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx"
 *     }
 */


/**
 * @api {get} /aigc/speech/recognition/:token 自动语音识别结果查询
 * @apiName 自动语音识别结果查询
 * @apiGroup Audio_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 查询已提交的语音转写任务。<code>qc</code> 使用提交接口返回的 <code>queryCode</code>，请保留原值。
 *                 <p><code>stateCode=7</code> 表示尚未完成，可间隔数秒重查；<code>0</code> 表示完成，从 <code>result.text</code> 读取全文，从 <code>result.words</code> 读取分词。<code>duration</code> 的单位为秒，<code>elapsed</code> 的单位为毫秒。HTTP 404 表示未找到任务，应检查查询码。</p>
 *
 * @apiParam {String} token 授权令牌。
 *
 * @apiQuery {String} qc 待查询任务的查询码，即提交任务时返回的 <code>queryCode</code> 。同时接受 <code>code</code> 作为别名。
 *
 * @apiSuccess {String} queryCode 操作查询码。
 * @apiSuccess {Number} stateCode 操作状态码。
 *                                <p><span class="default-value">正在处理：</span><code>7</code></p>
 *                                <p><span class="default-value">处理完成：</span><code>0</code></p>
 * @apiSuccess {Number} timestamp 提交任务执行时的时间戳。
 * @apiSuccess {Object} [result] 识别结果。
 * @apiSuccess {String} [result.text] 识别结果的文本内容。
 * @apiSuccess {Number} [result.duration] 以秒为单位的音频时长。
 * @apiSuccess {String} [result.lang] 音频文件的语言标识。例如：中文标识为<code>zh</code>，英文标识为<code>en</code> 。
 * @apiSuccess {Array}  [result.words] 识别文本内容的分词数组。
 * @apiSuccess {Number} [result.elapsed] 以毫秒为单位的识别耗时。
 * @apiSuccess {Object} [result.file] 音频文件标签。
 * @apiSuccess {Number} result.file.id 文件标签 ID。
 * @apiSuccess {String} result.file.domain 文件所属域。
 * @apiSuccess {String} result.file.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} result.file.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} result.file.fileName 原始文件名。
 * @apiSuccess {Number} result.file.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} result.file.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.file.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.file.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} result.file.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [result.file.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [result.file.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [result.file.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [result.file.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [result.file.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [result.file.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [result.file.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 *
 * @apiSuccessExample {json} 应答示例（正在识别）
 *     HTTP/1.1 200 OK
 *     {
 *         "queryCode": "GmrPWzCNNlbWlIipqBhdNIkpNgbicouP",
 *         "stateCode": 7,
 *         "timestamp": 1683613816824
 *     }
 * 
 *
 *
 * @apiSuccessExample {json} 应答示例（识别结果）
 *     HTTP/1.1 200 OK
 *     {
 *         "queryCode": "GmrPWzCNNlbWlIipqBhdNIkpNgbicouP",
 *         "stateCode": 0,
 *         "timestamp": 1683613816824,
 *         "result": {
 *             "text": "与用户的思维、想法有关。",
 *             "duration": 172.3,
 *             "lang": "zh",
 *             "words": [
 *                 "与",
 *                 "用户",
 *                 "的",
 *                 "思维",
 *                 "想法",
 *                 "有关"
 *             ],
 *             "elapsed": 2547,
 *             "file": {
 *                 "id": 2237345138,
 *                 "ownerId": 22003303,
 *                 "domain": "default_domain",
 *                 "fileName": "consultation_01.mp3",
 *                 "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *                 "fileSize": 7702093,
 *                 "lastModified": 1774939042000,
 *                 "fileType": "mp3",
 *                 "completedTime": 1774939043013,
 *                 "expiryTime": 3351739043013,
 *                 "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=mp3",
 *                 "md5": "bbb1288d78a11a06f6dda7a1daea8801",
 *                 "sha1": "786cd3aedb41b934960889c1bfbf9158bb8841a0"
 *             }
 *         }
 *     }
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 * @apiError 404 未找到指定文件的识别任务。
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/aigc/speech/recognition/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?qc=GmrPWzCNNlbWlIipqBhdNIkpNgbicouP
 */


/**
 * @api {post} /aigc/speech/diarization/:token 说话者语音多态识别
 * @apiName 说话者语音多态识别
 * @apiGroup Audio_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 区分音频中的不同说话者，获取各语音片段的文字、情绪和说话节奏，适合访谈、咨询与会议录音分析。先上传文件取得文件码，或提供服务端可访问的音频 URL。
 *                 <p>提交成功后保存 <code>queryCode</code>，调用“说话者语音多态识别结果查询”，将其填入 <code>qc</code>。任务耗时与音频长度有关，处理期间复用查询接口；需要重新分析同一文件时设置 <code>reset=true</code>。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} [fileCode] 语音文件的文件码。与<code>fileUrl</code>参数二选一。
 * @apiBody {String} [fileUrl] 语音文件的链接。与<code>fileCode</code>参数二选一。
 * @apiBody {Boolean} [reset] 当该参数设置为<code>true</code>时将忽略之前该文件的识别数据，重新执行识别。默认值：<code>false</code>。
 *
 *
 * @apiParamExample {json} 请求示例（使用文件码）
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx"
 *     }
 * @apiParamExample {json} 请求示例（使用文件链接）
 *     {
 *         "fileUrl": "https://mysite.com/files/2025/10/speech_8731.mp3"
 *     }
 *
 * @apiSuccess {String} queryCode 数据查询码。
 * @apiSuccess {Number} stateCode 操作状态码。
 *                                <p><span class="default-value">正在处理：</span><code>7</code></p>
 *                                <p><span class="default-value">处理完成：</span><code>0</code></p>
 * @apiSuccess {Number} timestamp 提交任务执行时的时间戳。
 *
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "queryCode": "GmrPWzCNNlbWlIipqBhdNIkpNgbicouP",
 *         "stateCode": 7,
 *         "timestamp": 1693613816824
 *     }
 *
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 * @apiError 404 未找到指定文件的识别任务。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/speech/diarization/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx"
 *     }
 */


/**
 * @api {get} /aigc/speech/diarization/:token 说话者语音多态识别结果查询
 * @apiName 说话者语音多态识别结果查询
 * @apiGroup Audio_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 使用提交接口返回的 <code>queryCode</code> 查询说话者分析结果。<code>stateCode=7</code> 时继续等待，<code>0</code> 时读取 <code>result</code>，其他值按任务异常处理。
 *                 <p>按 <code>tracks[]</code> 的说话人标识和 <code>segment.start/end</code> 展示语音片段；时间单位为秒。片段文字位于 <code>recognition.text</code>，汇总数据位于 <code>indicator.speakers[]</code>。示例保留一个片段与一位说话人的指标，实际返回数量以音频内容为准。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {String} qc 数据查询码。同时接受 <code>code</code> 作为别名。
 *
 * @apiSuccess {String} queryCode 数据查询码。
 * @apiSuccess {Number} stateCode 操作状态码。
 *                                <p><span class="default-value">正在处理：</span><code>7</code></p>
 *                                <p><span class="default-value">处理完成：</span><code>0</code></p>
 * @apiSuccess {Number} timestamp 提交任务执行时的时间戳。
 * @apiSuccess {Object} [result] 预测结果。
 * @apiSuccess {Number} [result.id] 结果ID。
 * @apiSuccess {String} [result.title] 语音数据标题。
 * @apiSuccess {String} [result.domain] 语音数据所属的域。
 * @apiSuccess {Number} [result.duration] 以秒为单位的语音时长。
 * @apiSuccess {Number} [result.elapsed] 以毫秒为单位的识别耗时。
 * @apiSuccess {String} [result.fileCode] 语音文件的文件码。
 * @apiSuccess {String} [result.remark] 该处理结果的备注信息。
 * @apiSuccess {Object} [result.file] 语音文件标签数据。
 * @apiSuccess {Number} result.file.id 文件标签 ID。
 * @apiSuccess {String} result.file.domain 文件所属域。
 * @apiSuccess {String} result.file.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} result.file.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} result.file.fileName 原始文件名。
 * @apiSuccess {Number} result.file.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} result.file.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.file.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.file.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} result.file.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [result.file.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [result.file.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [result.file.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [result.file.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [result.file.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [result.file.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [result.file.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 * @apiSuccess {Array}  [result.tracks] 按照声纹特征分割的音轨数组。
 * @apiSuccess {String} [result.tracks.track] 音轨顺序标识。
 * @apiSuccess {String} [result.tracks.label] 音轨说话者标签。
 * @apiSuccess {String} [result.tracks.display] 音轨显示名。
 * @apiSuccess {Object} [result.tracks.segment] 语音段落信息。
 * @apiSuccess {Number} [result.tracks.segment.start] 语音段落开始时间，单位：秒。
 * @apiSuccess {Number} [result.tracks.segment.end] 语音段落结束时间，单位：秒。
 * @apiSuccess {Number} [result.tracks.segment.duration] 语音段落时长，单位：秒。
 * @apiSuccess {Object} [result.tracks.emotion] 语音情绪信息。
 * @apiSuccess {Number} [result.tracks.emotion.elapsed] 以毫秒为单位的情绪预测耗时。
 * @apiSuccess {Object} [result.tracks.emotion.emotion] 情绪描述。
 * @apiSuccess {String} [result.tracks.emotion.emotion.name] 情绪名称。
 * @apiSuccess {String} [result.tracks.emotion.emotion.primaryWord] 情绪主描述词。
 * @apiSuccess {String} [result.tracks.emotion.emotion.secondaryWord] 情绪次描述词。
 * @apiSuccess {Number} [result.tracks.emotion.score] 情绪得分。
 * @apiSuccess {Number} [result.tracks.emotion.duration] 语音时长，单位：秒。
 * @apiSuccess {Object} [result.tracks.recognition] 音轨识别信息。
 * @apiSuccess {Number} [result.tracks.recognition.elapsed] 以毫秒为单位的音轨识别耗时。
 * @apiSuccess {String} [result.tracks.recognition.text] 音轨识别信息。
 * @apiSuccess {Array}  [result.tracks.recognition.words] 音轨识别信息的分词数组。
 * @apiSuccess {String} [result.tracks.recognition.lang] 音轨的语言类型。
 * @apiSuccess {Number} [result.tracks.recognition.duration] 语音时长，单位：秒。
 * @apiSuccess {Object} [result.indicator] 语音分析指标数据。
 * @apiSuccess {Number} [result.indicator.id] 指标数据对应的 ID 。
 * @apiSuccess {Number} [result.indicator.timestamp] 语音时间戳。
 * @apiSuccess {Number} [result.indicator.silenceDuration] 语音整体沉默时长，单位：秒。
 * @apiSuccess {Array}  [result.indicator.speakers] 说话人指标数组。
 * @apiSuccess {String} [result.indicator.speakers.label] 说话人标签。
 * @apiSuccess {String} [result.indicator.speakers.speaker] 说话人标识。
 * @apiSuccess {Number} [result.indicator.speakers.totalDuration] 说话人总说话时长，单位：秒。
 * @apiSuccess {Number} [result.indicator.speakers.totalWords] 说话人总说话词数。
 * @apiSuccess {Number} [result.indicator.speakers.durationRatio] 说话人说话时长占比。
 * @apiSuccess {Number} [result.indicator.speakers.rhythm] 说话人说话节奏，单位：w/m（每分钟词数）。
 * @apiSuccess {Number} [result.indicator.speakers.positiveSegmentRatio] 说话人积极语义片段占比。
 * @apiSuccess {Number} [result.indicator.speakers.negativeSegmentRatio] 说话人消极语义片段占比。
 * @apiSuccess {Number} [result.indicator.speakers.neutralSegmentRatio] 说话人中性语义片段占比。
 * @apiSuccess {Array}  [result.indicator.speakers.segmentIndicators] 说话人片段语义数组。
 * @apiSuccess {String} [result.indicator.speakers.segmentIndicators.sentiment] 说话人语义倾向描述。描述值有：<code>positive</code>、<code>negative</code>、<code>neutral</code>和<code>both</code>。
 * @apiSuccess {String} [result.indicator.speakers.segmentIndicators.track] 说话人片段音轨号。
 * @apiSuccess {Number} [result.indicator.speakers.segmentIndicators.rhythm] 说话人片段语言节奏。
 * @apiSuccess {Object} [result.indicator.speakers.emotionRatio] 说话人情绪比例。
 * @apiSuccess {Number} [result.indicator.speakers.emotionRatio.score] 说话人情绪综合得分。
 * @apiSuccess {Number} [result.indicator.speakers.emotionRatio.positiveRatio] 说话人积极情绪比例。
 * @apiSuccess {Number} [result.indicator.speakers.emotionRatio.negativeRatio] 说话人消极情绪比例。
 * @apiSuccess {Number} [result.indicator.speakers.emotionRatio.neutralRatio] 说话人中性情绪比例。
 * @apiSuccess {Array}  [result.indicator.speakers.emotions] 说话人情绪指标数组。
 * @apiSuccess {Number} [result.indicator.speakers.emotions.elapsed] 以毫秒为单位的情绪预测耗时。
 * @apiSuccess {Object} [result.indicator.speakers.emotions.emotion] 说话人情绪数据。
 * @apiSuccess {String} [result.indicator.speakers.emotions.emotion.name] 情绪名称。
 * @apiSuccess {String} [result.indicator.speakers.emotions.emotion.primaryWord] 情绪主描述词。
 * @apiSuccess {String} [result.indicator.speakers.emotions.emotion.secondaryWord] 情绪次描述词。
 * @apiSuccess {Number} [result.indicator.speakers.emotions.score] 说话人情绪预测得分。
 * @apiSuccess {Number} [result.indicator.speakers.emotions.duration] 说话人情绪时长，单位：秒。
 * 
 *
 *
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "queryCode": "GmrPWzCNNlbWlIipqBhdNIkpNgbicouP",
 *         "stateCode": 0,
 *         "timestamp": 1693613816824,
 *         "result": {
 *             "id": 774477198,
 *             "duration": 962.6666875,
 *             "elapsed": 1182845,
 *             "domain": "default_domain",
 *             "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *             "remark": "",
 *             "timestamp": 1774944927759,
 *             "title": "Voice-consultat-2026-03-31_16-15-27",
 *             "tracks": [
 *                 {
 *                     "label": "counselor",
 *                     "track": "1",
 *                     "display": "说话人1",
 *                     "segment": {
 *                         "duration": 2.9025000000000016,
 *                         "start": 5.43096875,
 *                         "end": 8.333468750000002
 *                     },
 *                     "recognition": {
 *                         "elapsed": 1090,
 *                         "duration": 2.6399998664855957,
 *                         "words": [
 *                             "你",
 *                             "可不可以",
 *                             "告诉",
 *                             "我",
 *                             "你",
 *                             "来",
 *                             "咨询",
 *                             "是",
 *                             "为什么",
 *                             "呢"
 *                         ],
 *                         "text": "你可不可以告诉我你来咨询是为什么呢？",
 *                         "lang": "zh"
 *                     },
 *                     "emotion": {
 *                         "elapsed": 0,
 *                         "duration": 2.9025000000000016,
 *                         "score": 0.8002817,
 *                         "emotion": {
 *                             "secondaryWord": "伤心",
 *                             "primaryWord": "悲伤",
 *                             "name": "Sad"
 *                         }
 *                     }
 *                 }
 *             ],
 *             "indicator": {
 *                 "id": 774477198,
 *                 "silenceDuration": 224.60481249999873,
 *                 "timestamp": 1774944939693,
 *                 "speakers": [
 *                     {
 *                         "label": "counselor",
 *                         "speaker": "counselor",
 *                         "rhythm": 191,
 *                         "totalDuration": 194.95687500000054,
 *                         "durationRatio": 20,
 *                         "positiveSegmentRatio": 38,
 *                         "negativeSegmentRatio": 34,
 *                         "neutralSegmentRatio": 15,
 *                         "totalWords": 620,
 *                         "emotions": [
 *                             {
 *                                 "elapsed": 0,
 *                                 "duration": 2.9025000000000016,
 *                                 "score": 0.8002817,
 *                                 "emotion": {
 *                                     "secondaryWord": "伤心",
 *                                     "primaryWord": "悲伤",
 *                                     "name": "Sad"
 *                                 }
 *                             }
 *                         ],
 *                         "segmentIndicators": [
 *                             {
 *                                 "sentiment": "neutral",
 *                                 "track": "1",
 *                                 "rhythm": 207
 *                             }
 *                         ],
 *                         "emotionRatio": {
 *                             "score": -34.26295127999998,
 *                             "negativeRatio": 100,
 *                             "neutralRatio": 0,
 *                             "positiveRatio": 0
 *                         }
 *                     }
 *                 ]
 *             },
 *             "file": {
 *                 "id": 2237345138,
 *                 "ownerId": 22003303,
 *                 "domain": "default_domain",
 *                 "fileName": "consultation_01.mp3",
 *                 "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *                 "fileSize": 7702093,
 *                 "lastModified": 1774939042000,
 *                 "fileType": "mp3",
 *                 "completedTime": 1774939043013,
 *                 "expiryTime": 3351739043013,
 *                 "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=mp3",
 *                 "md5": "bbb1288d78a11a06f6dda7a1daea8801",
 *                 "sha1": "786cd3aedb41b934960889c1bfbf9158bb8841a0"
 *             }
 *         }
 *     }
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 * @apiError 404 未找到指定文件的数据。
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/aigc/speech/diarization/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?qc=GmrPWzCNNlbWlIipqBhdNIkpNgbicouP
 */

/**
 * @api {post} /aigc/speech/emotion/:token 语音情绪识别
 * @apiName 语音情绪识别
 * @apiGroup Audio_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 识别整段语音的情绪倾向。先上传语音文件，并将文件码填入 <code>fileCode</code>。
 *                 <p>提交后使用原文件码调用“语音情绪识别结果查询”，查询参数为 <code>fc</code>。<code>stateCode=7</code> 表示正在处理，<code>0</code> 时读取 <code>result.emotion</code> 和 <code>result.score</code>。已有结果时可直接返回；需要重新识别时设置 <code>reset=true</code>。</p>
 *
 * @apiParam {String} token 授权令牌。
 *
 * @apiBody {String} fileCode 语音文件的文件码。
 * @apiBody {Boolean} [reset] 设置为 <code>true</code> 时忽略该文件已有的识别结果并重新识别。默认值：<code>false</code>。
 *
 *
 * @apiParamExample {json} 请求示例
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx"
 *     }
 * @apiSuccess {String} fileCode 语音文件的文件码。
 * @apiSuccess {Number} stateCode 操作状态码。<code>7</code> 正在处理，<code>0</code> 处理完成。
 * @apiSuccess {Number} timestamp 提交任务执行时的时间戳。
 * @apiSuccess {Object} [result] 识别结果。
 * @apiSuccess {Number} [result.elapsed] 以毫秒为单位的识别耗时。
 * @apiSuccess {Object} [result.emotion] 情绪描述。
 * @apiSuccess {String} [result.emotion.name] 情绪名称。
 * @apiSuccess {String} [result.emotion.primaryWord] 情绪主描述词。
 * @apiSuccess {String} [result.emotion.secondaryWord] 情绪次描述词。
 * @apiSuccess {Number} [result.score] 情绪得分。
 * @apiSuccess {Number} [result.duration] 语音时长，单位：秒。
 * @apiSuccess {Object} [result.file] 语音文件标签。
 * @apiSuccess {Number} result.file.id 文件标签 ID。
 * @apiSuccess {String} result.file.domain 文件所属域。
 * @apiSuccess {String} result.file.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} result.file.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} result.file.fileName 原始文件名。
 * @apiSuccess {Number} result.file.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} result.file.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.file.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.file.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} result.file.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [result.file.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [result.file.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [result.file.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [result.file.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [result.file.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [result.file.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [result.file.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 *
 *
 *
 * @apiSuccessExample {json} 已有识别结果时的应答
 *     HTTP/1.1 200 OK
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *         "stateCode": 0,
 *         "timestamp": 1774944939693,
 *         "result": {
 *             "elapsed": 812,
 *             "emotion": {
 *                 "name": "Sad",
 *                 "primaryWord": "悲伤",
 *                 "secondaryWord": "伤心"
 *             },
 *             "score": 0.8002817,
 *             "duration": 194.95,
 *             "file": {
 *                 "id": 2237345138,
 *                 "ownerId": 22003303,
 *                 "domain": "default_domain",
 *                 "fileName": "consultation_01.mp3",
 *                 "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *                 "fileSize": 7702093,
 *                 "lastModified": 1774939042000,
 *                 "fileType": "mp3",
 *                 "completedTime": 1774939043013,
 *                 "expiryTime": 3351739043013,
 *                 "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=mp3",
 *                 "md5": "bbb1288d78a11a06f6dda7a1daea8801",
 *                 "sha1": "786cd3aedb41b934960889c1bfbf9158bb8841a0"
 *             }
 *         }
 *     }
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 *
 * @apiSuccessExample {json} 新任务：正在处理
 *     HTTP/1.1 200 OK
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *         "stateCode": 7,
 *         "timestamp": 1774944939000
 *     }
 *
 *
 * @apiParamExample {json} 重新识别同一文件
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *         "reset": true
 *     }
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/speech/emotion/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx"
 *     }
 */

/**
 * @api {get} /aigc/speech/emotion/:token 语音情绪识别结果查询
 * @apiName 语音情绪识别结果查询
 * @apiGroup Audio_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 使用原音频的文件码 <code>fc</code> 查询情绪分析状态。<code>stateCode=7</code> 时稍后重查，<code>0</code> 时读取 <code>result.emotion.primaryWord</code> 等情绪说明；其他值按任务异常处理。
 *                 <p>HTTP 404 表示该文件没有可查询的任务，请检查文件码并确认已经提交。<code>duration</code> 为音频秒数，<code>elapsed</code> 为识别耗时毫秒数。</p>
 *
 * @apiParam {String} token 授权令牌。
 *
 * @apiQuery {String} fc 语音文件的文件码。同时接受 <code>code</code> 作为别名。
 *
 * @apiSuccess {String} fileCode 语音文件的文件码。
 * @apiSuccess {Number} stateCode 操作状态码。<code>7</code> 正在处理，<code>0</code> 处理完成。
 * @apiSuccess {Number} timestamp 提交任务执行时的时间戳。
 * @apiSuccess {Object} [result] 识别结果，字段同提交接口。
 * @apiSuccess {Number} result.elapsed 识别耗时，单位：毫秒。
 * @apiSuccess {Object} result.emotion 情绪描述。
 * @apiSuccess {String} result.emotion.name 情绪名称。
 * @apiSuccess {String} result.emotion.primaryWord 情绪主描述词。
 * @apiSuccess {String} result.emotion.secondaryWord 情绪次描述词。
 * @apiSuccess {Number} result.score 情绪得分。
 * @apiSuccess {Number} result.duration 音频时长，单位：秒。
 * @apiSuccess {Object} result.file 音频文件标签。
 * @apiSuccess {Number} result.file.id 文件标签 ID。
 * @apiSuccess {String} result.file.domain 文件所属域。
 * @apiSuccess {String} result.file.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} result.file.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} result.file.fileName 原始文件名。
 * @apiSuccess {Number} result.file.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} result.file.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.file.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.file.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} result.file.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [result.file.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [result.file.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [result.file.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [result.file.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [result.file.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [result.file.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [result.file.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 *
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 未提供文件码。
 * @apiError 404 未找到该文件的识别任务。
 *
 * @apiSuccessExample {json} 识别完成
 *     HTTP/1.1 200 OK
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *         "stateCode": 0,
 *         "timestamp": 1774944939693,
 *         "result": {
 *             "elapsed": 812,
 *             "emotion": {
 *                 "name": "Sad",
 *                 "primaryWord": "悲伤",
 *                 "secondaryWord": "伤心"
 *             },
 *             "score": 0.8002817,
 *             "duration": 194.95,
 *             "file": {
 *                 "id": 2237345138,
 *                 "ownerId": 22003303,
 *                 "domain": "default_domain",
 *                 "fileName": "consultation_01.mp3",
 *                 "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *                 "fileSize": 7702093,
 *                 "lastModified": 1774939042000,
 *                 "fileType": "mp3",
 *                 "completedTime": 1774939043013,
 *                 "expiryTime": 3351739043013,
 *                 "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=mp3",
 *                 "md5": "bbb1288d78a11a06f6dda7a1daea8801",
 *                 "sha1": "786cd3aedb41b934960889c1bfbf9158bb8841a0"
 *             }
 *         }
 *     }
 *
 * @apiSuccessExample {json} 仍在处理
 *     HTTP/1.1 200 OK
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *         "stateCode": 7,
 *         "timestamp": 1774944939000
 *     }
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/aigc/speech/emotion/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?fc=UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx
 */
