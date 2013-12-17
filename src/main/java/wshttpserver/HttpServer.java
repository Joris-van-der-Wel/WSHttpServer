package wshttpserver;

import wshttpserver.HttpDownloadThread.UpgradeWebSocketHandler;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

/**
 * A simple http 1.1 server with WebSockets.
 *
 * Supports:
 * + Sending static files (GET & HEAD)
 * + Directory index files
 * + Resumeable downloads (range header)
 * + Last-Modified & If-Modified-Since
 * + WebSockets using the java_websocket lib
 *
 * Threading model: HttpServer only has a server socket that it runs accept() on. This can be used in the main loop. All
 * sockets are then passed on to the HttpDownloadThread thread, which uses a select loop to serve all downloads from a
 * singe thread. If an Upgrade: WebSocket header is present, the socket is then removed from HttpDownloadThread and
 * added to one of the HttpWebSocketServer threads that all run their own select loop. The number of HttpWebSocketServer
 * threads that are spawned, depends on the number of cpu cores (including HyperThreading). select loop and parsing
 * happen on the same thread. This ensures the anti congestion features of TCP can do their thing properly.
 *
 * HttpWebsocketListener callbacks will originate from one of the HttpWebSocketServer threads.
 *
 * @author Joris
 */
public class HttpServer implements UpgradeWebSocketHandler, HttpWebSocketServerListener
{
        static final int LINEBUFFER_SIZE = 512; // Used to combine a line that spans multiple TCP segments
        static final int RCVBUFFER_SIZE = 16384;
        static final int BUFFER_SIZE = RCVBUFFER_SIZE + LINEBUFFER_SIZE;
        static final int WEBSOCKET_PARSE_THREADS = 2;
        static final long HTTP_TIMEOUT = 10;
        private static final Logger log = Logger.getLogger("wshttpserver");
        public volatile HttpWebSocketServerListener websocketListener;
        private boolean stop = false;
        File httpdocs;
        ServerSocketChannel ssChannel;
        HttpDownloadThread downloadThread;
        private List<HttpWebSocketServer> websocketServers;
        volatile int upgradeWebSocketHandler_counter = 0;
        private final Set<WebSocket> websockets = new HashSet<>();

        public HttpServer(ServerSocketChannel ssChannel, File httpdocs_, HttpWebSocketServerListener websocketListener) throws IOException
        {
                this.ssChannel = ssChannel;
                this.httpdocs = httpdocs_.getCanonicalFile();
                this.websocketListener = websocketListener;
                
                if (ssChannel == null)
                {
                        throw new IllegalArgumentException();
                }

                if (this.httpdocs != null)
                {
                        if (!this.httpdocs.isDirectory())
                        {
                                this.httpdocs = null;
                                log.log(Level.WARNING, "argument httpdocs is not a directory");
                        }
                }


                downloadThread = new HttpDownloadThread(this.httpdocs == null ? null : new File(this.httpdocs.getPath()), this);
                downloadThread.setDaemon(true);
                
                websocketServers = new ArrayList<>(WEBSOCKET_PARSE_THREADS);
                for (int a = 0; a < WEBSOCKET_PARSE_THREADS; ++a)
                {
                        HttpWebSocketServer server = new HttpWebSocketServer(this);
                        server.setDeamon(true);
                        websocketServers.add(server);
                }
        }
        
        public void addRouteStatic(String path, File file) throws IOException, SecurityException
        {
                downloadThread.addRouteStatic(path, file);
        }
        
        public static ServerSocketChannel openServerChannel(InetSocketAddress listenAddr) throws IOException
        {
                ServerSocketChannel ssChannel = ServerSocketChannel.open();
                ssChannel.configureBlocking(false);
                ssChannel.socket().bind(listenAddr);
                ssChannel.socket().setReceiveBufferSize(RCVBUFFER_SIZE);
                
                log.log(Level.INFO, "Listening on {0}:{1,number,#}", new Object[] { ssChannel.socket().getInetAddress(), getListeningPort(ssChannel) });
                return ssChannel;
        }

        public void setup() throws IOException
        {
                if (stop)
                {
                        throw new IllegalStateException();
                }
                
                downloadThread.startWaitReady();
                for (HttpWebSocketServer s : websocketServers)
                {
                        s.startWaitReady();
                }
                
                log.log(Level.INFO, "Http server setup at {0}:{1,number,#}", new Object[] { ssChannel.socket().getInetAddress(), getListeningPort(ssChannel) });
        }
        
