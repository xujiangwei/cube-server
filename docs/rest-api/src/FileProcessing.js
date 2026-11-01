/**
 * @apiDefine File_Processing 文件处理
 */


/**
 * @api {post} /filestorage/operation/list/ 批量获取文件清单
 * @apiName 批量获取文件清单
 * @apiGroup File_Processing
 * @apiVersion 1.0.0
 * 
 * @apiDescription 分页读取当前用户的文件，按上传完成时间倒序排列。默认查询索引 <code>0</code> 至 <code>9</code>；下一页可使用 <code>begin=10</code>、<code>end=19</code>。
 *                 <p>从 <code>data.list</code> 读取文件标签，使用 <code>data.total</code> 判断是否还有数据。<code>hierarchy=true</code> 时一次返回根目录下的全部文件并忽略分页，此时响应字段差异见参数表。</p>
 *
 * @apiHeader {String} [x-baize-api-version] 省略时返回 {sn,name,data}；设为 v1 时直接返回 data 对象。
 * @apiHeader {String} [x-baize-api-token] 可代替请求体中的 token；两处都提供时优先使用请求体。
 *
 * @apiBody {String} [token] 有效账号的令牌；与请求头 x-baize-api-token 至少提供一处。
 * @apiBody {Number} [begin] 指定查询位置的起始索引。默认值：<code>0</code>。
 * @apiBody {Number} [end] 指定查询位置的结束索引。默认值：<code>9</code>。
 * @apiBody {Boolean} [hierarchy] 是否按目录层级读取。默认值：<code>false</code>。<br/>
 *                              设为 <code>true</code> 时先取该用户的根目录，再一次性返回根目录下的全部文件，
 *                              此时 <code>begin</code> / <code>end</code> 被忽略，且响应中不含 <code>ownerId</code>、<code>domain</code>、<code>begin</code>、<code>end</code> 字段。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "token": "UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *         "begin": 0,
 *         "end": 9
 *     }
 * 
 * @apiSuccess {Number} sn 本次请求的流水号。
 * @apiSuccess {String} name 本次请求的名称。
 * @apiSuccess {Object} data 数据。
 * @apiSuccess {Number} data.ownerId 文件所属者的触点ID 。
 * @apiSuccess {String} data.domain 文件所属者所在的域。
 * @apiSuccess {Number} data.total 文件总数量。
 * @apiSuccess {Object[]} data.list 文件数据清单，每个元素为一个文件标签对象。
 * @apiSuccess {Number} data.list.id 文件标签 ID。
 * @apiSuccess {String} data.list.domain 文件所属域。
 * @apiSuccess {String} data.list.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} data.list.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} data.list.fileName 原始文件名。
 * @apiSuccess {Number} data.list.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} data.list.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} data.list.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} data.list.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} data.list.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [data.list.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [data.list.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [data.list.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [data.list.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [data.list.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [data.list.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [data.list.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 * @apiSuccess {Number} data.begin 文件清单数据的起始索引。
 * @apiSuccess {Number} data.end 文件清单数据的结束索引。
 * 
 *
 *
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 896144295,
 *         "name": "listFileLabels",
 *         "data": {
 *             "ownerId": 22003303,
 *             "domain": "default_domain",
 *             "total": 2,
 *             "begin": 0,
 *             "end": 9,
 *             "list": [
 *                 {
 *                     "fileName": "新闻.docx",
 *                     "completedTime": 1704861088556,
 *                     "fileCode": "XUvHsheBTvyQusIRBQCpANdxojdHtLHgWvxhYBZAkECKgizIlpZwwZCNxbKvmCeK",
 *                     "ownerId": 22003303,
 *                     "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=XUvHsheBTvyQusIRBQCpANdxojdHtLHgWvxhYBZAkECKgizIlpZwwZCNxbKvmCeK&type=docx",
 *                     "sha1": "5acd0a81b871b71408b1fedd0e545c891340c8a1",
 *                     "fileSize": 51642,
 *                     "domain": "default_domain",
 *                     "expiryTime": 1707453088556,
 *                     "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=XUvHsheBTvyQusIRBQCpANdxojdHtLHgWvxhYBZAkECKgizIlpZwwZCNxbKvmCeK&type=docx",
 *                     "id": 3676603220,
 *                     "lastModified": 1704859312422,
 *                     "fileType": "docx",
 *                     "md5": "f107b6ac58928539f822cbbdfc5ef5f2"
 *                 },
 *                 {
 *                     "fileName": "黟县国际乡村旅游度假区.txt",
 *                     "completedTime": 1703942996049,
 *                     "fileCode": "ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk",
 *                     "ownerId": 22003303,
 *                     "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
 *                     "sha1": "5b208b20aaffbe3e2e12b39d2bbb249838773e1c",
 *                     "fileSize": 6303,
 *                     "domain": "default_domain",
 *                     "expiryTime": 1706534996049,
 *                     "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
 *                     "id": 3397137026,
 *                     "lastModified": 1701756454027,
 *                     "fileType": "txt",
 *                     "md5": "8b49315f06e7a312c89eb8028b1c6d2c"
 *                 }
 *             ]
 *         }
 *     }
 * @apiError 400 查找数据时发生错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 * @apiError 404 未找到数据。
 *
 * @apiParamExample {json} 下一页：第 11 至 20 项
 *     {
 *         "token": "UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *         "begin": 10,
 *         "end": 19
 *     }
 *
 * @apiSuccessExample {json} 没有可用文件或已翻到列表末尾
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 896144296,
 *         "name": "listFileLabels",
 *         "data": {
 *             "ownerId": 22003303,
 *             "domain": "default_domain",
 *             "total": 0,
 *             "begin": 10,
 *             "end": 19,
 *             "list": []
 *         }
 *     }
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/filestorage/operation/list/
 *     Content-Type: application/json
 *
 *     {
 *         "token": "UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *         "begin": 0,
 *         "end": 9
 *     }
 */


