package io.sustc.service.impl.importer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The indexes, primary/unique constraints, foreign keys and {@code derived_*} triggers of a set of
 * tables, read from the catalog so that they can be dropped before a bulk load and rebuilt
 * afterwards.
 *
 * <p>Loading into bare heaps and building each index once with a sort is several times faster
 * than maintaining the indexes row by row, and foreign keys are validated with one anti-join
 * per constraint instead of one lookup per row. The triggers that maintain derived tables are
 * dropped because the importer writes those tables itself.
 *
 * <p>The definitions come from the catalog, so index, constraint and trigger definitions are not
 * repeated here (the table list and the COPY column lists are, in {@link BulkImporter}). Supported
 * are plain and unique indexes, primary key and unique constraints (also {@code DEFERRABLE} or
 * {@code INITIALLY DEFERRED}), foreign keys (also {@code NOT VALID}, which stay unvalidated), and
 * {@code derived_*} triggers. {@link #capture} refuses anything it could not recreate exactly:
 * an index that backs another kind of constraint (an exclusion constraint) and comments on the
 * deferred objects.
 */
final class DeferredSchemaObjects {

    /**
     * An index; {@code constraintType} is {@code p} or {@code u} if it backs a constraint, and the
     * two flags are that constraint's.
     */
    record Index(
            String table,
            String name,
            String definition,
            String constraintType,
            boolean deferrable,
            boolean initiallyDeferred) {

        boolean backsConstraint() {
            return constraintType != null;
        }
    }

    /** A foreign key; {@code definition} never ends in NOT VALID, {@code validated} says that. */
    record ForeignKey(String table, String name, String definition, boolean validated) {}

    record Trigger(String table, String name, String definition) {}

    private static final String INDEXES =
            """
            select t.relname as table_name, i.relname as index_name,
                   pg_get_indexdef(x.indexrelid) as definition, c.contype as constraint_type,
                   coalesce(c.condeferrable, false) as deferrable, coalesce(c.condeferred, false) as deferred
            from pg_index x
            join pg_class i on i.oid = x.indexrelid
            join pg_class t on t.oid = x.indrelid
            left join pg_constraint c on c.conindid = x.indexrelid and c.conrelid = x.indrelid
                                     and c.contype in ('p', 'u')
            where t.relnamespace = current_schema()::regnamespace and t.relname = any (?)
            order by t.relname, i.relname
            """;

    private static final String FOREIGN_KEYS =
            """
            select t.relname as table_name, c.conname as name, pg_get_constraintdef(c.oid) as definition,
                   c.convalidated as validated
            from pg_constraint c
            join pg_class t on t.oid = c.conrelid
            join pg_class r on r.oid = c.confrelid
            where c.contype = 'f' and t.relnamespace = current_schema()::regnamespace
              and (t.relname = any (?) or r.relname = any (?))
            order by t.relname, c.conname
            """;

    /**
     * What {@link #capture} cannot drop and recreate exactly: indexes that back constraints other
     * than primary key / unique, and comments on indexes, constraints and derived triggers.
     */
    private static final String UNSUPPORTED =
            """
            select format('index %I backs a constraint of type %s', i.relname, c.contype)
            from pg_constraint c
            join pg_class i on i.oid = c.conindid
            join pg_class t on t.oid = c.conrelid
            where c.contype not in ('p', 'u', 'f')
              and t.relnamespace = current_schema()::regnamespace and t.relname = any (?)
            union all
            select format('index %I has a comment', i.relname)
            from pg_index x
            join pg_class i on i.oid = x.indexrelid
            join pg_class t on t.oid = x.indrelid
            where obj_description(i.oid, 'pg_class') is not null
              and t.relnamespace = current_schema()::regnamespace and t.relname = any (?)
            union all
            select format('constraint %I on %I has a comment', c.conname, t.relname)
            from pg_constraint c
            join pg_class t on t.oid = c.conrelid
            left join pg_class r on r.oid = c.confrelid
            where c.contype in ('p', 'u', 'f') and obj_description(c.oid, 'pg_constraint') is not null
              and t.relnamespace = current_schema()::regnamespace
              and (t.relname = any (?) or r.relname = any (?))
            union all
            select format('trigger %I on %I has a comment', g.tgname, t.relname)
            from pg_trigger g
            join pg_class t on t.oid = g.tgrelid
            where not g.tgisinternal and g.tgname like 'derived\\_%' and obj_description(g.oid, 'pg_trigger') is not null
              and t.relnamespace = current_schema()::regnamespace and t.relname = any (?)
            """;

    /** pg_get_constraintdef appends this to the definition of a foreign key that is NOT VALID. */
    private static final String NOT_VALID_SUFFIX = " NOT VALID";

    /** Triggers that maintain derived tables; see the "Derived data" section of build.sql. */
    private static final String DERIVED_TRIGGERS =
            """
            select t.relname as table_name, g.tgname as name, pg_get_triggerdef(g.oid) as definition
            from pg_trigger g
            join pg_class t on t.oid = g.tgrelid
            where not g.tgisinternal and g.tgname like 'derived\\_%'
              and t.relnamespace = current_schema()::regnamespace and t.relname = any (?)
            order by t.relname, g.tgname
            """;

    /** The foreign keys of the current schema that are NOT VALID, as {@code table.name}. */
    private static final String NOT_VALIDATED_FOREIGN_KEYS =
            """
            select t.relname || '.' || c.conname
            from pg_constraint c
            join pg_class t on t.oid = c.conrelid
            where c.contype = 'f' and not c.convalidated and c.connamespace = current_schema()::regnamespace
            """;

    private final List<Index> indexes;
    private final List<ForeignKey> foreignKeys;
    private final List<Trigger> triggers;

    private DeferredSchemaObjects(List<Index> indexes, List<ForeignKey> foreignKeys, List<Trigger> triggers) {
        this.indexes = List.copyOf(indexes);
        this.foreignKeys = List.copyOf(foreignKeys);
        this.triggers = List.copyOf(triggers);
    }

    /**
     * Reads the objects from the catalog without changing anything.
     *
     * @throws ImportException if one of them could not be recreated exactly
     */
    static DeferredSchemaObjects capture(JdbcTemplate jdbc, List<String> tables) {
        String[] names = tables.toArray(String[]::new);
        List<String> unsupported = jdbc.queryForList(UNSUPPORTED, String.class, names, names, names, names, names);
        if (!unsupported.isEmpty()) {
            throw new ImportException("The importer cannot drop and recreate these schema objects exactly: "
                    + String.join("; ", unsupported));
        }
        List<Index> indexes = jdbc.query(
                INDEXES,
                (rs, i) -> new Index(
                        rs.getString("table_name"),
                        rs.getString("index_name"),
                        rs.getString("definition"),
                        rs.getString("constraint_type"),
                        rs.getBoolean("deferrable"),
                        rs.getBoolean("deferred")),
                (Object) names);
        List<ForeignKey> foreignKeys = jdbc.query(
                FOREIGN_KEYS,
                (rs, i) -> {
                    String definition = rs.getString("definition");
                    if (definition.endsWith(NOT_VALID_SUFFIX)) {
                        definition = definition.substring(0, definition.length() - NOT_VALID_SUFFIX.length());
                    }
                    return new ForeignKey(
                            rs.getString("table_name"), rs.getString("name"), definition, rs.getBoolean("validated"));
                },
                names,
                names);
        List<Trigger> triggers = jdbc.query(
                DERIVED_TRIGGERS,
                (rs, i) -> new Trigger(rs.getString("table_name"), rs.getString("name"), rs.getString("definition")),
                (Object) names);
        return new DeferredSchemaObjects(indexes, foreignKeys, triggers);
    }

    List<Index> indexes() {
        return indexes;
    }

    /** Drops the triggers and foreign keys first, then constraints, then plain indexes. */
    void dropAll(JdbcTemplate jdbc) {
        List<String> statements = new ArrayList<>();
        for (Trigger trigger : triggers) {
            statements.add("drop trigger " + quote(trigger.name()) + " on " + quote(trigger.table()));
        }
        for (ForeignKey fk : foreignKeys) {
            statements.add("alter table " + quote(fk.table()) + " drop constraint " + quote(fk.name()));
        }
        for (Index index : indexes) {
            if (index.backsConstraint()) {
                statements.add("alter table " + quote(index.table()) + " drop constraint " + quote(index.name()));
            }
        }
        for (Index index : indexes) {
            if (!index.backsConstraint()) {
                statements.add("drop index " + quote(index.name()));
            }
        }
        if (!statements.isEmpty()) {
            jdbc.batchUpdate(statements.toArray(String[]::new));
        }
    }

    /**
     * Turns the rebuilt unique indexes back into primary key / unique constraints. This only
     * updates the catalog; the indexes themselves are rebuilt with their {@code CREATE INDEX}
     * definitions, which can run concurrently on the same table.
     */
    void attachConstraints(JdbcTemplate jdbc) {
        String[] statements = indexes.stream()
                .filter(Index::backsConstraint)
                .map(DeferredSchemaObjects::attachStatement)
                .toArray(String[]::new);
        if (statements.length > 0) {
            jdbc.batchUpdate(statements);
        }
    }

    /**
     * Adds the foreign keys as {@code NOT VALID}, which is instant. Validation happens separately
     * (see {@link #validationStatementsByTable}) because it can run concurrently across tables.
     */
    void addForeignKeysNotValid(JdbcTemplate jdbc) {
        if (foreignKeys.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(foreignKeys.stream().map(fk -> addStatement(fk, false)).toArray(String[]::new));
    }

    /** Recreates the dropped triggers. */
    void createTriggers(JdbcTemplate jdbc) {
        if (!triggers.isEmpty()) {
            jdbc.batchUpdate(triggers.stream().map(Trigger::definition).toArray(String[]::new));
        }
    }

    /**
     * {@code VALIDATE CONSTRAINT} statements grouped by table, for the foreign keys that were valid
     * before the import: validations of one table conflict on its lock, so each group runs
     * sequentially while different tables run in parallel.
     */
    Map<String, List<String>> validationStatementsByTable() {
        Map<String, List<String>> byTable = new LinkedHashMap<>();
        for (ForeignKey fk : foreignKeys) {
            if (fk.validated()) {
                byTable.computeIfAbsent(fk.table(), t -> new ArrayList<>()).add(validateStatement(fk));
            }
        }
        return byTable;
    }

    /**
     * Recreates whatever is missing and validates the captured foreign keys that were valid before
     * and are left {@code NOT VALID}, for example after a failed import. The triggers are recreated
     * even if a validation fails. Safe to call repeatedly; meant for empty tables, where every
     * build and validation is instant.
     */
    void restoreMissing(JdbcTemplate jdbc) {
        Set<String> existingIndexes = new HashSet<>(jdbc.queryForList(
                "select indexname from pg_indexes where schemaname = current_schema()", String.class));
        Set<String> existingConstraints = new HashSet<>(jdbc.queryForList(
                "select t.relname || '.' || c.conname from pg_constraint c join pg_class t on t.oid = c.conrelid"
                        + " where c.connamespace = current_schema()::regnamespace",
                String.class));
        for (Index index : indexes) {
            if (!existingIndexes.contains(index.name())) {
                jdbc.execute(index.definition());
            }
        }
        for (Index index : indexes) {
            if (index.backsConstraint() && !existingConstraints.contains(index.table() + "." + index.name())) {
                jdbc.execute(attachStatement(index));
            }
        }
        for (ForeignKey fk : foreignKeys) {
            if (!existingConstraints.contains(fk.table() + "." + fk.name())) {
                jdbc.execute(addStatement(fk, fk.validated()));
            }
        }
        try {
            // A failure during validation leaves the foreign keys that were added NOT VALID behind.
            Set<String> notValidated = new HashSet<>(jdbc.queryForList(NOT_VALIDATED_FOREIGN_KEYS, String.class));
            for (ForeignKey fk : foreignKeys) {
                if (fk.validated() && notValidated.contains(fk.table() + "." + fk.name())) {
                    jdbc.execute(validateStatement(fk));
                }
            }
        } finally {
            Set<String> existingTriggers = new HashSet<>(jdbc.queryForList(
                    "select t.relname || '.' || g.tgname from pg_trigger g join pg_class t on t.oid = g.tgrelid"
                            + " where not g.tgisinternal and t.relnamespace = current_schema()::regnamespace",
                    String.class));
            for (Trigger trigger : triggers) {
                if (!existingTriggers.contains(trigger.table() + "." + trigger.name())) {
                    jdbc.execute(trigger.definition());
                }
            }
        }
    }

    /** Re-attaches a rebuilt unique index as the constraint it backed, with the same deferrability. */
    private static String attachStatement(Index index) {
        String kind = "p".equals(index.constraintType()) ? "primary key" : "unique";
        return "alter table " + quote(index.table()) + " add constraint " + quote(index.name()) + " " + kind
                + " using index " + quote(index.name())
                + (index.deferrable() ? " deferrable" : "")
                + (index.initiallyDeferred() ? " initially deferred" : "");
    }

    private static String addStatement(ForeignKey fk, boolean validate) {
        return "alter table " + quote(fk.table()) + " add constraint " + quote(fk.name()) + " " + fk.definition()
                + (validate ? "" : " not valid");
    }

    private static String validateStatement(ForeignKey fk) {
        return "alter table " + quote(fk.table()) + " validate constraint " + quote(fk.name());
    }

    static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
