import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Aggregates harness results. Usage: Stats report DIR | Stats compare DIR_A DIR_B.
 * A results directory holds SCENARIO/run-N/result.json; runs recorded with JFR are left out of all statistics.
 */
public final class Stats {
    private static final String[] METRICS = {"mspt_mean", "mspt_p50", "mspt_p95", "mspt_p99", "main_cpu_ms_per_tick", "alloc_kb_per_tick",
        "gc_count", "gc_ms", "chunks_per_s", "process_cpu_ms_per_chunk", "cpu_calibration_us"};
    /** Two-sided Student t critical values for df 1..30, then the normal limit. */
    private static final double[] T95 = {12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.160, 2.145, 2.131,
        2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042, 1.960};
    private static final double[] T99 = {63.657, 9.925, 5.841, 4.604, 4.032, 3.707, 3.499, 3.355, 3.250, 3.169, 3.106, 3.055, 3.012, 2.977, 2.947,
        2.921, 2.898, 2.878, 2.861, 2.845, 2.831, 2.819, 2.807, 2.797, 2.787, 2.779, 2.771, 2.763, 2.756, 2.750, 2.576};
    private static final double[] T999 = {636.62, 31.599, 12.924, 8.610, 6.869, 5.959, 5.408, 5.041, 4.781, 4.587, 4.437, 4.318, 4.221, 4.140, 4.073,
        4.015, 3.965, 3.922, 3.883, 3.850, 3.819, 3.792, 3.768, 3.745, 3.725, 3.707, 3.690, 3.674, 3.659, 3.646, 3.291};

    record Sample(int n, double mean, double sd) {
        static Sample of(List<Double> values) {
            int n = values.size();
            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            double ss = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum();
            return new Sample(n, mean, n > 1 ? Math.sqrt(ss / (n - 1)) : 0);
        }

        double cvPercent() {
            return mean == 0 ? 0 : sd / Math.abs(mean) * 100;
        }
    }

    /** Welch comparison of B against A. Bounds are half-widths of the confidence interval of the difference. */
    record Delta(double diff, double bound95, double bound99, double bound999, boolean testable) {
        static Delta of(Sample a, Sample b, boolean countFloor) {
            if (a.n < 2 || b.n < 2) {
                return new Delta(b.mean - a.mean, Double.NaN, Double.NaN, Double.NaN, false);
            }
            // Counts cannot be less noisy than Poisson; this keeps rare events with accidental zero spread from flagging.
            double va = countFloor ? Math.max(a.sd * a.sd, Math.abs(a.mean)) : a.sd * a.sd;
            double vb = countFloor ? Math.max(b.sd * b.sd, Math.abs(b.mean)) : b.sd * b.sd;
            double qa = va / a.n, qb = vb / b.n, se = Math.sqrt(qa + qb);
            double df = se == 0 ? 1 : (qa + qb) * (qa + qb) / (qa * qa / (a.n - 1) + qb * qb / (b.n - 1));
            int index = (int) Math.max(1, Math.min(31, Math.floor(df))) - 1;
            return new Delta(b.mean - a.mean, T95[index] * se, T99[index] * se, T999[index] * se, true);
        }
    }

    record Scenario(String name, int runs, Map<String, List<Double>> metrics, Map<String, List<String>> exact, Map<String, List<Double>> statistical) {
    }

    public static void main(String[] args) throws IOException {
        Locale.setDefault(Locale.ROOT);
        if (args.length == 2 && args[0].equals("report")) {
            report(Path.of(args[1]));
        } else if (args.length == 3 && args[0].equals("compare")) {
            System.exit(compare(Path.of(args[1]), Path.of(args[2])) ? 0 : 3);
        } else {
            System.err.println("usage: Stats report DIR | Stats compare DIR_A DIR_B");
            System.exit(2);
        }
    }

