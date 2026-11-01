/**
 * @apiDefine Knowledge 知识数据管理
 */

/**
 * @apiDefine KnowledgeDoc 知识库文档
 * @apiSuccess {Number} id 文档 ID 。
 * @apiSuccess {Number} contactId 文档所属的触点ID 。
 * @apiSuccess {String} domain 文档所属的触点域。
 * @apiSuccess {String} scope 文档的适用范围。
 * @apiSuccess {String} fileCode 文档对应文件的文件码。
 * @apiSuccess {String} splitter 文档使用的分段方式。
 * @apiSuccess {Object} fileLabel 文档的文件标签对象。
 * @apiSuccess {Number} fileLabel.id 文件标签 ID。
 * @apiSuccess {String} fileLabel.domain 文件所属域。
 * @apiSuccess {String} fileLabel.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} fileLabel.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} fileLabel.fileName 原始文件名。
 * @apiSuccess {Number} fileLabel.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} fileLabel.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} fileLabel.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} fileLabel.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} fileLabel.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [fileLabel.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [fileLabel.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [fileLabel.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [fileLabel.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [fileLabel.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [fileLabel.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [fileLabel.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 * @apiSuccess {Number} timestamp 文档时间戳。
 * @apiSuccess {Number} numSegments 文档被分段的数量。
 * @apiSuccess {Boolean} activated 文档是否已激活。
 */

/**
 * @apiDefine KnowledgeProgress 操作进度
 * @apiSuccess {Number} sn 操作序号。
 * @apiSuccess {Number} startTime 操作开始时的时间戳。
 * @apiSuccess {Number} endTime 操作结束时的时间戳。
 * @apiSuccess {Number} stateCode 状态码。状态码说明：
 *                                <p><code>0</code> - <span class="default-value">成功</span></p>
 *                                <p><code>7</code> - <span class="default-value">正在处理</span></p>
 *                                <p><code>9</code> - <span class="default-value">发生故障</span></p>
 *                                <p><code>23</code> - <span class="default-value">没有找到数据</span></p>
 *                                <p><code>26</code> - <span class="default-value">系统忙</span></p>
 *                                <p><code>30</code> - <span class="default-value">知识库配置错误</span></p>
 * @apiSuccess {Number} totalDocs 本次操作的所有文档数量。
 * @apiSuccess {Number} processedDocs 本次操作已经处理完成的文档数量。
 * @apiSuccess {Object} [activatingDoc] 当前进度中正在处理的文档。
 * @apiSuccess {Number} activatingDoc.id 知识文档 ID。
 * @apiSuccess {String} activatingDoc.domain 文档所属域。
 * @apiSuccess {Number} activatingDoc.timestamp 文档创建时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} activatingDoc.contactId 文档所属的触点 ID。
 * @apiSuccess {String} activatingDoc.fileCode 源文件的文件码。
 * @apiSuccess {String} activatingDoc.baseName 知识库分类名称。
 * @apiSuccess {String} [activatingDoc.fileName] 源文件名。
 * @apiSuccess {Boolean} activatingDoc.activated 文档是否已激活。
 * @apiSuccess {String} activatingDoc.splitter 分段器名称。
 * @apiSuccess {Number} activatingDoc.numSegments 文档片段数；尚未分段时可能为 -1。
 * @apiSuccess {String} activatingDoc.scope 文档适用范围。
 * @apiSuccess {String[]} activatingDoc.knowledgeLabels 知识标签列表。
 * @apiSuccess {Object} [activatingDoc.fileLabel] 源文件标签。
 * @apiSuccess {Number} activatingDoc.fileLabel.id 文件标签 ID。
 * @apiSuccess {String} activatingDoc.fileLabel.domain 文件所属域。
 * @apiSuccess {String} activatingDoc.fileLabel.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} activatingDoc.fileLabel.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} activatingDoc.fileLabel.fileName 原始文件名。
 * @apiSuccess {Number} activatingDoc.fileLabel.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} activatingDoc.fileLabel.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} activatingDoc.fileLabel.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} activatingDoc.fileLabel.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} activatingDoc.fileLabel.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [activatingDoc.fileLabel.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [activatingDoc.fileLabel.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [activatingDoc.fileLabel.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [activatingDoc.fileLabel.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [activatingDoc.fileLabel.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [activatingDoc.fileLabel.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [activatingDoc.fileLabel.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 */

/**
 * @apiDefine KnowledgeDocSuccessExample 知识文档应答示例
 * @apiSuccessExample {json} 知识文档-应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "numSegments": 0,
 *         "contactId": 22003303,
 *         "domain": "default_domain",
 *         "scope": "public",
 *         "fileCode": "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *         "id": 2816604439,
 *         "splitter": "Auto",
 *         "fileLabel": {
 *             "fileName": "2023年大语言模型发展概述.txt",
 *             "completedTime": 1703942996049,
 *             "fileCode": "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *             "ownerId": 22003303,
 *             "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
 *             "sha1": "5b208b20aaffbe3e2e12b39d2bbb249838773e1c",
 *             "fileSize": 6303,
 *             "domain": "default_domain",
 *             "expiryTime": 1706534996049,
 *             "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
 *             "id": 3397137026,
 *             "lastModified": 1701756454027,
 *             "fileType": "txt",
 *             "md5": "8b49315f06e7a312c89eb8028b1c6d2c"
 *         },
 *         "timestamp": 1704111915133,
 *         "activated": false
 *     }
 */

/**
 * @apiDefine KnowledgeProgressSuccessExample 操作进度应答示例
 * @apiSuccessExample {json} 操作开始-应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 327158635,
 *         "startTime": 1704111915243,
 *         "endTime": 0,
 *         "stateCode": 7,
 *         "totalDocs": 3,
 *         "processedDocs": 0
 *     }
 * 
 * @apiSuccessExample {json} 操作进行中-应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 327158635,
 *         "startTime": 1704111915243,
 *         "endTime": 0,
 *         "stateCode": 7,
 *         "totalDocs": 3,
 *         "processedDocs": 1,
 *         "activatingDoc": {
 *             "numSegments": 0,
 *             "contactId": 22003303,
 *             "domain": "default_domain",
 *             "scope": "public",
 *             "fileCode": "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *             "id": 2816604439,
 *             "splitter": "Auto",
 *             "fileLabel": {
 *                 "fileName": "2023年大语言模型发展概述.txt",
 *                 "completedTime": 1703942996049,
 *                 "fileCode": "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *                 "ownerId": 22003303,
 *                 "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
 *                 "sha1": "5b208b20aaffbe3e2e12b39d2bbb249838773e1c",
 *                 "fileSize": 6303,
 *                 "domain": "default_domain",
 *                 "expiryTime": 1706534996049,
 *                 "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
 *                 "id": 3397137026,
 *                 "lastModified": 1701756454027,
 *                 "fileType": "txt",
 *                 "md5": "8b49315f06e7a312c89eb8028b1c6d2c"
 *             },
 *             "timestamp": 1704111915133,
 *             "activated": false
 *         }
 *     }
 * 
 * @apiSuccessExample {json} 操作结束-应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 327158635,
 *         "startTime": 1704111915243,
 *         "endTime": 1704112037416,
 *         "stateCode": 0,
 *         "totalDocs": 3,
 *         "processedDocs": 3
 *     }
 */


