package io.opaa.fuzz;

import io.opaa.format.DocumentFormatResult;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * A time-boxed fuzzing campaign over the corpus on the {@link ProductionPath}: every seed once as
 * it is, then mutated seeds until the time is up. A finding is an {@link OutOfMemoryError}, a run
 * past {@link Settings#inputTimeout}, a throwable that escapes the path, or a temp file of a
 * discovered attachment left behind. Each finding is written to {@link Settings#findings} with its
 * input and how to reproduce it; an iteration is determined by the campaign seed and its number.
 */
final class FuzzCampaign {

  /** How many inputs of the same finding signature are written; the rest are only counted. */
  private static final int INPUTS_PER_SIGNATURE = 3;

  private static final int SLOWEST = 5;

  /** After this many runs that never returned the campaign stops: each still holds a thread. */
  private static final int MAX_STUCK_RUNS = 2;

  /** The name of the input that runs right now, beside the findings. */
  static final String CURRENT = "current-input";

  /** The prefix of every line the campaign prints; the build shows only these. */
  static final String LOG_PREFIX = "[parser-fuzz] ";

  enum Kind {
    OUT_OF_MEMORY,
    TIMEOUT,
    ESCAPED_THROWABLE,
    LEFTOVER_TEMP_FILE
  }

  /**
   * @param duration how long the campaign mutates, after one pass over the seeds as they are
   * @param seed the campaign seed; with an iteration number it determines one input
   * @param inputTimeout the bound of one input on the whole path, attachments included
   * @param findings where findings and the summary are written
   */
  record Settings(Duration duration, long seed, Duration inputTimeout, Path findings) {}

  /** One input: the seed it came from, the mutations applied in order, its name and content. */
  record Input(
      long iteration,
      FuzzCorpus.Seed seed,
      List<String> mutations,
      String fileName,
      byte[] bytes) {}

  /** What one finding is about, and where its input and description were written. */
  record Finding(Kind kind, String signature, Input input, Path written) {}

  private final Settings settings;
  private final List<Finding> findings = new ArrayList<>();
  private final Map<String, Integer> perSignature = new TreeMap<>();
  private final Map<String, Integer> runsPerFormat = new TreeMap<>();
  private final Map<DocumentFormatResult.Outcome, Integer> outcomes = new TreeMap<>();
  private final TreeMap<Duration, String> slowest = new TreeMap<>();

  FuzzCampaign(Settings settings) {
    this.settings = settings;
  }

  /** Runs the campaign on a path of its own; see {@link #run(ProductionPath)}. */
  List<Finding> run() throws IOException {
    try (ProductionPath path = ProductionPath.start()) {
      return run(path);
    }
  }

  /**
   * Runs the campaign on {@code path} and answers its findings; the summary is written either way.
   */
  List<Finding> run(ProductionPath path) throws IOException {
    Files.createDirectories(settings.findings());
    Path work = Files.createTempDirectory("opaa-fuzz-");
    long started = System.nanoTime();
    long iterations = 0;
    int stuck = 0;
    try {
      List<String> admitted = path.registry().supportedFormats().extensions();
      List<FuzzCorpus.Seed> seeds = FuzzCorpus.load(admitted);
      List<byte[]> others = seeds.stream().map(FuzzCorpus.Seed::bytes).toList();
      long deadline = started + settings.duration().toNanos();
      ExecutorService executor = newExecutor();
      while (stuck < MAX_STUCK_RUNS
          && (iterations < seeds.size() || System.nanoTime() < deadline)) {
        Input input =
            iterations < seeds.size()
                ? asIs(iterations, seeds.get((int) iterations))
                : mutated(iterations, seeds, others, admitted);
        if (execute(path, executor, input, work)) {
          stuck++;
          executor.shutdownNow();
          executor = newExecutor();
        }
        iterations++;
      }
      executor.shutdownNow();
    } finally {
      deleteQuietly(work);
      writeSummary(iterations, Duration.ofNanos(System.nanoTime() - started), stuck);
    }
    return findings;
  }

  /** Runs each file of {@code inputs} once and answers the findings, for a reproduction. */
  List<Finding> replay(List<Path> inputs) throws IOException {
    Files.createDirectories(settings.findings());
    Path work = Files.createTempDirectory("opaa-fuzz-");
    long started = System.nanoTime();
    long iteration = 0;
    try (ProductionPath path = ProductionPath.start()) {
      ExecutorService executor = newExecutor();
      for (Path file : inputs) {
        String name = file.getFileName().toString();
        FuzzCorpus.Seed seed =
            new FuzzCorpus.Seed(name, FuzzCorpus.extensionOf(name), Files.readAllBytes(file));
        if (execute(path, executor, asIs(iteration++, seed), work)) {
          executor.shutdownNow();
          executor = newExecutor();
        }
      }
      executor.shutdownNow();
    } finally {
      deleteQuietly(work);
      writeSummary(iteration, Duration.ofNanos(System.nanoTime() - started), 0);
    }
    return findings;
  }

  private static Input asIs(long iteration, FuzzCorpus.Seed seed) {
    return new Input(iteration, seed, List.of(), seed.name(), seed.bytes());
  }

  /** The input of {@code iteration}: a seed, one to four mutations, now and then a wrong name. */
  Input mutated(
      long iteration, List<FuzzCorpus.Seed> seeds, List<byte[]> others, List<String> admitted) {
    SplittableRandom random =
        new SplittableRandom(settings.seed() ^ (iteration * 0x9E3779B97F4A7C15L));
    FuzzCorpus.Seed seed = seeds.get(random.nextInt(seeds.size()));
    byte[] bytes = seed.bytes();
    List<String> applied = new ArrayList<>();
    for (int i = random.nextInt(1, 5); i > 0; i--) {
      Mutators.Mutation mutation = Mutators.ALL.get(random.nextInt(Mutators.ALL.size()));
      try {
        bytes = mutation.operator().apply(bytes, random, others);
        applied.add(mutation.name());
      } catch (IOException | RuntimeException e) {
        applied.add(mutation.name() + " (not applicable)");
      }
    }
    String extension =
        random.nextInt(16) == 0 ? admitted.get(random.nextInt(admitted.size())) : seed.extension();
    return new Input(iteration, seed, applied, "fuzz-" + iteration + extension, bytes);
  }

  /** Runs one input; answers whether its run never returned and still holds the thread. */
  private boolean execute(ProductionPath path, ExecutorService executor, Input input, Path work)
      throws IOException {
    Path file = work.resolve(input.fileName());
    Files.write(file, input.bytes());
    writeCurrent(input);
    long started = System.nanoTime();
    Future<ProductionPath.Run> future = executor.submit(() -> path.run(file, input.fileName()));
    try {
      ProductionPath.Run run =
          future.get(settings.inputTimeout().toMillis(), TimeUnit.MILLISECONDS);
      noteDuration(input, Duration.ofNanos(System.nanoTime() - started));
      runsPerFormat.merge(run.format(), 1, Integer::sum);
      run.outcomes().forEach(outcome -> outcomes.merge(outcome, 1, Integer::sum));
      if (!run.leftoverTempFiles().isEmpty()) {
        record(
            Kind.LEFTOVER_TEMP_FILE,
            input,
            run.format(),
            "temp files left: " + run.leftoverTempFiles(),
            started);
        run.leftoverTempFiles().forEach(FuzzCampaign::deleteQuietly);
      }
      return false;
    } catch (TimeoutException e) {
      future.cancel(true);
      record(Kind.TIMEOUT, input, "", "no answer within " + settings.inputTimeout(), started);
      return true;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      Kind kind = cause instanceof OutOfMemoryError ? Kind.OUT_OF_MEMORY : Kind.ESCAPED_THROWABLE;
      record(kind, input, signatureOf(cause), stackTrace(cause), started);
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("fuzzing interrupted", e);
    } finally {
      Files.deleteIfExists(file);
      deleteCurrent();
    }
  }

  /**
   * Writes the input about to run to {@link Settings#findings} as {@code current-input}, with a
   * description beside it: should the run end the JVM, it is the reproducer left behind.
   */
  private void writeCurrent(Input input) throws IOException {
    deleteCurrent();
    Files.write(
        settings.findings().resolve(CURRENT + FuzzCorpus.extensionOf(input.fileName())),
        input.bytes());
    Files.writeString(
        settings.findings().resolve(CURRENT + ".finding"),
        String.join(
            "\n",
            "kind: the JVM ended during this input (out of memory or killed)",
            "campaign seed: " + settings.seed(),
            "iteration: " + input.iteration(),
            "seed file: " + input.seed().name(),
            "mutations: " + input.mutations(),
            "file name: " + input.fileName(),
            "reproduce: ./gradlew parserFuzzTest -PfuzzReplay=<path of the input file>"),
        StandardCharsets.UTF_8);
  }

  private void deleteCurrent() throws IOException {
    try (var files = Files.list(settings.findings())) {
      for (Path file : files.filter(f -> f.getFileName().toString().startsWith(CURRENT)).toList()) {
        Files.deleteIfExists(file);
      }
    }
  }

  /** Keeps the {@link #SLOWEST} slowest inputs for the summary. */
  private void noteDuration(Input input, Duration elapsed) {
    slowest.put(
        elapsed, input.fileName() + " from " + input.seed().name() + " " + input.mutations());
    if (slowest.size() > SLOWEST) {
      slowest.pollFirstEntry();
    }
  }

  private void record(Kind kind, Input input, String where, String detail, long started)
      throws IOException {
    String signature = kind + " " + FuzzCorpus.extensionOf(input.fileName()) + " " + where;
    int seen = perSignature.merge(signature, 1, Integer::sum);
    Path written = null;
    if (seen <= INPUTS_PER_SIGNATURE) {
      String base =
          String.format(
              "%03d-%s-%d", findings.size() + 1, kind.name().toLowerCase(), input.iteration());
      written = settings.findings().resolve(base + FuzzCorpus.extensionOf(input.fileName()));
      Files.write(written, input.bytes());
      Files.writeString(
          settings.findings().resolve(base + ".finding"),
          String.join(
              "\n",
              "kind: " + kind,
              "signature: " + signature,
              "campaign seed: " + settings.seed(),
              "iteration: " + input.iteration(),
              "seed file: " + input.seed().name(),
              "mutations: " + input.mutations(),
              "file name: " + input.fileName(),
              "bytes: " + input.bytes().length,
              "elapsed: " + Duration.ofNanos(System.nanoTime() - started),
              "reproduce: ./gradlew parserFuzzTest -PfuzzReplay=<path of the input file>",
              "",
              detail),
          StandardCharsets.UTF_8);
    }
    findings.add(new Finding(kind, signature, input, written));
  }

  private void writeSummary(long iterations, Duration elapsed, int stuck) {
    String summary =
        String.join(
            "\n",
            "campaign seed: " + settings.seed(),
            "duration: " + elapsed,
            "inputs: " + iterations,
            "runs per routed format: " + runsPerFormat,
            "outcomes (documents and attachments): " + outcomes,
            "runs that never returned: " + stuck,
            "slowest inputs:",
            slowest.descendingMap().entrySet().stream()
                .map(entry -> "  " + entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining("\n")),
            "findings: " + findings.size(),
            perSignature.entrySet().stream()
                .map(entry -> "  " + entry.getValue() + "x " + entry.getKey())
                .collect(Collectors.joining("\n")));
    System.out.println(LOG_PREFIX + summary.replace("\n", "\n" + LOG_PREFIX));
    try {
      Files.writeString(settings.findings().resolve("summary.txt"), summary + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The kind of throwable and the first frames of OPAA's own code it passed. */
  private static String signatureOf(Throwable cause) {
    String frames =
        Arrays.stream(cause.getStackTrace())
            .filter(frame -> frame.getClassName().startsWith("io.opaa."))
            .limit(2)
            .map(frame -> frame.getClassName() + "#" + frame.getMethodName())
            .collect(Collectors.joining(" < "));
    return cause.getClass().getName() + (frames.isEmpty() ? "" : " at " + frames);
  }

  private static String stackTrace(Throwable cause) {
    StringWriter out = new StringWriter();
    cause.printStackTrace(new PrintWriter(out));
    return out.toString();
  }

  private static ExecutorService newExecutor() {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, "parser-fuzz");
          thread.setDaemon(true);
          return thread;
        });
  }

  private static void deleteQuietly(Path path) {
    try (var walk = Files.walk(path)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(
              file -> {
                try {
                  Files.deleteIfExists(file);
                } catch (IOException e) {
                  // a file the stuck run still holds stays behind with the temp directory
                }
              });
    } catch (IOException e) {
      // nothing to delete
    }
  }
}
