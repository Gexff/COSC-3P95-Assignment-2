/**
 * @Title COSC 3P95 Assignment 2
 *
 * @Author Geoffrey Jensen
 * @Student# 7148710
 *
 * @Author Justin Thomas Bijoy
 * @Student# 7123550
 *
 * This PredicateLogger class is responsible for recording predicate evaluations
 * and run-level outcomes during each execution of the client-side file transfer.
 *
 * The logger writes simple key/value pairs to a text file, documenting the
 * values of internal predicates and whether the execution resulted in an exception.
 * These logs are later consumed by the PredicateAnalyzer to support Statistical
 * Debugging (SD), enabling the computation of Failure, Context, and Increase metrics
 * based on program traces.
 */

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