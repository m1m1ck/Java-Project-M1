import java.util.Map;

/**
 * Stores the client's configuration parameters for connecting to and interacting with the server.
 * <p>
 * Includes network settings, target file identifier, concurrency parameters, and behavior probabilities.
 * Inherits common configuration options from {@link BaseConfig}.
 */
public class ClientConfig extends BaseConfig {

    /** Hostname or IP address of the server to request files from. */
    private String serverHost = "localhost";

    /** Port of the server to connect to for file requests. */
    private int serverPort = 12345;

    /**
     * File ID to download from the server.
     * <p>
     * If set to "random", a random file is selected from the server's resources.
     * The file ID usually corresponds to the SHA-256 hash of the file.
     */
    private String fileId = "random";

    /** Number of concurrent requests the client can make to download blocks. */
    private int dc = 1;

    /** Probability (between 0 and 1) that the client will deny a token request. */
    private float pc = 0.2F;

    /**
     * Returns the server host.
     * @return the server host
     */
    public String getServerHost() {
        return serverHost;
    }

    /**
     * Returns the server port.
     * @return the server port
     */
    public int getServerPort() {
        return serverPort;
    }

    /**
     * Returns the file ID the client wants to download.
     * @return the file ID
     */
    public String getFileId() {
        return fileId;
    }

    /**
     * Returns the number of concurrent block download requests allowed.
     * @return the concurrency level (D<sub>c</sub>)
     */
    public int getDC() {
        return dc;
    }

    /**
     * Returns the probability that the client denies a token request.
     * @return the probability value (P<sub>c</sub>)
     */
    public float getPC() {
        return pc;
    }

    /**
     * Parses command-line arguments in the format {@code --key=value}
     * and returns a configured {@code ClientConfig} instance.
     * <p>
     * Recognized keys:
     * <ul>
     *     <li><b>port</b> - client port (used to name the file directory)</li>
     *     <li><b>serverHost</b> - server address</li>
     *     <li><b>serverPort</b> - server port</li>
     *     <li><b>file</b> - file ID to request from the server</li>
     *     <li><b>Dc</b> - number of concurrent block requests</li>
     *     <li><b>B</b> - number of bytes per block request</li>
     *     <li><b>Pc</b> - probability of denying a token request</li>
     * </ul>
     * <p>
     * The {@code filesDirectory} is automatically set to {@code ../resources/ClientPort<port>}.
     *
     * @param args command-line arguments
     * @return a configured {@code ClientConfig} object
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
        if (argMap.containsKey("Dc")) {
            config.dc = Integer.parseInt(argMap.get("Dc"));
        }
        if (argMap.containsKey("B")) {
            config.b = Integer.parseInt(argMap.get("B"));
        }
        if (argMap.containsKey("Pc")) {
            config.pc = Float.parseFloat(argMap.get("Pc"));
        }

        config.filesDirectory = "../resources/ClientPort" + config.port;

        return config;
    }

    /**
     * Returns a string representation of this configuration.
     * @return a formatted string of all configuration values
     */
    @Override
    public String toString() {
        return "ClientConfig{" +
                "port=" + port +
                ", serverHost=" + serverHost +
                ", serverPort=" + serverPort +
                ", fileId=" + fileId +
                ", dc=" + dc +
                ", filesDirectory=" + filesDirectory + '\'' +
                ", b=" + b +
                ", pc=" + pc +
                '}';
    }
}
