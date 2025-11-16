/**
 * @Title COSC 3P95 Assignment 2
 *
 * @Author Geoffrey Jensen
 * @Student# 7148710
 *
 * @Author Justin Thomas Bijoy
 * @Student# 7123550
 *
 * This main class generates a folder with 20 randomly filled files. The program accepts the name of the folder to
 * create via a command line parameter.
 *
 * It creates:
 * 10 small files (5 KB to 300 KB)
 * 7 medium files (1 MB to 10 MB)
 * 3 large files (50 MB to 100 MB)
 */

import java.io.*;
import java.util.Random;

public class GenerateFiles {

    public static void main(String[] args) {
        if(args.length < 1){
            System.out.println("Please provide a folder name");
            System.exit(1);
        }

        String folder = args[0];

        File newFolder = new File(folder);
        if (newFolder.mkdir()) {
            System.out.println("Folder Created: " + folder);
        } else {
            System.out.println("Folder already exists: " + folder);
        }

        Random random = new Random();

        // Generate small files - 5 KB to 300 KB
        for(int i = 0; i < 10; i++){
            try(BufferedWriter writer = new BufferedWriter(new FileWriter(folder + "/" + (char) (i+65) + ".txt"))) {
                int nums = (int) (random.nextDouble() * (1024 * 295) + (5 * 1024));

                for(int j = 0; j < nums; j++){
                    writer.write(String.valueOf(random.nextInt()));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("Finished Writing File: " + i);
        }

        // Generate small files - 1 MB to 10 MB
        for(int i = 10; i < 17; i++){
            try(BufferedWriter writer = new BufferedWriter(new FileWriter(folder + "/" + (char) (i+65) + ".txt"))) {
                int nums = (int) (random.nextDouble() * (1024 * 1024 * 9) + (1024 * 1024));

                for(int j = 0; j < nums; j++){
                    writer.write(String.valueOf(random.nextInt()));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("Finished Writing File: " + i);
        }

        // Generate small files - 50 MB to 100 MB
        for(int i = 17; i < 20; i++){
            try(BufferedWriter writer = new BufferedWriter(new FileWriter(folder + "/" + (char) (i+65) + ".txt"))) {
                int nums = (int) (random.nextDouble() * (1024 * 1024 * 50) + (1024 * 1024 * 50));

                for(int j = 0; j < nums; j++){
                    writer.write(String.valueOf(random.nextInt()));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("Finished Writing File: " + i);
        }
    }
}
