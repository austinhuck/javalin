/*
 * Javalin - https://javalin.io
 * Copyright 2017 David Åse
 * Licensed under Apache 2.0: https://github.com/tipsy/javalin/blob/master/LICENSE
 *
 */

package io.javalin;

import io.javalin.apibuilder.ApiBuilder;
import io.javalin.apibuilder.EndpointGroup;
import io.javalin.core.EventManager;
import io.javalin.core.HandlerType;
import io.javalin.core.JavalinServlet;
import io.javalin.core.util.CorsBeforeHandler;
import io.javalin.core.util.CorsOptionsHandler;
import io.javalin.core.util.JettyServerUtil;
import io.javalin.core.util.LogUtil;
import io.javalin.core.util.RouteOverviewRenderer;
import io.javalin.core.util.Util;
import io.javalin.security.AccessManager;
import io.javalin.security.CoreRoles;
import io.javalin.security.Role;
import io.javalin.serversentevent.SseClient;
import io.javalin.serversentevent.SseHandler;
import io.javalin.staticfiles.JettyResourceHandler;
import io.javalin.staticfiles.Location;
import io.javalin.staticfiles.StaticFileConfig;
import io.javalin.websocket.WsEntry;
import io.javalin.websocket.WsHandler;
import io.javalin.websocket.WsPathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static io.javalin.security.SecurityUtil.roles;

public class Javalin {

    private static Logger log = LoggerFactory.getLogger(Javalin.class);
    protected Server jettyServer;
    protected SessionHandler jettySessionHandler;
    protected Consumer<WebSocketServletFactory> wsFactoryConfig = WebSocketServletFactory::getPolicy;
    protected WsPathMatcher wsPathMatcher = new WsPathMatcher();
    protected int port = 7000;
    protected String contextPath = "/";
    protected boolean showStartupBanner = true;
    protected boolean started = false;
    protected EventManager eventManager = new EventManager();
    protected Map<Class, Object> appAttributes = new HashMap<>();
    protected List<HandlerMetaInfo> handlerMetaInfo = new ArrayList<>();
    private JavalinServlet javalinServlet = new JavalinServlet(this);

    protected Javalin(Server jettyServer, SessionHandler jettySessionHandler) {
        this.jettyServer = jettyServer;
        this.jettySessionHandler = jettySessionHandler;
    }

    protected Javalin() {
        this(JettyServerUtil.defaultServer(), JettyServerUtil.defaultSessionHandler());
    }

    /**
     * Creates an instance of the application for further configuration.
     * The server does not run until {@link Javalin#start()} is called.
     *
     * @return instance of application for configuration.
     * @see Javalin#start()
     * @see Javalin#start(int)
     */
    public static Javalin create() {
        JettyServerUtil.INSTANCE.printHelpfulMessageIfNoServerHasBeenStartedAfterOneSecond();
        return new Javalin();
    }

    /**
     * Synchronously starts the application instance on the specified port.
     *
     * @param port to run on
     * @return running application instance.
     * @see Javalin#create()
     * @see Javalin#start()
     */
    public Javalin start(int port) {
        return port(port).start();
    }

