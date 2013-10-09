/*
 * The MIT License
 *
 * Copyright (c) 2012-2013, Ninja Squad
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.ninja_squad.dbsetup.operation;

import com.ninja_squad.dbsetup.bind.Binder;
import com.ninja_squad.dbsetup.bind.BinderConfiguration;
import com.ninja_squad.dbsetup.bind.Binders;
import com.ninja_squad.dbsetup.generator.ValueGenerator;
import com.ninja_squad.dbsetup.generator.ValueGenerators;
import com.ninja_squad.dbsetup.util.Preconditions;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operation which inserts one or several rows into a table. Example usage:
 * <pre>
 *   Insert insert =
 *       Insert.into("CLIENT")
 *             .columns("CLIENT_ID", "FIRST_NAME", "LAST_NAME", "DATE_OF_BIRTH", "CLIENT_TYPE")
 *             .values(1L, "John", "Doe", "1975-07-19", ClientType.NORMAL)
 *             .values(2L, "Jack", "Smith", "1969-08-22", ClientType.HIGH_PRIORITY)
 *             .withDefaultValue("DELETED", false)
 *             .withDefaultValue("VERSION", 1)
 *             .withBinder(new ClientTypeBinder(), "CLIENT_TYPE")
 *             .build();
 * </pre>
 *
 * The above operation will insert two rows inside the CLIENT table. For each row, the column DELETED will be set to
 * <code>false</code> and the column VERSION will be set to 1. For the column CLIENT_TYPE, instead of using the
 * {@link Binder} associated to the type of the column found in the metadata of the table, a custom binder will be used.
 * <p>
 * Instead of specifying values as an ordered sequence which must match the sequence of column names, some might prefer
 * passing a map of column/value associations. This makes things more verbose, but can be more readable in some cases,
 * when the number of columns is high. This also allows not specifying any value for columns that must stay null.
 * The map can be constructed like any other map and passed to the builder, or it can be added using a fluent builder.
 * The following snippet:
 *
 * <pre>
 *   Insert insert =
 *       Insert.into("CLIENT")
 *             .columns("CLIENT_ID", "FIRST_NAME", "LAST_NAME", "DATE_OF_BIRTH", "CLIENT_TYPE")
 *             .row().column("CLIENT_ID", 1L)
 *                   .column("FIRST_NAME", "John")
 *                   .column("LAST_NAME", "Doe")
 *                   .column("DATE_OF_BIRTH", "1975-07-19")
 *                   .column("CLIENT_TYPE", ClientType.NORMAL)
 *                   .build()
 *             .row().column("CLIENT_ID", 2L)
 *                   .column("FIRST_NAME", "Jack")
 *                   .column("LAST_NAME", "Smith")
 *                   .column("CLIENT_TYPE", ClientType.HIGH_PRIORITY)
 *                   .build() // null date of birth, because it's not in the map
 *             .build();
 * </pre>
 *
 * is thus equivalent to:
 *
 * <pre>
 *   Map&lt;String, Object&gt; johnDoe = new HashMap&lt;String, Object&gt;();
 *   johnDoe.put("CLIENT_ID", 1L);
 *   johnDoe.put("FIRST_NAME", "John");
 *   johnDoe.put("LAST_NAME", "Doe");
 *   johnDoe.put("DATE_OF_BIRTH", "1975-07-19");
 *   johnDoe.put("CLIENT_TYPE", ClientType.NORMAL);
 *
 *   Map&lt;String, Object&gt; jackSmith = new HashMap&lt;String, Object&gt;();
 *   jackSmith.put("CLIENT_ID", 2L);
 *   jackSmith.put("FIRST_NAME", "Jack");
 *   jackSmith.put("LAST_NAME", "Smith");
 *   jackSmith.put("CLIENT_TYPE", ClientType.HIGH_PRIORITY); // null date of birth, because it's not in the map
 *
 *   Insert insert =
 *       Insert.into("CLIENT")
 *             .columns("CLIENT_ID", "FIRST_NAME", "LAST_NAME", "DATE_OF_BIRTH", "CLIENT_TYPE")
 *             .values(johnDoe)
 *             .values(jackSmith)
 *             .build();
 * </pre>
 *
 * @author JB Nizet
 */
@Immutable
public final class Insert implements Operation {
    private final String table;
    private final List<String> columnNames;
    private final Map<String, List<Object>> generatedValues;
    private final List<List<?>> rows;
    private final boolean metadataUsed;

    private final Map<String, Binder> binders;

    private Insert(Builder builder) {
        this.table = builder.table;
        this.columnNames = builder.columnNames;
        this.rows = builder.rows;
        this.generatedValues = generateValues(builder.valueGenerators, rows.size());
        this.binders = builder.binders;
        this.metadataUsed = builder.metadataUsed;
    }

