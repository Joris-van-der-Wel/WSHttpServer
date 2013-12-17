package wshttpserver;

import wshttpserver.HttpDateUtils.DateParseException;
import wshttpserver.HttpUtil.METHOD;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Date;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;

/**
 * @author Joris
 */
class HttpResponse
{
        private static final Logger log = Logger.getLogger("wshttpserver");
        METHOD requestMethod;
        HashMap<String, String> requestHeaders;
        int status;
        String statusMessage;
        boolean sendStatusAsContent = true;
        boolean close;
        File file;
        ByteBuffer headers;
        RandomAccessFile raf;
        long fileBytesSent = 0;
        ByteBuffer fileBuffer;
        boolean range = false;
        long rangeStart = 0;
        long rangeEnd = 0;
        long rangeLength = 0;

        HttpResponse(METHOD requestMethod, HashMap<String, String> requestHeaders, int status, String statusMessage, boolean close, File file)
        {
                this.requestMethod = requestMethod;
                this.requestHeaders = requestHeaders;
                this.status = status;
                this.statusMessage = statusMessage;
                this.close = close;
                this.file = file;
        }

        public void prepare()
        {
                assert this.headers == null;
                long fileLength = 0;

                StringBuilder headerString = new StringBuilder();

                boolean sendFile = false;
                
                if (file != null)
                {
                        if (file.isDirectory())
                        {
                                file = HttpUtil.findDirectoryIndex(file);
                                if (file != null)
                                {
                                        sendFile = true;        
                                }
                        }
                        else if (file.isFile())
                        {
                                sendFile = true;
                        }
                        else
                        {
                                if (status == 200)
                                {
                                        status = 404;
                                        statusMessage = "File Not Found";
                                }
                        }
                }
                
                Date lastModified = null;
                
                if (sendFile)
                {
                        lastModified = new Date(file.lastModified());
                        
                        String ifModifiedSince = requestHeaders.get("if-modified-since");
                        if (ifModifiedSince != null)
                        {
                                try
                                {
                                        Date ifModifiedSinceDate = HttpDateUtils.parseDate(ifModifiedSince);
                                        
                                        if (lastModified.after(ifModifiedSinceDate))
                                        {
                                                sendFile = false;
                                                
                                                status = 304;
                                                statusMessage = "Not Modified";
                                                sendStatusAsContent = false;
                                        }
                                }
                                catch (DateParseException ex)
                                {
                                }
                        }
                }
                
                if (sendFile)
                {
                        sendFile = false;
                        
                        try
                        {
                                raf = new RandomAccessFile(file, "r");
                                fileLength = raf.length();
                                sendFile = true;
                        }
                        catch (FileNotFoundException ex)
                        {
                                try
                                {
                                        raf.close();
                                }
                                catch (IOException ex2)
                                {
                                }

                                raf = null;
                                if (status == 200)
                                {
                                        status = 404;
                                        statusMessage = "File Not Found";
                                }
                                log.log(Level.INFO, "File not found", ex);
                        }
                        catch (IOException ex)
                        {
                                try
                                {
                                        raf.close();
                                }
                                catch (IOException ex2)
                                {
                                }

                                raf = null;
                                if (status == 200)
                                {
                                        status = 404;
                                        statusMessage = "Error reading file";
                                }
                                log.log(Level.SEVERE, "Error reading file", ex);
                        }

                        String rangeValue = requestHeaders.get("range");


                        if (sendFile && rangeValue != null)
                        {
                                // only 1 range is supported
                                Matcher rangeMatcher = HttpUtil.simpleRange.matcher(rangeValue);
                                if (rangeMatcher.matches())
                                {
                                        range = true;
                                        try
                                        {
                                                String start = rangeMatcher.group(1);
                                                String end = rangeMatcher.group(2);

                                                if (start != null && start.isEmpty())
                                                {
                                                        start = null;
                                                }
                                                if (end != null && end.isEmpty())
                                                {
                                                        end = null;
                                                }


                                                if (start == null && end == null)
                                                {
                                                        throw new NumberFormatException(); // invalid range
                                                }

                                                // The final 500 bytes (byte offsets 9500-9999, inclusive): bytes=-500
                                                if (start == null)
                                                {
                                                        rangeStart = fileLength - Long.parseLong(end, 10);
                                                        rangeEnd = fileLength - 1;
                                                }
                                                // Or bytes=9500-
                                                else if (end == null)
                                                {
                                                        rangeStart = Long.parseLong(start, 10);
                                                        rangeEnd = fileLength - 1;
                                                }
                                                else
                                                {
                                                        rangeStart = Long.parseLong(start, 10);
                                                        rangeEnd = Long.parseLong(end, 10);
                                                }

                                                if (rangeEnd > fileLength)
                                                {
                                                        rangeEnd = fileLength - 1;
                                                }

                                                if (rangeEnd < rangeStart)
                                                {
                                                        range = false;
                                                }

                                                rangeLength = rangeEnd - rangeStart + 1;

                                                raf.seek(rangeStart);
                                        }
                                        catch (NumberFormatException ex)
                                        {
                                                log.log(Level.WARNING, "", ex);
                                                range = false;
                                        }
                                        catch (IOException ex)
                                        {
                                                range = false;
                                        }
                                }
                        }

                        if (range)
                        {
                                status = 206;
                        }
                }

                // HTTP/1.1 200 OK\r\n
                headerString.append("HTTP/1.1 ");
                headerString.append(status);
                headerString.append(" ");
                headerString.append(statusMessage);
                headerString.append("\r\n");

                if (close)
                {
                        headerString.append("Connection: close\r\n");
                }

                if (status == 405)
                {
                        headerString.append("Allow: GET, HEAD\r\n");
                }

                headerString.append("Server: Aphelion\r\n");
                headerString.append("X-Frame-Options: SAMEORIGIN\r\n");
                headerString.append("Date: ");
                headerString.append(HttpDateUtils.formatDate(new Date()));
                headerString.append("\r\n");

                if (!sendFile)
                {
                        headerString.append("Content-Type: text/plain; charset=UTF-8\r\n");
                        
                        if (sendStatusAsContent)
                        {
                                headerString.append("Content-Length: ");
                                headerString.append(HttpUtil.binarySizeUTF8(statusMessage));
                                headerString.append("\r\n");
                        }

                        headerString.append("\r\n"); // end of headers

                        if (requestMethod != METHOD.HEAD)
                        {
                                if (sendStatusAsContent)
                                {
                                        headerString.append(statusMessage);
                                }
                        }
                }
                else
                {


                        if (range)
                        {
                                headerString.append("Content-Range: bytes ");
                                headerString.append(rangeStart);
                                headerString.append("-");
                                headerString.append(rangeEnd);
                                headerString.append("/");
                                headerString.append(fileLength);
                                headerString.append("\r\n");
                                headerString.append("Content-Length: ");
                                headerString.append(rangeEnd - rangeStart + 1);
                                headerString.append("\r\n");
                        }
                        else
                        {
                                headerString.append("Content-Length: ");
                                headerString.append(fileLength);
                                headerString.append("\r\n");
                        }
                        
                        assert lastModified != null;
                        headerString.append("Last-Modified: ");
                        headerString.append(HttpDateUtils.formatDate(lastModified));
                        headerString.append("\r\n");
                        
                        headerString.append("Content-Type: ");
                        headerString.append(HttpMime.getMime(file));
                        headerString.append("\r\n");
                        
                        headerString.append("Accept-Ranges: bytes\r\n");
                        headerString.append("\r\n");
                }

                //System.out.println(headerString.toString());
                this.headers = ByteBuffer.wrap(headerString.toString().getBytes(HttpUtil.UTF8));
        }

