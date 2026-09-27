package io.opaa.migration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.exception.LiquibaseException;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;

/**
 * The master changelog as Liquibase itself resolves it, {@code includeAll} ordering included. The
 * order of its files is the order a fresh installation applies them in.
 */
final class MasterChangelog {

  static final String PATH = "db/changelog/db.changelog-master.yaml";

  /** The prefix of every module directory; the next path segment is the module's name. */
  static final String MODULE_ROOT = "db/changelog/";

  private MasterChangelog() {}

  /** Every changeSet of the master, in execution order. */
  static List<ChangeSet> changeSets() {
    ResourceAccessor accessor = new ClassLoaderResourceAccessor();
    try {
      DatabaseChangeLog changeLog =
          ChangeLogParserFactory.getInstance()
              .getParser(PATH, accessor)
              .parse(PATH, new ChangeLogParameters(), accessor);
      return changeLog.getChangeSets();
    } catch (LiquibaseException e) {
      throw new IllegalStateException("cannot parse " + PATH, e);
    }
  }

  /** Every changelog file of the master, in execution order. */
  static List<String> files() {
    Set<String> files = new LinkedHashSet<>();
    changeSets().forEach(changeSet -> files.add(changeSet.getFilePath()));
    return new ArrayList<>(files);
  }

  /**
   * Every file the master applies before {@code changelogFile} on a fresh installation: all files
   * of the lower modules, then the earlier files of its own module.
   */
  static List<String> filesBefore(String changelogFile) {
    List<String> files = files();
    int index = files.indexOf(changelogFile);
    if (index < 0) {
      throw new IllegalArgumentException(
          changelogFile + " is not part of " + PATH + "; its files are " + files);
    }
    return List.copyOf(files.subList(0, index));
  }

  /** The module directory name of a changelog file, e.g. {@code rights}. */
  static String moduleOf(String changelogFile) {
    if (!changelogFile.startsWith(MODULE_ROOT)) {
      throw new IllegalArgumentException(changelogFile + " lies outside " + MODULE_ROOT);
    }
    String rest = changelogFile.substring(MODULE_ROOT.length());
    int slash = rest.indexOf('/');
    if (slash < 0) {
      throw new IllegalArgumentException(changelogFile + " lies in no module directory");
    }
    return rest.substring(0, slash);
  }
}
