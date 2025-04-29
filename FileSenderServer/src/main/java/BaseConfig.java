import java.util.Map;
import java.util.HashMap;

/**
 * Abstract base class for server configurations.
 * <p>
 * Provides common configuration properties such as port number and file storage directory,
 * along with utility method for parsing command-line arguments.
 */
public abstract class BaseConfig {

    /** The port number the server should listen on. */
    protected int port;

    /** The directory path where files should be stored or retrieved from. */
    protected String filesDirectory;

    /** Number of bytes per block request. Used to control the size of data chunks handled by the server. */
    protected int b = 100;

    /**
     * Gets the port number.
     * @return the configured port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Gets the value of configuration parameter {@code b}.
     * @return the value of {@code b}
     */
    public int getB() {
        return b;
    }

    /**
     * Gets the directory path where files are stored.
     * @return the file directory path
     */
    public String getFilesDirectory() {
        return filesDirectory;
    }

    /**
     * Parses command-line arguments in the format {@code --key=value} and returns them as a map.
     * @param args the command-line arguments
     * @return a map of argument keys and values
     */
    protected static Map<String, String> parseArgs(String[] args) {
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
}
