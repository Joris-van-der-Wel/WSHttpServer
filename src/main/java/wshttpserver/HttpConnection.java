package wshttpserver;

import wshttpserver.HttpUtil.HttpException;
import wshttpserver.HttpUtil.METHOD;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.file.NoSuchFileException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;

/**
 *
 * @author Joris
 */
class HttpConnection
{
        private static final Logger log = Logger.getLogger("wshttpserver");
        private final int RAWHEAD_SIZE = 512;
        
        ConnectionStateChangeListener stateChangeListener;
        SelectionKey key;
        SocketChannel channel;
        File defaultRoute;
        Map<String, File> routes;
        
        long nanoLastReceived;
        private final LinkedList<HttpResponse> responses = new LinkedList<>(); // responses that still have to be sent out
        private HttpResponse currentResponse; // the response that is currently being sent;
        private boolean keepAlive;
        
        // Data about the current state (remember that multiple request may be made per connection):
        STATE state;
        METHOD method;
        URI requestUri;
        int clientHttpMinor; // The minor http version of the request. Aka 123 in HTTP/1.123
        boolean websocket = false;
        private ByteBuffer lineBuffer;
        ByteBuffer rawHead; // The bytes of the entire head (request-line and headers)
        HashMap<String, String> headers = new HashMap<>();

        static enum STATE
        {
                WAIT_FOR_REQUEST_LINE, // Just accepted the connection, waiting for http reponse
                READING_HEADERS, // read the http version, reading the headers

                // Data for this request is sent during/after these states:
                DONE_READING,
                UPGRADE, // This connection is being upgraded to a websocket, no further parsing by this object
                BAD_REQUEST, // Client sent a bad request. Ignore everything the client sends. The connection is closing.
                CLOSED;
        }
       
        
        HttpConnection(ConnectionStateChangeListener stateChangeListener, SelectionKey key, SocketChannel sChannel, File defaultRoute, Map<String, File> routes)
        {
                this.stateChangeListener = stateChangeListener;
                this.key = key;
                this.channel = sChannel;
                this.defaultRoute = defaultRoute;
                this.routes = routes;

                setState(STATE.WAIT_FOR_REQUEST_LINE);

                nanoLastReceived = System.nanoTime();

                try
                {
                        log.log(Level.INFO, "New TCP Connection: {0}", sChannel.getRemoteAddress());
                }
                catch (IOException | NullPointerException ex)
                {
                        log.log(Level.SEVERE, null, ex);
                }
        }

        // https://www.rfc-editor.org/rfc/rfc2616.txt
        @SuppressWarnings("unchecked")
        public void read(ByteBuffer buf) throws IOException
        {
                nanoLastReceived = System.nanoTime();

                //log.log(Level.INFO, buf.position() + ":" + buf.limit() + ":{0};", dumpBuffer(buf, false));

                if (state == STATE.CLOSED || state == STATE.BAD_REQUEST || state == STATE.UPGRADE)
                {
                        return;
                }

                while (buf.hasRemaining())
                {
                        boolean requestReady = false;
                        try
                        {
                                requestReady = readHttpRequest(buf);
                        }
                        catch (HttpException ex)
                        {
                                addResponse(new HttpResponse(method, (HashMap<String, String>) headers.clone(), ex.status, ex.getMessage(), ex.fatal || !this.keepAlive, null));

                                if (ex.fatal)
                                {
                                        setState(STATE.BAD_REQUEST);
                                }
                                else
                                {
                                        setState(STATE.WAIT_FOR_REQUEST_LINE);
                                }

                                log.log(Level.SEVERE, null, ex);
                        }
                        
                        if (requestReady)
                        {
                                File file = null;
                                
                                try
                                {
                                        file = getRoute(requestUri.getPath());
                                }
                                catch (NoSuchFileException ex)
                                {
                                        log.log(Level.INFO, "No such file: ", ex.getMessage());
                                }
                                
                                if (file == null)
                                {
                                        addResponse(new HttpResponse(method, (HashMap<String, String>)headers.clone(), 404, "File Not Found", !this.keepAlive, null));
                                }
                                else
                                {
                                        addResponse(new HttpResponse(method, (HashMap<String, String>)headers.clone(), 200, "Okay!", !this.keepAlive, file));
                                }
                                
                                // this clears our current header info, etc
                                if (this.keepAlive)
                                {
                                        setState(STATE.WAIT_FOR_REQUEST_LINE);
                                }
                                else
                                {
                                        setState(STATE.CLOSED);
                                }
                        }

                }
        }
        
