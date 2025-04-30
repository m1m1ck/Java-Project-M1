import java.io.*;
import java.util.*;
import java.net.Socket;
import java.net.ServerSocket;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

/**
 * The {@code Client} class simulates a client that connects to a server to retrieve file blocks.
 * It supports multithreaded downloading, token-based peer assistance (trusted client),
 * and file integrity verification via MD5.
 * <p>
 * Key responsibilities include:
 * <ul>
 *     <li>Connecting to the main server to fetch available file IDs</li>
 *     <li>Downloading files in parallel block-wise from the server or trusted peers</li>
 *     <li>Verifying downloaded files using server-provided MD5 hashes</li>
 *     <li>Launching a local trusted client server to assist others via tokens</li>
 *     <li>Tracking and writing client-side statistics (download time, tokens used, help provided)</li>
 * </ul>
 *
 * <p>
 * The client can either download directly from the server or use a peer-assisted model via
 * trusted token exchanges. When another client requests help via a token, this client
 * can accept the request (with probability {@code PC}) and serve the block if the token is valid.
 *
 * <p>
 * Internal token management uses {@link TokenInfo}, stored in a thread-safe map with a periodic
 * cleanup mechanism to remove expired tokens.
 *
 * <p>
 * Command-line arguments are parsed using {@link ClientConfig#fromArgs(String[])} to configure
 * the server address, port, file ID to download, concurrency level, and other client settings.
 *
 * @see ClientConfig
 * @see FileStorage
 */
public class Client {

    /**
     * Internal record to store token metadata used for trusted client transfers.
     *
     * @param fileId               the ID of the file the token grants access to
     * @param expirationTimeMillis the UNIX timestamp (in ms) at which the token becomes invalid
     */
    private record TokenInfo(String fileId, long expirationTimeMillis) { }

    /** Logger for client-side events and errors. */
    private static final Logger logger = Logger.getLogger(Client.class.getName());

    /** Map of valid tokens issued by this client to other peers. */
    private static final Map<String, TokenInfo> validTokens = new ConcurrentHashMap<>();

    /** Scheduled service to periodically clean up expired tokens. */
    private static final ScheduledExecutorService cleanerService = Executors.newSingleThreadScheduledExecutor();

    /** Random number generator used for probabilistic token denial. */
    private static final Random rand = new Random();

    /** Configuration object loaded from command-line arguments. */
    private static ClientConfig config;

    /** Manages file operations for the client. */
    private static FileStorage fileStorage;

    /** Counter for the number of tokens received by this client while downloading. */
    private static int ReceivedToken = 0;

    /** Duration of file download in milliseconds. */
    private static long durationMs = 0;

    /** Number of times this client helped another peer with a file block. */
    private static int helped = 0;

