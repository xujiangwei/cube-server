package cube.service.harness.execution;

import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Deployment-owned registry. Neither manifests nor model arguments choose a Java class/config. */
final class QueryProviders implements AutoCloseable {
  private final Map<String, QueryProvider> providers = new HashMap<>();

  QueryProviders(Properties config) throws Exception {
    try {
      if (config.containsKey("broker.type"))
        throw new IllegalArgumentException("USE_EXPLICIT_QUERY_PROVIDER_REGISTRATION");
      if ("true".equals(config.getProperty("broker.mock.enabled")))
        add(new MockQueryProvider(config));
      for (String id : config.getProperty("broker.providers", "").split(",")) {
        if (id.isEmpty()) continue;
        RuntimeSupport.id(id);
        String prefix = "broker.provider." + id + ".";
        Path path = Paths.get(config.getProperty(prefix + "config", ""));
        if (!path.isAbsolute())
          throw new IllegalArgumentException("PROVIDER_ABSOLUTE_CONFIG_REQUIRED");
        Properties privateConfig = new Properties();
        try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
          privateConfig.load(in);
        }
        Class<?> type = Class.forName(config.getProperty(prefix + "class", ""));
        if (!QueryProvider.class.isAssignableFrom(type))
          throw new IllegalArgumentException("QUERY_PROVIDER_TYPE_INVALID");
        add((QueryProvider) type.getConstructor(Properties.class).newInstance(privateConfig));
      }
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  private void add(QueryProvider provider) {
    try {
      String operation = RuntimeSupport.id(provider.operation());
      RuntimeSupport.id(provider.permission());
      RuntimeSupport.toolSchema(provider.inputSchema());
      RuntimeSupport.toolSchema(provider.resultSchema());
      if (providers.containsKey(operation))
        throw new IllegalArgumentException("DUPLICATE_QUERY_PROVIDER");
      providers.put(operation, provider);
    } catch (RuntimeException e) {
      provider.close();
      throw e;
    }
  }

  QueryProvider get(String operation) {
    QueryProvider provider = providers.get(operation);
    if (provider == null) throw new StoreException("TOOL_PROVIDER_UNAVAILABLE");
    return provider;
  }

  @Override
  public void close() {
    for (QueryProvider provider : providers.values()) provider.close();
  }
}