        private File getRoute(String requestPath) throws NoSuchFileException, IOException
        {
                if (requestPath == null || requestPath.isEmpty())
                {
                        return defaultRoute;
                }
                
                
                int start = 0;
                while (start < requestPath.length() && requestPath.charAt(start) == '/')
                {
                        ++start;
                }
                
                int len = requestPath.length();
                while (len >= start && requestPath.charAt(len-1) == '/')
                {
                        --len;
                }
                
                if (start == len)
                {
                        return defaultRoute;
                }
                
                
                while (len >= start)
                {
                        // /a/b/c/d/e.txt
                        // first try "/a/b/c/d/e.txt"
                        // then try "/a/b/c/d"
                        // then try "/a/b/c" etc
                        
                        File routeFile = routes.get(requestPath.substring(start, len));
                        
                        if (routeFile == null)
                        {
                                len = requestPath.lastIndexOf('/', len-1);
                                continue;
                        }
                        
                        String remainingPath = requestPath.substring(len);
                        
                        File file = remainingPath.length() > 0 
                                    ? new File(routeFile.getPath() + File.separator + remainingPath) 
                                    : routeFile;
                        
                        file = file.getCanonicalFile();
                        if (!file.getPath().startsWith(routeFile.getPath()))
                        {
                                log.log(Level.WARNING, "Attempt to access file outside of the route directory");
                                throw new NoSuchFileException(file.getPath());
                        }

                        return file;

                }
                

                if (defaultRoute == null)
                {
                        throw new NoSuchFileException("defaultRoute not set");
                }

                File file = new File(defaultRoute.getPath() + File.separator + requestPath);
                file = file.getCanonicalFile();

                if (!file.getPath().startsWith(defaultRoute.getPath()))
                {
                        log.log(Level.WARNING, "Attempt to access file outside of the route directory");
                        throw new NoSuchFileException(file.getPath());
                }
                
                return file;
        }
        
        private void addResponse(HttpResponse resp)
        {
                responses.add(resp);
                key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }

        /**
         * @return true if the reqeust has been fully read and a response may be sent
         */
        private boolean readHttpRequest(ByteBuffer buf) throws HttpException
        {
                /*
                 * Request       = Request-Line              ; Section 5.1
                 *                 *(( general-header        ; Section 4.5
                 *                  | request-header         ; Section 5.3
                 *                  | entity-header ) CRLF)  ; Section 7.1
                 *                 CRLF
                 *                 [ message-body ]          ; Section 4.3
                 */



                if (lineBuffer != null && lineBuffer.limit() > 0)
                {
                        assert lineBuffer.position() == 0 : "lineBuffer should have been flip()ed";
                        int pos = buf.position() - lineBuffer.limit();
                        buf.position(pos);
                        buf.put(lineBuffer);
                        lineBuffer.clear();
                        buf.position(pos);
                }

                //log.log(Level.INFO, buf.position() + ":" + buf.limit() + ":{0};", dumpBuffer(buf, false));

                if (state == STATE.WAIT_FOR_REQUEST_LINE)
                {
                        readRequestLine(buf);
                }

                // not "else"!
                if (state == STATE.READING_HEADERS)
                {
                        

                        readHeaders(buf);
                }

                if (state == STATE.DONE_READING)
                {
                        if ("websocket".equalsIgnoreCase(headers.get("upgrade")))
                        {
                                websocket = true;
                                setState(STATE.UPGRADE);
                                return false;
                        }
                        
                        if (method != METHOD.GET && method != METHOD.HEAD)
                        {
                                throw new HttpException(405, true, "Method Not Allowed");
                        }
                        
                        
                        if (headers.containsKey("content-length"))
                        {
                                // POST, OPTIONS, etc is not supported
                                throw new HttpException(400, true, "Request body is not allowed for this method");
                        }

                        //log.log(Level.SEVERE, "Remaining in buffer:{0}", dumpBuffer(buf, true));



                        return true;
                }

                // Some line that spans multiple socket reads() 
                if (buf.hasRemaining())
                {
                        if (state == STATE.WAIT_FOR_REQUEST_LINE || state == STATE.READING_HEADERS)
                        {
                                if (lineBuffer == null)
                                {
                                        lineBuffer = ByteBuffer.allocate(HttpServer.LINEBUFFER_SIZE);
                                }

                                try
                                {
                                        lineBuffer.put(buf);
                                        lineBuffer.flip();
                                }
                                catch (BufferOverflowException ex)
                                {
                                        throw new HttpException(413, true, "Line in Request-Entity is too Large", ex);
                                }
                        }
                }

                return false;
        }
        
