package cube.service.harness.execution;

import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 部署侧持有的查询提供方注册表。
 *
 * <p>关键设计点：
 * <ul>
 *   <li>注册来源只有部署端私有配置：模型参数与发布清单均不能选择 Java 类或配置文件；</li>
 *   <li>{@code broker.mock.enabled=true} 时额外登记 loopback MockConsole 测试提供方，
 *       仅用于离线协议回归，不承载业务；</li>
 *   <li>{@code broker.providers} 逐个声明 provider 短标识，私有配置必须是绝对路径
 *       （避免相对工作目录歧义），类必须实现 {@link QueryProvider}；</li>
 *   <li>operation 重复注册、标识或 schema 非法均拒绝启动；构造中途失败会先关闭已登记的
 *       提供方再向上抛出，避免半初始化状态；</li>
 *   <li>未配置任何提供方时服务可正常启动，相关调用在 {@link #get} 处以
 *       {@code TOOL_PROVIDER_UNAVAILABLE} 拒绝。</li>
 * </ul>
 */
final class QueryProviders implements AutoCloseable {
    /**
     * operation 到提供方实例的映射；仅在构造期写入，之后只读。
     */
    private final Map<String, QueryProvider> providers = new HashMap<>();
    private final java.util.List<QueryProviderFactory> factories = new java.util.ArrayList<>();

    /**
     * 从部署端私有配置构造注册表。
     *
     * <p>依次处理 MockConsole 开关与 {@code broker.providers} 列表：校验标识格式、
     * 读取私有配置、加载并实例化提供方类，最后统一登记。
     *
     * @param config 部署端私有配置（harness.properties），读取 broker.mock/broker.providers 等键
     * @throws Exception                配置缺失、类加载或实例化失败时向上传播
     * @throws IllegalArgumentException 出现以下配置错误时抛出：
     *                                  {@code USE_EXPLICIT_QUERY_PROVIDER_REGISTRATION}
     *                                  （遗留的 broker.type 配置，明确拒绝）、
     *                                  {@code PROVIDER_ABSOLUTE_CONFIG_REQUIRED}
     *                                  （私有配置不是绝对路径）、
     *                                  {@code QUERY_PROVIDER_TYPE_INVALID}
     *                                  （配置的类未实现 QueryProvider）、
     *                                  {@code DUPLICATE_QUERY_PROVIDER}（operation 重复）
     */
    QueryProviders(Properties config) throws Exception {
        try {
            if (config.containsKey("broker.type")) {
                throw new IllegalArgumentException("USE_EXPLICIT_QUERY_PROVIDER_REGISTRATION");
            }
            if ("true".equals(config.getProperty("broker.mock.enabled"))) {
                add(new MockQueryProvider(config));
            }
            for (String id : config.getProperty("broker.providers", "").split(",")) {
                if (id.isEmpty()) {
                    continue;
                }
                RuntimeSupport.id(id);
                String prefix = "broker.provider." + id + ".";
                Path path = Paths.get(config.getProperty(prefix + "config", ""));
                if (!path.isAbsolute()) {
                    throw new IllegalArgumentException("PROVIDER_ABSOLUTE_CONFIG_REQUIRED");
                }
                Properties privateConfig = new Properties();
                try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    privateConfig.load(in);
                }
                Class<?> type = Class.forName(config.getProperty(prefix + "class", ""));
                if (QueryProviderFactory.class.isAssignableFrom(type)) {
                    QueryProviderFactory factory = (QueryProviderFactory) type.getConstructor(Properties.class).newInstance(privateConfig);
                    factories.add(factory);
                    java.util.Collection<QueryProvider> created = factory.create();
                    if (created == null) throw new IllegalArgumentException("QUERY_PROVIDER_FACTORY_INVALID");
                    java.util.List<QueryProvider> pending = new java.util.ArrayList<>(created);
                    int consumed = 0;
                    RuntimeException registrationFailure = null;
                    try {
                        if (pending.isEmpty() || pending.size() > 4096 || pending.contains(null))
                            throw new IllegalArgumentException("QUERY_PROVIDER_FACTORY_INVALID");
                        for (QueryProvider provider : pending) {
                            // add closes a rejected provider, so transfer ownership first.
                            consumed++;
                            add(provider);
                        }
                    } catch (RuntimeException failure) {
                        registrationFailure = failure;
                        throw failure;
                    } finally {
                        RuntimeException cleanupFailure = null;
                        for (int i = consumed; i < pending.size(); i++) if (pending.get(i) != null) {
                            try { pending.get(i).close(); }
                            catch (RuntimeException failure) {
                                if (cleanupFailure == null) cleanupFailure = failure; else cleanupFailure.addSuppressed(failure);
                            }
                        }
                        if (cleanupFailure != null) {
                            if (registrationFailure != null) registrationFailure.addSuppressed(cleanupFailure); else throw cleanupFailure;
                        }
                    }
                } else if (QueryProvider.class.isAssignableFrom(type)) {
                    add((QueryProvider) type.getConstructor(Properties.class).newInstance(privateConfig));
                } else {
                    throw new IllegalArgumentException("QUERY_PROVIDER_TYPE_INVALID");
                }
            }
        } catch (Exception e) {
            try { close(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    /**
     * 校验并登记一个提供方实例。
     *
     * <p>校验 operation/permission 标识格式与输入/输出 schema 合法性后按 operation 入库；
     * 任一校验失败均关闭该实例并向上抛出，避免注册表持有不可用提供方。
     *
     * @param provider 待登记的提供方实例
     * @throws IllegalArgumentException operation 重复时抛出，错误码为
     *                                  {@code DUPLICATE_QUERY_PROVIDER}；标识或 schema
     *                                  非法时由 RuntimeSupport 校验抛出
     */
    private void add(QueryProvider provider) {
        try {
            String operation = RuntimeSupport.id(provider.operation());
            RuntimeSupport.id(provider.permission());
            RuntimeSupport.toolSchema(provider.inputSchema());
            RuntimeSupport.toolSchema(provider.resultSchema());
            if (providers.containsKey(operation)) {
                throw new IllegalArgumentException("DUPLICATE_QUERY_PROVIDER");
            }
            providers.put(operation, provider);
        } catch (RuntimeException e) {
            try { provider.close(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    /**
     * 按 operation 获取已注册的提供方。
     *
     * @param operation 工具声明中的 brokerOperation 标识
     * @return 已注册的提供方实例
     * @throws StoreException 未注册该 operation 时抛出，错误码为 {@code TOOL_PROVIDER_UNAVAILABLE}
     */
    QueryProvider get(String operation) {
        QueryProvider provider = providers.get(operation);
        if (provider == null) {
            throw new StoreException("TOOL_PROVIDER_UNAVAILABLE");
        }
        return provider;
    }
    java.util.Collection<QueryProvider> all() { return java.util.Collections.unmodifiableCollection(providers.values()); }

    /**
     * 关闭全部已注册提供方，释放各自持有的上游资源；重复调用安全。
     */
    @Override
    public void close() {
        RuntimeException failure = null;
        for (QueryProvider provider : providers.values()) {
            try { provider.close(); }
            catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        providers.clear();
        for (QueryProviderFactory factory : factories) {
            try { factory.close(); }
            catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        factories.clear();
        if (failure != null) throw failure;
    }
}
