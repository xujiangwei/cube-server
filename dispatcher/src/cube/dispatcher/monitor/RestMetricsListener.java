package cube.dispatcher.monitor;

import cube.common.action.HarnessAction;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;

/** Connector-level observation includes async completion, failed responses and unhandled routes. */
public final class RestMetricsListener implements HttpChannel.Listener {
    public static final String MODULE_ATTRIBUTE = "cube.dispatcher.monitor.module";
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "CONNECT", "TRACE"));
    private final String sampleAttribute = RestMetricsListener.class.getName() + ".sample";
    private final RestMetrics metrics;
    private final Supplier<List<ContextHandler>> handlers;
    private volatile List<ContextHandler> catalog = new ArrayList<>();
    private volatile int catalogSize = -1;

    public RestMetricsListener(RestMetrics metrics, Supplier<List<ContextHandler>> handlers) {
        this.metrics = metrics; this.handlers = handlers;
    }

    /** Returns module and a bounded route template; never return a concrete resource ID. */
    public String[] resolve(String path) {
        if (path == null || path.equals("/static") || path.startsWith("/static/")
                || path.equals("/doc") || path.startsWith("/doc/")) return null;
        List<ContextHandler> registered = handlers.get();
        if (registered.size() != catalogSize) {
            List<ContextHandler> next = new ArrayList<>(registered);
            next.removeIf(handler -> handler == null || handler.getContextPath() == null);
            next.sort(Comparator.comparingInt((ContextHandler h) -> h.getContextPath().length()).reversed());
            catalog = next; catalogSize = registered.size();
        }
        for (ContextHandler handler : catalog) {
            String context = handler.getContextPath();
            String prefix = context.endsWith("/") ? context.substring(0, context.length() - 1) : context;
            if ("/".equals(context)) {
                if (!"/".equals(path)) continue;
            } else if (!(path.equals(prefix) || path.startsWith(prefix + "/"))) continue;
            String module = module(handler);
            if ("/v1".equals(prefix)) {
                for (String route : new String[] {"/v1/chat/completions", "/v1/messages", "/v1/models"}) {
                    if (path.equals(route)) return new String[] {module, route};
                }
                if (path.startsWith("/v1/models/") && path.length() > 11 && path.indexOf('/', 11) < 0)
                    return new String[] {module, "/v1/models/{id}"};
                return new String[] {module, "未匹配路由"};
            }
            if ("/harness".equals(prefix)) {
                for (HarnessAction action : HarnessAction.values()) {
                    Matcher match = action.match(path);
                    if (match == null || !match.matches()) continue;
                    String normalized = path;
                    for (int i = match.groupCount(); i >= 1; --i) {
                        normalized = normalized.substring(0, match.start(i)) + "{id}" + normalized.substring(match.end(i));
                    }
                    return new String[] {module, normalized};
                }
                return new String[] {module, "未匹配路由"};
            }
            return new String[] {module, context};
        }
        return new String[] {"Other", "未匹配路由"};
    }

    private static String module(ContextHandler handler) {
        Object declared = handler.getAttribute(MODULE_ATTRIBUTE);
        if (declared != null) return declared.toString();
        String type = handler.getClass().getName();
        if (handler.getHandler() != null && !type.startsWith("cube.dispatcher.")) type = handler.getHandler().getClass().getName();
        String[] packages = {"aigc", "harness", "auth", "contact", "messaging", "filestorage", "fileprocessor",
                "cv", "conference", "multipointcomm", "riskmgmt", "hub", "ferry", "signal", "robodroid", "psychology"};
        String[] names = {"AIGC", "Harness", "Auth", "Contact", "Messaging", "FileStorage", "FileProcessor",
                "CV", "Conference", "MultipointComm", "RiskMgmt", "Hub", "Ferry", "Signal", "Robodroid", "Psychology"};
        for (int i = 0; i < packages.length; ++i) if (type.startsWith("cube.dispatcher." + packages[i] + ".")) return names[i];
        return "Other";
    }

    private RestMetrics.Sample sample(Request request) { return (RestMetrics.Sample) request.getAttribute(sampleAttribute); }

    @Override public void onRequestBegin(Request request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return;
        String[] route = resolve(request.getRequestURI());
        if (route == null) return;
        String method = request.getMethod();
        if (!METHODS.contains(method)) method = "OTHER";
        request.setAttribute(sampleAttribute, metrics.begin(route[0], route[1], method));
    }
    @Override public void onResponseContent(Request request, ByteBuffer content) {
        if (content.hasRemaining()) metrics.firstByte(sample(request));
    }
    @Override public void onComplete(Request request) {
        String type = request.getResponse().getContentType();
        metrics.complete(sample(request), request.getResponse().getStatus(),
                type != null && "text/event-stream".equalsIgnoreCase(type.split(";", 2)[0].trim()));
        request.removeAttribute(sampleAttribute);
    }
    @Override public void onRequestFailure(Request request, Throwable failure) { metrics.failure(sample(request)); }
    @Override public void onResponseFailure(Request request, Throwable failure) { metrics.failure(sample(request)); }
    @Override public void onDispatchFailure(Request request, Throwable failure) { metrics.failure(sample(request)); }
    @Override public void onBeforeDispatch(Request request) { }
    @Override public void onAfterDispatch(Request request) { }
    @Override public void onRequestContent(Request request, ByteBuffer content) { }
    @Override public void onRequestContentEnd(Request request) { }
    @Override public void onRequestTrailers(Request request) { }
    @Override public void onRequestEnd(Request request) { }
    @Override public void onResponseBegin(Request request) { }
    @Override public void onResponseCommit(Request request) { }
    @Override public void onResponseEnd(Request request) { }
}
