package jmri.web.server;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import javax.annotation.Nonnull;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import jmri.InstanceManager;
import jmri.ShutDownManager;
import jmri.ShutDownTask;
import jmri.implementation.QuietShutDownTask;
import jmri.server.json.JSON;
import jmri.server.web.spi.WebServerConfiguration;
import jmri.util.FileUtil;
import jmri.util.zeroconf.ZeroConfService;
import jmri.web.servlet.DenialServlet;
import jmri.web.servlet.RedirectionServlet;
import jmri.web.servlet.directory.DirectoryHandler;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An HTTP server that handles requests for HTTPServlets.
 *
 * This server loads HttpServlets registered as
 * {@link javax.servlet.http.HttpServlet} service providers and annotated with
 * the {@link javax.servlet.annotation.WebServlet} annotation. It also loads the
 * registered {@link jmri.server.web.spi.WebServerConfiguration} objects to get
 * configuration for file handling, redirection, and denial of access to
 * resources.
 *
 * When there is a conflict over how a path should be handled, denials take
 * precedence, followed by servlets, redirections, and lastly direct access to
 * files.
 *
 * @author Bob Jacobsen Copyright 2005, 2006
 * @author Randall Wood Copyright 2012, 2016
 */
public final class WebServer implements LifeCycle.Listener {

    private static enum Registration {
        DENIAL, REDIRECTION, RESOURCE, SERVLET
    };
    private Server server;
    private ZeroConfService zeroConfService = null;
    private WebServerPreferences preferences = null;
    private ShutDownTask shutDownTask = null;
    private final HashMap<String, Registration> registeredUrls = new HashMap<>();
    private final static Logger log = LoggerFactory.getLogger(WebServer.class.getName());

    /**
     * Create a WebServer instance with the default preferences.
     */
    public WebServer() {
        this(WebServerPreferences.getDefault());
    }

    /**
     * Create a WebServer instance with the specified preferences.
     *
     * @param preferences the preferences
     */
    protected WebServer(WebServerPreferences preferences) {
        this.preferences = preferences;
    }

    /**
     * Get the default web server instance.
     *
     * @return a WebServer instance, either the existing instance or a new
     *         instance created with the default constructor.
     */
    @Nonnull
    public static WebServer getDefault() {
        return InstanceManager.getOptionalDefault(WebServer.class).orElseGet(() -> {
            return InstanceManager.setDefault(WebServer.class, new WebServer());
        });
    }

    /**
     * Start the web server.
     */
    public void start() {
        if (server == null) {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setName("WebServer");
            threadPool.setMaxThreads(1000);
            server = new Server(threadPool);
            ServerConnector connector = new ServerConnector(server);
            connector.setIdleTimeout(5 * 60 * 1000); // 5 minutes
            connector.setSoLingerTime(-1);
            connector.setPort(preferences.getPort());
            server.setConnectors(new Connector[]{connector});
            server.setHandler(new ContextHandlerCollection());

            ContextHandlerCollection contexts = new ContextHandlerCollection();
            // Load all path handlers
            ServiceLoader.load(WebServerConfiguration.class).forEach((configuration) -> {
                Map<String, String> filePaths = configuration.getFilePaths();
                for (String key : filePaths.keySet()) {
                    this.registerResource(key, filePaths.get(key));
                }
                Map<String, String> redirections = configuration.getRedirectedPaths();
                for (String key : redirections.keySet()) {
                    this.registerRedirection(key, redirections.get(key));
                }
                List<String> denials = configuration.getForbiddenPaths();
                for (String key : denials) {
                    this.registerDenial(key);
                }
            });
            // Load all classes that provide the HttpServlet service.
            ServiceLoader.load(HttpServlet.class).forEach((servlet) -> {
                this.registerServlet(servlet.getClass(), servlet);
            });
            server.addLifeCycleListener(this);

            Thread serverThread = new ServerThread(server);
            serverThread.setName("WebServer"); // NOI18N
            serverThread.start();

        }

    }

    /**
     * Stop the server.
     *
     * @throws Exception if there is an error stopping the server
     */
    public void stop() throws Exception {
        server.stop();
    }

    /**
     * Get the public URI for a portable path. This method returns public URIs
     * for only some portable paths, and does not check that the portable path
     * is actually sane. Note that this refuses to return portable paths that
     * are outside of program: and preference:
     *
     * @param path the JMRI portable path
     * @return The servable URI or null
     * @see jmri.util.FileUtil#getPortableFilename(java.io.File)
     */
    public static String URIforPortablePath(String path) {
        if (path.startsWith(FileUtil.PREFERENCES)) {
            return path.replaceFirst(FileUtil.PREFERENCES, "/prefs/"); // NOI18N
        } else if (path.startsWith(FileUtil.PROGRAM)) {
            return path.replaceFirst(FileUtil.PROGRAM, "/dist/"); // NOI18N
        } else {
            return null;
        }
    }

