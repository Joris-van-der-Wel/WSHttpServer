package wshttpserver;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author Joris
 */
public class HttpUtilTest
{
        public HttpUtilTest()
        {
        }

        @Test
        public void testBinarySizeUTF8()
        {
                testBinarySizeUTF8_string("$");
                testBinarySizeUTF8_string("¢");
                testBinarySizeUTF8_string("€");
                testBinarySizeUTF8_string("$Â¢â‚¬");
                testBinarySizeUTF8_string("\uD834\uDD1E");
        }
        
        private void testBinarySizeUTF8_string(String test)
        {
                assertEquals(test.getBytes(Charset.forName("UTF-8")).length, HttpUtil.binarySizeUTF8(test));
        }
        
        @Test
        public void testFindCRLF()
        {
                ByteBuffer buf = ByteBuffer.allocate(100);
                buf.put((byte) 't');
                buf.put((byte) 'e');
                buf.put((byte) 's');
                buf.put((byte) 't');
                buf.put((byte) '\r');
                buf.put((byte) '\n');
                buf.put((byte) 'b');
                buf.put((byte) 'l');
                buf.put((byte) 'a');
                buf.flip();
                assertEquals(4, HttpUtil.findCRLF(buf, 0));
                assertEquals(4, HttpUtil.findCRLF(buf, 1));
                assertEquals(4, HttpUtil.findCRLF(buf, 2));
                assertEquals(4, HttpUtil.findCRLF(buf, 3));
                assertEquals(4, HttpUtil.findCRLF(buf, 4));
                assertEquals(-1, HttpUtil.findCRLF(buf, 5));
                
                buf.clear();
                buf.put((byte) '\r');
                buf.put((byte) '\n');
                buf.flip();
                assertEquals(0, HttpUtil.findCRLF(buf, 0));
                assertEquals(-1, HttpUtil.findCRLF(buf, 1));
                
        }
        
        @Test
        public void testFindCRLFIgnoreLWS()
        {
                ByteBuffer buf = ByteBuffer.allocate(100);
                buf.put((byte) 't'); //0
                buf.put((byte) 'e'); //1
                buf.put((byte) 's'); //2
                buf.put((byte) 't'); //3
                buf.put((byte) '\r');//4
                buf.put((byte) '\n');//5
                buf.put((byte) ' '); //6
                buf.put((byte) 'a'); //7
                buf.put((byte) 'b'); //8
                buf.put((byte) 'c'); //9
                buf.put((byte) '\r');//10
                buf.put((byte) '\n');//11
                buf.put((byte) '\r');//12
                buf.put((byte) '\n');//13
                buf.flip();
                
                assertEquals(10, HttpUtil.findCRLFIgnoreLWS(buf, 0));
                assertEquals(10, HttpUtil.findCRLFIgnoreLWS(buf, 3));
                assertEquals(10, HttpUtil.findCRLFIgnoreLWS(buf, 4));
                assertEquals(10, HttpUtil.findCRLFIgnoreLWS(buf, 5));
                assertEquals(10, HttpUtil.findCRLFIgnoreLWS(buf, 6));
                assertEquals(10, HttpUtil.findCRLFIgnoreLWS(buf, 9));
                assertEquals(10, HttpUtil.findCRLFIgnoreLWS(buf, 10));
                assertEquals(-1, HttpUtil.findCRLFIgnoreLWS(buf, 11));
                
                buf.clear();
                buf.put((byte) '\r');
                buf.put((byte) '\n');
                buf.put((byte) 'C');
                buf.flip();
                assertEquals(0, HttpUtil.findCRLFIgnoreLWS(buf, 0));
                assertEquals(-1, HttpUtil.findCRLFIgnoreLWS(buf, 1));
                
        }
        
        
        @Test
        public void testReadLine()
        {
                StringBuilder dest = new StringBuilder();
                
                ByteBuffer buf = ByteBuffer.allocate(100);
                buf.put((byte) 't');
                buf.put((byte) 'e');
                buf.put((byte) 's');
                buf.put((byte) 't');
                buf.put((byte) '\r');
                buf.put((byte) '\n');
                buf.put((byte) ' ');
                buf.put((byte) 'b');
                buf.put((byte) 'l');
                buf.put((byte) 'a');
                buf.put((byte) '\r');
                buf.put((byte) '\n');
                buf.put((byte) '\r');
                buf.put((byte) '\n');
                buf.flip();
                
                dest.setLength(0);
                assertTrue(HttpUtil.readLine(dest, buf, false));
                assertEquals("test", dest.toString());
                
                dest.setLength(0);
                assertTrue(HttpUtil.readLine(dest, buf, false));
                assertEquals(" bla", dest.toString());
                
                dest.setLength(0);
                assertTrue(HttpUtil.readLine(dest, buf, false));
                assertEquals("", dest.toString());
                
                
                buf.position(0);
                
                dest.setLength(0);
                assertTrue(HttpUtil.readLine(dest, buf, true));
                assertEquals("test\r\n bla", dest.toString());
                
                dest.setLength(0);
                assertFalse(HttpUtil.readLine(dest, buf, true));
        }
}
