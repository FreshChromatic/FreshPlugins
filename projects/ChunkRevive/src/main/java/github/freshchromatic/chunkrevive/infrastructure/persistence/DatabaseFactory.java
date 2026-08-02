package github.freshchromatic.chunkrevive.infrastructure.persistence;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.freshlib.database.Database;
import github.freshchromatic.freshlib.database.MySqlDatabase;
import github.freshchromatic.freshlib.database.SqliteDatabase;
import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class DatabaseFactory {

    private DatabaseFactory() {}

    public static Database create(JavaPlugin plugin, PluginConfig.Database cfg) {
        if ("mysql".equalsIgnoreCase(cfg.type)) {
            Logging.logger().info("Using MySQL database at " + cfg.mysql.host + ":" + cfg.mysql.port);
            return new MySqlDatabase(
                cfg.mysql.host,
                String.valueOf(cfg.mysql.port),
                cfg.mysql.database,
                cfg.mysql.username,
                cfg.mysql.password
            );
        }
        File dbFile = new File(plugin.getDataFolder(), cfg.sqlite.file);
        dbFile.getParentFile().mkdirs();
        Logging.logger().info("Using SQLite database at " + dbFile.getPath());
        return new SqliteDatabase(dbFile.getAbsolutePath());
    }
}
