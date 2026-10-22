/**
 * @apiDefine Psychology 心理学服务
 */

/**
 * @apiDefine Report_Response 报告
 * @apiSuccess {Number} sn 报告序号。
 * @apiSuccess {Number} contactId 提交数据的触点ID 。
 * @apiSuccess {Number} timestamp 提交数据时的时间戳。
 * @apiSuccess {String} name 报告名称。
 * @apiSuccess {Object} attribute 受测者属性。
 * @apiSuccess {String} attribute.gender 受测者性别。
 * @apiSuccess {Number} attribute.age 受测者年龄。
 * @apiSuccess {String} attribute.language 指定报告使用的语言。支持的语言：
 *                                         <p><code>cn</code>：<span class="default-value">中文</span></p>
 *                                         <p><code>en</code>：<span class="default-value">英文</span></p>
 * @apiSuccess {Boolean} attribute.strict 是否使用严格模式。默认值：<code>false</code>。
 * @apiSuccess {Number} state 该报告当前的状态。
 *                            <p><code>0</code>：<span class="default-value">报告已完成</span></p>
 *                            <p><code>7</code>：<span class="default-value">正在处理数据</span></p>
 *                            <p><code>8</code>：<span class="default-value">正在生成内容</span></p>
 *                            <p><code>9</code>：<span class="default-value">发生故障</span></p>
 *                            <p><code>24</code>：<span class="default-value">无效的数据</span></p>
 *                            <p><code>25</code>：<span class="default-value">无法进行的操作</span></p>
 *                            <p><code>27</code>：<span class="default-value">操作已停止</span></p>
 *                            <p><code>31</code>：<span class="default-value">单元故障</span></p>
 *                            <p><code>32</code>：<span class="default-value">文件错误</span></p>
 * @apiSuccess {Boolean} finished 报告是否已完成生成。
 * @apiSuccess {Number} finishedTimestamp 报告完成时的时间戳。
 * @apiSuccess {String} [theme] 报告主题。
 * @apiSuccess {Number} [queuePosition] 报告所在的执行队列位置。当报告状态为<code>7</code>、<code>8</code>正在生成时，该数据表示报告等待执行的队列位置。当值为<code>1</code>时表示排在第一个位置，依此类推。当值为<code>-1</code>时表示没有在队列中，报告已经进入推理阶段。
 * @apiSuccess {Object} [fileLabel] 报告使用的文件。
 * @apiSuccess {String} [reference] 报告使用的参考标准。
 * @apiSuccess {String} [summary] 报告概述。
 * @apiSuccess {String} [keyFeatureDescription] 关键特征描述（Markdown格式）。
 * @apiSuccess {Object} [evaluation] 绘画的评估结果。
 * @apiSuccess {Object} [evaluation.accelerator] 评估使用的催化剂数据。
 * @apiSuccess {Array}  [evaluation.accelerator.evaluations] 关键指标评估得分数组。
 * @apiSuccess {String} [evaluation.accelerator.evaluations.indicator] 指标名称。
 * @apiSuccess {Number} [evaluation.accelerator.evaluations.hit] 该指标命中的特征数量。
 * @apiSuccess {Number} [evaluation.accelerator.evaluations.value] 该指标的评级数值。
 * @apiSuccess {Object} [evaluation.accelerator.evaluations.rate] 指标评级数据。
 * @apiSuccess {Number} [evaluation.accelerator.evaluations.rate.value] 指标评级值。
 * @apiSuccess {String} [evaluation.accelerator.evaluations.rate.displayName] 指标评级文字说明。
 * @apiSuccess {Number} [evaluation.accelerator.evaluations.positiveScore] 指标正分得分。
 * @apiSuccess {Number} [evaluation.accelerator.evaluations.negativeScore] 指标负分得分。
 * @apiSuccess {Boolean} [evaluation.hesitating] 绘画内容可用信息是否较少，从而影响到评估结果。如果该变量为 <code>true</code> ，则不建议使用该结果的 <code>attention</code> 结论。
 * @apiSuccess {Object} [evaluation.factorSet] 测验的各效验因子集合。
 * @apiSuccess {Array}  [evaluation.factorSet.symptoms] 症状因子数组。
 * @apiSuccess {Array}  [evaluation.factorSet.personalities] 人格因子数组。
 * @apiSuccess {Array}  [evaluation.factorSet.affects] 情绪因子数组。
 * @apiSuccess {Array}  [evaluation.additionScales] 附加推荐量表列表。
 * @apiSuccess {Array}  [evaluation.keyFeatures] 关键特征列表。
 * @apiSuccess {Array}  [evaluation.representationList] 绘画表征列表。
 * @apiSuccess {Object} [evaluation.representationList.knowledgeStrategy] 表征对应的知识策略。
 * @apiSuccess {String} [evaluation.representationList.knowledgeStrategy.comment] 知识策略的评论文本。
 * @apiSuccess {Number} [evaluation.representationList.positiveCorrelation] 正相关强度。
 * @apiSuccess {Number} [evaluation.representationList.negativeCorrelation] 负相关强度。
 * @apiSuccess {String} [evaluation.representationList.description] 表征描述文本。
 * @apiSuccess {Object} [evaluation.attribute] 评测使用的受测者属性。
 * @apiSuccess {Number} [evaluation.attention] 评估结果对应的关注等级。
 * @apiSuccess {Array}  [reportTextList] 报告文本列表。
 * @apiSuccess {String} [reportTextList.indicator] 报告内容对应的指标名称。
 * @apiSuccess {Number} [reportTextList.tendency] 报告内容对应的指标倾向。该值为 <code>1</code> 表示正倾向，<code>-1</code> 表示负倾向。
 * @apiSuccess {String} [reportTextList.title] 报告条目标题。
 * @apiSuccess {String} [reportTextList.report] 报告条目内容文本。
 * @apiSuccess {String} [reportTextList.suggestion] 报告指标的应对建议文本。
 * @apiSuccess {Object} [extension] 扩展数据。
 * @apiSuccess {Array}  [extension.keywords] 报告相关关键词列表。
 * @apiSuccess {String} [extension.keywords.word] 关键词文本。
 * @apiSuccess {Number} [extension.keywords.score] 关键词权重得分。
 * @apiSuccess {Object} [attention] 对受测者的关注建议。
 * @apiSuccess {Number} [attention.level] 关注等级，从高到低依次为：<code>3</code>、<code>2</code>、<code>1</code>、<code>0</code>，
 *                                        依次对应<span class="default-value">特殊关注</span>、<span class="default-value">重点关注</span>、
 *                                        <span class="default-value">一般关注</span>和<span class="default-value">无需关注</span>。
 * @apiSuccess {String} [attention.desc] 关注文本描述。按照关注程度从高到低依次是：<span class="default-value">特殊关注</span>、<span class="default-value">重点关注</span>、
 *                                       <span class="default-value">一般关注</span>和<span class="default-value">无需关注</span>。
 * @apiSuccess {Object} [suggestion] 测验建议。
 * @apiSuccess {String} [suggestion.title] 测验建议标题。
 * @apiSuccess {String} [suggestion.description] 测验建议内容描述。
 * @apiSuccess {String} [suggestion.action] 测验建议的操作动作。
 * @apiSuccess {Object} [personality] 人格测验结果。
 * @apiSuccess {Object} [personality.theBigFive] 大五人格数据。
 * @apiSuccess {String} [personality.theBigFive.name] 大五人格画像名称。
 * @apiSuccess {String} [personality.theBigFive.displayName] 大五人格画像显示名。
 * @apiSuccess {String} [personality.theBigFive.description] 大五人格画像描述。
 * @apiSuccess {Array}  [personality.theBigFive.scores] 大五人格画像各维度得分数组。
 * @apiSuccess {String} [personality.theBigFive.scores.name] 大五人格画像维度名。
 * @apiSuccess {String} [personality.theBigFive.scores.code] 大五人格画像维度代码。
 * @apiSuccess {String} [personality.theBigFive.scores.paraphrase] 大五人格画像维度释义。
 * @apiSuccess {Number} [personality.theBigFive.scores.score] 大五人格画像维度得分。
 * @apiSuccess {String} [personality.theBigFive.scores.content] 大五人格画像维度描述内容。
 * @apiSuccess {Object} [personality.theBigFive.scores.annotation] 大五人格画像维度得分高分与低分注释。
 * @apiSuccess {Array}  [personality.theBigFive.scores.annotation.highAdvantages] 大五人格画像维度得分高分优势数组。
 * @apiSuccess {Array}  [personality.theBigFive.scores.annotation.highDisadvantages] 大五人格画像维度得分高分风险数组。
 * @apiSuccess {Array}  [personality.theBigFive.scores.annotation.lowAdvantages] 大五人格画像维度得分低分优势数组。
 * @apiSuccess {Array}  [personality.theBigFive.scores.annotation.lowDisadvantages] 大五人格画像维度得分低分风险数组。
 * @apiSuccess {Object} [dimensionScore] 维度得分。
 * @apiSuccess {Array}  [dimensionScore.factors] 维度得分因子。
 * @apiSuccess {Array}  [dimensionScore.scores] 维度得分分数。
 * @apiSuccess {Array}  [dimensionScore.rates] 维度得分等级。<span class="default-value">1 - 低</span>、<span class="default-value">2 - 中</span>、
 *                                             <span class="default-value">3 - 高</span>。
 * @apiSuccess {Array}  [dimensionScore.descriptions] 维度得分描述。
 * @apiSuccess {Object} [normDimensionScore] 参考维度分数。
 * @apiSuccess {Array}  [normDimensionScore.factors] 参考维度得分因子。
 * @apiSuccess {Array}  [normDimensionScore.scores] 参考维度得分分数。
 * @apiSuccess {String} [markdown] Markdown 格式的数据内容。
 * @apiSuccess {Array}  [factors] 量表报告的各因子报告数据数组。
 * @apiSuccess {String} [factors.name] 量表报告的各因子名称。
 * @apiSuccess {String} [factors.displayName] 量表报告的各因子显示名。
 * @apiSuccess {Number} [factors.score] 量表报告的各因子得分。
 * @apiSuccess {String} [factors.description] 量表报告的各因子报告描述文本。
 * @apiSuccess {String} [factors.suggestion] 量表报告的各因子报告建议文本。
 */


