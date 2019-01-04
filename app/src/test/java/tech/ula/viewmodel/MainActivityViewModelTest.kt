package tech.ula.viewmodel

import android.arch.core.executor.testing.InstantTaskExecutorRule
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Observer
import com.nhaarman.mockitokotlin2.* // ktlint-disable no-wildcard-imports
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import tech.ula.model.entities.App
import tech.ula.model.entities.Asset
import tech.ula.model.entities.Filesystem
import tech.ula.model.entities.Session
import tech.ula.model.state.* // ktlint-disable no-wildcard-imports
import tech.ula.utils.SshTypePreference

@RunWith(MockitoJUnitRunner::class)
class MainActivityViewModelTest {

    @get:Rule val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Mock lateinit var mockAppsStartupFsm: AppsStartupFsm

    @Mock lateinit var mockSessionStartupFsm: SessionStartupFsm

    @Mock lateinit var mockStateObserver: Observer<State>

    // Test variables
    private val selectedApp = App(name = "app")
    private val selectedSession = Session(id = 0, filesystemId = 0)
    private val selectedFilesystem = Filesystem(0)

    private val unselectedApp = App(name = "UNSELECTED")
    private val unselectedSession = Session(id = -1, name = "UNSELECTED", filesystemId = -1)
    private val unselectedFilesystem = Filesystem(id = -1, name = "UNSELECTED")

    private val asset = Asset(name = "asset", architectureType = "arch", distributionType = "dist", remoteTimestamp = 0)

    private lateinit var appsStartupStateLiveData: MutableLiveData<AppsStartupState>

    private lateinit var sessionStartupStateLiveData: MutableLiveData<SessionStartupState>

    private lateinit var mainActivityViewModel: MainActivityViewModel

    private fun makeAppSelections() {
        mainActivityViewModel.lastSelectedApp = selectedApp
        mainActivityViewModel.lastSelectedSession = selectedSession
        mainActivityViewModel.lastSelectedFilesystem = selectedFilesystem
    }

    private fun makeSessionSelections() {
        mainActivityViewModel.lastSelectedSession = selectedSession
        mainActivityViewModel.lastSelectedFilesystem = selectedFilesystem
    }

    @Before
    fun setup() {
        appsStartupStateLiveData = MutableLiveData()
        whenever(mockAppsStartupFsm.getState()).thenReturn(appsStartupStateLiveData)
        appsStartupStateLiveData.postValue(WaitingForAppSelection)

        sessionStartupStateLiveData = MutableLiveData()
        whenever(mockSessionStartupFsm.getState()).thenReturn(sessionStartupStateLiveData)
        sessionStartupStateLiveData.postValue(WaitingForSessionSelection)

        mainActivityViewModel = MainActivityViewModel(mockAppsStartupFsm, mockSessionStartupFsm)
        mainActivityViewModel.getState().observeForever(mockStateObserver)
    }

    @Test
    fun `State does not initially publish onChanged event`() {
        verify(mockStateObserver, never()).onChanged(any())
    }

    @Test
    fun `Restarts app setup after permissions are granted if an app was selected first`() = runBlocking {
        mainActivityViewModel.waitForPermissions(appToContinue = selectedApp)
        mainActivityViewModel.permissionsHaveBeenGranted()
        verify(mockAppsStartupFsm).submitEvent(AppSelected(selectedApp))
    }

    @Test
    fun `Restarts session setup after permissions are granted if a session was selected first`() = runBlocking {
        mainActivityViewModel.waitForPermissions(sessionToContinue = selectedSession)
        mainActivityViewModel.permissionsHaveBeenGranted()
        verify(mockSessionStartupFsm).submitEvent(SessionSelected(selectedSession))
    }

    @Test
    fun `Posts IllegalState if both a session and app have been selected when permissions are granted`() {
        mainActivityViewModel.waitForPermissions(selectedApp, selectedSession)
        mainActivityViewModel.permissionsHaveBeenGranted()

        verify(mockStateObserver).onChanged(IllegalState("Both a session and an app have been selected when permissions are granted"))
    }