/**
 * @api {post} /filestorage/operation/find/ 查找已上传的文件
 * @apiName 查找已上传的文件
 * @apiGroup File_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 按文件码、文件名或 MD5 查找当前用户可访问的文件。建议每次只选择一种查询方式；已知文件码时优先使用 <code>fileCode</code>。
 *                 <p>默认从 <code>data.list</code> 读取匹配的文件标签，确认文件码和类型后再调用下载或后续处理接口。</p>
 *
 * @apiHeader {String} [x-baize-api-version] 省略时返回 {sn,name,data}；设为 v1 时直接返回 data 对象。
 * @apiHeader {String} [x-baize-api-token] 可代替请求体中的 token；两处都提供时优先使用请求体。
 *
 * @apiBody {String} [token] 有效账号的令牌；与请求头 x-baize-api-token 至少提供一处。
 * @apiBody {String} [fileName] 按照文件名进行查询。
 * @apiBody {String} [md5] 按照文件的 MD5 进行查询。
 * @apiBody {String} [fileCode] 按照文件码进行查询。
 * 
 * @apiParamExample {json} 请求示例-使用文件码
 *     {
 *         "token": "dbCdxBbfCWAMQprkXroWcLjDWRrtMMsi",
 *         "fileCode": "pAjoxDqsuCvCHatmTRmoPMvlSKqyfJCDIgRbanuvcPZIWOevIfLLVxRmvwUUkJlm"
 *     }
 * 
 * @apiParamExample {json} 请求示例-使用文件名
 *     {
 *         "token": "dbCdxBbfCWAMQprkXroWcLjDWRrtMMsi",
 *         "fileName": "yuebing.wav"
 *     }
 * 
 * @apiParamExample {json} 请求示例-使用MD5码
 *     {
 *         "token": "dbCdxBbfCWAMQprkXroWcLjDWRrtMMsi",
 *         "md5": "354f1fbdb30e5dcce26edaf06d1f5252"
 *     }
 * 
 * @apiSuccess {Object} data 数据。
 * @apiSuccess {Object[]} data.list 查找到的文件数据清单。
 * @apiSuccess {Number} data.list.id 文件标签 ID。
 * @apiSuccess {String} data.list.domain 文件所属域。
 * @apiSuccess {String} data.list.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} data.list.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} data.list.fileName 原始文件名。
 * @apiSuccess {Number} data.list.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} data.list.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} data.list.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} data.list.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} data.list.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [data.list.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [data.list.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [data.list.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [data.list.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [data.list.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [data.list.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [data.list.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 2240457160,
 *         "name": "findFile",
 *         "data": {
 *             "domain": "default_domain",
 *             "contactId": 20230001,
 *             "list": [
 *                 {
 *                     "fileName": "yuebing.wav",
 *                     "completedTime": 1681613195560,
 *                     "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *                     "ownerId": 20230001,
 *                     "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx&type=wav",
 *                     "sha1": "0f6de9dceafff20f2146f3beb88ffdb84ad106db",
 *                     "fileSize": 10248112,
 *                     "domain": "default_domain",
 *                     "expiryTime": 1684205195560,
 *                     "directURL": "http://211.157.179.37:6080/files/UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *                     "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx&type=wav",
 *                     "id": 3222371014,
 *                     "lastModified": 1607927012129,
 *                     "fileType": "wav",
 *                     "md5": "354f1fbdb30e5dcce26edaf06d1f5252"
 *                 }
 *             ]
 *         }
 *     }
 * 
 * @apiError 400 查找数据时发生错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 * @apiError 404 未找到数据。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/filestorage/operation/find/
 *     Content-Type: application/json
 *
 *     {
 *         "token": "dbCdxBbfCWAMQprkXroWcLjDWRrtMMsi",
 *         "fileCode": "pAjoxDqsuCvCHatmTRmoPMvlSKqyfJCDIgRbanuvcPZIWOevIfLLVxRmvwUUkJlm"
 *     }
 */



