/**
 * @apiDefine Natural_Language_Processing 自然语言处理
 */


/**
 * @apiDefine GenerativeRecordAsAttachment
 * @apiBody {Array} [records.object.queryAdditions] 与<code>queryFileLabels</code>参数二选一。附加推理文本内容。如果文本内容超过模型上下文窗口长度将自动截断超出的文本内容。
 * @apiBody {Array} [records.object.queryFileLabels] 与<code>queryAdditions</code>参数二选一。附加推理文件的 <code>FileLabel</code> 对象。使用文件上传接口上传文件后，获得该文件的 <code>FileLabel</code> 对象。
 */


/**
 * @api {post} /aigc/nlp/sentiment/:token 文本情感分析
 * @apiName 文本情感分析
 * @apiGroup Natural_Language_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 分析处理带有情感色彩的主观性文本，判断文本观点的正负倾向性。
 *
 * @apiParam {String} token 授权令牌。
 *
 * @apiBody {String} text 文本内容。
 *
 * @apiParamExample {json} 请求示例
 *     {
 *         "text": "今天心情很好呀"
 *     }
 *
 * @apiSuccess {String} label 评价标签，<code>negative</code> 负面、 <code>positive</code> 正面或<code>neutral</code> 中立（中性）。
 * @apiSuccess {Number} negative 负面评价得分。
 * @apiSuccess {Number} positive 正面评价得分。
 * @apiSuccess {Array} paragraphs 内容段落得分。
 *
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "label": "positive",
 *         "negative": 0.01948561891913414,
 *         "positive": 0.9805143475532532,
 *         "paragraphs": [
 *             {
 *                 "label": "positive",
 *                 "negative": 0.01948561891913414,
 *                 "positive": 0.9805143475532532
 *             }
 *         ]
 *     }
 *
 * @apiError 400 任务执行时发生故障。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 * @apiError 404 任务参数不完整。
 * @apiError 406 接口拒绝请求。
 */


/**
 * @api {post} /aigc/nlp/summarization/:token 生成文本摘要
 * @apiName 生成文本摘要
 * @apiGroup Natural_Language_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 生成文本的内容摘要。
 *
 * @apiParam {String} token 授权令牌。
 *
 * @apiBody {String} text 文本内容。
 *
 * @apiParamExample {json} 请求示例
 *     {
 *         "text": "时信魔方是一款面向开发者的实时协作开发框架，旨在帮助开发者快速建立基础的实时协作能力，让开发者能够集中精力解决用户的复杂业务需求，提高用户满意度。时信魔方框架结构采用了分层设计，包括用户界面层、业务逻辑层、数据存储层和框架管理层。"
 *     }
 *
 * @apiSuccess {String} summarization 文本摘要内容。
 *
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "summarization": "时信魔方是基于分层设计的实时协作开发框架"
 *     }
 *
 * @apiError 400 任务执行时发生故障。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 * @apiError 404 任务参数不完整。
 * @apiError 406 接口拒绝请求。
 */


