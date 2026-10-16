package io.opaa.compliance;

import io.opaa.compliance.ManifestDeltaRenderer.LabelledChanges;
import io.opaa.compliance.ManifestDeltaRenderer.PullRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entry point of {@code ./gradlew complianceDelta}: writes {@value #MARKDOWN_FILE} and {@value
 * #JSON_FILE} for two versions into an output directory. Runs offline on Git history and files.
 *
 * <p>Arguments: {@code --out <dir>}, one of {@code --from-ref <ref>}, {@code --from-json <file>},
 * {@code --from-dir <dir>}, one of {@code --to-ref}, {@code --to-json}, {@code --to-dir}, and
 * optionally {@code --repo <dir>} (default: the working directory) and {@code --labelled-prs
 * <file>}: a JSON array of pull requests labelled {@code data-impact} with {@code number}, {@code
 * title} and {@code url}, as {@code gh pr list --json number,title,url} writes it. The list is
 * narrowed to the pull requests whose number a commit subject between both versions names.
 */
public final class ManifestDeltaGenerator {

  /** File name of the Markdown form. */
  public static final String MARKDOWN_FILE = "manifest-delta.md";

  /** File name of the JSON form. */
  public static final String JSON_FILE = "manifest-delta.json";

  private static final Pattern PR_IN_SUBJECT =
      Pattern.compile("\\(#(\\d+)\\)|^Merge pull request #(\\d+)");
  private static final Set<String> OPTIONS =
      Set.of(
          "--out",
          "--repo",
          "--from-ref",
          "--from-json",
          "--from-dir",
          "--to-ref",
          "--to-json",
          "--to-dir",
          "--labelled-prs");
  private static final String USAGE =
      "usage: ManifestDeltaGenerator --out <dir> (--from-ref <ref> | --from-json <file> |"
          + " --from-dir <dir>) (--to-ref <ref> | --to-json <file> | --to-dir <dir>)"
          + " [--repo <dir>] [--labelled-prs <file>]";

  private ManifestDeltaGenerator() {}

  public static void main(String[] args) {
    Map<String, String> options = options(args);
    Path repository = Path.of(options.getOrDefault("--repo", "."));
    ManifestSnapshot from = snapshot(options, "--from", repository);
    ManifestSnapshot to = snapshot(options, "--to", repository);
    LabelledChanges labelled =
        options.containsKey("--labelled-prs")
            ? labelled(Path.of(options.get("--labelled-prs")), repository, from, to)
            : LabelledChanges.notQueried();
    List<Path> written =
        write(Path.of(require(options, "--out")), ManifestDelta.between(from, to), labelled);
    written.forEach(file -> System.out.println("written: " + file));
  }

  /** Writes both forms of {@code delta} into {@code directory}, replacing earlier ones. */
  static List<Path> write(Path directory, ManifestDelta delta, LabelledChanges labelled) {
    try {
      Files.createDirectories(directory);
      return List.of(
          Files.writeString(
              directory.resolve(MARKDOWN_FILE),
              ManifestDeltaRenderer.markdown(delta, labelled),
              StandardCharsets.UTF_8),
          Files.writeString(
              directory.resolve(JSON_FILE),
              ManifestDeltaRenderer.json(delta, labelled),
              StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write the manifest delta to " + directory, e);
    }
  }

  /**
   * The pull requests of {@code file} whose number a commit subject between both versions names;
   * all of them, marked as not narrowed, when Git cannot list that range.
   */
  static LabelledChanges labelled(
      Path file, Path repository, ManifestSnapshot from, ManifestSnapshot to) {
    List<PullRequest> all = new ArrayList<>();
    try {
      JsonNode root = JsonMapper.builder().build().readTree(Files.readString(file));
      if (!root.isArray()) {
        throw new IllegalArgumentException(file + " must hold a JSON array of pull requests");
      }
      for (JsonNode node : root) {
        all.add(
            new PullRequest(
                node.path("number").asInt(),
                node.path("title").asString(""),
                node.path("url").asString("")));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
    all.sort(Comparator.comparingInt(PullRequest::number));
    Set<Integer> inRange = numbersInRange(repository, from, to);
    if (inRange == null) {
      return new LabelledChanges(true, false, all);
    }
    return new LabelledChanges(
        true, true, all.stream().filter(pr -> inRange.contains(pr.number())).toList());
  }

  private static Set<Integer> numbersInRange(
      Path repository, ManifestSnapshot from, ManifestSnapshot to) {
    String fromCommit = from.origin().commit();
    String toCommit = to.origin().commit();
    if (fromCommit.equals(ManifestSnapshot.UNKNOWN) || toCommit.equals(ManifestSnapshot.UNKNOWN)) {
      return null;
    }
    try {
      Set<Integer> numbers = new HashSet<>();
      for (String subject : ManifestSnapshot.Git.subjects(repository, fromCommit, toCommit)) {
        Matcher matcher = PR_IN_SUBJECT.matcher(subject);
        while (matcher.find()) {
          String number = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
          numbers.add(Integer.parseInt(number));
        }
      }
      return numbers;
    } catch (IllegalStateException | UncheckedIOException e) {
      return null;
    }
  }

  private static ManifestSnapshot snapshot(
      Map<String, String> options, String side, Path repository) {
    List<String> given =
        List.of(side + "-ref", side + "-json", side + "-dir").stream()
            .filter(options::containsKey)
            .toList();
    if (given.size() != 1) {
      throw new IllegalArgumentException(
          "give exactly one of " + side + "-ref, " + side + "-json, " + side + "-dir; " + USAGE);
    }
    String option = given.getFirst();
    String value = options.get(option);
    return switch (option.substring(side.length())) {
      case "-ref" -> ManifestSnapshot.fromGit(repository, value);
      case "-json" -> ManifestSnapshot.fromJson(Path.of(value));
      default ->
          ManifestSnapshot.fromDirectory(Path.of(value), ManifestSnapshot.Git.head(repository));
    };
  }

  private static Map<String, String> options(String[] args) {
    Map<String, String> options = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      if (!OPTIONS.contains(args[i]) || i + 1 >= args.length || args[i + 1].isBlank()) {
        throw new IllegalArgumentException("unexpected argument '" + args[i] + "'; " + USAGE);
      }
      if (options.put(args[i], args[i + 1]) != null) {
        throw new IllegalArgumentException(args[i] + " given twice; " + USAGE);
      }
    }
    return options;
  }

  private static String require(Map<String, String> options, String option) {
    String value = options.get(option);
    if (value == null) {
      throw new IllegalArgumentException(option + " is missing; " + USAGE);
    }
    return value;
  }
}
