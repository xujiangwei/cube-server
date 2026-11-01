/**
 * @apiDefine ComputerVision 视觉服务
 */


/**
 * @api {post} /cv/barcode/make/:token 生成条形码
 * @apiName 生成条形码
 * @apiGroup ComputerVision
 * @apiVersion 1.0.0
 *
 * @apiDescription 将业务编号等字符串批量生成为条形码文件，可附带上部或下部文字。<code>list[].data</code> 使用字符串，保留编号中的前导零。
 *                 <p>单独生成条码时省略 <code>merge</code>；需要打印时设置 <code>merge=true</code>，配合 <code>paper</code>、<code>layout</code> 与 <code>size</code> 生成合并 PDF。此时只返回合并后的文件，<code>amount</code> 为输出文件数。使用返回的文件标签下载或预览。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Array} list 待生成条形码数据列表。
 * @apiBody {String} list.data 指定条形码数据内容。
 * @apiBody {String} [list.header] 指定条形码图像上部文字描述。
 * @apiBody {String} [list.footer] 指定条形码图像下部文字描述。
 * @apiBody {String} [paper] 指定打印纸张规格。建议值：<code>A4</code> 。
 * @apiBody {Boolean} [merge] 是否将生成的条形码图像合并到一个 PDF 文件中。如果该参数设置为<code>true</code>，则仅返回合并后的 PDF 文件。
 * @apiBody {String} [layout] 条码排列布局形式。默认值：<code>tile</code> 平铺形式。支持 <code>tile</code> 和 <code>single</code>。
 * @apiBody {String} [size] 条码容器大小。默认值：<code>small</code>。支持4种大小：<code>small</code>、<code>normal</code>、<code>large</code>和<code>ultra</code>。
 * 
 * @apiParamExample {json} 生成单个条形码示例
 *     {
 *         "list": [{
 *             "data": "532201608101",
 *             "header": "启航一中",
 *             "footer": "高200101    张伟"
 *         }]
 *     }
 *
 * @apiParamExample {json} 生成多个条形码并合并到PDF文件示例
 *     {
 *         "paper": "A4",
 *         "merge": true,
 *         "list": [{
 *             "data": "532201608101",
 *             "header": "启航一中",
 *             "footer": "高200101    张伟"
 *         }, {
 *             "data": "532201608102",
 *             "header": "启航一中",
 *             "footer": "高200101    吕小布"
 *         }, {
 *             "data": "532201608103",
 *             "header": "启航一中",
 *             "footer": "高200101    乔玲玲"
 *         }]
 *     }
 *
 * @apiSuccess {Number} amount 成功生成文件的数量。
 * @apiSuccess {Array} list 生成的条形码文件列表。
 * 
 * @apiSuccessExample {json} 生成条形码文件应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "amount": 1,
 *         "list": [{
 *             "id": 3259511167,
 *             "ownerId": 22003303,
 *             "domain": "default_domain",
 *             "completedTime": 1736577245039,
 *             "expiryTime": 3313377245039,
 *             "fileCode": "zCLNvLyOqDrIZUPKhLUuHwRZYWAohVGviGFPeFuPuJPWqQGpcJfPRRxYfGEeypZI",
 *             "fileName": "8768590498700517780.pdf",
 *             "fileSize": 12919,
 *             "lastModified": 1736577245000,
 *             "fileType": "pdf",
 *             "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=zCLNvLyOqDrIZUPKhLUuHwRZYWAohVGviGFPeFuPuJPWqQGpcJfPRRxYfGEeypZI&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *             "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=zCLNvLyOqDrIZUPKhLUuHwRZYWAohVGviGFPeFuPuJPWqQGpcJfPRRxYfGEeypZI&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *             "sha1": "4705501c60616a897d96293f41ebfabab5087385",
 *             "md5": "439cf383e7a044893df7a4defb668f43"
 *         }]
 *     }
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/cv/barcode/make/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "list": [
 *             {
 *                 "data": "532201608101",
 *                 "header": "启航一中",
 *                 "footer": "高200101    张伟"
 *             }
 *         ]
 *     }
 */


