import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

/**
 * Stores the client's parameters (e.g., server host, server port, file ID, D_c, etc.).
 */
public class ClientConfig extends BaseConfig {
    private String serverHost = "localhost";
    private int serverPort = 12345;
    private String fileId = "random";
    private int dC = 1;
    private float pC = 0.2F;
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
    public float getPC() {
        return pC;
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
        if (argMap.containsKey("B")) {
            config.b = Integer.parseInt(argMap.get("B"));
        }
        if (argMap.containsKey("Pc")) {
            config.pC = Float.parseFloat(argMap.get("Pc"));
        }

        config.filesDirectory = "../resources/ClientPort" + config.port;

        return config;
    }

    @Override
    public String toString() {
        return "ClientConfig{" +
                "port=" + port +
                ", serverHost=" + serverHost +
                ", serverPort=" + serverPort +
                ", fileId=" + fileId +
                ", dC=" + dC +
                ", directory=" + filesDirectory + '\'' +
                ", B=" + b +
                ", Pc=" + pC +
                '}';
    }
}
