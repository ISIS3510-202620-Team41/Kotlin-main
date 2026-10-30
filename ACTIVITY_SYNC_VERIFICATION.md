# Activity synchronization verification

Automated coverage:

- `ActivitySynchronizationTest`: online detail replaces stale memory and disk; successful join refreshes server participant count; HTTP 409 succeeds only with server-confirmed membership; failed verification never uses cached membership; pending enrollment survives refresh without count inflation; server confirmation clears pending status.
- `ActivityPersistenceTest` (Android emulator): writes both pending and confirmed enrollment to a real Room database, closes it, clears the memory cache, reopens it, and restores membership/count offline without calling the API.

Run with PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest --offline '-Pandroid.testInstrumentationRunnerArguments.class=com.group41.kotlinapp.ActivityPersistenceTest'
```

Authenticated backend/device checks still required (not established by repository tests):

1. Open an unjoined activity online and join. Confirm server participant count, disabled gray button, and updated detail after leaving and returning.
2. Force-stop and reopen the app, online and then offline. Confirm the enrollment and last known count remain visible.
3. Join another cached activity offline. Confirm pending label, disabled gray button, and one local participant increment; force-stop/reopen while offline and confirm persistence.
4. Restore connectivity. Confirm the queue drains, pending clears, and server count appears without another increment. Exercise both already-enrolled and full/closed HTTP 409 responses.
5. Create an activity online and offline, then synchronize. Confirm recommendations refresh on return and when synchronization finishes while Home remains open.
6. If the created activity is absent, inspect the fresh recommendation response using the same coordinates, radius, timezone, and user schedule. The app preserves backend eligibility/ranking filters and does not insert an ineligible activity into the recommendation list. Home currently uses the existing fixed coordinates (4.6382, -74.0840).

Database reopen tests simulate persistence loss of memory; they do not force-stop the application process or verify live backend eligibility rules.
