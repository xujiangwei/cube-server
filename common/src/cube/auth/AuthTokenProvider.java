package cube.auth;

/** Cube 授权服务提供的令牌查询接口；调用方须检查有效期和用户绑定。 */
public interface AuthTokenProvider {
    AuthToken getToken(String code);
}
