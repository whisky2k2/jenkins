/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Stephen Connolly
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson;

import jenkins.model.Jenkins;
import hudson.model.Computer;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import hudson.remoting.Channel;
import hudson.remoting.SocketOutputStream;
import hudson.remoting.SocketInputStream;
import hudson.remoting.Engine;
import hudson.remoting.Channel.Listener;
import hudson.remoting.Channel.Mode;
import hudson.cli.CliManagerImpl;
import hudson.cli.CliEntryPoint;
import hudson.util.IOException2;
import jenkins.security.HMACConfidentialKey;

import java.io.ByteArrayInputStream;
import hudson.slaves.OfflineCause;
import jenkins.AgentProtocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.BindException;
import java.security.SecureRandom;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Listens to incoming TCP connections from JNLP slave agents and CLI.
 *
 * <p>
 * Aside from the HTTP endpoint, Jenkins runs {@link TcpSlaveAgentListener} that listens on a TCP socket.
 * Historically  this was used for inbound connection from slave agents (hence the name), but over time
 * it was extended and made generic, so that multiple protocols of different purposes can co-exist on the
 * same socket.
 *
 * <p>
 * This class accepts the socket, then after a short handshaking, it dispatches to appropriate
 * {@link AgentProtocol}s.
 *
 * @author Kohsuke Kawaguchi
 * @see AgentProtocol
 */
public final class TcpSlaveAgentListener extends Thread {

    private final ServerSocket serverSocket;
    private volatile boolean shuttingDown;

    public final int configuredPort;

    /**
     * @param port
     *      Use 0 to choose a random port.
     */
    public TcpSlaveAgentListener(int port) throws IOException {
        super("TCP slave agent listener port="+port);
        try {
            serverSocket = new ServerSocket(port);
        } catch (BindException e) {
            throw (BindException)new BindException("Failed to listen on port "+port+" because it's already in use.").initCause(e);
        }
        this.configuredPort = port;

        LOGGER.info("JNLP slave agent listener started on TCP port "+getPort());

        start();
    }

    /**
     * Gets the TCP port number in which we are listening.
     */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    @Override
    public void run() {
        try {
            // the loop eventually terminates when the socket is closed.
            while (true) {
                Socket s = serverSocket.accept();

                // this prevents a connection from silently terminated by the router in between or the other peer
                // and that goes without unnoticed. However, the time out is often very long (for example 2 hours
                // by default in Linux) that this alone is enough to prevent that.
                s.setKeepAlive(true);
                // we take care of buffering on our own
                s.setTcpNoDelay(true);

                new ConnectionHandler(s).start();
            }
        } catch (IOException e) {
            if(!shuttingDown) {
                LOGGER.log(Level.SEVERE,"Failed to accept JNLP slave agent connections",e);
            }
        }
    }

    /**
     * Initiates the shuts down of the listener.
     */
    public void shutdown() {
        shuttingDown = true;
        try {
            serverSocket.close();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to close down TCP port",e);
        }
    }

    private final class ConnectionHandler extends Thread {
        private final Socket s;
        /**
         * Unique number to identify this connection. Used in the log.
         */
        private final int id;

        public ConnectionHandler(Socket s) {
            this.s = s;
            synchronized(getClass()) {
                id = iotaGen++;
            }
            setName("TCP slave agent connection handler #"+id+" with "+s.getRemoteSocketAddress());
        }

        @Override
        public void run() {
            try {
                LOGGER.info("Accepted connection #"+id+" from "+s.getRemoteSocketAddress());

                DataInputStream in = new DataInputStream(s.getInputStream());
                PrintWriter out = new PrintWriter(s.getOutputStream(),true); // DEPRECATED: newer protocol shouldn't use PrintWriter but should use DataOutputStream

                String s = in.readUTF();

                if(s.startsWith("Protocol:")) {
                    String protocol = s.substring(9);
                    AgentProtocol p = AgentProtocol.of(protocol);
                    if (p!=null)
                        p.handle(this.s);
                    else
                        error(out, "Unknown protocol:" + s);
                } else {
                    error(out, "Unrecognized protocol: "+s);
                }
            } catch (InterruptedException e) {
                LOGGER.log(Level.WARNING,"Connection #"+id+" aborted",e);
                try {
                    s.close();
                } catch (IOException _) {
                    // try to clean up the socket
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,"Connection #"+id+" failed",e);
                try {
                    s.close();
                } catch (IOException _) {
                    // try to clean up the socket
                }
            }
        }

