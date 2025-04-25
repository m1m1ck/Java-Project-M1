import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Handles a single client connection. Read commands from the client,
 * processes them using FileStorage, and sends appropriate responses.
 */
public class RequestHandler implements Runnable {
    private static final Logger logger = Logger.getLogger(RequestHandler.class.getName());

    private final Socket socket;
    private final FileStorage fileStorage;
    private final List<ServerFile> files;
    private final Map<String, List<TrustedClient>> trustedClients;
    private BufferedReader reader;
    private PrintWriter writer;
    private String command;
    private boolean closed;

    // Default constructor for normal handling (opens own streams)
    public RequestHandler(Socket socket, FileStorage fileStorage, List<ServerFile> files, Map<String, List<TrustedClient>> trustedClients) {
        this.socket = socket;
        this.fileStorage = fileStorage;
        this.files = files;
        this.trustedClients = trustedClients;
        this.reader = null;
        this.writer = null;
        this.command = null;
    }

    // Constructor for when streams are already opened
    public RequestHandler(Socket socket, BufferedReader reader, PrintWriter writer, String command, FileStorage fileStorage, List<ServerFile> files, Map<String, List<TrustedClient>> trustedClients) {
        this.socket = socket;
        this.fileStorage = fileStorage;
        this.files = files;
        this.trustedClients = trustedClients;
        this.reader = reader;
        this.writer = writer;
        this.command = command;
    }

    @Override
    public void run() {
        try {
            // If streams are not pre-opened, open them
            if (command == null) {
                try (InputStream in = socket.getInputStream();
                     OutputStream out = socket.getOutputStream()) {
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(in));
                    PrintWriter bufferedWriter = new PrintWriter(out, true);

                    handleCommands(bufferedReader, bufferedWriter, out);
                }
            } else {
                handleOneCommand(command, writer, socket.getOutputStream());
                handleCommands(reader, writer, socket.getOutputStream());
            }
        } catch (IOException e) {
            logger.warning("I/O error with client " + socket.getRemoteSocketAddress() +
                    ": " + e.getMessage());
        } finally {
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                logger.warning("Error closing socket: " + e.getMessage());
            }
            logger.info("Connection closed: " + (socket != null ? socket.getRemoteSocketAddress() : "N/A"));
        }
    }

    private void handleCommands(BufferedReader reader, PrintWriter writer, OutputStream out) throws IOException {
        String line;
        while ((line = reader.readLine()) != null && !closed) {
            logger.info("Received command: " + line);
            handleOneCommand(line, writer, out);
        }
    }

    private void handleOneCommand(String line, PrintWriter writer, OutputStream out) throws IOException {
        String[] parts = line.trim().split("\\s+");
        String command = parts[0].toUpperCase();

        switch (command) {
            case "LIST_FILES":
                for (ServerFile f : files) {
                    writer.println("Name: " + f.fileName() + ", ID: " + f.sha256());
                }
                writer.println("END_OF_LIST");
                break;

            case "ONLY_DOWNLOAD":
            case "DOWNLOAD":
                if (parts.length < 3) {
                    writer.println("ERROR: Missing file ID or block number for DOWNLOAD");
                    break;
                }

                String fileId = parts[1];
                int blockInd = Integer.parseInt(parts[2]);

                Optional<ServerFile> fileD = files.stream()
                        .filter(f -> f.sha256().equals(fileId))
                        .findAny();

                if (fileD.isPresent()) {
                    byte[] blockData = fileStorage.getBlock(fileD.get().fileName(), blockInd);
                    out.write(blockData);
                    out.flush();
                } else {
                    writer.println("ERROR: Missing file for DOWNLOAD with ID: " + parts[1]);
                }
                break;

            case "MD5":
                if (parts.length < 3) {
                    writer.println("ERROR: Missing file ID or hash for MD5");
                    break;
                }

                String file2Id = parts[1];
                String file2Hash = parts[2];

                Optional<ServerFile> fileMD = files.stream()
                        .filter(f -> f.sha256().equals(file2Id))
                        .findAny();

                if (fileMD.isPresent()) {
                    if (fileMD.get().md5().equals(file2Hash)) {
                        writer.println("CORRECT");

                        String clientHost = socket.getInetAddress().getHostAddress();
                        int clientPort = socket.getPort();
                        trustedClients.get(fileMD.get().sha256())
                                .add(new TrustedClient(clientHost, clientPort));
                    } else {
                        writer.println("WRONG");
                    }
                } else {
                    writer.println("ERROR: Missing file for MD5 with ID: " + parts[1]);
                }
                break;

            case "CLOSE_CONNECTION":
                writer.println("Connection closing...");
                closed = true;
                return;

            default:
                writer.println("UNKNOWN_COMMAND");
                break;
        }
    }
}