/**
 * @apiDefine Scale_Response 量表
 * @apiSuccess {Number} sn 量表序号。
 * @apiSuccess {String} name 量表名称。
 * @apiSuccess {String} displayName 量表显示名称。
 * @apiSuccess {String} instruction 量表指导语。
 * @apiSuccess {Number} timestamp 量表创建时的时间戳。
 * @apiSuccess {Object} attribute 受测人属性。
 * @apiSuccess {String} attribute.gender 受测人性别。
 * @apiSuccess {Number} attribute.age 受测人年龄。
 * @apiSuccess {String} attribute.language 指定使用的语言。支持的语言：
 *                                         <p><code>cn</code>：<span class="default-value">中文</span></p>
 *                                         <p><code>en</code>：<span class="default-value">英文</span></p>
 * @apiSuccess {Boolean} attribute.strict 是否使用了严格模式。
 * @apiSuccess {Array}  sections 量表段落列表。
 * @apiSuccess {String} sections.title 段落标题。
 * @apiSuccess {String} sections.content 段落内容说明。
 * @apiSuccess {Array}  sections.questions 该段落问题列表。
 * @apiSuccess {Number} sections.questions.sn 问题序号。
 * @apiSuccess {String} sections.questions.choice 问题答案选择方式，分为单选和多选。<code>single</code> 表示单选，<code>multiple</code> 表示多选。
 * @apiSuccess {String} sections.questions.content 问题内容。
 * @apiSuccess {Array}  sections.questions.answers 问题对应的可选答案列表。
 * @apiSuccess {Number} sections.questions.answers.sn 答案序号。
 * @apiSuccess {String} sections.questions.answers.code 答案代码。
 * @apiSuccess {String} sections.questions.answers.content 答案内容。
 * @apiSuccess {Boolean} sections.questions.answers.chosen 该答案是否已被选中。
 * @apiSuccess {Object} [result] 量表评测结果。量表评测结果仅在量表提交所有问答的答题表之后才能得到结果数据。
 * @apiSuccess {Boolean} [result.complete] 量表所有问题是否都已完成回答。
 * @apiSuccess {String} [result.content] 量表结果内容。内容是 Markdown 格式的文本描述。
 * @apiSuccess {Object} [result.score] 结果各项数据映射。
 * @apiSuccess {String} [result.score.name] 评估项数据的名称。
 * @apiSuccess {Number} [result.score.value] 评估项数据的数值。
 * @apiSuccess {Object} [result.score.level] 评估项数据的等级信息。
 * @apiSuccess {Number} [result.score.level.level] 评估项数据的程度级别。
 *                                                 <p><code>0</code>：<span class="default-value">没有</span></p>
 *                                                 <p><code>1</code>：<span class="default-value">略有</span></p>
 *                                                 <p><code>2</code>：<span class="default-value">轻度</span></p>
 *                                                 <p><code>3</code>：<span class="default-value">中度</span></p>
 *                                                 <p><code>4</code>：<span class="default-value">重度</span></p>
 * @apiSuccess {Number} [result.score.level.prefix] 评估项数据的等级前缀。
 */



/**
 * @api {post} /aigc/psychology/report/:token 生成心理评测报告（执行）
 * @apiName 生成心理评测报告
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 *
 * @apiDescription 使用绘画/量表生成心理评测报告。
 *                 <br/>生成绘画报告前，绘画文件使用文件上传接口进行文件上传。
 *                 <br/>生成量表报告时，量表需要完成答案提交。
 *                 <br/>该接口是非阻塞接口，服务器会将该请求放入队列执行，接口返回报告相关信息。使用<code>生成心理评测报告（查询）</code>接口来查询报告生成结果和进度。
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Object} attribute 受测者属性。（绘画评测用参数）
 * @apiBody {String} attribute.gender 受测者性别。（绘画评测用参数）
 * @apiBody {Number} attribute.age 受测者年龄。（绘画评测用参数）
 * @apiBody {String} attribute.language 指定报告使用的语言。支持的语言：
 *                                      <p><code>cn</code>：<span class="default-value">中文</span></p>
 *                                      <p><code>en</code>：<span class="default-value">英文</span></p>
 * @apiBody {Boolean} [attribute.strict] 是否使用严格模式，默认值：<code>false</code>。（绘画评测用参数）
 * @apiBody {String} [fileCode] 绘画文件的文件码。（绘画评测用参数）
 * @apiBody {Boolean} [adjust] 是否需要对绘画文件进行边界校准。（绘画评测用参数）
 * @apiBody {String} [theme] 推理绘画内容的主题（绘画评测用参数）。默认值：<code>Generic</code> 。支持的主题有：
 *                           <p><code>HTP</code>：<span class="default-value">房树人绘画</span></p>
 *                           <p><code>AS</code>：<span class="default-value">依恋类型</span></p>
 *                           <p><code>PIR</code>：<span class="default-value">雨中人绘画</span></p>
 *                           <p><code>TT</code>：<span class="default-value">树木绘画</span></p>
 *                           <p><code>SP</code>：<span class="default-value">自画像</span></p>
 * @apiBody {Number} [indicators] 允许推理的最大指标数量。默认值：<code>10</code>。（绘画评测用参数）
 * @apiBody {Number} [scaleSn] 量表的序号。（量表评测用参数）
 * 
 * @apiParamExample {json} 生成绘画评测报告请求示例
 *     {
 *         "theme": "HTP",
 *         "attribute": {
 *             "gender": "male",
 *             "age": 24,
 *             "language": "cn",
 *             "strict": false
 *         },
 *         "fileCode": "AIjoFDlzyphCHatmTRmoPMhxAwgEXFahaYVpazWLyZTGoySNYxVFBRNYpMsaGLDK",
 *         "adjust": false
 *     }
 * 
 * @apiParamExample {json} 生成量表评测报告请求示例
 *     {
 *         "scaleSn": 2960942553
 *     }
 * 
 * @apiUse Report_Response
 * 
 * @apiSuccessExample {json} 绘画评测报告应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 1119207859,
 *         "contactId": 22003303,
 *         "timestamp": 1713929178941,
 *         "name": "AXL-20240731178843",
 *         "theme": "Generic",
 *         "finished": false,
 *         "attribute": {
 *             "gender": "male",
 *             "age": 24,
 *             "language": "cn",
 *             "strict": false
 *         },
 *         "state": 7,
 *         "fileLabel": {
 *             "fileName": "pt_001.jpg",
 *             "completedTime": 1713921950363,
 *             "fileCode": "AIjoFDlzyphCHatmTRmoPMhxAwgEXFahaYVpazWLyZTGoySNYxVFBRNYpMsaGLDK",
 *             "ownerId": 22003303,
 *             "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=AIjoFDlzyphCHatmTRmoPMhxAwgEXFahaYVpazWLyZTGoySNYxVFBRNYpMsaGLDK&type=jpg&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *             "sha1": "2e441e0b836c8581ed32d37671d86babe3b096ea",
 *             "fileSize": 971341,
 *             "domain": "default_domain",
 *             "expiryTime": 1716513950363,
 *             "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=AIjoFDlzyphCHatmTRmoPMhxAwgEXFahaYVpazWLyZTGoySNYxVFBRNYpMsaGLDK&type=jpg&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *             "id": 2202189684,
 *             "lastModified": 1713768766000,
 *             "fileType": "jpg",
 *             "md5": "5b6f68815e1eec83484d87128e2063fa"
 *         }
 *     }
 * 
 * @apiSuccessExample {json} 量表评测报告应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 2960942553,
 *         "contactId": 22003303,
 *         "name": "AXL-20240731178843",
 *         "timestamp": 1722416990677,
 *         "attribute": {
 *             "gender": "male",
 *             "strict": false,
 *             "language": "cn",
 *             "age": 29
 *         },
 *         "finished": false,
 *         "finishedTimestamp": 0,
 *         "state": 7,
 *         "factors": [{
 *             "name": "Somatization",
 *             "score": 0.6666666666666666,
 *             "displayName": "躯体化症状",
 *             "suggestion": "",
 *             "description": ""
 *         }, {
 *             "name": "Obsession",
 *             "score": 1,
 *             "displayName": "强迫症状",
 *             "suggestion": "",
 *             "description": ""
 *         },
 *         ... ]
 *     }
 * 
 * @apiError 400 参数错误。
 * @apiError 401 令牌错误。
 * @apiError 404 提交请求错误。
 */


