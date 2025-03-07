/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.sqlexec;

import org.apache.flink.sql.parser.ddl.SqlCreateTable;
import org.apache.flink.sql.parser.dml.RichSqlInsert;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.SqlDialect;
import org.apache.flink.table.api.SqlParserException;
import org.apache.flink.table.api.TableConfig;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.api.Types;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.calcite.FlinkPlannerImpl;
import org.apache.flink.table.catalog.Catalog;
import org.apache.flink.table.catalog.CatalogBaseTable;
import org.apache.flink.table.catalog.CatalogDatabaseImpl;
import org.apache.flink.table.catalog.CatalogManager;
import org.apache.flink.table.catalog.CatalogManagerCalciteSchema;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.CatalogTableImpl;
import org.apache.flink.table.catalog.FunctionCatalog;
import org.apache.flink.table.catalog.GenericInMemoryCatalog;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.exceptions.DatabaseNotExistException;
import org.apache.flink.table.catalog.exceptions.TableAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.TableNotExistException;
import org.apache.flink.table.delegation.Parser;
import org.apache.flink.table.expressions.ExpressionBridge;
import org.apache.flink.table.expressions.PlannerExpressionConverter;
import org.apache.flink.table.expressions.resolver.ExpressionResolver.ExpressionResolverBuilder;
import org.apache.flink.table.module.ModuleManager;
import org.apache.flink.table.operations.CatalogSinkModifyOperation;
import org.apache.flink.table.operations.Operation;
import org.apache.flink.table.operations.ShowFunctionsOperation;
import org.apache.flink.table.operations.ShowFunctionsOperation.FunctionScope;
import org.apache.flink.table.operations.UseCatalogOperation;
import org.apache.flink.table.operations.UseDatabaseOperation;
import org.apache.flink.table.operations.command.ClearOperation;
import org.apache.flink.table.operations.command.HelpOperation;
import org.apache.flink.table.operations.command.QuitOperation;
import org.apache.flink.table.operations.command.ResetOperation;
import org.apache.flink.table.operations.command.SetOperation;
import org.apache.flink.table.operations.ddl.AlterDatabaseOperation;
import org.apache.flink.table.operations.ddl.AlterTableOptionsOperation;
import org.apache.flink.table.operations.ddl.AlterTableRenameOperation;
import org.apache.flink.table.operations.ddl.CreateDatabaseOperation;
import org.apache.flink.table.operations.ddl.CreateTableOperation;
import org.apache.flink.table.operations.ddl.DropDatabaseOperation;
import org.apache.flink.table.parse.CalciteParser;
import org.apache.flink.table.planner.ParserImpl;
import org.apache.flink.table.planner.PlanningConfigurationBuilder;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.utils.TypeConversions;
import org.apache.flink.table.utils.CatalogManagerMocks;
import org.apache.flink.table.utils.ExpressionResolverMocks;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.apache.calcite.jdbc.CalciteSchemaBuilder.asRootSchema;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage;

/** Test cases for {@link SqlToOperationConverter}. * */
public class SqlToOperationConverterTest {
    private final TableConfig tableConfig = new TableConfig();
    private final Catalog catalog = new GenericInMemoryCatalog("MockCatalog", "default");
    private final CatalogManager catalogManager =
            CatalogManagerMocks.preparedCatalogManager().defaultCatalog("builtin", catalog).build();
    private final ModuleManager moduleManager = new ModuleManager();
    private final FunctionCatalog functionCatalog =
            new FunctionCatalog(tableConfig, catalogManager, moduleManager);
    private final PlanningConfigurationBuilder planningConfigurationBuilder =
            new PlanningConfigurationBuilder(
                    tableConfig,
                    functionCatalog,
                    asRootSchema(
                            new CatalogManagerCalciteSchema(catalogManager, tableConfig, false)),
                    new ExpressionBridge<>(PlannerExpressionConverter.INSTANCE()));
    private final Parser parser =
            new ParserImpl(
                    catalogManager,
                    () -> getPlannerBySqlDialect(SqlDialect.DEFAULT),
                    () -> getParserBySqlDialect(SqlDialect.DEFAULT));

    @Rule public ExpectedException thrown = ExpectedException.none();

