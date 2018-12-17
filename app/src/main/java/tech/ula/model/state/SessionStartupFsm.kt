package tech.ula.model.state

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import tech.ula.model.daos.SessionDao
import tech.ula.model.entities.Asset
import tech.ula.model.entities.Filesystem
import tech.ula.model.entities.Session
import tech.ula.model.repositories.AssetRepository
import tech.ula.model.repositories.UlaDatabase
import tech.ula.utils.DownloadUtility
import tech.ula.utils.FilesystemUtility
import tech.ula.utils.TimeUtility
import tech.ula.utils.asyncAwait
import java.io.File

class SessionStartupFsm(
        ulaDatabase: UlaDatabase,
        private val assetRepository: AssetRepository,
        private val filesystemUtility: FilesystemUtility,
        private val downloadUtility: DownloadUtility) {

    private val state = MutableLiveData<SessionStartupState>().apply { postValue(WaitingForSessionSelection) }

    private val sessionDao = ulaDatabase.sessionDao()
    private val activeSessionsLiveData = sessionDao.findActiveSessions()
    private val activeSessions = mutableListOf<Session>()

    private val filesystemDao = ulaDatabase.filesystemDao()
    private val filesystemsLiveData = filesystemDao.getAllFilesystems()
    private val filesystems = mutableListOf<Filesystem>()

    private val downloadingAssets = mutableListOf<Pair<Asset, Long>>()
    private val downloadedIds = mutableListOf<Long>()

    private val extractionLogger: (String) -> Unit = { line ->
        state.postValue(ExtractingFilesystem(line))
    }

    init {
        activeSessionsLiveData.observeForever {
            it?.let { list ->
                activeSessions.clear()
                activeSessions.addAll(list)
            }
        }
        filesystemsLiveData.observeForever {
            it?.let { list ->
                filesystems.clear()
                filesystems.addAll(list)
            }
        }
    }

    fun getState(): LiveData<SessionStartupState> {
        return state
    }

    // Exposed for testing purposes. This should not be called during real use cases.
    internal fun setState(newState: SessionStartupState) {
        state.postValue(newState)
    }

    fun submitEvent(event: SessionStartupEvent) {
        if (!transitionIsAcceptable(event)) {
            state.postValue(IncorrectTransition(event, state.value!!))
            return
        }
        return when (event) {
            is SessionSelected -> { handleSessionSelected(event.session) }
            is RetrieveAssetLists -> { handleRetrieveAssetLists(event.filesystem) }
            is GenerateDownloads -> { handleGenerateDownloads(event.filesystem, event.assetLists) }
            is DownloadAssets -> { handleDownloadAssets(event.assetsToDownload) }
            is AssetDownloadComplete -> { handleAssetsDownloadComplete(event.downloadAssetId) }
            is CopyDownloadsToLocalStorage -> { handleCopyDownloads() }
            is ExtractFilesystem -> { handleExtractFilesystem(event.filesystem) }
            is VerifyFilesystemAssets -> { handleVerifyFilesystemAssets(event.filesystem) }
            is ResetState -> { state.postValue(WaitingForSessionSelection) } // TODO test
        }
    }

    fun transitionIsAcceptable(event: SessionStartupEvent): Boolean {
        val currentState = state.value!!
        return when (event) {
            is SessionSelected -> currentState is WaitingForSessionSelection
            is RetrieveAssetLists -> currentState is SessionIsReadyForPreparation
            is GenerateDownloads -> currentState is AssetListsRetrievalSucceeded
            is DownloadAssets -> currentState is DownloadsRequired
            is AssetDownloadComplete -> currentState is DownloadingRequirements
            is CopyDownloadsToLocalStorage -> currentState is DownloadsHaveSucceeded
            is ExtractFilesystem -> currentState is NoDownloadsRequired || currentState is CopyingSucceeded
            is VerifyFilesystemAssets -> currentState is ExtractionSucceeded
            is ResetState -> true // TODO test
        }
    }

    // TODO handle non-existence?
    private fun findFilesystemForSession(session: Session): Filesystem {
        return filesystems.find { filesystem -> filesystem.id == session.filesystemId }!!
    }

    private fun handleSessionSelected(session: Session) {
        if (activeSessions.isNotEmpty()) {
            if (activeSessions.contains(session)) {
                state.postValue(SessionIsRestartable(session))
                return
            }

            state.postValue(SingleSessionSupported)
            return
        }

        val filesystem = findFilesystemForSession(session)
        state.postValue(SessionIsReadyForPreparation(session, filesystem))
    }

    private fun handleRetrieveAssetLists(filesystem: Filesystem) = runBlocking {
        state.postValue(RetrievingAssetLists)

        val assetLists = async {
            assetRepository.getAllAssetLists(filesystem.distributionType, filesystem.archType)
        }.await()

        if (assetLists.any { it.isEmpty() }) {
            state.postValue(AssetListsRetrievalFailed)
            return@runBlocking
        }

        state.postValue(AssetListsRetrievalSucceeded(assetLists))
    }

    private fun handleGenerateDownloads(filesystem: Filesystem, assetLists: List<List<Asset>>) {
        state.postValue(GeneratingDownloadRequirements)

        val requiredDownloads = assetLists.map { assetList ->
            assetList.filter { asset ->  
                val needsUpdate = assetRepository.doesAssetNeedToUpdated(asset)

                if (asset.isLarge && needsUpdate && filesystemUtility.
                                hasFilesystemBeenSuccessfullyExtracted("${filesystem.id}")) {
                    return@filter false
                }
                needsUpdate
            }
        }.flatten()

        if (requiredDownloads.isEmpty()) {
            state.postValue(NoDownloadsRequired)
            return
        }

        val largeDownloadRequired = requiredDownloads.any { it.isLarge }
        state.postValue(DownloadsRequired(requiredDownloads, largeDownloadRequired))
    }

    private fun handleDownloadAssets(assetsToDownload: List<Asset>) {

        downloadingAssets.clear()
        downloadedIds.clear()

        val newDownloads = downloadUtility.downloadRequirements(assetsToDownload)
        downloadingAssets.addAll(newDownloads)
        state.postValue(DownloadingRequirements(0, downloadingAssets.size)) // TODO test
    }

    private fun handleAssetsDownloadComplete(downloadId: Long) {
        if (!downloadUtility.downloadedSuccessfully(downloadId)) state.postValue(DownloadsHaveFailed)

        downloadedIds.add(downloadId)
        downloadUtility.setTimestampForDownloadedFile(downloadId) // TODO test
        if (downloadingAssets.size != downloadedIds.size){
            state.postValue(DownloadingRequirements(downloadedIds.size, downloadingAssets.size)) // TODO test
            return
        }

        state.postValue(DownloadsHaveSucceeded)
    }

    private fun handleCopyDownloads() {
        state.postValue(CopyingFilesToRequiredDirectories)
        try {
            downloadUtility.moveAssetsToCorrectLocalDirectory()
        } catch (err: Exception) {
            state.postValue(CopyingFailed)
            return
        }
        state.postValue(CopyingSucceeded)
    }

    private fun handleExtractFilesystem(filesystem: Filesystem) = runBlocking {
        val filesystemDirectoryName = "${filesystem.id}"

        if (filesystemUtility.hasFilesystemBeenSuccessfullyExtracted(filesystemDirectoryName)) {
            state.postValue(ExtractionSucceeded)
            return@runBlocking
        }

        val copyingSucceeded = copyDistributionAssetsToFilesystem(filesystem)
        if (!copyingSucceeded) {
            state.postValue(DistributionCopyFailed)
            return@runBlocking
        }

        asyncAwait {
            // TODO test
            filesystemUtility.extractFilesystem(filesystem, extractionLogger)
//            while (!filesystemUtility.isExtractionComplete(filesystemDirectoryName)) {
//                delay(500)
//            }
        }

        if (filesystemUtility.hasFilesystemBeenSuccessfullyExtracted(filesystemDirectoryName)) {
            state.postValue(ExtractionSucceeded)
            return@runBlocking
        }

        state.postValue(ExtractionFailed)
    }

    private fun copyDistributionAssetsToFilesystem(filesystem: Filesystem): Boolean {
        return try {
            filesystemUtility.copyAssetsToFilesystem("${filesystem.id}", filesystem.distributionType)
            assetRepository.setLastDistributionUpdate(filesystem.distributionType, TimeUtility().getCurrentTimeMillis()) // TODO inject and test
            true
        } catch (err: Exception) {
            false
        }
    }

    private fun handleVerifyFilesystemAssets(filesystem: Filesystem) {
        state.postValue(VerifyingFilesystemAssets)

        val filesystemDirectoryName = "${filesystem.id}"
        val requiredAssets = assetRepository.getDistributionAssetsForExistingFilesystem(filesystem)
        val allAssetsArePresent = filesystemUtility.areAllRequiredAssetsPresent(filesystemDirectoryName, requiredAssets)
        val allAssetsAreUpToDate = filesystem.lastUpdated >= assetRepository.getLastDistributionUpdate(filesystem.distributionType)

        when {
            allAssetsArePresent && allAssetsAreUpToDate -> {
                state.postValue(FilesystemHasRequiredAssets)
            }

            allAssetsArePresent-> {
                val copyingSucceeded = copyDistributionAssetsToFilesystem(filesystem)
                if (copyingSucceeded) {
                    state.postValue(FilesystemHasRequiredAssets)
                    filesystemUtility.removeRootfsFilesFromFilesystem(filesystemDirectoryName)
                }
                else state.postValue(DistributionCopyFailed)
            }

            else -> {
                state.postValue(FilesystemIsMissingRequiredAssets)
            }
        }
    }

    // TODO handle moving apps files to profiled
}

