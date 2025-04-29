import java.util.List;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Utility launcher class for testing the system by starting the server and multiple clients.
 * <p>
 * This class reads command-line arguments, parses them into server and client parameters,
 * launches the server process, and then launches a configurable number of clients with a delay between each.
 * <p>
 * This is helpful for testing concurrency, performance, or behavior under varying numbers of client connections.
 */
public class Test {

    /**
     * Entry point of the testing application.
     * <p>
     * Parses command-line arguments, launches the server and multiple client processes,
     * waits for clients to complete, and shuts down the server process.
     *
     * <p><b>Supported arguments:</b>
     * <ul>
     *     <li><code>serverPort</code>: port the server listens on</li>
     *     <li><code>serverHost</code>: server host address (for clients)</li>
     *     <li><code>clients</code>: number of clients to launch</li>
     *     <li><code>clientsDelay</code>: delay between launching each client (ms)</li>
     *     <li><code>P, T, Cs, filesDir, B, Dc, file, Pc</code>: forwarded to server or client as appropriate</li>
     * </ul>
     *
     * @param args command-line arguments
     * @throws Exception if any process fails or an argument is invalid
     */
    public static void main(String[] args) throws Exception {
        var argMap = parseArgs(args);

        int clientsCount = Integer.parseInt(argMap.getOrDefault("clients", "1"));

        int clientsDelay= Integer.parseInt(argMap.getOrDefault("clientsDelay", "10000"));
        List<String> serverArgs = new ArrayList<>();
        List<String> clientCommonArgs = new ArrayList<>();

        for (var entry : argMap.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            switch (key) {
                case "serverPort" -> {
                    serverArgs.add("--port=" + value);
                    clientCommonArgs.add("--serverPort=" + value);
                }
                case "serverHost" -> clientCommonArgs.add("--serverHost=" + value);
                case "P", "T", "Cs", "filesDir" -> serverArgs.add("--" + key + "=" + value);
                case "B" -> {
                    serverArgs.add("--B=" + value);
                    clientCommonArgs.add("--B=" + value);
                }
                case "Dc", "file", "Pc" -> clientCommonArgs.add("--" + key + "=" + value);
                case "clients","clientsDelay" -> { /* ignoré */ }
                default -> throw new IllegalArgumentException("Unknown argument: " + key);
            }
        }

        Process serverProcess = launchProcess(
            "Server",
            serverArgs
        );
        Thread.sleep(1_000);

        List<Process> clientProcesses = new ArrayList<>();
        for (int i = 0; i < clientsCount; i++) {
            List<String> clientArgs = new ArrayList<>(clientCommonArgs);
            int clientPort = 5000 + i;
            clientArgs.add("--port=" + clientPort);

            Process clientProcess = launchProcess(
                "Client",
                clientArgs
            );
            clientProcesses.add(clientProcess);
            Thread.sleep(clientsDelay);
        }

        for (Process cp : clientProcesses) {
            cp.waitFor();
        }

        serverProcess.destroy();
    }

    /**
     * Launches a new Java process for the specified class with given arguments.
     * <p>
     * The process inherits I/O streams from the parent and is assumed to be compiled under a specific output directory.
     *
     * @param fqcn      fully qualified class name to run (e.g., "Server", "Client")
     * @param arguments list of command-line arguments to pass
     * @return a started {@link Process} instance
     * @throws IOException if the process fails to launch
     */
    private static Process launchProcess(String fqcn, List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-cp");
        command.add("../../../out/production/FileSenderServer");
        command.add(fqcn);
        command.addAll(arguments);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        return pb.start();
    }

    /**
     * Parses command-line arguments into a key-value map.
     * <p>
     * Only accepts arguments in the format <code>--key=value</code>.
     *
     * @param args raw command-line arguments
     * @return a map of parsed argument keys and their values
     */
    private static java.util.Map<String, String> parseArgs(String[] args) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
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