        /**
         * Handles CLI connection request.
         */
        private void runCliConnect(DataInputStream in, PrintWriter out) throws IOException, InterruptedException {
            out.println("Welcome");
            Channel channel = new Channel("CLI channel from " + s.getInetAddress(),
                    Computer.threadPoolForRemoting, Mode.BINARY,
                    new BufferedInputStream(new SocketInputStream(this.s)),
                    new BufferedOutputStream(new SocketOutputStream(this.s)), null, true, Jenkins.getInstance().pluginManager.uberClassLoader);
            channel.setProperty(CliEntryPoint.class.getName(),new CliManagerImpl(channel));
            channel.join();
        }

        /**
         * Handles JNLP slave agent connection request.
         */
        private void runJnlpConnect(DataInputStream in, PrintWriter out) throws IOException, InterruptedException {
            final String secret = in.readUTF();
            final String nodeName = in.readUTF();

            if(!SLAVE_SECRET.mac(nodeName).equals(secret)) {
                error(out, "Unauthorized access");
                return;
            }

            SlaveComputer computer = (SlaveComputer) Jenkins.getInstance().getComputer(nodeName);
            if(computer==null) {
                error(out, "No such slave: "+nodeName);
                return;
            }

            if(computer.getChannel()!=null) {
                error(out, nodeName+" is already connected to this master. Rejecting this connection.");
                return;
            }

            out.println(Engine.GREETING_SUCCESS);

            jnlpConnect(computer);
        }

        /**
         * Handles JNLP slave agent connection request (v2 protocol)
         */
        private void runJnlp2Connect(DataInputStream in, PrintWriter out) throws IOException, InterruptedException {
            Properties request = new Properties();
            request.load(new ByteArrayInputStream(in.readUTF().getBytes("UTF-8")));

            final String nodeName = request.getProperty("Node-Name");

            if(!SLAVE_SECRET.mac(nodeName).equals(request.getProperty("Secret-Key"))) {
                error(out, "Unauthorized access");
                return;
            }

            SlaveComputer computer = (SlaveComputer) Jenkins.getInstance().getComputer(nodeName);
            if(computer==null) {
                error(out, "No such slave: "+nodeName);
                return;
            }

            Channel ch = computer.getChannel();
            if(ch !=null) {
                String c = request.getProperty("Cookie");
                if (c!=null && c.equals(ch.getProperty(COOKIE_NAME))) {
                    // we think we are currently connected, but this request proves that it's from the party
                    // we are supposed to be communicating to. so let the current one get disconnected
                    LOGGER.info("Disconnecting "+nodeName+" as we are reconnected from the current peer");
                    try {
                        computer.disconnect(new ConnectionFromCurrentPeer()).get(15, TimeUnit.SECONDS);
                    } catch (ExecutionException e) {
                        throw new IOException2("Failed to disconnect the current client",e);
                    } catch (TimeoutException e) {
                        throw new IOException2("Failed to disconnect the current client",e);
                    }
                } else {
                    error(out, nodeName + " is already connected to this master. Rejecting this connection.");
                    return;
                }
            }

            out.println(Engine.GREETING_SUCCESS);

            Properties response = new Properties();
            String cookie = generateCookie();
            response.put("Cookie",cookie);
            writeResponseHeaders(out, response);

            ch = jnlpConnect(computer);

            ch.setProperty(COOKIE_NAME, cookie);
        }

        private void writeResponseHeaders(PrintWriter out, Properties response) {
            for (Entry<Object, Object> e : response.entrySet()) {
                out.println(e.getKey()+": "+e.getValue());
            }
            out.println(); // empty line to conclude the response header
        }

        private String generateCookie() {
            byte[] cookie = new byte[32];
            new SecureRandom().nextBytes(cookie);
            return Util.toHexString(cookie);
        }

