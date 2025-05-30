/*
 * This file is generated by jOOQ.
 */
package stroom.analytics.impl.db.jooq.tables;


import stroom.analytics.impl.db.jooq.Indexes;
import stroom.analytics.impl.db.jooq.Keys;
import stroom.analytics.impl.db.jooq.Stroom;
import stroom.analytics.impl.db.jooq.tables.records.ExecutionScheduleRecord;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Function12;
import org.jooq.Identity;
import org.jooq.Index;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row12;
import org.jooq.Schema;
import org.jooq.SelectField;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class ExecutionSchedule extends TableImpl<ExecutionScheduleRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>stroom.execution_schedule</code>
     */
    public static final ExecutionSchedule EXECUTION_SCHEDULE = new ExecutionSchedule();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<ExecutionScheduleRecord> getRecordType() {
        return ExecutionScheduleRecord.class;
    }

    /**
     * The column <code>stroom.execution_schedule.id</code>.
     */
    public final TableField<ExecutionScheduleRecord, Integer> ID = createField(DSL.name("id"), SQLDataType.INTEGER.nullable(false).identity(true), this, "");

    /**
     * The column <code>stroom.execution_schedule.name</code>.
     */
    public final TableField<ExecutionScheduleRecord, String> NAME = createField(DSL.name("name"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.execution_schedule.enabled</code>.
     */
    public final TableField<ExecutionScheduleRecord, Boolean> ENABLED = createField(DSL.name("enabled"), SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline("0", SQLDataType.BOOLEAN)), this, "");

    /**
     * The column <code>stroom.execution_schedule.node_name</code>.
     */
    public final TableField<ExecutionScheduleRecord, String> NODE_NAME = createField(DSL.name("node_name"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.execution_schedule.schedule_type</code>.
     */
    public final TableField<ExecutionScheduleRecord, String> SCHEDULE_TYPE = createField(DSL.name("schedule_type"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.execution_schedule.expression</code>.
     */
    public final TableField<ExecutionScheduleRecord, String> EXPRESSION = createField(DSL.name("expression"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.execution_schedule.contiguous</code>.
     */
    public final TableField<ExecutionScheduleRecord, Boolean> CONTIGUOUS = createField(DSL.name("contiguous"), SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline("0", SQLDataType.BOOLEAN)), this, "");

    /**
     * The column <code>stroom.execution_schedule.start_time_ms</code>.
     */
    public final TableField<ExecutionScheduleRecord, Long> START_TIME_MS = createField(DSL.name("start_time_ms"), SQLDataType.BIGINT, this, "");

    /**
     * The column <code>stroom.execution_schedule.end_time_ms</code>.
     */
    public final TableField<ExecutionScheduleRecord, Long> END_TIME_MS = createField(DSL.name("end_time_ms"), SQLDataType.BIGINT, this, "");

    /**
     * The column <code>stroom.execution_schedule.doc_type</code>.
     */
    public final TableField<ExecutionScheduleRecord, String> DOC_TYPE = createField(DSL.name("doc_type"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.execution_schedule.doc_uuid</code>.
     */
    public final TableField<ExecutionScheduleRecord, String> DOC_UUID = createField(DSL.name("doc_uuid"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.execution_schedule.run_as_user_uuid</code>.
     */
    public final TableField<ExecutionScheduleRecord, String> RUN_AS_USER_UUID = createField(DSL.name("run_as_user_uuid"), SQLDataType.VARCHAR(255), this, "");

    private ExecutionSchedule(Name alias, Table<ExecutionScheduleRecord> aliased) {
        this(alias, aliased, null);
    }

    private ExecutionSchedule(Name alias, Table<ExecutionScheduleRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>stroom.execution_schedule</code> table reference
     */
    public ExecutionSchedule(String alias) {
        this(DSL.name(alias), EXECUTION_SCHEDULE);
    }

    /**
     * Create an aliased <code>stroom.execution_schedule</code> table reference
     */
    public ExecutionSchedule(Name alias) {
        this(alias, EXECUTION_SCHEDULE);
    }

    /**
     * Create a <code>stroom.execution_schedule</code> table reference
     */
    public ExecutionSchedule() {
        this(DSL.name("execution_schedule"), null);
    }

    public <O extends Record> ExecutionSchedule(Table<O> child, ForeignKey<O, ExecutionScheduleRecord> key) {
        super(child, key, EXECUTION_SCHEDULE);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Stroom.STROOM;
    }

    @Override
    public List<Index> getIndexes() {
        return Arrays.asList(Indexes.EXECUTION_SCHEDULE_EXECUTION_SCHEDULE_DOC_IDX, Indexes.EXECUTION_SCHEDULE_EXECUTION_SCHEDULE_ENABLED_IDX);
    }

    @Override
    public Identity<ExecutionScheduleRecord, Integer> getIdentity() {
        return (Identity<ExecutionScheduleRecord, Integer>) super.getIdentity();
    }

    @Override
    public UniqueKey<ExecutionScheduleRecord> getPrimaryKey() {
        return Keys.KEY_EXECUTION_SCHEDULE_PRIMARY;
    }

    @Override
    public ExecutionSchedule as(String alias) {
        return new ExecutionSchedule(DSL.name(alias), this);
    }

    @Override
    public ExecutionSchedule as(Name alias) {
        return new ExecutionSchedule(alias, this);
    }

    @Override
    public ExecutionSchedule as(Table<?> alias) {
        return new ExecutionSchedule(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public ExecutionSchedule rename(String name) {
        return new ExecutionSchedule(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public ExecutionSchedule rename(Name name) {
        return new ExecutionSchedule(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public ExecutionSchedule rename(Table<?> name) {
        return new ExecutionSchedule(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row12 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row12<Integer, String, Boolean, String, String, String, Boolean, Long, Long, String, String, String> fieldsRow() {
        return (Row12) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function12<? super Integer, ? super String, ? super Boolean, ? super String, ? super String, ? super String, ? super Boolean, ? super Long, ? super Long, ? super String, ? super String, ? super String, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function12<? super Integer, ? super String, ? super Boolean, ? super String, ? super String, ? super String, ? super Boolean, ? super Long, ? super Long, ? super String, ? super String, ? super String, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
