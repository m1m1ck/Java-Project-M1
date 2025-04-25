import java.util.Map;

/**
 * Holds server configuration parameters such as port, Cs, T, P,
 * and the directory containing files for the server.
 */
public class ServerConfig extends BaseConfig {
    private int Cs = 5;           // Maximum number of parallel connections
    private double P = 0.2;       // Probability of connection closure
    private int T = 10;           // Frequency (in seconds) to attempt closure

    // Getters
    public int getCs() { return Cs; }
    public double getP() { return P; }
    public int getT() { return T; }

    /**
     * Parses command-line arguments in the form of --key=value
     * and creates a ServerConfig object with the specified settings.
     *
     * @param args command-line arguments
     * @return a configured ServerConfig instance
     */
    public static ServerConfig fromArgs(String[] args) {
        ServerConfig config = new ServerConfig();
        Map<String, String> argMap = parseArgs(args);

        if (argMap.containsKey("port")) {
            config.port = Integer.parseInt(argMap.get("port"));
        }
        if (argMap.containsKey("Cs")) {
            config.Cs = Integer.parseInt(argMap.get("Cs"));
        }
        if (argMap.containsKey("P")) {
            config.P = Double.parseDouble(argMap.get("P"));
        }
        if (argMap.containsKey("T")) {
            config.T = Integer.parseInt(argMap.get("T"));
        }
        if (argMap.containsKey("filesDir")) {
            config.filesDirectory = argMap.get("filesDir");
        }

        config.filesDirectory = config.getClass().getClassLoader().getResource("ServerResources/").getPath();

        return config;
    }

    @Override
    public String toString() {
        return "ServerConfig{" +
                "port=" + port +
                ", Cs=" + Cs +
                ", P=" + P +
                ", T=" + T +
                ", filesDirectory='" + filesDirectory + '\'' +
                ", b='" + b +
                '}';
    }
}