/**
 * @apiDefine KnowledgeFramework 知识库分类结构
 * @apiSuccess {Number} infoList.contactId 分类库的触点ID 。
 * @apiSuccess {String} infoList.displayName 分类库显示名称。
 * @apiSuccess {String} infoList.name 分类库分类名。
 * @apiSuccess {String} [infoList.category] 分类库的分类描述。
 * @apiSuccess {Number} infoList.unitId 分类库使用的单元 ID 。<code>0</code> 表示自动分配单元。
 * @apiSuccess {Number} infoList.storeSize 分类库已占用的存储空间大小，单位：字节。
 * @apiSuccess {Number} infoList.timestamp 分类库最近更新时间戳。
 */


/**
 * @apiDefine KnowledgeBase 知识库基础参数
 * @apiBody {String} name 知识库库名。库名只能使用字母或数字。
 * @apiBody {String} displayName 知识库显示名。
 * @apiBody {String} category 知识库分类。
 */


/**
 * @api {get} /aigc/knowledge/info/:token 获取知识库框架信息
 * @apiName 获取知识库框架信息
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 获取当前用户的知识库分类及其信息，作为选择知识库和编辑设置的入口。
 *                 <p>从 infoList 保存实际库名 name，供导入、移除、重置与问答的 base 参数使用；displayName 仅用于展示。编辑知识库信息时保留返回的其他字段。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiSuccess {Number} total 触点可使用的分类库总数。
 * @apiSuccess {Object[]} infoList 分类库数据列表；每项字段如下。
 * @apiUse KnowledgeFramework
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 2,
 *         "infoList": [
 *             {
 *                 "contactId": 20230011,
 *                 "displayName": "默认文档库",
 *                 "name": "document",
 *                 "unitId": 0,
 *                 "storeSize": 0,
 *                 "timestamp": 1705624776694
 *             },
 *             {
 *                 "contactId": 20230011,
 *                 "displayName": "人物资料",
 *                 "name": "person",
 *                 "unitId": 0,
 *                 "storeSize": 0,
 *                 "category": "人物",
 *                 "timestamp": 1708220644549
 *             }
 *         ]
 *     }
 * 
 * @apiError 400 数据出错。
 * @apiError 401 令牌出错。
 * @apiError 403 参数出错。
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/aigc/knowledge/info/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 */


/**
 * @api {post} /aigc/knowledge/new/:token 新建分类知识库
 * @apiName 新建分类知识库
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 创建知识库分类，用于将不同业务的文档分开管理和问答。
 *                 <p>name 作为后续 base 参数，只能使用字母或数字；displayName 用于界面展示。创建后仅当前用户可见，共享范围需要通过设置参数调整。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiUse KnowledgeBase
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *        "name": "standard",
 *        "displayName": "标准库",
 *        "category": "标准"
 *    }
 * 
 * @apiUse KnowledgeFramework
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "contactId": 22003303,
 *         "displayName": "标准库",
 *         "name": "standard",
 *         "unitId": 0,
 *         "storeSize": 0,
 *         "category": "标准",
 *         "timestamp": 1711507241967
 *     }
 * 
 * @apiError 400 数据出错。
 * @apiError 401 令牌出错。
 * @apiError 403 参数出错。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/knowledge/new/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "name": "standard",
 *         "displayName": "标准库",
 *         "category": "标准"
 *     }
 */


/**
 * @api {post} /aigc/knowledge/delete/:token 删除分类知识库
 * @apiName 删除分类知识库
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 删除指定库名的分类知识库及其关联数据。
 *                 <p>使用框架信息返回的 name 确认目标；显示名不能代替库名。删除后不再使用原库名提交问答或文档操作。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} name 知识库库名。库名只能使用字母或数字。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *        "name": "standard"
 *    }
 * 
 * @apiUse KnowledgeFramework
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "contactId": 22003303,
 *         "displayName": "标准库",
 *         "name": "standard",
 *         "unitId": 0,
 *         "storeSize": 0,
 *         "category": "标准",
 *         "timestamp": 1711507241967
 *     }
 * 
 * @apiError 400 数据出错。
 * @apiError 401 令牌出错。
 * @apiError 403 参数出错。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/knowledge/delete/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "name": "standard"
 *     }
 */


/**
 * @api {post} /aigc/knowledge/profile/:token 更新知识库设置参数
 * @apiName 更新知识库设置参数
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 修改用户知识库的可用状态、容量上限和资料共享范围。
 *                 <p>maxSize 单位为字节；scope=private 仅自己可用，scope=public 向同域用户共享。仅提交本次需要修改的可选项，并根据应答确认设置。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Number} contactId 指定待更新参数的用户ID 。
 * @apiBody {Number} [state] 指定用户ID的知识库状态：<code>0</code> 可用状态，<code>1</code> 只读状态，<code>2</code> 禁用状态。
 * @apiBody {Number} [maxSize] 指定用户ID的知识库可用的最大存储空间大小，单位：字节（bytes）。
 * @apiBody {String} [scope] 指定用户ID的知识库文档的使用范围：<code>private</code> 仅自己可用，<code>public</code> 域里的所有用户共享。
 * 
 * @apiParamExample {json} 更新知识库设置参数
 *     {
 *         "contactId": 22003303,
 *         "state": 0,
 *         "maxSize": 549755813888,
 *         "scope": "public"
 *     }
 * 
 * @apiSuccess {Number} id 配置数据ID。
 * @apiSuccess {Number} contactId 用户ID。
 * @apiSuccess {String} domain 域名称。
 * @apiSuccess {Number} state 知识库状态。
 * @apiSuccess {Number} maxSize 知识库最大文档容量，单位：字节（bytes）。
 * @apiSuccess {String} scope 知识库文档使用范围。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "id": 1,
 *         "contactId": 22003303,
 *         "domain": "default_domain",
 *         "state": 0,
 *         "maxSize": 549755813888,
 *         "scope": "public"
 *     }
 * 
 * @apiError 400 数据出错。
 * @apiError 401 令牌出错。
 * @apiError 403 参数出错。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/knowledge/profile/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "contactId": 22003303,
 *         "state": 0,
 *         "maxSize": 549755813888,
 *         "scope": "public"
 *     }
 */


