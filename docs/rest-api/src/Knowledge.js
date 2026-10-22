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
 * @apiSuccess {Object} [processingDoc] 当前进度中正在处理的文档。
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
 *             "fileSecureURL": "https://127.0.0.1:7017/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
 *             "sha1": "5b208b20aaffbe3e2e12b39d2bbb249838773e1c",
 *             "fileSize": 6303,
 *             "domain": "default_domain",
 *             "expiryTime": 1706534996049,
 *             "fileURL": "http://127.0.0.1:7010/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
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
 *         "processingDoc": {
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
 *                 "fileSecureURL": "https://127.0.0.1:7017/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
 *                 "sha1": "5b208b20aaffbe3e2e12b39d2bbb249838773e1c",
 *                 "fileSize": 6303,
 *                 "domain": "default_domain",
 *                 "expiryTime": 1706534996049,
 *                 "fileURL": "http://127.0.0.1:7010/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
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
 * @apiSuccess {Number} contactId 分类库的触点ID 。
 * @apiSuccess {String} displayName 分类库显示名称。
 * @apiSuccess {String} name 分类库分类名。
 * @apiSuccess {String} [category] 分类库的分类描述。
 * @apiSuccess {Number} unitId 分类库使用的单元 ID 。<code>0</code> 表示自动分配单元。
 * @apiSuccess {Number} storeSize 分类库已占用的存储空间大小，单位：字节。
 * @apiSuccess {Number} timestamp 分类库最近更新时间戳。
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
 * @apiDescription 获取指定用户知识库配置信息数据。配置数据包括每个分类知识库的基础信息。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiSuccess {Number} total 触点可使用的分类库总数。
 * @apiSuccess {Array} infoList 分类库数据列表。
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
 */


/**
 * @api {post} /aigc/knowledge/new/:token 新建分类知识库
 * @apiName 新建分类知识库
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 新建分类知识库。新建的知识库只对当前用户可见。对于作用域为 <code>public</code> 的分类库，也只对新建人可见。
 *                 因此如果需要全局所有人使用新建库，需要逐一调用该接口为用户新建指定的分类库。
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
 */


/**
 * @api {post} /aigc/knowledge/delete/:token 删除分类知识库
 * @apiName 删除分类知识库
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 删除分类知识库，同时删除库里的所有数据。
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
 */


/**
 * @api {post} /aigc/knowledge/profile/:token 更新知识库设置参数
 * @apiName 更新知识库设置参数
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 更新指定用户的知识库设置参数。
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
 */


/**
 * @api {post} /aigc/knowledge/reset/:token 重置知识库数据
 * @apiName 重置知识库数据
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 重置知识库操作将重新计算所有已经导入的文档。该操作会依次处理每一个文档，因此执行时间较长。
 *                 当调用该接口后，接口会立即返回进度描述，通过获取进度信息来确定重置是否执行完毕。
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
 */


/**
 * @api {get} /aigc/knowledge/reset/:token 获取重置知识库操作进度
 * @apiName 获取重置知识库操作进度
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 重置知识库操作将重新计算所有已经导入的文档。该操作会依次处理每一个文档，因此执行时间较长。
 *                 当调用该接口后，接口会立即返回进度描述，通过获取进度信息来确定重置是否执行完毕。
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
 *                 "fileSecureURL": "https://127.0.0.1:7017/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
 *                 "sha1": "5b208b20aaffbe3e2e12b39d2bbb249838773e1c",
 *                 "fileSize": 6303,
 *                 "domain": "default_domain",
 *                 "expiryTime": 1706534996049,
 *                 "fileURL": "http://127.0.0.1:7010/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
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
 */


/**
 * @api {post} /aigc/knowledge/import/:token Document - 导入知识库文档
 * @apiName 导入知识库文档
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 将一个或多个文件导入知识库形成知识库文档。当模型工作在知识库模式时将使用相关的文件进行推理。
 *                 <p>当导入多个文件时使用<code>查询导入知识库操作进度</code>接口查询导入是否完成。</p>
 *                 <p>导入一个文件时接口返回知识文档数据，导入多个文件时接口返回操作进度数据。</p>
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} [fileCode] 指定导入文件的文件码。当仅导入一个文档时使用该参数。
 * @apiBody {Array} [fileCodeList] 指定导入文件的文件码清单。当导入多个文档时使用该参数。
 * @apiBody {String} base 知识库库名。
 * 
 * @apiParamExample {json} 导入一个文件
 *     {
 *         "fileCode": "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk"
 *     }
 * 
 * @apiParamExample {json} 导入多个文件
 *     {
 *         "fileCodeList": [
 *             "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *             "XUvHsheBTvyQusIRBQCpANdxojdHtLHgWvxhYBZAkECKgizIlpZwwZCNxbKvmCeK",
 *             "MqMElyJWzMWMhfveBDPpAaqkbJqHrwFeUGiSjmXlvpAKtizvYnkuUkalVzitKNCv",
 *             ...
 *         ]
 *     }
 * 
 * @apiUse KnowledgeDoc
 * @apiUse KnowledgeProgress
 * 
 * @apiUse KnowledgeDocSuccessExample
 * @apiUse KnowledgeProgressSuccessExample
 * 
 * @apiError 400 执行时出错。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 */


/**
 * @api {get} /aigc/knowledge/import/:token Document - 查询导入知识库操作进度
 * @apiName 查询导入知识库操作进度
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 查询指定知识库的操作进度。
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
 */


/**
 * @api {post} /aigc/knowledge/remove/:token Document - 移除知识库文档
 * @apiName 移除知识库文档
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 将一个或多个知识库文档从知识库里移除。移除的文件内容将从知识库里删除。
 *                 <p>当移除多个文件时使用<code>查询移除知识库操作进度</code>接口查询移除是否完成。</p>
 *                 <p>移除一个文件时接口返回知识文档数据，移除多个文件时接口返回操作进度数据。</p>
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} [fileCode] 指定移除文件的文件码。当仅移除一个文档时使用该参数。
 * @apiBody {Array} [fileCodeList] 指定移除文件的文件码清单。当移除多个文档时使用该参数。
 * @apiBody {String} base 知识库库名。
 * 
 * @apiParamExample {json} 移除一个文件
 *     {
 *         "fileCode": "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk"
 *     }
 * 
 * @apiParamExample {json} 移除多个文件
 *     {
 *         "fileCodeList": [
 *             "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *             "XUvHsheBTvyQusIRBQCpANdxojdHtLHgWvxhYBZAkECKgizIlpZwwZCNxbKvmCeK",
 *             "MqMElyJWzMWMhfveBDPpAaqkbJqHrwFeUGiSjmXlvpAKtizvYnkuUkalVzitKNCv",
 *             ...
 *         ]
 *     }
 * 
 * @apiUse KnowledgeDoc
 * @apiUse KnowledgeProgress
 * 
 * @apiUse KnowledgeDocSuccessExample
 * @apiUse KnowledgeProgressSuccessExample
 * 
 * @apiError 400 执行时出错。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 */


/**
 * @api {get} /aigc/knowledge/remove/:token Document - 查询移除知识库操作进度
 * @apiName 查询移除知识库操作进度
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 查询指定知识库的操作进度。
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
 */


/**
 * @api {post} /aigc/knowledge/article/append/:token Article - 新增知识库文章
 * @apiName 新增知识库文章
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 向系统添加新的知识库文章。
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
 */


/**
 * @api {post} /aigc/knowledge/article/remove/:token Article - 删除知识库文章
 * @apiName 删除知识库文章
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 从系统中删除知识库文章。
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
 */


/**
 * @api {post} /aigc/knowledge/article/activate/:token Article - 激活知识库文章
 * @apiName 激活知识库文章
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 激活指定的知识库文章。
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
 */


/**
 * @api {post} /aigc/knowledge/article/deactivate/:token Article - 释放知识库文章
 * @apiName 释放知识库文章
 * @apiGroup Knowledge
 * @apiVersion 1.0.0
 * 
 * @apiDescription 释放指定的知识库文章。释放后文章内容从知识库中移除，文章内容不参与后续的大模型内容生成。
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
 */