sealed class SessionStartupState
data class IncorrectTransition(val event: SessionStartupEvent, val state: SessionStartupState) : SessionStartupState()
object WaitingForSessionSelection : SessionStartupState()
object SingleSessionSupported : SessionStartupState()
data class SessionIsRestartable(val session: Session) : SessionStartupState()
data class SessionIsReadyForPreparation(val session: Session, val filesystem: Filesystem) : SessionStartupState()
object RetrievingAssetLists : SessionStartupState()
data class AssetListsRetrievalSucceeded(val assetLists: List<List<Asset>>) : SessionStartupState()
object AssetListsRetrievalFailed : SessionStartupState()
object GeneratingDownloadRequirements : SessionStartupState()
data class DownloadsRequired(val requiredDownloads: List<Asset>, val largeDownloadRequired: Boolean) : SessionStartupState()
object NoDownloadsRequired : SessionStartupState()
data class DownloadingRequirements(val numCompleted: Int, val numTotal: Int) : SessionStartupState()
object DownloadsHaveSucceeded : SessionStartupState()
object DownloadsHaveFailed : SessionStartupState()
object CopyingFilesToRequiredDirectories : SessionStartupState()
object CopyingSucceeded : SessionStartupState()
object CopyingFailed : SessionStartupState()
object DistributionCopyFailed: SessionStartupState()
data class ExtractingFilesystem(val extractionTarget: String) : SessionStartupState()
object ExtractionSucceeded : SessionStartupState()
object ExtractionFailed : SessionStartupState()
object VerifyingFilesystemAssets : SessionStartupState()
object FilesystemHasRequiredAssets : SessionStartupState()
object FilesystemIsMissingRequiredAssets : SessionStartupState()

sealed class SessionStartupEvent
data class SessionSelected(val session: Session) : SessionStartupEvent()
data class RetrieveAssetLists(val filesystem: Filesystem) : SessionStartupEvent()
data class GenerateDownloads(val filesystem: Filesystem, val assetLists: List<List<Asset>>) : SessionStartupEvent()
data class DownloadAssets(val assetsToDownload: List<Asset>) : SessionStartupEvent()
data class AssetDownloadComplete(val downloadAssetId: Long) : SessionStartupEvent()
object CopyDownloadsToLocalStorage : SessionStartupEvent()
data class ExtractFilesystem(val filesystem: Filesystem) : SessionStartupEvent()
data class VerifyFilesystemAssets(val filesystem: Filesystem) : SessionStartupEvent()
object ResetState : SessionStartupEvent()