        public int getListeningPort()
        {
                return getListeningPort(ssChannel);
        }
        
        /** Returns the TCP port that we are currently listening on
         * @param ssChannel 
         * @return -1 if not yet listening, otherwise the listening port.
         */
        public static int getListeningPort(ServerSocketChannel ssChannel)
        {
                if (ssChannel == null)
                {
                        return -1;
                }
                
                ServerSocket sock = ssChannel.socket();
                if (sock == null)
                {
                        return -1;
                }
                
                return sock.getLocalPort();
        }

        public void stop()
        {
                downloadThread.interrupt();
                for (HttpWebSocketServer s : websocketServers)
                {
                        s.thread.interrupt();
                }

                try
                {
                        downloadThread.join();
                        for (HttpWebSocketServer s : websocketServers)
                        {
                                s.thread.join();
                        }
                }
                catch (InterruptedException ex)
                {
                }
                
                downloadThread = null;
                stop = true;
                log.log(Level.INFO, "HttpServer has stopped");
        }

        
        public void loop()
        {
                if (stop)
                {
                        throw new IllegalStateException();
                }
                
                try
                {
                        SocketChannel sChannel;
                        while ((sChannel = ssChannel.accept()) != null)
                        {
                                downloadThread.addNewChannel(sChannel);
                        }
                }
                catch (ClosedChannelException ex)
                {
                        // This exception is okay after stop() has been called.
                        // However normally this error should only shown once.
                        // Stop running the loop (or unregister this object from the loop) 
                        // when calling close()
                        log.log(Level.INFO, "Channel closed in accept()");
                }
                catch (IOException ex)
                {
                        log.log(Level.SEVERE, "IOException in accept()", ex);
                }
        }

        /**
         * Returns a WebSocket[] of currently connected clients. use synchronized() on the list while doing anything
         * with it
         *
         * @return The currently connected clients.
         */
        @ThreadSafe
        public Set<WebSocket> websockets()
        {
                return websockets;
        }

        @Override
        @ThreadSafe // The list is not modified after the constructor
        public void upgradeWebSocketHandler(SocketChannel sChannel, ByteBuffer prependData)
        {
                HttpWebSocketServer s = websocketServers.get(upgradeWebSocketHandler_counter % websocketServers.size());
                ++upgradeWebSocketHandler_counter;
                s.addNewChannel(sChannel, prependData);
        }

        @Override
        @ThreadSafe
        public boolean wssConnect(SelectionKey key)
        {
                HttpWebSocketServerListener listener = this.websocketListener;
                
                if (listener == null)
                {
                        return true;
                }

                return listener.wssConnect(key);
        }

        @Override
        @ThreadSafe
        public void wssOpen(WebSocket conn, ClientHandshake handshake)
        {
                HttpWebSocketServerListener listener = this.websocketListener;
                synchronized(websockets)
                {
                        websockets.add(conn);
                }

                if (listener != null)
                {
                        listener.wssOpen(conn, handshake);
                }
        }

        @Override
        @ThreadSafe
        public void wssClose(WebSocket conn, int code, String reason, boolean remote)
        {
                HttpWebSocketServerListener listener = this.websocketListener;
                try
                {
                        if (listener != null)
                        {
                                listener.wssClose(conn, code, reason, remote);
                        }
                }
                finally
                {
                        synchronized (websockets)
                        {
                                websockets.remove(conn);
                        }
                }
        }

        @Override
        @ThreadSafe
        public void wssMessage(WebSocket conn, String message)
        {
                HttpWebSocketServerListener listener = this.websocketListener;
                if (listener != null)
                {
                        listener.wssMessage(conn, message);
                }
        }

        @Override
        @ThreadSafe
        public void wssMessage(WebSocket conn, ByteBuffer message)
        {
                HttpWebSocketServerListener listener = this.websocketListener;
                if (listener != null)
                {
                        listener.wssMessage(conn, message);
                }
        }

        @Override
        @ThreadSafe
        public void wssError(WebSocket conn, Exception ex)
        {
                HttpWebSocketServerListener listener = this.websocketListener;
                if (listener != null)
                {
                        listener.wssError(conn, ex);
                }
        }
}