/**
 * @api {post} /aigc/knowledge/reset/:token 重置知识库数据
 * @apiName 重置知识库数据
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 重新处理指定知识库中已经导入的全部文档，适用于更新文档处理结果。
 *                 <p>保存返回的 sn，调用“获取重置知识库操作进度”并传入相同 base；处理期间无需重复提交。backup 默认 true，是否关闭备份应按业务需要选择。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} base 指定知识库库名。
 * @apiBody {Boolean} [backup] 是否在重置前进行备份。默认值：<code>true</code> 。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "base": "document",
 *         "backup": false
 *     }
 * 
 * @apiSuccess {Number} sn 操作序号。
 * @apiSuccess {String} progress 重置进度描述。
 *                               <p><span class="default-value">已启动：</span><code>Start</code></p>
 *                               <p><span class="default-value">正在执行备份操作：</span><code>BackupStore</code></p>
 *                               <p><span class="default-value">正在执行删除错误：</span><code>DeleteStore</code></p>
 *                               <p><span class="default-value">正在激活文档：</span><code>ActivateDoc</code></p>
 *                               <p><span class="default-value">已结束：</span><code>End</code></p>
 * @apiSuccess {Number} startTime 开始时间戳。
 * @apiSuccess {Number} endTime 结束时间戳。
 * @apiSuccess {Number} totalDocs 总文档数量。
 * @apiSuccess {Number} processedDocs 已处理文档数量。
 * @apiSuccess {Number} stateCode 状态码。
 * @apiSuccess {Object} [activatingDoc] 当前正在激活的文档。
 * @apiSuccess {Number} activatingDoc.id 知识文档 ID。
 * @apiSuccess {String} activatingDoc.domain 文档所属域。
 * @apiSuccess {Number} activatingDoc.timestamp 文档创建时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} activatingDoc.contactId 文档所属的触点 ID。
 * @apiSuccess {String} activatingDoc.fileCode 源文件的文件码。
 * @apiSuccess {String} activatingDoc.baseName 知识库分类名称。
 * @apiSuccess {String} [activatingDoc.fileName] 源文件名。
 * @apiSuccess {Boolean} activatingDoc.activated 文档是否已激活。
 * @apiSuccess {String} activatingDoc.splitter 分段器名称。
 * @apiSuccess {Number} activatingDoc.numSegments 文档片段数；尚未分段时可能为 -1。
 * @apiSuccess {String} activatingDoc.scope 文档适用范围。
 * @apiSuccess {String[]} activatingDoc.knowledgeLabels 知识标签列表。
 * @apiSuccess {Object} [activatingDoc.fileLabel] 源文件标签。
 * @apiSuccess {Number} activatingDoc.fileLabel.id 文件标签 ID。
 * @apiSuccess {String} activatingDoc.fileLabel.domain 文件所属域。
 * @apiSuccess {String} activatingDoc.fileLabel.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} activatingDoc.fileLabel.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} activatingDoc.fileLabel.fileName 原始文件名。
 * @apiSuccess {Number} activatingDoc.fileLabel.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} activatingDoc.fileLabel.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} activatingDoc.fileLabel.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} activatingDoc.fileLabel.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} activatingDoc.fileLabel.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [activatingDoc.fileLabel.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [activatingDoc.fileLabel.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [activatingDoc.fileLabel.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [activatingDoc.fileLabel.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [activatingDoc.fileLabel.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [activatingDoc.fileLabel.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [activatingDoc.fileLabel.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 327158635,
 *         "processedDocs": -1,
 *         "progress": "Start",
 *         "startTime": 1704111915243,
 *         "stateCode": 0,
 *         "endTime": 0,
 *         "totalDocs": -1
 *     }
 * 
 * @apiError 400 数据出错。
 * @apiError 401 令牌出错。
 * @apiError 403 参数出错。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/knowledge/reset/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "base": "document",
 *         "backup": false
 *     }
 */


