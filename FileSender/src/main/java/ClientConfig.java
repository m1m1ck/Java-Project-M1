import java.nio.file.Paths;
import java.util.Map;

/**
 * Stores the client's parameters (e.g., server host, server port, file ID, D_c, etc.).
 */
public class ClientConfig extends BaseConfig {
    private String serverHost = "localhost";
    private int serverPort = 12345;
    private String fileId = "random";
    private int dC = 1;
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

        if (argMap.containsKey("port")) {
            config.port = Integer.parseInt(argMap.get("port"));
        }
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

        config.filesDirectory = Paths.get("src", "main", "resources", "Client" + config.hashCode()).toString();

        return config;
    }

    @Override
    public String toString() {
        return "ClientConfig{" +
                "port=" + port +
                ", serverHost='" + serverHost + '\'' +
                ", serverPort=" + serverPort +
                ", fileId='" + fileId + '\'' +
                ", dC=" + dC +
                ", directory" + filesDirectory +
                '}';
    }
}