    @Test
    fun `Posts IllegalState if neither a session nor an app has been selected when permissions are granted`() {
        mainActivityViewModel.waitForPermissions()
        mainActivityViewModel.permissionsHaveBeenGranted()

        verify(mockStateObserver).onChanged(IllegalState("Neither a session nor app have been selected when permissions are granted."))
    }

    @Test
    fun `Apps and sessions cannot be selected if apps state is not WaitingForAppSelection`() = runBlocking {
        appsStartupStateLiveData.postValue(FetchingDatabaseEntries)

        mainActivityViewModel.submitAppSelection(selectedApp)
        mainActivityViewModel.submitSessionSelection(selectedSession)

        verify(mockAppsStartupFsm, never()).submitEvent(AppSelected(selectedApp))
        verify(mockSessionStartupFsm, never()).submitEvent(SessionSelected(selectedSession))
    }

    @Test
    fun `Apps and sessions cannot be selected if session state is not WaitingForSessionSelection`() = runBlocking {
        sessionStartupStateLiveData.postValue(SessionIsReadyForPreparation(selectedSession, Filesystem(id = 0)))

        mainActivityViewModel.submitAppSelection(selectedApp)
        mainActivityViewModel.submitSessionSelection(selectedSession)

        verify(mockAppsStartupFsm, never()).submitEvent(AppSelected(selectedApp))
        verify(mockSessionStartupFsm, never()).submitEvent(SessionSelected(selectedSession))
    }

    @Test
    fun `Submits app selection if selections can be made`() = runBlocking {
        // App and session state are initialized to waiting for selection
        mainActivityViewModel.submitAppSelection(selectedApp)

        verify(mockAppsStartupFsm).submitEvent(AppSelected(selectedApp))
    }

    @Test
    fun `Submits session selection if selections can be made`() = runBlocking {
        // App and session state are initialized to waiting for selection
        mainActivityViewModel.submitSessionSelection(selectedSession)

        verify(mockSessionStartupFsm).submitEvent(SessionSelected(selectedSession))
    }

    @Test
    fun `Submits completed download ids`() = runBlocking {
        mainActivityViewModel.submitCompletedDownloadId(1)

        verify(mockSessionStartupFsm).submitEvent(AssetDownloadComplete(1))
    }

    @Test
    fun `Posts IllegalState if filesystem credentials are submitted while no filesystem is selected`() {
        mainActivityViewModel.submitFilesystemCredentials("", "", "")

        verify(mockStateObserver).onChanged(IllegalState("Submitting credentials for an unselected filesystem"))
    }

    @Test
    fun `Submits filesystem credentials for last selected filesystem`() = runBlocking {
        val username = "user"
        val password = "pass"
        val vncPassword = "vnc"
        mainActivityViewModel.lastSelectedFilesystem = selectedFilesystem

        mainActivityViewModel.submitFilesystemCredentials(username, password, vncPassword)

        verify(mockAppsStartupFsm).submitEvent(SubmitAppsFilesystemCredentials(selectedFilesystem, username, password, vncPassword))
    }

    @Test
    fun `Posts IllegalState if service preference submitted while no app is selected`() {
        mainActivityViewModel.submitAppServicePreference(SshTypePreference)

        verify(mockStateObserver).onChanged(IllegalState("Submitting a preference for an unselected app"))
    }

    @Test
    fun `Submits app service preference for last selected app`() = runBlocking {
        mainActivityViewModel.lastSelectedApp = selectedApp

        mainActivityViewModel.submitAppServicePreference(SshTypePreference)

        verify(mockAppsStartupFsm).submitEvent(SubmitAppServicePreference(selectedApp, SshTypePreference))
    }

    @Test
    fun `Resets startup state when user input is cancelled`() = runBlocking {
        mainActivityViewModel.lastSelectedApp = selectedApp
        mainActivityViewModel.lastSelectedSession = selectedSession
        mainActivityViewModel.lastSelectedFilesystem = selectedFilesystem

        mainActivityViewModel.handleUserInputCancelled()

        assertEquals(mainActivityViewModel.lastSelectedApp, unselectedApp)
        assertEquals(mainActivityViewModel.lastSelectedSession, unselectedSession)
        assertEquals(mainActivityViewModel.lastSelectedFilesystem, unselectedFilesystem)

        verify(mockAppsStartupFsm).submitEvent(ResetAppState)
        verify(mockSessionStartupFsm).submitEvent(ResetSessionState)
    }