        private void ensureRawHeadHasRemaining(int remaining)
        {
                if (rawHead == null)
                {
                        // ensure the buffer is a multiple of RAWHEAD_SIZE
                        rawHead = ByteBuffer.allocate((remaining / RAWHEAD_SIZE + 1) * RAWHEAD_SIZE);
                }
                else if (remaining > rawHead.remaining())
                {
                        int newCap = rawHead.capacity();
                        newCap += remaining - rawHead.remaining();
                        newCap = (newCap / RAWHEAD_SIZE + 1) * RAWHEAD_SIZE;
                        
                        ByteBuffer newBuf = ByteBuffer.allocate(newCap);
                        
                        rawHead.flip();
                        newBuf.put(rawHead);
                        newBuf.limit(newBuf.capacity());
                        rawHead = newBuf;
                        
                        assert rawHead.remaining() >= remaining;
                }
        }

        private void rawHead_putToPos(ByteBuffer buf, int beforeLinePosition)
        {
                int position = buf.position();
                int limit = buf.limit();

                // set the buffer to the line that was just read
                buf.position(beforeLinePosition);
                buf.limit(position);
                
                ensureRawHeadHasRemaining(buf.remaining());
                
                // add it to rawHead
                rawHead.put(buf);

                // reset buf to what it was before
                buf.position(position);
                buf.limit(limit);
        }
        
        /**
         * Read the request line and move the buffer position beyond the request line
         */
        private void readRequestLine(ByteBuffer buf) throws HttpException
        {
                StringBuilder line = new StringBuilder();

                try
                {
                        while (true)
                        {
                                line.setLength(0);
                                
                                int beforeLinePosition = buf.position();
                                
                                if (!HttpUtil.readLine(line, buf, false))
                                {
                                        return;
                                }
                                
                                rawHead_putToPos(buf, beforeLinePosition);

                                if (line.length() == 0)
                                {
                                        // ignore empty line before request line
                                        continue;
                                }

                                //System.out.print("*********** ");
                                //System.out.print(line);
                                //System.out.println(" ***********;");

                                //  HTTP-Version   = "HTTP" "/" 1*DIGIT "." 1*DIGIT
                                // Also see https://tools.ietf.org/html/rfc2145

                                if (line.length() > 300)
                                {
                                        throw new HttpException(414, true, "Request-Line Too Long");
                                }
                                else
                                {
                                        Matcher requestLine = HttpUtil.requestLine.matcher(line.subSequence(0, line.length()));
                                        if (requestLine.matches())
                                        {
                                                method = METHOD.fromRequestLine(requestLine.group(1));

                                                if (method == METHOD.UNKNOWN)
                                                {
                                                        throw new HttpException(501, true, "Unknown Method");
                                                }

                                                String uri = requestLine.group(2);
                                                if (uri.length() > 255)
                                                {
                                                        throw new HttpException(414, true, "Request-URI Too Long");
                                                }
                                                else
                                                {
                                                        requestUri = new URI(uri);
                                                        this.clientHttpMinor = Integer.parseInt(requestLine.group(3), 10);
                                                }
                                        }
                                        else
                                        {
                                                throw new HttpException(400, true, "Invalid Request-Line (regexp)");
                                        }

                                        setState(STATE.READING_HEADERS);
                                        return;
                                }
                        }
                }
                catch (IndexOutOfBoundsException ex)
                {
                        throw new HttpException(400, true, "Invalid Request-Line (iob)", ex);
                }
                catch (NumberFormatException ex)
                {
                        throw new HttpException(400, true, "Invalid Request-Line. Expected integer", ex);
                }
                catch (URISyntaxException ex)
                {
                        throw new HttpException(400, true, "Invalid Request-Line. Malformed URI", ex);
                }
        }