    @Before
    public void before() throws TableAlreadyExistException, DatabaseNotExistException {
        catalogManager.initSchemaResolver(true, getExpressionResolver());
        final ObjectPath path1 = new ObjectPath(catalogManager.getCurrentDatabase(), "t1");
        final ObjectPath path2 = new ObjectPath(catalogManager.getCurrentDatabase(), "t2");
        final TableSchema tableSchema =
                TableSchema.builder()
                        .field("a", DataTypes.BIGINT())
                        .field("b", DataTypes.VARCHAR(Integer.MAX_VALUE))
                        .field("c", DataTypes.INT())
                        .field("d", DataTypes.VARCHAR(Integer.MAX_VALUE))
                        .build();
        Map<String, String> properties = new HashMap<>();
        properties.put("connector", "COLLECTION");
        final CatalogTable catalogTable = new CatalogTableImpl(tableSchema, properties, "");
        catalog.createTable(path1, catalogTable, true);
        catalog.createTable(path2, catalogTable, true);
    }

    @After
    public void after() throws TableNotExistException {
        final ObjectPath path1 = new ObjectPath(catalogManager.getCurrentDatabase(), "t1");
        final ObjectPath path2 = new ObjectPath(catalogManager.getCurrentDatabase(), "t2");
        catalog.dropTable(path1, true);
        catalog.dropTable(path2, true);
    }

    @Test
    public void testUseCatalog() {
        final String sql = "USE CATALOG cat1";
        Operation operation = parse(sql, SqlDialect.DEFAULT);
        assert operation instanceof UseCatalogOperation;
        assertEquals("cat1", ((UseCatalogOperation) operation).getCatalogName());
    }

    @Test
    public void testUseDatabase() {
        final String sql1 = "USE db1";
        Operation operation1 = parse(sql1, SqlDialect.DEFAULT);
        assert operation1 instanceof UseDatabaseOperation;
        assertEquals("builtin", ((UseDatabaseOperation) operation1).getCatalogName());
        assertEquals("db1", ((UseDatabaseOperation) operation1).getDatabaseName());

        final String sql2 = "USE cat1.db1";
        Operation operation2 = parse(sql2, SqlDialect.DEFAULT);
        assert operation2 instanceof UseDatabaseOperation;
        assertEquals("cat1", ((UseDatabaseOperation) operation2).getCatalogName());
        assertEquals("db1", ((UseDatabaseOperation) operation2).getDatabaseName());
    }

    @Test(expected = ValidationException.class)
    public void testUseDatabaseWithException() {
        final String sql = "USE cat1.db1.tbl1";
        Operation operation = parse(sql, SqlDialect.DEFAULT);
    }

    @Test
    public void testCreateDatabase() {
        final String[] createDatabaseSqls =
                new String[] {
                    "create database db1",
                    "create database if not exists cat1.db1",
                    "create database cat1.db1 comment 'db1_comment'",
                    "create database cat1.db1 comment 'db1_comment' with ('k1' = 'v1', 'K2' = 'V2')"
                };
        final String[] expectedCatalogs = new String[] {"builtin", "cat1", "cat1", "cat1"};
        final String expectedDatabase = "db1";
        final String[] expectedComments = new String[] {null, null, "db1_comment", "db1_comment"};
        final boolean[] expectedIgnoreIfExists = new boolean[] {false, true, false, false};
        Map<String, String> properties = new HashMap<>();
        properties.put("k1", "v1");
        properties.put("K2", "V2");
        final Map[] expectedProperties =
                new Map[] {
                    new HashMap<String, String>(),
                    new HashMap<String, String>(),
                    new HashMap<String, String>(),
                    new HashMap(properties)
                };

        for (int i = 0; i < createDatabaseSqls.length; i++) {
            Operation operation = parse(createDatabaseSqls[i], SqlDialect.DEFAULT);
            assert operation instanceof CreateDatabaseOperation;
            final CreateDatabaseOperation createDatabaseOperation =
                    (CreateDatabaseOperation) operation;
            assertEquals(expectedCatalogs[i], createDatabaseOperation.getCatalogName());
            assertEquals(expectedDatabase, createDatabaseOperation.getDatabaseName());
            assertEquals(
                    expectedComments[i], createDatabaseOperation.getCatalogDatabase().getComment());
            assertEquals(expectedIgnoreIfExists[i], createDatabaseOperation.isIgnoreIfExists());
            assertEquals(
                    expectedProperties[i],
                    createDatabaseOperation.getCatalogDatabase().getProperties());
        }
    }

    @Test
    public void testAlterDatabase() throws Exception {
        catalogManager.registerCatalog("cat1", new GenericInMemoryCatalog("default", "default"));
        catalogManager
                .getCatalog("cat1")
                .get()
                .createDatabase(
                        "db1", new CatalogDatabaseImpl(new HashMap<>(), "db1_comment"), true);
        final String sql = "alter database cat1.db1 set ('k1'='v1', 'K2'='V2')";
        Operation operation = parse(sql, SqlDialect.DEFAULT);
        assert operation instanceof AlterDatabaseOperation;
        Map<String, String> properties = new HashMap<>();
        properties.put("k1", "v1");
        properties.put("K2", "V2");
        assertEquals("db1", ((AlterDatabaseOperation) operation).getDatabaseName());
        assertEquals("cat1", ((AlterDatabaseOperation) operation).getCatalogName());
        assertEquals(
                "db1_comment",
                ((AlterDatabaseOperation) operation).getCatalogDatabase().getComment());
        assertEquals(
                properties,
                ((AlterDatabaseOperation) operation).getCatalogDatabase().getProperties());
    }

