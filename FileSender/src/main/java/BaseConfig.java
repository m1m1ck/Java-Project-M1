import java.util.HashMap;
import java.util.Map;

/**
 * Abstract base class for configurations, containing common logic.
 */
public abstract class BaseConfig {

    protected int port;
    protected String filesDirectory;

    public int getPort() { return port; }
    public String getFilesDirectory() { return filesDirectory; }

    /**
     *  Parses command line arguments in the format --key=value
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