        private void readHeaders(ByteBuffer buf) throws HttpException
        {
                StringBuilder line = new StringBuilder();
                try
                {
                        while (true)
                        {
                                line.setLength(0);
                                
                                int beforeLinePosition = buf.position();
                                if (!HttpUtil.readLine(line, buf, true))
                                {
                                        return;
                                }

                                rawHead_putToPos(buf, beforeLinePosition);
                                
                                //System.out.println(">" + line);



                                Matcher headerLine = HttpUtil.headerLine.matcher(line.subSequence(0, line.length()));
                                if (headerLine.matches())
                                {
                                        String name = headerLine.group(1);
                                        String value = headerLine.group(2);
                                        // todo: multiple headers with the same name
                                        headers.put(name.toLowerCase(), value.trim());
                                        if (headers.size() > 50)
                                        {
                                                throw new HttpException(400, true, "Too many headers");
                                        }
                                }
                                else
                                {
                                        throw new HttpException(400, true, "Invalid message-header (regexp)");
                                }

                                if (buf.remaining() >= 2)
                                {
                                        // \r\n\r\n
                                        if (HttpUtil.isCR(buf.get(buf.position()))
                                                && HttpUtil.isLF(buf.get(buf.position() + 1)))
                                        {
                                                ensureRawHeadHasRemaining(2);
                                                
                                                // .get() also moves the position
                                                rawHead.put(buf.get());
                                                rawHead.put(buf.get());
                                                
                                                setState(STATE.DONE_READING);
                                                return;
                                        }
                                }
                        }
                }
                catch (IndexOutOfBoundsException ex)
                {
                        throw new HttpException(400, true, "Invalid message-header (iob)", ex);
                }
        }

        private void setState(STATE newState)
        {
                if (this.state == newState)
                {
                        return;
                }

                STATE oldState = this.state;
                this.state = newState;

                if (newState == STATE.DONE_READING)
                {
                        if (this.clientHttpMinor > 0)
                        {
                                this.keepAlive = "keep-alive".equals(headers.get("connection"));
                                //log.log(Level.INFO, "Keep-alive enabled");
                        }
                        else
                        {
                                this.keepAlive = false;
                        }
                }
                
                stateChangeListener.connectionStateChange(this, oldState, newState);
                
                // clear state variables
                // do not clear when the new state is UPGRADE!
                if (newState == STATE.WAIT_FOR_REQUEST_LINE || newState == STATE.CLOSED || newState == STATE.BAD_REQUEST)
                {
                        this.requestUri = null;
                        this.clientHttpMinor = 0;
                        this.websocket = false;
                        
                        if (this.lineBuffer != null)
                        {
                                this.lineBuffer.clear();
                        }

                        if (this.rawHead != null)
                        {
                                this.rawHead.clear();
                        }
                        
                        this.headers.clear();
                        this.keepAlive = false;
                }
        }
        
        
        // channel is ready to write more
        public void writeable() throws IOException
        {
                while (!responses.isEmpty() || currentResponse != null)
                {
                        if (currentResponse == null)
                        {
                                currentResponse = responses.removeFirst();
                                currentResponse.prepare();
                        }

                        if (currentResponse.write(channel))
                        {
                                if (currentResponse.close)
                                {
                                        log.log(Level.INFO, "Closing... {0}:{1}", new Object[]{currentResponse.close, this.keepAlive});
                                        setState(STATE.CLOSED);
                                        channel.close(); // TODO: does this immediately clear the outgoing buffer?
                                }

                                // the response is done writing
                                currentResponse = null;
                        }
                        else
                        {
                                return; // the outgoing buffer is full
                        }

                }
                
                // nothing more to write
                key.interestOps(SelectionKey.OP_READ);
        }

        public void closed()
        {
                setState(STATE.CLOSED);
        }
        
        public static interface ConnectionStateChangeListener
        {
                public void connectionStateChange(HttpConnection conn, STATE oldState, STATE newState);
        }
}
