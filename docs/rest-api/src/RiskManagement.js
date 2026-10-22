/**
 * @apiDefine Risk_Management 风险管理
 */

/**
 * @api {get} /riskmgmt/contact/risk/:token 查询用户风险控制数据
 * @apiName 查询用户风险控制数据
 * @apiGroup Risk_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 按照用户的触点ID查询用户的风险控制参数。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {Number} cid 待查询用户的触点ID 。
 * 
 * @apiSuccess {Number} contactId 触点ID 。
 * @apiSuccess {Number} mask 触点风险控制掩码。
 * @apiSuccess {Boolean} forbidAll 是否阻止该触点的所有行为。
 * @apiSuccess {Boolean} forbidSignIn 是否阻止该触点签入行为。
 * @apiSuccess {Boolean} forbidSaveFile 是否阻止该触点保存文件行为。
 * @apiSuccess {Boolean} forbidDestroyFile 是否阻止该触点销毁文件行为。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "contactId": 22003303,
 *         "mask": 0,
 *         "forbidAll": false,
 *         "forbidSignIn": false,
 *         "forbidSaveFile": false,
 *         "forbidDestroyFile": false
 *     }
 * 
 * @apiError 400 查询数据发生错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 */


/**
 * @api {post} /riskmgmt/contact/risk/:token 修改用户风险控制数据
 * @apiName 修改用户风险控制数据
 * @apiGroup Risk_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 修改用户的风险控制参数。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Number} contactId 用户的触点ID 。
 * @apiBody {Number} originalMask 用户的原控制掩码。
 * @apiBody {Boolean} [forbidAll] 是否阻止该触点的所有行为。
 * @apiBody {Boolean} [forbidSignIn] 是否阻止该触点签入行为。
 * @apiBody {Boolean} [forbidSaveFile] 是否阻止该触点保存文件行为。
 * @apiBody {Boolean} [forbidDestroyFile] 是否阻止该触点销毁文件行为。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "contactId": 22003303,
 *         "originalMask": 0,
 *         "forbidSignIn": true
 *     }
 * 
 * @apiSuccess {Number} contactId 用户的触点ID 。
 * @apiSuccess {Number} mask 触点风险控制掩码。
 * @apiSuccess {Boolean} forbidAll 是否阻止该触点的所有行为。
 * @apiSuccess {Boolean} forbidSignIn 是否阻止该触点签入行为。
 * @apiSuccess {Boolean} forbidSaveFile 是否阻止该触点保存文件行为。
 * @apiSuccess {Boolean} forbidDestroyFile 是否阻止该触点销毁文件行为。
 * 
 * @apiSuccessExample {json} 应答示例（修改后的风险控制数据）
 *     HTTP/1.1 200 OK
 *     {
 *         "contactId": 22003303,
 *         "mask": 2,
 *         "forbidAll": false,
 *         "forbidSignIn": true,
 *         "forbidSaveFile": false,
 *         "forbidDestroyFile": false
 *     }
 * 
 * @apiError 400 服务器错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 * @apiError 404 设置参数时发生错误。
 */
