package net.osparty.store;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * H2-backed {@link PartyStore}. Uses a single embedded database file under
 * {@code <runelite>/osparty/osparty.mv.db}, opened once for the plugin session and
 * guarded by a monitor (RuneLite reads/writes these lists from the EDT at human
 * speed, so a single synchronized connection is more than enough).
 *
 * <p>The schema is versioned through {@link #SCHEMA_VERSION} and a {@code schema_meta}
 * table so party-history tables can be added later without clobbering existing data.
 */
@Slf4j
@Singleton
public class H2PartyStore implements PartyStore
{
	/** Bump when {@link #migrate} gains a new step. */
	private static final int SCHEMA_VERSION = 1;

	private final Connection conn;

	@Inject
	public H2PartyStore()
	{
		this(new File(RuneLite.RUNELITE_DIR, "osparty"));
	}

	/** Test/embeddable entry point: open (or create) the database in {@code dir}. */
	public H2PartyStore(File dir)
	{
		this.conn = open(dir);
		try
		{
			migrate();
		}
		catch (SQLException e)
		{
			throw new IllegalStateException("Failed to initialise OSParty database", e);
		}
	}

	private static Connection open(File dir)
	{
		if (!dir.exists() && !dir.mkdirs())
		{
			throw new IllegalStateException("Could not create OSParty data dir: " + dir);
		}
		String path = new File(dir, "osparty").getAbsolutePath().replace('\\', '/');
		// DB_CLOSE_ON_EXIT=FALSE: we close explicitly on plugin shutdown, not via a
		// shutdown hook that could race the RuneLite lifecycle.
		String url = "jdbc:h2:file:" + path + ";DB_CLOSE_ON_EXIT=FALSE";
		try
		{
			// Open via H2's JdbcDataSource, not DriverManager: under RuneLite's isolated plugin
			// classloader the JDBC ServiceLoader auto-registration never runs, so DriverManager reports
			// "No suitable driver found". Referencing the H2 class directly sidesteps that (the same
			// approach the Polywoof hub plugin uses).
			org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
			ds.setURL(url);
			ds.setUser("osparty");
			ds.setPassword("");
			Connection c = ds.getConnection();
			c.setAutoCommit(true);
			return c;
		}
		catch (SQLException e)
		{
			throw new IllegalStateException("Could not open OSParty database at " + path, e);
		}
	}

	private void migrate() throws SQLException
	{
		try (Statement st = conn.createStatement())
		{
			st.execute("CREATE TABLE IF NOT EXISTS schema_meta (version INT NOT NULL)");
		}

		int current;
		try (Statement st = conn.createStatement();
			ResultSet rs = st.executeQuery("SELECT version FROM schema_meta"))
		{
			current = rs.next() ? rs.getInt(1) : 0;
		}

		if (current < 1)
		{
			try (Statement st = conn.createStatement())
			{
				// account_hash = -1 means "unknown" (matching falls back to username). Real
				// hashes use the full signed-long range, so -1 is an exact sentinel, not "<= 0".
				st.execute("CREATE TABLE IF NOT EXISTS player_flag ("
					+ "kind VARCHAR(16) NOT NULL,"
					+ "account_hash BIGINT NOT NULL,"
					+ "username VARCHAR(64) NOT NULL,"
					+ "updated_at BIGINT NOT NULL)");
				st.execute("CREATE INDEX IF NOT EXISTS idx_player_flag_kind ON player_flag(kind)");
			}
		}
		// Future migrations: if (current < 2) { ... }

		if (current != SCHEMA_VERSION)
		{
			try (Statement st = conn.createStatement())
			{
				st.execute("DELETE FROM schema_meta");
				st.execute("INSERT INTO schema_meta (version) VALUES (" + SCHEMA_VERSION + ")");
			}
		}
	}

	@Override
	public synchronized List<PlayerFlag> loadFlags(FlagKind kind)
	{
		List<PlayerFlag> out = new ArrayList<>();
		try (PreparedStatement ps = conn.prepareStatement(
			"SELECT account_hash, username FROM player_flag WHERE kind = ?"))
		{
			ps.setString(1, kind.name());
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					out.add(new PlayerFlag(rs.getLong(1), rs.getString(2)));
				}
			}
		}
		catch (SQLException e)
		{
			log.warn("OSParty: failed to load {} flags", kind, e);
		}
		return out;
	}

	@Override
	public synchronized void upsertFlag(FlagKind kind, PlayerFlag flag)
	{
		try
		{
			if (flag.hasKnownHash())
			{
				// Replace any existing hash row and fold in a stale name-only row (backfill).
				delete("DELETE FROM player_flag WHERE kind = ? AND (account_hash = ? "
						+ "OR (account_hash = -1 AND LOWER(username) = ?))",
					kind, flag.getAccountHash(), flag.getUsername());
			}
			else
			{
				delete("DELETE FROM player_flag WHERE kind = ? AND account_hash = -1 AND LOWER(username) = ?",
					kind, null, flag.getUsername());
			}
			try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO player_flag (kind, account_hash, username, updated_at) VALUES (?, ?, ?, ?)"))
			{
				ps.setString(1, kind.name());
				ps.setLong(2, flag.getAccountHash());
				ps.setString(3, flag.getUsername());
				ps.setLong(4, System.currentTimeMillis());
				ps.executeUpdate();
			}
		}
		catch (SQLException e)
		{
			log.warn("OSParty: failed to upsert {} flag", kind, e);
		}
	}

	@Override
	public synchronized void removeFlag(FlagKind kind, PlayerFlag flag)
	{
		try
		{
			if (flag.hasKnownHash())
			{
				delete("DELETE FROM player_flag WHERE kind = ? AND account_hash = ?",
					kind, flag.getAccountHash(), null);
			}
			else
			{
				delete("DELETE FROM player_flag WHERE kind = ? AND account_hash = -1 AND LOWER(username) = ?",
					kind, null, flag.getUsername());
			}
		}
		catch (SQLException e)
		{
			log.warn("OSParty: failed to remove {} flag", kind, e);
		}
	}

	/**
	 * Run a delete whose placeholders are, in order: kind, then optionally a hash
	 * ({@code hash != null}) and/or a lowercased username ({@code username != null}).
	 */
	private void delete(String sql, FlagKind kind, Long hash, String username) throws SQLException
	{
		try (PreparedStatement ps = conn.prepareStatement(sql))
		{
			int i = 1;
			ps.setString(i++, kind.name());
			if (hash != null)
			{
				ps.setLong(i++, hash);
			}
			if (username != null)
			{
				ps.setString(i, username.toLowerCase());
			}
			ps.executeUpdate();
		}
	}

	@Override
	public synchronized void close()
	{
		try
		{
			if (!conn.isClosed())
			{
				conn.close();
			}
		}
		catch (SQLException e)
		{
			log.warn("OSParty: error closing database", e);
		}
	}
}
