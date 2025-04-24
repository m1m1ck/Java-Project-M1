import java.util.UUID;

/**
 * Represents a single-use token that a trusted client can generate
 * and pass to another client for downloading a limited number of blocks.
 */
public class Token {
    private final String tokenId;
    private final int maxBlocks; // The N parameter (max blocks to download with this token)

    public Token(int maxBlocks) {
        this.tokenId = UUID.randomUUID().toString();
        this.maxBlocks = maxBlocks;
    }

    public String getTokenId() {
        return tokenId;
    }

    public int getMaxBlocks() {
        return maxBlocks;
    }

    // You can store additional info, like how many blocks have been used, etc.
}
