import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class Client {
    private static final Logger logger = Logger.getLogger(Client.class.getName());

    public static void main(String[] args) {
        ClientConfig config = ClientConfig.fromArgs(args);
        logger.info("[Client] Starting: " + config);

        // --- Metrics setup ---
        final MetricsLogger metrics;
        final ScheduledExecutorService scheduler;
        try {
            metrics = new MetricsLogger("results_client.csv");
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(
                () -> metrics.logSnapshot(
                    config.getDC(), /* DC */
                    0.0,            /* P: unavailable in client */
                    0,              /* T: unavailable in client */
                    0               /* Cs: unavailable in client */
                ),
                0, 1, TimeUnit.SECONDS
            );
        } catch (IOException e) {
            logger.severe("[Metrics] Failed to initialize: " + e.getMessage());
            return;
        }

        try {
            FileStorage fileStorage = new FileStorage(config.getFilesDirectory(), config.getB());
            downloadFile(config, fileStorage, metrics);
        } catch (IOException | InterruptedException e) {
            logger.severe("[Client] Error: " + e.getMessage());
        } finally {
            scheduler.shutdown();
            metrics.close();
        }

        // launch trusted-server for peer delegation
        try {
            startTrustedClientServerSingleThread(
                config,
                new FileStorage(config.getFilesDirectory(), config.getB())
            );
        } catch (IOException e) {
            logger.severe("[TrustedServer] Error: " + e.getMessage());
        }
    }

    private static void downloadFile(ClientConfig config,
                                     FileStorage fileStorage,
                                     MetricsLogger metrics)
            throws IOException, InterruptedException {
        List<String> possibleFileIds = new ArrayList<>();
        String fileId = config.getFileId();

        // LIST_FILES
        try (Socket socket = new Socket(config.getServerHost(), config.getServerPort());
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
            writer.println("LIST_FILES");
            String line;
            while ((line = reader.readLine()) != null) {
                if ("END_OF_LIST".equals(line)) break;
                String[] parts = line.split("ID: ");
                if (parts.length > 1) possibleFileIds.add(parts[1].trim());
            }
        }

        // choose random if needed
        if ("random".equals(fileId) && !possibleFileIds.isEmpty()) {
            fileId = possibleFileIds.get(
                ThreadLocalRandom.current().nextInt(possibleFileIds.size())
            );
        }

        downloadFileBlocks(config, fileStorage, fileId, metrics);
    }

    private static void downloadFileBlocks(ClientConfig config,
                                           FileStorage fileStorage,
                                           String fileId,
                                           MetricsLogger metrics)
            throws InterruptedException {
        boolean fileDownloaded = false;
        while (!fileDownloaded) {
            ExecutorService executor = Executors.newFixedThreadPool(config.getDC());
            ConcurrentMap<Integer, byte[]> blocksMap = new ConcurrentHashMap<>();
            CountDownLatch latch = new CountDownLatch(config.getDC());

            for (int i = 0; i < config.getDC(); i++) {
                final int threadIndex = i;
                executor.submit(() -> {
                    try {
                        downloadBlocksForThread(
                            config, fileId, threadIndex, blocksMap, metrics
                        );
                    } finally {
                        latch.countDown();
                    }
                });
            }
            executor.shutdown();
            latch.await();

            try {
                fileDownloaded = saveAndVerifyDownloadedFile(
                    config, fileStorage, fileId, blocksMap
                );
            } catch (IOException | NoSuchAlgorithmException e) {
                logger.warning("[Download] Verification failed, retrying: " + e.getMessage());
            }
        }
    }

    private static void downloadBlocksForThread(ClientConfig config,
                                                String fileId,
                                                int threadIndex,
                                                ConcurrentMap<Integer, byte[]> blocksMap,
                                                MetricsLogger metrics) {
        int blockIndex = threadIndex;
        try (Socket socket = new Socket(config.getServerHost(), config.getServerPort());
             DataInputStream dataIn = new DataInputStream(socket.getInputStream());
             DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream())) {
            while (true) {
                dataOut.writeBytes("DOWNLOAD " + fileId + " " + blockIndex + "\n");
                dataOut.flush();

                String response = readLine(dataIn);
                if (response == null || response.isEmpty() || "EOF".equals(response)) break;

                if (response.startsWith("SENDING")) {
                    int length = dataIn.readInt();
                    if (length <= 0) break;
                    byte[] buffer = new byte[length];
                    dataIn.readFully(buffer);
                    blocksMap.put(blockIndex, buffer);
                    metrics.addBytes(length);
                    blockIndex += config.getDC();

                } else if (response.startsWith("TOKEN")) {
                    handleTokenDownloadWithStream(
                        config, response, fileId, blockIndex, blocksMap, metrics
                    );
                    blockIndex += config.getDC();
                } else {
                    break;
                }
            }
        } catch (IOException e) {
            logger.warning("[Thread " + threadIndex + "] Error: " + e.getMessage());
        }
    }

    private static void handleTokenDownloadWithStream(ClientConfig config,
                                                      String tokenResponse,
                                                      String fileId,
                                                      int blockIndex,
                                                      ConcurrentMap<Integer, byte[]> blocksMap,
                                                      MetricsLogger metrics) {
        String[] parts = tokenResponse.split("\\s+");
        if (parts.length < 5) return;
        String token = parts[1];
        String host = parts[2];
        int port = Integer.parseInt(parts[3]);
        try (Socket peerSocket = new Socket(host, port);
             DataInputStream pin = new DataInputStream(peerSocket.getInputStream());
             DataOutputStream pout = new DataOutputStream(peerSocket.getOutputStream())) {
            pout.writeBytes("DOWNLOAD_TOKEN " + token + " " + fileId + " " + blockIndex + "\n");
            pout.flush();
            String resp = readLine(pin);
            if (resp != null && resp.startsWith("SENDING")) {
                int len = pin.readInt();
                if (len > 0) {
                    byte[] buf = new byte[len];
                    pin.readFully(buf);
                    blocksMap.put(blockIndex, buf);
                    metrics.addBytes(len);
                }
            }
        } catch (IOException e) {
            logger.warning("[Token] Failed via token: " + e.getMessage());
        }
    }

    private static boolean saveAndVerifyDownloadedFile(ClientConfig config,
                                                       FileStorage fileStorage,
                                                       String fileId,
                                                       Map<Integer, byte[]> blocksMap)
            throws IOException, NoSuchAlgorithmException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        blocksMap.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> {
                try {
                    baos.write(e.getValue());
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        byte[] full = baos.toByteArray();
        String outName = "output_" + fileId + ".txt";
        fileStorage.saveFile(full, outName);
        String hash = fileStorage.getMD5(outName);

        try (Socket s = new Socket(config.getServerHost(), config.getServerPort());
             BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter w = new PrintWriter(s.getOutputStream(), true)) {
            w.println("MD5 " + fileId + " " + hash + " " + config.getPort());
            return "CORRECT".equals(r.readLine());
        }
    }

    public static void startTrustedClientServerSingleThread(ClientConfig config,
                                                            FileStorage fileStorage)
            throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(config.getPort())) {
            logger.info("[TrustedServer] Started on port " + config.getPort());
            while (true) {
                Socket client = serverSocket.accept();
                handleTrustedClient(client, fileStorage);
            }
        }
    }

    private static void handleTrustedClient(Socket socket, FileStorage fileStorage) {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter writer = new PrintWriter(s.getOutputStream(), true);
             DataOutputStream dataOut = new DataOutputStream(s.getOutputStream())) {
            String line = reader.readLine();
            if (line == null) return;
            String[] parts = line.split("\\s+");
            if (parts.length < 3 || !"TOKEN_REQUEST".equals(parts[0])) {
                writer.println("ERROR: Invalid TOKEN_REQUEST");
                return;
            }
            String fileId = parts[1];
            int blockIndex = Integer.parseInt(parts[2]);
            String token = UUID.randomUUID().toString();
            String host = s.getLocalAddress().getHostAddress();
            int port = s.getLocalPort();
            writer.println("TOKEN " + token + " " + host + " " + port);

            line = reader.readLine();
            if (line == null) return;
            parts = line.split("\\s+");
            if (parts.length < 4 
                || !"DOWNLOAD_TOKEN".equals(parts[0])
                || !token.equals(parts[1])
                || !fileId.equals(parts[2])
                || blockIndex != Integer.parseInt(parts[3])) {
                writer.println("INVALID_TOKEN");
                return;
            }

            byte[] block = fileStorage.getBlock(fileId, blockIndex);
            writer.println("SENDING");
            dataOut.writeInt(block.length);
            dataOut.write(block);
            dataOut.flush();

        } catch (IOException e) {
            logger.warning("[TrustedClient] Error: " + e.getMessage());
        }
    }

    /** Utility: read a line terminated by '\n' from DataInputStream */
    private static String readLine(DataInputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            buf.write(b);
        }
        return buf.toString("UTF-8").trim();
    }

    /**
     * Simple CSV logger for periodic snapshots
     */
    private static class MetricsLogger {
        private final PrintWriter out;
        private final AtomicLong bytesDownloaded = new AtomicLong();

        public MetricsLogger(String path) throws IOException {
            this.out = new PrintWriter(new FileWriter(path, false));
            out.println("timestamp,dc,bytesDownloaded");
        }

        public void addBytes(long n) {
            bytesDownloaded.addAndGet(n);
        }

        public synchronized void logSnapshot(int dc, double P, int T, int Cs) {
            long ts = System.currentTimeMillis();
            long bytes = bytesDownloaded.get();
            out.printf("%d,%d,%d%n", ts, dc, bytes);
            out.flush();
        }

        public void close() {
            out.close();
        }
    }
}