/**
 * @api {post} /filestorage/operation/delete/ 删除已上传的文件
 * @apiName 删除已上传的文件
 * @apiGroup File_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 通过 <code>fileCodeList</code> 或 <code>md5List</code> 删除已上传文件，至少提供一种清单。调用前可用查找接口确认目标。
 *                 <p>默认从 <code>data.list</code> 读取删除结果。删除后，使用原文件的下载或分析功能可能不可用；应用应同步移除对应文件入口。</p>
 *
 * @apiHeader {String} [x-baize-api-version] 省略时返回 {sn,name,data}；设为 v1 时直接返回 data 对象。
 * @apiHeader {String} [x-baize-api-token] 可代替请求体中的 token；两处都提供时优先使用请求体。
 *
 * @apiBody {String} [token] 有效账号的令牌；与请求头 x-baize-api-token 至少提供一处。
 * @apiBody {Array} [fileCodeList] 指定待删除文件的文件码列表。
 * @apiBody {Array} [md5List] 指定待删除文件的 MD5 码列表。
 * 
 * @apiParamExample {json} 请求示例-使用文件码
 *     {
 *         "token": "dbCdxBbfCWAMQprkXroWcLjDWRrtMMsi",
 *         "fileCodeList": [
 *             "ZwWjxuebTvyQusIRBQCpANsMdYfWGyhTWVkuLBZnXePmVXoKAPmWwMPNXbkvzpRK",
 *             "CSoqOXtVMCjScTDoKvsPkQUFbVKCRLSXKKKqOdfaXRcmVXOKACMWJZpnkbXVZpEx"
 *          ]
 *     }
 * 
 * @apiParamExample {json} 请求示例-使用MD5码
 *     {
 *         "token": "dbCdxBbfCWAMQprkXroWcLjDWRrtMMsi",
 *         "md5List": [
 *             "354f1fbdb30e5dcce26edaf06d1f5252",
 *             "112629479e47e90164f7ecc3e752f0d2"
 *         ]
 *     }
 * 
 * @apiSuccess {Number} sn 本次请求的流水号。
 * @apiSuccess {String} name 本次请求的名称。
 * @apiSuccess {Object} data 数据。
 * @apiSuccess {Object[]} data.list 已删除的文件数据清单，每个元素为一个文件标签对象。
 * @apiSuccess {Number} data.list.id 文件标签 ID。
 * @apiSuccess {String} data.list.domain 文件所属域。
 * @apiSuccess {String} data.list.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} data.list.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} data.list.fileName 原始文件名。
 * @apiSuccess {Number} data.list.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} data.list.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} data.list.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} data.list.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} data.list.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [data.list.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [data.list.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [data.list.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [data.list.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [data.list.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [data.list.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [data.list.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 * 
 *
 *
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 1498466208,
 *         "name": "deleteFile",
 *         "data": {
 *             "list": [
 *                 {
 *                     "fileName": "yuebing_1681977076354.wav",
 *                     "completedTime": 1681977076658,
 *                     "fileCode": "ZwWjxuebTvyQusIRBQCpANsMdYfWGyhTWVkuLBZnXePmVXoKAPmWwMPNXbkvzpRK",
 *                     "ownerId": 20230001,
 *                     "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=ZwWjxuebTvyQusIRBQCpANsMdYfWGyhTWVkuLBZnXePmVXoKAPmWwMPNXbkvzpRK",
 *                     "sha1": "0f6de9dceafff20f2146f3beb88ffdb84ad106db",
 *                     "fileSize": 10248112,
 *                     "domain": "default_domain",
 *                     "expiryTime": 1684569076658,
 *                     "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=ZwWjxuebTvyQusIRBQCpANsMdYfWGyhTWVkuLBZnXePmVXoKAPmWwMPNXbkvzpRK",
 *                     "id": 226243982,
 *                     "lastModified": 1681977076000,
 *                     "fileType": "wav",
 *                     "md5": "354f1fbdb30e5dcce26edaf06d1f5252"
 *                 }
 *             ]
 *         }
 *     }
 * @apiError 400 查找数据时发生错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 * @apiError 404 未找到数据。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/filestorage/operation/delete/
 *     Content-Type: application/json
 *
 *     {
 *         "token": "dbCdxBbfCWAMQprkXroWcLjDWRrtMMsi",
 *         "fileCodeList": [
 *             "ZwWjxuebTvyQusIRBQCpANsMdYfWGyhTWVkuLBZnXePmVXoKAPmWwMPNXbkvzpRK",
 *             "CSoqOXtVMCjScTDoKvsPkQUFbVKCRLSXKKKqOdfaXRcmVXOKACMWJZpnkbXVZpEx"
 *         ]
 *     }
 */