    private Map<String, List<Object>> generateValues(Map<String, ValueGenerator<?>> valueGenerators,
                                                      int count) {
        Map<String, List<Object>> result = new LinkedHashMap<String, List<Object>>();
        for (Map.Entry<String, ValueGenerator<?>> entry : valueGenerators.entrySet()) {
            result.put(entry.getKey(), generateValues(entry.getValue(), count));
        }
        return result;
    }

    private List<Object> generateValues(ValueGenerator<?> valueGenerator, int count) {
        List<Object> result = new ArrayList<Object>(count);
        for (int i = 0; i < count; i++) {
            result.add(valueGenerator.nextValue());
        }
        return result;
    }

    /**
     * Inserts the values and generated values in the table. Unless <code>useMetadata</code> has been set to
     * <code>false</code>, the given configuration is used to get the appropriate binder. Nevertheless, if a binder
     * has explicitely been associated to a given column, this binder will always be used for this column.
     */
    @edu.umd.cs.findbugs.annotations.SuppressWarnings(
        value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
        justification = "The point here is precisely to compose a SQL String from column names coming from the user")
    @Override
    public void execute(Connection connection, BinderConfiguration configuration) throws SQLException {
        StringBuilder sql = new StringBuilder("insert into ").append(table).append(" (");

        List<String> allColumnNames = new ArrayList<String>(columnNames);
        allColumnNames.addAll(generatedValues.keySet());

        for (Iterator<String> it = allColumnNames.iterator(); it.hasNext(); ) {
            String columnName = it.next();
            sql.append(columnName);
            if (it.hasNext()) {
                sql.append(", ");
            }
        }
        sql.append(") values (");
        for (Iterator<String> it = allColumnNames.iterator(); it.hasNext(); ) {
            it.next();
            sql.append("?");
            if (it.hasNext()) {
                sql.append(", ");
            }
        }
        sql.append(")");

        PreparedStatement stmt = connection.prepareStatement(sql.toString());

        try {
            Map<String, Binder> metadataBinders = new HashMap<String, Binder>();
            if (metadataUsed) {
                initializeBinders(stmt, allColumnNames, configuration, metadataBinders);
            }

            int rowIndex = 0;
            for (List<?> row : rows) {
                int i = 0;
                for (Object value : row) {
                    String columnName = columnNames.get(i);
                    Binder binder = getBinder(columnName, metadataBinders);
                    binder.bind(stmt, i + 1, value);
                    i++;
                }
                for (Map.Entry<String, List<Object>> entry : generatedValues.entrySet()) {
                    String columnName = entry.getKey();
                    List<Object> rowValues = entry.getValue();
                    Binder binder = getBinder(columnName, metadataBinders);
                    binder.bind(stmt, i + 1, rowValues.get(rowIndex));
                    i++;
                }

                stmt.executeUpdate();
                rowIndex++;
            }
        }
        finally {
            stmt.close();
        }
    }

    private void initializeBinders(PreparedStatement stmt,
                                   List<String> allColumnNames,
                                   BinderConfiguration configuration,
                                   Map<String, Binder> metadataBinders) throws SQLException {
        ParameterMetaData metadata = stmt.getParameterMetaData();
        int i = 1;
        for (String columnName : allColumnNames) {
            if (!this.binders.containsKey(columnName)) {
                metadataBinders.put(columnName, configuration.getBinder(metadata, i));
            }
            i++;
        }
    }

    private Binder getBinder(String columnName, Map<String, Binder> metadataBinders) {
        Binder result = binders.get(columnName);
        if (result == null) {
            result = metadataBinders.get(columnName);
        }
        if (result == null) {
            result = Binders.defaultBinder();
        }
        return result;
    }

    @Override
    public String toString() {
        return "insert into "
               + table
               + " [columns="
               + columnNames
               + ", generatedValues="
               + generatedValues
               + ", rows="
               + rows
               + ", metadataUsed="
               + metadataUsed
               + ", binders="
               + binders
               + "]";

    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + binders.hashCode();
        result = prime * result + columnNames.hashCode();
        result = prime * result + generatedValues.hashCode();
        result = prime * result + Boolean.valueOf(metadataUsed).hashCode();
        result = prime * result + rows.hashCode();
        result = prime * result + table.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        Insert other = (Insert) obj;

        return binders.equals(other.binders)
               && columnNames.equals(other.columnNames)
               && generatedValues.equals(other.generatedValues)
               && metadataUsed == other.metadataUsed
               && rows.equals(other.rows)
               && table.equals(other.table);
    }

    /**
     * Creates a new Builder instance, in order to build an Insert operation into the given table
     * @param table the name of the table to insert into
     * @return the created Builder
     */
    public static Builder into(@Nonnull String table) {
        Preconditions.checkNotNull(table, "table may not be null");
        return new Builder(table);
    }

