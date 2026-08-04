/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * MySQL-backed {@link Catalog}: one {@code images} table, one row per
 * filename, holding the {@link CatalogEntry} as a native {@code JSON} column
 * and the thumbnail as a {@code MEDIUMBLOB} — no ORM, no migration
 * framework, just JDBC and one {@code CREATE TABLE IF NOT EXISTS} run at
 * construction.
 * <p>
 * Holds a single {@link Connection} for the lifetime of the instance;
 * methods are {@code synchronized} to keep that connection from being used
 * concurrently. This is not a connection pool — fine for the single-user
 * desktop use this project targets, worth revisiting if it's ever used
 * concurrently from more than a couple of threads.
 * </p>
 * <p>
 * Each method retries once, reconnecting first, if the connection has gone
 * bad — real MySQL, on real hardware, drops connections (a restart, a
 * network blip) independently of anything this app did wrong. This is not
 * failover: it's still the one {@link Config.Db} endpoint, just tolerant of
 * that endpoint hiccuping mid-run instead of failing the operation outright.
 * </p>
 */
public class MySqlCatalog implements Catalog {

    private final Config.Db db;
    private Connection conn;

    /**
     * Opens the connection named by {@code db} and ensures the {@code images}
     * table exists.
     *
     * @param db connection parameters, normally {@link Config#db}
     * @throws IOException if the connection or table setup fails
     */
    public MySqlCatalog(Config.Db db) throws IOException {
        this.db = db;
        try {
            conn = connect();
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS images ("
                        + "filename VARCHAR(255) PRIMARY KEY, "
                        + "data JSON NOT NULL, "
                        + "thumbnail MEDIUMBLOB)");
            }
        } catch (SQLException ex) {
            throw new IOException("Could not open MySQL catalog at " + db.host + ":" + db.port, ex);
        }
    }

    private Connection connect() throws SQLException {
        String url = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC",
                db.host, db.port, db.database);
        return DriverManager.getConnection(url, db.user, db.password);
    }

    /**
     * Reconnects if the held connection is no longer usable. Called before
     * the first attempt of every operation and again before its retry, so a
     * connection that died between calls (rather than mid-call) is caught
     * just as well as one that dies during the call itself.
     */
    private void reconnectIfBroken() throws SQLException {
        if (null != conn && conn.isValid(2)) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // already broken; nothing to clean up
        }
        conn = connect();
    }

    @Override
    public synchronized void save(CatalogEntry entry, BufferedImage thumbnail) throws IOException {
        byte[] png = null;
        if (null != thumbnail) {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(thumbnail, "png", bos);
                png = bos.toByteArray();
            } catch (IOException ex) {
                throw new IOException("Could not encode thumbnail for " + entry.filename, ex);
            }
        }
        String json = JSON.writeValueAsString(entry);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                reconnectIfBroken();
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO images (filename, data, thumbnail) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE data = VALUES(data), thumbnail = VALUES(thumbnail)")) {
                    ps.setString(1, entry.filename);
                    ps.setString(2, json);
                    ps.setBytes(3, png);
                    ps.executeUpdate();
                }
                return;
            } catch (SQLException ex) {
                if (attempt == 2) {
                    throw new IOException("Could not save catalog entry " + entry.filename, ex);
                }
            }
        }
    }

    @Override
    public synchronized CatalogEntry loadEntry(String filename) throws IOException {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                reconnectIfBroken();
                try (PreparedStatement ps = conn.prepareStatement("SELECT data FROM images WHERE filename = ?")) {
                    ps.setString(1, filename);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return null;
                        }
                        return JSON.readValue(rs.getString(1), CatalogEntry.class);
                    }
                }
            } catch (SQLException ex) {
                if (attempt == 2) {
                    throw new IOException("Could not load catalog entry " + filename, ex);
                }
            }
        }
        return null;
    }

    @Override
    public synchronized List<CatalogEntry> listAll() throws IOException {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                reconnectIfBroken();
                try (Statement st = conn.createStatement();
                        ResultSet rs = st.executeQuery("SELECT data FROM images")) {
                    List<CatalogEntry> all = new ArrayList<>();
                    while (rs.next()) {
                        all.add(JSON.readValue(rs.getString(1), CatalogEntry.class));
                    }
                    return all;
                }
            } catch (SQLException ex) {
                if (attempt == 2) {
                    throw new IOException("Could not list catalog entries", ex);
                }
            }
        }
        return null;
    }

    @Override
    public synchronized BufferedImage loadThumbnail(String filename) throws IOException {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                reconnectIfBroken();
                try (PreparedStatement ps = conn.prepareStatement("SELECT thumbnail FROM images WHERE filename = ?")) {
                    ps.setString(1, filename);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return null;
                        }
                        byte[] png = rs.getBytes(1);
                        if (null == png) {
                            return null;
                        }
                        return ImageIO.read(new ByteArrayInputStream(png));
                    }
                }
            } catch (SQLException ex) {
                if (attempt == 2) {
                    throw new IOException("Could not load thumbnail " + filename, ex);
                }
            }
        }
        return null;
    }

    /**
     * Prefix shared by every checkpoint table, e.g. {@code images_ckpt_1770326400000}.
     * The suffix is the epoch-millis timestamp the checkpoint was taken at,
     * so the latest checkpoint is just the lexically/numerically greatest
     * suffix.
     */
    private static final String CHECKPOINT_PREFIX = "images_ckpt_";

    @Override
    public synchronized void checkpoint() throws IOException {
        String table = CHECKPOINT_PREFIX + System.currentTimeMillis();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                reconnectIfBroken();
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE " + table + " AS SELECT * FROM images");
                }
                return;
            } catch (SQLException ex) {
                if (attempt == 2) {
                    throw new IOException("Could not create checkpoint " + table, ex);
                }
            }
        }
    }

    @Override
    public synchronized void restoreLatestCheckpoint() throws IOException {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                reconnectIfBroken();
                String latest = latestCheckpointTable();
                if (null == latest) {
                    throw new IllegalStateException("No checkpoint to restore");
                }
                boolean autoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try (Statement st = conn.createStatement()) {
                    st.execute("DELETE FROM images");
                    st.execute("INSERT INTO images SELECT * FROM " + latest);
                    conn.commit();
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(autoCommit);
                }
                return;
            } catch (SQLException ex) {
                if (attempt == 2) {
                    throw new IOException("Could not restore latest checkpoint", ex);
                }
            }
        }
    }

    /**
     * @return the table name of the most recent checkpoint, or {@code null}
     * if none exists
     */
    private String latestCheckpointTable() throws SQLException {
        String latest = null;
        long latestMillis = -1;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_name LIKE ?")) {
            ps.setString(1, db.database);
            ps.setString(2, CHECKPOINT_PREFIX + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    try {
                        long millis = Long.parseLong(name.substring(CHECKPOINT_PREFIX.length()));
                        if (millis > latestMillis) {
                            latestMillis = millis;
                            latest = name;
                        }
                    } catch (NumberFormatException ignored) {
                        // not one of ours; skip it
                    }
                }
            }
        }
        return latest;
    }
}