/**
 * @api {get} /aigc/knowledge/reset/:token 获取重置知识库操作进度
 * @apiName 获取重置知识库操作进度
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 使用对应提交接口返回的操作序号 <code>sn</code> 与相同的知识库名 <code>base</code> 查询执行进度。
 *                 <p><code>stateCode=7</code> 时任务仍在处理，可间隔数秒重查；<code>0</code> 表示完成，其他值按下方状态码处理。使用 <code>processedDocs</code> 与 <code>totalDocs</code> 展示文档处理进度；不要重复提交同一操作来获取状态。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {Number} sn 指定操作序号。
 * @apiQuery {String} base 指定知识库库名。
 * 
 * @apiSuccess {Number} sn 操作序号。
 * @apiSuccess {String} progress 重置进度描述。
 *                               <p><span class="default-value">已启动：</span><code>Start</code></p>
 *                               <p><span class="default-value">正在执行备份操作：</span><code>BackupStore</code></p>
 *                               <p><span class="default-value">正在执行删除错误：</span><code>DeleteStore</code></p>
 *                               <p><span class="default-value">正在激活文档：</span><code>ActivateDoc</code></p>
 *                               <p><span class="default-value">已结束：</span><code>End</code></p>
 * @apiSuccess {Number} startTime 开始时间戳。
 * @apiSuccess {Number} endTime 结束时间戳。
 * @apiSuccess {Number} totalDocs 总文档数量。
 * @apiSuccess {Number} processedDocs 已处理文档数量。
 * @apiSuccess {Number} stateCode 状态码。
 * @apiSuccess {Object} [activatingDoc] 当前正在激活的文档。
 * @apiSuccess {Number} activatingDoc.id 知识文档 ID。
 * @apiSuccess {String} activatingDoc.domain 文档所属域。
 * @apiSuccess {Number} activatingDoc.timestamp 文档创建时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} activatingDoc.contactId 文档所属的触点 ID。
 * @apiSuccess {String} activatingDoc.fileCode 源文件的文件码。
 * @apiSuccess {String} activatingDoc.baseName 知识库分类名称。
 * @apiSuccess {String} [activatingDoc.fileName] 源文件名。
 * @apiSuccess {Boolean} activatingDoc.activated 文档是否已激活。
 * @apiSuccess {String} activatingDoc.splitter 分段器名称。
 * @apiSuccess {Number} activatingDoc.numSegments 文档片段数；尚未分段时可能为 -1。
 * @apiSuccess {String} activatingDoc.scope 文档适用范围。
 * @apiSuccess {String[]} activatingDoc.knowledgeLabels 知识标签列表。
 * @apiSuccess {Object} [activatingDoc.fileLabel] 源文件标签。
 * @apiSuccess {Number} activatingDoc.fileLabel.id 文件标签 ID。
 * @apiSuccess {String} activatingDoc.fileLabel.domain 文件所属域。
 * @apiSuccess {String} activatingDoc.fileLabel.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} activatingDoc.fileLabel.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} activatingDoc.fileLabel.fileName 原始文件名。
 * @apiSuccess {Number} activatingDoc.fileLabel.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} activatingDoc.fileLabel.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} activatingDoc.fileLabel.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} activatingDoc.fileLabel.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} activatingDoc.fileLabel.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [activatingDoc.fileLabel.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [activatingDoc.fileLabel.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [activatingDoc.fileLabel.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [activatingDoc.fileLabel.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [activatingDoc.fileLabel.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [activatingDoc.fileLabel.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [activatingDoc.fileLabel.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 *
 * @apiSuccessExample {json} 正在处理中-应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 327158635,
 *         "processedDocs": 0,
 *         "progress": "ActivateDoc",
 *         "startTime": 1704111915243,
 *         "stateCode": 0,
 *         "endTime": 0,
 *         "totalDocs": 1,
 *         "activatingDoc": {
 *             "numSegments": 0,
 *             "contactId": 22003303,
 *             "domain": "default_domain",
 *             "scope": "public",
 *             "fileCode": "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *             "id": 2816604439,
 *             "splitter": "Auto",
 *             "fileLabel": {
 *                 "fileName": "2023年大语言模型发展概述.txt",
 *                 "completedTime": 1703942996049,
 *                 "fileCode": "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *                 "ownerId": 22003303,
 *                 "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
 *                 "sha1": "5b208b20aaffbe3e2e12b39d2bbb249838773e1c",
 *                 "fileSize": 6303,
 *                 "domain": "default_domain",
 *                 "expiryTime": 1706534996049,
 *                 "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
 *                 "id": 3397137026,
 *                 "lastModified": 1701756454027,
 *                 "fileType": "txt",
 *                 "md5": "8b49315f06e7a312c89eb8028b1c6d2c"
 *             },
 *             "timestamp": 1704111915133,
 *             "activated": false
 *         }
 *     }
 * 
 * @apiSuccessExample {json} 处理结束-应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 327158635,
 *         "processedDocs": 1,
 *         "progress": "End",
 *         "startTime": 1704111915243,
 *         "stateCode": 0,
 *         "endTime": 1704112037416,
 *         "totalDocs": 1
 *     }
 * 
 * @apiError 400 数据出错。
 * @apiError 401 令牌出错。
 * @apiError 403 参数出错。
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/aigc/knowledge/reset/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?sn=327158635&base=document
 */


/**
 * @api {post} /aigc/knowledge/import/:token Document - 导入知识库文档
 * @apiName 导入知识库文档
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 将已上传的文件加入指定知识库，使其内容可用于知识库问答。先上传并取得文件码，再用 fileCode 导入单个文件，或用 fileCodeList 导入多个文件；base 填写实际知识库名。
 *                 <p>单文件请求返回知识文档对象；批量请求返回操作进度，保存 <code>sn</code>，再调用相应的进度查询接口，同时提交相同的 <code>base</code>。<code>stateCode=7</code> 时继续查询，<code>0</code> 表示操作完成，其他值按异常处理。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} [fileCode] 指定导入文件的文件码。当仅导入一个文档时使用该参数。
 * @apiBody {Array} [fileCodeList] 指定导入文件的文件码清单。当导入多个文档时使用该参数。
 * @apiBody {String} base 知识库库名。
 * 
 *
 * @apiParamExample {json} 导入一个文件
 *     {
 *         "fileCode": "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *         "base": "document"
 *     }
 *
 * @apiParamExample {json} 导入多个文件
 *     {
 *         "fileCodeList": [
 *             "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *             "XUvHsheBTvyQusIRBQCpANdxojdHtLHgWvxhYBZAkECKgizIlpZwwZCNxbKvmCeK",
 *             "MqMElyJWzMWMhfveBDPpAaqkbJqHrwFeUGiSjmXlvpAKtizvYnkuUkalVzitKNCv"
 *         ],
 *         "base": "document"
 *     }
 * @apiUse KnowledgeDoc
 * @apiUse KnowledgeProgress
 * 
 * @apiUse KnowledgeDocSuccessExample
 * @apiUse KnowledgeProgressSuccessExample
 * 
 * @apiError 400 执行时出错。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/knowledge/import/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "fileCode": "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *         "base": "document"
 *     }
 */


/**
 * @api {get} /aigc/knowledge/import/:token Document - 查询导入知识库操作进度
 * @apiName 查询导入知识库操作进度
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 使用对应提交接口返回的操作序号 <code>sn</code> 与相同的知识库名 <code>base</code> 查询执行进度。
 *                 <p><code>stateCode=7</code> 时任务仍在处理，可间隔数秒重查；<code>0</code> 表示完成，其他值按下方状态码处理。使用 <code>processedDocs</code> 与 <code>totalDocs</code> 展示文档处理进度；不要重复提交同一操作来获取状态。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {Number} sn 指定查询操作的序号。
 * @apiQuery {String} base 知识库库名。
 * 
 * @apiUse KnowledgeProgress
 * @apiUse KnowledgeProgressSuccessExample
 * 
 * @apiError 400 执行时出错。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/aigc/knowledge/import/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?sn=327158635&base=document
 */


/**
 * @api {post} /aigc/knowledge/remove/:token Document - 移除知识库文档
 * @apiName 移除知识库文档
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 将指定文件对应的文档移出知识库。单个文档使用 fileCode，多个文档使用 fileCodeList，并提供相同的 base；此操作不代替源文件删除接口。
 *                 <p>单文件请求返回知识文档对象；批量请求返回操作进度，保存 <code>sn</code>，再调用相应的进度查询接口，同时提交相同的 <code>base</code>。<code>stateCode=7</code> 时继续查询，<code>0</code> 表示操作完成，其他值按异常处理。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} [fileCode] 指定移除文件的文件码。当仅移除一个文档时使用该参数。
 * @apiBody {Array} [fileCodeList] 指定移除文件的文件码清单。当移除多个文档时使用该参数。
 * @apiBody {String} base 知识库库名。
 * 
 *
 * @apiParamExample {json} 移除一个文件
 *     {
 *         "fileCode": "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *         "base": "document"
 *     }
 *
 * @apiParamExample {json} 移除多个文件
 *     {
 *         "fileCodeList": [
 *             "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *             "XUvHsheBTvyQusIRBQCpANdxojdHtLHgWvxhYBZAkECKgizIlpZwwZCNxbKvmCeK",
 *             "MqMElyJWzMWMhfveBDPpAaqkbJqHrwFeUGiSjmXlvpAKtizvYnkuUkalVzitKNCv"
 *         ],
 *         "base": "document"
 *     }
 * @apiUse KnowledgeDoc
 * @apiUse KnowledgeProgress
 * 
 * @apiUse KnowledgeDocSuccessExample
 * @apiUse KnowledgeProgressSuccessExample
 * 
 * @apiError 400 执行时出错。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/knowledge/remove/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "fileCode": "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *         "base": "document"
 *     }
 */


