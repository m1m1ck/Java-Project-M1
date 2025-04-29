import java.io.*;
import java.util.*;
import java.net.Socket;
import java.net.ServerSocket;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * The {@code Server} class represents the central file server.
 * It handles incoming client connections and supports the following commands:
 *
 * <ul>
 *     <li>{@code LIST_FILES} — responds with a list of available files and their unique identifiers (SHA-256);</li>
 *     <li>{@code DOWNLOAD <fileId> <blockIndex>} — sends the requested block of the specified file to the client;</li>
 *     <li>{@code MD5 <fileId> <hash> <clientPort>} — verifies the integrity of a downloaded file by comparing
 *         its MD5 hash, and if successful, may issue a token for peer-to-peer sharing.</li>
 * </ul>
 *
 * The server listens on a specified port and uses {@link FileStorage} to load files and retrieve file blocks.
 * It supports concurrent client requests by spawning a dedicated thread per connection.
 *
 * Upon successful file verification, the server can generate and issue a token that allows the verified
 * client to act as a temporary trusted peer (TrustedClient), serving blocks to other clients.
 */
public class Server {

    /** Logger for server events */
    private static final Logger logger = Logger.getLogger(Server.class.getName());

    /** Map from file ID to list of trusted clients for that file */
    private static final Map<String, List<TrustedClient>> trustedClients = new ConcurrentHashMap<>();

    /** List of currently active client sockets */
    private static final List<Socket> activeSockets = new ArrayList<>();

    /** Configuration settings for the server, loaded from a cmd args or defaults. */
    private static ServerConfig config;

    /** Manages file storage operations for the server, such as retrieving files. */
    private static FileStorage fileStorage;

    /** List of files available on the server */
    private static List<ServerFile> files;

    /** Counter for closed connections (simulated failures) */
    private static int closedConnections = 0;

    /**
     * Main method. Parses server configuration, initializes storage and thread pool,
     * starts disconnection simulator, and accepts incoming client connections.
     * @param args command-line arguments for ServerConfig
     */
    public static void main(String[] args) {
        config = ServerConfig.fromArgs(args);

        logger.info("Server starting with parameters: " + config);

        fileStorage = new FileStorage(config.getFilesDirectory(), config.getB());
        files = fileStorage.getFiles();

        for (ServerFile file : files) {
            trustedClients.put(file.sha256(), new ArrayList<>());
        }

        ExecutorService executor = Executors.newFixedThreadPool(config.getCs());
        fileStorage.writeServerStats(closedConnections);

        try (ServerSocket serverSocket = new ServerSocket(config.getPort())) {
            logger.info("Server is listening on port: " + config.getPort());

            startDisconnectionSimulator(config.getP(), config.getT());

            while (true) {
                Socket clientSocket = serverSocket.accept();
                logger.info("New client connected: " + clientSocket.getRemoteSocketAddress());

                if (((ThreadPoolExecutor) executor).getActiveCount() < config.getCs()) {
                    synchronized (activeSockets) {
                        activeSockets.add(clientSocket);
                    }
                    RequestHandler handler = new RequestHandler(activeSockets, clientSocket, fileStorage, files, trustedClients);
                    executor.execute(handler);
                } else {
                    SearchPeer(executor, clientSocket);
                }
            }
        } catch (IOException e) {
            logger.severe("Server socket error: " + e.getMessage());
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Starts a scheduled task that simulates disconnections by randomly closing one active socket
     * at each interval with the given probability.
     * @param probability probability of a disconnection each interval
     * @param intervalSeconds interval in seconds between disconnection attempts
     */
    private static void startDisconnectionSimulator(double probability, double intervalSeconds) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        Random rand = new Random();
        Runnable task = () -> {
            if (Math.random() > probability) return;
            try {
                synchronized (activeSockets) {
                    while (!activeSockets.isEmpty()) {
                        int idx = rand.nextInt(activeSockets.size());
                        Socket socket = activeSockets.get(idx);
                        if (socket.isClosed()) {
                            activeSockets.remove(idx);
                        } else {
                            logger.warning("Closing connection to client (simulated failure): "
                                           + socket.getRemoteSocketAddress());
                            socket.close();
                            closedConnections++;
                            fileStorage.writeServerStats(closedConnections);
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                logger.severe("Error while closing socket: " + e.getMessage());
            }
        };
        scheduler.scheduleAtFixedRate(task,
                (long)(intervalSeconds * 1000),
                (long)(intervalSeconds * 1000),
                TimeUnit.MILLISECONDS);
    }

    /**
     * If the thread pool is full, attempts to find a trusted client to handle a DOWNLOAD request.
     * If a trusted client is found, sends the TOKEN from the peer back to the client; otherwise,
     * hands off to a new RequestHandler on this same socket.
     * @param executor thread pool for executing new handlers
     * @param clientSocket the client socket that requested DOWNLOAD
     * @throws IOException if I/O error occurs
     */
    private static void SearchPeer(ExecutorService executor, Socket clientSocket) throws IOException {

        BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true);

        String line = reader.readLine();

        if (!TryFindTrustedClient(line, writer)) {
            executor.execute(new RequestHandler(
                    activeSockets,
                    clientSocket,
                    reader,
                    writer,
                    line,
                    fileStorage,
                    files,
                    trustedClients
            ));
        }
    }

    /**
     * Iterates through trusted clients for the requested fileId and attempts to get a TOKEN.
     * @param line client request line, expected "DOWNLOAD fileId blockIndex"
     * @param writer to send TOKEN back if found
     * @return true if a peer provided a TOKEN, false otherwise
     */
    private static boolean TryFindTrustedClient(String line, PrintWriter writer) {
        if (line == null || !line.trim().startsWith("DOWNLOAD")) {
            return false;
        }

        String[] parts = line.trim().split("\\s+");
        if (parts.length < 3) {
            return false;
        }

        String fileId = parts[1];

        List<TrustedClient> candidates = trustedClients.getOrDefault(fileId, List.of());
        List<TrustedClient> shuffledCandidates = new ArrayList<>(candidates);
        Collections.shuffle(shuffledCandidates);

        for (TrustedClient peer : shuffledCandidates) {
            try (Socket peerSocket = new Socket(peer.host(), peer.port());
                 PrintWriter peerWriter = new PrintWriter(peerSocket.getOutputStream(), true);
                 BufferedReader peerReader = new BufferedReader(new InputStreamReader(peerSocket.getInputStream()))) {

                peerWriter.println("TOKEN_REQUEST " + fileId);
                String tokenLine = peerReader.readLine();

                if (tokenLine != null && tokenLine.startsWith("TOKEN ")) {
                    writer.println(tokenLine);
                    return true;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return false;
    }
}
