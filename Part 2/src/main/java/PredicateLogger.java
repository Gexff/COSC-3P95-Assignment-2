import java.io.FileWriter;
import java.io.IOException;

public class PredicateLogger {

    private final String filename;
    private boolean exceptionOccurred = false;

    public PredicateLogger(String filename) {
        this.filename = filename;
    }

    public void log(String line) {
        try (FileWriter fw = new FileWriter(filename, true)) {
            fw.write(line + "\n");
        } catch (IOException e) {
            System.out.println("Error writing log: " + e.getMessage());
        }
    }

    public void markException() {
        exceptionOccurred = true;
    }

    public void finalizeLog() {
        log("EXCEPTION=" + (exceptionOccurred ? "1" : "0"));
    }
}