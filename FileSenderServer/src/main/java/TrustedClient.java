/**
 * Represents a trusted client in the distributed file-sharing system.
 * <p>
 * A trusted client is one that has successfully verified a file and received a token from the server,
 * allowing it to serve file blocks to other clients via peer-to-peer communication.
 *
 * <p>This record encapsulates the necessary connection details — the client's network {@code host}
 * and its listening {@code port} — for initiating block requests.
 *
 * @param host the IP address or hostname of the trusted client
 * @param port the port number on which the trusted client is accepting connections
 */
public record TrustedClient(String host, int port) {
}
