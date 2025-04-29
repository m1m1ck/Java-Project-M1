import java.io.File;
import java.util.List;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.text.DecimalFormat;
import java.io.RandomAccessFile;
import java.util.logging.Logger;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.text.DecimalFormatSymbols;
import java.security.NoSuchAlgorithmException;

/**
 * Manages storage operations for the server or client, including file hashing,
 * block retrieval, file saving, and writing client/server statistics.
 */
public class FileStorage {
    /** Logger instance for recording informational and error messages related to file storage operations. */
    private static final Logger logger = Logger.getLogger(FileStorage.class.getName());
    /** The base directory where files are stored or retrieved from. */
    private final String baseDirectory;
    /** The size (in bytes) of each block when reading or writing file chunks. */
    private final int blockSize;
    /** Formatter used to format time values in seconds with six decimal places and a dot as the decimal separator. */
    private final DecimalFormat timeFormat;

    /**
     * Constructs a FileStorage instance tied to a specific directory and block size.
     *
     * @param baseDirectory the directory path where files are stored
     * @param blockSize     the number of bytes per file block
     */
    public FileStorage(String baseDirectory, int blockSize) {
        this.baseDirectory = baseDirectory;
        this.blockSize = blockSize;

        timeFormat = new DecimalFormat("00.000000");
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setDecimalSeparator('.');
        timeFormat.setDecimalFormatSymbols(symbols);

        logger.info("Initializing FileStorage for directory: " + baseDirectory);

        File dir = new File(baseDirectory);
        if (!dir.exists() || !dir.isDirectory()) {
            try{
                Files.createDirectories(dir.toPath());
            } catch (IOException e) {
                logger.warning("Directory does not exist and cannot be created: " + baseDirectory);
                throw new RuntimeException(e);
            }
        }
    }


    /**
     * Writes client statistics to a CSV file named based on the client's port.
     *
     * @param time       time in milliseconds to download the file
     * @param gotHelped  number of times the client received token from trusted client
     * @param helped     number of times the client send token as trusted client
     * @param config     the ClientConfig instance associated with the client
     */
    public void writeClientStats(long time, int gotHelped, int helped, ClientConfig config) {
        String fileName = "../resources/stats/client" + config.getPort() + ".csv";
        double seconds = time / 1000.0;

        List<String> lines = List.of(
                "time to download, " + timeFormat.format(seconds),
                "got helped, " + gotHelped,
                "helped, " + helped
        );

        writeLinesToFile(fileName, lines);
    }

    /**
     * Writes server statistics (e.g., number of closed connections) to a fixed CSV file.
     *
     * @param closed number of connections closed by the server
     */
    public void writeServerStats(int closed) {
        String fileName = "../resources/stats/outputserver.csv";
        List<String> lines = List.of("closed connections, " + closed);
        writeLinesToFile(fileName, lines);
    }

    /**
     * Writes lines of text to a file, overwriting existing content.
     *
     * @param fileName the target file path
     * @param lines    the content lines to write
     */
    private void writeLinesToFile(String fileName, List<String> lines) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(fileName, false))) {
            for (String line : lines) {
                writer.println(line);
            }
        } catch (IOException e) {
            logger.warning("Failed to write file: " + fileName + ". Error: " + e.getMessage());
        }
    }

    /**
     * Returns a list of available files as ServerFile objects.
     * Uses the SHA-256 hash of the file content as the file identifier.
     *
     * @return a list of files with their SHA-256 and MD5 hashes
     */
    public List<ServerFile> getFiles() {
        List<ServerFile> fileHashes = new ArrayList<>();
        File folder = new File(baseDirectory);
        File[] files = folder.listFiles();

        if (files != null) {
            for (File file : files) {
                if (!file.isFile()) continue;
                try {
                    String shaHash = generateHash(file, "SHA-256");
                    String md5Hash = generateHash(file, "MD5");
                    fileHashes.add(new ServerFile(file.getName(), shaHash, md5Hash));
                } catch (IOException | NoSuchAlgorithmException e) {
                    e.printStackTrace();
                }
            }
        }
        return fileHashes;
    }

    /**
     * Computes a hash for a given file using the specified algorithm.
     *
     * @param file      the file to hash
     * @param algorithm hash algorithm name ("MD5", "SHA-256")
     * @return hexadecimal string representation of the hash
     * @throws IOException              if reading the file fails
     * @throws NoSuchAlgorithmException if the algorithm is not available
     */
    private String generateHash(File file, String algorithm) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance(algorithm);

        byte[] fileBytes = Files.readAllBytes(file.toPath());
        byte[] hashBytes = digest.digest(fileBytes);

        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            hexString.append(String.format("%02x", b));
        }

        return hexString.toString();
    }

    /**
     * Returns the MD5 checksum of the specified file.
     *
     * @param filename name of the file to hash
     * @return the MD5 hash string
     * @throws IOException              if file reading fails
     * @throws NoSuchAlgorithmException if MD5 algorithm is unavailable
     */
    public String getMD5(String filename) throws IOException, NoSuchAlgorithmException {
        File file = new File(baseDirectory + "/" + filename);
        return generateHash(file, "MD5");
    }

    /**
     * Retrieves a specific block of a file based on the block index.
     *
     * @param filename    the name of the file
     * @param blockIndex  the index of the block to retrieve (0-based)
     * @return a byte array containing the requested block, or an empty array if index is out of range
     * @throws IOException if the file does not exist or cannot be read
     */
    public byte[] getBlock(String filename, int blockIndex) throws IOException {
        File file = new File(baseDirectory + "/" + filename);

        if (!file.exists()) {
            throw new IOException("File does not exist: " + file.getAbsolutePath());
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileLength = raf.length();
            long totalBlocks = (fileLength + blockSize - 1) / blockSize;

            if (blockIndex < 0 || blockIndex >= totalBlocks) {
                return new byte[0];
            }

            long position = (long) blockIndex * blockSize;
            int actualBlockSize = (int) Math.min(blockSize, fileLength - position);

            byte[] block = new byte[actualBlockSize];
            raf.seek(position);
            raf.readFully(block);

            return block;
        }
    }

    /**
     * Saves a byte array as a file in the configured base directory.
     *
     * @param fileData the byte content of the file
     * @param fileName the name to assign to the saved file
     */
    public void saveFile(byte[] fileData, String fileName) {
        File dir = new File(baseDirectory);

        File outputFile = new File(dir, fileName);

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(fileData);
            logger.info("File downloaded: " + outputFile.getAbsolutePath());
        } catch (IOException e) {
            logger.info("Error on saving file : " + e.getMessage());
        }
    }
}
