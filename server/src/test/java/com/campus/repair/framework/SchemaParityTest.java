package com.campus.repair.framework;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建表脚本一致性守门。
 *
 * <p>两份脚本的「真源分工」（见 docs/phases/README.md §三.1）：
 * <ul>
 *   <li>{@code server/src/main/resources/schema-test.sql} 是列定义真源（local 与测试都跑它）；</li>
 *   <li>{@code sql/schema.sql} 是生产 MySQL 8 方言版本，表集合必须包含 H2 全部表；</li>
 *   <li>只允许 MySQL 侧多出 {@code sys_permission} / {@code sys_role_permission} 两张**预留**表
 *       （运行期鉴权走职能码，不查它们）。</li>
 * </ul>
 *
 * <p>本测试不连数据库，只做静态解析，因此能进默认 {@code mvn test}；工程根缺失
 * （例如只拷贝了 server 目录）时跳过而不是失败。
 */
class SchemaParityTest {

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?`?(\\w+)`?\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** 表体：从 CREATE TABLE 后的第一个 '(' 到下一个行首 ')' */
    private static final Pattern TABLE_BODY =
            Pattern.compile("CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?`?(\\w+)`?\\s*\\((.*?)\\n\\)\\s*(?:ENGINE|;)?",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern COLUMN_LINE =
            Pattern.compile("^\\s*`?(\\w+)`?\\s+[A-Za-z]", Pattern.MULTILINE);

    private static final Set<String> ALLOWED_MYSQL_ONLY = Set.of("sys_permission", "sys_role_permission");

    /** 这些表必须逐个列对齐（新增/删除列时最容易只改一边）。 */
    private static final Set<String> STRICT_COLUMN_TABLES =
            Set.of("worker_profile", "spatial_node", "repair_category", "repair_order", "sys_config",
                    "sys_identity_roster", "sys_identity_review", "sys_content_review", "sys_announcement",
                    "sys_operation_log");

    private static final Set<String> NON_COLUMN_KEYWORDS =
            Set.of("PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN", "CHECK", "FULLTEXT", "SPATIAL");

    @Test
    @DisplayName("H2 与 MySQL 建表脚本的表集合一致（允许两张权限预留表）")
    void tableSetsAreAligned() throws IOException {
        Map<String, Set<String>> h2 = parse(h2Schema());
        Map<String, Set<String>> mysql = parse(mysqlSchema());

        Set<String> h2Tables = new TreeSet<>(h2.keySet());
        Set<String> mysqlTables = new TreeSet<>(mysql.keySet());

        Set<String> missingInMysql = new TreeSet<>(h2Tables);
        missingInMysql.removeAll(mysqlTables);
        assertTrue(missingInMysql.isEmpty(),
                "H2 有、MySQL 缺的表（请把列定义补进 sql/schema.sql）：" + missingInMysql);

        Set<String> mysqlOnly = new TreeSet<>(mysqlTables);
        mysqlOnly.removeAll(h2Tables);
        assertEquals(new TreeSet<>(ALLOWED_MYSQL_ONLY), mysqlOnly,
                "MySQL 侧只允许两张权限预留表，出现其它表说明两边又漂移了：" + mysqlOnly);

        assertTrue(h2Tables.size() >= 21, "H2 基线表数异常（应为 21 张），当前 " + h2Tables.size());
    }

    @Test
    @DisplayName("核心表的列集合逐个对齐（防止只改一边）")
    void strictTablesHaveSameColumns() throws IOException {
        Map<String, Set<String>> h2 = parse(h2Schema());
        Map<String, Set<String>> mysql = parse(mysqlSchema());

        for (String table : STRICT_COLUMN_TABLES) {
            if (!h2.containsKey(table)) {
                continue;
            }
            assertTrue(mysql.containsKey(table), "MySQL 缺表: " + table);
            assertEquals(h2.get(table), mysql.get(table),
                    "表 " + table + " 的列集合不一致（H2 vs MySQL）");
        }
    }

    private Map<String, Set<String>> parse(String sql) {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        Matcher tableMatcher = TABLE_BODY.matcher(sql);
        while (tableMatcher.find()) {
            String table = tableMatcher.group(1).toLowerCase();
            String body = tableMatcher.group(2);
            Set<String> columns = new LinkedHashSet<>();
            Matcher columnMatcher = COLUMN_LINE.matcher(body);
            while (columnMatcher.find()) {
                String column = columnMatcher.group(1);
                if (NON_COLUMN_KEYWORDS.contains(column.toUpperCase())) {
                    continue;
                }
                columns.add(column.toLowerCase());
            }
            tables.put(table, columns);
        }
        return tables;
    }

    private String h2Schema() throws IOException {
        try (var in = getClass().getClassLoader().getResourceAsStream("schema-test.sql")) {
            Assumptions.assumeTrue(in != null, "classpath 缺少 schema-test.sql");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String mysqlSchema() throws IOException {
        Path[] candidates = {
                Path.of("..", "sql", "schema.sql"),
                Path.of("sql", "schema.sql"),
                Path.of(System.getProperty("user.dir"), "..", "sql", "schema.sql")
        };
        for (Path candidate : candidates) {
            Path normalized = candidate.normalize();
            if (Files.isRegularFile(normalized)) {
                return Files.readString(normalized, StandardCharsets.UTF_8);
            }
        }
        Assumptions.abort("未找到 sql/schema.sql（只拷贝了 server 目录时跳过）");
        return "";
    }
}
