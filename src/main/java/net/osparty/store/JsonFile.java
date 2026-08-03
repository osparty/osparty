package net.osparty.store;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;

/**
 * One versioned JSON file under {@code <runelite>/osparty}, rewritten whole on every change.
 * Writes go through a sibling temp file and an atomic rename, so a crash, a full disk or a
 * serialisation failure can never leave a half-written file where the data used to be.
 */
@Slf4j
public final class JsonFile<T extends JsonFile.Versioned>
{
	/** Lets the file check its own schema version without knowing the shape of the data. */
	public interface Versioned
	{
		int version();
	}

	/** Derived from the client's shared Gson (never a fresh instance, the Plugin Hub forbids that). */
	private final Gson gson;
	private final File file;
	private final Class<T> type;
	private final int schemaVersion;
	/** Set when what's on disk came from a newer OSParty: read nothing, and write nothing over it. */
	private boolean foreignSchema;

	public JsonFile(File dir, String fileName, Class<T> type, int schemaVersion, Gson gson)
	{
		if (!dir.exists() && !dir.mkdirs())
		{
			throw new IllegalStateException("Could not create OSParty data dir: " + dir);
		}
		this.gson = gson.newBuilder().setPrettyPrinting().create();
		this.file = new File(dir, fileName);
		this.type = type;
		this.schemaVersion = schemaVersion;
	}

	/** @return the parsed contents, or {@code null} when there is nothing usable to load. */
	public T read()
	{
		if (!file.exists())
		{
			return null;
		}
		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			T data = gson.fromJson(reader, type);
			if (data == null)
			{
				return null;
			}
			if (data.version() > schemaVersion)
			{
				// Downgrade: we'd read it wrong and then overwrite it, so leave the file alone.
				foreignSchema = true;
				log.warn("OSParty: {} is schema v{}, newer than this plugin's v{}; leaving it untouched",
					file, data.version(), schemaVersion);
				return null;
			}
			return data;
		}
		catch (Exception e)
		{
			log.warn("OSParty: could not read {}, starting empty", file, e);
			return null;
		}
	}

	public void write(T data)
	{
		if (foreignSchema)
		{
			return;
		}
		Path target = file.toPath();
		Path temp = target.resolveSibling(file.getName() + ".tmp");
		try
		{
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8))
			{
				gson.toJson(data, writer);
			}
			try
			{
				Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (Exception e)
		{
			// Gson throws unchecked, so catching IOException alone would let a serialisation
			// failure escape with the half-written temp file still on disk.
			log.warn("OSParty: failed to write {}", file, e);
			try
			{
				Files.deleteIfExists(temp);
			}
			catch (IOException ignored)
			{
				// nothing useful to do; the live file is intact either way
			}
		}
	}
}