/**
 * @api {get} /aigc/knowledge/remove/:token Document - 查询移除知识库操作进度
 * @apiName 查询移除知识库操作进度
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 使用对应提交接口返回的操作序号 <code>sn</code> 与相同的知识库名 <code>base</code> 查询执行进度。
 *                 <p><code>stateCode=7</code> 时任务仍在处理，可间隔数秒重查；<code>0</code> 表示完成，其他值按下方状态码处理。使用 <code>processedDocs</code> 与 <code>totalDocs</code> 展示文档处理进度；不要重复提交同一操作来获取状态。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {Number} sn 指定查询操作的序号。
 * @apiQuery {String} base 知识库库名。
 * 
 * @apiUse KnowledgeProgress
 * @apiUse KnowledgeProgressSuccessExample
 * 
 * @apiError 400 执行时出错。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/aigc/knowledge/remove/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?sn=327158635&base=document
 */


/**
 * @api {post} /aigc/knowledge/article/append/:token Article - 新增知识库文章
 * @apiName 新增知识库文章
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 将一篇正文直接作为知识库文章保存，适合已有结构化文本的业务场景。
 *                 <p>提供分类、标题、正文、作者和发布日期；保存返回的文章 id，供后续激活、释放或删除使用。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} category 文章的分类。
 * @apiBody {String} title 文章的标题。
 * @apiBody {String} content 文章的内容。
 * @apiBody {String} author 文章原作者。
 * @apiBody {Number} year 文章发布的年份。
 * @apiBody {Number} month 文章发布的月份。
 * @apiBody {Number} date 文章发布的日期。
 * 
 * @apiParamExample {json} 新增文章
 *     {
 *         "category": "财经",
 *         "title": "非凡十年枝繁叶茂——七届南博会见证中国南亚合作硕果累累",
 *         "content": "8月20日，为期5天的第七届中国—南亚博览会在云南昆明落下帷幕。...",
 *         "author": "中国青年网",
 *         "year": 2023,
 *         "month": 8,
 *         "date": 21
 *     }
 * 
 * @apiSuccess {Number} id 文章 ID 。
 * @apiSuccess {Number} timestamp 新增文章的时间戳。
 * @apiSuccess {String} category 文章的分类。
 * @apiSuccess {String} title 文章的标题。
 * @apiSuccess {String} author 文章原作者。
 * @apiSuccess {Number} year 文章发布的年份。
 * @apiSuccess {Number} month 文章发布的月份。
 * @apiSuccess {Number} date 文章发布的日期。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "id": 805692013,
 *         "category": "财经",
 *         "title": "非凡十年枝繁叶茂——七届南博会见证中国南亚合作硕果累累",
 *         "author": "中国青年网",
 *         "timestamp": 1693805222066,
 *         "year": 2023,
 *         "month": 8,
 *         "date": 21
 *     }
 * 
 * @apiError 400 数据出错。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/knowledge/article/append/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "category": "财经",
 *         "title": "非凡十年枝繁叶茂——七届南博会见证中国南亚合作硕果累累",
 *         "content": "8月20日，为期5天的第七届中国—南亚博览会在云南昆明落下帷幕。...",
 *         "author": "中国青年网",
 *         "year": 2023,
 *         "month": 8,
 *         "date": 21
 *     }
 */


/**
 * @api {post} /aigc/knowledge/article/remove/:token Article - 删除知识库文章
 * @apiName 删除知识库文章
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 按文章 ID 列表删除知识库文章。
 *                 <p>提交前确认 ids，对仍需用于问答的文章应保留；返回结果用于确认本次处理的文章。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Array} ids 待删除的文章 ID 列表。
 * 
 * @apiParamExample {json} 删除文章
 *     {
 *         "ids": [
 *             805692013,
 *             748385181
 *         ]
 *     }
 * 
 * @apiSuccess {Array} ids 已删除文章的 ID 列表。
 * @apiSuccess {Number} total 删除文章总数量。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "ids": [ 805692013, 748385181 ],
 *         "total": 2
 *     }
 * 
 * @apiError 400 数据出错。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/knowledge/article/remove/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "ids": [
 *             805692013,
 *             748385181
 *         ]
 *     }
 */


/**
 * @api {post} /aigc/knowledge/article/activate/:token Article - 激活知识库文章
 * @apiName 激活知识库文章
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 使指定文章可用于知识库检索与问答。
 *                 <p>ids 使用新增或查询得到的文章 ID；从返回列表确认各文章的 activated 状态。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Array} ids 待激活文章的 ID 列表。
 * 
 * @apiParamExample {json} 激活文章
 *     {
 *         "ids": [
 *             3129977303
 *         ]
 *     }
 * 
 * @apiSuccess {Number} total 激活文章总数量。
 * @apiSuccess {Array} list 激活的文章列表。
 * @apiSuccess {Number} list.id 文章 ID 。
 * @apiSuccess {Number} list.timestamp 文章的时间戳。
 * @apiSuccess {String} list.category 文章的分类。
 * @apiSuccess {String} list.title 文章的标题。
 * @apiSuccess {String} list.author 文章原作者。
 * @apiSuccess {Number} list.year 文章发布的年份。
 * @apiSuccess {Number} list.month 文章发布的月份。
 * @apiSuccess {Number} list.date 文章发布的日期。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 1,
 *         "list": [{
 *             "date": 21,
 *             "month": 8,
 *             "year": 2023,
 *             "author": "中国青年网",
 *             "id": 3129977303,
 *             "category": "财经",
 *             "title": "非凡十年枝繁叶茂——七届南博会见证中国南亚合作硕果累累",
 *             "timestamp": 1693881829728
 *         }]
 *     }
 * 
 * @apiError 400 数据出错。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/knowledge/article/activate/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "ids": [
 *             3129977303
 *         ]
 *     }
 */