    /**
     * A builder used to create an Insert operation. Such a builder may only be used once. Once it has built its Insert
     * operation, all its methods throw an {@link IllegalStateException}.
     * @see Insert
     * @see Insert#into(String)
     * @author JB Nizet
     */
    public static final class Builder {
        private final String table;
        private final List<String> columnNames = new ArrayList<String>();
        private final Map<String, ValueGenerator<?>> valueGenerators = new LinkedHashMap<String, ValueGenerator<?>>();
        private final List<List<?>> rows = new ArrayList<List<?>>();

        private boolean metadataUsed = true;
        private final Map<String, Binder> binders = new HashMap<String, Binder>();

        private boolean built;

        private Builder(String table) {
            this.table = table;
        }

        /**
         * Specifies the list of columns into which values will be inserted. The values must the be specified, after,
         * using the {@link #values(Object...)} method, or with the {@link #values(java.util.Map)} method, or by adding
         * a row with named columns fluently using {@link #row()}.
         * @param columns the names of the columns to insert into.
         * @return this Builder instance, for chaining.
         * @throws IllegalStateException if the Insert has already been built, or if this method has already been
         * called, or if one of the given columns is also specified as one of the generated value columns.
         */
        public Builder columns(@Nonnull String... columns) {
            Preconditions.checkState(!built, "The insert has already been built");
            Preconditions.checkState(columnNames.isEmpty(), "columns have already been specified");
            for (String column : columns) {
                Preconditions.checkNotNull(column, "column may not be null");
                Preconditions.checkState(!valueGenerators.containsKey(column),
                                         "column "
                                             + column
                                             + " has already been specified as generated value column");
            }
            columnNames.addAll(Arrays.asList(columns));
            return this;
        }

        /**
         * Adds a row of values to insert.
         * @param values the values to insert.
         * @return this Builder instance, for chaining.
         * @throws IllegalStateException if the Insert has already been built, or if the number of values doesn't match
         * the number of columns.
         */
        public Builder values(@Nonnull Object... values) {
            Preconditions.checkState(!built, "The insert has already been built");
            Preconditions.checkArgument(values.length == columnNames.size(),
                                        "The number of values doesn't match the number of columns");
            rows.add(new ArrayList<Object>(Arrays.asList(values)));
            return this;
        }

        /**
         * Starts building a new row with named columns to insert.
         * @return a {@link RowBuilder} instance, which, when built, will add a row to this insert builder.
         * @throws IllegalStateException if the Insert has already been built.
         * @see RowBuilder
         */
        public RowBuilder row() {
            Preconditions.checkState(!built, "The insert has already been built");
            return new RowBuilder(this);
        }

        /**
         * Adds a row to this builder.
         * @param row the row to add. The keys of the map are the column names, which must match with
         * the column names specified in the call to {@link #columns(String...)}. If a column name is not present
         * in the map, null is inserted for this column.
         * @return this Builder instance, for chaining.
         * @throws IllegalStateException if the Insert has already been built.
         * @throws IllegalArgumentException if a column name of the map doesn't match with any of the column names
         * specified with {@link #columns(String...)}
         */
        public Builder values(@Nonnull Map<String, ?> row) {
            Preconditions.checkState(!built, "The insert has already been built");
            Preconditions.checkNotNull(row, "The row may not be null");

            Set<String> rowColumnNames = new HashSet<String>(row.keySet());
            rowColumnNames.removeAll(columnNames);
            if (!rowColumnNames.isEmpty()) {
                throw new IllegalArgumentException("The following columns of the row don't match with any column name: "
                                                       + rowColumnNames);
            }

            return addRow(row);
        }

        private Builder addRow(@Nonnull Map<String, ?> row) {
            Preconditions.checkState(!built, "The insert has already been built");

            List<Object> values = new ArrayList<Object>(columnNames.size());
            for (String columnName : columnNames) {
                values.add(row.get(columnName));
            }
            rows.add(values);
            return this;
        }

        /**
         * Associates a Binder to one or several columns.
         * @param binder the binder to use, regardless of the metadata, for the given columns
         * @param columns the name of the columns to associate with the given Binder
         * @return this Builder instance, for chaining.
         * @throws IllegalStateException if the Insert has already been built,
         * @throws IllegalArgumentException if any of the given columns is not
         * part of the columns or "generated value" columns.
         */
        public Builder withBinder(@Nonnull Binder binder, @Nonnull String... columns) {
            Preconditions.checkState(!built, "The insert has already been built");
            Preconditions.checkNotNull(binder, "binder may not be null");
            for (String columnName : columns) {
                Preconditions.checkArgument(this.columnNames.contains(columnName)
                                            || this.valueGenerators.containsKey(columnName),
                                            "column "
                                                + columnName
                                                + " is not one of the registered column names");
                binders.put(columnName, binder);
            }
            return this;
        }