    @Test
    fun `Submits DownloadAssets event`() = runBlocking {
        val downloads = listOf(asset)

        mainActivityViewModel.startAssetDownloads(downloads)

        verify(mockSessionStartupFsm).submitEvent(DownloadAssets(downloads))
    }

    @Test
    fun `Does not post IllegalState if app, session, and filesystem have not been selected and observed event is WaitingForAppSelection`() {
        appsStartupStateLiveData.postValue(WaitingForAppSelection)

        verify(mockStateObserver, never()).onChanged(IllegalState("Trying to handle app preparation before it has been selected."))
    }

    @Test
    fun `Does not post IllegalState if app, session, and filesystem have not been selected and observed event is FetchingDatabaseEntries`() {
        appsStartupStateLiveData.postValue(FetchingDatabaseEntries)

        verify(mockStateObserver, never()).onChanged(IllegalState("Trying to handle app preparation before it has been selected."))
    }

    @Test
    fun `Posts IllegalState if app, session, and filesystem have not been selected and an app state event is observed that is not the above`() {
        appsStartupStateLiveData.postValue(DatabaseEntriesFetchFailed)

        verify(mockStateObserver).onChanged(IllegalState("Trying to handle app preparation before it has been selected."))
    }

    @Test
    fun `Posts IllegalState on incorrect app transitions`() {
        makeAppSelections()

        val event = SubmitAppServicePreference(selectedApp, SshTypePreference)
        val state = WaitingForAppSelection
        val badTransition = IncorrectAppTransition(event, state)
        appsStartupStateLiveData.postValue(IncorrectAppTransition(event, state))

        verify(mockStateObserver).onChanged(IllegalState("Bad state transition: $badTransition"))
    }

    @Test
    fun `Updates last selected session and filesystem and submits check credentials event when app database entries are fetched`() = runBlocking {
        makeAppSelections()

        appsStartupStateLiveData.postValue(DatabaseEntriesFetched(selectedFilesystem, selectedSession))

        assertEquals(selectedFilesystem, mainActivityViewModel.lastSelectedFilesystem)
        assertEquals(selectedSession, mainActivityViewModel.lastSelectedSession)

        verify(mockAppsStartupFsm).submitEvent(CheckAppsFilesystemCredentials(selectedFilesystem))
    }

    @Test
    fun `Posts IllegalState if app database entry fetch fails`() {
        makeAppSelections()

        appsStartupStateLiveData.postValue(DatabaseEntriesFetchFailed)

        verify(mockStateObserver).onChanged(IllegalState("Couldn't fetch apps database entries."))
    }

    @Test
    fun `Submit preference check event if observed event is filesystem has credentials`() = runBlocking {
        makeAppSelections()

        appsStartupStateLiveData.postValue(AppsFilesystemHasCredentials)

        verify(mockAppsStartupFsm).submitEvent(CheckAppServicePreference(selectedApp))
    }

    @Test
    fun `Posts FilesystemCredentialsRequired if observed event is filesystem requires credentials`() {
        makeAppSelections()

        appsStartupStateLiveData.postValue(AppsFilesystemRequiresCredentials(selectedFilesystem))

        verify(mockStateObserver).onChanged(FilesystemCredentialsRequired)
    }

    @Test
    fun `Submits CopyAppScript event if observed event is app has service preference set`() = runBlocking {
        makeAppSelections()

        appsStartupStateLiveData.postValue(AppHasServiceTypePreferenceSet)

        verify(mockAppsStartupFsm).submitEvent(CopyAppScriptToFilesystem(selectedApp, selectedFilesystem))
    }

    @Test
    fun `Posts PreferenceRequired if equivalent event observed`() {
        makeAppSelections()

        appsStartupStateLiveData.postValue(AppRequiresServiceTypePreference)

        verify(mockStateObserver).onChanged(AppServiceTypePreferenceRequired)
    }

    @Test
    fun `Submits SyncDataBaseEntries when observed event is app script copying succeeded`() = runBlocking {
        makeAppSelections()

        appsStartupStateLiveData.postValue(AppScriptCopySucceeded)

        verify(mockAppsStartupFsm).submitEvent(SyncDatabaseEntries(selectedApp, selectedSession, selectedFilesystem))
    }

