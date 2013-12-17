package wshttpserver;

import wshttpserver.HttpConnection.ConnectionStateChangeListener;
import wshttpserver.HttpConnection.STATE;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Joris
 */
class HttpDownloadThread extends Thread implements ConnectionStateChangeListener
{
        private static final Logger log = Logger.getLogger("wshttpserver");
        private volatile boolean running = false;
        private volatile boolean ready = false;
        private Selector selector;
        private final File defaultRoute;
        private final Map<String, File> routes = new HashMap<>();
        private final UpgradeWebSocketHandler upgradeWebSocketHandler;
        private final ByteBuffer buf = ByteBuffer.allocateDirect(HttpServer.BUFFER_SIZE);
        private final ConcurrentLinkedQueue <SocketChannel> newChannels = new ConcurrentLinkedQueue<>();
        
        private long lastTimeoutCheck = System.nanoTime();

        HttpDownloadThread(File httpdocs, UpgradeWebSocketHandler upgradeWebSocketHandler)
        {
                this.defaultRoute = httpdocs;
                this.upgradeWebSocketHandler = upgradeWebSocketHandler;
        }
        
        /** Register a route (url path) to be served by the specified file or directory.
         * @param path The path part of the URL that this route applies to. 
         *             Must not begin or end with a slash
         *             For example "assets" or "abc/def"
         * @param file File or directory
         */
        public void addRouteStatic(String path, File file) throws IOException, SecurityException
        {
                if (running)
                {
                        throw new IllegalStateException();
                        // If adding routes while the server runs is desirable, 
                        // this.routes should become thread safe.
                }
                
                routes.put(path, file.getCanonicalFile());
        }

        @Override
        public void connectionStateChange(HttpConnection conn, STATE oldState, STATE newState)
        {
                if (newState == HttpConnection.STATE.CLOSED)
                {
                        conn.key.attach(null);
                        conn.key.cancel();
                        try
                        {
                                conn.channel.close();
                        }
                        catch (IOException ex)
                        {
                                log.log(Level.SEVERE, null, ex);
                        }
                        
                }
                else if (newState == HttpConnection.STATE.UPGRADE)
                {
                        conn.key.attach(null);
                        conn.key.cancel();

                        if (conn.websocket && upgradeWebSocketHandler != null)
                        {
                                ByteBuffer rawHead = conn.rawHead;
                                conn.rawHead = null; // make sure nothing is able to interfere
                                rawHead.flip();
                                upgradeWebSocketHandler.upgradeWebSocketHandler(conn.channel, rawHead);
                        }
                        else
                        {
                                try
                                {
                                        conn.channel.close();
                                }
                                catch (IOException ex)
                                {
                                        log.log(Level.SEVERE, null, ex);
                                }
                        }
                }
        }
        
        public void startWaitReady()
        {
                this.start();
                while (!ready)
                {
                        try
                        {
                                Thread.sleep(1);
                        }
                        catch (InterruptedException ex)
                        {
                                Thread.currentThread().interrupt();
                                return;
                        }
                }
        }
        
        static interface UpgradeWebSocketHandler { void upgradeWebSocketHandler(SocketChannel sChannel, ByteBuffer prependData); }

        @Override
        public void run()
        {
                running = true;
                setName("HttpDownload-"+getId());
                try
                {
                        selector = Selector.open();
                        ready = true;

                        while (!this.isInterrupted())
                        {
                                try
                                {
                                        selector.select(500);
                                }
                                catch (ClosedSelectorException ex)
                                {
                                        break;
                                }
                                
                                {
                                        SocketChannel sChannel = newChannels.poll();
                                        if (sChannel != null)
                                        {
                                                sChannel.configureBlocking(false);
                                                sChannel.socket().setTcpNoDelay(false);
                                                SelectionKey key = sChannel.register(selector, SelectionKey.OP_READ);
                                                key.attach(new HttpConnection(this, key, sChannel, defaultRoute, routes));
                                        }
                                }
                                
                                Iterator<SelectionKey> it;
                                
                                long now = System.nanoTime();
                                
                                if (now - lastTimeoutCheck > 1_000_000_000l)
                                {
                                        lastTimeoutCheck = now;
                                        it = selector.keys().iterator();
                                
                                        while (it.hasNext())
                                        {
                                                SelectionKey key = it.next();
                                                HttpConnection conn = (HttpConnection) key.attachment();
                                                if (now - conn.nanoLastReceived > HttpServer.HTTP_TIMEOUT * 1_000_000_000l)
                                                {
                                                        log.log(Level.INFO, "Dropping connection {0} because of timeout", conn.channel.getRemoteAddress());
                                                        key.attach(null);
                                                        key.cancel();
                                                        conn.channel.close();
                                                        conn.closed();
                                                }
                                        }
                                }

                                it = selector.selectedKeys().iterator();

                                while (it.hasNext())
                                {
                                        SelectionKey key = it.next();

                                        HttpConnection conn = (HttpConnection) key.attachment();

                                        it.remove();
                                        
                                        if (conn == null)
                                        {
                                                // was just removed by a timeout
                                                continue;
                                        }
                                        
                                        try
                                        {

                                                if (key.isReadable())
                                                {
                                                        if (!readable(conn))
                                                        {
                                                                key.attach(null);
                                                                key.cancel();
                                                                conn.channel.close();
                                                                conn.closed();
                                                                continue;
                                                        }
                                                }

                                                if (key.isValid() && key.isWritable())
                                                {
                                                        conn.writeable();
                                                }
                                        }
                                        catch (IOException ex)
                                        {
                                                log.log(Level.WARNING, null, ex);
                                                
                                                key.attach(null);
                                                key.cancel();
                                                conn.channel.close();
                                                conn.closed();
                                        }
                                }
                        }
                        
                        selector.close();
                        selector = null;
                }
                catch (IOException ex)
                {
                        log.log(Level.SEVERE, null, ex);
                }
        }
        
        /** @return false if this connection should be removed */
        private boolean readable(HttpConnection conn) throws IOException
        {
                buf.clear();

                // start reading at LINEBUFFER_SIZE so that the previous line can be prepended
                buf.limit(buf.capacity());
                buf.position(HttpServer.LINEBUFFER_SIZE);
                buf.mark();

                int read;

                try
                {
                        read = conn.channel.read(buf);
                }
                catch (ClosedChannelException ex)
                {
                        return false;
                }

                if (read < 0)
                {
                        return false;
                }

                if (read > 0)
                {
                        buf.limit(buf.position());
                        buf.reset();

                        conn.read(buf);
                }
                
                return true;
        }
        

        /**
         * Add a new socket channel to be handled by this thread.
         */
        
        @ThreadSafe
        void addNewChannel(SocketChannel sChannel) throws IOException
        {
                newChannels.add(sChannel);
                
                try
                {
                        selector.wakeup();
                }
                catch (IllegalStateException | NullPointerException ex)
                {
                        // Thread has not started yet, or it just stopped
                        assert false;
                }
        }
}
