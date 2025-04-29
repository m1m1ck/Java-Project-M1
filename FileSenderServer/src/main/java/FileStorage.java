import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages server files, including reading the directory,
 * computing (or storing) file MD5, and retrieving blocks.
 */
public class FileStorage {
    private static final Logger logger = Logger.getLogger(FileStorage.class.getName());
    private final String baseDirectory;
    private final int blockSize;

    /**
     * @param baseDirectory the directory path where files are located
     * Initializes the FileStorage by scanning the directory
     */
    public FileStorage(String baseDirectory, int blockSize) {
        this.baseDirectory = baseDirectory;
        this.blockSize = blockSize;
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


    public void createclientcsv(long time, int gothelped, int helped ,ClientConfig config) {
        String fileName = "../resources/stats/client"+ config.getPort() +".csv";

        double seconds = time / 1000.0;
        DecimalFormat df = new DecimalFormat("00.000000");
        DecimalFormatSymbols decimalFormatSymbols = new DecimalFormatSymbols();
        decimalFormatSymbols.setDecimalSeparator('.');
        df.setDecimalFormatSymbols(decimalFormatSymbols);

        String line1 = "time to download, "   + df.format(seconds);
        String line2 = "got helped, " + gothelped;
        String line3 = "helped, " + helped;
        

        try (PrintWriter writer = new PrintWriter(new FileWriter(fileName, false))) {
            writer.println(line1);
            writer.println(line2);
            writer.println(line3);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public void createservercsv(int closed) {
        String fileName = "../resources/stats/outputserver.csv";


        String line1 = "closed connections, " + closed;
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(fileName, false))) {
            writer.println(line1);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Returns a list of available file IDs.
     * The result of the sha-256 algorithm is used as an identifier.
     */
    public List<ServerFile> getFiles() {
        List<ServerFile> fileHashes = new ArrayList<>();
        File folder = new File(baseDirectory);
        File[] files = folder.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    try {
                        String shaHash = generateHash(file, "SHA-256");
                        String md5Hash = generateHash(file, "MD5");
                        fileHashes.add(new ServerFile(file.getName(), shaHash, md5Hash));
                    } catch (IOException | NoSuchAlgorithmException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return fileHashes;
    }


    private String generateHash(File file, String algorithm)  throws IOException, NoSuchAlgorithmException {
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
     * identifier of the file
     */
    public String getMD5(String filename) throws IOException, NoSuchAlgorithmException {
        File file = new File(baseDirectory + "/" + filename);
        return generateHash(file, "MD5");
    }

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