/**
 * @api {get} /aigc/psychology/report/:token 生成心理评测报告（查询）
 * @apiName 查询（获取）心理评测报告信息
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 *
 * @apiDescription 查询（获取）心理评测报告信息。
 *                 <br/>该接口用于查询报告的生成进度和报告数据。
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {Number} [sn] 报告序号。与批量查询参数二选一使用。
 * @apiQuery {Boolean} [markdown] 是否包含 Markdown 格式的数据内容。默认值：<code>false</code>。
 * @apiQuery {Number} [page] 分页的页索引（批量查询）。
 * @apiQuery {Number} [size] 分页的页大小（批量查询）。
 * @apiQuery {Boolean} [desc] 分页数据是否按照时间降序排序（批量查询），默认值：<code>true</code>。
 * @apiQuery {Number} [state] 报告状态（批量查询）。
 * 
 * @apiUse Report_Response
 * 
 * @apiSuccess {Number} total 查询条件的总记录数。
 * @apiSuccess {Array} list 满足查询条件的当前分页的数据数组。
 * @apiSuccess {Number} page 当前分页的页索引。
 * 
 * @apiSuccessExample {json} 应答示例-查询指定序号绘画报告
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 1119207859,
 *         "theme": "Generic",
 *         "name": "AXL-20240731178843",
 *         "attribute": {
 *             "gender": "male",
 *             "age": 24,
 *             "language": "cn",
 *             "strict": false
 *         },
 *         "state": 0,
 *         "attention": {
 *             "level": 3,
 *             "desc": "特殊关注"
 *         },
 *         "suggestion": {
 *             "title": "寻求就医",
 *             "description": "心理疾病的种类繁多，症状各异，就医后，医生会通过详细的病史询问...",
 *             "action": "PsychiatryDepartment"   
 *         },
 *         "contactId": 22003303,
 *         "finished": true,
 *         "timestamp": 1713929178941,
 *         "fileLabel": {
 *             "fileName": "pt_001.jpg",
 *             "completedTime": 1713921950363,
 *             "fileCode": "AIjoFDlzyphCHatmTRmoPMhxAwgEXFahaYVpazWLyZTGoySNYxVFBRNYpMsaGLDK",
 *             "ownerId": 22003303,
 *             "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=AIjoFDlzyphCHatmTRmoPMhxAwgEXFahaYVpazWLyZTGoySNYxVFBRNYpMsaGLDK&type=jpg&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *             "sha1": "2e441e0b836c8581ed32d37671d86babe3b096ea",
 *             "fileSize": 971341,
 *             "domain": "default_domain",
 *             "expiryTime": 1716513950363,
 *             "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=AIjoFDlzyphCHatmTRmoPMhxAwgEXFahaYVpazWLyZTGoySNYxVFBRNYpMsaGLDK&type=jpg&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *             "id": 2202189684,
 *             "lastModified": 1713768766000,
 *             "fileType": "jpg",
 *             "md5": "5b6f68815e1eec83484d87128e2063fa"
 *         },
 *         "reference": "Normal",
 *         "summary": "你性格独立，擅长独立解决问题，不依赖他人。这使你能够在需要时保持情绪稳定，深思熟虑并做出明智的决策...",
 *         "keyFeatureDescription": "",
 *         "extension": {
 *             "keywords": [
 *                 {
 *                     "score": 0.6368,
 *                     "word": "高度独立"
 *                 },
 *                 {
 *                     "score": 0.8017,
 *                     "word": "情感疏离"
 *                 },
 *                 {
 *                     "score": 0.9633,
 *                     "word": "自我依赖"
 *                 }
 *             ]
 *         },
 *         "evaluation": {
 *             "accelerator": {
 *                 "evaluations": [
 *                     {
 *                         "indicator": "人际关系",
 *                         "hit": 6,
 *                         "rate": {
 *                             "value": 4,
 *                             "displayName": "高"
 *                         },
 *                         "positiveScore": 2.8813999999999997,
 *                         "negativeScore": 0.5744,
 *                         "value": 4
 *                     }, {
 *                         "indicator": "自信",
 *                         "hit": 4,
 *                         "rate": {
 *                             "value": 3,
 *                             "displayName": "中等"
 *                         },
 *                         "positiveScore": 1.5883,
 *                         "negativeScore": 0.6295,
 *                         "value": 2
 *                     }, {
 *                         "indicator": "理想主义",
 *                         "hit": 2,
 *                         "rate": {
 *                             "value": 3,
 *                             "displayName": "中等"
 *                         },
 *                         "positiveScore": 1.1381999999999999,
 *                         "negativeScore": 0,
 *                         "value": 2
 *                     },
 *                     ...
 *                 ]
 *             },
 *             "attention": 0,
 *             "hesitating": false,
 *             "additionScales": [],
 *             "factorSet": {
 *                 "symptoms": [
 *                     {
 *                         "name": "somatization",
 *                         "value": 2.0138607025146484
 *                     }, {
 *                         "name": "obsession",
 *                         "value": 1.7619595527648926
 *                     }, {
 *                         "name": "interpersonal",
 *                         "value": 1.769364833831787
 *                     }, {
 *                         "name": "depression",
 *                         "value": 1.9514126777648926
 *                     }, {
 *                         "name": "anxiety",
 *                         "value": 2.0437886714935303
 *                     }, {
 *                         "name": "hostile",
 *                         "value": 2.4450478553771973
 *                     }, {
 *                         "name": "horror",
 *                         "value": 2.2604403495788574
 *                     }, {
 *                         "name": "paranoid",
 *                         "value": 1.9085214138031006
 *                     }, {
 *                         "name": "psychosis",
 *                         "value": 2.133737564086914
 *                     }, {
 *                         "name": "sleepDiet",
 *                         "value": 2.2604622840881348
 *                     }
 *                 ],
 *                 "affects": [
 *                     {
 *                         "name": "positive",
 *                         "value": 24.815996170043945
 *                     }, {
 *                         "name": "negative",
 *                         "value": 21.090620040893555
 *                     }
 *                 ],
 *                 "personalities": [
 *                     {
 *                         "name": "obligingness",
 *                         "value": 5.890892028808594
 *                     }, {
 *                         "name": "conscientiousness",
 *                         "value": 6.12178897857666
 *                     }, {
 *                         "name": "extraversion",
 *                         "value": 4.995666027069092
 *                     }, {
 *                         "name": "achievement",
 *                         "value": 6.226171970367432
 *                     },  {
 *                         "name": "neuroticism",
 *                         "value": 4.877026557922363
 *                     }
 *                 ]
 *             },
 *             "representationList": [
 *                 {
 *                     "knowledgeStrategy": {
 *                         "comment": "情感稳定"
 *                     },
 *                     "positiveCorrelation": 1,
 *                     "description": "具有情感稳定",
 *                     "negativeCorrelation": 1
 *                 }, {
 *                     "knowledgeStrategy": {
 *                         "comment": "社交需求"
 *                     },
 *                     "positiveCorrelation": 2,
 *                     "description": "明显社交需求",
 *                     "negativeCorrelation": 0
 *                 },
 *                 ...
 *             ],
 *             "attribute": {
 *                 "gender": "male",
 *                 "age": 24,
 *                 "language": "cn",
 *                 "strict": false
 *             },
 *             "keyFeatures": []
 *         },
 *         "personality": {
 *             "theBigFive": {
 *                 "name": "Developer",
 *                 "displayName": "开发者",
 *                 "description": "你是良师益友的开发者，你的性格特点是...",
 *                 "scores": [{
 *                     "name": "宜人性",
 *                     "code": "Obligingness",
 *                     "score": 7.13,
 *                     "paraphrase": "宜人性考察个体对他人所持的态度，包括同理心、信任、利他...",
 *                     "content": "高宜人性的人有同情心，能理解他人，相信人性本善，乐于助人...",
 *                     "annotation": {
 *                         "lowDisadvantages": [
 *                             "以自我为中心。",
 *                             ...
 *                         ],
 *                         "lowAdvantages": [
 *                             "讲求实际。",
 *                             ...
 *                         ],
 *                         "highAdvantages": [
 *                             "有同情心、关心别人。",
 *                             ...
 *                         ],
 *                         "highDisadvantages": [
 *                             "过于天真、易受骗。",
 *                             ...
 *                         ]
 *                     }
 *                 }, {
 *                     "name": "尽责性",
 *                     "code": "Conscientiousness",
 *                     "score": 8.09,
 *                     "paraphrase": "尽责性反映个体自我控制的程度，有具体评估个体在行为上的条理性...",
 *                     "content": "高尽责性的人有责任感，喜欢制订计划，办事有条有理、一丝不苟...",
 *                     "annotation": {
 *                         "lowDisadvantages": [
 *                             "责任心不强、不谨慎。",
 *                             ...
 *                         ],
 *                         "lowAdvantages": [
 *                             "有创造性。",
 *                             ...
 *                         ],
 *                         "highAdvantages": [
 *                             "有计划、未雨绸缪。",
 *                             ...
 *                         ],
 *                         "highDisadvantages": [
 *                             "独裁主义。",
 *                             ...
 *                         ]
 *                     }
 *                 }, ...]
 *             }
 *         },
 *         "dimensionScore": {
 *             "factors": [
 *                 "SelfAssessment",
 *                 "MentalHealth",
 *                 "InterpersonalRelationship",
 *                 "Cognition",
 *                 "Mood",
 *                 "Behavior"
 *             ],
 *             "scores": [
 *                 34,
 *                 78,
 *                 68,
 *                 52,
 *                 89,
 *                 91
 *             ],
 *             "descriptions": [
 *                 "自我评价较低，比较自卑。优点是谦虚...",
 *                 "心理健康状况良好，但在面对较大压力时可能会有一些短暂的心理不适...",
 *                 "有一定的人际关系网络，能与他人正常交往...",
 *                 "认知能力一般，能够满足日常的学习和工作需求...",
 *                 "情绪稳定且积极，能够较好地管理和调节自己的情绪...",
 *                 "行为积极主动，有良好的自律性和执行力..."
 *             ],
 *             "rates": [
 *                 1,
 *                 3,
 *                 3,
 *                 2,
 *                 2,
 *                 2
 *             ]
 *         },
 *         "normDimensionScore": {
 *             "factors": [
 *                 "SelfAssessment",
 *                 "MentalHealth",
 *                 "Mood",
 *                 "InterpersonalRelationship",
 *                 "Cognition",
 *                 "Behavior"
 *             ],
 *             "scores": [
 *                 80,
 *                 80,
 *                 80,
 *                 80,
 *                 80,
 *                 80
 *             ]
 *         },
 *         "reportTextList": [
 *             {
 *                 "indicator": "自信心",
 *                 "tendency": 1,
 *                 "title": "自信心较强",
 *                 "report": "你具备良好的自信心，...",
 *                 "suggestion": "保持良好的..."
 *             },
 *             {
 *                 "indicator": "理想主义",
 *                 "tendency": 1,
 *                 "title": "理想主义者",
 *                 "report": "你可能在日常的生活中...",
 *                 "suggestion": "应当积极地参与到..."
 *             },
 *             ...
 *         ],
 *         "markdown": "# 一般报告\n\n> 男    24岁\n> 2024-04-24 11:26:18..."
 *     }
 *
 * @apiSuccessExample {json} 应答示例-查询指定序号绘画报告（正在生成报告）
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 1119207859,
 *         "contactId": 22003303,
 *         "timestamp": 1713929178941,
 *         "theme": "Generic",
 *         "name": "AXL-20240731178843",
 *         "finished": false,
 *         "attribute": {
 *             "gender": "male",
 *             "age": 24,
 *             "language": "cn",
 *             "strict": false
 *         },
 *         "state": 7,
 *         "fileLabel": {
 *             "fileName": "pt_001.jpg",
 *             "completedTime": 1713921950363,
 *             "fileCode": "AIjoFDlzyphCHatmTRmoPMhxAwgEXFahaYVpazWLyZTGoySNYxVFBRNYpMsaGLDK",
 *             "ownerId": 22003303,
 *             "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=AIjoFDlzyphCHatmTRmoPMhxAwgEXFahaYVpazWLyZTGoySNYxVFBRNYpMsaGLDK&type=jpg&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *             "sha1": "2e441e0b836c8581ed32d37671d86babe3b096ea",
 *             "fileSize": 971341,
 *             "domain": "default_domain",
 *             "expiryTime": 1716513950363,
 *             "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=AIjoFDlzyphCHatmTRmoPMhxAwgEXFahaYVpazWLyZTGoySNYxVFBRNYpMsaGLDK&type=jpg&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *             "id": 2202189684,
 *             "lastModified": 1713768766000,
 *             "fileType": "jpg",
 *             "md5": "5b6f68815e1eec83484d87128e2063fa"
 *         }
 *     }
 * 
 * @apiSuccessExample {json} 应答示例-查询指定序号量表报告
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 2960942553,
 *         "contactId": 22003303,
 *         "name": "AXL-20240731178843",
 *         "attribute": {
 *             "gender": "male",
 *             "strict": false,
 *             "language": "cn",
 *             "age": 29
 *         },
 *         "finished": true,
 *         "finishedTimestamp": 1722416990942,
 *         "state": 0,
 *         "timestamp": 1722416990677,
 *         "factors": [
 *             {
 *                 "name": "Somatization",
 *                 "score": 0.6666666666666666,
 *                 "displayName": "躯体化症状",
 *                 "description": "您无明显躯体化症状...",
 *                 "suggestion": "您没有明显躯体化症状，请继续保持。"
 *             },
 *             {
 *                 "name": "Obsession",
 *                 "score": 1,
 *                 "displayName": "强迫症状",
 *                 "description": "您没有明显强迫症状...",
 *                 "suggestion": "您没有明显强迫症状，请继续保持。"
 *              },
 *              ...
 *         ]
 *     }
 * 
 * @apiSuccessExample {json} 应答示例-批量查询结果
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 2,
 *         "page": 0,
 *         "list": [{
 *             "sn": 2642761518,
 *             "contactId": 22003303,
 *             ...
 *         }, {
 *             ...
 *         }]
 *     }
 *
 * @apiError 400 参数错误。
 * @apiError 401 令牌错误。
 * @apiError 404 提交请求错误。
 */


