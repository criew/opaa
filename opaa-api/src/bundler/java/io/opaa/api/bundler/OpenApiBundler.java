package io.opaa.api.bundler;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Merges the OpenAPI fragments of one directory into a single spec: {@code root.yaml} first, then
 * every other {@code *.yaml} in name order. A fragment may only hold {@code tags}, {@code paths}
 * and {@code components}; a key defined twice fails the build instead of silently overriding.
 *
 * <p>Works on the composed node tree, not on loaded values, so every scalar keeps its source style
 * and resolves exactly as it did in the fragment. {@code frontend/scripts/bundle-openapi.mjs}
 * implements the same rules for the frontend build, which has no JVM.
 *
 * <p>Validation: no YAML anchors or aliases; every {@code $ref} is local ({@code #/...}) and
 * resolves in the merged document; every operation carries exactly one tag, declared in the {@code
 * tags} of its own fragment; every declared tag is used.
 */
public final class OpenApiBundler {

  static final String ROOT_FILE = "root.yaml";
  private static final Pattern FRAGMENT_NAME = Pattern.compile("[a-z0-9-]+\\.yaml");
  private static final Set<String> FRAGMENT_KEYS = Set.of("tags", "paths", "components");
  private static final Set<String> HTTP_METHODS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  private OpenApiBundler() {}

  /** Arguments: fragment directory, output file. */
  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      throw new IllegalArgumentException("usage: OpenApiBundler <fragmentDir> <outputFile>");
    }
    Path out = Path.of(args[1]);
    Files.createDirectories(out.getParent());
    Files.writeString(out, bundle(Path.of(args[0])), StandardCharsets.UTF_8);
  }

  /** Returns the merged spec as YAML text. */
  public static String bundle(Path dir) throws IOException {
    List<Path> fragments;
    try (Stream<Path> files = Files.list(dir)) {
      fragments =
          files
              .filter(p -> p.getFileName().toString().endsWith(".yaml"))
              .filter(p -> !p.getFileName().toString().equals(ROOT_FILE))
              .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
              .toList();
    }
    MappingNode root = compose(dir.resolve(ROOT_FILE));
    List<NodeTuple> head = new ArrayList<>();
    Map<String, NodeTuple> tags = new LinkedHashMap<>();
    Map<String, NodeTuple> paths = new LinkedHashMap<>();
    Map<String, Map<String, NodeTuple>> components = new LinkedHashMap<>();
    Map<String, String> tagOrigin = new LinkedHashMap<>();
    Map<String, String> pathOrigin = new LinkedHashMap<>();

    for (NodeTuple t : root.getValue()) {
      String key = key(t);
      switch (key) {
        case "tags", "paths", "components" ->
            mergeSection(
                key, t.getValueNode(), ROOT_FILE, tags, paths, components, tagOrigin, pathOrigin);
        default -> head.add(t);
      }
    }
    for (Path fragment : fragments) {
      String name = fragment.getFileName().toString();
      if (!FRAGMENT_NAME.matcher(name).matches()) {
        throw fail(name, "file name must match " + FRAGMENT_NAME.pattern());
      }
      MappingNode doc = compose(fragment);
      for (NodeTuple t : doc.getValue()) {
        String key = key(t);
        if (!FRAGMENT_KEYS.contains(key)) {
          throw fail(name, "top-level key '" + key + "' is not allowed, only " + FRAGMENT_KEYS);
        }
        mergeSection(key, t.getValueNode(), name, tags, paths, components, tagOrigin, pathOrigin);
      }
    }

    List<NodeTuple> merged = new ArrayList<>(head);
    if (!tags.isEmpty()) {
      merged.add(
          tuple("tags", new SequenceNode(Tag.SEQ, valuesOf(tags), DumperOptions.FlowStyle.BLOCK)));
    }
    merged.add(tuple("paths", mapping(new ArrayList<>(paths.values()))));
    List<NodeTuple> componentTuples = new ArrayList<>();
    components.forEach(
        (kind, entries) ->
            componentTuples.add(tuple(kind, mapping(new ArrayList<>(entries.values())))));
    merged.add(tuple("components", mapping(componentTuples)));
    MappingNode result = mapping(merged);

    validateRefs(result, result);
    validateTags(paths, pathOrigin, tagOrigin);
    return serialize(result);
  }

  private static void mergeSection(
      String key,
      Node value,
      String file,
      Map<String, NodeTuple> tags,
      Map<String, NodeTuple> paths,
      Map<String, Map<String, NodeTuple>> components,
      Map<String, String> tagOrigin,
      Map<String, String> pathOrigin) {
    switch (key) {
      case "tags" -> {
        if (!(value instanceof SequenceNode seq)) {
          throw fail(file, "'tags' must be a list");
        }
        for (Node item : seq.getValue()) {
          String name = scalar(get(asMapping(item, file, "tag"), "name"), file, "tag name");
          if (tags.containsKey(name)) {
            throw fail(file, "tag '" + name + "' is already declared in " + tagOrigin.get(name));
          }
          tags.put(name, tuple(name, item));
          tagOrigin.put(name, file);
        }
      }
      case "paths" -> {
        MappingNode map = asMapping(value, file, "paths");
        putAll(paths, map, file, "path");
        map.getValue().forEach(t -> pathOrigin.put(key(t), file));
      }
      case "components" -> {
        for (NodeTuple kind : asMapping(value, file, "components").getValue()) {
          putAll(
              components.computeIfAbsent(key(kind), k -> new LinkedHashMap<>()),
              asMapping(kind.getValueNode(), file, "components/" + key(kind)),
              file,
              "components/" + key(kind));
        }
      }
      default -> throw new IllegalStateException(key);
    }
  }

  private static void putAll(
      Map<String, NodeTuple> target, MappingNode source, String file, String what) {
    for (NodeTuple t : source.getValue()) {
      if (target.putIfAbsent(key(t), t) != null) {
        throw fail(file, what + " '" + key(t) + "' is defined in more than one fragment");
      }
    }
  }

  private static void validateTags(
      Map<String, NodeTuple> paths, Map<String, String> pathOrigin, Map<String, String> tagOrigin) {
    Set<String> used = new HashSet<>();
    for (NodeTuple path : paths.values()) {
      String file = pathOrigin.get(key(path));
      for (NodeTuple op : asMapping(path.getValueNode(), file, key(path)).getValue()) {
        if (!HTTP_METHODS.contains(key(op))) {
          continue;
        }
        String where = key(op) + " " + key(path);
        Node tagList = get(asMapping(op.getValueNode(), file, where), "tags");
        if (!(tagList instanceof SequenceNode seq) || seq.getValue().size() != 1) {
          throw fail(file, where + " must carry exactly one tag");
        }
        String tag = scalar(seq.getValue().get(0), file, where + " tag");
        if (!file.equals(tagOrigin.get(tag))) {
          throw fail(file, where + " uses tag '" + tag + "', which is not declared in this file");
        }
        used.add(tag);
      }
    }
    for (Map.Entry<String, String> declared : tagOrigin.entrySet()) {
      if (!used.contains(declared.getKey())) {
        throw fail(declared.getValue(), "tag '" + declared.getKey() + "' is declared but unused");
      }
    }
  }

  private static void validateRefs(Node node, MappingNode doc) {
    if (node instanceof MappingNode map) {
      for (NodeTuple t : map.getValue()) {
        if ("$ref".equals(key(t)) && t.getValueNode() instanceof ScalarNode ref) {
          resolve(ref.getValue(), doc);
        } else {
          validateRefs(t.getValueNode(), doc);
        }
      }
    } else if (node instanceof SequenceNode seq) {
      seq.getValue().forEach(n -> validateRefs(n, doc));
    }
  }

  private static void resolve(String ref, MappingNode doc) {
    if (!ref.startsWith("#/")) {
      throw new IllegalStateException(
          "$ref '" + ref + "' is not local - fragments refer into the merged document (#/...)");
    }
    Node current = doc;
    for (String segment : ref.substring(2).split("/")) {
      String name = segment.replace("~1", "/").replace("~0", "~");
      current = current instanceof MappingNode map ? get(map, name) : null;
      if (current == null) {
        throw new IllegalStateException("$ref '" + ref + "' does not resolve in the merged spec");
      }
    }
  }

  private static MappingNode compose(Path file) throws IOException {
    LoaderOptions options = new LoaderOptions();
    options.setCodePointLimit(Integer.MAX_VALUE);
    String name = file.getFileName().toString();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      Node node = new Yaml(options).compose(reader);
      rejectAnchors(node, name);
      return asMapping(node, name, "document");
    } catch (YAMLException e) {
      throw fail(name, e.getMessage());
    }
  }

  private static void rejectAnchors(Node node, String file) {
    if (node.getAnchor() != null) {
      throw fail(file, "YAML anchors and aliases are not allowed (&" + node.getAnchor() + ")");
    }
    if (node instanceof MappingNode map) {
      for (NodeTuple t : map.getValue()) {
        rejectAnchors(t.getKeyNode(), file);
        rejectAnchors(t.getValueNode(), file);
      }
    } else if (node instanceof SequenceNode seq) {
      seq.getValue().forEach(n -> rejectAnchors(n, file));
    }
  }

  private static String serialize(Node node) throws IOException {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setAllowUnicode(true);
    options.setWidth(Integer.MAX_VALUE);
    options.setSplitLines(false);
    options.setIndent(2);
    options.setIndicatorIndent(2);
    options.setIndentWithIndicator(true);
    options.setLineBreak(DumperOptions.LineBreak.UNIX);
    Writer out = new StringWriter();
    new Yaml(options).serialize(node, out);
    return out.toString();
  }

  private static MappingNode asMapping(Node node, String file, String what) {
    if (node instanceof MappingNode map) {
      return map;
    }
    throw fail(file, what + " must be a mapping");
  }

  private static Node get(MappingNode map, String key) {
    for (NodeTuple t : map.getValue()) {
      if (key.equals(key(t))) {
        return t.getValueNode();
      }
    }
    return null;
  }

  private static String scalar(Node node, String file, String what) {
    if (node instanceof ScalarNode s) {
      return s.getValue();
    }
    throw fail(file, what + " must be a scalar");
  }

  private static String key(NodeTuple t) {
    return ((ScalarNode) t.getKeyNode()).getValue();
  }

  private static NodeTuple tuple(String key, Node value) {
    return new NodeTuple(
        new ScalarNode(Tag.STR, key, null, null, DumperOptions.ScalarStyle.PLAIN), value);
  }

  private static MappingNode mapping(List<NodeTuple> tuples) {
    return new MappingNode(Tag.MAP, tuples, DumperOptions.FlowStyle.BLOCK);
  }

  private static List<Node> valuesOf(Map<String, NodeTuple> tags) {
    return tags.values().stream().map(NodeTuple::getValueNode).toList();
  }

  private static IllegalStateException fail(String file, String message) {
    return new IllegalStateException(file + ": " + message);
  }
}