    @Test
    fun `Posts IllegalState if app script copy fails`() {
        makeAppSelections()

        appsStartupStateLiveData.postValue(AppScriptCopyFailed)

        verify(mockStateObserver).onChanged(IllegalState("Couldn't copy app script."))
    }

    @Test
    fun `Updates session and filesystem and submits session selected event once database entries sync`() = runBlocking {
        makeAppSelections()

        val updatedSession = Session(0, name = "updated", filesystemId = 0)
        val updatedFilesystem = Filesystem(0, name = "updated")
        appsStartupStateLiveData.postValue(AppDatabaseEntriesSynced(selectedApp, updatedSession, updatedFilesystem))

        assertEquals(updatedSession, mainActivityViewModel.lastSelectedSession)
        assertEquals(updatedFilesystem, mainActivityViewModel.lastSelectedFilesystem)

        verify(mockSessionStartupFsm).submitEvent(SessionSelected(updatedSession))
    }

    @Test
    fun `Posts IllegalState if session preparation event is observed that is not WaitingForSelection and prep reqs have not been met`() {
        sessionStartupStateLiveData.postValue(SingleSessionSupported)

        verify(mockStateObserver).onChanged(IllegalState("Trying to handle session preparation before one has been selected."))
    }

    @Test
    fun `Posts IllegalState if observes incorrect session transition`() {
        makeSessionSelections()

        val event = SessionSelected(selectedSession)
        val state = SingleSessionSupported
        val badTransition = IncorrectSessionTransition(event, state)
        sessionStartupStateLiveData.postValue(badTransition)

        verify(mockStateObserver).onChanged(IllegalState("Bad state transition: $badTransition"))
    }

    @Test
    fun `Posts CanOnlyStartSingleSession if equivalent event observed`() {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(SingleSessionSupported)

        verify(mockStateObserver).onChanged(CanOnlyStartSingleSession)
    }

    @Test
    fun `Posts SessionIsRestartable when equivalent event observed`() {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(SessionIsRestartable(selectedSession))

        verify(mockStateObserver).onChanged(SessionCanBeRestarted(selectedSession))
    }

    @Test
    fun `Updates selected session and filesystem, posts StartingSetup, and submits RetrieveAssetLists when session is ready for prep is observed`() = runBlocking {
        sessionStartupStateLiveData.postValue(SessionIsReadyForPreparation(selectedSession, selectedFilesystem))

        assertEquals(selectedSession, mainActivityViewModel.lastSelectedSession)
        assertEquals(selectedFilesystem, mainActivityViewModel.lastSelectedFilesystem)

        verify(mockStateObserver).onChanged(StartingSetup)
        verify(mockSessionStartupFsm).submitEvent(RetrieveAssetLists(selectedFilesystem))
    }

    @Test
    fun `Posts FetchingAssetLists when equivalent state is observed`() {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(RetrievingAssetLists)

        verify(mockStateObserver).onChanged(FetchingAssetLists)
    }

    @Test
    fun `Submits GenerateDownload event if asset list retrieval success observed`() = runBlocking {
        makeSessionSelections()

        val assetLists = listOf(listOf(asset))
        sessionStartupStateLiveData.postValue(AssetListsRetrievalSucceeded(assetLists))

        verify(mockSessionStartupFsm).submitEvent(GenerateDownloads(selectedFilesystem, assetLists))
    }

    @Test
    fun `Posts IllegalState if asset list retrieval failure observed`() {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(AssetListsRetrievalFailed)

        verify(mockStateObserver).onChanged(IllegalState("Failed to retrieve asset lists."))
    }

    @Test
    fun `Posts CheckingForAssetsUpdates when generating download requirements is observed`() {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(GeneratingDownloadRequirements)

        verify(mockStateObserver).onChanged(CheckingForAssetsUpdates)
    }

    @Test
    fun `Posts LargeDownloadRequired if downloads are required and include a large one`() {
        makeSessionSelections()

        val downloads = listOf(asset)
        sessionStartupStateLiveData.postValue(DownloadsRequired(downloads, largeDownloadRequired = true))

        verify(mockStateObserver).onChanged(LargeDownloadRequired(downloads))
    }

