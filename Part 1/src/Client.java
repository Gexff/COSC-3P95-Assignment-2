/**
 * @Title COSC 3P95 Assignment 2
 *
 * @Author Geoffrey Jensen
 * @Student# 7148710
 *
 * @Author Justin Thomas Bijoy
 * @Student# 7123550
 *
 * This is the Client class that connects to the server and sends its files. This program accepts the name of the
 * folder to be transferred via command line parameter.
 *
 * The transfer protocol is as follows:
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

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;

public class Client {
    ObjectInputStream oInputStream;
    ObjectOutputStream oOutputStream;
    DataOutputStream dOutputStream;
    Cipher cipher;
    ByteArrayOutputStream byteArrayOutputStream;

    static String SESSION_ID;

    public Client(String host, int portNumber, String folderPath) throws Exception {
        File folder = new File(folderPath);
        File[] files = folder.listFiles();

        if(files == null){
            throw new Exception("No files found in: " + folderPath);
        }

        String[] tmp = folderPath.split("/");
        String folderName = tmp[tmp.length-1];

        try(Socket socket = new Socket(host, portNumber)) {
            oOutputStream = new ObjectOutputStream(socket.getOutputStream());
            oInputStream = new ObjectInputStream(socket.getInputStream());
            dOutputStream = new DataOutputStream(socket.getOutputStream());

            byteArrayOutputStream = new ByteArrayOutputStream(Server.BUFFER_SIZE);

            byte[] decodedKey = Base64.getDecoder().decode(Server.SECRET_KEY);
            SecretKey key = new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
            cipher = Cipher.getInstance("AES/CFB8/NoPadding");
            IvParameterSpec ivParameterSpec = new IvParameterSpec(Server.IV);
            cipher.init(Cipher.ENCRYPT_MODE, key, ivParameterSpec);

            // Receive SESSION_ID
            SESSION_ID = (String) oInputStream.readObject();

            // Send folder name
            oOutputStream.writeObject(folderName);

            // Create ArrayList of file names
            ArrayList<String> fileNames = new ArrayList<>();
            for (File file : files){
                fileNames.add(file.getName());
            }

            // Send ArrayList of file names
            oOutputStream.writeObject(fileNames);

            MessageDigest md = MessageDigest.getInstance("MD5");

            for(File file : files){
                // Read file into memory
                byte[] data = Files.readAllBytes(file.toPath());

                if(Server.USE_ADVANCED_FEATURES){
                    byte[] compressedData = compress(data, md);
                    byte[] encryptedCompressedData = encrypt(compressedData, cipher);
                    sendData(dOutputStream, encryptedCompressedData);
                    sendChecksum(dOutputStream, md);
                }
                else{
                    sendData(dOutputStream, data);
                }

                System.out.println("Finished transferring: " + file.getName() + ", Size: " + file.length());
            }

            oOutputStream.close();
            dOutputStream.close();

        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendChecksum(DataOutputStream dOutputStream, MessageDigest md) throws IOException {
        // SPAN: Start

        // Send length of digest and contents
        byte[] digest = md.digest();
        dOutputStream.writeInt(digest.length);
        dOutputStream.write(digest, 0, digest.length);
        dOutputStream.flush();

        // SPAN: End
    }

    private void sendData(DataOutputStream dOutputStream, byte[] data) throws IOException {
        // SPAN: Start

        // Send data length and contents
        dOutputStream.writeInt(data.length);
        dOutputStream.write(data);

        // SPAN: End
    }

    private byte[] encrypt(byte[] data, Cipher cipher) {
        // SPAN: Start

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try(CipherOutputStream cipherOutputStream = new CipherOutputStream(byteArrayOutputStream, cipher))
        {
            cipherOutputStream.write(data);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // SPAN: End
        return byteArrayOutputStream.toByteArray();
    }

    private byte[] compress(byte[] data, MessageDigest md) {
        // SPAN: Start

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try(DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(byteArrayOutputStream);
            DigestOutputStream digestOutputStream = new DigestOutputStream(deflaterOutputStream, md))
        {
            digestOutputStream.write(data);
            deflaterOutputStream.finish();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // SPAN: End
        return byteArrayOutputStream.toByteArray();
    }

    public static void main(String[] args) {
        if (args.length < 1){
            System.out.println("Please enter the name of the folder to transfer!");
            System.exit(1);
        }
        try {
            new Client("localhost", 5000, args[0]);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
