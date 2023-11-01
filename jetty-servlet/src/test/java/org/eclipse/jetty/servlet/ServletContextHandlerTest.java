//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.FilterRegistration;
import javax.servlet.GenericServlet;
import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestAttributeEvent;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.RoleInfo;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.AbstractHandlerContainer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ServletContextHandlerTest
{
    private Server _server;
    private LocalConnector _connector;

    private static final AtomicInteger __testServlets = new AtomicInteger();
    private static int __initIndex = 0;
    private static int __destroyIndex = 0;

    public static class StopTestFilter implements Filter
    {
        int _initIndex;
        int _destroyIndex;

        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
            _initIndex = __initIndex++;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException,
            ServletException
        {
        }

        @Override
        public void destroy()
        {
            _destroyIndex = __destroyIndex++;
        }
    }

    public static class StopTestServlet extends GenericServlet
    {
        int _initIndex;
        int _destroyIndex;

        @Override
        public void destroy()
        {
            _destroyIndex = __destroyIndex++;
            super.destroy();
        }

        @Override
        public void init() throws ServletException
        {
            _initIndex = __initIndex++;
            super.init();
        }

        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
        }
    }

    public static class StopTestListener implements ServletContextListener
    {
        int _initIndex;
        int _destroyIndex;

        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            _initIndex = __initIndex++;
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
            _destroyIndex = __destroyIndex++;
        }
    }

    public static class MySCI implements ServletContainerInitializer
    {
        @Override
        public void onStartup(Set<Class<?>> c, ServletContext ctx)
        {
            //add a programmatic listener
            if (ctx.getAttribute("MySCI.startup") != null)
                throw new IllegalStateException("MySCI already called");
            ctx.setAttribute("MySCI.startup", Boolean.TRUE);
            ctx.addListener(new MyContextListener());
        }
    }

    public static class MySCIStarter extends AbstractLifeCycle implements ServletContextHandler.ServletContainerInitializerCaller
    {
        ServletContainerInitializer _sci;
        ContextHandler.Context _ctx;

        MySCIStarter(ContextHandler.Context ctx, ServletContainerInitializer sci)
        {
            _ctx = ctx;
            _sci = sci;
        }

        @Override
        protected void doStart() throws Exception
        {
            super.doStart();
            //call the SCI
            _ctx.setExtendedListenerTypes(true);
            _sci.onStartup(Collections.emptySet(), _ctx);
        }
    }

    public static class MyContextListener implements ServletContextListener
    {

        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            assertNull(sce.getServletContext().getAttribute("MyContextListener.contextInitialized"));
            sce.getServletContext().setAttribute("MyContextListener.contextInitialized", Boolean.TRUE);
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
            assertNull(sce.getServletContext().getAttribute("MyContextListener.contextDestroyed"));
            sce.getServletContext().setAttribute("MyContextListener.contextDestroyed", Boolean.TRUE);
        }
    }

    public static class MySessionHandler extends SessionHandler
    {
        public void checkSessionListeners(int size)
        {
            assertNotNull(_sessionListeners);
            assertEquals(size, _sessionListeners.size());
        }

        public void checkSessionAttributeListeners(int size)
        {
            assertNotNull(_sessionAttributeListeners);
            assertEquals(size, _sessionAttributeListeners.size());
        }

        public void checkSessionIdListeners(int size)
        {
            assertNotNull(_sessionIdListeners);
            assertEquals(size, _sessionIdListeners.size());
        }
    }

    public static class MyTestSessionListener implements HttpSessionAttributeListener, HttpSessionListener
    {
        @Override
        public void sessionCreated(HttpSessionEvent se)
        {
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent se)
        {
        }

        @Override
        public void attributeAdded(HttpSessionBindingEvent event)
        {
        }

        @Override
        public void attributeRemoved(HttpSessionBindingEvent event)
        {
        }

        @Override
        public void attributeReplaced(HttpSessionBindingEvent event)
        {
        }
    }

    public static class MySCAListener implements ServletContextAttributeListener
    {
        public static int adds = 0;
        public static int removes = 0;
        public static int replaces = 0;

        @Override
        public void attributeAdded(ServletContextAttributeEvent event)
        {
            ++adds;
        }

        @Override
        public void attributeRemoved(ServletContextAttributeEvent event)
        {
            ++removes;
        }

        @Override
        public void attributeReplaced(ServletContextAttributeEvent event)
        {
            ++replaces;
        }
    }

    public static class MyRequestListener implements ServletRequestListener
    {
        public static int destroys = 0;
        public static int inits = 0;

        @Override
        public void requestDestroyed(ServletRequestEvent sre)
        {
            ++destroys;
        }

        @Override
        public void requestInitialized(ServletRequestEvent sre)
        {
            ++inits;
        }
    }

    public static class MyRAListener implements ServletRequestAttributeListener
    {
        public static int adds = 0;
        public static int removes = 0;
        public static int replaces = 0;

        @Override
        public void attributeAdded(ServletRequestAttributeEvent srae)
        {
            ++adds;
        }

        @Override
        public void attributeRemoved(ServletRequestAttributeEvent srae)
        {
            ++removes;
        }

        @Override
        public void attributeReplaced(ServletRequestAttributeEvent srae)
        {
            ++replaces;
        }
    }

    public static class MySListener implements HttpSessionListener
    {
        public static int creates = 0;
        public static int destroys = 0;

        @Override
        public void sessionCreated(HttpSessionEvent se)
        {
            ++creates;
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent se)
        {
            ++destroys;
        }
    }

    public static class MySAListener implements HttpSessionAttributeListener
    {
        public static int adds = 0;
        public static int removes = 0;
        public static int replaces = 0;

        @Override
        public void attributeAdded(HttpSessionBindingEvent event)
        {
            ++adds;
        }

        @Override
        public void attributeRemoved(HttpSessionBindingEvent event)
        {
            ++removes;
        }

        @Override
        public void attributeReplaced(HttpSessionBindingEvent event)
        {
            ++replaces;
        }
    }

    public static class MySIListener implements HttpSessionIdListener
    {
        public static int changes = 0;

        @Override
        public void sessionIdChanged(HttpSessionEvent event, String oldSessionId)
        {
            ++changes;
        }
    }

    public static class InitialListener implements ServletContextListener
    {
        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            //Add all of the sorts of listeners that are allowed
            try
            {
                MySCAListener mySCAListener = sce.getServletContext().createListener(MySCAListener.class);
                sce.getServletContext().addListener(mySCAListener);

                MyRequestListener myRequestListener = sce.getServletContext().createListener(MyRequestListener.class);
                sce.getServletContext().addListener(myRequestListener);

                MyRAListener myRAListener = sce.getServletContext().createListener(MyRAListener.class);
                sce.getServletContext().addListener(myRAListener);

                MySListener mySListener = sce.getServletContext().createListener(MySListener.class);
                sce.getServletContext().addListener(mySListener);

                MySAListener mySAListener = sce.getServletContext().createListener(MySAListener.class);
                sce.getServletContext().addListener(mySAListener);

                MySIListener mySIListener = sce.getServletContext().createListener(MySIListener.class);
                sce.getServletContext().addListener(mySIListener);
            }
            catch (Exception e)
            {
                fail(e);
            }

            // And also test you can't add a ServletContextListener from a ServletContextListener
            try
            {
                MyContextListener contextListener = sce.getServletContext().createListener(MyContextListener.class);
                assertThrows(IllegalArgumentException.class, () -> sce.getServletContext().addListener(contextListener), "Adding SCI from an SCI!");
            }
            catch (IllegalArgumentException e)
            {
                //expected
            }
            catch (ServletException x)
            {
                fail(x);
            }

            sce.getServletContext().setAttribute("foo", "bar");
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {

        }
    }

    @BeforeEach
    public void createServer()
    {
        _server = new Server();

        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        __testServlets.set(0);
    }

    @AfterEach
    public void destroyServer() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testDestroyOrder() throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        ServletContextHandler root = new ServletContextHandler(contexts, "/", ServletContextHandler.SESSIONS);
        ListenerHolder listenerHolder = new ListenerHolder();
        StopTestListener stopTestListener = new StopTestListener();
        listenerHolder.setListener(stopTestListener);
        root.getServletHandler().addListener(listenerHolder);
        ServletHolder servletHolder = new ServletHolder();
        StopTestServlet stopTestServlet = new StopTestServlet();
        servletHolder.setServlet(stopTestServlet);
        root.addServlet(servletHolder, "/test");
        FilterHolder filterHolder = new FilterHolder();
        StopTestFilter stopTestFilter = new StopTestFilter();
        filterHolder.setFilter(stopTestFilter);
        root.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
        _server.start();
        _server.stop();

        assertEquals(0, stopTestListener._initIndex); //listeners contextInitialized called first
        assertEquals(1, stopTestFilter._initIndex); //filters init
        assertEquals(2, stopTestServlet._initIndex); //servlets init

        assertEquals(0, stopTestFilter._destroyIndex); //filters destroyed first
        assertEquals(1, stopTestServlet._destroyIndex); //servlets destroyed next
        assertEquals(2, stopTestListener._destroyIndex); //listener contextDestroyed last
    }

    @Test
    public void testAddSessionListener()
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        ServletContextHandler root = new ServletContextHandler(contexts, "/", ServletContextHandler.SESSIONS);

        MySessionHandler sessions = new MySessionHandler();
        root.setSessionHandler(sessions);
        assertNotNull(sessions);

        root.addEventListener(new MyTestSessionListener());
        sessions.checkSessionAttributeListeners(1);
        sessions.checkSessionIdListeners(0);
        sessions.checkSessionListeners(1);
    }

    @Test
    public void testListenerFromSCI() throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        ServletContextHandler root = new ServletContextHandler(contexts, "/");
        root.addBean(new MySCIStarter(root.getServletContext(), new MySCI()), true);
        _server.start();
        assertTrue((Boolean)root.getServletContext().getAttribute("MySCI.startup"));
        assertTrue((Boolean)root.getServletContext().getAttribute("MyContextListener.contextInitialized"));
    }

    @Test
    public void testContextInitializationDestruction() throws Exception
    {
        Server server = new Server();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        ServletContextHandler root = new ServletContextHandler(contexts, "/");
        class TestServletContextListener implements ServletContextListener
        {
            public int initialized = 0;
            public int destroyed = 0;

            @Override
            public void contextInitialized(ServletContextEvent sce)
            {
                initialized++;
            }

            @Override
            public void contextDestroyed(ServletContextEvent sce)
            {
                destroyed++;
            }
        }

        TestServletContextListener listener = new TestServletContextListener();
        root.addEventListener(listener);
        server.start();
        server.stop();
        assertEquals(1, listener.initialized);
        server.stop();
        assertEquals(1, listener.destroyed);
    }

    @Test
    public void testListenersFromContextListener() throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        ServletContextHandler root = new ServletContextHandler(contexts, "/", ServletContextHandler.SESSIONS);
        ListenerHolder initialListener = new ListenerHolder();
        initialListener.setListener(new InitialListener());
        root.getServletHandler().addListener(initialListener);
        root.addServlet(TestServlet.class, "/test");
        _server.start();

        ListenerHolder[] listenerHolders = root.getServletHandler().getListeners();
        assertNotNull(listenerHolders);
        for (ListenerHolder l : listenerHolders)
        {
            assertTrue(l.isStarted());
            assertNotNull(l.getListener());
            //all listeners except the first should be programmatic
            if (!"org.eclipse.jetty.servlet.ServletContextHandlerTest$InitialListener".equals(l.getClassName()))
            {
                assertFalse(root.isDurableListener(l.getListener()));
                assertTrue(root.isProgrammaticListener(l.getListener()));
            }
        }

        EventListener[] listeners = root.getEventListeners();
        assertNotNull(listeners);
        List<String> listenerClassNames = new ArrayList<>();
        for (EventListener l : listeners)
        {
            listenerClassNames.add(l.getClass().getName());
        }

        assertTrue(listenerClassNames.contains("org.eclipse.jetty.servlet.ServletContextHandlerTest$MySCAListener"));
        assertTrue(listenerClassNames.contains("org.eclipse.jetty.servlet.ServletContextHandlerTest$MyRequestListener"));
        assertTrue(listenerClassNames.contains("org.eclipse.jetty.servlet.ServletContextHandlerTest$MyRAListener"));
        assertTrue(listenerClassNames.contains("org.eclipse.jetty.servlet.ServletContextHandlerTest$MySListener"));
        assertTrue(listenerClassNames.contains("org.eclipse.jetty.servlet.ServletContextHandlerTest$MySAListener"));
        assertTrue(listenerClassNames.contains("org.eclipse.jetty.servlet.ServletContextHandlerTest$MySIListener"));

        //test ServletRequestAttributeListener
        String response = _connector.getResponse("GET /test?req=all HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("200 OK"));
        assertEquals(1, MyRAListener.adds);
        assertEquals(1, MyRAListener.replaces);
        assertEquals(1, MyRAListener.removes);

        //test HttpSessionAttributeListener
        response = _connector.getResponse("GET /test?session=create HTTP/1.0\r\n\r\n");
        String sessionid = response.substring(response.indexOf("JSESSIONID"), response.indexOf(";"));
        assertThat(response, Matchers.containsString("200 OK"));
        assertEquals(1, MySListener.creates);
        assertEquals(1, MySAListener.adds);
        assertEquals(0, MySAListener.replaces);
        assertEquals(0, MySAListener.removes);
        StringBuffer request = new StringBuffer();
        request.append("GET /test?session=replace HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("Cookie: ").append(sessionid).append("\n");
        request.append("\n");
        response = _connector.getResponse(request.toString());
        assertThat(response, Matchers.containsString("200 OK"));
        assertEquals(1, MySListener.creates);
        assertEquals(1, MySAListener.adds);
        assertEquals(1, MySAListener.replaces);
        assertEquals(0, MySAListener.removes);
        request = new StringBuffer();
        request.append("GET /test?session=remove HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("Cookie: ").append(sessionid).append("\n");
        request.append("\n");
        response = _connector.getResponse(request.toString());
        assertThat(response, Matchers.containsString("200 OK"));
        assertEquals(1, MySListener.creates);
        assertEquals(1, MySAListener.adds);
        assertEquals(1, MySAListener.replaces);
        assertEquals(1, MySAListener.removes);

        //test HttpSessionIdListener.sessionIdChanged
        request = new StringBuffer();
        request.append("GET /test?session=change HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("Cookie: ").append(sessionid).append("\n");
        request.append("\n");
        response = _connector.getResponse(request.toString());
        assertThat(response, Matchers.containsString("200 OK"));
        assertEquals(1, MySIListener.changes);
        sessionid = response.substring(response.indexOf("JSESSIONID"), response.indexOf(";"));

        //test HttpServletListener.sessionDestroyed
        request = new StringBuffer();
        request.append("GET /test?session=delete HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("Cookie: ").append(sessionid).append("\n");
        request.append("\n");
        response = _connector.getResponse(request.toString());
        assertThat(response, Matchers.containsString("200 OK"));
        assertEquals(1, MySListener.destroys);

        //test ServletContextAttributeListener
        //attribute was set when context listener registered
        assertEquals(1, MySCAListener.adds);
        response = _connector.getResponse("GET /test?ctx=all HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("200 OK"));
        assertEquals(1, MySCAListener.replaces);
        assertEquals(1, MySCAListener.removes);
    }

    @Test
    public void testFindContainer() throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        ServletContextHandler root = new ServletContextHandler(contexts, "/", ServletContextHandler.SESSIONS);

        SessionHandler session = root.getSessionHandler();
        ServletHandler servlet = root.getServletHandler();
        SecurityHandler security = new ConstraintSecurityHandler();
        root.setSecurityHandler(security);

        _server.start();

        assertEquals(root, AbstractHandlerContainer.findContainerOf(_server, ContextHandler.class, session));
        assertEquals(root, AbstractHandlerContainer.findContainerOf(_server, ContextHandler.class, security));
        assertEquals(root, AbstractHandlerContainer.findContainerOf(_server, ContextHandler.class, servlet));
    }

    @Test
    public void testInitOrder() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        ServletHolder holder0 = context.addServlet(TestServlet.class, "/test0");
        ServletHolder holder1 = context.addServlet(TestServlet.class, "/test1");
        ServletHolder holder2 = context.addServlet(TestServlet.class, "/test2");

        holder1.setInitOrder(1);
        holder2.setInitOrder(2);

        context.setContextPath("/");
        _server.setHandler(context);
        _server.start();

        assertEquals(2, __testServlets.get());

        String response = _connector.getResponse("GET /test1 HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("200 OK"));

        assertEquals(2, __testServlets.get());

        response = _connector.getResponse("GET /test2 HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("200 OK"));

        assertEquals(2, __testServlets.get());

        assertThat(holder0.getServletInstance(), nullValue());
        response = _connector.getResponse("GET /test0 HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("200 OK"));
        assertEquals(3, __testServlets.get());
        assertThat(holder0.getServletInstance(), notNullValue(Servlet.class));

        _server.stop();
        assertEquals(0, __testServlets.get());

        holder0.setInitOrder(0);
        _server.start();
        assertEquals(3, __testServlets.get());
        assertThat(holder0.getServletInstance(), notNullValue(Servlet.class));
        _server.stop();
        assertEquals(0, __testServlets.get());
    }

    @Test
    public void testAddServletFromServlet()
    {
        //A servlet cannot be added by another servlet
        Logger logger = Log.getLogger(ContextHandler.class.getName() + "ROOT");

        try (StacklessLogging ignored = new StacklessLogging(logger))
        {
            ServletContextHandler context = new ServletContextHandler();
            context.setLogger(logger);
            ServletHolder holder = context.addServlet(ServletAddingServlet.class, "/start");
            context.getServletHandler().setStartWithUnavailable(false);
            holder.setInitOrder(0);
            context.setContextPath("/");
            _server.setHandler(context);
            ServletException se = assertThrows(ServletException.class, _server::start);
            assertThat("Servlet can only be added from SCI or SCL", se.getCause(), instanceOf(IllegalStateException.class));
        }
    }

    @Test
    public void testAddFilterFromServlet()
    {
        //A filter cannot be added from a servlet
        Logger logger = Log.getLogger(ContextHandler.class.getName() + "ROOT");

        try (StacklessLogging ignored = new StacklessLogging(logger))
        {
            ServletContextHandler context = new ServletContextHandler();
            context.setLogger(logger);
            ServletHolder holder = context.addServlet(FilterAddingServlet.class, "/filter");
            context.getServletHandler().setStartWithUnavailable(false);
            holder.setInitOrder(0);
            context.setContextPath("/");
            _server.setHandler(context);
            ServletException se = assertThrows(ServletException.class, _server::start);
            assertThat("Filter can only be added from SCI or SCL", se.getCause(), instanceOf(IllegalStateException.class));
        }
    }

    @Test
    public void testAddServletByClassFromFilter()
    {
        //A servlet cannot be added from a Filter
        Logger logger = Log.getLogger(ContextHandler.class.getName() + "ROOT");

        try (StacklessLogging ignored = new StacklessLogging(logger))
        {
            ServletContextHandler context = new ServletContextHandler();
            context.setLogger(logger);
            FilterHolder holder = new FilterHolder(new Filter()
            {
                @Override
                public void init(FilterConfig filterConfig)
                {
                    ServletRegistration rego = filterConfig.getServletContext().addServlet("hello", HelloServlet.class);
                    rego.addMapping("/hello/*");
                }

                @Override
                public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                {
                }

                @Override
                public void destroy()
                {
                }
            });
            context.addFilter(holder, "/*", EnumSet.of(DispatcherType.REQUEST));
            context.getServletHandler().setStartWithUnavailable(false);
            context.setContextPath("/");
            _server.setHandler(context);
            assertThrows(IllegalStateException.class, _server::start, "Servlet can only be added from SCI or SCL");
        }
    }

    @Test
    public void testAddServletByInstanceFromFilter()
    {
        //A servlet cannot be added from a Filter
        Logger logger = Log.getLogger(ContextHandler.class.getName() + "ROOT");

        try (StacklessLogging ignored = new StacklessLogging(logger))
        {
            ServletContextHandler context = new ServletContextHandler();
            context.setLogger(logger);
            FilterHolder holder = new FilterHolder(new Filter()
            {
                @Override
                public void init(FilterConfig filterConfig)
                {
                    ServletRegistration rego = filterConfig.getServletContext().addServlet("hello", new HelloServlet());
                    rego.addMapping("/hello/*");
                }

                @Override
                public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                {
                }

                @Override
                public void destroy()
                {
                }
            });
            context.addFilter(holder, "/*", EnumSet.of(DispatcherType.REQUEST));
            context.getServletHandler().setStartWithUnavailable(false);
            context.setContextPath("/");
            _server.setHandler(context);
            assertThrows(IllegalStateException.class, _server::start, "Servlet can only be added from SCI or SCL");
        }
    }

    @Test
    public void testAddServletByClassNameFromFilter()
    {
        //A servlet cannot be added from a Filter
        Logger logger = Log.getLogger(ContextHandler.class.getName() + "ROOT");

        try (StacklessLogging ignored = new StacklessLogging(logger))
        {
            ServletContextHandler context = new ServletContextHandler();
            context.setLogger(logger);
            FilterHolder holder = new FilterHolder(new Filter()
            {
                @Override
                public void init(FilterConfig filterConfig)
                {
                    ServletRegistration rego = filterConfig.getServletContext().addServlet("hello", HelloServlet.class.getName());
                    rego.addMapping("/hello/*");
                }

                @Override
                public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                {
                }

                @Override
                public void destroy()
                {
                }
            });
            context.addFilter(holder, "/*", EnumSet.of(DispatcherType.REQUEST));
            context.getServletHandler().setStartWithUnavailable(false);
            context.setContextPath("/");
            _server.setHandler(context);
            assertThrows(IllegalStateException.class, _server::start, "Servlet can only be added from SCI or SCL");
        }
    }

    @Test
    public void testAddServletFromSCL() throws Exception
    {
        //A servlet can be added from a ServletContextListener
        ServletContextHandler context = new ServletContextHandler();
        context.getServletHandler().setStartWithUnavailable(false);
        context.setContextPath("/");
        context.addEventListener(new ServletContextListener()
        {

            @Override
            public void contextInitialized(ServletContextEvent sce)
            {
                ServletRegistration rego = sce.getServletContext().addServlet("hello", HelloServlet.class);
                rego.addMapping("/hello/*");
            }

            @Override
            public void contextDestroyed(ServletContextEvent sce)
            {
            }
        });
        _server.setHandler(context);
        _server.start();

        StringBuilder request = new StringBuilder();
        request.append("GET /hello HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Hello World"));
    }

    @Test
    public void testAddServletFromSCI() throws Exception
    {
        //A servlet can be added from a ServletContainerInitializer
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        ServletContextHandler root = new ServletContextHandler(contexts, "/");
        class ServletAddingSCI implements ServletContainerInitializer
        {
            @Override
            public void onStartup(Set<Class<?>> c, ServletContext ctx)
            {
                ServletRegistration rego = ctx.addServlet("hello", HelloServlet.class);
                rego.addMapping("/hello/*");
            }
        }

        root.addBean(new MySCIStarter(root.getServletContext(), new ServletAddingSCI()), true);
        _server.start();

        StringBuilder request = new StringBuilder();
        request.append("GET /hello HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Hello World"));
    }

    @Test
    public void testAddServletAfterStart() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(TestServlet.class, "/test");
        context.setContextPath("/");
        _server.setHandler(context);
        _server.start();

        StringBuffer request = new StringBuffer();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Test"));

        context.addServlet(HelloServlet.class, "/hello");

        request = new StringBuffer();
        request.append("GET /hello HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Hello World"));
    }

    @Test
    public void testServletRegistrationByClass() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletRegistration reg = context.getServletContext().addServlet("test", TestServlet.class);
        reg.addMapping("/test");

        _server.setHandler(context);
        _server.start();

        StringBuilder request = new StringBuilder();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Test"));
    }

    @Test
    public void testServletRegistrationByClassName() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletRegistration reg = context.getServletContext().addServlet("test", TestServlet.class.getName());
        reg.addMapping("/test");

        _server.setHandler(context);
        _server.start();

        StringBuilder request = new StringBuilder();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Test"));
    }

    @Test
    public void testPartialServletRegistrationByName() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder partial = new ServletHolder();
        partial.setName("test");
        context.addServlet(partial, "/test");

        //complete partial servlet registration by providing name of the servlet class
        ServletRegistration reg = context.getServletContext().addServlet("test", TestServlet.class.getName());
        assertNotNull(reg);
        assertEquals(TestServlet.class.getName(), partial.getClassName());

        _server.setHandler(context);
        _server.start();

        StringBuilder request = new StringBuilder();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Test"));
    }

    @Test
    public void testPartialServletRegistrationByClass() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder partial = new ServletHolder();
        partial.setName("test");
        context.addServlet(partial, "/test");

        //complete partial servlet registration by providing the servlet class
        ServletRegistration reg = context.getServletContext().addServlet("test", TestServlet.class);
        assertNotNull(reg);
        assertEquals(TestServlet.class.getName(), partial.getClassName());
        assertSame(TestServlet.class, partial.getHeldClass());

        _server.setHandler(context);
        _server.start();

        StringBuilder request = new StringBuilder();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Test"));
    }

    @Test
    public void testNullServletRegistration() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder full = new ServletHolder();
        full.setName("test");
        full.setHeldClass(TestServlet.class);
        context.addServlet(full, "/test");

        //Must return null if the servlet has been fully defined previously
        ServletRegistration reg = context.getServletContext().addServlet("test", TestServlet.class);
        assertNull(reg);

        _server.setHandler(context);
        _server.start();

        StringBuilder request = new StringBuilder();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Test"));
    }

    @Test
    public void testHandlerBeforeServletHandler() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);

        HandlerWrapper extra = new HandlerWrapper();

        context.getSessionHandler().insertHandler(extra);

        context.addServlet(TestServlet.class, "/test");
        context.setContextPath("/");
        _server.setHandler(context);
        _server.start();

        StringBuilder request = new StringBuilder();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Test"));

        assertEquals(extra, context.getSessionHandler().getHandler());
    }

    @Test
    public void testGzipHandlerOption() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS | ServletContextHandler.GZIP);
        GzipHandler gzip = context.getGzipHandler();
        _server.start();
        assertEquals(context.getSessionHandler(), context.getHandler());
        assertEquals(gzip, context.getSessionHandler().getHandler());
        assertEquals(context.getServletHandler(), gzip.getHandler());
    }

    @Test
    public void testGzipHandlerSet() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.setSessionHandler(new SessionHandler());
        context.setGzipHandler(new GzipHandler());
        GzipHandler gzip = context.getGzipHandler();
        _server.start();
        assertEquals(context.getSessionHandler(), context.getHandler());
        assertEquals(gzip, context.getSessionHandler().getHandler());
        assertEquals(context.getServletHandler(), gzip.getHandler());
    }

    @Test
    public void testReplaceServletHandlerWithServlet() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(TestServlet.class, "/test");
        context.setContextPath("/");
        _server.setHandler(context);
        _server.start();

        StringBuffer request = new StringBuffer();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Test"));

        context.stop();
        ServletHandler srvHnd = new ServletHandler();
        srvHnd.addServletWithMapping(HelloServlet.class, "/hello");
        context.setServletHandler(srvHnd);
        context.start();

        request = new StringBuffer();
        request.append("GET /hello HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Hello World"));
    }

    @Test
    public void testSetSecurityHandler()
    {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS | ServletContextHandler.SECURITY | ServletContextHandler.GZIP);
        assertNotNull(context.getSessionHandler());
        SessionHandler sessionHandler = context.getSessionHandler();
        assertNotNull(context.getSecurityHandler());
        SecurityHandler securityHandler = context.getSecurityHandler();
        assertNotNull(context.getGzipHandler());
        GzipHandler gzipHandler = context.getGzipHandler();

        //check the handler linking order
        HandlerWrapper h = (HandlerWrapper)context.getHandler();
        assertSame(h, sessionHandler);

        h = (HandlerWrapper)h.getHandler();
        assertSame(h, securityHandler);

        h = (HandlerWrapper)h.getHandler();
        assertSame(h, gzipHandler);

        //replace the security handler
        SecurityHandler myHandler = new SecurityHandler()
        {
            @Override
            protected RoleInfo prepareConstraintInfo(String pathInContext, Request request)
            {
                return null;
            }

            @Override
            protected boolean checkUserDataPermissions(String pathInContext, Request request, Response response,
                                                       RoleInfo constraintInfo)
            {
                return false;
            }

            @Override
            protected boolean isAuthMandatory(Request baseRequest, Response baseResponse, Object constraintInfo)
            {
                return false;
            }

            @Override
            protected boolean checkWebResourcePermissions(String pathInContext, Request request, Response response,
                                                          Object constraintInfo, UserIdentity userIdentity)
            {
                return false;
            }
        };

        //check the linking order
        context.setSecurityHandler(myHandler);
        assertSame(myHandler, context.getSecurityHandler());

        h = (HandlerWrapper)context.getHandler();
        assertSame(h, sessionHandler);

        h = (HandlerWrapper)h.getHandler();
        assertSame(h, myHandler);

        h = (HandlerWrapper)h.getHandler();
        assertSame(h, gzipHandler);
    }

    @Test
    public void testReplaceServletHandlerWithoutServlet() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(TestServlet.class, "/test");
        context.setContextPath("/");
        _server.setHandler(context);
        _server.start();

        StringBuffer request = new StringBuffer();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Test"));

        context.stop();
        ServletHandler srvHnd = new ServletHandler();
        context.setServletHandler(srvHnd);
        context.start();

        context.addServlet(HelloServlet.class, "/hello");

        request = new StringBuffer();
        request.append("GET /hello HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Hello World"));
    }

    @Test
    public void testReplaceHandler() throws Exception
    {
        ServletContextHandler servletContextHandler = new ServletContextHandler();
        ServletHolder sh = new ServletHolder(new TestServlet());
        servletContextHandler.addServlet(sh, "/foo");
        final AtomicBoolean contextInit = new AtomicBoolean(false);
        final AtomicBoolean contextDestroy = new AtomicBoolean(false);

        servletContextHandler.addEventListener(new ServletContextListener()
        {

            @Override
            public void contextInitialized(ServletContextEvent sce)
            {
                if (sce.getServletContext() != null)
                    contextInit.set(true);
            }

            @Override
            public void contextDestroyed(ServletContextEvent sce)
            {
                if (sce.getServletContext() != null)
                    contextDestroy.set(true);
            }
        });
        ServletHandler shandler = servletContextHandler.getServletHandler();

        ResourceHandler rh = new ResourceHandler();

        servletContextHandler.insertHandler(rh);
        assertEquals(shandler, servletContextHandler.getServletHandler());
        assertEquals(rh, servletContextHandler.getHandler());
        assertEquals(rh.getHandler(), shandler);
        _server.setHandler(servletContextHandler);
        _server.start();
        assertTrue(contextInit.get());
        _server.stop();
        assertTrue(contextDestroy.get());
    }

    @Test
    public void testFallThrough() throws Exception
    {
        HandlerList list = new HandlerList();
        _server.setHandler(list);

        ServletContextHandler root = new ServletContextHandler(list, "/", ServletContextHandler.SESSIONS);

        ServletHandler servlet = root.getServletHandler();
        servlet.setEnsureDefaultServlet(false);
        servlet.addServletWithMapping(HelloServlet.class, "/hello/*");

        list.addHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.sendError(404, "Fell Through");
            }
        });

        _server.start();

        String response = _connector.getResponse("GET /hello HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("200 OK"));

        response = _connector.getResponse("GET /other HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("404 Fell Through"));
    }

    /**
     * Test behavior of legacy ServletContextHandler.Decorator, with
     * new DecoratedObjectFactory class
     *
     * @throws Exception on test failure
     */
    @SuppressWarnings("deprecation")
    @Test
    public void testLegacyDecorator() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.addDecorator(new DummyLegacyDecorator());
        _server.setHandler(context);

        context.addServlet(DecoratedObjectFactoryServlet.class, "/objfactory/*");
        _server.start();

        String response = _connector.getResponse("GET /objfactory/ HTTP/1.0\r\n\r\n");
        assertThat("Response status code", response, containsString("200 OK"));

        String expected = String.format("Attribute[%s] = %s", DecoratedObjectFactory.ATTR, DecoratedObjectFactory.class.getName());
        assertThat("Has context attribute", response, containsString(expected));

        assertThat("Decorators size", response, containsString("Decorators.size = [2]"));

        expected = String.format("decorator[] = %s", DummyLegacyDecorator.class.getName());
        assertThat("Specific Legacy Decorator", response, containsString(expected));
    }

    /**
     * Test behavior of new {@link org.eclipse.jetty.util.Decorator}, with
     * new DecoratedObjectFactory class
     *
     * @throws Exception on test failure
     */
    @Test
    public void testUtilDecorator() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.getObjectFactory().addDecorator(new DummyUtilDecorator());
        _server.setHandler(context);

        context.addServlet(DecoratedObjectFactoryServlet.class, "/objfactory/*");
        _server.start();

        String response = _connector.getResponse("GET /objfactory/ HTTP/1.0\r\n\r\n");
        assertThat("Response status code", response, containsString("200 OK"));

        String expected = String.format("Attribute[%s] = %s", DecoratedObjectFactory.ATTR, DecoratedObjectFactory.class.getName());
        assertThat("Has context attribute", response, containsString(expected));

        assertThat("Decorators size", response, containsString("Decorators.size = [2]"));

        expected = String.format("decorator[] = %s", DummyUtilDecorator.class.getName());
        assertThat("Specific Legacy Decorator", response, containsString(expected));
    }

    public static class HelloServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException
        {
            resp.setStatus(HttpServletResponse.SC_OK);
            PrintWriter writer = resp.getWriter();
            writer.write("Hello World");
        }
    }

    public static class MyFilter implements Filter
    {

        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException,
            ServletException
        {
            request.getServletContext().setAttribute("filter", "filter");
            chain.doFilter(request, response);
        }

        @Override
        public void destroy()
        {
        }
    }

    public static class DummyUtilDecorator implements org.eclipse.jetty.util.Decorator
    {
        @Override
        public <T> T decorate(T o)
        {
            return o;
        }

        @Override
        public void destroy(Object o)
        {
        }
    }

    @SuppressWarnings("deprecation")
    public static class DummyLegacyDecorator implements org.eclipse.jetty.servlet.ServletContextHandler.Decorator
    {
        @Override
        public <T> T decorate(T o)
        {
            return o;
        }

        @Override
        public void destroy(Object o)
        {
        }
    }

    public static class DecoratedObjectFactoryServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setContentType("text/plain");
            resp.setStatus(HttpServletResponse.SC_OK);
            PrintWriter out = resp.getWriter();

            Object obj = req.getServletContext().getAttribute(DecoratedObjectFactory.ATTR);
            out.printf("Attribute[%s] = %s%n", DecoratedObjectFactory.ATTR, obj.getClass().getName());

            if (obj instanceof DecoratedObjectFactory)
            {
                out.printf("Object is a DecoratedObjectFactory%n");
                DecoratedObjectFactory objFactory = (DecoratedObjectFactory)obj;
                List<Decorator> decorators = objFactory.getDecorators();
                out.printf("Decorators.size = [%d]%n", decorators.size());
                for (Decorator decorator : decorators)
                {
                    out.printf(" decorator[] = %s%n", decorator.getClass().getName());
                }
            }
            else
            {
                out.printf("Object is NOT a DecoratedObjectFactory%n");
            }
        }
    }

    public static class ServletAddingServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.getWriter().write("Start");
            resp.getWriter().close();
        }

        @Override
        public void init() throws ServletException
        {
            ServletRegistration dynamic = getServletContext().addServlet("added", AddedServlet.class);
            dynamic.addMapping("/added/*");
        }
    }

    public static class FilterAddingServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.getWriter().write("Filter");
            resp.getWriter().close();
        }

        @Override
        public void init() throws ServletException
        {
            FilterRegistration dynamic = getServletContext().addFilter("filter", new MyFilter());
            dynamic.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
        }
    }

    public static class AddedServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.getWriter().write("Added");
            resp.getWriter().close();
        }
    }

    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        public void destroy()
        {
            super.destroy();
            __testServlets.decrementAndGet();
        }

        @Override
        public void init() throws ServletException
        {
            __testServlets.incrementAndGet();
            super.init();
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException
        {
            resp.setStatus(HttpServletResponse.SC_OK);
            PrintWriter writer = resp.getWriter();
            writer.write("Test");

            String action = req.getParameter("session");
            if (!Objects.isNull(action))
            {
                if ("create".equalsIgnoreCase(action))
                {
                    //Make a session
                    HttpSession session = req.getSession(true);
                    session.setAttribute("some", "thing");
                }
                else if ("change".equalsIgnoreCase(action))
                {
                    req.getSession(true);
                    req.changeSessionId();
                }
                else if ("replace".equalsIgnoreCase(action))
                {
                    HttpSession session = req.getSession(false);
                    session.setAttribute("some", "other");
                }
                else if ("remove".equalsIgnoreCase(action))
                {
                    HttpSession session = req.getSession(false);
                    session.removeAttribute("some");
                }
                else if ("delete".equalsIgnoreCase(action))
                {
                    HttpSession session = req.getSession(false);
                    session.invalidate();
                }
                else
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);

                return;
            }

            action = req.getParameter("req");
            if (!Objects.isNull(action))
            {
                //test all attribute ops
                req.setAttribute("some", "value");
                req.setAttribute("some", "other");
                req.removeAttribute("some");

                return;
            }

            action = req.getParameter("ctx");
            if (!Objects.isNull(action))
            {
                //change and remove context attribute
                req.getServletContext().setAttribute("foo", "foo");
                req.getServletContext().removeAttribute("foo");
            }
        }
    }
}
