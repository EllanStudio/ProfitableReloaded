package com.faridfaharaj.profitable.data;

import com.faridfaharaj.profitable.Profitable;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.logging.Level;

public final class DataBase {

    private static HikariDataSource dataSource;
    private static boolean mysql;

    private DataBase() {
    }

    public static void connectMySQL() throws SQLException {
        var configFile = Profitable.getInstance().getConfig();
        String host = required(configFile.getString("database.mysql.host"), "database.mysql.host");
        String port = required(configFile.getString("database.mysql.port"), "database.mysql.port");
        String database = required(configFile.getString("database.mysql.database"), "database.mysql.database");
        String username = required(configFile.getString("database.mysql.username"), "database.mysql.username");
        String password = configFile.getString("database.mysql.password", "");
        String options = configFile.getString("database.mysql.options", "?useSSL=true&serverTimezone=UTC");

        HikariConfig config = new HikariConfig();
        config.setPoolName("ProfitableReloaded-MySQL");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + options);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(2, configFile.getInt("database.pool.maximum-size", 10)));
        config.setMinimumIdle(Math.max(0, configFile.getInt("database.pool.minimum-idle", 2)));
        config.setConnectionTimeout(Math.max(1000, configFile.getLong("database.pool.connection-timeout-ms", 10000)));
        config.setIdleTimeout(300000);
        config.setMaxLifetime(1800000);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");

        dataSource = new HikariDataSource(config);
        mysql = true;
        verifyConnection();
    }

    public static void connectSQLite() throws SQLException {
        if (Profitable.getInstance().getConfig().getBoolean("redis.enabled", false)) {
            throw new SQLException("Redis multi-server mode requires a shared MySQL database; SQLite is single-server only");
        }

        Path dataFolder = Profitable.getInstance().getDataFolder().toPath();
        Path legacy = dataFolder.resolve("data").resolve("server_Wide.db");
        Path database = dataFolder.resolve("Data.db");
        try {
            Files.createDirectories(dataFolder);
            if (Files.exists(legacy) && Files.notExists(database)) {
                Files.move(legacy, database, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new SQLException("Could not prepare SQLite database file", e);
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("ProfitableReloaded-SQLite");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + database.toAbsolutePath());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.setConnectionInitSql("PRAGMA foreign_keys = ON");
        dataSource = new HikariDataSource(config);
        mysql = false;

        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA busy_timeout = 10000");
        }
    }

    public static Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Database pool is not initialized");
        }
        return dataSource.getConnection();
    }

    public static DataSource getDataSource() {
        if (dataSource == null || dataSource.isClosed()) {
            throw new IllegalStateException("Database pool is not initialized");
        }
        return dataSource;
    }

    public static boolean isMySQL() {
        return mysql;
    }

    public static void migrateDatabase() {
        if (!mysql) {
            migrateSQLite();
            return;
        }
        int legacyVersion = readLegacyVersion();
        var configuration = Flyway.configure(Profitable.getInstance().getClass().getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/migration/" + (mysql ? "mysql" : "sqlite"))
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion(String.valueOf(Math.max(1, legacyVersion))))
                .validateMigrationNaming(true)
                .cleanDisabled(true);

        int migrations = configuration.load().migrate().migrationsExecuted;
        Profitable.getInstance().getLogger().info("Flyway database migration complete (" + migrations + " applied)");
    }

    private static void migrateSQLite() {
        String[] migrations = {"V1__tables.sql", "V2__indexes.sql", "V3__coordination.sql",
                "V4__order_fifo_and_constraints.sql", "V5__durable_settlement_and_sequence.sql",
                "V6__audited_market_adjustments.sql", "V7__market_time_and_wallet_adjustments.sql"};
        try (Connection connection = getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS profitable_schema_history ("
                        + "version INT NOT NULL PRIMARY KEY, description VARCHAR(100) NOT NULL, installed_on BIGINT NOT NULL)");
            }

            int currentVersion = readSQLiteVersion(connection);
            if (currentVersion == 0 && tableExists(connection, "assets")) {
                if (!columnExists(connection, "assets", "world")) {
                    throw new IllegalStateException("Pre-0.2 SQLite schema detected. Back up Data.db and migrate it before starting this version.");
                }
                currentVersion = Math.max(1, readLegacyVersion(connection));
                baselineSQLite(connection, currentVersion);
            }

            for (int version = currentVersion + 1; version <= migrations.length; version++) {
                applySQLiteMigration(connection, version, migrations[version - 1]);
            }
            Profitable.getInstance().getLogger().info("SQLite schema migration complete (version " + migrations.length + ")");
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Could not migrate SQLite database", e);
        }
    }

    private static void applySQLiteMigration(Connection connection, int version, String file) throws SQLException, IOException {
        var rawResource = Profitable.getInstance().getResource("db/migration/sqlite/" + file);
        if (rawResource == null) {
            throw new IOException("Missing SQLite migration resource " + file);
        }
        String sql;
        try (var resource = rawResource) {
            sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                for (String command : sql.split(";")) {
                    if (!command.isBlank()) {
                        statement.execute(command.trim());
                    }
                }
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO profitable_schema_history(version, description, installed_on) VALUES (?, ?, ?)")) {
                statement.setInt(1, version);
                statement.setString(2, file);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        }
    }

    private static int readSQLiteVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM profitable_schema_history")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static void baselineSQLite(Connection connection, int version) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO profitable_schema_history(version, description, installed_on) VALUES (?, ?, ?)")) {
            for (int current = 1; current <= version; current++) {
                statement.setInt(1, current);
                statement.setString(2, "Legacy baseline");
                statement.setLong(3, System.currentTimeMillis());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet result = connection.getMetaData().getTables(null, null, table, new String[]{"TABLE"})) {
            return result.next();
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (ResultSet result = connection.getMetaData().getColumns(null, null, table, column)) {
            return result.next();
        }
    }

    public static void closeConnection() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        dataSource = null;
    }

    private static int readLegacyVersion() {
        try (Connection connection = getConnection()) {
            return readLegacyVersion(connection);
        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.WARNING, "Could not read legacy migration version; Flyway will inspect the schema", e);
            return 0;
        }
    }

    private static int readLegacyVersion(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(connection.getCatalog(), null,
                "profitable_database_version", new String[]{"TABLE"})) {
            if (!tables.next()) {
                return 0;
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT MAX(version) FROM profitable_database_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static void verifyConnection() throws SQLException {
        try (Connection connection = getConnection()) {
            if (!connection.isValid(5)) {
                throw new SQLException("Database connection validation failed");
            }
        }
    }

    private static String required(String value, String path) throws SQLException {
        if (value == null || value.isBlank()) {
            throw new SQLException("Missing required configuration: " + path);
        }
        return value.trim();
    }
}