    @Test
    fun `Submits DownloadAssets event if downloads are required but do not include a large one`() = runBlocking {
        makeSessionSelections()

        val downloads = listOf(asset)
        sessionStartupStateLiveData.postValue(DownloadsRequired(downloads, largeDownloadRequired = false))

        verify(mockSessionStartupFsm).submitEvent(DownloadAssets(downloads))
    }

    @Test
    fun `Submits extract filesystem event if no downloads are required`() = runBlocking {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(NoDownloadsRequired)

        verify(mockSessionStartupFsm).submitEvent(ExtractFilesystem(selectedFilesystem))
    }

    @Test
    fun `Posts DownloadProgress as it observes requirements downloading`() {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(DownloadingRequirements(0, 0))

        verify(mockStateObserver).onChanged(DownloadProgress(0, 0))
    }

    @Test
    fun `Submits CopyDownloadsToLocalStorage event when observing download success`() = runBlocking {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(DownloadsHaveSucceeded)

        verify(mockSessionStartupFsm).submitEvent(CopyDownloadsToLocalStorage)
    }

    @Test
    fun `Posts IllegalState if downloads fail`() {
        makeSessionSelections()

        val reason = "cause"
        sessionStartupStateLiveData.postValue(DownloadsHaveFailed(reason))

        verify(mockStateObserver).onChanged(IllegalState("Downloads have failed: $reason"))
    }

    @Test
    fun `Posts CopyingDownloads when equivalent state is observed`() {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(CopyingFilesToRequiredDirectories)

        verify(mockStateObserver).onChanged(CopyingDownloads)
    }

    @Test
    fun `Submits ExtractFilesystem event when copying success is observed`() = runBlocking {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(CopyingSucceeded)

        verify(mockSessionStartupFsm).submitEvent(ExtractFilesystem(selectedFilesystem))
    }

    @Test
    fun `Posts IllegalState when copying failure is observed`() {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(CopyingFailed)

        verify(mockStateObserver).onChanged(IllegalState("Failed to copy assets to local storage."))
    }

    @Test
    fun `Posts IllegalState if distribution copying failure is observed`() {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(DistributionCopyFailed)

        verify(mockStateObserver).onChanged(IllegalState("Failed to copy assets to filesystem."))
    }

    @Test
    fun `Posts FilesystemExtraction when observing filesystem extraction`() {
        makeSessionSelections()

        val target = "bullseye"
        sessionStartupStateLiveData.postValue(ExtractingFilesystem(target))

        verify(mockStateObserver).onChanged(FilesystemExtraction(target))
    }

    @Test
    fun `Submits VerifyFilesystemAssets when extraction success is observed`() = runBlocking {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(ExtractionSucceeded)

        verify(mockSessionStartupFsm).submitEvent(VerifyFilesystemAssets(selectedFilesystem))
    }

    @Test
    fun `Posts IllegalState when extraction failure is observed`() {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(ExtractionFailed)

        verify(mockStateObserver).onChanged(IllegalState("Failed to extract filesystem."))
    }

    @Test
    fun `Posts VerifyingFilesystem while observing filesystem verification`() {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(VerifyingFilesystemAssets)

        verify(mockStateObserver).onChanged(VerifyingFilesystem)
    }

    @Test
    fun `Posts SessionCanBeStarted and resets state when observing that filesystem has all required assets`() = runBlocking {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(FilesystemHasRequiredAssets)

        verify(mockStateObserver).onChanged(SessionCanBeStarted(selectedSession))
        assertEquals(unselectedApp, mainActivityViewModel.lastSelectedApp)
        assertEquals(unselectedSession, mainActivityViewModel.lastSelectedSession)
        assertEquals(unselectedFilesystem, mainActivityViewModel.lastSelectedFilesystem)
        verify(mockSessionStartupFsm).submitEvent(ResetSessionState)
        verify(mockAppsStartupFsm).submitEvent(ResetAppState)
    }

    @Test
    fun `Posts IllegalState when observing filesystem is missing assets`() {
        makeSessionSelections()

        sessionStartupStateLiveData.postValue(FilesystemIsMissingRequiredAssets)

        verify(mockStateObserver).onChanged(IllegalState("Filesystem is missing assets."))
    }
}