/**
 * @api {get} /aigc/psychology/report/part/:token 获取报告指定部分信息
 * @apiName 获取报告指定部分信息
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 *
 * @apiDescription 获取报告指定部分信息。
 *                 <br/>该接口可按照指定内容获取报告数据。
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {Number} sn 报告序号。
 * @apiQuery {Boolean} [content] 是否指定 Markdown 格式的内容数据。
 * @apiQuery {Boolean} [section] 是否指定分段心理评价数据。
 * @apiQuery {Boolean} [thought] 是否指定思维文本。
 * 
 * @apiSuccess {Number} sn 报告序号。
 * @apiSuccess {Number} state 报告状态。
 *                            <p><code>0</code>：<span class="default-value">已就绪</span></p>
 *                            <p><code>7</code>：<span class="default-value">正在处理数据</span></p>
 *                            <p><code>8</code>：<span class="default-value">正在生成内容</span></p>
 *                            <p><code>9</code>：<span class="default-value">发生故障</span></p>
 * @apiSuccess {String} [content] 报告内容，格式：Markdown 。
 * @apiSuccess {Array}  [sections] 报告分段内容列表，格式：Markdown 。
 * @apiSuccess {String} [thought] 报告推理思维文本内容，格式：Markdown 。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 1671400196,
 *         "state": 0,
 *         "content": "根据您提供的绘画图片...",
 *         "sections": [
 *             ...
 *         ],
 *         "thought": "我看到的绘画画面的特征如下：\n* 画面的画幅占画布整体面积较大。该特征说明受测人有以下心理特征：具有自我存在，具有外向，具有防御性。..."
 *     }
 * 
 * @apiError 400 参数错误。
 * @apiError 401 令牌错误。
 * @apiError 404 提交请求错误。
 */


/**
 * @api {post} /aigc/psychology/stop/:token 生成心理评测报告（停止）
 * @apiName 停止已经提交的报告生成任务
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 *
 * @apiDescription 停止已经提交的心理学报告生成任务。该接口将正在排队执行的的生成任务从队列里移除，如果报告已经完成生成或者正在推理则无法停止。
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Number} sn 报告的 SN 序号。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "sn": 1119207859
 *     }
 * 
 * @apiUse Report_Response
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 1119207859,
 *         "contactId": 22003303,
 *         "timestamp": 1713929178941,
 *         "name": "一般-2024-04-24 11:26:18",
 *         "theme": "一般",
 *         "finished": false,
 *         "attribute": {
 *             "gender": "male",
 *             "age": 24,
 *             "language": "cn",
 *             "strict": false
 *         },
 *         "state": 27,
 *         "fileLabel": {
 *             "fileName": "pt_001.jpg",
 *             "completedTime": 1713921950363,
 *             "fileCode": "AIjoFDlzyphCHatmTRmoPMhxAwgEXFahaYVpazWLyZTGoySNYxVFBRNYpMsaGLDK",
 *             "ownerId": 22003303,
 *             "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=AIjoFDlzyphCHatmTRmoPMhxAwgEXFahaYVpazWLyZTGoySNYxVFBRNYpMsaGLDK&type=jpg&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *             "sha1": "2e441e0b836c8581ed32d37671d86babe3b096ea",
 *             "fileSize": 971341,
 *             "domain": "default_domain",
 *             "expiryTime": 1716513950363,
 *             "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=AIjoFDlzyphCHatmTRmoPMhxAwgEXFahaYVpazWLyZTGoySNYxVFBRNYpMsaGLDK&type=jpg&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *             "id": 2202189684,
 *             "lastModified": 1713768766000,
 *             "fileType": "jpg",
 *             "md5": "5b6f68815e1eec83484d87128e2063fa"
 *         }
 *     }
 * 
 * @apiError 400 参数错误。
 * @apiError 401 令牌错误。
 * @apiError 404 没有符合要求的报告任务。
 */


/**
 * @api {post} /aigc/psychology/check/:token 检测图像是否是绘画
 * @apiName 检测图像是否是绘画
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 *
 * @apiDescription 检测图像是否是绘画。该检测接口通过计算机视觉技术检测图像，用于预检测用户上传的图像数据是否符合绘画图像要求，排除用户上传了其他照片或者空白绘画等情况。
 *                 <br/>需要注意的是该接口并不能绝对准确检测出图像是否符合绘画要求。
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} fileCode 文件码。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "fileCode": "qxplztvpmoyuZatmTRmoPMfHOGKuNPWnEGRNMDgzmHVuWySNYxZRHhHYrSMcuNLq"
 *     }
 * 
 * @apiSuccess {Boolean} result 文件是否是绘画图像。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "result": true
 *     }
 * 
 * @apiError 400 参数错误。
 * @apiError 401 令牌错误。
 * @apiError 404 提交请求错误。
 */


/**
 * @api {post} /aigc/psychology/report/reset/:token 重置或重算报告部分数据
 * @apiName 重置或重算报告部分数据
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 * 
 * @apiDescription 重置或重算指定报告的部分数据。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Number} sn 待操作报告序号。
 * @apiBody {Number} [attention] 指定报告的关注等级数据。不设置该数据时，将重新进行计算关注等级。
 *                               关注等级，从高到低依次为：<code>3</code>、<code>2</code>、<code>1</code>、<code>0</code>，
 *                               依次对应<span class="default-value">特殊关注</span>、<span class="default-value">重点关注</span>、
 *                               <span class="default-value">一般关注</span>和<span class="default-value">无需关注</span>。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "sn": 3245827114
 *     }
 * 
 * @apiUse Report_Response
 * 
 * @apiError 400 参数错误。
 * @apiError 401 令牌错误。
 * @apiError 404 提交请求错误。
 */


/**
 * @api {get} /aigc/psychology/scales/:token 量表 - 获取可用的量表清单
 * @apiName 获取可用的量表清单
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 *
 * @apiDescription 获取可用的量表清单。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiSuccess {Number} total 总数量。
 * @apiSuccess {Array} list 量表清单。
 * @apiSuccess {String} list.name 量表名称。
 * @apiSuccess {String} list.displayName 量表显示名称。
 * @apiSuccess {Number} list.sn 量表序号。
 * @apiSuccess {Number} list.timestamp 量表创建时的时间戳。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 1,
 *         "list": [{
 *             "name": "MBTI-93",
 *             "displayName": "MBTI性格测试",
 *             "sn": 912589799,
 *             "timestamp": 1716361998600
 *         }]
 *     }
 * 
 * @apiError 400 参数错误。
 * @apiError 401 令牌错误。
 * @apiError 404 数据错误。
 */


/**
 * @api {post} /aigc/psychology/scales/:token 量表 - 生成量表
 * @apiName 生成量表
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 *
 * @apiDescription 根据量表名生成量表实例。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} name 指定量表名。
 * @apiBody {String} gender 受测人性别。
 * @apiBody {Number} age 受测人年龄。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "name": "P-91",
 *         "gender": "male",
 *         "age": 29
 *     }
 * 
 * @apiUse Scale_Response
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 613095974,
 *         "name": "P-91",
 *         "displayName": "人格性格测试",
 *         "instruction": "该问卷用于揭示你是如何看待事物、如何做决定，答案无好坏之分...",
 *         "attribute": {
 *             "gender": "male",
 *             "age": 29,
 *             "language": "cn",
 *             "strict": false
 *         },
 *         "timestamp": 1716371198762,
 *         "sections": [{
 *             "title": "第一部分",
 *             "content": "哪一答案最接近地描述了你通常的思考和行为方式。",
 *             "questions": [{
 *                 "sn": 1,
 *                 "choice": "single",
 *                 "content": "当你某日想去某个地方，你会____？",
 *                 "answers": [{
 *                     "sn": 1,
 *                     "code": "A",
 *                     "content": "A. 计划好将做的事情以及何时做",
 *                     "chosen": false
 *                 }, {
 *                     "sn": 1,
 *                     "code": "B",
 *                     "content": "B. 什么都不想就去",
 *                     "chosen": false
 *                 }]
 *             }, ... ]
 *         }, ... ]
 *     }
 * 
 * @apiError 400 参数错误。
 * @apiError 401 令牌错误。
 * @apiError 404 数据错误。
 */