        /**
         * Attempt to write some http resonse stuff on a socket channel.
         *
         * @return true if there is nothing more to write
         */
        public boolean write(SocketChannel channel) throws IOException
        {
                if (headers != null)
                {
                        if (headers.hasRemaining())
                        {
                                if (channel.write(headers) < 0)
                                {
                                        throw new IOException("closed");
                                }
                        }

                        if (headers.hasRemaining()) // unable to write further, try again later
                        {
                                return false;
                        }
                        else
                        {
                                headers = null;
                        }
                }

                if (raf != null)
                {
                        if (fileBuffer == null)
                        {
                                fileBuffer = ByteBuffer.wrap(new byte[1024]);
                                fileBuffer.position(fileBuffer.limit()); // so that hasRemaining returns false
                        }

                        while (true)
                        {
                                if (!fileBuffer.hasRemaining())
                                {
                                        int read = raf.read(fileBuffer.array());
                                        if (read == -1)
                                        {
                                                raf.close();
                                                return true;
                                        }
                                        fileBuffer.position(0);
                                        fileBuffer.limit(read);
                                }

                                if (fileBuffer.hasRemaining())
                                {
                                        if (range)
                                        {
                                                long limit = rangeLength - fileBytesSent;
                                                if (fileBuffer.limit() > limit)
                                                {
                                                        fileBuffer.limit((int) limit);
                                                }
                                        }

                                        fileBytesSent += channel.write(fileBuffer);

                                        if (range && fileBytesSent >= rangeLength)
                                        {
                                                raf.close();
                                                return true; // done
                                        }

                                        if (fileBuffer.hasRemaining()) // unable to write further, try again later
                                        {
                                                return false;
                                        }
                                }
                        }
                }

                return true; // done
        }
}