/**
 * @api {post} /aigc/knowledge/article/deactivate/:token Article - 释放知识库文章
 * @apiName 释放知识库文章
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 使指定文章退出可用于问答的资料范围。
 *                 <p>ids 使用实际文章 ID。需要恢复使用时重新激活；需要删除文章本身时调用“删除知识库文章”。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Array} ids 需释放文章的 ID 列表。
 * 
 * @apiParamExample {json} 释放文章
 *     {
 *         "ids": [
 *             3129977303
 *         ]
 *     }
 * 
 * @apiSuccess {Number} total 释放文章总数量。
 * @apiSuccess {Array} list 释放的文章列表。
 * @apiSuccess {Number} list.id 文章 ID 。
 * @apiSuccess {Number} list.timestamp 文章的时间戳。
 * @apiSuccess {String} list.category 文章的分类。
 * @apiSuccess {String} list.title 文章的标题。
 * @apiSuccess {String} list.author 文章原作者。
 * @apiSuccess {Number} list.year 文章发布的年份。
 * @apiSuccess {Number} list.month 文章发布的月份。
 * @apiSuccess {Number} list.date 文章发布的日期。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 1,
 *         "list": [{
 *             "date": 21,
 *             "month": 8,
 *             "year": 2023,
 *             "author": "中国青年网",
 *             "id": 3129977303,
 *             "category": "财经",
 *             "title": "非凡十年枝繁叶茂——七届南博会见证中国南亚合作硕果累累",
 *             "timestamp": 1693881829728
 *         }]
 *     }
 * 
 * @apiError 400 数据出错。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/knowledge/article/deactivate/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "ids": [
 *             3129977303
 *         ]
 *     }
 */

/**
 * @api {post} /aigc/knowledge/qa/:token QA - 基于知识库的问答（提交）
 * @apiName 提交知识库问答
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 *
 * @apiDescription 在指定频道内，根据知识库资料回答问题。先创建频道并确认目标知识库已包含可用文档或文章，将频道码填入 <code>channel</code>，问题填入 <code>query</code>，可用 <code>base</code> 指定知识库。
 *                 <p>默认 <code>sync=true</code>，等待本次回答；设置 <code>sync=false</code> 可先取得进度，再用相同的 <code>channel</code> 与 <code>base</code> 查询。一次问答结束后再在同一频道提交下一问，便于关联进度与问题。</p>
 *                 <p>通过 <code>code</code> 判断任务状态：<code>7</code> 正在处理、<code>8</code> 正在生成、<code>0</code> 已完成，其他值按异常处理。完成后读取 <code>result.record.answer</code>；<code>result.sources[]</code> 提供引用来源及 <code>segment</code> 原文。<code>percent</code> 仅用于展示，可将显示值限制在 0 至 100，完成判断以 code 为准。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} channel 频道码。用于隔离会话上下文。
 * @apiBody {String} query 提问内容。
 * @apiBody {Boolean} [sync] 是否同步返回结果。默认值：<code>true</code>。
 * @apiBody {String} [base] 指定参与检索的知识库分类名。不设置时使用该频道的默认知识库。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "channel": "mnxRSaLdPuHEVaKA",
 *         "query": "固态电池的能量密度是多少",
 *         "sync": true
 *     }
 * 
 * @apiSuccess {Number} sn 本次问答任务的序列号。
 * @apiSuccess {Object} channel 频道数据。
 * @apiSuccess {String} channel.code 频道码。
 * @apiSuccess {Number} channel.creationTime 频道创建时间，Unix 毫秒时间戳。
 * @apiSuccess {String} channel.participant 频道参与人名称。
 * @apiSuccess {String} channel.language 频道语言标识 cn 或 en。
 * @apiSuccess {Number} start 任务开始时间戳。
 * @apiSuccess {Number} end 任务结束时间戳。
 * @apiSuccess {Number} percent 处理进度参考值；展示时可限制在 0 至 100，任务完成以 code=0 为准。
 * @apiSuccess {Number} code 任务状态码：7 正在处理，8 正在生成，0 已完成；其他值表示异常。
 * @apiSuccess {Object} [result] 问答结果，任务完成后返回。
 * @apiSuccess {String} result.query 用户原始问题。
 * @apiSuccess {Object} [result.record] 生成记录；任务完成并产生记录时返回。
 * @apiSuccess {Number} result.record.sn 生成记录序列号。
 * @apiSuccess {String} [result.record.unit] 生成模型单元名。
 * @apiSuccess {String} [result.record.query] 用户原始问题。
 * @apiSuccess {String} [result.record.answer] 生成的回答文本。
 * @apiSuccess {String} [result.record.thought] 模型思考文本。
 * @apiSuccess {Number} result.record.timestamp 记录生成时间，Unix 毫秒时间戳。
 * @apiSuccess {Object[]} [result.sources] 本次回答引用的知识来源；无来源时可省略。
 * @apiSuccess {String} [result.sources.segment] 引用的原文片段；片段来源时返回。
 * @apiSuccess {Object} [result.sources.document] 文档来源时返回的知识文档。
 * @apiSuccess {Number} result.sources.document.id 知识文档 ID。
 * @apiSuccess {String} result.sources.document.domain 文档所属域。
 * @apiSuccess {Number} result.sources.document.timestamp 文档创建时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.sources.document.contactId 文档所属的触点 ID。
 * @apiSuccess {String} result.sources.document.fileCode 源文件的文件码。
 * @apiSuccess {String} result.sources.document.baseName 知识库分类名称。
 * @apiSuccess {String} [result.sources.document.fileName] 源文件名。
 * @apiSuccess {Boolean} result.sources.document.activated 文档是否已激活。
 * @apiSuccess {String} result.sources.document.splitter 分段器名称。
 * @apiSuccess {Number} result.sources.document.numSegments 文档片段数；尚未分段时可能为 -1。
 * @apiSuccess {String} result.sources.document.scope 文档适用范围。
 * @apiSuccess {String[]} result.sources.document.knowledgeLabels 知识标签列表。
 * @apiSuccess {Object} [result.sources.document.fileLabel] 源文件标签。
 * @apiSuccess {Number} result.sources.document.fileLabel.id 文件标签 ID。
 * @apiSuccess {String} result.sources.document.fileLabel.domain 文件所属域。
 * @apiSuccess {String} result.sources.document.fileLabel.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} result.sources.document.fileLabel.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} result.sources.document.fileLabel.fileName 原始文件名。
 * @apiSuccess {Number} result.sources.document.fileLabel.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} result.sources.document.fileLabel.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.sources.document.fileLabel.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.sources.document.fileLabel.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} result.sources.document.fileLabel.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [result.sources.document.fileLabel.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [result.sources.document.fileLabel.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [result.sources.document.fileLabel.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [result.sources.document.fileLabel.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [result.sources.document.fileLabel.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [result.sources.document.fileLabel.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [result.sources.document.fileLabel.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 * @apiSuccess {Object} [result.sources.article] 文章来源时返回的文章元数据。
 * @apiSuccess {String} result.sources.article.domain 文章所属域。
 * @apiSuccess {Number} result.sources.article.timestamp 文章创建时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.sources.article.contactId 文章所属触点 ID。
 * @apiSuccess {String} result.sources.article.base 知识库分类名称。
 * @apiSuccess {String} result.sources.article.category 文章分类。
 * @apiSuccess {String} result.sources.article.author 文章作者。
 * @apiSuccess {Number} result.sources.article.year 文章日期的年份。
 * @apiSuccess {Number} result.sources.article.month 文章日期的月份。
 * @apiSuccess {Number} result.sources.article.date 文章日期的日。
 * @apiSuccess {String} result.sources.article.scope 文章适用范围。
 * @apiSuccess {Boolean} result.sources.article.activated 文章是否已激活。
 * @apiSuccess {Number} result.sources.article.numSegments 文章片段数。
 * @apiSuccess {String} result.sources.article.splitter 分段器名称。
 * @apiSuccess {Number} result.sources.article.numWords 文章字数；该精简对象不包含正文。
 * @apiSuccess {Number} result.sources.article.id 文章 ID。
 * @apiSuccess {String} result.sources.article.title 文章标题。
 * 
 * @apiError 400 知识库服务返回异常。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {json} 异步提问：指定知识库
 *     {
 *         "channel": "mnxRSaLdPuHEVaKA",
 *         "base": "document",
 *         "query": "产品保修期多长？",
 *         "sync": false
 *     }
 *
 * @apiSuccessExample {json} 异步提交：任务已受理
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 3121624789,
 *         "channel": {
 *             "code": "mnxRSaLdPuHEVaKA",
 *             "creationTime": 1793321900000,
 *             "participant": "知识助手",
 *             "language": "cn"
 *         },
 *         "start": 1793322000000,
 *         "end": 0,
 *         "percent": 0,
 *         "code": 7
 *     }
 *
 * @apiSuccessExample {json} 回答完成：含回答与引用原文
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 3121624789,
 *         "channel": {
 *             "code": "mnxRSaLdPuHEVaKA",
 *             "creationTime": 1793321900000,
 *             "participant": "知识助手",
 *             "language": "cn"
 *         },
 *         "start": 1793322000000,
 *         "end": 1793322005000,
 *         "percent": 150,
 *         "code": 0,
 *         "result": {
 *             "query": "产品保修期多长？",
 *             "record": {
 *                 "sn": 3121624790,
 *                 "unit": "Baize",
 *                 "query": "产品保修期多长？",
 *                 "answer": "根据产品手册，保修期为自购买之日起一年。",
 *                 "timestamp": 1793322005000
 *             },
 *             "sources": [
 *                 {
 *                     "segment": "保修期：自购买之日起一年。"
 *                 }
 *             ]
 *         }
 *     }
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/knowledge/qa/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "channel": "mnxRSaLdPuHEVaKA",
 *         "query": "固态电池的能量密度是多少",
 *         "sync": true
 *     }
 */