    @Test
    public void testDropDatabase() {
        final String[] dropDatabaseSqls =
                new String[] {
                    "drop database db1",
                    "drop database if exists db1",
                    "drop database if exists cat1.db1 CASCADE",
                    "drop database if exists cat1.db1 RESTRICT"
                };
        final String[] expectedCatalogs = new String[] {"builtin", "builtin", "cat1", "cat1"};
        final String expectedDatabase = "db1";
        final boolean[] expectedIfExists = new boolean[] {false, true, true, true};
        final boolean[] expectedIsCascades = new boolean[] {false, false, true, false};

        for (int i = 0; i < dropDatabaseSqls.length; i++) {
            Operation operation = parse(dropDatabaseSqls[i], SqlDialect.DEFAULT);
            assert operation instanceof DropDatabaseOperation;
            final DropDatabaseOperation dropDatabaseOperation = (DropDatabaseOperation) operation;
            assertEquals(expectedCatalogs[i], dropDatabaseOperation.getCatalogName());
            assertEquals(expectedDatabase, dropDatabaseOperation.getDatabaseName());
            assertEquals(expectedIfExists[i], dropDatabaseOperation.isIfExists());
            assertEquals(expectedIsCascades[i], dropDatabaseOperation.isCascade());
        }
    }

    @Test
    public void testShowFunctions() {
        final String sql1 = "SHOW FUNCTIONS";
        assertShowFunctions(sql1, sql1, FunctionScope.ALL);

        final String sql2 = "SHOW USER FUNCTIONS";
        assertShowFunctions(sql2, sql2, FunctionScope.USER);
    }

    @Test
    public void testCreateTable() {
        final String sql =
                "CREATE TABLE tbl1 (\n"
                        + "  a bigint,\n"
                        + "  b varchar, \n"
                        + "  c int, \n"
                        + "  d varchar"
                        + ")\n"
                        + "  PARTITIONED BY (a, d)\n"
                        + "  with (\n"
                        + "    'connector' = 'kafka', \n"
                        + "    'kafka.topic' = 'log.test'\n"
                        + ")\n";
        final FlinkPlannerImpl planner = getPlannerBySqlDialect(SqlDialect.DEFAULT);
        SqlNode node = getParserBySqlDialect(SqlDialect.DEFAULT).parse(sql);
        assert node instanceof SqlCreateTable;
        Operation operation = SqlToOperationConverter.convert(planner, catalogManager, node).get();
        assert operation instanceof CreateTableOperation;
        CreateTableOperation op = (CreateTableOperation) operation;
        CatalogTable catalogTable = op.getCatalogTable();
        assertEquals(Arrays.asList("a", "d"), catalogTable.getPartitionKeys());
        assertArrayEquals(
                catalogTable.getSchema().getFieldNames(), new String[] {"a", "b", "c", "d"});
        assertArrayEquals(
                catalogTable.getSchema().getFieldDataTypes(),
                new DataType[] {
                    DataTypes.BIGINT(),
                    DataTypes.VARCHAR(Integer.MAX_VALUE),
                    DataTypes.INT(),
                    DataTypes.VARCHAR(Integer.MAX_VALUE)
                });
    }

    @Test
    public void testCreateTableWithComputedColumn()
            throws TableAlreadyExistException, DatabaseNotExistException {
        Map<String, String> props = new HashMap<>();
        props.put("connector", "kafka");
        props.put("kafka.topic", "log.test");
        CatalogBaseTable table =
                new CatalogTableImpl(
                        TableSchema.builder()
                                .field("a", DataTypes.BIGINT())
                                .field("b", DataTypes.BIGINT(), "a + 1")
                                .build(),
                        props,
                        "Test table with computed column");
        ObjectPath path = ObjectPath.fromString("default.kafka");
        this.catalog.createTable(path, table, false);
        final FlinkPlannerImpl planner = getPlannerBySqlDialect(SqlDialect.DEFAULT);
        String sql = "select * from kafka";
        SqlNode node = getParserBySqlDialect(SqlDialect.DEFAULT).parse(sql);
        assert node instanceof SqlSelect;
        thrown.expectCause(Matchers.isA(ValidationException.class));
        thrown.expectMessage("Invalid expression for computed column 'b'.");
        SqlToOperationConverter.convert(planner, catalogManager, node);
    }