    /**
     * Main entry point for the client.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        config = ClientConfig.fromArgs(args);
        logger.info("[Client] Starting: " + config);

        long start = System.currentTimeMillis();

        fileStorage = new FileStorage(config.getFilesDirectory(), config.getB());

        try {
            selectAndDownloadFile();
            
            durationMs = System.currentTimeMillis() - start;

            fileStorage.writeClientStats(durationMs, ReceivedToken, 0, config);

            startTrustedClientServer();
        } catch (IOException | InterruptedException e) {
            logger.severe("[Client] Critical error: " + e.getMessage());
        }
    }

    /**
     * Sends request to server to obtain list of files.
     * Afterwards selects a file (either specific or random) and initiates download.
     */
    private static void selectAndDownloadFile() throws IOException, InterruptedException {
        List<String> possibleFileIds = new ArrayList<>();
        String fileId = config.getFileId();

        try (Socket socket = new Socket(config.getServerHost(), config.getServerPort());
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

            writer.println("LIST_FILES");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("END_OF_LIST")) break;
                String[] parts = line.trim().split("ID: ");
                if (parts.length > 1) {
                    possibleFileIds.add(parts[1].trim());
                }
            }
        }

        if ("random".equals(fileId) && !possibleFileIds.isEmpty()) {
            fileId = possibleFileIds.get(ThreadLocalRandom.current().nextInt(possibleFileIds.size()));
        }

        downloadFileInBlocks(fileId);
    }

    /**
     * Downloads the specified file using multiple threads to retrieve blocks in parallel.
     *
     * @param fileId the file identifier to download
     */
    private static void downloadFileInBlocks(String fileId) throws InterruptedException {
        boolean fileDownloaded = false;

        while (!fileDownloaded) {
            ExecutorService executor = Executors.newFixedThreadPool(config.getDC());
            ConcurrentMap<Integer, byte[]> blocksMap = new ConcurrentHashMap<>();
            CountDownLatch latch = new CountDownLatch(config.getDC());

            for (int i = 0; i < config.getDC(); i++) {
                int threadIndex = i;
                executor.submit(() -> {
                    try {
                        downloadBlocksByThread(fileId, threadIndex, blocksMap);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            executor.shutdown();
            latch.await();

            try {
                fileDownloaded = saveAndVerifyDownloadedFile(fileId, blocksMap);
            } catch (IOException | NoSuchAlgorithmException e) {
                logger.warning("[Download] Verification failed, retrying: " + e.getMessage());
            }
        }
    }

    /**
     * Reads a single line from the data stream, terminated by newline.
     *
     * @param in the input stream
     * @return the read line without the newline character
     */
    private static String readLine(DataInputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            buf.write(b);
        }
        return buf.toString(StandardCharsets.UTF_8).trim();
    }

    /**
     * Downloads file blocks using a single thread.
     *
     * @param fileId    file identifier
     * @param threadIndex thread number used to calculate block indexes
     * @param blocksMap concurrent map to store retrieved blocks
     */
    private static void downloadBlocksByThread(String fileId,
                                               int threadIndex,
                                               ConcurrentMap<Integer, byte[]> blocksMap) {
        int blockIndex = threadIndex;

        try (Socket socket = new Socket(config.getServerHost(), config.getServerPort());
             DataInputStream  dataIn  = new DataInputStream(socket.getInputStream());
             DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream())) {

            while (true) {
                dataOut.writeBytes("DOWNLOAD " + fileId + " " + blockIndex + "\n");
                dataOut.flush();

                String response = readLine(dataIn);

                if (response == null || response.isEmpty()) break;
                if ("EOF".equals(response)) break;

                if (response.startsWith("SENDING")) {
                    int length = dataIn.readInt();
                    if (length <= 0) break;

                    byte[] buffer = new byte[length];
                    dataIn.readFully(buffer);
                    blocksMap.put(blockIndex, buffer);
                    blockIndex += config.getDC();

                } else if (response.startsWith("TOKEN")) {

                    ReceivedToken++;
                    logger.info("Client received token: " + response);

                    downloadBlocksUsingToken(response, fileId, blockIndex, blocksMap);
                    break;
                } else {
                    break;
                }
            }
        } catch (IOException e) {
            logger.warning("[Thread " + threadIndex + "] Connection error: " + e.getMessage());
        }
    }

    /**
     * Attempts to download file blocks using a token from a trusted client.
     *
     * @param tokenResponse the response string containing the token
     * @param fileId        file identifier
     * @param blockIndex    block index to start downloading from
     * @param blocksMap     map to store downloaded blocks
     */
    private static void downloadBlocksUsingToken(String tokenResponse,
                                                 String fileId,
                                                 int blockIndex,
                                                 ConcurrentMap<Integer, byte[]> blocksMap) {
        String[] parts = tokenResponse.split("\\s+");
        if (parts.length < 4) return;

        String token = parts[1], host = parts[2];
        int port = Integer.parseInt(parts[3]);

        while (true) {
            try (Socket peer = new Socket(host, port);
                 DataOutputStream pout = new DataOutputStream(peer.getOutputStream());
                 DataInputStream  pin  = new DataInputStream(peer.getInputStream())) {

                pout.writeBytes("DOWNLOAD_TOKEN " + token + " " + fileId + " " + blockIndex + "\n");
                pout.flush();

                String resp = readLine(pin);

                if (resp.startsWith("SENDING")) {
                    int len = pin.readInt();
                    if (len <= 0) break;

                    byte[] buf = new byte[len];
                    pin.readFully(buf);
                    blocksMap.put(blockIndex, buf);
                } else {
                    break;
                }
                blockIndex += config.getDC();
            } catch (IOException e) {
                logger.warning("[Token] Failed to download via token: " + e.getMessage());
                break;
            }
        }
    }

    /**
     * Assembles and saves a downloaded file from blocks, then verifies its MD5 hash with the server.
     *
     * @param fileId    the file ID
     * @param blocksMap the map of downloaded blocks
     * @return true if the file is verified correctly, false otherwise
     */
    private static boolean saveAndVerifyDownloadedFile(String fileId, Map<Integer, byte[]> blocksMap)
            throws IOException, NoSuchAlgorithmException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        blocksMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    try { outputStream.write(entry.getValue()); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                });

        byte[] fullFileBytes = outputStream.toByteArray();
        String outputFile = "output_" + fileId + ".txt";
        fileStorage.saveFile(fullFileBytes, outputFile);
        String hash = fileStorage.getMD5(outputFile);

        try (Socket socket = new Socket(config.getServerHost(), config.getServerPort());
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
            writer.println("MD5 " + fileId + " " + hash + " " + config.getPort());
            String response = reader.readLine();
            if (!"CORRECT".equals(response)) {
                logger.warning("[Verify] Incorrect file hash for " + fileId);
                return false;
            }
            logger.info("[Verify] File " + fileId + " verified successfully.");
            return true;
        }
    }

    /**
     * Starts a trusted client server to assist other clients via token-based downloads.
     */
    public static void startTrustedClientServer() {
        cleanerService.scheduleAtFixedRate(Client::cleanExpiredTokens, 5, 5, TimeUnit.SECONDS);

        try (ServerSocket serverSocket = new ServerSocket(config.getPort())) {
            logger.info("[TrustedClient] Started on port " + config.getPort());
            while (true) {
                Socket socket = serverSocket.accept();
                handleRequest(socket);
                fileStorage.writeClientStats(durationMs, ReceivedToken, helped, config);
            }
        } catch (IOException e) {
            logger.severe("[TrustedClient] Critical error: " + e.getMessage());
        } finally {
            cleanerService.shutdown();
        }
    }

    /**
     * Handles incoming trusted client requests for tokens or block downloads.
     *
     * @param socket the incoming client socket
     */
    private static void handleRequest(Socket socket) {
        try (Socket s = socket;
             DataInputStream reader = new DataInputStream(s.getInputStream());
             DataOutputStream writer = new DataOutputStream(s.getOutputStream())) {

            String line = readLine(reader);
            if (line == null) return;
            String[] parts = line.trim().split("\\s+");

            if ("TOKEN_REQUEST".equals(parts[0])) {
                if (parts.length < 2) {
                    writer.writeBytes("ERROR: Invalid TOKEN_REQUEST format\n");
                    return;
                }
                if (rand.nextFloat() < config.getPC()) {
                    writer.writeBytes("CLIENT DENIED THE TOKEN REQUEST\n");
                    return;
                }
                String fileId = parts[1];
                String token = UUID.randomUUID().toString();
                long expirationTime = System.currentTimeMillis() + 240_000;
                validTokens.put(token, new TokenInfo(fileId, expirationTime));

                String host = s.getLocalAddress().getHostAddress();
                int port = s.getLocalPort();
                writer.writeBytes("TOKEN " + token + " " + host + " " + port + "\n");
                writer.flush();
                helped++;
                return;
            }

            if ("DOWNLOAD_TOKEN".equals(parts[0])) {
                if (parts.length < 4) {
                    writer.writeBytes("ERROR: Invalid DOWNLOAD_TOKEN format\n");
                    return;
                }
                String token = parts[1];
                String fileId = parts[2];
                int blockIndex = Integer.parseInt(parts[3]);

                TokenInfo info = validTokens.get(token);
                if (info == null || !info.fileId.equals(fileId)) {
                    writer.writeBytes("INVALID_TOKEN\n");
                    return;
                }

                byte[] block = fileStorage.getBlock("output_" + fileId + ".txt", blockIndex);
                writer.writeBytes("SENDING\n");
                writer.flush();
                writer.writeInt(block.length);
                writer.write(block);
                writer.flush();
                return;
            }

            writer.writeBytes("ERROR: Unknown command\n");
            writer.flush();
        } catch (IOException e) {
            logger.warning("[TrustedClient] Error handling trusted client: " + e.getMessage());
        }
    }

    /**
     * Removes expired tokens from the internal token map.
     */
    private static void cleanExpiredTokens() {
        long now = System.currentTimeMillis();
        validTokens.entrySet().removeIf(entry -> entry.getValue().expirationTimeMillis < now);
    }
}