        private Channel jnlpConnect(SlaveComputer computer) throws InterruptedException, IOException {
            final String nodeName = computer.getName();
            final OutputStream log = computer.openLogFile();
            PrintWriter logw = new PrintWriter(log,true);
            logw.println("JNLP agent connected from "+ this.s.getInetAddress());

            try {
                computer.setChannel(new BufferedInputStream(this.s.getInputStream()), new BufferedOutputStream(this.s.getOutputStream()), log,
                    new Listener() {
                        @Override
                        public void onClosed(Channel channel, IOException cause) {
                            if(cause!=null)
                                LOGGER.log(Level.WARNING, "Connection #"+id+" for + " + nodeName + " terminated",cause);
                            try {
                                ConnectionHandler.this.s.close();
                            } catch (IOException e) {
                                // ignore
                            }
                        }
                    });
                return computer.getChannel();
            } catch (AbortException e) {
                logw.println(e.getMessage());
                logw.println("Failed to establish the connection with the slave");
                throw e;
            } catch (IOException e) {
                logw.println("Failed to establish the connection with the slave " + nodeName);
                e.printStackTrace(logw);
                throw e;
            }
        }

        private void error(PrintWriter out, String msg) throws IOException {
            out.println(msg);
            LOGGER.log(Level.WARNING,"Connection #"+id+" is aborted: "+msg);
            s.close();
        }
    }

    /**
     * Connection terminated because we are reconnected from the current peer.
     */
    public static class ConnectionFromCurrentPeer extends OfflineCause {
        public String toString() {
            return "The current peer is reconnecting";
        }
    }

    private static int iotaGen=1;

    private static final Logger LOGGER = Logger.getLogger(TcpSlaveAgentListener.class.getName());

    private static final String COOKIE_NAME = TcpSlaveAgentListener.class.getName()+".cookie";

    /**
     * Host name that we advertise the CLI client to connect to.
     * This is primarily for those who have reverse proxies in place such that the HTTP host name
     * and the CLI TCP/IP connection host names are different.
     *
     * TODO: think about how to expose this (including whether this needs to be exposed at all.)
     */
    public static String CLI_HOST_NAME = System.getProperty(TcpSlaveAgentListener.class.getName()+".hostName");

    /**
     * This secret value is used as a seed for slaves.
     */
    public static final HMACConfidentialKey SLAVE_SECRET = new HMACConfidentialKey(
            // this name is modified to match the change in the trunk
            "jenkins.slaves.JnlpSlaveAgentProtocol.secret");
}

/*
Pasted from http://today.java.net/pub/a/today/2005/09/01/webstart.html

    Is it unrealistic to try to control access to JWS files?
    Is anyone doing this?

It is not unrealistic, and we are doing it. Create a protected web page
with a download button or link that makes a servlet call. If the user has
already logged in to your website, of course they can go there without
further authentication. The servlet reads the cookies sent by the browser
when the link is activated. It then generates a dynamic JNLP file adding
the authentication cookie and any other required cookies (JSESSIONID, etc.)
via <argument> tags. Write the WebStart application so that it picks up
any required cookies from the argument list, and adds these cookies to its
request headers on subsequent calls to the server. (Note: in the dynamic
JNLP file, do NOT put href= in the opening jnlp tag. If you do, JWS will
try to reload the JNLP from disk and since it's dynamic, it won't be there.
Leave it off and JWS will be happy.)

When returning the dynamic JNLP, the servlet should invoke setHeader(
"Expires", 0 ) and addDateHeader() twice on the servlet response to set
both "Date" and "Last-Modified" to the current date. This keeps the browser
from using a cached copy of a prior dynamic JNLP obtained from the same URL.

Note also that the JAR file(s) for the JWS application should not be on
a password-protected path - the launcher won't know about the authentication
cookie. But once the application starts, you can run all its requests
through a protected path requiring the authentication cookie, because
the application gets it from the dynamic JNLP. Just write it so that it
can't do anything useful without going through a protected path or doing
something to present credentials that could only have come from a valid
user.
*/
