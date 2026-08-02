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
 */
public class MySqlCatalog implements Catalog {

    private final Connection conn;

    /**
     * Opens the connection named by {@code db} and ensures the {@code images}
     * table exists.
     *
     * @param db connection parameters, normally {@link Config#db}
     * @throws IOException if the connection or table setup fails
     */
    public MySqlCatalog(Config.Db db) throws IOException {
        try {
            String url = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC",
                    db.host, db.port, db.database);
            conn = DriverManager.getConnection(url, db.user, db.password);
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

    @Override
    public synchronized void save(CatalogEntry entry, BufferedImage thumbnail) throws IOException {
        try {
            byte[] png = null;
            if (null != thumbnail) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(thumbnail, "png", bos);
                png = bos.toByteArray();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO images (filename, data, thumbnail) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE data = VALUES(data), thumbnail = VALUES(thumbnail)")) {
                ps.setString(1, entry.filename);
                ps.setString(2, JSON.writeValueAsString(entry));
                ps.setBytes(3, png);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new IOException("Could not save catalog entry " + entry.filename, ex);
        }
    }

    @Override
    public synchronized CatalogEntry loadEntry(String filename) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT data FROM images WHERE filename = ?")) {
            ps.setString(1, filename);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return JSON.readValue(rs.getString(1), CatalogEntry.class);
            }
        } catch (SQLException ex) {
            throw new IOException("Could not load catalog entry " + filename, ex);
        }
    }

    @Override
    public synchronized BufferedImage loadThumbnail(String filename) throws IOException {
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
        } catch (SQLException ex) {
            throw new IOException("Could not load thumbnail " + filename, ex);
        }
    }
}
