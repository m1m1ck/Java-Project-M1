//package com.example.distributeddownload;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Entry point for launching the server. This class configures the server,
 * initializes components, and listens for incoming client connections.
 */
public class Server {
    private static final Logger logger = Logger.getLogger(Server.class.getName());

    public static void main(String[] args) {
        // 1. Parse command-line arguments into a ServerConfig object
        ServerConfig config = ServerConfig.fromArgs(args);

        // 2. Log server start and parameters
        logger.info("Server starting with parameters: " + config);

        // 3. Create FileStorage to manage available files (MD5, etc.)
        FileStorage fileStorage = new FileStorage(config.getFilesDirectory());
        fileStorage.init(); // Currently a stub method

        // 4. Create a fixed thread pool with a maximum of Cs threads
        ExecutorService executor = Executors.newFixedThreadPool(config.getCs());

        // (Optional) Create a ConnectionManager instance if needed
        // ConnectionManager connectionManager = new ConnectionManager();

        // 5. Start the ServerSocket and begin listening on the specified port
        try (ServerSocket serverSocket = new ServerSocket(config.getPort())) {
            logger.info("Server is listening on port: " + config.getPort());

            // 6. Main loop to accept client connections
            while (true) {
                Socket clientSocket = serverSocket.accept();
                logger.info("New client connected: " + clientSocket.getRemoteSocketAddress());

                // Create a RequestHandler and submit it to the thread pool
                RequestHandler handler = new RequestHandler(clientSocket, fileStorage /*, connectionManager */);
                executor.execute(handler);
            }
        } catch (IOException e) {
            logger.severe("Server socket error: " + e.getMessage());
        } finally {
            executor.shutdown();
        }
    }
}