    @Test
    public void testCreateTableWithMinusInOptionKey() {
        final String sql =
                "create table source_table(\n"
                        + "  a int,\n"
                        + "  b bigint,\n"
                        + "  c varchar\n"
                        + ") with (\n"
                        + "  'a-B-c-d124' = 'Ab',\n"
                        + "  'a.b-c-d.e-f.g' = 'ada',\n"
                        + "  'a.b-c-d.e-f1231.g' = 'ada',\n"
                        + "  'a.b-c-d.*' = 'adad')\n";
        final FlinkPlannerImpl planner = getPlannerBySqlDialect(SqlDialect.DEFAULT);
        SqlNode node = getParserBySqlDialect(SqlDialect.DEFAULT).parse(sql);
        assert node instanceof SqlCreateTable;
        Operation operation = SqlToOperationConverter.convert(planner, catalogManager, node).get();
        assert operation instanceof CreateTableOperation;
        CreateTableOperation op = (CreateTableOperation) operation;
        CatalogTable catalogTable = op.getCatalogTable();
        Map<String, String> properties =
                catalogTable.getOptions().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, String> sortedProperties = new TreeMap<>(properties);
        final String expected =
                "{a-B-c-d124=Ab, "
                        + "a.b-c-d.*=adad, "
                        + "a.b-c-d.e-f.g=ada, "
                        + "a.b-c-d.e-f1231.g=ada}";
        assertEquals(expected, sortedProperties.toString());
    }

    @Test(expected = TableException.class)
    public void testCreateTableWithPkUniqueKeys() {
        final String sql =
                "CREATE TABLE tbl1 (\n"
                        + "  a bigint,\n"
                        + "  b varchar, \n"
                        + "  c int, \n"
                        + "  d varchar, \n"
                        + "  primary key(a), \n"
                        + "  unique(a, b) \n"
                        + ")\n"
                        + "  PARTITIONED BY (a, d)\n"
                        + "  with (\n"
                        + "    'connector' = 'kafka', \n"
                        + "    'kafka.topic' = 'log.test'\n"
                        + ")\n";
        final FlinkPlannerImpl planner = getPlannerBySqlDialect(SqlDialect.DEFAULT);
        SqlNode node = getParserBySqlDialect(SqlDialect.DEFAULT).parse(sql);
        assert node instanceof SqlCreateTable;
        SqlToOperationConverter.convert(planner, catalogManager, node);
    }

    @Test
    public void testSqlInsertWithStaticPartition() {
        final String sql = "insert into t1 partition(a=1) select b, c, d from t2";
        FlinkPlannerImpl planner = getPlannerBySqlDialect(SqlDialect.DEFAULT);
        SqlNode node = getParserBySqlDialect(SqlDialect.DEFAULT).parse(sql);
        assert node instanceof RichSqlInsert;
        Operation operation = SqlToOperationConverter.convert(planner, catalogManager, node).get();
        assert operation instanceof CatalogSinkModifyOperation;
        CatalogSinkModifyOperation sinkModifyOperation = (CatalogSinkModifyOperation) operation;
        final Map<String, String> expectedStaticPartitions = new HashMap<>();
        expectedStaticPartitions.put("a", "1");
        assertEquals(expectedStaticPartitions, sinkModifyOperation.getStaticPartitions());
    }

