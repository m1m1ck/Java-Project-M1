import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Test {

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
                case "DC", "file", "Pc" -> clientCommonArgs.add("--" + key + "=" + value);
                case "clients","clientsDelay" -> { /* ignoré */ }
                default -> throw new IllegalArgumentException("Unknown argument: " + key);
            }
        }

        // Lance le serveur
        Process serverProcess = launchProcess(
            "Server",
            serverArgs
        );
        Thread.sleep(1_000);

        // Lance les clients
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

        // Attends la fin des clients
        for (Process cp : clientProcesses) {
            cp.waitFor();
        }

        // Stoppe le serveur
        serverProcess.destroy();
    }

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
