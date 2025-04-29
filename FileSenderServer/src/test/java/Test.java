import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Test {

    public static void main(String[] args) throws Exception {
        var argMap = parseArgs(args);

        int clientsCount = Integer.parseInt(argMap.getOrDefault("clients", "1"));

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
                case "clients" -> {
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + key);
            }
        }

        Process serverProcess = launchProcess("Server", serverArgs);
        Thread.sleep(1000);

        List<Process> clientProcesses = new ArrayList<>();

        for (int i = 0; i < clientsCount; i++) {
            List<String> clientArgs = new ArrayList<>(clientCommonArgs);

            int clientPort = 5000 + i;
            clientArgs.add("--port=" + clientPort);

            Process clientProcess = launchProcess("Client", clientArgs);
            clientProcesses.add(clientProcess);
            Thread.sleep(10000);
        }

        for (Process clientProcess : clientProcesses) {
            clientProcess.waitFor();
        }

        serverProcess.destroy();
    }

    private static Process launchProcess(String mainClass, List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>();

        command.add(System.getProperty("java.home") + "/bin/java");

        command.add("-cp");
        command.add(System.getProperty("java.class.path"));

        command.add(mainClass);

        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.inheritIO();

        return builder.start();
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
