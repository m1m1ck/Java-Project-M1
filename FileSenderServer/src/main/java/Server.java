import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
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
    private static int closeconnection = 0;

    public static void main(String[] args) {
        ServerConfig config = ServerConfig.fromArgs(args);

        logger.info("Server starting with parameters: " + config);

        FileStorage fileStorage = new FileStorage(config.getFilesDirectory(), config.getB());
        files = fileStorage.getFiles();

        trustedClients = new ConcurrentHashMap<>();
        for (ServerFile file : files) {
            trustedClients.put(file.sha256(), new ArrayList<>());
        }

        ExecutorService executor = Executors.newFixedThreadPool(config.getCs());
        fileStorage.createservercsv(closeconnection);

        try (ServerSocket serverSocket = new ServerSocket(config.getPort())) {
            logger.info("Server is listening on port: " + config.getPort());

            List<Socket> activeSockets = new ArrayList<>();
            startDisconnectionSimulator(activeSockets, config.getP(), config.getT(), fileStorage);

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
                    SearchPeer(activeSockets, executor, clientSocket, fileStorage);
                }
            }
        } catch (IOException e) {
            logger.severe("Server socket error: " + e.getMessage());
        } finally {
            executor.shutdown();
        }
    }

    private static void startDisconnectionSimulator(List<Socket> activeSockets, double probability, double intervalSeconds, FileStorage fileStorage ) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        Random rand = new Random();
        Runnable task = () -> {
            if (Math.random() < probability) {
                try {
                    synchronized (activeSockets) {
                        while (!activeSockets.isEmpty()) {
                            int idx = rand.nextInt(activeSockets.size());
                            Socket socket = activeSockets.get(idx);
                            if (socket.isClosed()) {
                                activeSockets.remove(idx);
                            } else {
                                Server.logger.warning("Closing connection to client (simulated failure): "
                                    + socket.getRemoteSocketAddress());
                                socket.close();
                                closeconnection++;
                                fileStorage.createservercsv(closeconnection);;
                                break;
                            }
                        }
                    }
                } catch (IOException e) {
                    Server.logger.severe("Error while closing socket: " + e.getMessage());
                }
            }
        };

        scheduler.scheduleAtFixedRate(task, (long)(intervalSeconds * 1000), (long)(intervalSeconds * 1000), TimeUnit.MILLISECONDS);
    }

    private static void SearchPeer(List<Socket> activeSockets,
                                   ExecutorService executor,
                                   Socket clientSocket,
                                   FileStorage fileStorage) throws IOException {

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream()));
        PrintWriter writer = new PrintWriter(
                clientSocket.getOutputStream(), true);

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
        List<TrustedClient> shuffledcandidates = new ArrayList<>(candidates);
        Collections.shuffle(shuffledcandidates);

        for (TrustedClient peer : shuffledcandidates) {
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

        return  false;
    }
}