    private static Map<String, Scenario> load(Path dir) throws IOException {
        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        List<Path> scenarioDirs;
        try (Stream<Path> stream = Files.list(dir)) {
            scenarioDirs = stream.filter(Files::isDirectory).sorted().toList();
        }
        for (Path scenarioDir : scenarioDirs) {
            Map<String, List<Double>> metrics = new LinkedHashMap<>();
            Map<String, List<String>> exact = new TreeMap<>();
            Map<String, List<Double>> statistical = new TreeMap<>();
            List<Path> results;
            try (Stream<Path> stream = Files.list(scenarioDir)) {
                results = stream.map(p -> p.resolve("result.json")).filter(Files::isRegularFile).sorted().toList();
            }
            int runs = 0;
            List<JsonObject> parsed = new ArrayList<>();
            for (Path result : results) {
                JsonObject json = new Gson().fromJson(Files.readString(result), JsonObject.class);
                if (!json.get("jfr").getAsBoolean()) {
                    parsed.add(json);
                }
            }
            TreeSet<String> statisticalKeys = new TreeSet<>();
            parsed.forEach(json -> statisticalKeys.addAll(json.getAsJsonObject("digest_statistical").keySet()));
            for (JsonObject json : parsed) {
                runs++;
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("metrics").entrySet()) {
                    metrics.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue().getAsDouble());
                }
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("digest_exact").entrySet()) {
                    exact.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue().getAsString());
                }
                JsonObject stat = json.getAsJsonObject("digest_statistical");
                // A count that is absent in a run (no entity of that type) is a zero, not a missing value.
                for (String key : statisticalKeys) {
                    statistical.computeIfAbsent(key, k -> new ArrayList<>()).add(stat.has(key) ? stat.get(key).getAsDouble() : 0.0);
                }
            }
            if (runs > 0) {
                scenarios.put(scenarioDir.getFileName().toString(), new Scenario(scenarioDir.getFileName().toString(), runs, metrics, exact, statistical));
            }
        }
        return scenarios;
    }

    private static void report(Path dir) throws IOException {
        Map<String, Scenario> scenarios = load(dir);
        StringBuilder out = new StringBuilder();
        out.append(String.format("%-15s %4s %9s %7s %8s %8s %8s %9s %10s %6s %7s %7s %9s%n",
            "scenario", "runs", "mspt", "noise%", "p50", "p95", "p99", "cpu ms/t", "alloc KB/t", "GCs", "GC ms", "cal us", "chunks/s"));
        Map<String, Object> json = new LinkedHashMap<>();
        for (Scenario s : scenarios.values()) {
            Map<String, Sample> samples = new LinkedHashMap<>();
            s.metrics.forEach((key, values) -> samples.put(key, Sample.of(values)));
            Sample mspt = samples.get("mspt_mean");
            out.append(String.format("%-15s %4d %9.3f %7s %8.3f %8.3f %8.3f %9.3f %10.0f %6.1f %7.0f %7.1f %9s%n", s.name, s.runs, mspt.mean,
                s.runs > 1 ? String.format("%.2f", mspt.cvPercent()) : "n/a", samples.get("mspt_p50").mean, samples.get("mspt_p95").mean,
                samples.get("mspt_p99").mean, samples.get("main_cpu_ms_per_tick").mean, samples.get("alloc_kb_per_tick").mean,
                samples.get("gc_count").mean, samples.get("gc_ms").mean, samples.get("cpu_calibration_us").mean,
                samples.containsKey("chunks_per_s") ? String.format("%.1f ±%.1f%%", samples.get("chunks_per_s").mean, samples.get("chunks_per_s").cvPercent()) : "-"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("runs", s.runs);
            entry.put("metrics", samples);
            entry.put("digest_exact", s.exact);
            Map<String, Sample> statistical = new TreeMap<>();
            s.statistical.forEach((key, values) -> statistical.put(key, Sample.of(values)));
            entry.put("digest_statistical", statistical);
            json.put(s.name, entry);
        }
        out.append("\nmspt and percentiles are means over runs; noise% is the run-to-run coefficient of variation of mean mspt.\n");
        out.append("cal us is the duration of a fixed arithmetic loop on the main thread: it tracks the core's speed, not the server's.\n");
        out.append("\nBehavior digest (exact fields must not vary; statistical fields are mean ± sd over runs)\n");
        for (Scenario s : scenarios.values()) {
            out.append('\n').append(s.name).append('\n');
            s.exact.forEach((key, values) -> out.append(String.format("  exact  %-36s %s%n", key,
                values.stream().distinct().count() == 1 ? values.getFirst() : "VARIES " + values)));
            s.statistical.forEach((key, values) -> {
                Sample sample = Sample.of(values);
                out.append(String.format("  stat   %-36s %10.1f ± %.1f%n", key, sample.mean, sample.sd));
            });
        }
        Files.writeString(dir.resolve("summary.txt"), out);
        Files.writeString(dir.resolve("summary.json"), new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(json));
        System.out.print(out);
    }

    /** Returns true when nothing differs beyond noise: no metric past its 99% bound and no digest drift. */
    private static boolean compare(Path dirA, Path dirB) throws IOException {
        Map<String, Scenario> a = load(dirA), b = load(dirB);
        boolean same = true;
        StringBuilder drift = new StringBuilder();
        System.out.printf("A = %s%nB = %s%n%n", dirA, dirB);
        System.out.printf("%-15s %-20s %5s %11s %11s %9s %9s %9s  %s%n", "scenario", "metric", "runs", "A", "B", "delta%", "±95%", "±99%", "verdict");
        for (Scenario sa : a.values()) {
            Scenario sb = b.get(sa.name);
            if (sb == null) {
                continue;
            }
            for (String metric : METRICS) {
                if (!sa.metrics.containsKey(metric) || !sb.metrics.containsKey(metric)) {
                    continue;
                }
                Sample x = Sample.of(sa.metrics.get(metric)), y = Sample.of(sb.metrics.get(metric));
                Delta delta = Delta.of(x, y, false);
                double scale = x.mean == 0 ? Double.NaN : 100 / Math.abs(x.mean);
                String verdict = verdict(delta);
                // GC counts move in whole steps and follow allocation, and the calibration loop measures the machine,
                // not the jar; they inform but do not decide.
                boolean decides = !metric.startsWith("gc_") && !metric.equals("cpu_calibration_us");
                if (decides && verdict.equals("DIFFERENT")) {
                    same = false;
                }
                System.out.printf("%-15s %-20s %2d/%-2d %11.3f %11.3f %+9.2f %9s %9s  %s%n", sa.name, metric, x.n, y.n, x.mean, y.mean,
                    delta.diff * scale, bound(delta.bound95 * scale), bound(delta.bound99 * scale), decides || verdict.equals("n/a") ? verdict : "(" + verdict + ")");
            }
            System.out.println();

            for (Map.Entry<String, List<String>> entry : sa.exact.entrySet()) {
                List<String> va = entry.getValue(), vb = sb.exact.getOrDefault(entry.getKey(), List.of());
                boolean stableA = va.stream().distinct().count() == 1, stableB = vb.stream().distinct().count() == 1;
                if (!stableA || !stableB) {
                    drift.append(String.format("  %-15s exact %-34s NOT REPRODUCIBLE within one jar: A=%s B=%s%n", sa.name, entry.getKey(), va, vb));
                    same = false;
                } else if (!va.getFirst().equals(vb.getFirst())) {
                    drift.append(String.format("  %-15s exact %-34s DRIFT: A=%s B=%s%n", sa.name, entry.getKey(), va.getFirst(), vb.getFirst()));
                    same = false;
                }
            }
            TreeSet<String> keys = new TreeSet<>(sa.statistical.keySet());
            keys.addAll(sb.statistical.keySet());
            for (String key : keys) {
                Sample x = Sample.of(sa.statistical.getOrDefault(key, zeros(sa.runs))), y = Sample.of(sb.statistical.getOrDefault(key, zeros(sb.runs)));
                Delta delta = Delta.of(x, y, true);
                if (delta.testable && Math.abs(delta.diff) > delta.bound999) {
                    drift.append(String.format("  %-15s stat  %-34s DRIFT: A=%.1f±%.1f B=%.1f±%.1f (diff %+.1f, 99.9%% bound ±%.1f)%n",
                        sa.name, key, x.mean, x.sd, y.mean, y.sd, delta.diff, delta.bound999));
                    same = false;
                }
            }
        }
        System.out.println("verdict: ~ within the 95% bound, ? between the 95% and 99% bounds, DIFFERENT beyond the 99% bound (Welch t).");
        System.out.println("delta% is (B - A) / A. Bounds need at least 2 runs per side; more runs tighten them.");
        System.out.println();
        System.out.println(drift.isEmpty() ? "Behavior digest: no drift (exact fields identical, statistical fields within their 99.9% bounds)."
            : "Behavior digest drift:\n" + drift);
        System.out.println(same ? "RESULT: no significant difference" : "RESULT: significant difference");
        return same;
    }

    private static List<Double> zeros(int n) {
        return java.util.Collections.nCopies(n, 0.0);
    }

    private static String verdict(Delta delta) {
        if (!delta.testable) {
            return "n/a";
        }
        double diff = Math.abs(delta.diff);
        return diff <= delta.bound95 ? "~" : diff <= delta.bound99 ? "?" : "DIFFERENT";
    }

    private static String bound(double value) {
        return Double.isNaN(value) ? "n/a" : String.format("%.2f", value);
    }
}