    public int getPort() {
        return preferences.getPort();
    }

    public WebServerPreferences getPreferences() {
        return preferences;
    }

    /**
     * Register a URL pattern to be denied access.
     *
     * @param urlPattern the pattern to deny access to
     */
    public void registerDenial(String urlPattern) {
        this.registeredUrls.put(urlPattern, Registration.DENIAL);
        ServletContextHandler servletContext = new ServletContextHandler(ServletContextHandler.NO_SECURITY);
        servletContext.setContextPath(urlPattern);
        DenialServlet servlet = new DenialServlet();
        servletContext.addServlet(new ServletHolder(servlet), "/*"); // NOI18N
        ((ContextHandlerCollection) this.server.getHandler()).addHandler(servletContext);
    }

    /**
     * Register a URL pattern to return resources from the file system.
     *
     * @param urlPattern the pattern to get resources for
     * @param filePath   the portable path for the resources
     * @throws IllegalArgumentException if urlPattern is already registered to
     *                                  deny access or for a servlet
     */
    public void registerResource(String urlPattern, String filePath) throws IllegalArgumentException {
        if (this.registeredUrls.get(urlPattern) != null) {
            throw new IllegalArgumentException("urlPattern \"" + urlPattern + "\" is already registered.");
        }
        this.registeredUrls.put(urlPattern, Registration.RESOURCE);
        ServletContextHandler servletContext = new ServletContextHandler(ServletContextHandler.NO_SECURITY);
        servletContext.setContextPath(urlPattern);
        HandlerList handlers = new HandlerList();
        if (filePath.startsWith("program:")) { // NOI18N
            log.debug("Setting up handler chain for {}", urlPattern);
            // make it possible to override anything under program: with an identical path under preference:
            ResourceHandler preferenceHandler = new DirectoryHandler(FileUtil.getAbsoluteFilename(filePath.replace("program:", "preference:"))); // NOI18N
            ResourceHandler programHandler = new DirectoryHandler(FileUtil.getAbsoluteFilename(filePath));
            handlers.setHandlers(new Handler[]{preferenceHandler, programHandler, new DefaultHandler()});
        } else {
            log.debug("Setting up handler chain for {}", urlPattern);
            ResourceHandler handler = new DirectoryHandler(FileUtil.getAbsoluteFilename(filePath));
            handlers.setHandlers(new Handler[]{handler, new DefaultHandler()});
        }
        ContextHandler handlerContext = new ContextHandler();
        handlerContext.setContextPath(urlPattern);
        handlerContext.setHandler(handlers);
        ((ContextHandlerCollection) this.server.getHandler()).addHandler(handlerContext);
    }

    /**
     * Register a URL pattern to be redirected to another resource.
     *
     * @param urlPattern  the pattern to be redirected
     * @param redirection the path to which the pattern is redirected
     * @throws IllegalArgumentException if urlPattern is already registered for
     *                                  any other purpose
     */
    public void registerRedirection(String urlPattern, String redirection) throws IllegalArgumentException {
        Registration registered = this.registeredUrls.get(urlPattern);
        if (registered != null && registered != Registration.REDIRECTION) {
            throw new IllegalArgumentException("\"" + urlPattern + "\" registered to " + registered);
        }
        this.registeredUrls.put(urlPattern, Registration.REDIRECTION);
        ServletContextHandler servletContext = new ServletContextHandler(ServletContextHandler.NO_SECURITY);
        servletContext.setContextPath(urlPattern);
        RedirectionServlet servlet = new RedirectionServlet(urlPattern, redirection);
        servletContext.addServlet(new ServletHolder(servlet), ""); // NOI18N
        ((ContextHandlerCollection) this.server.getHandler()).addHandler(servletContext);
    }

    /**
     * Register a {@link javax.servlet.http.HttpServlet } that is annotated with
     * the {@link javax.servlet.annotation.WebServlet } annotation.
     *
     * This method calls
     * {@link #registerServlet(java.lang.Class, javax.servlet.http.HttpServlet)}
     * with a null HttpServlet.
     *
     * @param type The actual class of the servlet.
     */
    public void registerServlet(Class<? extends HttpServlet> type) {
        this.registerServlet(type, null);
    }