/**
 * @api {post} /aigc/chat/:token 文本内容生成与推理
 * @apiName 文本内容生成与推理
 * @apiGroup Natural_Language_Processing
 * @apiVersion 1.0.0
 * 
 * @apiDescription 向指定大模型传送待推理内容或提示词。模型返回应答信息。
 *                 <p>也可使用预先提供的附件数据进行推理和内容生成：附件数据可以是文本原文或者文件，使用文件时先使用上传文件接口将文件上传，再将文件对象填写到 <code>records</code> 参数里。具体用法见下方「请求示例3」。</p>
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} channel 频道代码。频道代码用于识别频道，并通过该频道代码查询频道状态和异步处理时的数据。
 * @apiBody {String} content 发送给模型的对话内容。内容长度受限于所选择模型的限制，不同模型可接收的内容长度不同。长度超限时返回错误。
 * @apiBody {String} [unit] 可选参数。指定需要使用的模型名称。支持<code>Baize</code>和<code>Baize2</code> 两个模型，默认值：<code>Baize</code>。
 * @apiBody {Object} [option] 可选参数。指定模型的工作参数。
 * @apiBody {Number} [option.temperature] 可选参数。指定 <code>Temperature</code> 参数。默认值：<code>0.3</code>。<br/>
 *                   该参数控制着生成的随机性。较高的温度值会增加文本的多样性和创造性，但可能会牺牲一些准确性或连贯性。具体地，<code>temperature</code> 会调整概率输出的 <b>softmax</b> 概率分布，如果 <code>temperature</code> 的值为<code>1</code>，则没有任何调整；如果其值比<code>1</code>大，则会生成更加随机的文本；如果其值比<code>1</code>小，则生成的文本更加保守。
 * @apiBody {Number} [option.topP] 可选参数。指定 <code>Top P</code> 参数。默认值：<code>0.85</code>。<br/>
 *                   单步累计采用阈值，值越大越多 <b>token</b> 会被考虑。如果累计概率已经超过0.85，剩下的 token 不会被考虑。
 * @apiBody {Number} [option.topK] 可选参数。指定 <code>Top K</code> 参数。默认值：<code>5</code>。<br/>
 *                   单步采用 <b>token</b> 的数量，值越大采用 <b>token</b> 会越多。单步中最多考虑的 <b>token</b> 数量。
 * @apiBody {Number} [option.repetitionPenalty] 可选参数。指定 <code>Repetition Penalty</code> 参数。默认值：<code>1.05</code>。<br/>
 *                   重复惩罚值，值越大越不会生成重复 <b>token</b> 。较高的值意味着更强的惩罚，生成的文本中将出现更少的重复。如果取值为<code>0</code>，则没有惩罚，生成的文本可能包含大量重复的内容。
 * @apiBody {Number} [option.maxNewTokens] 可选参数。指定 <code>Max New Tokens</code> 参数。默认值：<code>2048</code>。<br/>
 *                   生成的最大 <b>token</b> 数量。
 * @apiBody {String} [pattern] 可选参数。指定模型的工作模式。标准会话模式：<code>chat</code> ，知识库模式：<code>knowledge</code> 。
 * @apiBody {Number} [histories] 可选参数。自动使用服务器上记录的历史会话对话数量。设置为 <code>0</code> 表示不使用历史对轮对话。
 * @apiBody {Array} [records] 可选参数。对话的上下文记录。上下文记录作为多轮对话的历史或者作为对话附录；也可作为附件数据列表，附件数据可以是文本原文或者文件。
 * @apiBody {Boolean} [recordable] 可选参数。是否记录本次对话内容，记录的内容可用于后续多轮对话。
 * @apiBody {Boolean} [networking] 可选参数。是否进行联网搜索数据并进行推理。
 * @apiBody {Array} [categories] 可选参数。在推理时引用的匹配分类。
 * 
 * @apiUse GenerativeRecordAsAttachment
 * 
 * @apiParamExample {json} 请求示例1
 *     {
 *         "channel": "dyFbWTHGuEOMaIuJ",
 *         "content": "你好"
 *     }
 * 
 * @apiParamExample {json} 请求示例2
 *     {
 *         "channel": "dyFbWTHGuEOMaIuJ",
 *         "content": "在心理学里“沟通意愿”是指什么",
 *         "unit": "Baize2",
 *         "recordable": false,
 *         "histories": 0
 *     }
 * 
 * @apiParamExample {json} 请求示例3
 *     {
 *         "channel": "NUtlgDEwItABtPna",
 *         "content": "什么是固态电池",
 *         "unit": "Baize",
 *         "recordable": false,
 *         "networking": false,
 *         "histories": 0,
 *         "records": [
 *             {
 *                 "queryAdditions": [
 *                     "大家都知道锂电池主要由正极材料、负极材料、电解质、隔膜组成。电解质有液态与固态两种形式，当前广泛使用的锂离子电池主要采用液态有机溶剂电解质。目前来看，液态锂电池，无论是磷酸铁锂电池还是三元锂电池，综合性能在各种技术的加持下，都已经发挥到“极限”，在技术上越来越难有新的突破。\n\n在更高的安全性和能量密度要求下，固态电池应运而生。固态电池是一种新型电池技术，其采用具有高离子传导性和稳定性的固态电解质取代易燃、易爆的有机电解液，同时，在正、负极材料方面，固态电池可沿用液态锂电池材料体系，且升级空间更大。因此，固态电池被普遍视为下一代电池技术，成为全球相关企业竞相角逐的焦点。\n\n与锂离子电池和锂离子聚合物电池不同的是，固态电池是一种使用固体电极和固体电解质的电池。在固态离子学中，固态电池是一种使用固体电极和固体电解液的电池。固态电池一般功率密度较低，能量密度较高。由于固态电池的功率重量比较高，所以它是电动汽车很理想的电池。"
 *                 ]
 *             }
 *         ]
 *      }
 * 
 * @apiSuccess {Number} sn 本次对话的序列号。
 * @apiSuccess {String} participant 回复对话的模型名。
 * @apiSuccess {String} content 回复的对话内容。
 * @apiSuccess {String} thought 模型的思维内容。
 * @apiSuccess {Number} timestamp 回复对话的时间戳。
 * @apiSuccess {String} pattern 模型的工作模式。
 * @apiSuccess {Object} context 回复的上下文数据。
 * @apiSuccess {Number} context.id 上下文数据的 ID 。
 * @apiSuccess {Boolean} context.simplex 是否是单一上下文描述。
 * @apiSuccess {Array} context.resources 上下文关联的资源清单。
 * @apiSuccess {Boolean} context.networking 是否联网进行搜索，并对搜索结果进行推理。
 * @apiSuccess {Number} context.timestamp 上下文数据的时间戳。
 * 
 * @apiSuccessExample {json} 应答示例1
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 842923,
 *         "participant": "Baize",
 *         "content": "今天是新的开始，让我们迎着新的希望开始砥砺奋进的一天。",
 *         "thought": "",
 *         "timestamp": 1680149631661,
 *         "pattern": "chat",
 *         "context": {
 *             "id": 873551,
 *             "simplex": true,
 *             "inferable": false,
 *             "networking": false,
 *             "resources": []
 *         }
 *     }
 * 
 * @apiSuccessExample {json} 应答示例2
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 1045062944,
 *         "participant": "Baize",
 *         "content": "沟通意愿是心理学中的一个重要概念，涉及多个维度和因素。以下是对沟通意愿的全面分析：\n\n1. **需求驱动**：沟通意愿通常由情感需求、信息需求或社交需求驱动。例如，孤独或压力大的个体可能更倾向于沟通以寻求支持或分享感受。\n\n2. **自我表达**：个体渴望表达自己的想法和感受，如通过写作或演讲，这也是一种沟通意愿的体现。\n\n3. **社会关系需要**：人类作为社会性生物，需要建立和维护关系，因此沟通意愿受到这种基本需求的影响。\n\n4. **性格特质**：外向性在大五人格模型中显著影响沟通意愿，外向者通常更愿意与他人交流。\n\n5. **情境影响**：环境因素如安静或热闹的环境会影响沟通意愿，不同情境下个体的沟通倾向可能不同。\n\n6. **类型差异**：个体可能偏好正式或非正式沟通，影响其沟通方式和频率。\n\n7. **人际关系质量**：高沟通意愿有助于建立和维护良好的人际关系，而低沟通意愿可能带来挑战。\n\n8. **变化性**：沟通意愿受情绪和压力影响，可能随时间和情境变化。\n\n9. **心理健康**：高沟通意愿有助于释放压力和获得支持，促进心理健康，而低沟通意愿可能带来负面影响。\n\n10. **文化差异**：不同文化背景下，沟通意愿的表现形式和强度可能不同，如集体主义和个人主义文化的影响。\n\n11. **技术影响**：社交媒体等技术改变了沟通方式，影响沟通意愿的表现。\n\n12. **信任因素**：信任他人会增加沟通意愿，而缺乏信任则会降低。\n\n13. **目标导向**：为达到特定目标而进行的沟通，可能影响沟通的方式和强度。\n\n总结而言，沟通意愿是一个多维度的概念，涉及个人需求、性格、情境、文化、技术、信任和目标等多个因素。理解这些因素有助于更好地理解个体的沟通行为及其背后的心理机制。",
 *         "thought": "嗯，我现在要理解一下心理学里的“沟通意愿”是什么意思。首先，沟通意愿听起来像是一个人想要和别人交流的欲望或动机。但具体是什么呢？我需要拆开来看。\n\n沟通意愿可能涉及到很多方面。比如，一个人为什么会想要沟通？是不是因为有需求，比如情感上的需求，或者信息上的需求？比如说，当一个人感到孤独或者压力大的时候，可能会更想和别人说话，这就是情感需求驱动的沟通意愿。\n\n另外，自我表达也是一个因素。人们可能想分享自己的想法和感受，所以会有沟通的欲望。比如，喜欢写作或者说话的人，可能有很强的自我表达的需求。\n\n还有，社会关系的需要。人是社会性的动物，需要和别人建立联系，所以沟通意愿可能也受到这种需求的影响。比如，参加社交活动时，人们更愿意和他人交流。\n\n那沟通意愿是不是和性格有关呢？比如，外向的人可能比内向的人更愿意沟通。这可能涉及到人格特质，比如大五人格模型中的外向性。\n\n另外，沟通意愿可能还受到情境的影响。比如，一个安静的环境可能让人更愿意独处，而不愿意沟通。相反，热闹的环境可能会激发沟通的欲望。\n\n还有，沟通意愿可能有不同的类型。比如，有些人可能更喜欢正式的沟通，比如在工作场合，而有些人则更喜欢非正式的交流，比如和朋友聊天。这可能影响到他们沟通的方式和频率。\n\n沟通意愿的强弱可能也会影响人际关系的质量。如果一个人有高的沟通意愿，可能会更容易建立和维护关系，而沟通意愿低的人可能会在人际关系上遇到困难。\n\n不过，沟通意愿也不是固定不变的，它可能会随着时间和情境的变化而变化。比如，当一个人处于压力下时，沟通意愿可能会降低，或者在心情好的时候，沟通意愿会增强。\n\n那沟通意愿和心理健康有什么关系呢？可能沟通意愿高的人更容易释放压力，获得情感支持，从而心理健康更好。相反，沟通意愿低的人可能会压抑情绪，导致心理问题。\n\n不过，我也需要考虑一下沟通意愿的负面影响。比如，过度沟通可能会让人感到疲惫，或者在不适当的场合沟通可能会带来麻烦。所以，沟通意愿需要适度。\n\n总结一下，沟通意愿可能包括需求驱动、自我表达、社会关系需要、性格特质、情境影响、类型差异、影响人际关系、变化性、心理健康等多个方面。每个方面都可能影响一个人的沟通意愿，进而影响他们的交流行为和人际关系。\n\n不过，我可能忽略了一些方面，比如文化差异。在不同的文化中，沟通意愿可能有不同的表现。比如，有些文化更强调集体主义，可能更重视群体沟通，而有些文化更强调个人主义，可能更注重个人表达。\n\n还有，技术的影响。现在有了社交媒体，人们可能更多通过网络沟通，这也可能改变沟通意愿的表现形式和强度。\n\n另外，信任也是一个因素。如果一个人信任他人，可能会更愿意沟通；反之，如果缺乏信任，沟通意愿可能会降低。\n\n还有，目标导向。有些人沟通是为了达到某个具体的目标，比如说服别人，获取信息，而不是单纯为了交流而交流。这种目标导向可能会影响沟通的方式和强度。\n\n总的来说，沟通意愿是一个多维度的概念，涉及多个因素，包括个人需求、性格、情境、文化、技术、信任和目标等。理解这些因素有助于更好地理解一个人的沟通行为和背后的心理机制。",
 *         "timestamp": 1741657060177,
 *         "pattern": "chat",
 *         "context": {
 *             "id": 4201871863,
 *             "simplex": true,
 *             "inferable": false,
 *             "resources": [],
 *             "networkingInferEnd": false,
 *             "networking": false,
 *             "inferring": false,
 *             "timestamp": 1741656930074
 *         }
 *     }
 * 
 * @apiSuccessExample {json} 应答示例3
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 3754753017,
 *         "participant": "Baize",
 *         "content": "固态电池是一种新型的锂离子电池，其正极和负极材料都是固态的。与传统的液态锂离子电池相比，固态电池具有更高的能量密度、更长的使用寿命、更低的自放电率和更好的安全性能。此外，固态电池的生产过程也更加环保，因为它们不需要使用有毒的溶剂和液体。",
 *         "thought": "",
 *         "pattern": "chat",
 *         "context": {
 *             "inferable": false,
 *             "resources": [],
 *             "networkingInferEnd": false,
 *             "networking": false,
 *             "id": 1749144535,
 *             "simplex": true,
 *             "inferring": false,
 *             "timestamp": 1711603253350
 *         },
 *         "timestamp": 1711603258881
 *     }
 * 
 * @apiError 400 服务节点访问错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 * @apiError 404 必填参数错误。
 */