    /**
     * Synchronously starts the application instance.
     *
     * @return running application instance.
     * @see Javalin#create()
     */
    public Javalin start() {
        long startupTimer = System.currentTimeMillis();
        if (started) {
            throw new IllegalStateException("Cannot call start() again on a started server.");
        }
        if (showStartupBanner) {
            log.info(Util.INSTANCE.javalinBanner());
        }
        Util.INSTANCE.printHelpfulMessageIfLoggerIsMissing();
        eventManager.fireEvent(JavalinEvent.SERVER_STARTING);
        try {
            log.info("Starting Javalin ...");
            port = JettyServerUtil.initialize(
                jettyServer,
                jettySessionHandler,
                port,
                contextPath,
                javalinServlet,
                wsPathMatcher,
                wsFactoryConfig
            );
            log.info("Javalin started in " + (System.currentTimeMillis() - startupTimer) + "ms \\o/");
            started = true;
            JettyServerUtil.INSTANCE.setNoJettyStarted(false);
            eventManager.fireEvent(JavalinEvent.SERVER_STARTED);
        } catch (Exception e) {
            log.error("Failed to start Javalin");
            eventManager.fireEvent(JavalinEvent.SERVER_START_FAILED);
            if (e.getMessage() != null && e.getMessage().contains("Failed to bind to")) {
                throw new RuntimeException("Port already in use. Make sure no other process is using port " + port + " and try again.", e);
            } else if (e.getMessage() != null && e.getMessage().contains("Permission denied")) {
                throw new RuntimeException("Port 1-1023 require elevated privileges (process must be started by admin).", e);
            }
            throw new RuntimeException(e);
        }
        JettyServerUtil.reEnableJettyLogger();
        return this;
    }

    /**
     * Synchronously stops the application instance.
     *
     * @return stopped application instance.
     */
    public Javalin stop() {
        eventManager.fireEvent(JavalinEvent.SERVER_STOPPING);
        log.info("Stopping Javalin ...");
        try {
            jettyServer.stop();
        } catch (Exception e) {
            log.error("Javalin failed to stop gracefully", e);
        }
        log.info("Javalin has stopped");
        eventManager.fireEvent(JavalinEvent.SERVER_STOPPED);
        return this;
    }

    /**
     * Configure the instance to return 405 (Method Not Allowed) instead of 404 (Not Found) whenever a request method doesn't exists but there are handlers for other methods on the same requested path.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin prefer405over404() {
        ensureActionIsPerformedBeforeServerStart("Telling Javalin to return 405 instead of 404 when applicable");
        javalinServlet.setPrefer405over404(true);
        return this;
    }

    /**
     * Configure instance to not show banner in logs.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin disableStartupBanner() {
        ensureActionIsPerformedBeforeServerStart("Telling Javalin to not show banner in logs");
        showStartupBanner = false;
        return this;
    }

    /**
     * Configure instance to treat '/test/' and '/test' as different URLs.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin dontIgnoreTrailingSlashes() {
        ensureActionIsPerformedBeforeServerStart("Telling Javalin to not ignore slashes");
        javalinServlet.getMatcher().setIgnoreTrailingSlashes(false);
        if (javalinServlet.getResourceHandler() == null) {
            javalinServlet.setResourceHandler(new JettyResourceHandler());
        }
        javalinServlet.getResourceHandler().dontIgnoreTrailingSlashes();
        return this;
    }

    /**
     * Configure instance to use a custom jetty Server.
     *
     * @see <a href="https://javalin.io/documentation#custom-server">Documentation example</a>
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin server(@NotNull Supplier<Server> server) {
        ensureActionIsPerformedBeforeServerStart("Setting a custom server");
        jettyServer = server.get();
        return this;
    }

    /**
     * Configure instance to use a custom jetty SessionHandler.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin sessionHandler(@NotNull Supplier<SessionHandler> sessionHandler) {
        ensureActionIsPerformedBeforeServerStart("Setting a custom session handler");
        jettySessionHandler = JettyServerUtil.INSTANCE.getValidSessionHandlerOrThrow(sessionHandler);
        return this;
    }

    /**
     * Configure the WebSocketServletFactory of the instance
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin wsFactoryConfig(@NotNull Consumer<WebSocketServletFactory> wsFactoryConfig) {
        ensureActionIsPerformedBeforeServerStart("Setting a custom WebSocket factory config");
        this.wsFactoryConfig = wsFactoryConfig;
        return this;
    }

    /**
     * Configure instance to serve static files from path in classpath.
     * The method can be called multiple times for different locations.
     * The method must be called before {@link Javalin#start()}.
     *
     * @see <a href="https://javalin.io/documentation#static-files">Static files in docs</a>
     */
    public Javalin enableStaticFiles(@NotNull String classpathPath) {
        return enableStaticFiles(classpathPath, Location.CLASSPATH);
    }

