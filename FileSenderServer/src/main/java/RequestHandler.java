import java.io.*;
import java.util.Map;
import java.util.List;
import java.net.Socket;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Handles a single client connection and processes file-related requests on the server side.
 * <p>
 * Supports operations such as listing available files, serving file blocks for download,
 * verifying file integrity via MD5, and registering clients as trusted peers for future sharing.
 * <p>
 * Each instance runs in a separate thread and manages its own socket connection.
 * After processing is complete or the connection is closed, the socket is removed from the active list.
 *
 * @see TrustedClient
 * @see ServerFile
 * @see FileStorage
 */
public class RequestHandler implements Runnable {

    /** Logger instance for logging request handler events. */
    private static final Logger logger = Logger.getLogger(RequestHandler.class.getName());

    /** The socket associated with this client connection. */
    private final Socket socket;

    /** The file storage manager used to access and serve file data. */
    private final FileStorage fileStorage;

    /** The list of files currently hosted on the server. */
    private final List<ServerFile> files;

    /** The list of currently active client sockets. */
    private final List<Socket> activeSockets;

    /** The mapping of file IDs to trusted clients that have successfully downloaded them. */
    private final Map<String, List<TrustedClient>> trustedClients;

    /** Reader for receiving text commands from the client (optional in single-command mode). */
    private final BufferedReader reader;

    /** Writer for sending text responses to the client (optional in single-command mode). */
    private final PrintWriter writer;

    /** A single pre-parsed command (used in single-command mode). */
    private final String command;

    /** Indicates whether the connection has been explicitly closed. */
    private boolean closed;

    /**
     * Constructor used in interactive mode (command will be read from input stream).
     *
     * @param activeSockets   list of currently active client sockets
     * @param socket          client socket for this connection
     * @param fileStorage     file storage for serving data
     * @param files           list of files available on the server
     * @param trustedClients  map of file IDs to trusted clients
     */
    public RequestHandler(List<Socket> activeSockets,
                          Socket socket,
                          FileStorage fileStorage,
                          List<ServerFile> files,
                          Map<String, List<TrustedClient>> trustedClients) {
        this(activeSockets, socket, null, null, null, fileStorage, files, trustedClients);
    }

    /**
     * Constructor used in single-command mode (used for trusted client interaction).
     *
     * @param activeSockets   list of currently active client sockets
     * @param socket          client socket for this connection
     * @param reader          buffered reader to receive commands
     * @param writer          print writer to send responses
     * @param command         initial command to process
     * @param fileStorage     file storage for serving data
     * @param files           list of files available on the server
     * @param trustedClients  map of file IDs to trusted clients
     */
    public RequestHandler(List<Socket> activeSockets,
                          Socket socket,
                          BufferedReader reader,
                          PrintWriter writer,
                          String command,
                          FileStorage fileStorage,
                          List<ServerFile> files,
                          Map<String, List<TrustedClient>> trustedClients) {
        this.socket = socket;
        this.activeSockets = activeSockets;
        this.fileStorage = fileStorage;
        this.files = files;
        this.trustedClients = trustedClients;
        this.reader = reader;
        this.writer = writer;
        this.command = command;
    }

    /**
     * The main execution method for this request handler.
     * <p>
     * Read commands either from a socket stream or from a pre-parsed command,
     * processes them, and sends responses. Cleans up and removes the socket after use.
     */
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

        } finally {
            synchronized (activeSockets) {
                activeSockets.remove(socket);
            }
            try {
                if (!socket.isClosed()) socket.close();
            } catch (IOException ignored) {

            }
        }
    }

    /**
     * Continuously reads and handles commands from the input stream until the connection is closed.
     *
     * @param reader   the input reader for receiving commands
     * @param writer   the writer for sending responses
     * @param dataOut  the binary data output stream for sending file blocks
     * @throws IOException if an I/O error occurs
     */
    private void handleCommands(BufferedReader reader, PrintWriter writer, DataOutputStream dataOut) throws IOException {
        String line;
        while ((line = reader.readLine()) != null && !closed) {
            handleOneCommand(line, writer, dataOut);
        }
    }

    /**
     * Parses and processes a single command sent by the client.
     * Handles listing files, downloading blocks, verifying file hashes, and more.
     *
     * @param line     the command line input
     * @param writer   the writer for sending responses
     * @param dataOut  the binary stream for sending file block data
     * @throws IOException if an I/O or parsing error occurs
     */
    private void handleOneCommand(String line, PrintWriter writer, DataOutputStream dataOut) throws IOException {
        String[] parts = line.trim().split("\\s+");
        String cmd = parts[0].toUpperCase();

        switch (cmd) {
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
                    writer.println("ERROR: Missing file for DOWNLOAD with ID: " + fileId);
                }
            }
            case "MD5" -> {
                if (parts.length < 4) {
                    writer.println("ERROR: Missing file ID or hash or port for MD5");
                    break;
                }

                String fileId = parts[1], fileHash = parts[2];
                int port = Integer.parseInt(parts[3]);

                Optional<ServerFile> fileMD = files.stream()
                        .filter(f -> f.sha256().equals(fileId))
                        .findAny();

                if (fileMD.isPresent() && fileMD.get().md5().equals(fileHash)) {
                    writer.println("CORRECT");

                    String clientHost = socket.getInetAddress().getHostAddress();
                    trustedClients.get(fileMD.get().sha256())
                            .add(new TrustedClient(clientHost, port));

                    logger.info("Added trusted client: " + clientHost + " " + port);
                } else {
                    writer.println("WRONG");
                }
            }
            case "CLOSE_CONNECTION" -> {
                writer.println("Connection closing...");
                closed = true;
            }
            default -> {
                writer.println("UNKNOWN_COMMAND");
            }
        }
    }
}