/**
 * @api {post} /aigc/text2file/:token 自然语言文本推理文件（执行）
 * @apiName 自然语言文本推理文件
 * @apiGroup Natural_Language_Processing
 * @apiVersion 1.0.0
 * 
 * @apiDescription 通过使用自然语言方式处理并生成文件。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} text 自然语言文本。该文本是对文件处理需求的自然语言内容。
 * @apiBody {Array} files 指定需要处理的文件的文件码列表。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "text": "生成新表格，表头依次是“编号”，“姓名”，“手机号”，“部门”，“性别”，“年龄”，将已知表格里的“学号”填到“编号”，“性别”填到“性别”，“出生日期”填到“年龄”里。如果没有数据就填写空格。",
 *         "files": [
 *             "AIjoFDlzyphCHatmTRmoPMzNmWMQBFIZeeDpsPIlelxGWqWTGxVFBRNYpMsaGLDK"
 *         ]
 *     }
 * 
 * @apiSuccess {Number} sn 本次处理任务的序列号，该序列号用于查询当前任务状态和数据。
 * @apiSuccess {Number} state 当前状态的状态码。任务请求成功时该状态码为：<code>7</code>。相关状态码如下：
 *                            <p><code>0</code>：<span class="default-value">已完成</span></p>
 *                            <p><code>7</code>：<span class="default-value">正在处理</span></p>
 *                            <p><code>9</code>：<span class="default-value">发生故障</span></p>
 *                            <p><code>24</code>：<span class="default-value">无效的数据</span></p>
 *                            <p><code>25</code>：<span class="default-value">无法进行的操作</span></p>
 *                            <p><code>27</code>：<span class="default-value">操作已停止</span></p>
 *                            <p><code>31</code>：<span class="default-value">单元故障</span></p>
 *                            <p><code>32</code>：<span class="default-value">文件错误</span></p>
 * @apiSuccess {Number} timestamp 当前操作的时间戳。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 3677528462,
 *         "state": 7,
 *         "timestamp": 1737368258819
 *     }
 * 
 * @apiError 400 服务节点访问错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 * @apiError 404 文件处理故障。
 */