/**
 * @api {post} /filestorage/file/ 上传文件数据
 * @apiName 上传文件数据
 * @apiGroup File_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 以原始二进制请求体上传文件。将令牌和文件名放入查询参数，文件名中的中文、空格等字符应进行 URL 编码；不要使用 multipart/form-data 或将文件内容包装为 JSON。
 *                 <p>默认响应从 <code>data.fileCode</code> 读取文件码；后续音频、心理学、视觉或知识库接口都使用该值。提供 <code>filesize</code> 时须与实际字节数一致，<code>modified</code> 使用 Unix 毫秒时间戳。</p>
 *                 <p>下方 curl 示例读取当前目录的 <code>sample.txt</code>。使用 Windows PowerShell 时将命令名写为 <code>curl.exe</code>，以调用 curl 程序。</p>
 *
 * @apiQuery {String} token 有效账号的令牌。
 * @apiQuery {String} filename 文件名。
 * @apiQuery {Number} [filesize] 文件大小，单位：字节。可选参数。
 * @apiQuery {Number} [modified] 文件最后修改时间。
 *
 * @apiSuccess {Number} sn 本次请求的流水号。
 * @apiSuccess {String} name 本次请求的名称。
 * @apiSuccess {Object} data 数据。
 * @apiSuccess {String} data.fileName 文件名。
 * @apiSuccess {Number} data.fileSize 上传文件的大小，单位：字节。
 * @apiSuccess {String} data.fileCode 文件码。
 * @apiSuccess {Number} data.lastModified 文件最后修改时间。
 * @apiSuccess {Number} data.position 已写入服务器的文件数据位置，单位：字节。
 *
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 2114771911,
 *         "name": "uploadFile",
 *         "data": {
 *             "fileName": "yuebing.wav",
 *             "fileSize": 10248112,
 *             "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHyoznXRPmiKBXapMJJZcNkOXiZpEx",
 *             "lastModified": 1682835314622,
 *             "position": 10248112
 *         }
 *     }
 *
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 * @apiError 404 未找到令牌。
 * @apiError 406 令牌无效。
 * @apiError 503 接收数据错误。
 *
 * @apiParamExample {bash} 上传本地文件（curl）
 *     curl --request POST "http://211.157.179.37:7010/filestorage/file/?token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&filename=sample.txt" --header "Content-Type: application/octet-stream" --data-binary "@sample.txt"
 */


