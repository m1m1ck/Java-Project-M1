// --- RequestHandler.java ---
import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

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
        this(activeSockets, socket, null, null, null, fileStorage, files, trustedClients);
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
            // I/O error
        } finally {
            synchronized (activeSockets) { activeSockets.remove(socket); }
            try { if (!socket.isClosed()) socket.close(); } catch (IOException ignored) {}
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
