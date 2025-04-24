//package com.example.distributeddownload;

import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Manages active client connections for tracking and potential forced closure
 * (simulating failures).
 */
public class ConnectionManager {
    private static final Logger logger = Logger.getLogger(ConnectionManager.class.getName());
    private final List<Socket> activeConnections = new ArrayList<>();
    private final Random random = new Random();

    /**
     * Adds a socket to the list of active connections.
     */
    public synchronized void addConnection(Socket socket) {
        activeConnections.add(socket);
        logger.info("Connection added: " + socket.getRemoteSocketAddress());
    }

    /**
     * Removes a socket from the list of active connections.
     */
    public synchronized void removeConnection(Socket socket) {
        activeConnections.remove(socket);
        logger.info("Connection removed: " + socket.getRemoteSocketAddress());
    }

    /**
     * Closes a random active connection to simulate a failure.
     */
    public synchronized void closeRandomConnection() {
        if (activeConnections.isEmpty()) {
            return;
        }
        int idx = random.nextInt(activeConnections.size());
        Socket socket = activeConnections.get(idx);
        try {
            logger.warning("Closing connection to simulate failure: " + socket.getRemoteSocketAddress());
            socket.close();
        } catch (Exception e) {
            logger.warning("Error closing socket: " + e.getMessage());
        }
        activeConnections.remove(socket);
    }
}
