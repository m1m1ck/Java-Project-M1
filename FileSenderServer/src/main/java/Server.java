import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class Server {
    private static final Logger logger = Logger.getLogger(Server.class.getName());
    private static Map<String, List<TrustedClient>> trustedClients;
    private static List<ServerFile> files;

    public static void main(String[] args) throws IOException {
        ServerConfig config = ServerConfig.fromArgs(args);
        logger.info("Server starting: " + config);

        // Initialize file storage and file list
        FileStorage storage = new FileStorage(config.getFilesDirectory(), config.getB());
        files = storage.getFiles();

        // Prepare trusted clients map
        trustedClients = new ConcurrentHashMap<>();
        for (ServerFile f : files) {
            trustedClients.put(f.sha256(), Collections.synchronizedList(new ArrayList<>()));
        }

        // Executor for handling client requests
        final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(config.getCs());
        List<Socket> active = Collections.synchronizedList(new ArrayList<>());

        // --- Metrics setup ---
        MetricsLogger metrics = new MetricsLogger("results_server.csv");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            int activeCount = executor.getActiveCount();
            metrics.logSnapshot(activeCount, config.getP(), config.getT(), config.getCs());
        }, 0, 1, TimeUnit.SECONDS);

        // Start disconnection simulator
        startDisconnector(active, config.getP(), config.getT());

        // Listen for client connections
        try (ServerSocket serverSocket = new ServerSocket(config.getPort())) {
            logger.info("Listening on port " + config.getPort());
            while (true) {
                Socket client = serverSocket.accept();
                logger.info("New client: " + client.getRemoteSocketAddress());
                active.add(client);

                if (executor.getActiveCount() < config.getCs()) {
                    executor.execute(new RequestHandler(active, client, storage, files, trustedClients));
                } else {
                    handleFallback(active, executor, client, storage);
                }
            }
        } finally {
            scheduler.shutdown();
            executor.shutdown();
        }
    }

    private static void startDisconnector(List<Socket> active, double p, int interval) {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor();
        s.scheduleAtFixedRate(() -> {
            if (Math.random() < p && !active.isEmpty()) {
                synchronized (active) {
                    int i = ThreadLocalRandom.current().nextInt(active.size());
                    try { active.get(i).close(); }
                    catch (IOException ignored) {}
                }
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    private static void handleFallback(
            List<Socket> active,
            ExecutorService exec,
            Socket client,
            FileStorage storage) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter writer = new PrintWriter(client.getOutputStream(), true)) {

            String cmd = reader.readLine();
            if (!tryFindTrusted(cmd, writer)) {
                exec.execute(new RequestHandler(active, client, reader, writer, cmd, storage, files, trustedClients));
            }
        } catch (IOException e) {
            logger.warning("Fallback error: " + e.getMessage());
        }
    }

    private static boolean tryFindTrusted(String cmd, PrintWriter writer) {
        if (cmd == null || !cmd.startsWith("DOWNLOAD")) return false;
        String[] p = cmd.split("\\s+");
        if (p.length < 3) return false;
        String fileId = p[1];
        int idx;
        try { idx = Integer.parseInt(p[2]); }
        catch(NumberFormatException e) { return false; }

        for (TrustedClient peer : trustedClients.getOrDefault(fileId, Collections.emptyList())) {
            try (Socket sock = new Socket(peer.host(), peer.port());
                 PrintWriter pw = new PrintWriter(sock.getOutputStream(), true);
                 BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream()))) {
                pw.println("TOKEN_REQUEST " + fileId + " " + idx);
                String resp = br.readLine();
                if (resp != null && resp.startsWith("TOKEN ")) {
                    writer.println(resp);
                    return true;
                }
            } catch (IOException ignored) {
                // peer unreachable
            }
        }
        return false;
    }

    // --- MetricsLogger inner class ---
    private static class MetricsLogger {
        private final PrintWriter out;
        public MetricsLogger(String path) throws IOException {
            out = new PrintWriter(new FileWriter(path, false));
            out.println("timestamp,active,P,T,Cs");
        }
        public synchronized void logSnapshot(int active, double P, int T, int Cs) {
            long ts = System.currentTimeMillis();
            out.printf(java.util.Locale.US, "%d,%d,%.3f,%d,%d%n", ts, active, P, T, Cs);
            out.flush();
        }
    }
}