import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

        // 2. As a simple test, let's connect to the server and try "LIST_FILES"
        //    or download the file specified by config.getFileId().
        try {
            // If you want to just do a single thread approach first:
            downloadFileSingleThread(config);

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
    private static void downloadFileSingleThread(ClientConfig config) throws IOException {
        logger.info("Connecting to server: " + config.getServerHost() + ":" + config.getServerPort());
        Socket socket = new Socket(config.getServerHost(), config.getServerPort());
        logger.info("Connected to server.");

        // Set up streams to communicate with the server
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

            // Example: we can send "LIST_FILES" or some request for file
            writer.println("LIST_FILES");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("END_OF_LIST")) {
                    break;
                }
                logger.info("Server file: " + line);
            }

            // Now let's imagine we want to request a specific file
            // We might define a command like "DOWNLOAD <fileId>"
            writer.println("DOWNLOAD " + config.getFileId());
            
            // Then we'd expect from the server: file size, or number of blocks, etc.
            // (This logic depends on how you implement the server's protocol.)

            // Here just a stub: let's read lines until server closes or we get an "OK".
            while ((line = reader.readLine()) != null) {
                logger.info("Server says: " + line);
                if (line.equals("OK_DOWNLOAD")) {
                    break;
                }
            }

            // TODO: implement receiving blocks, writing them to a local file, etc.

            logger.info("Single-thread download finished (stub).");

        } finally {
            socket.close();
        }
    }

    /**
     * Demonstrates how to set up parallel downloads using DC (stub).
     */
    private static void downloadFileParallel(ClientConfig config) throws IOException {
        logger.info("Using parallel download with " + config.getDC() + " connections.");

        // For parallel tasks, we can create a ThreadPool
        ExecutorService pool = Executors.newFixedThreadPool(config.getDC());

        // For example, if the server says the file is X blocks,
        // we might schedule tasks for each block. (STUB approach)
        int totalBlocks = 10; // Suppose the server says there are 10 blocks, for example

        for (int blockIndex = 0; blockIndex < totalBlocks; blockIndex++) {
            final int idx = blockIndex;
            pool.submit(() -> {
                try {
                    downloadBlock(config, idx);
                } catch (IOException e) {
                    logger.warning("Failed to download block " + idx + ": " + e.getMessage());
                }
            });
        }

        pool.shutdown();
        // We might wait for pool termination, then assemble blocks, etc.
        // Then compute MD5 and send to server...
    }

    /**
     * Downloads a single block from the server (or from a trusted client, in advanced scenario).
     * This is a stub that just shows the idea.
     */
    private static void downloadBlock(ClientConfig config, int blockIndex) throws IOException {
        logger.info("Downloading block #" + blockIndex + " from server...");
        Socket socket = new Socket(config.getServerHost(), config.getServerPort());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
            // Command like: "GET_BLOCK <fileId> <blockIndex>"
            writer.println("GET_BLOCK " + config.getFileId() + " " + blockIndex);

            // The server might respond with the binary data or some line-based protocol
            // This is just a placeholder
            String response = reader.readLine();
            logger.info("Block " + blockIndex + " response: " + response);

            // Then you'd read the actual bytes of the block, store them in a byte array, etc.
        } finally {
            socket.close();
        }
    }
}
