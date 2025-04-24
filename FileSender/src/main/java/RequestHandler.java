import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.logging.Logger;

/**
 * Handles a single client connection. Read commands from the client,
 * processes them using FileStorage, and sends appropriate responses.
 */
public class RequestHandler implements Runnable {
    private static final Logger logger = Logger.getLogger(RequestHandler.class.getName());

    private final Socket socket;
    private final FileStorage fileStorage;

    public RequestHandler(Socket socket, FileStorage fileStorage) {
        this.socket = socket;
        this.fileStorage = fileStorage;
    }

    @Override
    public void run() {
        try (InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in));
             PrintWriter writer = new PrintWriter(out, true)) {

            logger.info("Handling client: " + socket.getRemoteSocketAddress());

            // Example of reading a command from the client
            String line = reader.readLine();
            if (line == null) {
                logger.info("Client closed connection immediately.");
                return;
            }
            logger.info("Received command: " + line);

            // Minimal logic: if command is LIST_FILES, return file list
            if ("LIST_FILES".equalsIgnoreCase(line.trim())) {
                List<ServerFile> files = fileStorage.getFiles();
                for (ServerFile f : files) {
                    writer.println("Name: " + f.fileName() + ", ID: " + f.sha256());
                }
                writer.println("END_OF_LIST"); // Indicate the end of the list
            } else {
                writer.println("UNKNOWN_COMMAND");
            }

        } catch (IOException e) {
            logger.warning("I/O error with client " + socket.getRemoteSocketAddress() +
                    ": " + e.getMessage());
        } finally {
            // Cleanly close the socket
            try {
                socket.close();
            } catch (IOException e) {
                logger.warning("Error closing socket: " + e.getMessage());
            }
            logger.info("Connection closed: " + socket.getRemoteSocketAddress());
        }
    }
}