    @Test // TODO: tweak the tests when FLINK-13604 is fixed.
    public void testCreateTableWithFullDataTypes() {
        final List<TestItem> testItems =
                Arrays.asList(
                        // Expect to be DataTypes.CHAR(1).
                        createTestItem("CHAR", DataTypes.STRING()),
                        // Expect to be DataTypes.CHAR(1).notNull().
                        createTestItem("CHAR NOT NULL", DataTypes.STRING()),
                        // Expect to be DataTypes.CHAR(1).
                        createTestItem("CHAR NULL", DataTypes.STRING()),
                        // Expect to be DataTypes.CHAR(33).
                        createTestItem("CHAR(33)", DataTypes.STRING()),
                        createTestItem("VARCHAR", DataTypes.STRING()),
                        // Expect to be DataTypes.VARCHAR(33).
                        createTestItem("VARCHAR(33)", DataTypes.STRING()),
                        createTestItem("STRING", DataTypes.STRING()),
                        createTestItem("BOOLEAN", DataTypes.BOOLEAN()),
                        // Expect to be DECIMAL(10, 0).
                        createTestItem(
                                "DECIMAL",
                                TypeConversions.fromLegacyInfoToDataType(Types.DECIMAL())),
                        // Expect to be DECIMAL(10, 0).
                        createTestItem(
                                "DEC", TypeConversions.fromLegacyInfoToDataType(Types.DECIMAL())),
                        // Expect to be DECIMAL(10, 0).
                        createTestItem(
                                "NUMERIC",
                                TypeConversions.fromLegacyInfoToDataType(Types.DECIMAL())),
                        // Expect to be DECIMAL(10, 0).
                        createTestItem(
                                "DECIMAL(10)",
                                TypeConversions.fromLegacyInfoToDataType(Types.DECIMAL())),
                        // Expect to be DECIMAL(10, 0).
                        createTestItem(
                                "DEC(10)",
                                TypeConversions.fromLegacyInfoToDataType(Types.DECIMAL())),
                        // Expect to be DECIMAL(10, 0).
                        createTestItem(
                                "NUMERIC(10)",
                                TypeConversions.fromLegacyInfoToDataType(Types.DECIMAL())),
                        // Expect to be DECIMAL(10, 3).
                        createTestItem(
                                "DECIMAL(10, 3)",
                                TypeConversions.fromLegacyInfoToDataType(Types.DECIMAL())),
                        // Expect to be DECIMAL(10, 3).
                        createTestItem(
                                "DEC(10, 3)",
                                TypeConversions.fromLegacyInfoToDataType(Types.DECIMAL())),
                        // Expect to be DECIMAL(10, 3).
                        createTestItem(
                                "NUMERIC(10, 3)",
                                TypeConversions.fromLegacyInfoToDataType(Types.DECIMAL())),
                        createTestItem("TINYINT", DataTypes.TINYINT()),
                        createTestItem("SMALLINT", DataTypes.SMALLINT()),
                        createTestItem("INTEGER", DataTypes.INT()),
                        createTestItem("INT", DataTypes.INT()),
                        createTestItem("BIGINT", DataTypes.BIGINT()),
                        createTestItem("FLOAT", DataTypes.FLOAT()),
                        createTestItem("DOUBLE", DataTypes.DOUBLE()),
                        createTestItem("DOUBLE PRECISION", DataTypes.DOUBLE()),
                        createTestItem(
                                "DATE", TypeConversions.fromLegacyInfoToDataType(Types.SQL_DATE())),
                        createTestItem(
                                "TIME", TypeConversions.fromLegacyInfoToDataType(Types.SQL_TIME())),
                        createTestItem(
                                "TIME WITHOUT TIME ZONE",
                                TypeConversions.fromLegacyInfoToDataType(Types.SQL_TIME())),
                        // Expect to be Time(3).
                        createTestItem(
                                "TIME(3)",
                                TypeConversions.fromLegacyInfoToDataType(Types.SQL_TIME())),
                        // Expect to be Time(3).
                        createTestItem(
                                "TIME(3) WITHOUT TIME ZONE",
                                TypeConversions.fromLegacyInfoToDataType(Types.SQL_TIME())),
                        createTestItem(
                                "TIMESTAMP",
                                TypeConversions.fromLegacyInfoToDataType(Types.SQL_TIMESTAMP())),
                        createTestItem(
                                "TIMESTAMP WITHOUT TIME ZONE",
                                TypeConversions.fromLegacyInfoToDataType(Types.SQL_TIMESTAMP())),
                        // Expect to be timestamp(3).
                        createTestItem(
                                "TIMESTAMP(3)",
                                TypeConversions.fromLegacyInfoToDataType(Types.SQL_TIMESTAMP())),
                        // Expect to be timestamp(3).
                        createTestItem(
                                "TIMESTAMP(3) WITHOUT TIME ZONE",
                                TypeConversions.fromLegacyInfoToDataType(Types.SQL_TIMESTAMP())),
                        // Expect to be ARRAY<INT NOT NULL>.
                        createTestItem("ARRAY<INT NOT NULL>", DataTypes.ARRAY(DataTypes.INT())),
                        createTestItem("INT ARRAY", DataTypes.ARRAY(DataTypes.INT())),
                        // Expect to be ARRAY<INT NOT NULL>.
                        createTestItem("INT NOT NULL ARRAY", DataTypes.ARRAY(DataTypes.INT())),
                        // Expect to be ARRAY<INT> NOT NULL.
                        createTestItem("INT ARRAY NOT NULL", DataTypes.ARRAY(DataTypes.INT())),
                        // Expect to be MULTISET<INT NOT NULL>.
                        createTestItem(
                                "MULTISET<INT NOT NULL>", DataTypes.MULTISET(DataTypes.INT())),
                        createTestItem("INT MULTISET", DataTypes.MULTISET(DataTypes.INT())),
                        // Expect to be MULTISET<INT NOT NULL>.
                        createTestItem(
                                "INT NOT NULL MULTISET", DataTypes.MULTISET(DataTypes.INT())),
                        // Expect to be MULTISET<INT> NOT NULL.
                        createTestItem(
                                "INT MULTISET NOT NULL", DataTypes.MULTISET(DataTypes.INT())),
                        createTestItem(
                                "MAP<BIGINT, BOOLEAN>",
                                DataTypes.MAP(DataTypes.BIGINT(), DataTypes.BOOLEAN())),
                        // Expect to be ROW<`f0` INT NOT NULL, `f1` BOOLEAN>.
                        createTestItem(
                                "ROW<f0 INT NOT NULL, f1 BOOLEAN>",
                                DataTypes.ROW(
                                        DataTypes.FIELD("f0", DataTypes.INT()),
                                        DataTypes.FIELD("f1", DataTypes.BOOLEAN()))),
                        // Expect to be ROW<`f0` INT NOT NULL, `f1` BOOLEAN>.
                        createTestItem(
                                "ROW(f0 INT NOT NULL, f1 BOOLEAN)",
                                DataTypes.ROW(
                                        DataTypes.FIELD("f0", DataTypes.INT()),
                                        DataTypes.FIELD("f1", DataTypes.BOOLEAN()))),
                        createTestItem(
                                "ROW<`f0` INT>",
                                DataTypes.ROW(DataTypes.FIELD("f0", DataTypes.INT()))),
                        createTestItem(
                                "ROW(`f0` INT)",
                                DataTypes.ROW(DataTypes.FIELD("f0", DataTypes.INT()))),
                        createTestItem("ROW<>", DataTypes.ROW()),
                        createTestItem("ROW()", DataTypes.ROW()),
                        // Expect to be ROW<`f0` INT NOT NULL '...', `f1` BOOLEAN '...'>.
                        createTestItem(
                                "ROW<f0 INT NOT NULL 'This is a comment.', "
                                        + "f1 BOOLEAN 'This as well.'>",
                                DataTypes.ROW(
                                        DataTypes.FIELD("f0", DataTypes.INT()),
                                        DataTypes.FIELD("f1", DataTypes.BOOLEAN()))),
                        createTestItem(
                                "ROW<f0 INT, f1 BOOLEAN> ARRAY",
                                DataTypes.ARRAY(
                                        DataTypes.ROW(
                                                DataTypes.FIELD("f0", DataTypes.INT()),
                                                DataTypes.FIELD("f1", DataTypes.BOOLEAN())))),
                        createTestItem(
                                "ARRAY<ROW<f0 INT, f1 BOOLEAN>>",
                                DataTypes.ARRAY(
                                        DataTypes.ROW(
                                                DataTypes.FIELD("f0", DataTypes.INT()),
                                                DataTypes.FIELD("f1", DataTypes.BOOLEAN())))),
                        createTestItem(
                                "ROW<f0 INT, f1 BOOLEAN> MULTISET",
                                DataTypes.MULTISET(
                                        DataTypes.ROW(
                                                DataTypes.FIELD("f0", DataTypes.INT()),
                                                DataTypes.FIELD("f1", DataTypes.BOOLEAN())))),
                        createTestItem(
                                "MULTISET<ROW<f0 INT, f1 BOOLEAN>>",
                                DataTypes.MULTISET(
                                        DataTypes.ROW(
                                                DataTypes.FIELD("f0", DataTypes.INT()),
                                                DataTypes.FIELD("f1", DataTypes.BOOLEAN())))),
                        createTestItem(
                                "ROW<f0 Row<f00 INT, f01 BOOLEAN>, "
                                        + "f1 INT ARRAY, "
                                        + "f2 BOOLEAN MULTISET>",
                                DataTypes.ROW(
                                        DataTypes.FIELD(
                                                "f0",
                                                DataTypes.ROW(
                                                        DataTypes.FIELD("f00", DataTypes.INT()),
                                                        DataTypes.FIELD(
                                                                "f01", DataTypes.BOOLEAN()))),
                                        DataTypes.FIELD("f1", DataTypes.ARRAY(DataTypes.INT())),
                                        DataTypes.FIELD(
                                                "f2", DataTypes.MULTISET(DataTypes.BOOLEAN())))));
        StringBuilder buffer = new StringBuilder("create table t1(\n");
        for (int i = 0; i < testItems.size(); i++) {
            buffer.append("f").append(i).append(" ").append(testItems.get(i).testExpr);
            if (i == testItems.size() - 1) {
                buffer.append(")");
            } else {
                buffer.append(",\n");
            }
        }
        final String sql = buffer.toString();
        final FlinkPlannerImpl planner = getPlannerBySqlDialect(SqlDialect.DEFAULT);
        SqlNode node = getParserBySqlDialect(SqlDialect.DEFAULT).parse(sql);
        assert node instanceof SqlCreateTable;
        Operation operation = SqlToOperationConverter.convert(planner, catalogManager, node).get();
        TableSchema schema = ((CreateTableOperation) operation).getCatalogTable().getSchema();
        Object[] expectedDataTypes = testItems.stream().map(item -> item.expectedType).toArray();
        assertArrayEquals(expectedDataTypes, schema.getFieldDataTypes());
    }