/**
 * @api {get} /aigc/knowledge/qa/:token QA - 基于知识库的问答（查询）
 * @apiName 查询知识库问答进度
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 *
 * @apiDescription 查询指定频道最近一次知识库问答，使用提交时相同的 <code>channel</code> 与 <code>base</code>。这里按频道查询，<code>sn</code> 用于识别返回的是哪次任务。
 *                 <p><code>code=7</code> 或 <code>8</code> 时继续等待，<code>0</code> 时读取 <code>result.record.answer</code>，其他值表示异常。成功的 HTTP 响应也可能包含未完成任务，必须同时检查业务状态。<code>percent</code> 仅供展示，可能超过 100，不用于判定完成。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {String} channel 频道码。
 * @apiQuery {String} [base] 知识库分类名。需要与提交时保持一致。
 * 
 * @apiSuccess {Number} sn 本次问答任务的序列号。
 * @apiSuccess {Object} channel 频道数据。
 * @apiSuccess {String} channel.code 频道码。
 * @apiSuccess {Number} channel.creationTime 频道创建时间，Unix 毫秒时间戳。
 * @apiSuccess {String} channel.participant 频道参与人名称。
 * @apiSuccess {String} channel.language 频道语言标识 cn 或 en。
 * @apiSuccess {Number} start 任务开始时间戳。
 * @apiSuccess {Number} end 任务结束时间戳。
 * @apiSuccess {Number} percent 处理进度参考值；展示时可限制在 0 至 100，任务完成以 code=0 为准。
 * @apiSuccess {Number} code 任务状态码：7 正在处理，8 正在生成，0 已完成；其他值表示异常。
 * @apiSuccess {Object} [result] 问答结果，任务完成后返回。
 * @apiSuccess {String} result.query 用户原始问题。
 * @apiSuccess {Object} [result.record] 生成记录；任务完成并产生记录时返回。
 * @apiSuccess {Number} result.record.sn 生成记录序列号。
 * @apiSuccess {String} [result.record.unit] 生成模型单元名。
 * @apiSuccess {String} [result.record.query] 用户原始问题。
 * @apiSuccess {String} [result.record.answer] 生成的回答文本。
 * @apiSuccess {String} [result.record.thought] 模型思考文本。
 * @apiSuccess {Number} result.record.timestamp 记录生成时间，Unix 毫秒时间戳。
 * @apiSuccess {Object[]} [result.sources] 本次回答引用的知识来源；无来源时可省略。
 * @apiSuccess {String} [result.sources.segment] 引用的原文片段；片段来源时返回。
 * @apiSuccess {Object} [result.sources.document] 文档来源时返回的知识文档。
 * @apiSuccess {Number} result.sources.document.id 知识文档 ID。
 * @apiSuccess {String} result.sources.document.domain 文档所属域。
 * @apiSuccess {Number} result.sources.document.timestamp 文档创建时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.sources.document.contactId 文档所属的触点 ID。
 * @apiSuccess {String} result.sources.document.fileCode 源文件的文件码。
 * @apiSuccess {String} result.sources.document.baseName 知识库分类名称。
 * @apiSuccess {String} [result.sources.document.fileName] 源文件名。
 * @apiSuccess {Boolean} result.sources.document.activated 文档是否已激活。
 * @apiSuccess {String} result.sources.document.splitter 分段器名称。
 * @apiSuccess {Number} result.sources.document.numSegments 文档片段数；尚未分段时可能为 -1。
 * @apiSuccess {String} result.sources.document.scope 文档适用范围。
 * @apiSuccess {String[]} result.sources.document.knowledgeLabels 知识标签列表。
 * @apiSuccess {Object} [result.sources.document.fileLabel] 源文件标签。
 * @apiSuccess {Number} result.sources.document.fileLabel.id 文件标签 ID。
 * @apiSuccess {String} result.sources.document.fileLabel.domain 文件所属域。
 * @apiSuccess {String} result.sources.document.fileLabel.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} result.sources.document.fileLabel.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} result.sources.document.fileLabel.fileName 原始文件名。
 * @apiSuccess {Number} result.sources.document.fileLabel.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} result.sources.document.fileLabel.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.sources.document.fileLabel.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.sources.document.fileLabel.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} result.sources.document.fileLabel.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [result.sources.document.fileLabel.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [result.sources.document.fileLabel.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [result.sources.document.fileLabel.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [result.sources.document.fileLabel.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [result.sources.document.fileLabel.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [result.sources.document.fileLabel.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [result.sources.document.fileLabel.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 * @apiSuccess {Object} [result.sources.article] 文章来源时返回的文章元数据。
 * @apiSuccess {String} result.sources.article.domain 文章所属域。
 * @apiSuccess {Number} result.sources.article.timestamp 文章创建时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.sources.article.contactId 文章所属触点 ID。
 * @apiSuccess {String} result.sources.article.base 知识库分类名称。
 * @apiSuccess {String} result.sources.article.category 文章分类。
 * @apiSuccess {String} result.sources.article.author 文章作者。
 * @apiSuccess {Number} result.sources.article.year 文章日期的年份。
 * @apiSuccess {Number} result.sources.article.month 文章日期的月份。
 * @apiSuccess {Number} result.sources.article.date 文章日期的日。
 * @apiSuccess {String} result.sources.article.scope 文章适用范围。
 * @apiSuccess {Boolean} result.sources.article.activated 文章是否已激活。
 * @apiSuccess {Number} result.sources.article.numSegments 文章片段数。
 * @apiSuccess {String} result.sources.article.splitter 分段器名称。
 * @apiSuccess {Number} result.sources.article.numWords 文章字数；该精简对象不包含正文。
 * @apiSuccess {Number} result.sources.article.id 文章 ID。
 * @apiSuccess {String} result.sources.article.title 文章标题。
 * 
 * @apiError 400 知识库服务返回异常。
 * @apiError 401 Token 无效。
 * @apiError 403 未提供 <code>channel</code> 参数。
 *
 * @apiSuccessExample {json} 处理中
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 3121624789,
 *         "channel": {
 *             "code": "mnxRSaLdPuHEVaKA",
 *             "creationTime": 1793321900000,
 *             "participant": "知识助手",
 *             "language": "cn"
 *         },
 *         "start": 1793322000000,
 *         "end": 0,
 *         "percent": 0,
 *         "code": 7
 *     }
 *
 * @apiSuccessExample {json} 已完成：读取 result.record.answer
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 3121624789,
 *         "channel": {
 *             "code": "mnxRSaLdPuHEVaKA",
 *             "creationTime": 1793321900000,
 *             "participant": "知识助手",
 *             "language": "cn"
 *         },
 *         "start": 1793322000000,
 *         "end": 1793322005000,
 *         "percent": 150,
 *         "code": 0,
 *         "result": {
 *             "query": "产品保修期多长？",
 *             "record": {
 *                 "sn": 3121624790,
 *                 "unit": "Baize",
 *                 "query": "产品保修期多长？",
 *                 "answer": "根据产品手册，保修期为自购买之日起一年。",
 *                 "timestamp": 1793322005000
 *             },
 *             "sources": [
 *                 {
 *                     "segment": "保修期：自购买之日起一年。"
 *                 }
 *             ]
 *         }
 *     }
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/aigc/knowledge/qa/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?channel=mnxRSaLdPuHEVaKA&base=document
 */