/**
 * @api {get} /aigc/psychology/scale/:token 量表 - 获取量表数据
 * @apiName 获取量表数据
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 *
 * @apiDescription 获取已经生成的量表数据。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {Number} sn 已生成的量表实例的序号。
 * 
 * @apiUse Scale_Response
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 613095974,
 *         "name": "P-91",
 *         "displayName": "人格特质测试",
 *         "instruction": "该问卷用于揭示你是如何看待事物、如何做决定，答案无好坏之分...",
 *         "attribute": {
 *             "gender": "male",
 *             "age": 29,
 *             "language": "cn",
 *             "strict": false
 *         },
 *         "timestamp": 1716371198762,
 *         "sections": [{
 *             "title": "第一部分",
 *             "content": "哪一答案最接近地描述了你通常的思考和行为方式。",
 *             "questions": [{
 *                 "sn": 1,
 *                 "choice": "single",
 *                 "content": "当你某日想去某个地方，你会____？",
 *                 "answers": [{
 *                     "sn": 1,
 *                     "code": "A",
 *                     "content": "A. 计划好将做的事情以及何时做",
 *                     "chosen": false
 *                 }, {
 *                     "sn": 1,
 *                     "code": "B",
 *                     "content": "B. 什么都不想就去",
 *                     "chosen": false
 *                 }]
 *             }, ... ]
 *         }, ... ]
 *     }
 * 
 * @apiSuccessExample {json} 应答示例-带评测结果数据
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 613095974,
 *         "name": "P-91",
 *         "displayName": "人格特质测试",
 *         "instruction": "该问卷用于揭示你是如何看待事物、如何做决定，答案无好坏之分...",
 *         "attribute": {
 *             "gender": "male",
 *             "age": 29,
 *             "language": "cn",
 *             "strict": false
 *         },
 *         "timestamp": 1716371198762,
 *         "sections": [{
 *             "title": "第一部分",
 *             "content": "哪一答案最接近地描述了你通常的思考和行为方式。",
 *             "questions": [{
 *                 "sn": 1,
 *                 "choice": "single",
 *                 "content": "当你某日想去某个地方，你会____？",
 *                 "answers": [{
 *                     "sn": 1,
 *                     "code": "A",
 *                     "content": "A. 计划好将做的事情以及何时做",
 *                     "chosen": true
 *                 }, {
 *                     "sn": 1,
 *                     "code": "B",
 *                     "content": "B. 什么都不想就去",
 *                     "chosen": false
 *                 }]
 *             }, ... ]
 *         }, ... ],
 *         "result": {
 *             "complete": true,
 *             "content": "**性格类型** ：总经理（ESTJ）\n\n...",
 *             "score": {
 *                 "I": {
 *                     "name": "内倾",
 *                     "value": 9
 *                 },
 *                 "E": {
 *                     "name": "外倾",
 *                     "value": 12
 *                 },
 *                 "S": {
 *                     "name": "感觉",
 *                     "value": 13
 *                 },
 *                 "N": {
 *                     "name": "直觉",
 *                     "value": 13
 *                 },
 *                 "T": {
 *                     "name": "思考",
 *                     "value": 12
 *                 },
 *                 "F": {
 *                     "name": "情感",
 *                     "value": 12
 *                 },
 *                 "P": {
 *                     "name": "知觉",
 *                     "value": 7
 *                 },
 *                 "J": {
 *                     "name": "判断",
 *                     "value": 15
 *                 }
 *             }
 *         }
 *     }
 * 
 * 
 * @apiError 400 参数错误。
 * @apiError 401 令牌错误。
 * @apiError 404 数据错误。
 */


/**
 * @api {post} /aigc/psychology/scale/:token 量表 - 提交量表的答题卡
 * @apiName 提交量表的答题卡
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 *
 * @apiDescription 提交量表的答题卡，并返回结果。提交的答题卡答案会记录到数据库中，如果提交的答题卡不完整，则不返回量表的计算结果。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Number} sn 量表实例的序号。
 * @apiBody {Array} answers 答题卡答案列表。
 * @apiBody {Number} answers.sn 答案序号。
 * @apiBody {String} answers.choice 该答案被选中的答案代码。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "sn": 613095974,
 *         "answers": [{
 *             "sn": 1,
 *             "choice": "A"
 *         }, {
 *             "sn": 2,
 *             "choice": "B"
 *         }, {
 *             "sn": 3,
 *             "choice": "B"
 *         }, ... ]
 *     }
 * 
 * @apiSuccess {Boolean} complete 是否已完成答题。
 * @apiSuccess {String} [content] 测验结果内容描述。
 * @apiSuccess {Object} [score] 量表相关项计分。
 * @apiSuccess {String} [score.name] 相关项名称。
 * @apiSuccess {Number} [score.value] 相关项得分。
 * @apiSuccess {Object} [score.level] 相关项得分对应的等级。
 * @apiSuccess {Number} [score.level.level] 相关项得分的等级。
 *                                          <p><code>0</code>：<span class="default-value">没有</span></p>
 *                                          <p><code>1</code>：<span class="default-value">略有</span></p>
 *                                          <p><code>2</code>：<span class="default-value">轻度</span></p>
 *                                          <p><code>3</code>：<span class="default-value">中度</span></p>
 *                                          <p><code>4</code>：<span class="default-value">重度</span></p>
 * @apiSuccess {String} [score.level.prefix] 相关项得分的等级前缀。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "complete": true,
 *         "content": "* 略有躯体化症状...",
 *         "score": {
 *             "Psychosis": {
 *                 "level": {
 *                     "level": 1,
 *                     "prefix": "略有"
 *                 },
 *                 "name": "精神病性症状",
 *                 "value": 0.8
 *             },
 *             "Paranoid": {
 *                 "level": {
 *                     "level": 0,
 *                     "prefix": "没有"
 *                 },
 *                 "name": "偏执症状",
 *                 "value": 0.16666666666666666
 *             },
 *             "Obsession": {
 *                 "level": {
 *                     "level": 1,
 *                     "prefix": "略有"
 *                 },
 *                 "name": "强迫症状",
 *                 "value": 1
 *             },
 *             "Anxiety": {
 *                 "level": {
 *                     "level": 1,
 *                     "prefix": "略有"
 *                 },
 *                 "name": "焦虑症状",
 *                 "value": 0.9
 *             },
 *             "Hostile": {
 *                 "level": {
 *                     "level": 1,
 *                     "prefix": "略有"
 *                 },
 *                 "name": "敌对性症状",
 *                 "value": 0.6666666666666666
 *             },
 *             ...
 *         }
 *     }
 * 
 * @apiError 400 参数错误。
 * @apiError 401 令牌错误。
 * @apiError 404 数据错误。
 */