    /**
     * Configure instance to serve static files from path in the specified location.
     * The method can be called multiple times for different locations.
     * The method must be called before {@link Javalin#start()}.
     *
     * @see <a href="https://javalin.io/documentation#static-files">Static files in docs</a>
     */
    public Javalin enableStaticFiles(@NotNull String path, @NotNull Location location) {
        ensureActionIsPerformedBeforeServerStart("Enabling static files");
        if (javalinServlet.getResourceHandler() == null) {
            javalinServlet.setResourceHandler(new JettyResourceHandler());
        }
        javalinServlet.getResourceHandler().addStaticFileConfig(new StaticFileConfig(path, location));
        return this;
    }

    /**
     * Configure instance to serve WebJars.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin enableWebJars() {
        return enableStaticFiles("/webjars", Location.CLASSPATH);
    }

    /**
     * Any request that would normally result in a 404 for the path and its subpaths
     * instead results in a 200 with the file-content from path in classpath as response body.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin enableSinglePageMode(@NotNull String path, @NotNull String filePath) {
        return enableSinglePageMode(path, filePath, Location.CLASSPATH);
    }

    /**
     * Any request that would normally result in a 404 for the path and its subpaths
     * instead results in a 200 with the file-content as response body.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin enableSinglePageMode(@NotNull String path, @NotNull String filePath, @NotNull Location location) {
        ensureActionIsPerformedBeforeServerStart("Enabling single page mode");
        javalinServlet.getSinglePageHandler().add(path, filePath, location);
        return this;
    }

    /**
     * Configure instance to run on specified context path (common prefix).
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin contextPath(@NotNull String contextPath) {
        ensureActionIsPerformedBeforeServerStart("Setting the context path");
        this.contextPath = Util.INSTANCE.normalizeContextPath(contextPath);
        return this;
    }

    /**
     * Get which port instance is running on
     * Mostly useful if you start the instance with port(0) (random port)
     */
    public int port() {
        return port;
    }

    /**
     * Configure instance to run on specified port.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin port(int port) {
        ensureActionIsPerformedBeforeServerStart("Setting the port");
        this.port = port;
        return this;
    }

    /**
     * Configure instance to log debug information for each request.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin enableDebugLogging() {
        ensureActionIsPerformedBeforeServerStart("Enabling debug-logging");
        javalinServlet.setDebugLogging(true);
        wsLogger(LogUtil::wsDebugLogger);
        return this;
    }

    /**
     * Configure instance use specified request-logger
     * The method must be called before {@link Javalin#start()}.
     * Will override the default logger of {@link Javalin#enableDebugLogging()}.
     */
    public Javalin requestLogger(@NotNull RequestLogger requestLogger) {
        ensureActionIsPerformedBeforeServerStart("Setting a custom request logger");
        this.javalinServlet.setRequestLogger(requestLogger);
        return this;
    }

