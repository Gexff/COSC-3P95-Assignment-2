/**
 * @Title COSC 3P95 Assignment 2
 *
 * @Author Geoffrey Jensen
 * @Student# 7148710
 *
 * @Author Justin Thomas Bijoy
 * @Student# 7123550
 *
 * This class handles a bulk transfer of files from a single client. It implements runnable so instances can run at the
 * same time.
 *
 * It is instantiated with a socket connection, and the transfer protocol is as follows:
 *
 *  1.) Server sends the SESSION_ID to the client.
 *
 *  2.) Client sends the name of the folder to store the transferred files in to the server as a String object.
 *
 *  3.) Client sends an ArrayList object containing the names of the files that will be transferred to the Server.
 *  The server assumes the file transfer order will be the order of names in the ArrayList.
 *
 *  4.) For each file name in the ArrayList:
 *      Client side:
 *      i.) The client fully compresses the file data, then encrypts the data
 *      ii.) The client sends the length of the compressed/encrypted data to the Server,
 *          then sends the compressed/encrypted data.
 *      iii.) The client sends the checksum of the unencrypted file to the server, by first sending the length of the
 *          checksum, followed by the checksum itself.
 *
 *      Server side:
 *      iv.) The server reads the length of the compressed/encrypted data, then reads the compressed/encrypted data itself.
 *      v.) The server decrypts the data, then decompresses it.
 *      vi.) The server reads the length of the checksum from the client, then reads the checksum itself.
 *      vii.) The server compares the checksum from the client to the checksum it calculates from the received data.
 */

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.Socket;
import java.security.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.zip.InflaterOutputStream;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.common.Attributes;

public class FolderTransferRequest implements Runnable {
    Socket client;
    ObjectInputStream oInputStream;
    ObjectOutputStream oOutputStream;
    DataInputStream dInputStream;
    Cipher cipher;
    String SESSION_ID;

    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("COSC3P95.Server");
    private static final Meter meter = GlobalOpenTelemetry.meterBuilder("COSC3P95.Server").build();
    private static final LongCounter filesReceivedCounter = meter
            .counterBuilder("files_received_total")
            .setDescription("Total number of files successfully received")
            .build();
    private static final LongCounter checksumFailCounter = meter
            .counterBuilder("checksum_failures_total")
            .setDescription("Number of files where checksum verification failed")
            .build();

    public FolderTransferRequest(Socket client) throws IOException, NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException {
        oInputStream = new ObjectInputStream(client.getInputStream());
        oOutputStream = new ObjectOutputStream(client.getOutputStream());
        dInputStream = new DataInputStream(client.getInputStream());
        this.client = client;
        this.SESSION_ID = Server.generateSessionID(20);

        byte[] decodedKey = Base64.getDecoder().decode(Server.SECRET_KEY);
        SecretKey key = new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
        cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        IvParameterSpec ivParameterSpec = new IvParameterSpec(Server.IV);
        cipher.init(Cipher.DECRYPT_MODE, key, ivParameterSpec);
    }

