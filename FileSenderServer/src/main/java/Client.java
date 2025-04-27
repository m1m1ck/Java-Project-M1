import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class Client {
    private static final Logger logger = Logger.getLogger(Client.class.getName());

    public static void main(String[] args) {
        ClientConfig config = ClientConfig.fromArgs(args);
        logger.info("[Client] Starting: " + config);

        FileStorage fileStorage = new FileStorage(config.getFilesDirectory(), config.getB());

        try {
            downloadFile(config, fileStorage);
            startTrustedClientServerSingleThread(config, fileStorage);
        } catch (IOException | InterruptedException e) {
            logger.severe("[Client] Critical error: " + e.getMessage());
        }
    }

    private static void downloadFile(ClientConfig config, FileStorage fileStorage) throws IOException, InterruptedException {
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

        downloadFileBlocks(config, fileStorage, fileId);
    }

    private static void downloadFileBlocks(ClientConfig config, FileStorage fileStorage, String fileId) throws InterruptedException {
        boolean fileDownloaded = false;

        while (!fileDownloaded) {
            ExecutorService executor = Executors.newFixedThreadPool(config.getDC());
            ConcurrentMap<Integer, byte[]> blocksMap = new ConcurrentHashMap<>();
            CountDownLatch latch = new CountDownLatch(config.getDC());

            for (int i = 0; i < config.getDC(); i++) {
                int threadIndex = i;
                executor.submit(() -> {
                    try {
                        downloadBlocksForThread(config, fileId, threadIndex, blocksMap);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            executor.shutdown();
            latch.await();

            try {
                fileDownloaded = saveAndVerifyDownloadedFile(config, fileStorage, fileId, blocksMap);
            } catch (IOException | NoSuchAlgorithmException e) {
                logger.warning("[Download] Verification failed, retrying: " + e.getMessage());
            }
        }
    }

    private static void downloadBlocksForThread(ClientConfig config, String fileId, int threadIndex, ConcurrentMap<Integer, byte[]> blocksMap) {
        int blockIndex = threadIndex;

        try (Socket socket = new Socket(config.getServerHost(), config.getServerPort());
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             DataInputStream dataIn = new DataInputStream(socket.getInputStream())) {

            while (true) {
                writer.println("DOWNLOAD " + fileId + " " + blockIndex);
                String response = reader.readLine();

                if (response == null) break;

                if (response.startsWith("SENDING")) {
                    int length = dataIn.readInt();
                    if (length <= 0) break;

                    byte[] buffer = new byte[length];
                    dataIn.readFully(buffer);

                    blocksMap.put(blockIndex, buffer);
                    blockIndex += config.getDC();
                } else if (response.startsWith("TOKEN")) {
                    handleTokenDownload(response, fileId, blockIndex, blocksMap);
                    blockIndex += config.getDC();
                }
            }
        } catch (IOException e) {
            logger.warning("[Thread " + threadIndex + "] Connection error: " + e.getMessage());
        }
    }

    private static void handleTokenDownload(String tokenResponse, String fileId, int blockIndex, ConcurrentMap<Integer, byte[]> blocksMap) {
        String[] parts = tokenResponse.split("\\s+");
        if (parts.length < 5) return;

        String token = parts[1];
        String host = parts[2];
        int port = Integer.parseInt(parts[3]);

        try (Socket peerSocket = new Socket(host, port);
             PrintWriter peerWriter = new PrintWriter(peerSocket.getOutputStream(), true);
             BufferedReader peerReader = new BufferedReader(new InputStreamReader(peerSocket.getInputStream()));
             DataInputStream peerDataIn = new DataInputStream(peerSocket.getInputStream())) {

            peerWriter.println("DOWNLOAD_TOKEN " + token + " " + fileId + " " + blockIndex);
            String response = peerReader.readLine();

            if (response != null && response.startsWith("SENDING")) {
                int length = peerDataIn.readInt();
                if (length > 0) {
                    byte[] buffer = new byte[length];
                    peerDataIn.readFully(buffer);
                    blocksMap.put(blockIndex, buffer);
                }
            }
        } catch (IOException e) {
            logger.warning("[Token] Failed to download via token: " + e.getMessage());
        }
    }

    private static boolean saveAndVerifyDownloadedFile(ClientConfig config, FileStorage fileStorage, String fileId, Map<Integer, byte[]> blocksMap) throws IOException, NoSuchAlgorithmException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        blocksMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    try {
                        outputStream.write(entry.getValue());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        byte[] fullFileBytes = outputStream.toByteArray();
        String outputFile = "output_" + fileId + ".txt";

        fileStorage.saveFile(fullFileBytes, outputFile);
        String hash = fileStorage.getMD5(outputFile);

        try (Socket socket = new Socket(config.getServerHost(), config.getServerPort());
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

            writer.println("MD5 " + fileId + " " + hash);
            String response = reader.readLine();

            if (!"CORRECT".equals(response)) {
                logger.warning("[Verify] Incorrect file hash for " + fileId);
                return false;
            }

            logger.info("[Verify] File " + fileId + " verified successfully.");
            return true;
        }
    }

    public static void startTrustedClientServerSingleThread(ClientConfig config, FileStorage fileStorage) {
        try (ServerSocket serverSocket = new ServerSocket(config.getPort())) {
            logger.info("[TrustedServer] Started on port " + config.getPort());

            while (true) {
                handleTrustedClient(serverSocket.accept(), fileStorage);
            }
        } catch (IOException e) {
            logger.severe("[TrustedServer] Critical error: " + e.getMessage());
        }
    }

    private static void handleTrustedClient(Socket socket, FileStorage fileStorage) {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter writer = new PrintWriter(s.getOutputStream(), true);
             DataOutputStream dataOut = new DataOutputStream(s.getOutputStream())) {

            String line = reader.readLine();
            if (line == null) return;

            String[] parts = line.trim().split("\\s+");
            if (parts.length < 3 || !"TOKEN_REQUEST".equals(parts[0])) {
                writer.println("ERROR: Invalid TOKEN_REQUEST");
                return;
            }

            String fileId = parts[1];
            String blockIndex = parts[2];
            String token = UUID.randomUUID().toString();
            String host = s.getLocalAddress().getHostAddress();
            int port = s.getLocalPort();

            writer.println("TOKEN " + token + " " + host + " " + port);

            line = reader.readLine();
            if (line == null) return;

            parts = line.trim().split("\\s+");
            if (parts.length < 4 || !"DOWNLOAD_TOKEN".equals(parts[0])) {
                writer.println("ERROR: Invalid DOWNLOAD_TOKEN");
                return;
            }

            if (!token.equals(parts[1]) || !fileId.equals(parts[2]) || !blockIndex.equals(parts[3])) {
                writer.println("INVALID_TOKEN");
                return;
            }

            byte[] block = fileStorage.getBlock(fileId, Integer.parseInt(blockIndex));
            writer.println("SENDING");
            dataOut.writeInt(block.length);
            dataOut.write(block);
            dataOut.flush();

        } catch (IOException e) {
            logger.warning("[TrustedClient] Error: " + e.getMessage());
        }
    }
}