/**
 * @api {get} /aigc/text2file/:token 自然语言文本推理文件（查询）
 * @apiName 查询使用自然语言文本推理文件任务数据
 * @apiGroup Natural_Language_Processing
 * @apiVersion 1.0.0
 * 
 * @apiDescription 查询使用自然语言文本推理文件任务数据。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {String} sn 处理任务的序列号。
 * 
 * @apiSuccess {Number} sn 本次处理任务的序列号，该序列号用于查询当前任务状态和数据。
 * @apiSuccess {Number} state 当前状态的状态码。相关状态码如下：
 *                            <p><code>0</code>：<span class="default-value">已完成</span></p>
 *                            <p><code>7</code>：<span class="default-value">正在处理</span></p>
 *                            <p><code>9</code>：<span class="default-value">发生故障</span></p>
 *                            <p><code>24</code>：<span class="default-value">无效的数据</span></p>
 *                            <p><code>25</code>：<span class="default-value">无法进行的操作</span></p>
 *                            <p><code>27</code>：<span class="default-value">操作已停止</span></p>
 *                            <p><code>31</code>：<span class="default-value">单元故障</span></p>
 *                            <p><code>32</code>：<span class="default-value">文件错误</span></p>
 * @apiSuccess {Number} timestamp 当前操作的时间戳。
 * @apiSuccess {Object} [result] 文件数据处理结果。
 * @apiSuccess {Number} result.sn 对话序号。
 * @apiSuccess {String} result.answer 处理结果文本内容。
 * @apiSuccess {Array} result.answerFileLabels 处理后生成的文件列表。可通过<code>fileURL</code>或<code>fileSecureURL</code>下载文件。
 * @apiSuccess {Array} result.queryFileLabels 推理使用的原始文件列表。
 * @apiSuccess {String} result.unit 处理该任务使用的模型名。
 * @apiSuccess {Number} result.timestamp 结果生成时的时间戳。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 3677528462,
 *         "state": 0,
 *         "timestamp": 1737368258819,
 *         "result": {
 *             "sn": 2153758728,
 *             "answer": "已处理文件“**2021年花名册_1.xlsx**”。处理的数据大小合计 9.76 KB 。生成2个文件，合计 0.16 KB 。\n\n注意：由于提供的表格中没有包含手机号和部门的资料，所以这两列均用空格表示。同时，“年龄”一栏是根据出生日期计算得出的，但需要具体的年份来换算成实际的年龄，因此这里也留有空格。如果有具体年份的数据，可以进一步填充这一栏。",
 *             "answerFileLabels": [{
 *                 "fileName": "7年级3班.csv",
 *                 "completedTime": 1737370509070,
 *                 "fileCode": "WnCHxkUbeXFXpsbjtklwTxLrfQQCvvKRQCDXuFcVeZvyAcgJMvptlhPwNMgUyLxe",
 *                 "ownerId": 22003303,
 *                 "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=WnCHxkUbeXFXpsbjtklwTxLrfQQCvvKRQCDXuFcVeZvyAcgJMvptlhPwNMgUyLxe&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *                 "sha1": "3f8b82070deff3b568686890b304a402c293bacb",
 *                 "fileSize": 111,
 *                 "domain": "default_domain",
 *                 "expiryTime": 3314170509070,
 *                 "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=WnCHxkUbeXFXpsbjtklwTxLrfQQCvvKRQCDXuFcVeZvyAcgJMvptlhPwNMgUyLxe&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *                 "id": 600327243,
 *                 "lastModified": 1737370509000,
 *                 "fileType": "csv",
 *                 "md5": "8d30a3c6830ac3a69928cd5df891a15f"
 *             }, {
 *                 "fileName": "7年级4班.csv",
 *                 "completedTime": 1737370515169,
 *                 "fileCode": "WnCHxkUbeXFXpsbjtklwTyLrfQQCvvKRQCDXuFcVeZvyAcgJMvptlhPwNMgUyLxe",
 *                 "ownerId": 22003303,
 *                 "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=WnCHxkUbeXFXpsbjtklwTyLrfQQCvvKRQCDXuFcVeZvyAcgJMvptlhPwNMgUyLxe&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *                 "sha1": "af1ca3757e0e29cfa84ae7d33c59d6036d686636",
 *                 "fileSize": 149,
 *                 "domain": "default_domain",
 *                 "expiryTime": 3314170515169,
 *                 "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=WnCHxkUbeXFXpsbjtklwTyLrfQQCvvKRQCDXuFcVeZvyAcgJMvptlhPwNMgUyLxe&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *                 "id": 2831200543,
 *                 "lastModified": 1737370515000,
 *                 "fileType": "csv",
 *                 "md5": "042831b5a5246723661712704196f46e"
 *             }],
 *             "queryFileLabels": [{
 *                 "fileName": "2021年花名册_1.xlsx",
 *                 "completedTime": 1737352689963,
 *                 "fileCode": "AIjoFDlzyphCHatmTRmoPMzNmWMQBFIZeeDpsPIlelxGWqWTGxVFBRNYpMsaGLDK",
 *                 "ownerId": 22003303,
 *                 "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=AIjoFDlzyphCHatmTRmoPMzNmWMQBFIZeeDpsPIlelxGWqWTGxVFBRNYpMsaGLDK&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *                 "sha1": "bd2c12f43639921e116567c2fe188b77397b8f18",
 *                 "fileSize": 9991,
 *                 "domain": "default_domain",
 *                 "expiryTime": 3314152689963,
 *                 "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=AIjoFDlzyphCHatmTRmoPMzNmWMQBFIZeeDpsPIlelxGWqWTGxVFBRNYpMsaGLDK&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *                 "id": 2979694117,
 *                 "lastModified": 1737352689711,
 *                 "fileType": "xlsx",
 *                 "md5": "d2fbeeb80d37637e7e0a43bca4c22de4"
 *             }],
 *             "unit": "Baize",
 *             "timestamp": 1737370504963
 *         }
 *     }
 * 
 * @apiError 400 服务节点访问错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 * @apiError 404 文件处理故障。
 */