    /**
     * Configure instance to accept cross origin requests for specified origins.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin enableCorsForOrigin(@NotNull String... origin) {
        ensureActionIsPerformedBeforeServerStart("Enabling CORS");
        if (origin.length == 0) throw new IllegalArgumentException("Origins cannot be empty.");
        this.before("*", new CorsBeforeHandler(origin));
        this.options("*", new CorsOptionsHandler(), roles(CoreRoles.NO_WRAP));
        return this;
    }

    /**
     * Configure instance to accept cross origin requests for all origins.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin enableCorsForAllOrigins() {
        return enableCorsForOrigin("*");
    }

    /**
     * Configure instance to not gzip dynamic responses.
     * By default Javalin gzips all responses larger than 1500 bytes.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin disableDynamicGzip() {
        ensureActionIsPerformedBeforeServerStart("Disabling dynamic GZIP");
        javalinServlet.setDynamicGzipEnabled(false);
        return this;
    }

    /**
     * Configure instance to automatically add ETags for GET requests.
     * Static files already have ETags, this will calculate a checksum for
     * dynamic GET responses and return 304 if the content has not changed.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin enableAutogeneratedEtags() {
        ensureActionIsPerformedBeforeServerStart("Enabling autogenerated etags");
        javalinServlet.setAutogeneratedEtagsEnabled(true);
        return this;
    }

    /**
     * Configure instance to display a visual overview of all its mapped routes
     * on the specified path.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin enableRouteOverview(@NotNull String path) {
        return enableRouteOverview(path, new HashSet<>());
    }

    /**
     * Configure instance to display a visual overview of all its mapped routes
     * on the specified path with the specified roles
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin enableRouteOverview(@NotNull String path, @NotNull Set<Role> permittedRoles) {
        ensureActionIsPerformedBeforeServerStart("Enabling route overview");
        return this.get(path, new RouteOverviewRenderer(this), permittedRoles);
    }

    /**
     * Configure instance to use the specified content-type as a default
     * value for all responses. This can be overridden in any Handler.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin defaultContentType(@NotNull String contentType) {
        ensureActionIsPerformedBeforeServerStart("Changing default content type");
        javalinServlet.setDefaultContentType(contentType);
        return this;
    }

    /**
     * Configure instance to stop caching requests larger than the specified body size.
     * The default value is 4096 bytes.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin maxBodySizeForRequestCache(long bodySizeInBytes) {
        ensureActionIsPerformedBeforeServerStart("Changing request cache body size");
        javalinServlet.setMaxRequestCacheBodySize(bodySizeInBytes);
        return this;
    }

    /**
     * Registers an attribute on the instance.
     * Instance is available on the {@link Context} through {@link Context#appAttribute}.
     * Ex: app.attribute(MyExt.class, myExtInstance())
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin attribute(Class clazz, Object obj) {
        ensureActionIsPerformedBeforeServerStart("Registering app attributes");
        appAttributes.put(clazz, obj);
        return this;
    }

    /**
     * Retrieve an attribute stored on the instance.
     * Available on the {@link Context} through {@link Context#appAttribute}.
     * Ex: app.attribute(MyExt.class).myMethod()
     * Ex: ctx.appAttribute(MyExt.class).myMethod()
     */
    @SuppressWarnings("unchecked")
    public <T> T attribute(Class<T> clazz) {
        return (T) appAttributes.get(clazz);
    }

    /**
     * Configure instance to not cache any requests.
     * If you call this method you will not be able to log request-bodies.
     * The method must be called before {@link Javalin#start()}.
     */
    public Javalin disableRequestCache() {
        return maxBodySizeForRequestCache(0);
    }

    private void ensureActionIsPerformedBeforeServerStart(@NotNull String action) {
        if (started) {
            throw new IllegalStateException(action + " must be done before starting the server.");
        }
    }

    /**
     * Sets the access manager for the instance. Secured endpoints require one to be set.
     * The method must be called before {@link Javalin#start()}.
     *
     * @see <a href="https://javalin.io/documentation#access-manager">Access manager in docs</a>
     * @see AccessManager
     */
    public Javalin accessManager(@NotNull AccessManager accessManager) {
        ensureActionIsPerformedBeforeServerStart("Setting an AccessManager");
        this.javalinServlet.setAccessManager(accessManager);
        return this;
    }

    /**
     * Adds an exception mapper to the instance.
     * Useful for turning exceptions into standardized errors/messages/pages
     *
     * @see <a href="https://javalin.io/documentation#exception-mapping">Exception mapping in docs</a>
     */
    public <T extends Exception> Javalin exception(@NotNull Class<T> exceptionClass, @NotNull ExceptionHandler<? super T> exceptionHandler) {
        javalinServlet.getExceptionMapper().getExceptionMap().put(exceptionClass, (ExceptionHandler<Exception>) exceptionHandler);
        return this;
    }

