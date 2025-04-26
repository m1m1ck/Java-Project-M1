import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Main entry point for the client. 
 * 1) Parses parameters (server host/port, file ID, DC, etc.).
 * 2) Connects to the server, requests file info, downloads in blocks (in a simplified version).
 * 3) (Optionally) uses multiple threads to download blocks in parallel.
 */
public class Client {
    private static final Logger logger = Logger.getLogger(Client.class.getName());

    public static void main(String[] args) {
        // 1. Parse client parameters
        ClientConfig config = ClientConfig.fromArgs(args);
        logger.info("Client starting with parameters: " + config);

        FileStorage fileStorage = new FileStorage(config.getFilesDirectory(), config.getB());

        // 2. As a simple test, let's connect to the server and try "LIST_FILES"
        //    or download the file specified by config.getFileId().
        try {
            // If you want to just do a single thread approach first:
            downloadFile(config, fileStorage);
            startTrustedClientServerSingleThread(config, fileStorage);
            // If you want parallel approach using DC:
            // downloadFileParallel(config);
        } catch (IOException e) {
            logger.severe("Client I/O error: " + e.getMessage());
        }
    }

    /**
     * A simple single-thread method to connect to the server, request a file, and download it.
     * This is a STUB you will expand with real "GET_BLOCK" logic, MD5 checks, etc.
     */
    private static void downloadFile(ClientConfig config, FileStorage fileStorage) throws IOException {
        logger.info("Connecting to server: " + config.getServerHost() + ":" + config.getServerPort());

        // Set up streams to communicate with the server
        try (Socket socket = new Socket(config.getServerHost(), config.getServerPort());
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             DataInputStream dataIn = new DataInputStream(socket.getInputStream())) {
            logger.info("Connected to server.");

            List<String> possibleFileIds = new ArrayList<>();

            writer.println("LIST_FILES");
            String line;
            while ((line = reader.readLine()) != null) {

                String[] partsId = line.trim().split("ID: ");
                if (partsId.length > 1) {
                    possibleFileIds.add(partsId[1].trim());
                }

                if (line.equals("END_OF_LIST")) {
                    break;
                }
                logger.info("Server file: " + line);
            }

            String fileId = config.getFileId();
            if ("random".equals(fileId)) {
                fileId = possibleFileIds.get(ThreadLocalRandom.current().nextInt(possibleFileIds.size()));
            }

            String response;
            do {
                ExecutorService executor = Executors.newFixedThreadPool(config.getDC());
                ConcurrentMap<Integer, byte[]> blocksMap = new ConcurrentHashMap<>();

                CountDownLatch latch = new CountDownLatch(config.getDC());

                for (int i = 0; i < config.getDC(); i++) {
                    int threadIndex = i;

                    String finalFileId = fileId;
                    executor.submit(() -> {
                        int blockIndex = threadIndex;

                        try (
                                Socket socketTh = new Socket(config.getServerHost(), config.getServerPort());
                                OutputStream outTh = socketTh.getOutputStream();
                                InputStream inTh = socketTh.getInputStream();
                                PrintWriter writerTh = new PrintWriter(outTh, true);
                                DataInputStream dataInTh = new DataInputStream(inTh)
                        ) {
                            while (true) {
                                writerTh.println("DOWNLOAD " + finalFileId + " " + blockIndex);
                                String responseTh = reader.readLine();
                                if (responseTh.startsWith("SENDING")) {
                                    int length;
                                    try {
                                        length = dataIn.readInt();
                                    } catch (EOFException e) {
                                        break;
                                    }

                                    if (length <= 0) break;

                                    byte[] buffer = new byte[length];
                                    dataInTh.readFully(buffer);

                                    blocksMap.put(blockIndex, buffer);
                                    blockIndex += config.getDC();
                                } else if (responseTh.startsWith("TOKEN")) {
                                    String[] tokenParts = responseTh.split("\\s+");
                                    if (tokenParts.length < 5) {
                                        logger.info("Malformed token response: " + responseTh);
                                        break;
                                    }

                                    String tokenId = tokenParts[1];
                                    String host = tokenParts[2];
                                    int port = Integer.parseInt(tokenParts[3]);

                                    logger.info("Received token from " + host + ":" + port + " for block " + blockIndex);

                                    try (
                                            Socket peerSocket = new Socket(host, port);
                                            PrintWriter peerWriter = new PrintWriter(peerSocket.getOutputStream(), true);
                                            BufferedReader peerReader = new BufferedReader(new InputStreamReader(peerSocket.getInputStream()));
                                            DataInputStream peerDataIn = new DataInputStream(peerSocket.getInputStream())
                                    ) {
                                        peerWriter.println("DOWNLOAD_TOKEN " + tokenId + " " + finalFileId + " " + blockIndex);

                                        String peerResponse = peerReader.readLine();
                                        if (peerResponse != null && peerResponse.startsWith("SENDING")) {
                                            int length = peerDataIn.readInt();
                                            if (length > 0) {
                                                byte[] buffer = new byte[length];
                                                peerDataIn.readFully(buffer);
                                                blocksMap.put(blockIndex, buffer);
                                                blockIndex += config.getDC();
                                            } else {
                                                break;
                                            }
                                        } else {
                                            logger.info("Unexpected response from trusted peer: " + peerResponse);
                                        }
                                    } catch (IOException ex) {
                                        logger.info("Failed to connect to trusted client: " + ex.getMessage());
                                    }
                                }
                            }
                        } catch (IOException e) {
                            logger.info("Thread " + threadIndex + " error: " + e.getMessage());
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                executor.shutdown();
                latch.await();

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
                System.out.println(hash);
                writer.println("MD5 " + hash);
                response = reader.readLine();
                System.out.println(response);
            } while (!response.equals("CORRECT"));

        } catch (Exception e) {
            logger.info("Exception.");
        }
    }

    public static void startTrustedClientServerSingleThread(ClientConfig config, FileStorage fileStorage) {
        try (ServerSocket serverSocket = new ServerSocket(config.getPort())) {
            logger.info("Trusted client (single-threaded) listening on port " + config.getPort());

            while (true) {
                try (
                        Socket socket = serverSocket.accept();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                        DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream())
                ) {
                    // 1. Ждём TOKEN_REQUEST
                    String line = reader.readLine();
                    if (line == null) continue;

                    String[] parts = line.trim().split("\\s+");
                    if (!parts[0].equals("TOKEN_REQUEST") || parts.length < 3) {
                        writer.println("ERROR: Invalid TOKEN_REQUEST");
                        continue;
                    }

                    String fileId = parts[1];
                    String blockIndex = parts[2];
                    String token = UUID.randomUUID().toString();

                    String host = socket.getLocalAddress().getHostAddress();
                    int p = socket.getLocalPort();
                    writer.println("TOKEN " + token + " " + host + " " + p);

                    line = reader.readLine();
                    if (line == null) continue;

                    parts = line.trim().split("\\s+");
                    if (parts.length < 4 || !parts[0].equals("DOWNLOAD_TOKEN")) {
                        writer.println("ERROR: Invalid DOWNLOAD_TOKEN");
                        continue;
                    }

                    String receivedToken = parts[1];
                    String receivedFileId = parts[2];
                    String receivedBlockIndex = parts[3];

                    if (!token.equals(receivedToken) ||
                            !fileId.equals(receivedFileId) ||
                            !blockIndex.equals(receivedBlockIndex)) {
                        writer.println("INVALID_TOKEN");
                        continue;
                    }

                    // 3. Отправка блока
                    byte[] block = fileStorage.getBlock(fileId, Integer.parseInt(blockIndex));

                    writer.println("SENDING");
                    dataOut.writeInt(block.length);
                    dataOut.write(block);
                    dataOut.flush();

                } catch (IOException e) {
                    System.err.println("Client handling error: " + e.getMessage());
                }
            }

        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }
}
