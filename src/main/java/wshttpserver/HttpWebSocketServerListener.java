package wshttpserver;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;


public interface HttpWebSocketServerListener
{
        /**
         * Returns whether a new connection shall be accepted or not.<br> Therefore method is well suited to implement
         * some kind of connection limitation.<br>
         *
         * @param key 
         * @return true if the connection should be accepted
         * @see
         * {@link #onOpen(WebSocket, ClientHandshake)}, {@link #onWebsocketHandshakeReceivedAsServer(WebSocket, Draft, ClientHandshake)}
	 *
         */
        @ThreadSafe
        public boolean wssConnect(SelectionKey key);
        
        /**
         * Called after an opening handshake has been performed and the given websocket is ready to be written on.
         * @param ws
         * @param handshake  
         */
        @ThreadSafe
        public void wssOpen(WebSocket ws, ClientHandshake handshake);
        
        /**
         * Called after the websocket connection has been closed.
         *
         * @param ws 
         * @param code The codes can be looked up here: {@link CloseFrame}
         * @param reason Additional information string
         * @param remote Returns whether or not the closing of the connection was initiated by the remote host.
	 *
         */
        @ThreadSafe
        public void wssClose(WebSocket ws, int code, String reason, boolean remote);
        
         /**
         * Callback for string messages received from the remote host
         *
         * @param ws 
         * @param message 
         * @see #onMessage(WebSocket, ByteBuffer)
	 *
         */
        @ThreadSafe
        public void wssMessage(WebSocket ws, String message);
        
        /**
         * Callback for binary messages received from the remote host
         *
         * @param ws 
         * @param message 
         * @see #onMessage(WebSocket, String)
	 *
         */
        @ThreadSafe
        public void wssMessage(WebSocket ws, ByteBuffer message);

        /**
         * Called when errors occurs. If an error causes the websocket connection to fail
         * {@link #onClose(WebSocket, int, String, boolean)} will be called additionally.<br> This method will be called
         * primarily because of IO or protocol errors.<br> If the given exception is an RuntimeException that probably
         * means that you encountered a bug.<br>
         * 
         * @param ws Can be null if there error does not belong to one specific websocket. For example if the servers
         * port could not be bound.
	 * @param ex 
         */
        @ThreadSafe
        public void wssError(WebSocket ws, Exception ex);
}