    @Override
    public void run() {
        String folder = null;
        ArrayList<String> fileNames = null;

        // Send SESSION_ID to client
        try {
            oOutputStream.writeObject(SESSION_ID);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Read folder name
        try {
            String[] tmp = ((String) oInputStream.readObject()).split("/"); // Don't accept paths as input
            folder = "Server_" + tmp[tmp.length-1];
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        // Read ArrayList of file names
        try {
            fileNames = (ArrayList<String>) oInputStream.readObject();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        if(folder == null || fileNames == null){
            System.out.println("Something went wrong");
            return;
        }

        File newFolder = new File(folder);
        if (newFolder.mkdir()) {
            System.out.println("Folder Created: " + folder);
        } else {
            System.out.println("Folder already exists: " + folder);
        }
        System.out.println();

        MessageDigest md = null;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return;
        }

        for(String fileName : fileNames){
            try {
                // Read data length
                int length = dInputStream.readInt();

                byte[] retrievedData = readData(dInputStream, length);

                if(Server.USE_ADVANCED_FEATURES){
                    byte[] compressedData = decrypt(retrievedData, cipher);

                    byte[] rawData = decompress(compressedData);

                    writeToFile(rawData, folder + "/" + fileName, md);

                    byte[] clientDigest = readChecksum(dInputStream);

                    // Read MD5 hash
                    byte[] serverDigest = md.digest();

                    // Compare checksums
                    boolean match = compareChecksums(clientDigest, serverDigest);

                    if (match) {
                        System.out.println("MD5 Checksum matched for file: " + fileName);
                    } else {
                        System.out.println("MD5 Checksum DID NOT MATCH for file: " + fileName);
                    }
                }
                else{
                    try(FileOutputStream fileOutputStream = new FileOutputStream(folder + "/" + fileName))
                    {
                        fileOutputStream.write(retrievedData);
                    }
                }

                System.out.println("Finished transferring: " + fileName + "\n");

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean compareChecksums(byte[] clientDigest, byte[] serverDigest) {
        // SPAN: Start

        Span span = tracer.spanBuilder("checksum.compare").startSpan();
        boolean result = false;
        try {
            result = MessageDigest.isEqual(clientDigest, serverDigest);
            // SPAN: End
        } catch (Exception e) {
            span.recordException(e);
        } finally {
            span.setAttribute("checksum.result", result);
            span.end();
        }
        // SPAN: End
        return result;
    }

    private byte[] readChecksum(DataInputStream dInputStream) throws IOException {
        // SPAN: Start

        Span span = tracer.spanBuilder("read.checksum").startSpan();
        byte[] digest;
        try {
            int length = dInputStream.readInt();
            digest = new byte[length];
            dInputStream.read(digest, 0, length);
            span.setAttribute("checksum.length", length);
            span.addEvent("Checksum read from client");
        } finally {
            span.end();
        }
        return digest;

        // SPAN: End
    }

    private void writeToFile(byte[] rawData, String file, MessageDigest md) throws IOException {
        // SPAN: Start

        Span span = tracer.spanBuilder("write.file").startSpan();
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(rawData);
        try(DigestInputStream digestInputStream = new DigestInputStream(byteArrayInputStream, md);
            FileOutputStream fileOutputStream = new FileOutputStream(file))
        {
            byte[] buffer = new byte[Server.BUFFER_SIZE];
            int bytes;
            span.addEvent("Writing data to " + file);
            while ((bytes = digestInputStream.read(buffer)) != -1) {
                fileOutputStream.write(buffer, 0, bytes);
            }
            span.addEvent("Write complete");
        } catch (IOException e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }

        // SPAN: End
    }

    private byte[] decompress(byte[] data) {
        // SPAN: Start

        Span span = tracer.spanBuilder("decompress.data").startSpan();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try(InflaterOutputStream inflaterOutputStream = new InflaterOutputStream(byteArrayOutputStream))
        {
            inflaterOutputStream.write(data);
            span.addEvent("Decompression finished");
        } catch (IOException e) {
            e.printStackTrace();
            span.recordException(e);
        } finally {
            span.end();
        }

        // SPAN: End
        return byteArrayOutputStream.toByteArray();
    }

    private byte[] decrypt(byte[] data, Cipher cipher) {
        // SPAN: Start

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try(CipherOutputStream cipherOutputStream = new CipherOutputStream(byteArrayOutputStream, cipher))
        {
            cipherOutputStream.write(data);
            span.addEvent("Decryption complete");
        } catch (IOException e) {
            e.printStackTrace();
            span.recordException(e);
        } finally {
            span.end();
        }

        // SPAN: End
        return byteArrayOutputStream.toByteArray();
    }

    private byte[] readData(DataInputStream dInputStream, int length) throws IOException {
        Span span = tracer.spanBuilder("read.data").startSpan();
        byte[] retrievedData = new byte[length];

        // Read data
        int totalRead = 0;
        int read;
        try {
            while(totalRead < length && (read = dInputStream.read(retrievedData, totalRead, length - totalRead)) != -1) {
                totalRead += read;
            }
            span.setAttribute("bytes.read", totalRead);
        } catch (IOException e) {
            e.printStackTrace();
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
        return retrievedData;
    }
}