        /**
         * Specifies a default value to be inserted in a column for all the rows inserted by the Insert operation.
         * Calling this method is equivalent to calling
         * <code>withGeneratedValue(column, ValueGenerators.constant(value))</code>
         * @param column the name of the column
         * @param value the default value to insert into the column
         * @return this Builder instance, for chaining.
         * @throws IllegalStateException if the Insert has already been built, or if the given column is part
         * of the columns to insert.
         */
        public Builder withDefaultValue(@Nonnull String column, Object value) {
            return withGeneratedValue(column, ValueGenerators.constant(value));
        }

        /**
         * Allows the given column to be populated by a value generator, which will be called for every row of the
         * Insert operation being built.
         * @param column the name of the column
         * @param valueGenerator the generator generating values for the given column of every row
         * @return this Builder instance, for chaining.
         * @throws IllegalStateException if the Insert has already been built, or if the given column is part
         * of the columns to insert.
         */
        public Builder withGeneratedValue(@Nonnull String column, @Nonnull ValueGenerator<?> valueGenerator) {
            Preconditions.checkState(!built, "The insert has already been built");
            Preconditions.checkNotNull(column, "column may not be null");
            Preconditions.checkNotNull(valueGenerator, "valueGenerator may not be null");
            Preconditions.checkArgument(!columnNames.contains(column),
                                        "column "
                                        + column
                                        + " is already listed in the list of column names");
            valueGenerators.put(column, valueGenerator);
            return this;
        }

        /**
         * Determines if the metadata must be used to get the appropriate binder for each inserted column (except
         * the ones which have been associated explicitely with a Binder). The default is <code>true</code>. The insert
         * can be faster if set to <code>false</code>, but in this case, the {@link Binders#defaultBinder() default
         * binder} will be used for all the columns (except the ones which have been associated explicitely with a
         * Binder).
         * @return this Builder instance, for chaining.
         * @throws IllegalStateException if the Insert has already been built.
         */
        public Builder useMetadata(boolean useMetadata) {
            Preconditions.checkState(!built, "The insert has already been built");
            this.metadataUsed = useMetadata;
            return this;
        }

        /**
         * Builds the Insert operation.
         * @return the created Insert operation.
         * @throws IllegalStateException if the Insert has already been built, or if no column and no generated value
         * column has been specified.
         */
        public Insert build() {
            Preconditions.checkState(!built, "The insert has already been built");
            Preconditions.checkState(!this.columnNames.isEmpty() || !this.valueGenerators.isEmpty(),
                                     "no column and no generated value column has been specified");
            built = true;
            return new Insert(this);
        }
    }

    /**
     * A row builder, constructed with {@link com.ninja_squad.dbsetup.operation.Insert.Builder#row()}. This builder
     * allows adding a row with named columns to an Insert:
     *
     * <pre>
     *   Insert insert =
     *       Insert.into("CLIENT")
     *             .columns("CLIENT_ID", "FIRST_NAME", "LAST_NAME", "DATE_OF_BIRTH", "CLIENT_TYPE")
     *             .row().column("CLIENT_ID", 1L)
     *                   .column("FIRST_NAME", "John")
     *                   .column("LAST_NAME", "Doe")
     *                   .column("DATE_OF_BIRTH", "1975-07-19")
     *                   .column("CLIENT_TYPE", ClientType.NORMAL)
     *                   .build()
     *             .row().column("CLIENT_ID", 2L)
     *                   .column("FIRST_NAME", "Jack")
     *                   .column("LAST_NAME", "Smith")
     *                   .column("DATE_OF_BIRTH", "1969-08-22")
     *                   .column("CLIENT_TYPE", ClientType.HIGH_PRIORITY)
     *                   .build()
     *             .build();
     * </pre>
     */
    public static final class RowBuilder {
        private final Builder builder;
        private final Map<String, Object> row;

        private RowBuilder(Builder builder) {
            this.builder = builder;
            this.row = new HashMap<String, Object>();
        }

        /**
         * Adds a new named column to the row. If a previous value has already been added for the same column, it's
         * replaced by this new value.
         * @param name the name of the column, which must match with a column name defined in the Insert Builder
         * @param value the value of the column for the constructed row
         * @return this builder, for chaining
         * @throws IllegalArgumentException if the given name is not the name of one of the columns to insert
         */
        public RowBuilder column(@Nonnull String name, Object value) {
            Preconditions.checkNotNull(name, "the column name may not be null");
            Preconditions.checkArgument(builder.columnNames.contains(name),
                                        "column " + name + " is not one of the registered column names");
            row.put(name, value);
            return this;
        }

        /**
         * Adds the row to the Insert Builder and returns it, for chaining.
         * @return the Insert Builder
         */
        public Builder build() {
            return builder.addRow(row);
        }
    }
}