    /**
     * Register a {@link javax.servlet.http.HttpServlet } that is annotated with
     * the {@link javax.servlet.annotation.WebServlet } annotation.
     *
     * Registration reads the WebServlet annotation to get the list of paths the
     * servlet should handle and creates instances of the Servlet to handle each
     * path.
     *
     * Note that all HttpServlets registered using this mechanism must have a
     * default constructor.
     *
     * @param type     The actual class of the servlet.
     * @param instance An un-initialized, un-registered instance of the servlet.
     */
    public void registerServlet(Class<? extends HttpServlet> type, HttpServlet instance) {
        try {
            for (ServletContextHandler handler : this.registerServlet(
                    ServletContextHandler.NO_SECURITY,
                    type,
                    instance
            )) {
                ((ContextHandlerCollection) this.server.getHandler()).addHandler(handler);
            }
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException ex) {
            log.error("Unable to register servlet", ex);
        }
    }

    private List<ServletContextHandler> registerServlet(int options, Class<? extends HttpServlet> type, HttpServlet instance)
            throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        WebServlet info = type.getAnnotation(WebServlet.class);
        List<ServletContextHandler> handlers = new ArrayList<>(info.urlPatterns().length);
        for (String pattern : info.urlPatterns()) {
            if (this.registeredUrls.get(pattern) != Registration.DENIAL) {
                // DenialServlet gets special handling
                if (info.name().equals("DenialServlet")) { // NOI18N
                    this.registeredUrls.put(pattern, Registration.DENIAL);
                } else {
                    this.registeredUrls.put(pattern, Registration.SERVLET);
                }
                ServletContextHandler context = new ServletContextHandler(options);
                context.setContextPath(pattern);
                log.debug("Creating new {} for URL pattern {}", type.getName(), pattern);
                context.addServlet(type, "/*"); // NOI18N
                handlers.add(context);
            } else {
                log.error("Unable to register servlet \"{}\" to provide denied URL {}", info.name(), pattern);
            }
        }
        return handlers;
    }

    @Override
    public void lifeCycleStarting(LifeCycle lc) {
        shutDownTask = new ServerShutDownTask(this);
        InstanceManager.getOptionalDefault(ShutDownManager.class).ifPresent(manager -> {
            manager.register(shutDownTask);
        });
        log.info("Starting Web Server on port {}", preferences.getPort());
    }

    @Override
    public void lifeCycleStarted(LifeCycle lc) {
        HashMap<String, String> properties = new HashMap<>();
        properties.put("path", "/"); // NOI18N
        properties.put(JSON.JSON, JSON.JSON_PROTOCOL_VERSION);
        log.info("Starting ZeroConfService _http._tcp.local for Web Server with properties {}", properties);
        zeroConfService = ZeroConfService.create("_http._tcp.local.", preferences.getPort(), properties); // NOI18N
        zeroConfService.publish();
        log.debug("Web Server finished starting");
    }

    @Override
    public void lifeCycleFailure(LifeCycle lc, Throwable thrwbl) {
        log.warn("Web Server failed", thrwbl);
    }

    @Override
    public void lifeCycleStopping(LifeCycle lc) {
        if (zeroConfService != null) {
            zeroConfService.stop();
        }
        log.info("Stopping Web Server");
    }

    @Override
    public void lifeCycleStopped(LifeCycle lc) {
        InstanceManager.getOptionalDefault(ShutDownManager.class).ifPresent(manager -> {
            manager.deregister(shutDownTask);
        });
        log.debug("Web Server stopped");
    }

    static private class ServerThread extends Thread {

        private final Server server;

        public ServerThread(Server server) {
            this.server = server;
        }

        @Override
        public void run() {
            try {
                server.start();
                server.join();
            } catch (Exception ex) {
                log.error("Exception starting Web Server", ex);
            }
        }
    }

    static private class ServerShutDownTask extends QuietShutDownTask {

        private final WebServer server;
        private boolean isComplete = false;

        public ServerShutDownTask(WebServer server) {
            super("Stop Web Server"); // NOI18N
            this.server = server;
        }

        @Override
        public boolean execute() {
            new Thread(() -> {
                try {
                    server.stop();
                } catch (Exception ex) {
                    // Error without stack trace
                    log.warn("Error shutting down WebServer: {}", ex);
                    // Full stack trace
                    log.debug("Details follow: ", ex);
                }
                this.isComplete = true;
            }).start();
            return true;
        }

        @Override
        public boolean isParallel() {
            return true;
        }

        @Override
        public boolean isComplete() {
            return this.isComplete;
        }
    }
}
