# Java Project M1
# Here is structure of files

###          ├── ServerMain.java        // Entry point for the server
###         ├── ServerConfig.java      // Class for storing and parsing server parameters
###        ├── FileStorage.java       // Class for managing files (paths, MD5, etc.)
###       ├── RequestHandler.java    // Class that handles a single client request in a thread
###      ├── ConnectionManager.java // (Optional) Class for managing active connections
###     └── Token.java             // (Optional) Class for the single-use token mechanism

### ClientConfig.java (Stores client settings, similar to how ServerConfig stores server settings).
### ClientMain.java (The entry point where the client logic is launched).
### to compile them: (javac ClientConfig.java ClientMain.java
### java ClientMain --serverHost=localhost --serverPort=12345 --file=file1 --DC=2)

