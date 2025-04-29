import java.util.Map;

/**
 * Holds server configuration parameters such as port, maximum number of parallel connections (Cs),
 * probability of connection closure (P), frequency of closure attempts (T),
 * and the directory for server files.
 * <p>
 * Inherits common configuration logic from {@link BaseConfig}.
 */
public class ServerConfig extends BaseConfig {

    /** Maximum number of simultaneous client connections. */
    private int cs = 5;

    /** Probability of a connection being closed during a check. */
    private double p = 0.2;

    /** Interval in seconds at which to attempt connection closure. */
    private double t = 10;

    /**
     * Returns the maximum number of concurrent connections (Cs).
     * @return the number of allowed parallel connections
     */
    public int getCs() {
        return cs;
    }

    /**
     * Returns the probability (P) of connection closure.
     * @return the connection closure probability
     */
    public double getP() {
        return p;
    }

    /**
     * Returns the time interval (T) in seconds for closure attempts.
     * @return the closure check interval in seconds
     */
    public double getT() {
        return t;
    }

    /**
     * Creates and configures a {@code ServerConfig} instance by parsing command-line arguments
     * in the format {@code --key=value}.
     *
     * <p>Recognized keys:
     * <ul>
     *     <li><b>port</b> - server port</li>
     *     <li><b>Cs</b> - maximum parallel connections</li>
     *     <li><b>P</b> - connection closure probability</li>
     *     <li><b>T</b> - closure check interval</li>
     *     <li><b>filesDir</b> - path to server files</li>
     *     <li><b>B</b> - number of bytes per block request</li>
     * </ul>
     *
     * <p>If {@code filesDir} is not set, a default path is used.
     *
     * @param args the command-line arguments
     * @return a fully populated {@code ServerConfig} object
     */
    public static ServerConfig fromArgs(String[] args) {
        ServerConfig config = new ServerConfig();
        Map<String, String> argMap = parseArgs(args);

        if (argMap.containsKey("port")) {
            config.port = Integer.parseInt(argMap.get("port"));
        }
        if (argMap.containsKey("Cs")) {
            config.cs = Integer.parseInt(argMap.get("Cs"));
        }
        if (argMap.containsKey("P")) {
            config.p = Double.parseDouble(argMap.get("P"));
        }
        if (argMap.containsKey("T")) {
            config.t = Double.parseDouble(argMap.get("T"));
        }
        if (argMap.containsKey("filesDir")) {
            config.filesDirectory = argMap.get("filesDir");
        }
        if (argMap.containsKey("B")) {
            config.b = Integer.parseInt(argMap.get("B"));
        }

        // Set default directory path
        config.filesDirectory = "../resources/ServerResources/";

        return config;
    }

    /**
     * Returns a string representation of this configuration.
     * @return a string with all server config values
     */
    @Override
    public String toString() {
        return "ServerConfig{" +
                "port=" + port +
                ", cs=" + cs +
                ", p=" + p +
                ", t=" + t +
                ", filesDirectory='" + filesDirectory + '\'' +
                ", b=" + b +
                '}';
    }
}
