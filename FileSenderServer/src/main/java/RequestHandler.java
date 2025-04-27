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
    private final List<Socket> activeSockets;
    private final Map<String, List<TrustedClient>> trustedClients;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final String command;
    private boolean closed;

    public RequestHandler(List<Socket> activeSockets, Socket socket, FileStorage fileStorage, List<ServerFile> files, Map<String, List<TrustedClient>> trustedClients) {
        this.socket = socket;
        this.activeSockets = activeSockets;
        this.fileStorage = fileStorage;
        this.files = files;
        this.trustedClients = trustedClients;
        this.reader = null;
        this.writer = null;
        this.command = null;
    }

    public RequestHandler(List<Socket> activeSockets, Socket socket, BufferedReader reader, PrintWriter writer, String command, FileStorage fileStorage, List<ServerFile> files, Map<String, List<TrustedClient>> trustedClients) {
        this.socket = socket;
        this.activeSockets = activeSockets;
        this.fileStorage = fileStorage;
        this.files = files;
        this.trustedClients = trustedClients;
        this.reader = reader;
        this.writer = writer;
        this.command = command;
    }

    @Override
    public void run() {
        try (OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out)) {

            if (command == null) {
                try (InputStream in = socket.getInputStream();
                     BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(in));
                     PrintWriter bufferedWriter = new PrintWriter(out, true)) {

                    handleCommands(bufferedReader, bufferedWriter, dataOut);
                }
            } else {
                handleOneCommand(command, writer, dataOut);
                handleCommands(reader, writer, dataOut);
            }
        } catch (IOException e) {
            /*logger.warning("I/O error with client " + socket.getRemoteSocketAddress() +
                    ": " + e.getMessage());*/
        } finally {
            try {
                synchronized (activeSockets) {
                    activeSockets.remove(socket);
                }
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                logger.warning("Error closing socket: " + e.getMessage());
            }
            //slogger.info("Connection closed: " + socket.getRemoteSocketAddress());
        }
    }

    private void handleCommands(BufferedReader reader, PrintWriter writer, DataOutputStream dataOut) throws IOException {
        String line;
        while ((line = reader.readLine()) != null && !closed) {
            handleOneCommand(line, writer, dataOut);
        }
    }

    private void handleOneCommand(String line, PrintWriter writer, DataOutputStream dataOut) throws IOException {
        String[] parts = line.trim().split("\\s+");
        String command = parts[0].toUpperCase();

        switch (command) {
            case "LIST_FILES" -> {
                for (ServerFile f : files) {
                    writer.println("Name: " + f.fileName() + ", ID: " + f.sha256());
                }
                writer.println("END_OF_LIST");
            }
            case "DOWNLOAD" -> {
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
                    writer.println("SENDING");
                    byte[] blockData = fileStorage.getBlock(fileD.get().fileName(), blockInd);

                    dataOut.writeInt(blockData.length);
                    dataOut.write(blockData);
                    dataOut.flush();
                } else {
                    logger.warning("DOWNLOAD failed: file not found for ID " + parts[1]);
                    writer.println("ERROR: Missing file for DOWNLOAD with ID: " + parts[1]);
                }
            }
            case "MD5" -> {
                if (parts.length < 3) {
                    writer.println("ERROR: Missing file ID or hash for MD5");
                    break;
                }
                String fileId = parts[1];
                String fileHash = parts[2];
                Optional<ServerFile> fileMD = files.stream()
                        .filter(f -> f.sha256().equals(fileId))
                        .findAny();
                if (fileMD.isPresent()) {
                    if (fileMD.get().md5().equals(fileHash)) {
                        writer.println("CORRECT");

                        String clientHost = socket.getInetAddress().getHostAddress();
                        int clientPort = socket.getPort();
                        trustedClients.get(fileMD.get().sha256())
                                .add(new TrustedClient(clientHost, clientPort));
                        logger.info("MD5 verified and client trusted: " + clientHost + ":" + clientPort);
                    } else {
                        logger.warning("MD5 mismatch for file ID: " + fileId +  ". Expected: " + fileMD.get().md5() + ". Received: " + fileHash);
                        writer.println("WRONG");
                    }
                } else {
                    logger.warning("MD5 check failed: file not found for ID " + fileId);
                    writer.println("ERROR: Missing file for MD5 with ID: " + parts[1]);
                }
            }
            case "CLOSE_CONNECTION" -> {
                writer.println("Connection closing...");
                closed = true;
            }
            default -> {
                logger.warning("Unknown command received: " + command);
                writer.println("UNKNOWN_COMMAND");
            }
        }
    }
}
