/**
 * @apiDefine HarnessResourceAdmin
 * @apiUse HarnessAuth
 * @apiDescription 通用业务资源管理面。管理权限由 BZ 独立白名单授予，上传的 policy 不能授予管理权限。所有 POST/PUT 要求 Idempotency-Key；同键同请求重放，冲突为 409。请求体最多 262144 字节，复用现有包仓库及策略绑定。
 * @apiError (403) {String} errorCode ADMIN_FORBIDDEN、ADMIN_SIGNER_FORBIDDEN、ADMIN_PERMISSION_FORBIDDEN 或 DATA_SOURCE_FORBIDDEN。
 * @apiError (409) {String} errorCode IDEMPOTENCY_CONFLICT、VERSION_CONFLICT 或不可变版本冲突。
 */

/**
 * @api {post} /harness/v1/admin/packages/validate 校验业务资源包
 * @apiName HarnessValidatePackage
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiUse HarnessResourceAdmin
 * @apiHeader {String} Content-Type application/zip。
 * @apiHeader {String} Idempotency-Key 请求标识。
 * @apiDescription 要求 admin.release.subjects。正文为原始 ZIP；验证 manifest、摘要、依赖声明及已注册 Provider 的连接器/分析配置，不发布或激活。校验成功不证明依赖或 UNIT 在线。
 * @apiSuccess {String} digest 规范化 manifest 的 SHA-256；用于签名，不是整个 ZIP 的摘要。
 * @apiSuccess {Object} manifest 已验证的原 manifest。
 * @apiExample {curl} 校验本地包
 *     curl.exe -X POST http://211.157.179.37:7010/harness/v1/admin/packages/validate -H "Authorization: Bearer <Cube Token>" -H "Idempotency-Key: validate-001" -H "Content-Type: application/zip" --data-binary "@fhe-patrol-v1.zip"
 */

/**
 * @api {post} /harness/v1/admin/releases 发布签名业务资源包
 * @apiName HarnessPublishRelease
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiUse HarnessResourceAdmin
 * @apiHeader {String} Content-Type application/zip。
 * @apiHeader {String} Idempotency-Key 请求标识。
 * @apiHeader {String} X-Harness-Key-Id BZ 为本租户批准的签名公钥标识。
 * @apiHeader {String} X-Harness-Signature Base64 编码的 SHA256withRSA 签名；签名原文是校验返回的小写 64 字符 digest 的 ASCII 字节。
 * @apiDescription 要求 admin.release.subjects。发送和校验时相同的 ZIP。远程作者只可发布资源包，不能上传 executable 工具安装 Java/Python 插件。依赖必须在本租户已发布且 ready。发布后还需发布 policy 并 CAS 绑定，才对新任务生效。
 * @apiSuccess (201) {String} digest 固定的发布摘要。
 * @apiSuccess (201) {String} state 发布状态，成功为 ready。
 * @apiExample {curl} 发布包
 *     curl.exe -X POST http://211.157.179.37:7010/harness/v1/admin/releases -H "Authorization: Bearer <Cube Token>" -H "Idempotency-Key: publish-001" -H "Content-Type: application/zip" -H "X-Harness-Key-Id: f-author" -H "X-Harness-Signature: <Base64 signature>" --data-binary "@fhe-patrol-v1.zip"
 */

/**
 * @api {get} /harness/v1/admin/releases/:digest 查询业务资源发布
 * @apiName HarnessReadRelease
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiUse HarnessResourceAdmin
 * @apiParam {String} digest 实际发布摘要。
 * @apiDescription 要求 admin.release.subjects。只读本租户的发布元数据、manifest_json、provenance_json 和生命周期；不返回私有凭据。JSON 后缀字段为序列化 JSON 字符串。
 * @apiSuccess {String} digest 发布摘要。
 * @apiSuccess {String} state 当前发布状态。
 */

/**
 * @api {post} /harness/v1/admin/policies 发布租户业务策略
 * @apiName HarnessPublishPolicy
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiUse HarnessResourceAdmin
 * @apiHeader {String} Idempotency-Key 请求标识。
 * @apiDescription 要求 admin.policy.subjects。正文为现有 kind=policy 的 tenant-policy JSON，包含 schemaVersion、id、version、skillReleases、permissions、businessLimits。权限不能超过 BZ execution.permissions 上限；服务器 model/runtime 不由此发布。新版本不可变，发布不自动绑定。
 * @apiBody {Number} schemaVersion 固定为 1。
 * @apiBody {String} kind 固定为 policy。
 * @apiBody {String} id 固定为 default。
 * @apiBody {String} version 新的不可变版本标识。
 * @apiBody {String[]} skillReleases 业务包和所需通用依赖的实际发布摘要。
 * @apiBody {String[]} permissions 部署上限内的业务权限。
 * @apiBody {Object} businessLimits maxActiveRuns、maxLifetimeMillis。
 * @apiSuccess (201) {String} kind policy。
 * @apiSuccess (201) {String} version 已发布的版本。
 */

