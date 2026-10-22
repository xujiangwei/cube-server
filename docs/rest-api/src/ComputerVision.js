/**
 * @apiDefine ComputerVision 视觉服务
 */


/**
 * @api {post} /cv/barcode/make/:token 生成条形码
 * @apiName 生成条形码
 * @apiGroup ComputerVision
 * @apiVersion 1.0.0
 *
 * @apiDescription 生成条形码。该接口可批量生成条形码，可以将条形码按照打印格式合并生成 PDF 文件以便进行打印。
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
 *             "fileSecureURL": "https://localhost:7017/filestorage/file/?fc=zCLNvLyOqDrIZUPKhLUuHwRZYWAohVGviGFPeFuPuJPWqQGpcJfPRRxYfGEeypZI&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *             "fileURL": "http://localhost:7010/filestorage/file/?fc=zCLNvLyOqDrIZUPKhLUuHwRZYWAohVGviGFPeFuPuJPWqQGpcJfPRRxYfGEeypZI&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *             "sha1": "4705501c60616a897d96293f41ebfabab5087385",
 *             "md5": "439cf383e7a044893df7a4defb668f43"
 *         }]
 *     }
 */


/**
 * @api {post} /cv/barcode/detect/:token 检测并解码条形码
 * @apiName 检测并解码条形码
 * @apiGroup ComputerVision
 * @apiVersion 1.0.0
 *
 * @apiDescription 检测并解码图像文件里的条形码。该接口支持<code>JPEG</code>、<code>PNG</code>、<code>BMP</code>等图像格式，该接口单次最多检测<code>10</code>张图片。
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
 * @apiSuccess {Array} result.barcodes 文件中检测到的条形码信息。如果未能检测到条形码，该数组长度为<code>0</code>。
 * @apiSuccess {String} result.barcodes.data 条形码的解码数据。
 * @apiSuccess {String} result.barcodes.type 条形码的类型。
 * @apiSuccess {Object} result.barcodes.rect 条形码在图像里的空间位置信息。
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
 *                     "fileSecureURL": "https://localhost:7017/filestorage/file/?fc=GrzovpqsuCvCHatmTRmoPMvlSKqyfJCpkcFvKVcbcLxIWOevIfLLVxRmvwGwgxFW&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *                     "sha1": "c4082319bce7e1e9aba18ae9a3f640f737952c6b",
 *                     "fileSize": 3233993,
 *                     "domain": "default_domain",
 *                     "expiryTime": 3313472298016,
 *                     "fileURL": "http://localhost:7010/filestorage/file/?fc=GrzovpqsuCvCHatmTRmoPMvlSKqyfJCpkcFvKVcbcLxIWOevIfLLVxRmvwGwgxFW&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
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
 *                     "fileSecureURL": "https://localhost:7017/filestorage/file/?fc=ssrruxWMYphCHatmTRmoPMvlSKqyfJCnQEdpwpqdizfykWmzmNHDDVVIVCOyKxNg&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *                     "sha1": "8035318ba3b38be05286e0f5e56ec7e199e33e0e",
 *                     "fileSize": 147723,
 *                     "domain": "default_domain",
 *                     "expiryTime": 3309474504334,
 *                     "fileURL": "http://localhost:7010/filestorage/file/?fc=ssrruxWMYphCHatmTRmoPMvlSKqyfJCnQEdpwpqdizfykWmzmNHDDVVIVCOyKxNg&token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
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
 */
