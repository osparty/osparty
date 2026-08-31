package net.osparty.store;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The pending-setup flag is what keeps the panel's recovery offer alive across restarts, so the part
 * worth pinning is persistence: a mark survives a reopen, a clear survives a reopen, and the
 * logged-out sentinels never leave a flag that every character would then share.
 */
public class RecoverySetupStoreTest
{
	private File dir;
	private RecoverySetupStore store;

	@Before
	public void setUp() throws Exception
	{
		dir = Files.createTempDirectory("osparty-recovery-setup-test").toFile();
		store = new RecoverySetupStore(dir, new Gson());
	}

	@Test
	public void markAndClear()
	{
		assertFalse(store.isPending(42L));
		store.markPending(42L);
		assertTrue(store.isPending(42L));
		assertFalse("other accounts unaffected", store.isPending(43L));

		store.clearPending(42L);
		assertFalse(store.isPending(42L));
	}

	@Test
	public void pendingSurvivesReopen()
	{
		store.markPending(42L);
		assertTrue(new RecoverySetupStore(dir, new Gson()).isPending(42L));
	}

	@Test
	public void clearSurvivesReopen()
	{
		store.markPending(42L);
		store.clearPending(42L);
		assertFalse(new RecoverySetupStore(dir, new Gson()).isPending(42L));
	}

	@Test
	public void loggedOutSentinelsAreNeverPending()
	{
		store.markPending(0L);
		store.markPending(-1L);
		assertFalse(store.isPending(0L));
		assertFalse(store.isPending(-1L));
	}

	@Test
	public void clearingWhatWasNeverMarkedIsFine()
	{
		store.clearPending(42L);
		assertFalse(store.isPending(42L));
	}

	@Test
	public void savedCodesSurviveReopenAndAreIndependentOfPending()
	{
		assertFalse(store.hasSavedCodes(42L));
		store.markCodesSaved(42L);
		store.clearPending(42L);

		RecoverySetupStore reopened = new RecoverySetupStore(dir, new Gson());
		assertTrue(reopened.hasSavedCodes(42L));
		assertFalse(reopened.isPending(42L));

		// The unlink flow re-pends an account; its saved sheet is not forgotten by that.
		reopened.markPending(42L);
		assertTrue(reopened.hasSavedCodes(42L));
	}

	@Test
	public void aFileWrittenBeforeCodesSavedExistedStillLoads()
	{
		store.markPending(42L);
		assertFalse(new RecoverySetupStore(dir, new Gson()).hasSavedCodes(42L));
	}
}