/**
 * @api {post} /aigc/psychology/converse/:token 互动对话
 * @apiName 互动对话
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 *
 * @apiDescription 与模型进行互动对话，对话可以关联数据和上下文进行子任务推理。
 *                 该接口用于通用场景时需要填写 <code>relation</code> 参数。参数 <code>relations</code> 为兼容爱心理平台的旧参数，建议使用 <code>relation</code> 。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} channelCode 会话频道码。
 * @apiBody {Array}  [relations] 报告相关数据列表。用于兼容爱心理平台。
 * @apiBody {String} [relations.name] 报告对应的人名。
 * @apiBody {Number} [relations.reportSn] 报告序号。
 * @apiBody {Object} [context] 对话上下文数据。
 * @apiBody {String} [context.fileCode] 指定关联的文件码。
 * @apiBody {String} [context.promptTemplate] 指定提示词模板。
 * @apiBody {String} [context.strategy] 指定使用的对话策略文件。
 * @apiBody {Object} [relation] 对话的业务层关联关系。通用场景下需要填写该参数。
 * @apiBody {String} [relation.name] 对话参与人名称。
 * @apiBody {Number} [relation.reportSn] 对话关联的报告序号。
 * @apiBody {Number} [relation.articleSn] 对话关联的模板文章序号。
 * @apiBody {Number} [relation.comprehensiveReportSn] 对话关联的综合型报告序号。
 * @apiBody {Number} [relation.uid] 对话关联的用户ID 。有效用户才能触发子任务模式。
 * @apiBody {String} query 对话内容。
 * 
 * @apiParamExample {json} 请求示例（已过时）
 *     {
 *         "channelCode": "mnxRSaLdPuHEVaKA",
 *         "relations": [
 *             {
 *                 "name": "张伟",
 *                 "reportSn": 3694234996
 *             }
 *         ],
 *         "query": "这个人目前的状态对工作有什么影响？"
 *     }
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "channelCode": "mnxRSaLdPuHEVaKA",
 *         "relation": {
 *             "name": "典韦",
 *             "reportSn": "3694234996"
 *         },
 *         "context": {
 *             "strategy": "yunbao_for_counseling"
 *         },
 *         "query": "画里能看出什么心理状态"
 *     }
 * 
 * @apiSuccess {Number} sn 本次对话的序列号。
 * @apiSuccess {String} answer 问题的答案。
 * @apiSuccess {String} unit 参与此次对话的模型。
 * @apiSuccess {String} date 该对话的日期时间描述。
 * @apiSuccess {String} company 该对话所属的机构名称。
 * @apiSuccess {Number} timestamp 该对话的时间戳。
 * @apiSuccess {Array}  [queryFileLabels] 本次对话关联的输入文件标签列表。
 * @apiSuccess {Object} context 上下文。
 * @apiSuccess {Number} context.id 上下文数据的 ID 。
 * @apiSuccess {String} context.domain 上下文所属的域。
 * @apiSuccess {String} context.type 上下文类型。<code>simplex</code> 表示单一上下文，<code>complex</code> 表示复合上下文。
 * @apiSuccess {Boolean} context.inferable 上下文内容是否可推理。
 * @apiSuccess {Boolean} context.inferring 是否正在进行子任务推理。
 * @apiSuccess {Boolean} context.networking 是否联网进行搜索，并对搜索结果进行推理。
 * @apiSuccess {Number} context.timestamp 上下文数据的时间戳。
 * @apiSuccess {Array}  context.resources 上下文里关联的资源列表。
 * @apiSuccess {Number} context.resources.sn 资源的序列号。
 * @apiSuccess {String} context.resources.subject 资源归类主题。
 * @apiSuccess {Object} context.resources.payload 资源数据负载。
 * @apiSuccess {Array}  context.resources.payload.attachments 负载附件列表。
 * @apiSuccess {Number} context.resources.payload.attachments.id 附件的ID，例如：绘画报告的序号。
 * @apiSuccess {String} context.resources.payload.attachments.type 附件类型。
 *                                                                 <p><code>PsychologyReport</code>：<span class="default-value">心理学报告</span></p>
 * 
 * @apiSuccessExample {json} 一般应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 849831725,
 *         "answer": "对于轻度抑郁的人...",
 *         "date": "2025-02-03 14:20:11",
 *         "unit": "AiXinLi",
 *         "timestamp": 1719745913489,
 *         "company": "白泽京智",
 *         "context": {
 *             "domain": "",
 *             "resources": [],
 *             "id": 2823547304,
 *             "type": "simplex",
 *             "inferable": false,
 *             "inferring": false,
 *             "networkingInferEnd": false,
 *             "networking": false,
 *             "timestamp": 1719745912529
 *         }
 *     }
 * 
 * @apiSuccessExample {json} 具备上下文的应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "date": "2025-02-03 14:20:11",
 *         "unit": "AiXinLi",
 *         "answer": "进行评测需要知道受测人的性别和年龄，请告诉我画这幅画的受测人的**性别**和**年龄**。",
 *         "company": "白泽京智",
 *         "sn": 3905815584,
 *         "queryFileLabels": [
 *             {
 *                 "fileName": "painting_normal_10.jpg",
 *                 "completedTime": 1733447662913,
 *                 "fileCode": "yEryPsWMYphCHatmTRmoPMvlSKqyfJCFkchtgjgFYVfKQaAzmnbVHHboRYwquJJY",
 *                 "ownerId": 22003303,
 *                 "fileSecureURL": "https://localhost:7017/filestorage/file/?fc=yEryPsWMYphCHatmTRmoPMvlSKqyfJCFkchtgjgFYVfKQaAzmnbVHHboRYwquJJY&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=jpg",
 *                 "sha1": "6ed780c20f5d2dde1d3f7d333495b5dcc4b03bf3",
 *                 "fileSize": 3397892,
 *                 "domain": "default_domain",
 *                 "expiryTime": 3310247662913,
 *                 "fileURL": "http://localhost:7010/filestorage/file/?fc=yEryPsWMYphCHatmTRmoPMvlSKqyfJCFkchtgjgFYVfKQaAzmnbVHHboRYwquJJY&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=jpg",
 *                 "id": 641150805,
 *                 "lastModified": 1733447657006,
 *                 "fileType": "jpg",
 *                 "md5": "4c98afb553fdea6ab1f8eb3f1834ba67"
 *             }
 *         ],
 *         "timestamp": 1738563611198
 *     }
 * 
 * @apiSuccessExample {json} 进行子任务推理应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "date": "2025-02-03 14:32:04",
 *         "unit": "AiXinLi",
 *         "answer": "正在为受测人生成报告，该受测人为女性，年龄是15岁。报告生成需要数分钟，请稍候……",
 *         "company": "白泽京智",
 *         "sn": 3194100716,
 *         "context": {
 *             "inferable": false,
 *             "domain": "",
 *             "resources": [{
 *                    "sn": 1400602104,
 *                    "subject": "Attachment",
 *                    "payload": {
 *                        "attachments": [{
 *                            "id": 3325739656,
 *                            "type": "PsychologyReport"
 *                        }]
 *                    }
 *             }],
 *             "networkingInferEnd": false,
 *             "networking": false,
 *             "id": 3381774078,
 *             "type": "complex",
 *             "inferring": true,
 *             "timestamp": 1738564324101
 *         },
 *         "queryFileLabels": [
 *             {
 *                 "fileName": "painting_normal_10.jpg",
 *                 "completedTime": 1733447662913,
 *                 "fileCode": "yEryPsWMYphCHatmTRmoPMvlSKqyfJCFkchtgjgFYVfKQaAzmnbVHHboRYwquJJY",
 *                 "ownerId": 22003303,
 *                 "fileSecureURL": "https://localhost:7017/filestorage/file/?fc=yEryPsWMYphCHatmTRmoPMvlSKqyfJCFkchtgjgFYVfKQaAzmnbVHHboRYwquJJY&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=jpg",
 *                 "sha1": "6ed780c20f5d2dde1d3f7d333495b5dcc4b03bf3",
 *                 "fileSize": 3397892,
 *                 "domain": "default_domain",
 *                 "expiryTime": 3310247662913,
 *                 "fileURL": "http://localhost:7010/filestorage/file/?fc=yEryPsWMYphCHatmTRmoPMvlSKqyfJCFkchtgjgFYVfKQaAzmnbVHHboRYwquJJY&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=jpg",
 *                 "id": 641150805,
 *                 "lastModified": 1733447657006,
 *                 "fileType": "jpg",
 *                 "md5": "4c98afb553fdea6ab1f8eb3f1834ba67"
 *             }
 *         ],
 *         "timestamp": 1738564324097
 *     }
 * 
 * @apiError 400 参数错误。
 * @apiError 401 令牌错误。
 * @apiError 404 数据错误。
 */


/**
 * @api {post} /aigc/psychology/comprehensive/:token 综合型主题测评内容生成（执行）
 * @apiName 综合型主题测评内容生成（执行）
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 *
 * @apiDescription 根据不同的主题生成综合型测评内容。<br/>
 *                 该接口调用成功后返回任务的<code>sn</code>，通过该<code>sn</code>调用查询接口查询生成结果。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} theme 测评主题。
 *                         <p><code>SRBC</code>：<span class="default-value">《心画·双生》——伴侣潜意识关系投射测评</span></p>
 * @apiBody {Array}  comprehensives 参与测评的受测人数组。
 * @apiBody {String} comprehensives.name 受测人名称。此参数用于区别不同受测人，按照脱敏原则不建议使用受测人姓名。
 * @apiBody {String} [comprehensives.role] 受测人在测评里的角色。根据不同测试的选填参数。
 * @apiBody {Object} comprehensives.attribute 受测人基本属性信息。
 * @apiBody {String} comprehensives.attribute.gender 受测人性别。男性：<code>male</code>，女性：<code>female</code>。
 * @apiBody {Number} comprehensives.attribute.age 受测人年龄。
 * @apiBody {Array}  [comprehensives.fileCodes] 受测人的关联文件的文件码数组。与参数<code>fileUrls</code>二选一使用。
 * @apiBody {Array}  [comprehensives.fileUrls] 受测人的关联文件的文件链接数组。与参数<code>fileCodes</code>二选一使用。
 * @apiBody {Array}  comprehensives.choices 受测人的相关关键词数组。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "theme": "SRBC",
 *         "comprehensives": [{
 *             "name": "受测人1",
 *             "attribute": {
 *                 "gender": "male",
 *                 "age": 29
 *             },
 *             "fileCodes": [
 *                 "nspmAGrMYphCHatmTRmoPMvlSKqyfJCpOEZzctsvcLDQyIwNMRlhLlNItGGkETJq"
 *             ],
 *             "choices": [
 *                 "战场", "旋律", "影子"
 *             ]
 *         }, {
 *             "name": "受测人2",
 *             "attribute": {
 *                 "gender": "female",
 *                 "age": 26
 *             },
 *             "fileCodes": [
 *                 "suuAxsWMYphCHatmTRmoPMvlSKqyfJCpOEZzctsvcNTcgsejcbxLVXxWpKMaQHXS"
 *             ],
 *             "choices": [
 *                 "港湾", "灯火", "影子"
 *             ]
 *         }]
 *     }
 * 
 * @apiSuccess {Number} sn 任务的报告序号。
 * @apiSuccess {String} theme 测评主题。
 * @apiSuccess {Number} state 任务状态码：
 *                            <p><code>0</code>：<span class="default-value">成功生成</span></p>
 *                            <p><code>7</code>：<span class="default-value">正在推理</span></p>
 *                            <p><code>9</code>：<span class="default-value">发生错误</span></p>
 * @apiSuccess {Number} timestamp 任务时间戳。
 * @apiSuccess {Boolean} finished 任务是否已结束。
 * @apiSuccess {Array} comprehensives 参与测评的受测人数组。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 560158024,
 *         "state": 7,
 *         "theme": "SRBC",
 *         "finished": false,
 *         "timestamp": 1773898779745,
 *         "elapsed": 0,
 *         "summary": "",
 *         "sections": [],
 *         "comprehensives": [{
 *             "name": "受测人1",
 *             "attribute": {
 *                 "gender": "male",
 *                 "language": "cn",
 *                 "strict": false,
 *                 "age": 29
 *             },
 *             "files": [{
 *                 "fileName": "painting_20250607_01.jpg",
 *                 "completedTime": 1749263561240,
 *                 "fileCode": "nspmAGrMYphCHatmTRmoPMvlSKqyfJCpOEZzctsvcLDQyIwNMRlhLlNItGGkETJq",
 *                 "ownerId": 22003303,
 *                 "fileSecureURL": "https://192.168.0.110:7017/filestorage/file/?fc=nspmAGrMYphCHatmTRmoPMvlSKqyfJCpOEZzctsvcLDQyIwNMRlhLlNItGGkETJq&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=png",
 *                 "sha1": "9b1eddbb41a63673cde918d3df258feb1e5db02c",
 *                 "fileSize": 9780382,
 *                 "domain": "default_domain",
 *                 "expiryTime": 3326063561240,
 *                 "fileURL": "http://192.168.0.110:7010/filestorage/file/?fc=nspmAGrMYphCHatmTRmoPMvlSKqyfJCpOEZzctsvcLDQyIwNMRlhLlNItGGkETJq&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=png",
 *                 "id": 3058081652,
 *                 "lastModified": 1749263544177,
 *                 "fileType": "png",
 *                 "md5": "1ddffe6f33dbeac7d2cba66a93df55e4"
 *             }],          
 *             "choices": [
 *                 "战场",
 *                 "旋律",
 *                 "影子"
 *             ]
 *         }, {
 *             "name": "受测人2",
 *             "attribute": {
 *                 "gender": "female",
 *                 "language": "cn",
 *                 "strict": false,
 *                 "age": 26
 *             },
 *             "files": [{
 *                 "fileName": "painting_20250607_02jpg",
 *                 "completedTime": 1749263612902,
 *                 "fileCode": "suuAxsWMYphCHatmTRmoPMvlSKqyfJCpOEZzctsvcNTcgsejcbxLVXxWpKMaQHXS",
 *                 "ownerId": 22003303,
 *                 "fileSecureURL": "https://192.168.0.110:7017/filestorage/file/?fc=suuAxsWMYphCHatmTRmoPMvlSKqyfJCpOEZzctsvcNTcgsejcbxLVXxWpKMaQHXS&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=png",
 *                 "sha1": "0bff84d75a61b49ce1a4f19210aacf3fa5d82dcc",
 *                 "fileSize": 12484530,
 *                 "domain": "default_domain",
 *                 "expiryTime": 3326063612902,
 *                 "fileURL": "http://192.168.0.110:7010/filestorage/file/?fc=suuAxsWMYphCHatmTRmoPMvlSKqyfJCpOEZzctsvcNTcgsejcbxLVXxWpKMaQHXS&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=png",
 *                 "id": 1764616229,
 *                 "lastModified": 1749263592306,
 *                 "fileType": "png",
 *                 "md5": "80e41c112327f58c875091390a1a550f"
 *             }],
 *             "choices": [
 *                 "港湾",
 *                 "灯火",
 *                 "影子"
 *             ]
 *         }]
 *     }
 * 
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 */

