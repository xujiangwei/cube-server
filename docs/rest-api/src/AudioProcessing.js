/**
 * @apiDefine Audio_Processing 音频处理
 */

/**
 * @api {post} /aigc/speech/recognition/:token 自动语音识别
 * @apiName 自动语音识别
 * @apiGroup Audio_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 从音频数据中提取语言文本信息。<br/>
 *                 使用该接口时，可通过文件处理接口将音频文件上传到服务器，以获得文件码。
 *                 异步模式下，该接口在提交任务后将立即返回，使用查询接口获取识别结果。
 *
 * @apiParam {String} token 授权令牌。
 *
 * @apiBody {String} [fileCode] 音频文件的文件码。与<code>fileUrl</code>参数二选一。
 * @apiBody {String} [fileUrl] 音频文件的链接。与<code>fileCode</code>参数二选一。
 * @apiBody {Boolean} [sync] 是否同步返回识别结果。默认值：<code>false</code>。
 * @apiBody {Boolean} [reset] 当该参数设置为<code>true</code>时将忽略之前该文件的识别数据，重新执行识别。默认值：<code>false</code>。
 *
 * @apiParamExample {json} 请求示例（使用文件码）
 *     {
 *         "fileCode": "hKuvUFebTvyQusIRBQCpANsMdYfWGlHGwVxHLbznxecZIKBknPZWJZcNkOXiZpEx"
 *     }
 *
 * @apiParamExample {json} 请求示例（使用文件链接）
 *     {
 *         "fileUrl": "https://mysite.com/files/2025/10/speech_8731.mp3"
 *     }
 *
 * @apiParamExample {json} 同步请求示例（使用文件码）
 *     {
 *         "fileCode": "hKuvUFebTvyQusIRBQCpANsMdYfWGlHGwVxHLbznxecZIKBknPZWJZcNkOXiZpEx",
 *         "sync": true
 *     }
 *
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
 *
 * @apiSuccessExample {json} 应答示例（正在识别）
 *     HTTP/1.1 200 OK
 *     {
 *         "queryCode": "GmrPWzCNNlbWlIipqBhdNIkpNgbicouP",
 *         "stateCode": 7,
 *         "timestamp": 1683613816824
 *     }
 * 
 * @apiSuccessExample {json} 应答示例（识别结果）
 *     HTTP/1.1 200 OK
 *     {
 *         "stateCode": 0,
 *         "timestamp": 1683613816824,
 *         "result": {
 *             "text": "与用户的思维、想法...",
 *             "duration": 172.3,
 *             "lang": "zh",
 *             "words": ["与", "用户", "的", ...],
 *             "elapsed": 2547,
 *             "file": {
 *                 ...
 *             }
 *         }
 *     }
 *
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 * @apiError 404 参数错误。
 */


/**
 * @api {get} /aigc/speech/recognition/:token 自动语音识别结果查询
 * @apiName 自动语音识别结果查询
 * @apiGroup Audio_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 查询正在处理或已处理完成的自动语音识别任务。
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
 *
 * @apiSuccessExample {json} 应答示例（正在识别）
 *     HTTP/1.1 200 OK
 *     {
 *         "queryCode": "GmrPWzCNNlbWlIipqBhdNIkpNgbicouP",
 *         "stateCode": 7,
 *         "timestamp": 1683613816824
 *     }
 * 
 * @apiSuccessExample {json} 应答示例（识别结果）
 *     HTTP/1.1 200 OK
 *     {
 *         "queryCode": "GmrPWzCNNlbWlIipqBhdNIkpNgbicouP",
 *         "stateCode": 0,
 *         "timestamp": 1683613816824,
 *         "result": {
 *             "text": "与用户的思维、想法...",
 *             "duration": 172.3,
 *             "lang": "zh",
 *             "words": ["与", "用户", "的", ...],
 *             "elapsed": 2547,
 *             "file": {
 *                 ...
 *             }
 *         }
 *     }
 *
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 * @apiError 404 未找到指定文件的识别任务。
 */