/**
 * @api {post} /aigc/knowledge/update/:token Admin - 更新知识库设置
 * @apiName 更新知识库设置
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 *
 * @apiDescription 更新指定知识库的显示名、分类等信息。先从“获取知识库框架信息”取得目标 <code>info</code>，保留其识别字段与其他数据，再修改需要更新的字段并整体提交。
 *                 <p><code>contactId</code> 与 <code>name</code> 用于定位知识库，请勿使用显示名代替库名。成功响应为更新后的信息对象；用户容量、共享范围与可用状态通过“更新知识库设置参数”调整。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Object} info 知识库信息对象。
 * @apiBody {Number} info.contactId 知识库归属的触点ID 。
 * @apiBody {String} info.name 知识库名称，与 <code>contactId</code> 一起定位知识库。
 * @apiBody {String} info.displayName 知识库显示名。
 * @apiBody {Number} info.unitId 知识库使用的模型单元 ID 。
 * @apiBody {Number} info.storeSize 知识库存储容量。
 * @apiBody {Number} info.timestamp 知识库数据时间戳。
 * @apiBody {String} [info.category] 知识库分类，为空时不返回该字段。
 * 
 *
 * @apiParamExample {json} 请求示例
 *     {
 *         "info": {
 *             "contactId": 22003303,
 *             "name": "document",
 *             "displayName": "产品手册（2026）",
 *             "unitId": 1001,
 *             "storeSize": 10485760,
 *             "timestamp": 1741657060177,
 *             "category": "售后"
 *         }
 *     }
 * @apiSuccess {Number} contactId 知识库归属的触点ID 。
 * @apiSuccess {String} name 知识库名称。
 * @apiSuccess {String} displayName 知识库显示名。
 * @apiSuccess {Number} unitId 知识库使用的模型单元 ID 。
 * @apiSuccess {Number} storeSize 知识库存储容量。
 * @apiSuccess {Number} timestamp 更新时间戳。
 * @apiSuccess {String} [category] 知识库分类。
 * 
 * @apiError 400 更新失败或知识库不存在。
 * @apiError 401 Token 无效。
 * @apiError 403 请求体解析失败，或缺少 <code>info</code> 内的必需字段。
 *
 * @apiSuccessExample {json} 更新后的知识库信息
 *     HTTP/1.1 200 OK
 *     {
 *         "contactId": 22003303,
 *         "name": "document",
 *         "displayName": "产品手册（2026）",
 *         "unitId": 1001,
 *         "storeSize": 10485760,
 *         "timestamp": 1741657060177,
 *         "category": "售后"
 *     }
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/knowledge/update/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "info": {
 *             "contactId": 22003303,
 *             "name": "document",
 *             "displayName": "产品手册（2026）",
 *             "unitId": 1001,
 *             "storeSize": 10485760,
 *             "timestamp": 1741657060177,
 *             "category": "售后"
 *         }
 *     }
 */
