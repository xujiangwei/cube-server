/**
 * @apiDefine File_Processing 文件处理
 */


/**
 * @api {post} /filestorage/operation/list/ 批量获取文件清单
 * @apiName 批量获取文件清单
 * @apiGroup File_Processing
 * @apiVersion 1.0.0
 * 
 * @apiDescription 根据指定的清单索引信息，以分页方式批量获取文件数据列表。此接口以文件上传时的时间倒序返回文件标签数据，且每次最多返回10条数据。
 * 
 * @apiBody {String} token 有效账号的令牌。
 * @apiBody {Number} [begin] 指定查询位置的起始索引。默认值：<code>0</code>。
 * @apiBody {Number} [end] 指定查询位置的结束索引。默认值：<code>9</code>。
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
 * @apiSuccess {Array} data.list 文件数据清单，每个元素为一个文件标签对象。
 * @apiSuccess {Number} data.begin 文件清单数据的起始索引。
 * @apiSuccess {Number} data.end 文件清单数据的结束索引。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 896144295,
 *         "name": "listFileLabels",
 *         "data": {
 *             "ownerId": 22003303,
 *             "domain": "default_domain",
 *             "total": 4,
 *             "begin": 0,
 *             "end": 9,
 *             "list": [
 *                 {
 *                     "fileName": "新闻.docx",
 *                     "completedTime": 1704861088556,
 *                     "fileCode": "XUvHsheBTvyQusIRBQCpANdxojdHtLHgWvxhYBZAkECKgizIlpZwwZCNxbKvmCeK",
 *                     "ownerId": 22003303,
 *                     "fileSecureURL": "https://127.0.0.1:7017/filestorage/file/?fc=XUvHsheBTvyQusIRBQCpANdxojdHtLHgWvxhYBZAkECKgizIlpZwwZCNxbKvmCeK&type=docx",
 *                     "sha1": "5acd0a81b871b71408b1fedd0e545c891340c8a1",
 *                     "fileSize": 51642,
 *                     "domain": "default_domain",
 *                     "expiryTime": 1707453088556,
 *                     "fileURL": "http://127.0.0.1:7010/filestorage/file/?fc=XUvHsheBTvyQusIRBQCpANdxojdHtLHgWvxhYBZAkECKgizIlpZwwZCNxbKvmCeK&type=docx",
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
 *                     "fileSecureURL": "https://127.0.0.1:7017/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
 *                     "sha1": "5b208b20aaffbe3e2e12b39d2bbb249838773e1c",
 *                     "fileSize": 6303,
 *                     "domain": "default_domain",
 *                     "expiryTime": 1706534996049,
 *                     "fileURL": "http://127.0.0.1:7010/filestorage/file/?fc=ZZwzeQjuMqAZQLQOHlHwNSelKSYrRkTzKOHKZgfxOXcJYZBzEgyFiSthFOKVmpRk&type=txt",
 *                     "id": 3397137026,
 *                     "lastModified": 1701756454027,
 *                     "fileType": "txt",
 *                     "md5": "8b49315f06e7a312c89eb8028b1c6d2c"
 *                 },
 *                 ...
 *             ]
 *         }
 *     }
 * 
 * @apiError 400 查找数据时发生错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 * @apiError 404 未找到数据。
 */


/**
 * @api {post} /filestorage/operation/find/ 查找已上传的文件
 * @apiName 查找已上传的文件
 * @apiGroup File_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 根据指定的查询条件，查找已经上传到服务器上的文件。
 * 
 * @apiBody {String} token 有效账号的令牌。
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
 * @apiSuccess {Array} data.list 查找到的文件数据清单。
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
 *                     "fileSecureURL": "https://127.0.0.1:7017/filestorage/file/?fc=UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx&type=wav",
 *                     "sha1": "0f6de9dceafff20f2146f3beb88ffdb84ad106db",
 *                     "fileSize": 10248112,
 *                     "domain": "default_domain",
 *                     "expiryTime": 1684205195560,
 *                     "directURL": "http://127.0.0.1:6080/files/UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *                     "fileURL": "http://127.0.0.1:7010/filestorage/file/?fc=UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx&type=wav",
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
 */



/**
 * @api {post} /filestorage/operation/delete/ 删除已上传的文件
 * @apiName 删除已上传的文件
 * @apiGroup File_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 按照文件码或者 MD5 码删除已上传到服务器上的文件。参数<code>fileCodeList</code>与<code>md5List</code>至少指定一个。
 * 
 * @apiBody {String} token 有效账号的令牌。
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
 * @apiSuccess {Array} data.list 已删除的文件数据清单，每个元素为一个文件标签对象。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 1498466208,
 *         "name": "deleteFile",
 *         "data": {
 *             "list": [{
 *                 "fileName": "yuebing_1681977076354.wav",
 *                 "completedTime": 1681977076658,
 *                 "fileCode": "ZwWjxuebTvyQusIRBQCpANsMdYfWGyhTWVkuLBZnXePmVXoKAPmWwMPNXbkvzpRK",
 *                 "ownerId": 20230001,
 *                 "fileSecureURL": "https://127.0.0.1:7017/filestorage/file/?fc=ZwWjxuebTvyQusIRBQCpANsMdYfWGyhTWVkuLBZnXePmVXoKAPmWwMPNXbkvzpRK",
 *                 "sha1": "0f6de9dceafff20f2146f3beb88ffdb84ad106db",
 *                 "fileSize": 10248112,
 *                 "domain": "default_domain",
 *                 "expiryTime": 1684569076658,
 *                 "fileURL": "http://127.0.0.1:7010/filestorage/file/?fc=ZwWjxuebTvyQusIRBQCpANsMdYfWGyhTWVkuLBZnXePmVXoKAPmWwMPNXbkvzpRK",
 *                 "id": 226243982,
 *                 "lastModified": 1681977076000,
 *                 "fileType": "wav",
 *                 "md5": "354f1fbdb30e5dcce26edaf06d1f5252"
 *             }, {
 *                 ...
 *             }]
 *         }
 *     }
 * 
 * @apiError 400 查找数据时发生错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 * @apiError 404 未找到数据。
 */



/**
 * @api {post} /filestorage/file/ 上传文件数据
 * @apiName 上传文件数据
 * @apiGroup File_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 以二进制流方式上传文件数据。
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
 */


/**
 * @api {get} /filestorage/file/ 下载文件数据
 * @apiName 下载文件数据
 * @apiGroup File_Processing
 * @apiVersion 1.0.0
 *
 * @apiDescription 获取指定文件的数据。该接口直接返回文件本身的二进制数据流，应答的 <code>Content-Type</code> 由参数 <code>type</code> 或服务器探测的文件类型决定。
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
 */
