import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class Client {
    private static final Logger logger = Logger.getLogger(Client.class.getName());
    private static final Map<String, TokenInfo> validTokens = new ConcurrentHashMap<>();

    private record TokenInfo(String fileId, long expirationTimeMillis) {
    }

    private static final ScheduledExecutorService cleanerService = Executors.newSingleThreadScheduledExecutor();
    private static final Random rand = new Random();

    public static void main(String[] args) {
        ClientConfig config = ClientConfig.fromArgs(args);
        logger.info("[Client] Starting: " + config);

        //time from here
        FileStorage fileStorage = new FileStorage(config.getFilesDirectory(), config.getB());

        try {
            downloadFile(config, fileStorage);
            //to here.
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
                        System.out.println("latch" + threadIndex);
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

    // Utility: read a line (up to '\n') from DataInputStream
    private static String readLine(DataInputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            buf.write(b);
        }
        return buf.toString("UTF-8").trim();
    }

    // --- Modified to use Data streams only ---
    private static void downloadBlocksForThread(ClientConfig config, String fileId, int threadIndex, ConcurrentMap<Integer, byte[]> blocksMap) {
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
                    logger.info("Client received token: " + response);
                    handleTokenDownloadWithStream(config, response, fileId, blockIndex, blocksMap);
                    break;
                } else {
                    break;
                }
            }
        } catch (IOException e) {
            logger.warning("[Thread " + threadIndex + "] Connection error: " + e.getMessage());
        }
    }

    private static void handleTokenDownloadWithStream(ClientConfig config, String tokenResponse, String fileId, int blockIndex, ConcurrentMap<Integer, byte[]> blocksMap) {
        String[] parts = tokenResponse.split("\\s+");
        if (parts.length < 4) return;
        String token = parts[1], host = parts[2];
        int port = Integer.parseInt(parts[3]);
        while (true) {
            try (Socket peer = new Socket(host, port);
                 DataOutputStream pout = new DataOutputStream(peer.getOutputStream());
                 DataInputStream  pin  = new DataInputStream(peer.getInputStream())) {

                // send the DOWNLOAD_TOKEN command over the same DataOutputStream
                pout.writeBytes("DOWNLOAD_TOKEN " + token + " " + fileId + " " + blockIndex + "\n");
                pout.flush();

                // read the reply line via your utility
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

    // saveAndVerifyDownloadedFile remains unchanged
    private static boolean saveAndVerifyDownloadedFile(ClientConfig config, FileStorage fileStorage, String fileId, Map<Integer, byte[]> blocksMap) throws IOException, NoSuchAlgorithmException {
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

    // startTrustedClientServerSingleThread unchanged

    public static void startTrustedClientServerSingleThread(ClientConfig config, FileStorage fileStorage) {
        // Периодически чистить протухшие токены
        cleanerService.scheduleAtFixedRate(Client::cleanExpiredTokens, 5, 5, TimeUnit.SECONDS);

        try (ServerSocket serverSocket = new ServerSocket(config.getPort())) {
            logger.info("[TrustedServer] Started on port " + config.getPort());
            while (true) {
                Socket socket = serverSocket.accept();
                handleTrustedClient(socket, fileStorage, config);
            }
        } catch (IOException e) {
            logger.severe("[TrustedServer] Critical error: " + e.getMessage());
        } finally {
            cleanerService.shutdown();
        }
    }

    private static void handleTrustedClient(Socket socket, FileStorage fileStorage, ClientConfig config) {
        try (Socket s = socket;
             DataInputStream reader = new DataInputStream(s.getInputStream());
             DataOutputStream writer = new DataOutputStream(s.getOutputStream())) {

            String line = readLine(reader); // используй readLine(DataInputStream)
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
                writer.writeBytes("SENDING\n");  // пишем строку вручную
                writer.flush();
                writer.writeInt(block.length); // пишем длину
                writer.write(block);           // пишем данные
                writer.flush();
                return;
            }

            writer.writeBytes("ERROR: Unknown command\n");
            writer.flush();
        } catch (IOException e) {
            logger.warning("[TrustedServer] Error handling trusted client: " + e.getMessage());
        }
    }



    private static void cleanExpiredTokens() {
        long now = System.currentTimeMillis();
        validTokens.entrySet().removeIf(entry -> entry.getValue().expirationTimeMillis < now);
    }
}