    /**
     * Adds a lifecycle event listener.
     * The method must be called before {@link Javalin#start()}.
     *
     * @see <a href="https://javalin.io/documentation#lifecycle-events">Events in docs</a>
     */
    public Javalin event(@NotNull JavalinEvent javalinEvent, @NotNull EventListener eventListener) {
        ensureActionIsPerformedBeforeServerStart("Event-mapping");
        eventManager.getListenerMap().get(javalinEvent).add(eventListener);
        return this;
    }

    /**
     * Configures a web socket handler to be called after every web socket event
     * The method must be called before {@link Javalin#start()}.
     * Will override the default logger of {@link Javalin#enableDebugLogging()}.
     */
    public Javalin wsLogger(@NotNull Consumer<WsHandler> ws) {
        ensureActionIsPerformedBeforeServerStart("Adding a WebSocket logger");
        WsHandler wsLogger = new WsHandler();
        ws.accept(wsLogger);
        wsPathMatcher.setWsLogger(wsLogger);
        return this;
    }

    /**
     * Adds an error mapper to the instance.
     * Useful for turning error-codes (404, 500) into standardized messages/pages
     *
     * @see <a href="https://javalin.io/documentation#error-mapping">Error mapping in docs</a>
     */
    public Javalin error(int statusCode, @NotNull ErrorHandler errorHandler) {
        javalinServlet.getErrorMapper().getErrorHandlerMap().put(statusCode, errorHandler);
        return this;
    }

    /**
     * Registers an {@link Extension} with the instance.
     * You're free to implement the extension as a class or a lambda expression
     */
    public Javalin register(Extension extension) {
        extension.registerOnJavalin(this);
        return this;
    }

    /**
     * Creates a temporary static instance in the scope of the endpointGroup.
     * Allows you to call get(handler), post(handler), etc. without without using the instance prefix.
     *
     * @see <a href="https://javalin.io/documentation#handler-groups">Handler groups in documentation</a>
     * @see ApiBuilder
     */
    public Javalin routes(@NotNull EndpointGroup endpointGroup) {
        ApiBuilder.setStaticJavalin(this);
        endpointGroup.addEndpoints();
        ApiBuilder.clearStaticJavalin();
        return this;
    }

