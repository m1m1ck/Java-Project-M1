# FileSenderServer

## About

This is a **distributed file sharing system** developed in Java. It allows multiple clients to connect to a central server to download file blocks and validate their integrity using hashes (SHA-256 and MD5).

This project was implemented as part of an **academic course assignment**, aiming to provide hands-on experience with Java concurrency and networking.

---

## Project Info

- **Project Name:** FileSenderServer
- **Course:** Distributed Java Programming
- **Developed by:**  
  - *Student 1:* Khernuf Valid
  - *Student 2:* Michoulier Marc 
  - *Student 3:* Vitiugova Luliia
- **Institution:** Sorbonne Paris North University
- **Semester:** Spring 2025

---

## Project Structure

```text
FileSenderServer/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   ├── BaseConfig.java           # Abstract base config class
│   │   │   ├── Client.java               # Client class handling file requests
│   │   │   ├── ClientConfig.java         # Configuration for the client
│   │   │   ├── FileStorage.java          # File reading and block-serving logic
│   │   │   ├── RequestHandler.java       # Handles commands from clients
│   │   │   ├── Server.java               # Server startup and logic
│   │   │   ├── ServerConfig.java         # Configuration for the server
│   │   │   ├── ServerFile.java           # Record of stored files
│   │   │   └── TrustedClient.java        # Record of trusted clients
│   │   └── resources/
│   │       └── ServerResources/          # Contains files for server to operate
│   │       └── stats/                    # Output of the statistics
│   └── test/
│       ├── java/
│       │   └── Test.java                 # Launches server + multiple clients for testing
│       │   └── Test.class                # Class file of Test.java
│       └── resources/
│           └── ServerResources/          # Duplicate for launching through Test file
│           └── stats/                    # Output of the statistics if launched through Test file
├── docs/                                 # Javadoc-generated documentation
└── out/                                  # Compiler output
```
When clients download files, they save them in a folder based on their port number. For example:

- Clients started via `Test.java` will store files in:  
  `src/test/resources/Client<port>/`  
- Clients started manually (via `Client` class) will store files in:  
  `src/main/resources/Client<port>/`  

> Make sure these folders can be created before running clients.

---

## Logging

The system uses Java's built-in `Logger` to output runtime information to the console.  
It provides visibility into key events, such as:

- When trusted clients are registered or used.
- When files are served or downloaded.
- When connections are opened or closed.
- Errors or unexpected behavior.

---
### Statistics Logging

The system tracks and logs key metrics during execution:

- **For each Client**:
  - Total time spent downloading the file.
  - Number of times the client was helped by another (received token).
  - Number of times the client helped others (acted as a trusted client).

These statistics are automatically and **continuously saved** to a CSV file named:

- `client<port>.csv` – depending on the port of the client. 

The files are saved in:

- `src/main/stats/` – when run manually.
- `src/test/stats/` – when run using `Test.java`.

Each time a tracked value changes, the corresponding CSV file is updated.

- **For the Server**:
  - The number of times it closed connections.
  
This is logged in:

- `outputserver.csv`  
  Located in the same `stats` directory (`main` or `test`) based on how the server is launched.

Additionally, a summary PDF file named **`Metrics.pdf`** is created using the collected statistics.  
It includes visual **plots and diagrams**.  
This file is located at the **root of the project ZIP archive**.

> Make sure the `/stats` directory exists under both `src/main/resources/` and `src/test/resources/`, or is writable. Otherwise, logging may fail silently.

## How to Compile & Run

### Prerequisites

- Java JDK 17 or higher
- Java added to system `PATH`
- PowerShell or Terminal access

---

### Run the system
You can run the project in two ways:

---

### Option 1: Run Using Test.java
This automatically launches the server and clients.

### ⚠️ AWARE

Before running the system, keep in mind:

- **System overload**: If you start too many clients or use too many threads (`--clients`, `--Dc`, `--Cs`), the system may experience resource exhaustion. This can cause unexpected behavior such as hanging, closed sockets, or zombie processes. It is recommended to test with a **reasonable number of clients (e.g. up to 5)** unless running on a powerful machine.
  
- **Client ports**: Clients are launched starting from **port 5000**. Each new client increments the port by 1 (e.g., 5000, 5001, 5002...). Make sure these ports are available on your system and **not blocked by firewalls or already in use**.

- **Client simulation**: Clients are intentionally left running ("hanging") after execution to simulate a distributed system where clients remain available for sharing or verification. This is expected behavior.

#### Arguments:
 - ```--serverPort```: Port for the server to bind

 - ```--clients```: Number of clients to start

 - ```--clientsDelay```: Delay in ms between client launches (Default: 10 seconds)

 - ```--file```: File id (SHA-256 of file) to be downloaded by clients (Default: random file from server resources)

 - ```--Pc```: Probability (between 0 and 1) that the client will deny a token request (Default: 0.2)

 - ```--Dc```: Number of concurrent requests the client can make to download blocks. (Default: 1)

 - ```--Cs```: Maximum number of simultaneous client connections. (Default: 5)

 - ```--T```: Interval in seconds at which to attempt connection closure. (Default: 10)

 - ```--P```: Probability of a connection being closed during a check. (Default: 0.2)

 - ```--B```: Block size in bytes (Default: 100)

#### Example
launch this command in the folder with java file
```
java Test.java --clients=4 --serverPort=23456 --P=0.4 --T=3 --Cs=4 --B=10 --serverHost=localhost --file=5e90375d21169526b7c0543df51fa58b24aedc667976e7b128406a161a8139a9 --Dc=8 --Pc=0.5 --clientsDelay=2000
```

### Option 2: Manual Execution

#### Run Server

#### Arguments:
 - ```--port```: Port for the server to bind
  
 - ```--Cs```: Maximum number of simultaneous client connections. (Default: 5)

 - ```--T```: Interval in seconds at which to attempt connection closure. (Default: 10)

 - ```--P```: Probability of a connection being closed during a check. (Default: 0.2)

 - ```--B```: Block size in bytes (Default: 100)

 - ```filesDir```: The directory path where files should be stored or retrieved from (Default: `resources/ServerResources/`)

#### Example
launch this command in the folder with java file
```
java Server.java --port=23456 --P=0.4 --T=3 --Cs=4 --B=10
```

#### Run Client

#### Arguments:
 - ```--port```: Port for the client to bind

 - ```--serverHost```: Host of the launched server

 - ```--serverPort```: Port of the launched server

 - ```--file```: File id (SHA-256 of file) to be downloaded by clients (Default: random file from server resources)

 - ```--Pc```: robability (between 0 and 1) that the client will deny a token request (Default: 0.2)

 - ```--Dc```: Number of concurrent requests the client can make to download blocks. (Default: 1)

 - ```--B```: Block size in bytes (Default: 100)

 - ```filesDir```: The directory path where files should be stored or retrieved from (Default: `resources/Client<Port>/`)

#### Example
launch this command in the folder with java file
```
java Client.java --port=5000 --serverHost=localhost --serverPort=23456 --file=5e90375d21169526b7c0543df51fa58b24aedc667976e7b128406a161a8139a9 --Dc=6 --Pc=0.5 --B=10
```