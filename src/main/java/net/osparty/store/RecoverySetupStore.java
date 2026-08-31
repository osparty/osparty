package net.osparty.store;

import com.google.gson.Gson;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;

/**
 * Which characters on this install still owe the user a recovery-setup moment, in
 * {@code <runelite>/osparty/recovery-setup.json}.
 *
 * <p>Enrolment is silent, and the codes that arrive with it are no longer pushed at the player the
 * moment they land — they are offered from the panel, whenever the player chooses to look. This file
 * is what keeps that offer alive across restarts: the flag is set when a first credential arrives and
 * cleared only when the player has actually saved codes or linked Discord. Losing the file costs a
 * reminder, not access — the codes themselves can be reissued from any signed-in device.
 *
 * <p>Beside {@link CredentialStore} rather than in the plugin config for the same reason: config is
 * synced off this machine, and although a "still needs setup" bit is hardly a secret, account state
 * that only describes this install has no business travelling.
 */
@Singleton
public class RecoverySetupStore
{
	private static final String FILE_NAME = "recovery-setup.json";
	private static final int SCHEMA_VERSION = 1;

	private final JsonFile<Data> store;
	/** Accounts enrolled here whose recovery setup the user has not finished. Guarded by the instance monitor. */
	private final Set<Long> pending = new HashSet<>();
	/**
	 * Accounts whose user confirmed saving a codes sheet. Kept apart from {@link #pending} because the
	 * two routes are not equally durable: a Discord link can be unlinked later, and when that happens the
	 * only question that matters is whether codes were ever actually saved — the server can't answer it
	 * (an unspent sheet looks the same whether it's in a drawer or was never looked at).
	 */
	private final Set<Long> codesSaved = new HashSet<>();

	@Inject
	public RecoverySetupStore(Gson gson)
	{
		this(new File(RuneLite.RUNELITE_DIR, "osparty"), gson);
	}

	/** Test/embeddable entry point: read (or create) the store in {@code dir}. */
	public RecoverySetupStore(File dir, Gson gson)
	{
		this.store = new JsonFile<>(dir, FILE_NAME, Data.class, SCHEMA_VERSION, gson);
		load();
	}

	/** On-disk shape: a version tag, the accounts still awaiting setup, and those with a saved sheet. */
	private static final class Data implements JsonFile.Versioned
	{
		int version = SCHEMA_VERSION;
		Set<Long> pending = new HashSet<>();
		Set<Long> codesSaved = new HashSet<>();

		@Override
		public int version()
		{
			return version;
		}
	}

	private synchronized void load()
	{
		pending.clear();
		codesSaved.clear();
		Data data = store.read();
		if (data == null)
		{
			return;
		}
		if (data.pending != null)
		{
			pending.addAll(data.pending);
		}
		if (data.codesSaved != null)
		{
			codesSaved.addAll(data.codesSaved);
		}
	}

	/** Whether {@code accountHash} enrolled here and has not yet saved codes or linked Discord. */
	public synchronized boolean isPending(long accountHash)
	{
		return AccountHash.isKnown(accountHash) && pending.contains(accountHash);
	}

	/** A first credential just arrived for {@code accountHash}; keep offering setup until it is done. */
	public synchronized void markPending(long accountHash)
	{
		if (AccountHash.isKnown(accountHash) && pending.add(accountHash))
		{
			save();
		}
	}

	/** The user finished setup for {@code accountHash} — stop offering it. */
	public synchronized void clearPending(long accountHash)
	{
		if (pending.remove(accountHash))
		{
			save();
		}
	}

	/** The user confirmed saving {@code accountHash}'s codes sheet. Survives later regenerations: those
	 * are user-initiated from a dialog whose whole purpose is the new sheet. */
	public synchronized void markCodesSaved(long accountHash)
	{
		if (AccountHash.isKnown(accountHash) && codesSaved.add(accountHash))
		{
			save();
		}
	}

	/** Whether the user has ever confirmed saving a codes sheet for {@code accountHash}. */
	public synchronized boolean hasSavedCodes(long accountHash)
	{
		return codesSaved.contains(accountHash);
	}

	private void save()
	{
		Data data = new Data();
		data.pending = new HashSet<>(pending);
		data.codesSaved = new HashSet<>(codesSaved);
		store.write(data);
	}
}