/**
 * @api {get} /filestorage/file/ 下载文件数据
 * @apiName 下载文件数据
 * @apiGroup File_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 使用文件码 <code>fc</code> 和令牌获取文件内容。成功响应为文件本身的二进制数据，客户端应保存或按文件类型展示。
 *                 <p><code>type</code> 用于指定响应 MIME 类型，例如 <code>txt</code>、<code>jpg</code>；省略时自动判断。使用 curl 时通过 <code>--output</code> 保存文件，Windows PowerShell 中使用 <code>curl.exe</code>。</p>
 *
 * @apiQuery {String} token 有效账号的令牌。
 * @apiQuery {String} fc 指定文件码。
 * @apiQuery {String} [type] 指定文件类型，例如：<code>jpg</code> 。通过指定文件类型，服务器设置对应的 MIME 头数据。如果不指定则服务器自行判断。
 * 
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 * @apiError 404 未找到文件。
 * @apiError 406 令牌无效。
 * @apiError 503 接收数据错误。
 *
 * @apiParamExample {bash} 下载并保存文件（curl）
 *     curl "http://211.157.179.37:7010/filestorage/file/?token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&fc=UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx&type=mp3" --output "consultation_01.mp3"
 *
 * @apiSuccessExample {http} 音频文件响应（下方为内容说明）
 *     HTTP/1.1 200 OK
 *     Content-Type: audio/mpeg
 *
 *     <consultation_01.mp3 的二进制数据>
 */

