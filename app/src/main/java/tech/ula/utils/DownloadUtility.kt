package tech.ula.utils

import android.app.DownloadManager
import tech.ula.model.entities.Asset
import java.io.File

class DownloadUtility(
    private val downloadManager: DownloadManager,
    private val timestampPreferences: TimestampPreferences,
    private val downloadManagerWrapper: DownloadManagerWrapper,
    private val applicationFilesDir: File
) {

    private val downloadDirectory = downloadManagerWrapper.getDownloadsDirectory()

    fun downloadRequirements(assetList: List<Asset>): List<Pair<Asset, Long>> {
        return assetList.map { it to download(it) }
    }

    fun downloadedSuccessfully(id: Long): Boolean {
        val query = downloadManagerWrapper.generateQuery(id)
        val cursor = downloadManagerWrapper.generateCursor(downloadManager, query)
        return downloadManagerWrapper.downloadHasNotFailed(cursor)
    }

    private fun download(asset: Asset): Long {
        val branch = if (asset.distributionType.equals("support", true)) "staging" else "master"
        val url = "https://github.com/CypherpunkArmory/UserLAnd-Assets-" +
                "${asset.distributionType}/raw/$branch/assets/" +
                "${asset.architectureType}/${asset.name}"
        val destination = asset.concatenatedName
        val request = downloadManagerWrapper.generateDownloadRequest(url, destination)
        deletePreviousDownload(asset)

        return downloadManager.enqueue(request)
    }

    private fun deletePreviousDownload(asset: Asset) {
        val downloadsDirectoryFile = File(downloadDirectory, asset.concatenatedName)
        val localFile = File(applicationFilesDir, asset.pathName)

        if (downloadsDirectoryFile.exists())
            downloadsDirectoryFile.delete()
        if (localFile.exists())
            localFile.delete()
    }

    fun setTimestampForDownloadedFile(id: Long) {
        val query = downloadManagerWrapper.generateQuery(id)
        val cursor = downloadManagerWrapper.generateCursor(downloadManager, query)
        val titleName = downloadManagerWrapper.getDownloadTitle(cursor)
        if (titleName == "" || !titleName.contains("UserLAnd")) return
        // Title should be asset.concatenatedName
        timestampPreferences.setSavedTimestampForFileToNow(titleName)
    }

    @Throws(Exception::class)
    fun moveAssetsToCorrectLocalDirectory() {
        downloadDirectory.walkBottomUp()
                .filter { it.name.contains("UserLAnd-") }
                .forEach {
                    val delimitedContents = it.name.split("-", limit = 3)
                    if (delimitedContents.size != 3) return@forEach
                    val (_, directory, filename) = delimitedContents
                    val containingDirectory = File("${applicationFilesDir.path}/$directory")
                    val targetDestination = File("${containingDirectory.path}/$filename")
                    it.copyTo(targetDestination, overwrite = true)
                    makePermissionsUsable(containingDirectory.path, filename)
                    it.delete()
                }
    }
}