    /**
     * Adds a request handler for the specified handlerType and path to the instance.
     * Requires an access manager to be set on the instance.
     * This is the method that all the verb-methods (get/post/put/etc) call.
     *
     * @see AccessManager
     * @see Javalin#accessManager(AccessManager)
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin addHandler(@NotNull HandlerType handlerType, @NotNull String path, @NotNull Handler handler, @NotNull Set<Role> roles) {
        javalinServlet.addHandler(handlerType, path, handler, roles);
        handlerMetaInfo.add(new HandlerMetaInfo(handlerType, Util.prefixContextPath(contextPath, path), handler, roles));
        return this;
    }

    /**
     * Adds a request handler for the specified handlerType and path to the instance.
     * This is the method that all the verb-methods (get/post/put/etc) call.
     *
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin addHandler(@NotNull HandlerType httpMethod, @NotNull String path, @NotNull Handler handler) {
        return addHandler(httpMethod, path, handler, new HashSet<>()); // no roles set for this route (open to everyone)
    }

    // HTTP verbs

    /**
     * Adds a GET request handler for the specified path to the instance.
     *
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin get(@NotNull String path, @NotNull Handler handler) {
        return addHandler(HandlerType.GET, path, handler);
    }

    /**
     * Adds a POST request handler for the specified path to the instance.
     *
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin post(@NotNull String path, @NotNull Handler handler) {
        return addHandler(HandlerType.POST, path, handler);
    }

    /**
     * Adds a PUT request handler for the specified path to the instance.
     *
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin put(@NotNull String path, @NotNull Handler handler) {
        return addHandler(HandlerType.PUT, path, handler);
    }

    /**
     * Adds a PATCH request handler for the specified path to the instance.
     *
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin patch(@NotNull String path, @NotNull Handler handler) {
        return addHandler(HandlerType.PATCH, path, handler);
    }

    /**
     * Adds a DELETE request handler for the specified path to the instance.
     *
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin delete(@NotNull String path, @NotNull Handler handler) {
        return addHandler(HandlerType.DELETE, path, handler);
    }

    /**
     * Adds a HEAD request handler for the specified path to the instance.
     *
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin head(@NotNull String path, @NotNull Handler handler) {
        return addHandler(HandlerType.HEAD, path, handler);
    }

    /**
     * Adds a TRACE request handler for the specified path to the instance.
     *
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin trace(@NotNull String path, @NotNull Handler handler) {
        return addHandler(HandlerType.TRACE, path, handler);
    }

    /**
     * Adds a CONNECT request handler for the specified path to the instance.
     *
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin connect(@NotNull String path, @NotNull Handler handler) {
        return addHandler(HandlerType.CONNECT, path, handler);
    }

    /**
     * Adds a OPTIONS request handler for the specified path to the instance.
     *
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin options(@NotNull String path, @NotNull Handler handler) {
        return addHandler(HandlerType.OPTIONS, path, handler);
    }

    // Secured HTTP verbs

    /**
     * Adds a GET request handler with the given roles for the specified path to the instance.
     * Requires an access manager to be set on the instance.
     *
     * @see AccessManager
     * @see Javalin#accessManager(AccessManager)
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin get(@NotNull String path, @NotNull Handler handler, @NotNull Set<Role> permittedRoles) {
        return addHandler(HandlerType.GET, path, handler, permittedRoles);
    }

    /**
     * Adds a POST request handler with the given roles for the specified path to the instance.
     * Requires an access manager to be set on the instance.
     *
     * @see AccessManager
     * @see Javalin#accessManager(AccessManager)
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin post(@NotNull String path, @NotNull Handler handler, @NotNull Set<Role> permittedRoles) {
        return addHandler(HandlerType.POST, path, handler, permittedRoles);
    }

    /**
     * Adds a PUT request handler with the given roles for the specified path to the instance.
     * Requires an access manager to be set on the instance.
     *
     * @see AccessManager
     * @see Javalin#accessManager(AccessManager)
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin put(@NotNull String path, @NotNull Handler handler, @NotNull Set<Role> permittedRoles) {
        return addHandler(HandlerType.PUT, path, handler, permittedRoles);
    }

    /**
     * Adds a PATCH request handler with the given roles for the specified path to the instance.
     * Requires an access manager to be set on the instance.
     *
     * @see AccessManager
     * @see Javalin#accessManager(AccessManager)
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin patch(@NotNull String path, @NotNull Handler handler, @NotNull Set<Role> permittedRoles) {
        return addHandler(HandlerType.PATCH, path, handler, permittedRoles);
    }

    /**
     * Adds a DELETE request handler with the given roles for the specified path to the instance.
     * Requires an access manager to be set on the instance.
     *
     * @see AccessManager
     * @see Javalin#accessManager(AccessManager)
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin delete(@NotNull String path, @NotNull Handler handler, @NotNull Set<Role> permittedRoles) {
        return addHandler(HandlerType.DELETE, path, handler, permittedRoles);
    }

    /**
     * Adds a HEAD request handler with the given roles for the specified path to the instance.
     * Requires an access manager to be set on the instance.
     *
     * @see AccessManager
     * @see Javalin#accessManager(AccessManager)
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin head(@NotNull String path, @NotNull Handler handler, @NotNull Set<Role> permittedRoles) {
        return addHandler(HandlerType.HEAD, path, handler, permittedRoles);
    }

    /**
     * Adds a TRACE request handler with the given roles for the specified path to the instance.
     * Requires an access manager to be set on the instance.
     *
     * @see AccessManager
     * @see Javalin#accessManager(AccessManager)
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin trace(@NotNull String path, @NotNull Handler handler, @NotNull Set<Role> permittedRoles) {
        return addHandler(HandlerType.TRACE, path, handler, permittedRoles);
    }

    /**
     * Adds a CONNECT request handler with the given roles for the specified path to the instance.
     * Requires an access manager to be set on the instance.
     *
     * @see AccessManager
     * @see Javalin#accessManager(AccessManager)
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin connect(@NotNull String path, @NotNull Handler handler, @NotNull Set<Role> permittedRoles) {
        return addHandler(HandlerType.CONNECT, path, handler, permittedRoles);
    }

    /**
     * Adds a OPTIONS request handler with the given roles for the specified path to the instance.
     * Requires an access manager to be set on the instance.
     *
     * @see AccessManager
     * @see Javalin#accessManager(AccessManager)
     * @see <a href="https://javalin.io/documentation#handlers">Handlers in docs</a>
     */
    public Javalin options(@NotNull String path, @NotNull Handler handler, @NotNull Set<Role> permittedRoles) {
        return addHandler(HandlerType.OPTIONS, path, handler, permittedRoles);
    }