/**
 * @api {get} /aigc/psychology/comprehensive/:token 综合型主题测评内容生成（查询）
 * @apiName 综合型主题测评内容生成（查询）
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 *
 * @apiDescription 查询综合型主题测评内容。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {Number} sn 报告序号。
 * 
 * @apiSuccess {Number} sn 报告序号。
 * @apiSuccess {String} theme 测评主题。
 * @apiSuccess {Number} state 报告状态码：
 *                            <p><code>0</code>：<span class="default-value">成功生成</span></p>
 *                            <p><code>7</code>：<span class="default-value">正在处理文件</span></p>
 *                            <p><code>8</code>：<span class="default-value">正在推理</span></p>
 *                            <p><code>9</code>：<span class="default-value">发生错误</span></p>
 * @apiSuccess {Number} timestamp 报告时间戳。
 * @apiSuccess {Boolean} finished 报告是否已结束。
 * @apiSuccess {Number} elapsed 报告推理用时，单位：毫秒。
 * @apiSuccess {String} summary 报告摘要。
 * @apiSuccess {Array}  sections 报告内容段落数组。
 * @apiSuccess {String} sections.title 报告内容段落标题。
 * @apiSuccess {Array}  sections.contents 报告段落内容数组。
 * @apiSuccess {Array}  comprehensives 参与测评的受测人数组。
 * @apiSuccess {String} comprehensives.name 参与测评的受测人名称。
 * @apiSuccess {String} [comprehensives.role] 参与测评的受测人角色。
 * @apiSuccess {Object} comprehensives.attribute 参与测评的受测人属性信息。
 * @apiSuccess {Array}  comprehensives.choices 参与测评的受测人选择的关键词数组。
 * @apiSuccess {Array}  comprehensives.sections 该受测人的测评结果段落。
 * @apiSuccess {String} [comprehensives.sections.indicator] 该受测人的测评分类指标名称。
 * @apiSuccess {String} comprehensives.sections.title 该受测人的指标标题。
 * @apiSuccess {Array}  comprehensives.sections.contents 该受测人的指标内容数组。
 * @apiSuccess {Array}  comprehensives.files 该受测人的关联文件数组。（数据格式参看文件标签）
 * @apiSuccess {Array}  comprehensives.scales 该受测人的关联量表数组。（数据格式参看量表）
 * @apiSuccess {Number} comprehensives.scales.contactId 量表所属的触点ID 。
 * @apiSuccess {String} comprehensives.scales.name 量表名称。
 * @apiSuccess {String} comprehensives.scales.displayName 量表显示名称。
 * @apiSuccess {String} comprehensives.scales.instruction 量表指导语。
 * @apiSuccess {Number} comprehensives.scales.sn 量表序号。
 * @apiSuccess {Number} comprehensives.scales.timestamp 量表创建时的时间戳。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 782225854,
 *         "theme": "SRBC",
 *         "state": 0,
 *         "timestamp": 1773912803205,
 *         "finished": true,
 *         "elapsed": 165480,
 *         "summary": "你们的爱情像一场温柔而复杂的拉锯战，充满了抓与逃、依赖与自由的矛盾...",
 *         "sections": [{
 *             "title": "双人潜意识合盘——融合区（你们共同潜意识的基石）",
 *             "contents": [
 *                 "你们的关系就像一首动人的交响乐，时而激昂，时而温柔，充满了张力与共鸣。榕树和蒲公英看似截然不同..."
 *             ]
 *         }],
 *         "comprehensives": [{
 *             "name": "受测人1",
 *             "attribute": {
 *                 "gender": "male",
 *                 "language": "cn",
 *                 "strict": false,
 *                 "age": 29
 *             },
 *             "choices": [
 *                 "战场",
 *                 "旋律",
 *                 "影子"
 *             ],
 *             "sections": [{
 *                 "indicator": "Dandelion",
 *                 "title": "蒲公英型",
 *                 "contents": [
 *                     "你们的关系洒脱不羁，彼此保持着适度的独立与自由。虽然你们乐于给予对方广阔的发展空间，但这种开放式的相处方式也可能使感情显得不够稳固，难以建立深厚的情感纽带。"
 *                 ]
 *             }, {
 *                 "title": "你隐藏的期待，TA并未察觉",
 *                 "contents": [
 *                     "作为蒲公英型的你，外表洒脱不羁，像一阵风一样来去自由，但内心深处其实渴望有一个能让自己安心停靠的港湾..."
 *                 ]
 *             }],
 *             "files": [{
 *                 "fileName": "painting_20250607_01.jpg",
 *                 "completedTime": 1749263561240,
 *                 "fileCode": "nspmAGrMYphCHatmTRmoPMvlSKqyfJCpOEZzctsvcLDQyIwNMRlhLlNItGGkETJq",
 *                 "ownerId": 22003303,
 *                 "fileSecureURL": "https://192.168.0.110:7017/filestorage/file/?fc=nspmAGrMYphCHatmTRmoPMvlSKqyfJCpOEZzctsvcLDQyIwNMRlhLlNItGGkETJq&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=png",
 *                 "sha1": "9b1eddbb41a63673cde918d3df258feb1e5db02c",
 *                 "fileSize": 9780382,
 *                 "domain": "default_domain",
 *                 "expiryTime": 3326063561240,
 *                 "fileURL": "http://192.168.0.110:7010/filestorage/file/?fc=nspmAGrMYphCHatmTRmoPMvlSKqyfJCpOEZzctsvcLDQyIwNMRlhLlNItGGkETJq&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=png",
 *                 "id": 3058081652,
 *                 "lastModified": 1749263544177,
 *                 "fileType": "png",
 *                 "md5": "1ddffe6f33dbeac7d2cba66a93df55e4"
 *             }],
 *             "scales": [{
 *                 "contactId": 22003303,
 *                 "displayName": "Subconscious Relationship Between a Couple",
 *                 "instruction": "请从以下12个词语中，直觉选择3个最符合你们关系氛围的词。",
 *                 "scoringScript": "",
 *                 "name": "SRBC",
 *                 "sn": 1299731740,
 *                 "sections": [{
 *                     "questions": [{
 *                         "sn": 1,
 *                         "choice": "multiple",
 *                         "prompt": "",
 *                         "inferenceResult": "",
 *                         "content": "请从以下12个词语中，直觉选择3个最符合你们关系氛围的词。",
 *                         "questionContent": "请从以下12个词语中，直觉选择3个最符合你们关系氛围的词。",
 *                         "hidden": false,
 *                         "inference": "",
 *                         "answerContent": "",
 *                         "answers": [{
 *                             "code": "1",
 *                             "sn": 1,
 *                             "content": "港湾",
 *                             "chosen": false
 *                         }, {
 *                             "code": "2",
 *                             "sn": 1,
 *                             "content": "战场",
 *                             "chosen": true
 *                         }, {
 *                             "code": "3",
 *                             "sn": 1,
 *                             "content": "舞台",
 *                             "chosen": false
 *                         }, ...]
 *                     }],
 *                     "title": "",
 *                     "content": ""
 *                 }],
 *                 "timestamp": 1773912803217
 *             }]
 *         }, {
 *             "name": "受测人2",
 *             "attribute": {
 *                 "gender": "female",
 *                 "language": "cn",
 *                 "strict": false,
 *                 "age": 26
 *             },
 *             "choices": [
 *                 "港湾",
 *                 "灯火",
 *                 "影子"
 *             ],
 *             "sections": [{
 *                 "indicator": "BanyanTree",
 *                 "title": "榕树型",
 *                 "contents": [
 *                     "你们的爱如同榕树般，独木成林，根系相互交织，彼此紧密依存。你们习惯了共生，热衷于分享生活的点滴，但偶尔也会因过度的依恋而失去各自的私人空间。"
 *                 ]
 *             }, {
 *                 "title": "你隐藏的期待，TA并未察觉",
 *                 "contents": [
 *                     "作为榕树型的你，外表看似依赖又温暖，渴望与对方紧密相连，但内心深处其实藏着一个未被言说的期待——你渴望被更深地看见..."
 *                 ]
 *             }],
 *             "files": [{
 *                 "fileName": "painting_20250607_02jpg",
 *                 "completedTime": 1749263612902,
 *                 "fileCode": "suuAxsWMYphCHatmTRmoPMvlSKqyfJCpOEZzctsvcNTcgsejcbxLVXxWpKMaQHXS",
 *                 "ownerId": 22003303,
 *                 "fileSecureURL": "https://192.168.0.110:7017/filestorage/file/?fc=suuAxsWMYphCHatmTRmoPMvlSKqyfJCpOEZzctsvcNTcgsejcbxLVXxWpKMaQHXS&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=png",
 *                 "sha1": "0bff84d75a61b49ce1a4f19210aacf3fa5d82dcc",
 *                 "fileSize": 12484530,
 *                 "domain": "default_domain",
 *                 "expiryTime": 3326063612902,
 *                 "fileURL": "http://192.168.0.110:7010/filestorage/file/?fc=suuAxsWMYphCHatmTRmoPMvlSKqyfJCpOEZzctsvcNTcgsejcbxLVXxWpKMaQHXS&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=png",
 *                 "id": 1764616229,
 *                 "lastModified": 1749263592306,
 *                 "fileType": "png",
 *                 "md5": "80e41c112327f58c875091390a1a550f"
 *             }],
 *             "scales": [{
 *                 "contactId": 22003303,
 *                 "displayName": "Subconscious Relationship Between a Couple",
 *                 "instruction": "请从以下12个词语中，直觉选择3个最符合你们关系氛围的词。",
 *                 "scoringScript": "",
 *                 "name": "SRBC",
 *                 "sn": 4124029206,
 *                 "sections": [{
 *                     "questions": [{
 *                         "questionContent": "请从以下12个词语中，直觉选择3个最符合你们关系氛围的词。",
 *                         "hidden": false,
 *                         "inference": "",
 *                         "answerContent": "",
 *                         "answers": [{
 *                             "code": "1",
 *                             "sn": 1,
 *                             "content": "港湾",
 *                             "chosen": true
 *                         }, {
 *                             "code": "2",
 *                             "sn": 1,
 *                             "content": "战场",
 *                             "chosen": false
 *                         }, {
 *                             "code": "3",
 *                             "sn": 1,
 *                             "content": "舞台",
 *                             "chosen": false
 *                         }, ...],
 *                         "sn": 1,
 *                         "choice": "multiple",
 *                         "prompt": "",
 *                         "inferenceResult": "",
 *                         "content": "请从以下12个词语中，直觉选择3个最符合你们关系氛围的词。"
 *                     }],
 *                     "title": "",
 *                     "content": ""
 *                 }],
 *                 "timestamp": 1773912803224
 *             }]
 *         }]
 *     }
 * 
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 */


