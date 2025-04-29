import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicLong;

public class MetricsLogger {
    private final PrintWriter out;
    private final AtomicLong bytesDownloaded = new AtomicLong(0);

    public MetricsLogger(String csvPath) throws IOException {
        // En-tête CSV
        this.out = new PrintWriter(new FileWriter(csvPath, false));
        out.println("timestamp,dc,P,T,Cs,bytesDownloaded");
    }

    /** À appeler chaque fois que vous recevez un bloc */
    public void addBytes(long n) {
        bytesDownloaded.addAndGet(n);
    }

    /** Dump instantané des métriques */
    public synchronized void logSnapshot(int dc, double P, int T, int Cs) {
        long ts = System.currentTimeMillis();
        long bytes = bytesDownloaded.get();
        out.printf("%d,%d,%.3f,%d,%d,%d%n", ts, dc, P, T, Cs, bytes);
        out.flush();
    }

    public void close() {
        out.close();
    }
}
