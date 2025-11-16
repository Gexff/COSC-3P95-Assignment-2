/**
 * @Title COSC 3P95 Assignment 2
 *
 * @Author Geoffrey Jensen
 * @Student# 7148710
 *
 * @Author Justin Thomas Bijoy
 * @Student# 7123550
 *
 * This PredicateAnalyzer program processes all predicate log files generated
 * across multiple client executions to perform Statistical Debugging (SD).
 *
 * It automatically scans the working directory for files matching the pattern
 * run_*.txt, breaks down each run’s predicate evaluations, and add up results
 * across all executions. The analyzer computes the Failure(P), Context(P), and
 * Increase(P) metrics for every predicate, providing results into which conditions
 * link the most strongly with program failures.
 *
 * The final results are exported to a CSV file containing raw predicate counts
 * and computed SD metrics, enabling further analysis, and  interpretation
 * of predicates to identify the deliberate bug’s root cause.
 */

import java.io.*;
import java.util.*;

public class PredicateAnalyzer {

    static class Run {
        Map<String, Boolean> predicates = new HashMap<>();
        boolean failed;
    }

    public static void main(String[] args) throws Exception {

        File folder = new File(".");
        File[] runFiles = folder.listFiles((dir, name) -> name.startsWith("run_") && name.endsWith(".txt"));

        if (runFiles == null || runFiles.length == 0) {
            System.out.println("No run_*.txt files found.");
            return;
        }

        System.out.println("Found " + runFiles.length + " run files.");

        List<Run> allRuns = new ArrayList<>();

        for (File file : runFiles) {
            System.out.println("Processing: " + file.getName());
            allRuns.addAll(readfiles(file.getAbsolutePath()));
        }

        Set<String> predicateNames = new TreeSet<>();
        for (Run r : allRuns) predicateNames.addAll(r.predicates.keySet());

        List<String[]> csvRows = new ArrayList<>();
        csvRows.add(new String[]{
                "Predicate",
                "trueCount",
                "falseCount",
                "P_true_fail",
                "P_true_total",
                "P_observed_fail",
                "P_observed_success",
                "failure",
                "context",
                "increase"
        });

        for (String pred : predicateNames) {
            Result res = computeMetrics(pred, allRuns);
            csvRows.add(new String[]{
                    pred,
                    String.valueOf(res.trueCount),
                    String.valueOf(res.falseCount),
                    String.valueOf(res.P_true_fail),
                    String.valueOf(res.P_true_total),
                    String.valueOf(res.P_observed_fail),
                    String.valueOf(res.P_observed_success),
                    String.format("%.4f", res.failure),
                    String.format("%.4f", res.context),
                    String.format("%.4f", res.increase)
            });
        }

        writeCSV("results.csv", csvRows);
        System.out.println("Done! Results written to results.csv");
    }

    private static List<Run> readfiles(String filename) throws Exception {
        List<Run> runs = new ArrayList<>();

        BufferedReader br = new BufferedReader(new FileReader(filename));
        String line;
        Run currentRun = new Run();

        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("----------------------------------")) {
                if (!currentRun.predicates.isEmpty()) runs.add(currentRun);
                currentRun = new Run();
                continue;
            }

            if (line.startsWith("EXCEPTION=")) {
                currentRun.failed = line.endsWith("1");
                runs.add(currentRun);
                currentRun = new Run();
                continue;
            }

            if (line.contains("=")) {
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    currentRun.predicates.put(parts[0], Boolean.parseBoolean(parts[1]));
                }
            }
        }

        br.close();
        return runs;
    }

    static class Result {
        int trueCount, falseCount;
        int P_true_fail, P_true_total;
        int P_observed_fail, P_observed_success;
        double failure, context, increase;
    }

    private static Result computeMetrics(String predicate, List<Run> runs) {

        int P_true_fail = 0;
        int P_true_total = 0;

        int P_observed_fail = 0;
        int P_observed_success = 0;

        int trueCount = 0;
        int falseCount = 0;

        for (Run r : runs) {
            if (!r.predicates.containsKey(predicate)) continue;

            boolean val = r.predicates.get(predicate);

            if (val) {
                trueCount++;
                P_true_total++;
                if (r.failed) P_true_fail++;
            } else {
                falseCount++;
            }

            if (r.failed) P_observed_fail++;
            else P_observed_success++;
        }

        Result res = new Result();

        res.trueCount = trueCount;
        res.falseCount = falseCount;
        res.P_true_fail = P_true_fail;
        res.P_true_total = P_true_total;
        res.P_observed_fail = P_observed_fail;
        res.P_observed_success = P_observed_success;

        res.failure = (P_true_total > 0)
                ? (double) P_true_fail / P_true_total
                : 0;

        int totalObserved = P_observed_fail + P_observed_success;
        res.context = (totalObserved > 0)
                ? (double) P_observed_fail / totalObserved
                : 0;

        res.increase = res.failure - res.context;

        return res;
    }

    private static void writeCSV(String filename, List<String[]> rows) throws Exception {
        PrintWriter pw = new PrintWriter(new FileWriter(filename));
        for (String[] r : rows) {
            pw.println(String.join(",", r));
        }
        pw.close();
    }
}