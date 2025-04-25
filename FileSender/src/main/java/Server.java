import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Entry point for launching the server. This class configures the server,
 * initializes components, and listens for incoming client connections.
 */
public class Server {
    private static final Logger logger = Logger.getLogger(Server.class.getName());
    private static Map<String, List<TrustedClient>> trustedClients;
    private static List<ServerFile> files;

    public static void main(String[] args) {
        // 1. Parse command-line arguments into a ServerConfig object
        ServerConfig config = ServerConfig.fromArgs(args);

        // 2. Log server start and parameters
        logger.info("Server starting with parameters: " + config);

        // 3. Create FileStorage to manage available files (MD5, etc.)
        FileStorage fileStorage = new FileStorage(config.getFilesDirectory(), config.getB());
        files = fileStorage.getFiles();

        for (ServerFile file : files) {
            trustedClients.put(file.sha256(), new ArrayList<>());
        }

        // 4. Create a fixed thread pool with a maximum of Cs threads
        ExecutorService executor = Executors.newFixedThreadPool(config.getCs());

        // 5. Start the ServerSocket and begin listening on the specified port
        try (ServerSocket serverSocket = new ServerSocket(config.getPort())) {
            logger.info("Server is listening on port: " + config.getPort());

            // 6. Main loop to accept client connections
            while (true) {
                Socket clientSocket = serverSocket.accept();
                logger.info("New client connected: " + clientSocket.getRemoteSocketAddress());

                // Create a RequestHandler and submit it to the thread pool
                if (((ThreadPoolExecutor) executor).getActiveCount() < config.getCs()) {
                    startDisconnectionSimulator(clientSocket, config.getP(), config.getT(), logger);

                    RequestHandler handler = new RequestHandler(clientSocket, fileStorage, files, trustedClients);
                    executor.execute(handler);
                } else {
                    SearchPeer(executor, clientSocket, fileStorage);
                }
            }
        } catch (IOException e) {
            logger.severe("Server socket error: " + e.getMessage());
        } finally {
            executor.shutdown();
        }
    }

    private static void startDisconnectionSimulator(Socket socket, double probability, int intervalSeconds, Logger logger) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        Runnable task = () -> {
            if (socket.isClosed()) {
                scheduler.shutdown();
                return;
            }

            if (Math.random() < probability) {
                try {
                    logger.warning("Closing connection to client (simulated failure): " + socket.getRemoteSocketAddress());
                    socket.close();
                } catch (IOException e) {
                    logger.severe("Error while closing socket: " + e.getMessage());
                } finally {
                    scheduler.shutdown();
                }
            }
        };

        scheduler.scheduleAtFixedRate(task, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private static void SearchPeer(ExecutorService executor, Socket clientSocket, FileStorage fileStorage) {

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             OutputStream out = clientSocket.getOutputStream();
             PrintWriter writer = new PrintWriter(out, true)) {

            String line = reader.readLine();

            if (!TryFindTrustedClient(line, executor, clientSocket, fileStorage, writer)) {
                executor.execute(new RequestHandler(clientSocket, reader, writer, line,  fileStorage, files, trustedClients));
            }

        } catch (IOException e) {
            logger.severe("Error handling ONLY_DOWNLOAD fallback: " + e.getMessage());
        }
    }

    private static boolean TryFindTrustedClient(String line, ExecutorService executor, Socket clientSocket, FileStorage fileStorage, PrintWriter writer) {
        if (line == null || !line.trim().startsWith("ONLY_DOWNLOAD")) {
            return false;
        }

        String[] parts = line.trim().split("\\s+");
        if (parts.length < 3) {
            return false;
        }

        String fileId = parts[1];
        int blockIndex = Integer.parseInt(parts[2]);

        List<TrustedClient> candidates = trustedClients.getOrDefault(fileId, List.of());
        boolean peerFound = false;

        for (TrustedClient peer : candidates) {
            try (Socket peerSocket = new Socket(peer.host(), peer.port());
                 PrintWriter peerWriter = new PrintWriter(peerSocket.getOutputStream(), true);
                 BufferedReader peerReader = new BufferedReader(new InputStreamReader(peerSocket.getInputStream()))) {

                peerWriter.println("TOKEN_REQUEST " + fileId + " " + blockIndex);
                String tokenLine = peerReader.readLine();

                if (tokenLine != null && tokenLine.startsWith("TOKEN ")) {
                    writer.println(tokenLine);
                    peerFound = true;
                    break;
                }
            } catch (IOException e) {
            }
        }

        return  peerFound;
    }
}