    @Test
    public void testCreateTableWithUnsupportedFeatures() {
        final List<TestItem> testItems =
                Arrays.asList(
                        createTestItem(
                                "ARRAY<TIMESTAMP(3) WITH LOCAL TIME ZONE>",
                                "Type is not supported: TIMESTAMP_WITH_LOCAL_TIME_ZONE"),
                        createTestItem(
                                "TIMESTAMP(3) WITH LOCAL TIME ZONE",
                                "Type is not supported: TIMESTAMP_WITH_LOCAL_TIME_ZONE"),
                        createTestItem(
                                "TIMESTAMP WITH LOCAL TIME ZONE",
                                "Type is not supported: TIMESTAMP_WITH_LOCAL_TIME_ZONE"),
                        createTestItem("BYTES", "Type is not supported: VARBINARY"),
                        createTestItem("VARBINARY(33)", "Type is not supported: VARBINARY"),
                        createTestItem("VARBINARY", "Type is not supported: VARBINARY"),
                        createTestItem("BINARY(33)", "Type is not supported: BINARY"),
                        createTestItem("BINARY", "Type is not supported: BINARY"),
                        createTestItem(
                                "AS 1 + 1",
                                "Only regular columns are supported in the DDL of the old planner."),
                        createTestItem(
                                "INT METADATA",
                                "Only regular columns are supported in the DDL of the old planner."));
        final String sqlTemplate = "CREATE TABLE T1 (f0 %s)";
        final FlinkPlannerImpl planner = getPlannerBySqlDialect(SqlDialect.DEFAULT);
        for (TestItem item : testItems) {
            String sql = String.format(sqlTemplate, item.testExpr);
            SqlNode node = getParserBySqlDialect(SqlDialect.DEFAULT).parse(sql);
            assert node instanceof SqlCreateTable;
            try {
                SqlToOperationConverter.convert(planner, catalogManager, node);
            } catch (Exception e) {
                assertThat(e, is(instanceOf(TableException.class)));
                assertThat(e, hasMessage(equalTo(item.expectedError)));
            }
        }
    }

