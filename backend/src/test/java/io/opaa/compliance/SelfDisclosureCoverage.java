package io.opaa.compliance;

import com.tngtech.archunit.core.domain.JavaClass;
import io.opaa.architecture.MainClasses;
import io.opaa.compliance.ComplianceManifest.AccessPath;
import io.opaa.compliance.ComplianceManifest.DataEntry;
import io.opaa.compliance.ComplianceManifest.NonPersonalTable;
import io.opaa.compliance.ComplianceManifest.TableRef;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Holds the self-disclosure exporters against the fact manifest, down to the columns: every
 * category of the manifest has exactly one exporter or stands in a pending list, every field names
 * a column the disclosure may hold, and every column the manifest leads as part of the disclosure
 * is covered by a field (ADR-0052, Entscheidung 12). The columns of the path to the person count as
 * covered by the selection itself.
 */
final class SelfDisclosureCoverage {

  /** The pending lists, one file per exporter group, one category id per line. */
  static final String PENDING_DIR = "src/test/resources/compliance/self-disclosure-pending/";

  private SelfDisclosureCoverage() {}

  /** The {@code CATEGORY} of every exporter class of the backend, by class name. */
  static Map<String, SelfDisclosureCategory> productionCategories() {
    Map<String, SelfDisclosureCategory> categories = new TreeMap<>();
    for (JavaClass type : MainClasses.get()) {
      if (type.isInterface()
          || type.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT)
          || !type.isAssignableTo(SelfDisclosureExporter.class)) {
        continue;
      }
      categories.put(type.getName(), categoryOf(type.reflect()));
    }
    return categories;
  }

  private static SelfDisclosureCategory categoryOf(Class<?> exporter) {
    try {
      java.lang.reflect.Field field = exporter.getField("CATEGORY");
      int modifiers = field.getModifiers();
      if (!Modifier.isStatic(modifiers)
          || !Modifier.isFinal(modifiers)
          || field.getType() != SelfDisclosureCategory.class) {
        return null;
      }
      return (SelfDisclosureCategory) field.get(null);
    } catch (NoSuchFieldException | IllegalAccessException missing) {
      return null;
    }
  }

  /** The pending lists of the backend, file name to category ids. */
  static Map<String, List<String>> productionPending() {
    Map<String, List<String>> pending = new TreeMap<>();
    Path dir = Path.of(PENDING_DIR);
    if (!Files.isDirectory(dir)) {
      return pending;
    }
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".txt")).sorted().toList()) {
        pending.put(
            file.getFileName().toString(),
            Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return pending;
  }

  /**
   * Every gap between {@code exporters} (class name to its category, {@code null} without a usable
   * constant), {@code pending} (file name to ids) and {@code manifest}.
   */
  static List<String> gaps(
      ComplianceManifest manifest,
      Map<String, SelfDisclosureCategory> exporters,
      Map<String, List<String>> pending) {
    List<String> gaps = new ArrayList<>();
    Map<String, DataEntry> categories = new LinkedHashMap<>();
    manifest.data().stream()
        .filter(DataEntry::selfDisclosed)
        .forEach(d -> categories.put(d.id(), d));
    Columns columns = Columns.of(manifest);

    Map<String, String> exporterOf = new HashMap<>();
    exporters.forEach(
        (className, category) -> {
          if (category == null) {
            gaps.add(
                "Exporter "
                    + className
                    + " has no 'public static final SelfDisclosureCategory CATEGORY'.");
            return;
          }
          String previous = exporterOf.putIfAbsent(category.id(), className);
          if (previous != null) {
            gaps.add(
                "Category '"
                    + category.id()
                    + "' has two exporters, "
                    + previous
                    + " and "
                    + className
                    + "; keep one.");
          }
          DataEntry data = categories.get(category.id());
          if (data == null) {
            gaps.add(
                "Exporter "
                    + className
                    + " delivers '"
                    + category.id()
                    + "', which is no data entry of the manifest with a table that reaches the"
                    + " person (person.columns or person.via).");
            return;
          }
          if (!data.accessPaths().contains(AccessPath.EXPORT)) {
            gaps.add(
                "Data entry '"
                    + data.id()
                    + "' in "
                    + ManifestCompleteness.MANIFEST_DIR
                    + data.area()
                    + ".yaml has an exporter of the self-disclosure; add EXPORT to its"
                    + " accessPaths.");
          }
          gaps.addAll(fieldGaps(className, category, columns));
          gaps.addAll(coverageGaps(className, category, data));
        });

    Map<String, String> pendingIn = new HashMap<>();
    pending.forEach(
        (file, ids) -> {
          for (String id : ids) {
            String other = pendingIn.putIfAbsent(id, file);
            if (other != null) {
              gaps.add("Category '" + id + "' is pending in both " + other + " and " + file + ".");
            }
            if (!categories.containsKey(id)) {
              gaps.add(
                  "Pending list "
                      + PENDING_DIR
                      + file
                      + " names '"
                      + id
                      + "', which is no category of the self-disclosure; remove the line.");
            } else if (exporterOf.containsKey(id)) {
              gaps.add(
                  "Category '"
                      + id
                      + "' has the exporter "
                      + exporterOf.get(id)
                      + " and is still pending in "
                      + PENDING_DIR
                      + file
                      + "; remove the line.");
            }
          }
        });

    for (DataEntry data : categories.values()) {
      if (!exporterOf.containsKey(data.id()) && !pendingIn.containsKey(data.id())) {
        gaps.add(
            "Category '"
                + data.id()
                + "' ("
                + ManifestCompleteness.MANIFEST_DIR
                + data.area()
                + ".yaml) has no self-disclosure exporter. Add a @Component implementing"
                + " io.opaa.compliance.SelfDisclosureExporter in the module owning the data, with"
                + " 'public static final SelfDisclosureCategory CATEGORY' whose id is '"
                + data.id()
                + "', or exclude its tables from the self-disclosure (person.excluded).");
      }
    }
    return gaps;
  }

  private static List<String> fieldGaps(
      String className, SelfDisclosureCategory category, Columns columns) {
    List<String> gaps = new ArrayList<>();
    for (Section section : category.sections()) {
      for (Field field : section.fields()) {
        for (String column : field.columns()) {
          String where =
              "Field "
                  + category.id()
                  + "/"
                  + section.key()
                  + "/"
                  + field.key()
                  + " of "
                  + className
                  + " names "
                  + column;
          if (!columns.known.contains(column)) {
            gaps.add(where + ", which the manifest does not know; name an existing table.column.");
          } else if (columns.excluded.containsKey(column) && !field.derived()) {
            gaps.add(
                where
                    + ", which the manifest excludes from the self-disclosure ("
                    + columns.excluded.get(column)
                    + "). Drop the field, or declare it Field.derived(...) when it only says yes/no"
                    + " or a fixed label about the column.");
          }
        }
      }
    }
    return gaps;
  }

  private static List<String> coverageGaps(
      String className, SelfDisclosureCategory category, DataEntry data) {
    Set<String> named = new HashSet<>();
    category.sections().stream()
        .flatMap(section -> section.fields().stream())
        .forEach(field -> named.addAll(field.columns()));
    List<String> gaps = new ArrayList<>();
    for (TableRef table : data.tables()) {
      List<String> path =
          table.person() == null || table.person().columns() == null
              ? List.of()
              : table.person().columns();
      for (String column : table.disclosedColumns()) {
        String qualified = table.name() + "." + column;
        if (!path.contains(column) && !named.contains(qualified)) {
          gaps.add(
              "Column "
                  + qualified
                  + " is part of the self-disclosure of '"
                  + data.id()
                  + "', but no field of "
                  + className
                  + " names it. Add a field naming \""
                  + qualified
                  + "\", or exclude the column with a reason under person.excludedColumns in "
                  + ManifestCompleteness.MANIFEST_DIR
                  + data.area()
                  + ".yaml.");
        }
      }
    }
    return gaps;
  }

  /** The columns of the manifest: all known ones and those excluded from the disclosure. */
  private record Columns(Set<String> known, Map<String, String> excluded) {

    static Columns of(ComplianceManifest manifest) {
      Set<String> known = new LinkedHashSet<>();
      Map<String, String> excluded = new HashMap<>();
      for (DataEntry data : manifest.data()) {
        for (TableRef table : data.tables()) {
          List<String> all = new ArrayList<>(table.personalColumns());
          if (table.nonPersonalColumns() != null) {
            all.addAll(table.nonPersonalColumns());
          }
          for (String column : all) {
            String qualified = table.name() + "." + column;
            known.add(qualified);
            if (table.person() != null && !table.person().reachesPerson()) {
              excluded.put(qualified, table.person().excluded());
            }
          }
          if (table.person() != null) {
            table
                .person()
                .excludedColumnsOrEmpty()
                .forEach((column, reason) -> excluded.put(table.name() + "." + column, reason));
          }
        }
      }
      for (NonPersonalTable table : manifest.nonPersonalTables()) {
        for (String column : table.columns()) {
          known.add(table.table() + "." + column);
        }
      }
      return new Columns(known, excluded);
    }
  }
}
