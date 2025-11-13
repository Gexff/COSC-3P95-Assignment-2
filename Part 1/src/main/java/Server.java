/**
 * @Title COSC 3P95 Assignment 2
 *
 * @Author Geoffrey Jensen
 * @Student# 7148710
 *
 * @Author Justin Thomas Bijoy
 * @Student# 7123550
 *
 * This Server class opens a socket to listen for incoming connections. New connections are given to a new
 * FolderTransferRequest runnable object that is passed to a thread pool for execution, to allow for simultaneous
 * transfer requests.
 */

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class Server {
    private int portNumber;
    private Executor executor;

    static int BUFFER_SIZE = 4096;

    static boolean USE_ADVANCED_FEATURES = true;

    // Used for symmetric encryption and decryption
    static String SECRET_KEY = "FJEHUIHIHFS7232HFH297H";
    static byte[] IV = {0x00, 0x10, 0x05, 0x20, 0x00, 0x22, 0x40, 0x21, 0x00, 0x00, 0x07, 0x09, 0x00, 0x0A, 0x00, 0x11};

    public Server(int portNumber){
        this.portNumber = portNumber;
        executor = Executors.newFixedThreadPool(6);

        try(ServerSocket serverSocket = new ServerSocket(this.portNumber)) {
            while (true) { // listen for connections
                Socket client = serverSocket.accept();

                // Create a request for the thread pool to complete
                executor.execute(new FolderTransferRequest(client));
            }
        }
        catch(IOException io) {
            System.out.println("Having trouble with port " + portNumber + " " + io.getMessage());
        }
        catch(Exception e){
            System.out.println(e.getMessage());
        }
    }

    public static String generateSessionID(int length){
        String alphabet = "abcdefghjklmnpqrstuvwyxzABCDEFGHJKLMNPQRSTUVWYXZ0123456789";

        StringBuilder builder = new StringBuilder();
        Random random = new Random();

        for(int i = 0; i < length; i++){
            builder.append(alphabet.charAt((int) (random.nextDouble() * alphabet.length())));
        }
        return builder.toString();
    }

    public static void main(String[] args) {
        new Server(5000);
    }
}