    @Test
    public void testAlterTable() throws Exception {
        Catalog catalog = new GenericInMemoryCatalog("default", "default");
        catalogManager.registerCatalog("cat1", catalog);
        catalog.createDatabase("db1", new CatalogDatabaseImpl(new HashMap<>(), null), true);
        CatalogTable catalogTable =
                new CatalogTableImpl(
                        TableSchema.builder().field("a", DataTypes.STRING()).build(),
                        new HashMap<>(),
                        "tb1");
        catalogManager.setCurrentCatalog("cat1");
        catalogManager.setCurrentDatabase("db1");
        catalog.createTable(new ObjectPath("db1", "tb1"), catalogTable, true);
        final String[] renameTableSqls =
                new String[] {
                    "alter table cat1.db1.tb1 rename to tb2",
                    "alter table db1.tb1 rename to tb2",
                    "alter table tb1 rename to cat1.db1.tb2",
                };
        final ObjectIdentifier expectedIdentifier = ObjectIdentifier.of("cat1", "db1", "tb1");
        final ObjectIdentifier expectedNewIdentifier = ObjectIdentifier.of("cat1", "db1", "tb2");
        // test rename table converter
        for (int i = 0; i < renameTableSqls.length; i++) {
            Operation operation = parse(renameTableSqls[i], SqlDialect.DEFAULT);
            assert operation instanceof AlterTableRenameOperation;
            final AlterTableRenameOperation alterTableRenameOperation =
                    (AlterTableRenameOperation) operation;
            assertEquals(expectedIdentifier, alterTableRenameOperation.getTableIdentifier());
            assertEquals(expectedNewIdentifier, alterTableRenameOperation.getNewTableIdentifier());
        }
        // test alter table properties
        Operation operation =
                parse(
                        "alter table cat1.db1.tb1 set ('k1' = 'v1', 'K2' = 'V2')",
                        SqlDialect.DEFAULT);
        assert operation instanceof AlterTableOptionsOperation;
        final AlterTableOptionsOperation alterTableOptionsOperation =
                (AlterTableOptionsOperation) operation;
        assertEquals(expectedIdentifier, alterTableOptionsOperation.getTableIdentifier());
        assertEquals(2, alterTableOptionsOperation.getCatalogTable().getOptions().size());
        Map<String, String> properties = new HashMap<>();
        properties.put("k1", "v1");
        properties.put("K2", "V2");
        assertEquals(properties, alterTableOptionsOperation.getCatalogTable().getOptions());
    }

