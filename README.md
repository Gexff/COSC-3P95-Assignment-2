# COSC 3P95 Assignemnt 2

This assignment tasked us with creating a Client-Server program that is intrumented with OpenTelemetry. The program itself involves the Client sending a folder of files to the Sevrer, so we can get experiance with distributed tracing.

## Part 1
The first part of the assignment involved writing the inital program, and instrumenting it. The Part 1/ folder contains an IntelliJ project with the following src files:

### Server.java
This is the server program that constanly listens for incoming socket connections. When there is a new connection, it is passed to a thread pool to perform the folder transfer, while the server can continue to listen for new connections.

#### Compile
While inside the Part 1/ folder
```bash
javac -d build src/Server.java src/FolderTransferRequest.java
```
#### Run
```bash
java -cp build Server
```

### FolderTransferRequest.java
This is the runnable class that is executed by the Server's thread pool. It handles the full transfer process of the Client's folder to the Server.

### Client.java
This program is for a single client. It takes the name of the folder to be transerred as a command-line argument, and sends all files within that folder to the Server.

#### Compile
While inside the Part 1/ folder
```bash
javac -d build src/Client.java src/Server.java src/FolderTransferRequest.java
```
#### Run
```bash
java -cp build Client <Name of Folder to Transfer>
```

### GenerateFiles.java
This class creates dummy data for testing the Client-Server transfer. It takes in the name of the folder it will place the generated files as a command-line argument.

#### Compile
While inside the Part 1/ folder
```bash
javac -d build src/GenerateFiles.java
```
#### Run
```bash
java -cp build GenerateFiles <Name of Folder to Place Generated Files>
```