/**
 * @api {post} /filestorage/operation/modify/ 修改文件的上下文数据
 * @apiName 修改文件的上下文数据
 * @apiGroup File_Processing
 * @apiVersion 1.0.0
 * 
 * @apiDescription 为文件保存业务元数据，例如场景、标签或业务记录 ID。必须同时提供 <code>fileCode</code> 和对象类型的 <code>context</code>。
 *                 <p><code>context</code> 整体替换旧内容，更新时须提交需要保留的所有键；提交空对象 <code>{}</code> 清空上下文。默认从 <code>data.context</code> 读取结果。</p>
 *
 * @apiHeader {String} [x-baize-api-version] 响应外壳版本。设置为 <code>v1</code> 时直接返回文件标签数据；
 *                                          不设置或设置为其它值时返回 <code>{sn,name,data}</code> 外壳。默认：<code>v0</code>。
 * @apiHeader {String} [x-baize-api-token] 授权令牌。请求体中未提供 <code>token</code> 时使用。
 * 
 * @apiHeader {String} [x-baize-api-version] 省略时返回 {sn,name,data}；设为 v1 时直接返回 data 对象。
 * @apiHeader {String} [x-baize-api-token] 可代替请求体中的 token；两处都提供时优先使用请求体。
 *
 * @apiBody {String} [token] 有效账号的令牌；与请求头 x-baize-api-token 至少提供一处。
 * @apiBody {String} fileCode 待修改文件的文件码。
 * @apiBody {Object} context 业务自定义键值对象，键为字符串，值可为任意 JSON 值，无固定子字段。例如 {"scene":"inspection","tags":["fire"]}。整体替换原有内容；{} 清空上下文。
 * 
 *
 * @apiParamExample {json} 请求示例
 *     {
 *         "token": "UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *         "context": {
 *             "scene": "inspection",
 *             "tags": [
 *                 "fire"
 *             ]
 *         }
 *     }
 * @apiSuccess {Number} sn 本次请求的流水号。
 * @apiSuccess {String} name 本次请求的名称。
 * @apiSuccess {Object} data 修改后的文件标签数据。
 * @apiSuccess {Number} data.id 文件标签 ID。
 * @apiSuccess {String} data.domain 文件所属域。
 * @apiSuccess {String} data.fileCode 上传完成的文件码，后续文件接口使用该值。
 * @apiSuccess {Number} data.ownerId 文件所有者的触点 ID。
 * @apiSuccess {String} data.fileName 原始文件名。
 * @apiSuccess {Number} data.fileSize 文件大小，单位：字节。
 * @apiSuccess {Number} data.lastModified 原文件最后修改时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} data.completedTime 上传完成时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} data.expiryTime 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} data.fileType 文件类型的扩展名，如 jpg、mp3、pdf。
 * @apiSuccess {String} [data.md5] 文件内容的 MD5 摘要。
 * @apiSuccess {String} [data.sha1] 文件内容的 SHA-1 摘要。
 * @apiSuccess {String} [data.fileURL] HTTP 文件访问地址。
 * @apiSuccess {String} [data.fileSecureURL] HTTPS 文件访问地址。
 * @apiSuccess {String} [data.directURL] 存在时返回的文件直连地址，精简表示可省略。
 * @apiSuccess {String} [data.externalURL] 文件的外部来源地址。
 * @apiSuccess {Object} [data.context] 业务自定义键值对象，键为字符串，值可为任意 JSON 值；无固定子字段，例如 {"scene":"inspection"}。
 * 
 * @apiError 400 缺少 <code>fileCode</code> 或 <code>context</code> 。
 * @apiError 403 未提供令牌。
 * @apiError 404 文件不存在或修改失败。
 *
 * @apiSuccessExample {json} 默认响应：修改后的文件标签
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 1498466209,
 *         "name": "modifyFile",
 *         "data": {
 *             "id": 2237345138,
 *             "ownerId": 22003303,
 *             "domain": "default_domain",
 *             "fileName": "consultation_01.mp3",
 *             "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *             "fileSize": 7702093,
 *             "lastModified": 1774939042000,
 *             "fileType": "mp3",
 *             "completedTime": 1774939043013,
 *             "expiryTime": 3351739043013,
 *             "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=mp3",
 *             "md5": "bbb1288d78a11a06f6dda7a1daea8801",
 *             "sha1": "786cd3aedb41b934960889c1bfbf9158bb8841a0",
 *             "context": {
 *                 "scene": "inspection",
 *                 "tags": [
 *                     "fire"
 *                 ]
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} 清空上下文
 *     {
 *         "token": "UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *         "context": {}
 *     }
 *
 * @apiParamExample {http} v1：通过请求头认证并直接返回文件标签
 *     POST http://211.157.179.37:7010/filestorage/operation/modify/
 *     Content-Type: application/json
 *     x-baize-api-token: UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     x-baize-api-version: v1
 *
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *         "context": {
 *             "scene": "inspection",
 *             "tags": [
 *                 "fire"
 *             ]
 *         }
 *     }
 *
 * @apiSuccessExample {json} v1 响应：直接读取 context
 *     HTTP/1.1 200 OK
 *     {
 *         "id": 2237345138,
 *         "ownerId": 22003303,
 *         "domain": "default_domain",
 *         "fileName": "consultation_01.mp3",
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *         "fileSize": 7702093,
 *         "lastModified": 1774939042000,
 *         "fileType": "mp3",
 *         "completedTime": 1774939043013,
 *         "expiryTime": 3351739043013,
 *         "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&type=mp3",
 *         "md5": "bbb1288d78a11a06f6dda7a1daea8801",
 *         "sha1": "786cd3aedb41b934960889c1bfbf9158bb8841a0",
 *         "context": {
 *             "scene": "inspection",
 *             "tags": [
 *                 "fire"
 *             ]
 *         }
 *     }
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/filestorage/operation/modify/
 *     Content-Type: application/json
 *
 *     {
 *         "token": "UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *         "context": {
 *             "scene": "inspection",
 *             "tags": [
 *                 "fire"
 *             ]
 *         }
 *     }
 */