    @Test
    public void testClearCommand() {
        assertSimpleCommand("ClEaR", instanceOf(ClearOperation.class));
    }

    @Test
    public void testHelpCommand() {
        assertSimpleCommand("hELp", instanceOf(HelpOperation.class));
    }

    @Test
    public void testQuitCommand() {
        assertSimpleCommand("qUIt", instanceOf(QuitOperation.class));
        assertSimpleCommand("Exit", instanceOf(QuitOperation.class));
    }

    @Test
    public void testResetCommand() {
        assertSimpleCommand("REsEt", instanceOf(ResetOperation.class));
    }

    @Test
    public void testSetOperation() {
        assertSetCommand("   SEt       ");
        assertSetCommand("SET execution.runtime-type= batch", "execution.runtime-type", "batch");
        assertSetCommand(
                "SET pipeline.jars = /path/to/test-_-jar.jar",
                "pipeline.jars",
                "/path/to/test-_-jar.jar");

        assertFailedSetCommand("SET execution.runtime-type=");
    }

    // ~ Tool Methods ----------------------------------------------------------

    private static TestItem createTestItem(Object... args) {
        assert args.length == 2;
        final String testExpr = (String) args[0];
        TestItem testItem = TestItem.fromTestExpr(testExpr);
        if (args[1] instanceof String) {
            testItem.withExpectedError((String) args[1]);
        } else {
            testItem.withExpectedType(args[1]);
        }
        return testItem;
    }

    private void assertShowFunctions(
            String sql, String expectedSummary, FunctionScope expectedScope) {
        Operation operation = parse(sql, SqlDialect.DEFAULT);
        assert operation instanceof ShowFunctionsOperation;
        final ShowFunctionsOperation showFunctionsOperation = (ShowFunctionsOperation) operation;

        assertEquals(expectedScope, showFunctionsOperation.getFunctionScope());
        assertEquals(expectedSummary, showFunctionsOperation.asSummaryString());
    }

    private void assertSimpleCommand(String statement, Matcher<? super Operation> matcher) {
        Operation operation = parser.parse(statement).get(0);
        assertThat(operation, matcher);
    }

    private void assertSetCommand(String statement, String... operands) {
        SetOperation operation = (SetOperation) parser.parse(statement).get(0);

        assertArrayEquals(operands, operation.getOperands());
    }

    private void assertFailedSetCommand(String statement) {
        thrown.expect(SqlParserException.class);

        parser.parse(statement);
    }

    private CalciteParser getParserBySqlDialect(SqlDialect sqlDialect) {
        tableConfig.setSqlDialect(sqlDialect);
        return planningConfigurationBuilder.createCalciteParser();
    }

    private FlinkPlannerImpl getPlannerBySqlDialect(SqlDialect sqlDialect) {
        tableConfig.setSqlDialect(sqlDialect);
        return planningConfigurationBuilder.createFlinkPlanner(
                catalogManager.getCurrentCatalog(), catalogManager.getCurrentDatabase());
    }

    private ExpressionResolverBuilder getExpressionResolver() {
        return ExpressionResolverMocks.basicResolver(catalogManager, functionCatalog, parser);
    }

    private Operation parse(String sql, SqlDialect sqlDialect) {
        FlinkPlannerImpl planner = getPlannerBySqlDialect(sqlDialect);
        final CalciteParser calciteParser = getParserBySqlDialect(sqlDialect);
        SqlNode node = calciteParser.parse(sql);
        return SqlToOperationConverter.convert(planner, catalogManager, node).get();
    }

    // ~ Inner Classes ----------------------------------------------------------

    private static class TestItem {
        private final String testExpr;
        @Nullable private Object expectedType;
        @Nullable private String expectedError;

        private TestItem(String testExpr) {
            this.testExpr = testExpr;
        }

        static TestItem fromTestExpr(String testExpr) {
            return new TestItem(testExpr);
        }

        TestItem withExpectedType(Object expectedType) {
            this.expectedType = expectedType;
            return this;
        }

        TestItem withExpectedError(String expectedError) {
            this.expectedError = expectedError;
            return this;
        }

        @Override
        public String toString() {
            return this.testExpr;
        }
    }
}
