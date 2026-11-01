/**
 * @apiDefine Risk_Management 风险管理
 */

/**
 * @api {get} /riskmgmt/contact/risk/:token 查询用户风险控制数据
 * @apiName 查询用户风险控制数据
 * @apiGroup Risk_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 使用触点 ID <code>cid</code> 查询用户当前的行为限制，适合在管理页面展示禁用或限制状态。
 *                 <p>使用各 <code>forbid*</code> 布尔字段展示限制项，同时保存原始 <code>mask</code>；修改限制时将它作为 <code>originalMask</code> 提交。</p>
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
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/riskmgmt/contact/risk/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?cid=22003303
 */


/**
 * @api {post} /riskmgmt/contact/risk/:token 修改用户风险控制数据
 * @apiName 修改用户风险控制数据
 * @apiGroup Risk_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 修改用户的签入、文件保存或删除等行为限制。先查询当前数据，把返回的 <code>mask</code> 原样填入 <code>originalMask</code>，再提供本次需要修改的布尔项。
 *                 <p>设置 <code>true</code> 开启限制，<code>false</code> 解除限制；未提供的项保留原控制值。若直接提交 <code>mask</code>，它会作为完整目标值优先处理。成功后以返回的布尔字段刷新界面，并保存新的 mask。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Number} contactId 用户的触点ID 。
 * @apiBody {Number} originalMask 刚从查询接口取得的原控制掩码；不要固定填写 0。
 * @apiBody {Number} [mask] 直接指定完整目标控制值；提供时优先于 forbid* 布尔参数。
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
 *
 * @apiParamExample {json} 解除签入限制（刚查询到 mask=2）
 *     {
 *         "contactId": 22003303,
 *         "originalMask": 2,
 *         "forbidSignIn": false
 *     }
 *
 * @apiSuccessExample {json} 解除后的结果
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
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/riskmgmt/contact/risk/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "contactId": 22003303,
 *         "originalMask": 0,
 *         "forbidSignIn": true
 *     }
 */