/**
 * @api {get} /harness/v1/admin/bindings/policy/default 查询当前业务策略绑定
 * @apiName HarnessReadPolicyBinding
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiUse HarnessResourceAdmin
 * @apiDescription 要求 admin.policy.subjects。查询得到的 version 用于下次 CAS；没有绑定时 target=""、version=0。
 * @apiSuccess {String} target 当前策略引用，例如 default@1。
 * @apiSuccess {Number} version 当前绑定版本。
 * @apiSuccessExample {json} 当前绑定
 *     {"target":"default@1","version":1}
 */

/**
 * @api {put} /harness/v1/admin/bindings/policy/default 切换业务策略绑定
 * @apiName HarnessBindPolicy
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiUse HarnessResourceAdmin
 * @apiHeader {String} Idempotency-Key 请求标识。
 * @apiDescription 要求 admin.policy.subjects。CAS 和幂等回执同事务提交。冲突后读取当前版本，使用新幂等键显式决定是否重试。回退同样引用旧版本并执行 CAS；在途 run 保持原快照。
 * @apiBody {String} target 已发布的策略引用。
 * @apiBody {Number} expectedVersion 读取的绑定版本，首次绑定为 0。
 * @apiSuccess {String} target 实际绑定引用。
 * @apiSuccess {Number} version 新的绑定版本。
 * @apiParamExample {json} 切换到新策略
 *     {"target":"default@2","expectedVersion":1}
 */

/**
 * @api {put} /harness/v1/admin/sources/:id/versions/:version 登记数据源版本
 * @apiName HarnessPutSource
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiUse HarnessResourceAdmin
 * @apiHeader {String} Idempotency-Key 请求标识。
 * @apiParam {String} id 数据源标识。
 * @apiParam {String} version 不可变版本。
 * @apiBody {String} baseUrl 运维批准的 HTTP(S) origin，不能含路径、末尾斜杠、用户信息、查询串或 fragment。
 * @apiBody {String} secretRef 运维批准的私有凭据引用；不上传明文密钥。
 * @apiBody {String} identityMapping 固定为 cube-subject-hmac-v1。
 * @apiBody {String[]} allowedPaths 运维批准的固定 GET 路径，最多 16 项。
 * @apiDescription 要求 admin.source.subjects。数据源和 secret 引用受部署配置上限限制；更改字段需新建版本。连接器引用 id@version；登记本身不授予任何对话用户读取权限。
 * @apiSuccess (201) {String} sourceRef 固定数据源引用。
 * @apiSuccess (201) {String} digest 配置摘要。
 * @apiParamExample {json} 获准数据源
 *     {"baseUrl":"https://forest.example.internal","secretRef":"forest-source","identityMapping":"cube-subject-hmac-v1","allowedPaths":["/api/patrol/records","/api/media/content"]}
 */

/**
 * @api {get} /harness/v1/admin/sources/:id/versions/:version 查询数据源版本
 * @apiName HarnessReadSource
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiUse HarnessResourceAdmin
 * @apiParam {String} id 数据源标识。
 * @apiParam {String} version 已登记版本。
 * @apiDescription 要求 admin.source.subjects。只读本租户元数据，返回 secretRef 名称但不返回密钥值。
 * @apiSuccess {String} sourceRef 固定数据源引用。
 * @apiSuccess {String} digest 配置摘要。
 * @apiSuccess {Object} config 已登记配置。
 */

/**
 * @api {post} /harness/v1/admin/source-grants 授权或撤销用户数据源访问
 * @apiName HarnessSourceGrant
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiUse HarnessResourceAdmin
 * @apiHeader {String} Idempotency-Key 请求标识。
 * @apiBody {String} subject 当前租户内的 Cube 联系人标识。
 * @apiBody {String} sourceRef 已登记的 id@version。
 * @apiBody {Boolean} allowed true 授权只读查询/取图，false 撤销。
 * @apiDescription 要求 admin.source-grant.subjects。租户来自认证 token，不接受自报租户。每次访问重新验证此授权及 F 的实际用户权限；已撤权后重放旧授权请求只返回原回执，不恢复权限。
 * @apiSuccess {String} subject 用户标识。
 * @apiSuccess {String} sourceRef 数据源引用。
 * @apiSuccess {Boolean} allowed 此次请求已提交的授权值；旧请求重放不是当前授权查询。
 * @apiParamExample {json} 撤销访问
 *     {"subject":"10001","sourceRef":"source-f@1","allowed":false}
 */
