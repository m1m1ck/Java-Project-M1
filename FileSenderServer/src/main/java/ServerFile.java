/**
 * Represents metadata for a file served by the server.
 * <p>
 * Contains the file's name and its cryptographic checksums (SHA-256 and MD5)
 * for integrity verification and file identification.
 * <p>
 * The {@code sha256} value is used as a unique file identifier (file ID) across the system.
 *
 * @param fileName the name of the file
 * @param sha256   the SHA-256 hash of the file contents; used as the file ID
 * @param md5      the MD5 hash of the file contents
 */
public record ServerFile(String fileName, String sha256, String md5) {
}
