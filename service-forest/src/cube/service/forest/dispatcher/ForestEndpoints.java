package cube.service.forest.dispatcher;

import cube.dispatcher.aigc.spi.DispatcherExtension;
import cube.service.forest.dispatcher.handler.ForestHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import java.util.*;

public final class ForestEndpoints implements DispatcherExtension {
    public String getName() { return "forest"; }
    public List<String> getRestPrefixes() { return Collections.singletonList("/aigc/forest"); }
    public List<ContextHandler> getEndpointHandlers() {
        return Arrays.asList(new ForestHandler(true), new ForestHandler(false));
    }
}
