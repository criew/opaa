package io.opaa.migration;

import io.opaa.architecture.ModularArchitecture;
import io.opaa.architecture.ModularArchitecture.Module;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Judges the database objects each module's step creates or changes against a table-to-module
 * assignment and {@link ModularArchitecture#ALLOWED_MODULE_EDGES}. A step may create and change
 * only tables of its own module; a trigger it creates, a table a function it creates names and a
 * foreign key of its tables may reach its own module or one it may depend on. A step may drop or
 * replace only triggers and functions its own module created. Extension objects are not judged.
 */
final class ModuleBoundaryCheck {

  /** Applies one module's step to the database the check observes. */
  @FunctionalInterface
  interface StepAction {
    void apply() throws Exception;
  }

  private final Connection connection;
  private final Map<String, Module> tableModules;
  private final List<String> violations = new ArrayList<>();
  private final Map<String, Module> triggerCreators = new HashMap<>();
  private final Map<String, Module> functionCreators = new HashMap<>();
  private Snapshot previous;

  /**
   * Starts observing {@code connection}'s current schema; what exists already belongs to no step.
   */
  ModuleBoundaryCheck(Connection connection, Map<String, Module> tableModules) throws SQLException {
    this.connection = connection;
    this.tableModules = tableModules;
    this.previous = snapshot();
  }

  /** Runs {@code action} as a step of {@code module} and records what it breaks. */
  void step(Module module, StepAction action) throws Exception {
    action.apply();
    Snapshot current = snapshot();
    judgeTables(module, current);
    judgeTriggers(module, current);
    judgeFunctions(module, current);
    previous = current;
  }

  /**
   * Every violation so far, plus those of the final state: foreign keys between modules and
   * assigned tables that no step created.
   */
  List<String> violations() throws SQLException {
    List<String> all = new ArrayList<>(violations);
    for (ForeignKey foreignKey : foreignKeys()) {
      Module from = tableModules.get(foreignKey.table());
      Module to = tableModules.get(foreignKey.referencedTable());
      if (from != null && to != null && !mayReach(from, to)) {
        all.add(
            "foreign key "
                + foreignKey.name()
                + " points upward from "
                + foreignKey.table()
                + " ("
                + from
                + ") to "
                + foreignKey.referencedTable()
                + " ("
                + to
                + ")");
      }
    }
    tableModules.keySet().stream()
        .filter(table -> !previous.tables().containsKey(table))
        .sorted()
        .forEach(table -> all.add(table + " is assigned to a module but no changelog creates it"));
    return all;
  }

  private void judgeTables(Module module, Snapshot current) {
    current
        .tables()
        .forEach(
            (table, state) -> {
              TableState before = previous.tables().get(table);
              if (before == null) {
                Module owner = tableModules.get(state.logicalTable());
                if (owner == null) {
                  violations.add(
                      "table "
                          + state.logicalTable()
                          + " (created by "
                          + module
                          + ") is assigned to no module in ChangelogModuleBoundaryTest.TABLE_MODULES");
                } else if (owner != module) {
                  violations.add(
                      module + " creates table " + table + ", which belongs to module " + owner);
                }
              } else if (!before.fingerprint().equals(state.fingerprint())) {
                judgeChange(module, table, state.logicalTable(), "changes");
              }
            });
    previous
        .tables()
        .forEach(
            (table, state) -> {
              if (!current.tables().containsKey(table)) {
                judgeChange(module, table, state.logicalTable(), "drops");
              }
            });
  }

  private void judgeChange(Module module, String table, String logicalTable, String verb) {
    Module owner = tableModules.get(logicalTable);
    if (owner != null && owner != module) {
      violations.add(module + " " + verb + " table " + table + " of module " + owner);
    }
  }

  private void judgeTriggers(Module module, Snapshot current) {
    current
        .triggers()
        .forEach(
            (trigger, table) -> {
              if (!previous.triggers().containsKey(trigger)) {
                triggerCreators.put(trigger, module);
                Module owner = tableModules.get(logicalTable(current, table));
                if (owner != null && !mayReach(module, owner)) {
                  violations.add(
                      module
                          + " creates trigger "
                          + trigger
                          + " on table "
                          + table
                          + " of module "
                          + owner
                          + ", which it may not depend on");
                }
              }
            });
    previous.triggers().keySet().stream()
        .filter(trigger -> !current.triggers().containsKey(trigger))
        .forEach(trigger -> judgeForeignObject(module, "drops trigger", trigger, triggerCreators));
  }

  private void judgeFunctions(Module module, Snapshot current) {
    current
        .functions()
        .forEach(
            (function, source) -> {
              String before = previous.functions().get(function);
              if (before == null) {
                functionCreators.put(function, module);
              } else if (!before.equals(source)) {
                judgeForeignObject(module, "replaces function", function, functionCreators);
              } else {
                return;
              }
              tableModules.forEach(
                  (table, owner) -> {
                    if (!mayReach(module, owner)
                        && Pattern.compile("\\b" + table + "\\b").matcher(source).find()) {
                      violations.add(
                          module
                              + " function "
                              + function
                              + " names table "
                              + table
                              + " of module "
                              + owner
                              + ", which it may not depend on");
                    }
                  });
            });
    previous.functions().keySet().stream()
        .filter(function -> !current.functions().containsKey(function))
        .forEach(
            function -> judgeForeignObject(module, "drops function", function, functionCreators));
  }

  private void judgeForeignObject(
      Module module, String verb, String object, Map<String, Module> creators) {
    Module creator = creators.get(object);
    if (creator != null && creator != module) {
      violations.add(module + " " + verb + " " + object + ", created by module " + creator);
    }
  }

  private static boolean mayReach(Module from, Module to) {
    return from == to || ModularArchitecture.ALLOWED_MODULE_EDGES.get(from).contains(to);
  }

  private static String logicalTable(Snapshot snapshot, String table) {
    TableState state = snapshot.tables().get(table);
    return state == null ? table : state.logicalTable();
  }

  // ---------------------------------------------------------------------------------------------
  // Catalog reads, all confined to current_schema()
  // ---------------------------------------------------------------------------------------------

  private Snapshot snapshot() throws SQLException {
    return new Snapshot(tables(), triggers(), functions());
  }

  /**
   * Every table and partition with its logical table (a partition's parent) and a fingerprint of
   * everything but its triggers: columns, constraints, indexes, owner and privileges.
   */
  private Map<String, TableState> tables() throws SQLException {
    Map<String, TableState> tables = new HashMap<>();
    query(
        """
        SELECT c.relname,
               COALESCE(parent.relname, c.relname),
               concat_ws(' | ',
                 (SELECT string_agg(a.attname || ' ' || format_type(a.atttypid, a.atttypmod)
                          || CASE WHEN a.attnotnull THEN ' not null' ELSE '' END
                          || COALESCE(' default ' || pg_get_expr(d.adbin, d.adrelid), ''),
                          ', ' ORDER BY a.attname)
                    FROM pg_attribute a
                    LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                   WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped),
                 (SELECT string_agg(con.conname || ' ' || pg_get_constraintdef(con.oid), ', '
                          ORDER BY con.conname)
                    FROM pg_constraint con WHERE con.conrelid = c.oid),
                 (SELECT string_agg(pg_get_indexdef(i.indexrelid), ', '
                          ORDER BY pg_get_indexdef(i.indexrelid))
                    FROM pg_index i WHERE i.indrelid = c.oid),
                 pg_get_userbyid(c.relowner),
                 c.relacl::text)
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          LEFT JOIN pg_inherits inh ON inh.inhrelid = c.oid
          LEFT JOIN pg_class parent ON parent.oid = inh.inhparent
         WHERE n.nspname = current_schema()
           AND c.relkind IN ('r', 'p')
           AND c.relname NOT IN ('databasechangelog', 'databasechangeloglock')
        """,
        rs -> tables.put(rs.getString(1), new TableState(rs.getString(2), rs.getString(3))));
    return tables;
  }

  /** Every trigger a changelog created, keyed by {@code table.trigger}, with its table. */
  private Map<String, String> triggers() throws SQLException {
    Map<String, String> triggers = new HashMap<>();
    query(
        """
        SELECT c.relname, t.tgname
          FROM pg_trigger t
          JOIN pg_class c ON c.oid = t.tgrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = current_schema() AND NOT t.tgisinternal AND t.tgparentid = 0
        """,
        rs -> triggers.put(rs.getString(1) + "." + rs.getString(2), rs.getString(1)));
    return triggers;
  }

  /** Every function outside an extension, keyed by its signature, with its source. */
  private Map<String, String> functions() throws SQLException {
    Map<String, String> functions = new HashMap<>();
    query(
        """
        SELECT p.oid::regprocedure::text, p.prosrc
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = current_schema()
           AND NOT EXISTS (SELECT 1 FROM pg_depend d
                            WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid
                              AND d.deptype = 'e')
        """,
        rs -> functions.put(rs.getString(1), Objects.toString(rs.getString(2), "")));
    return functions;
  }

  /** Every foreign key, with the logical tables on both ends. */
  private List<ForeignKey> foreignKeys() throws SQLException {
    List<ForeignKey> foreignKeys = new ArrayList<>();
    query(
        """
        SELECT con.conname,
               COALESCE(fp.relname, f.relname),
               COALESCE(tp.relname, t.relname)
          FROM pg_constraint con
          JOIN pg_class f ON f.oid = con.conrelid
          JOIN pg_class t ON t.oid = con.confrelid
          JOIN pg_namespace n ON n.oid = f.relnamespace
          LEFT JOIN pg_inherits fi ON fi.inhrelid = f.oid
          LEFT JOIN pg_class fp ON fp.oid = fi.inhparent
          LEFT JOIN pg_inherits ti ON ti.inhrelid = t.oid
          LEFT JOIN pg_class tp ON tp.oid = ti.inhparent
         WHERE n.nspname = current_schema() AND con.contype = 'f' AND con.conparentid = 0
        """,
        rs -> foreignKeys.add(new ForeignKey(rs.getString(1), rs.getString(2), rs.getString(3))));
    return foreignKeys;
  }

  @FunctionalInterface
  private interface RowConsumer {
    void accept(ResultSet rs) throws SQLException;
  }

  private void query(String sql, RowConsumer consumer) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      while (rs.next()) {
        consumer.accept(rs);
      }
    }
  }

  private record Snapshot(
      Map<String, TableState> tables,
      Map<String, String> triggers,
      Map<String, String> functions) {}

  private record TableState(String logicalTable, String fingerprint) {}

  private record ForeignKey(String name, String table, String referencedTable) {}
}