/**
 * @api {post} /aigc/speech/diarization/:token 说话者语音多态识别
 * @apiName 说话者语音多态识别
 * @apiGroup Audio_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 对语音数据进行声纹识别，把不同说话人的声音，按照说话人提取出来，同时对说话人的情绪进行预测，并将说话内容识别输出文本，对文本进行语义倾向预测。<br/>
 *                 使用该接口时，可通过文件处理接口将语音文件上传到服务器，以获得文件码。
 *                 该接口在提交任务后将立即返回，使用查询接口获取处理结果。
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} [fileCode] 语音文件的文件码。与<code>fileUrl</code>参数二选一。
 * @apiBody {String} [fileUrl] 语音文件的链接。与<code>fileCode</code>参数二选一。
 * @apiBody {Boolean} [reset] 当该参数设置为<code>true</code>时将忽略之前该文件的识别数据，重新执行识别。默认值：<code>false</code>。
 *
 * @apiParamExample {json} 请求示例（使用文件码）
 *     {
 *         "fileCode": "yywwzzwzwxytNEMrwHFtBGEDuPtOrEDuPtOryywwzzwzrwxytNEMrwHFtBGiMPRk"
 *     }
 * 
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
 */


/**
 * @api {get} /aigc/speech/diarization/:token 说话者语音多态识别结果查询
 * @apiName 说话者语音多态识别结果查询
 * @apiGroup Audio_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 查询正在处理或已处理完成的说话者语音多态识别任务。
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
 *             "fileCode": "CnDtDLwrEvDsREVcxWJoPMVNcUsErxsvkUFdaLMVWpRausGrOZpHrPdmToSGynTy",
 *             "remark": "",
 *             "timestamp": 1774944927759,
 *             "title": "Voice-consultat-2026-03-31_16-15-27",
 *             "tracks": [{
 *                 "label": "counselor",
 *                 "track": "1",
 *                 "display": "说话人1",
 *                 "segment": {
 *                     "duration": 2.9025000000000016,
 *                     "start": 5.43096875,
 *                     "end": 8.333468750000002
 *                 },
 *                 "recognition": {
 *                     "elapsed": 1090,
 *                     "duration": 2.6399998664855957,
 *                     "words": [ "你", "可不可以", "告诉",  "我", "你", "来", "咨询", "是", "为什么", "呢" ],
 *                     "text": "你可不可以告诉我你来咨询是为什么呢？",
 *                     "lang": "zh"
 *                 },
 *                 "emotion": {
 *                     "elapsed": 0,
 *                     "duration": 2.9025000000000016,
 *                     "score": 0.8002817,
 *                     "emotion": {
 *                         "secondaryWord": "伤心",
 *                         "primaryWord": "悲伤",
 *                         "name": "Sad"
 *                     }
 *                 }
 *             },
 *             ... ],
 *             "indicator": {
 *                 "id": 774477198,
 *                 "silenceDuration": 224.60481249999873,
 *                 "timestamp": 1774944939693,
 *                 "speakers": [{
 *                     "label": "counselor",
 *                     "speaker": "counselor",
 *                     "rhythm": 191,
 *                     "totalDuration": 194.95687500000054,
 *                     "durationRatio": 20,
 *                     "positiveSegmentRatio": 38,
 *                     "negativeSegmentRatio": 34,
 *                     "neutralSegmentRatio": 15,
 *                     "totalWords": 620,
 *                     "emotions": [{
 *                         "elapsed": 0,
 *                         "duration": 2.9025000000000016,
 *                         "score": 0.8002817,
 *                         "emotion": {
 *                             "secondaryWord": "伤心",
 *                             "primaryWord": "悲伤",
 *                             "name": "Sad"
 *                         }
 *                     },
 *                     ... ],
 *                     "segmentIndicators": [{
 *                         "sentiment": "neutral",
 *                         "track": "1",
 *                         "rhythm": 207
 *                     },
 *                     ... ],
 *                     "emotionRatio": {
 *                         "score": -34.26295127999998,
 *                         "negativeRatio": 100,
 *                         "neutralRatio": 0,
 *                         "positiveRatio": 0
 *                     }
 *                 },
 *                 ... ]
 *             },
 *             "file": {
 *                 "id": 2237345138,
 *                 "ownerId": 22003303,
 *                 "domain": "default_domain",
 *                 "fileName": "consultation_01.mp3",
 *                 "fileCode": "CnDtDLwrEvDsREVcxWJoPMVNcUsErxsvkUFdaLMVWpRausGrOZpHrPdmToSGynTy",
 *                 "fileSize": 7702093,
 *                 "lastModified": 1774939042000,
 *                 "fileType": "mp3",
 *                 "completedTime": 1774939043013,
 *                 "expiryTime": 3351739043013,
 *                 "fileURL": "http://192.168.1.27:7010/filestorage/file/?fc=CnDtDLwrEvDsREVcxWJoPMVNcUsErxsvkUFdaLMVWpRausGrOZpHrPdmToSGynTy&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=mp3",
 *                 "fileSecureURL": "https://192.168.1.27:7017/filestorage/file/?fc=CnDtDLwrEvDsREVcxWJoPMVNcUsErxsvkUFdaLMVWpRausGrOZpHrPdmToSGynTy&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=mp3",
 *                 "sha1": "786cd3aedb41b934960889c1bfbf9158bb8841a0",
 *                 "md5": "bbb1288d78a11a06f6dda7a1daea8801"
 *             }
 *         }
 *     }
 * 
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 * @apiError 404 未找到指定文件的数据。
 */

