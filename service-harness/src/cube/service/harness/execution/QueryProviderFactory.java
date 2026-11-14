package cube.service.harness.execution;

import java.util.Collection;

/** Trusted deployment extension for catalog-backed batches of providers.
 *
 * Each returned provider transfers to the host, which validates and closes it.
 * A factory owns only shared resources; close must not close returned providers.
 * If create fails before returning, the factory must clean up unpublished instances.
 */
public interface QueryProviderFactory extends AutoCloseable {
    Collection<QueryProvider> create() throws Exception;
    @Override void close();
}