/**
 * @api {post} /aigc/psychology/template/:token 模板化生成指定分析指标的测评报告（执行）
 * @apiName 模板化生成指定分析指标的测评报告（执行）
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 *
 * @apiDescription 以模板化方式生成绘画内容里指定的分析指标的测评报告。例如：使用 <code>depression</code> 模板生成针对抑郁倾向指标的分析结果。<br/>
 *                 该接口对于报告内容会进行通俗化描述，以便通俗化阅读。<br/>
 *                 该接口调用成功后返回任务的<code>sn</code>，通过该<code>sn</code>调用查询接口查询推理结果。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Object} attribute 受测人信息。
 * @apiBody {Number} attribute.age 受测人年龄。
 * @apiBody {String} attribute.gender 受测人性别。男性：<code>male</code>，女性：<code>female</code>。
 * @apiBody {String} theme 推理使用的绘画测评主题。支持的主题有：
 *                         <p><code>HTP</code>：<span class="default-value">房树人绘画</span></p>
 *                         <p><code>PIR</code>：<span class="default-value">雨中人绘画</span></p>
 *                         <p><code>TT</code>：<span class="default-value">树木绘画</span></p>
 *                         <p><code>SP</code>：<span class="default-value">自画像</span></p>
 * @apiBody {String} templateName 指定指标模板。支持的模板有：
 *                                <p><code>depression</code>：<span class="default-value">内心的风景-潜意识情绪与抑郁倾向测评</span></p>
 *                                <p><code>anxiety</code>：<span class="default-value">迷雾与彼岸-潜意识焦虑测评</span></p>
 *                                <p><code>obsession</code>：<span class="default-value">内心的秩序-潜意识控制欲与强迫测评</span></p>
 *                                <p><code>stress</code>：<span class="default-value">风雨中的你-潜意识压力测评</span>。该模板仅适用于<code>PIR</code>绘画</p>
 *                                <p><code>popularization</code>：<span class="default-value">潜意识画像</span>。该模板仅适用于<code>HTP</code>绘画，用于生成通俗化的房树人测评报告内容</p>
 * @apiBody {Boolean} [structured] 是否结构化输出结果。默认值：<code>false</code>。
 * @apiBody {String} [fileCode] 指定绘画文件的文件码。与参数<code>fileUrl</code>二选一使用。
 * @apiBody {String} [fileUrl] 指定绘画文件的文件链接。与参数<code>fileCode</code>二选一使用。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "attribute": {
 *             "age": 31,
 *             "gender": "male"
 *         },
 *         "theme": "HTP",
 *         "templateName": "depression",
 *         "fileCode": "ntpmAGrMYphCHatmTRmoPMvlSKqyfJCpOEZzSvkveLDQyIwNMRlhLlNItGGkEJLi"
 *     }
 * 
 * @apiSuccess {Number} sn 推理任务的报告序号。
 * @apiSuccess {Object} attribute 受测人信息。
 * @apiSuccess {Number} attribute.age 受测人年龄。
 * @apiSuccess {String} attribute.gender 受测人性别。
 * @apiSuccess {String} theme 推理使用的绘画测评主题。
 * @apiSuccess {String} templateName 指标模板。
 * @apiSuccess {String} fileCode 绘画文件的文件码。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 985740654,
 *         "attribute": {
 *             "gender": "male",
 *             "age": 31
 *         },
 *         "theme": "HTP",
 *         "templateName": "depression",
 *         "fileCode": "ntpmAGrMYphCHatmTRmoPMvlSKqyfJCpOEZzSvkveLDQyIwNMRlhLlNItGGkEJLi"  
 *     }
 * 
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 */

/**
 * @api {get} /aigc/psychology/template/:token 模板化生成指定分析指标的测评报告（查询）
 * @apiName 模板化生成指定分析指标的测评报告（查询）
 * @apiGroup Psychology
 * @apiVersion 1.0.0
 *
 * @apiDescription 查询以模板化方式生成绘画内容里指定的分析指标的测评报告。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {Number} sn 报告序号。
 * 
 * @apiSuccess {Number} sn 推理任务的报告序号。
 * @apiSuccess {String} theme 推理使用的绘画测评主题。
 * @apiSuccess {String} templateName 指标模板。
 * @apiSuccess {Number} state 工作状态码：
 *                            <p><code>0</code>：<span class="default-value">成功生成</span></p>
 *                            <p><code>7</code>：<span class="default-value">正在推理</span></p>
 *                            <p><code>9</code>：<span class="default-value">发生错误</span></p>
 * @apiSuccess {Number} timestamp 任务执行时的时间戳。
 * @apiSuccess {Number} rate 指标测评等级。
 *                           <p><code>1</code>：<span class="default-value">很低</span></p>
 *                           <p><code>2</code>：<span class="default-value">低</span></p>
 *                           <p><code>3</code>：<span class="default-value">中等</span></p>
 *                           <p><code>4</code>：<span class="default-value">高</span></p>
 * @apiSuccess {String} title 内容标题。
 * @apiSuccess {String} [content] 内容文本。
 * @apiSuccess {Array}  [paragraphs] 内容段落数组。
 * @apiSuccess {String} [paragraphs.title] 内容段落标题。
 * @apiSuccess {String} [paragraphs.content] 内容段落文本内容。
 * 
 * @apiSuccessExample {json} 应答示例（正在处理）
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 985740654,
 *         "templateName": "depression",
 *         "theme": "HTP",
 *         "state": 7,
 *         "timestamp": 1775264371165,
 *         "title": "内心的风景-潜意识情绪与抑郁倾向测评"
 *     }
 * 
 * @apiSuccessExample {json} 应答示例（非结构化输出）
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 985740654,
 *         "templateName": "depression",
 *         "theme": "HTP",
 *         "state": 0,
 *         "rate": 2,
 *         "timestamp": 1775264371165,
 *         "title": "内心的风景-潜意识情绪与抑郁倾向测评",
 *         "paragraphs": [{
 *             "title": "暮秋落叶之树",
 *             "content": "您的内心风景画像是： **暮秋落叶之树**\n\n## 状态解析（你的内心现在是什么样）\n\n从您的绘画作品中，我们可以看到一棵在暮秋时节的树，树干依然挺拔，但叶子已经开始变黄、掉落，甚至果实也显得有些干瘪。这幅画面反映出您目前的心理状态处于一种“心累”的状态..."
 *         }]
 *     }
 * 
 * @apiSuccessExample {json} 应答示例（结构化输出）
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 2051922769,
 *         "templateName": "anxiety",
 *         "theme": "HTP",
 *         "state": 0,
 *         "rate": 3,
 *         "timestamp": 1775361584910,
 *         "title": "迷雾与彼岸-潜意识焦虑测评",
 *         "content": "您的内心焦虑画像是： **狂风巨浪中的独木桥**",
 *         "paragraphs": [{
 *             "title": "## 状态解析",
 *             "content": "你的内心正经历着一场风暴。桥下的水不再平静，而是变成了随时会把你吞噬的漩涡和巨浪；桥面变得像独木桥一样狭窄..."
 *         }, {
 *             "title": "## 具体表现与底层逻辑",
 *             "content": "- 具体表现：频繁出现心慌、心悸、胸闷、手心出汗、肠胃痉挛等强烈的躯体症状；注意力极难集中..."
 *         }, {
 *             "title": "## 专属破局建议",
 *             "content": "- 启动“物理接地（Grounding）”：焦虑发作觉得心慌时，立刻寻找身边5个能看到的东西..."
 *         }]
 *     }
 * 
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 */