/**
 * @api {post} /aigc/speech/emotion/:token 语音情绪识别
 * @apiName 语音情绪识别
 * @apiGroup Audio_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 识别整段语音的情绪倾向。使用前先通过文件接口上传语音文件并获得文件码。<br/>
 *                 该接口在提交任务后立即返回，结果按文件码缓存，使用查询接口获取结果。
 *
 * @apiParam {String} token 授权令牌。
 *
 * @apiBody {String} fileCode 语音文件的文件码。
 * @apiBody {Boolean} [reset] 设置为 <code>true</code> 时忽略该文件已有的识别结果并重新识别。默认值：<code>false</code>。
 *
 * @apiParamExample {json} 请求示例
 *     {
 *         "fileCode": "CnDtDLwrEvDsREVcxWJoPMVNcUsErxsvkUFdaLMVWpRausGrOZpHrPdmToSGynTy"
 *     }
 *
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
 *
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "fileCode": "CnDtDLwrEvDsREVcxWJoPMVNcUsErxsvkUFdaLMVWpRausGrOZpHrPdmToSGynTy",
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
 *             "file": { ... }
 *         }
 *     }
 *
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 */

/**
 * @api {get} /aigc/speech/emotion/:token 语音情绪识别结果查询
 * @apiName 语音情绪识别结果查询
 * @apiGroup Audio_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 按文件码查询语音情绪识别任务的状态与结果。
 *
 * @apiParam {String} token 授权令牌。
 *
 * @apiQuery {String} fc 语音文件的文件码。同时接受 <code>code</code> 作为别名。
 *
 * @apiSuccess {String} fileCode 语音文件的文件码。
 * @apiSuccess {Number} stateCode 操作状态码。<code>7</code> 正在处理，<code>0</code> 处理完成。
 * @apiSuccess {Number} timestamp 提交任务执行时的时间戳。
 * @apiSuccess {Object} [result] 识别结果，字段同提交接口。
 *
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 未提供文件码。
 * @apiError 404 未找到该文件的识别任务。
 */