/**
 * @api {post} /cv/barcode/detect/:token 检测并解码条形码
 * @apiName 检测并解码条形码
 * @apiGroup ComputerVision
 * @apiVersion 1.0.0
 *
 * @apiDescription 识别图片中的条形码并解码内容。先上传图片取得文件码，再按请求字段提交；支持 JPEG、PNG、BMP，单次最多 10 张。
 *                 <p>按返回列表关联输入文件并读取识别内容。一张图片可包含多个条码，应用应遍历识别结果；未识别出条码时按空结果展示，必要时换用更清晰、无遮挡的图片。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Array} list 文件码列表。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "list": [
 *             "GrzovpqsuCvCHatmTRmoPMvlSKqyfJCpkcFvKVcbcLxIWOevIfLLVxRmvwGwgxFW",
 *             "ssrruxWMYphCHatmTRmoPMvlSKqyfJCnQEdpwpqdizfykWmzmNHDDVVIVCOyKxNg"
 *         ]
 *     }
 * 
 * @apiSuccess {Array} result 检测解码结果列表。
 * @apiSuccess {Object} result.file 文件标签数据。
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
 * @apiSuccess {Array} result.barcodes 文件中检测到的条形码信息。如果未能检测到条形码，该数组长度为<code>0</code>。
 * @apiSuccess {String} result.barcodes.data 条形码的解码数据。
 * @apiSuccess {String} result.barcodes.type 条形码的类型。
 * @apiSuccess {Object} result.barcodes.rect 条形码在图像里的空间位置信息。
 * @apiSuccess {Number} result.barcodes.rect.x 条形码矩形左上角横坐标，单位：原图像素。
 * @apiSuccess {Number} result.barcodes.rect.y 条形码矩形左上角纵坐标，单位：原图像素。
 * @apiSuccess {Number} result.barcodes.rect.width 矩形宽度，单位：像素。
 * @apiSuccess {Number} result.barcodes.rect.height 矩形高度，单位：像素。
 * @apiSuccess {Number} result.barcodes.timestamp 该条形码检测解码时的时间戳。
 * @apiSuccess {Number} result.timestamp 该文件检测解码时的时间戳。
 * @apiSuccess {Number} elapsed 本次检测及解码耗时，单位：毫秒。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "result": [
 *             {
 *                 "file": {
 *                     "fileName": "painting_for_barcode_2.jpg",
 *                     "completedTime": 1736672298016,
 *                     "fileCode": "GrzovpqsuCvCHatmTRmoPMvlSKqyfJCpkcFvKVcbcLxIWOevIfLLVxRmvwGwgxFW",
 *                     "ownerId": 22003303,
 *                     "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=GrzovpqsuCvCHatmTRmoPMvlSKqyfJCpkcFvKVcbcLxIWOevIfLLVxRmvwGwgxFW&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *                     "sha1": "c4082319bce7e1e9aba18ae9a3f640f737952c6b",
 *                     "fileSize": 3233993,
 *                     "domain": "default_domain",
 *                     "expiryTime": 3313472298016,
 *                     "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=GrzovpqsuCvCHatmTRmoPMvlSKqyfJCpkcFvKVcbcLxIWOevIfLLVxRmvwGwgxFW&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *                     "id": 2003405512,
 *                     "lastModified": 1736672292515,
 *                     "fileType": "jpg",
 *                     "md5": "53c48575087619e60eef9e6aee48f909"
 *                 },
 *                 "barcodes": [
 *                     {
 *                         "data": "baize-532201-2",
 *                         "type": "CODE128",
 *                         "rect": {
 *                             "x": 1616,
 *                             "width": 920,
 *                             "y": 1464,
 *                             "height": 122
 *                         },
 *                         "timestamp": 1736674735791
 *                     }
 *                 ],
 *                 "timestamp": 1736674735794
 *             }, {
 *                 "file": {
 *                     "fileName": "painting_1127_t.jpg",
 *                     "completedTime": 1732674504334,
 *                     "fileCode": "ssrruxWMYphCHatmTRmoPMvlSKqyfJCnQEdpwpqdizfykWmzmNHDDVVIVCOyKxNg",
 *                     "ownerId": 22003303,
 *                     "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=ssrruxWMYphCHatmTRmoPMvlSKqyfJCnQEdpwpqdizfykWmzmNHDDVVIVCOyKxNg&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *                     "sha1": "8035318ba3b38be05286e0f5e56ec7e199e33e0e",
 *                     "fileSize": 147723,
 *                     "domain": "default_domain",
 *                     "expiryTime": 3309474504334,
 *                     "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=ssrruxWMYphCHatmTRmoPMvlSKqyfJCnQEdpwpqdizfykWmzmNHDDVVIVCOyKxNg&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *                     "id": 3489413796,
 *                     "lastModified": 1732674503773,
 *                     "fileType": "jpg",
 *                     "md5": "e6830354c8c4456ef03090a7430ce45d"
 *                 },
 *                 "barcodes": [],
 *                 "timestamp": 1736674736035
 *             }
 *         ],
 *         "elapsed": 1744
 *     }
 * 
 * @apiError 400 系统故障。
 * @apiError 401 令牌无效。
 * @apiError 403 参数格式错误。
 * @apiError 404 未找到指定文件的数据。
 *
 * @apiSuccessExample {json} 图片未检测出条形码
 *     HTTP/1.1 200 OK
 *     {
 *         "result": [
 *             {
 *                 "file": {
 *                     "fileName": "painting_for_barcode_2.jpg",
 *                     "completedTime": 1736672298016,
 *                     "fileCode": "GrzovpqsuCvCHatmTRmoPMvlSKqyfJCpkcFvKVcbcLxIWOevIfLLVxRmvwGwgxFW",
 *                     "ownerId": 22003303,
 *                     "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/?fc=GrzovpqsuCvCHatmTRmoPMvlSKqyfJCpkcFvKVcbcLxIWOevIfLLVxRmvwGwgxFW&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *                     "sha1": "c4082319bce7e1e9aba18ae9a3f640f737952c6b",
 *                     "fileSize": 3233993,
 *                     "domain": "default_domain",
 *                     "expiryTime": 3313472298016,
 *                     "fileURL": "http://211.157.179.37:7010/filestorage/file/?fc=GrzovpqsuCvCHatmTRmoPMvlSKqyfJCpkcFvKVcbcLxIWOevIfLLVxRmvwGwgxFW&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *                     "id": 2003405512,
 *                     "lastModified": 1736672292515,
 *                     "fileType": "jpg",
 *                     "md5": "53c48575087619e60eef9e6aee48f909"
 *                 },
 *                 "barcodes": [],
 *                 "timestamp": 1736674735794
 *             }
 *         ]
 *     }
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/cv/barcode/detect/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "list": [
 *             "GrzovpqsuCvCHatmTRmoPMvlSKqyfJCpkcFvKVcbcLxIWOevIfLLVxRmvwGwgxFW",
 *             "ssrruxWMYphCHatmTRmoPMvlSKqyfJCnQEdpwpqdizfykWmzmNHDDVVIVCOyKxNg"
 *         ]
 *     }
 */
