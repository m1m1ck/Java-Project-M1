import java.util.HashMap;
import java.util.Map;

/**
 * Stores the client's parameters (e.g., server host, server port, file ID, D_c, etc.).
 */
public class ClientConfig {
    private String serverHost = "localhost";
    private int serverPort = 12345;
    private String fileId = "file1";
    private int dC = 1; // Number of parallel connections
    // Add more if needed, e.g., Pc, etc.

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public String getFileId() {
        return fileId;
    }

    public int getDC() {
        return dC;
    }

    /**
     * Parse command-line arguments in the format --key=value
     */
    public static ClientConfig fromArgs(String[] args) {
        ClientConfig config = new ClientConfig();
        Map<String, String> argMap = parseArgs(args);

        if (argMap.containsKey("serverHost")) {
            config.serverHost = argMap.get("serverHost");
        }
        if (argMap.containsKey("serverPort")) {
            config.serverPort = Integer.parseInt(argMap.get("serverPort"));
        }
        if (argMap.containsKey("file")) {
            config.fileId = argMap.get("file");
        }
        if (argMap.containsKey("DC")) {
            config.dC = Integer.parseInt(argMap.get("DC"));
        }
        return config;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                String[] parts = arg.substring(2).split("=", 2);
                if (parts.length == 2) {
                    map.put(parts[0], parts[1]);
                }
            }
        }
        return map;
    }

    @Override
    public String toString() {
        return "ClientConfig{" +
                "serverHost='" + serverHost + '\'' +
                ", serverPort=" + serverPort +
                ", fileId='" + fileId + '\'' +
                ", dC=" + dC +
                '}';
    }
}