    // Filters

    /**
     * Adds a BEFORE request handler for the specified path to the instance.
     *
     * @see <a href="https://javalin.io/documentation#before-handlers">Handlers in docs</a>
     */
    public Javalin before(@NotNull String path, @NotNull Handler handler) {
        return addHandler(HandlerType.BEFORE, path, handler);
    }

    /**
     * Adds a BEFORE request handler for all routes in the instance.
     *
     * @see <a href="https://javalin.io/documentation#before-handlers">Handlers in docs</a>
     */
    public Javalin before(@NotNull Handler handler) {
        return before("*", handler);
    }

    /**
     * Adds an AFTER request handler for the specified path to the instance.
     *
     * @see <a href="https://javalin.io/documentation#before-handlers">Handlers in docs</a>
     */
    public Javalin after(@NotNull String path, @NotNull Handler handler) {
        return addHandler(HandlerType.AFTER, path, handler);
    }

    /**
     * Adds an AFTER request handler for all routes in the instance.
     *
     * @see <a href="https://javalin.io/documentation#before-handlers">Handlers in docs</a>
     */
    public Javalin after(@NotNull Handler handler) {
        return after("*", handler);
    }

    /**
     * Adds a WebSocket handler on the specified path.
     *
     * @see <a href="https://javalin.io/documentation#websockets">WebSockets in docs</a>
     */
    public Javalin ws(@NotNull String path, @NotNull Consumer<WsHandler> ws) {
        String prefixedPath = Util.prefixContextPath(contextPath, path);
        WsHandler configuredWebSocket = new WsHandler();
        ws.accept(configuredWebSocket);
        wsPathMatcher.add(new WsEntry(prefixedPath, configuredWebSocket));
        handlerMetaInfo.add(new HandlerMetaInfo(HandlerType.WEBSOCKET, prefixedPath, ws, new HashSet<>()));
        return this;
    }

    /**
     * Gets the list of HandlerMetaInfo-objects
     */
    public List<HandlerMetaInfo> getHandlerMetaInfo() {
        return new ArrayList<>(handlerMetaInfo);
    }

    /**
     * Adds a lambda handler for a Server Sent Event connection on the specified path.
     */
    public Javalin sse(@NotNull String path, @NotNull Consumer<SseClient> client) {
        return sse(path, client, new HashSet<>());
    }

    /**
     * Adds a lambda handler for a Server Sent Event connection on the specified path.
     * Requires an access manager to be set on the instance.
     */
    public Javalin sse(@NotNull String path, @NotNull Consumer<SseClient> client, @NotNull Set<Role> permittedRoles) {
        return get(path, new SseHandler(client), permittedRoles);
    }

}
