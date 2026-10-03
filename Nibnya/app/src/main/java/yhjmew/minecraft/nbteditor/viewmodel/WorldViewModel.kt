package yhjmew.minecraft.nbteditor.viewmodel

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yhjmew.minecraft.nbteditor.R
import yhjmew.minecraft.nbteditor.BedrockParser
import yhjmew.minecraft.nbteditor.MainActivity
import yhjmew.minecraft.nbteditor.NbtTranslator.getString
import yhjmew.minecraft.nbteditor.PlayerDbManager
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import yhjmew.minecraft.nbteditor.AppLogger
import yhjmew.minecraft.nbteditor.NbtTranslator
import yhjmew.minecraft.nbteditor.SafPathResolver

class WorldViewModel : ViewModel() {

    
    var editorVM: EditorViewModel? = null

    // ============================================
    
    // ============================================
    data class WorldItem(
        val folderName: String,
        val displayName: String
    )

    data class CacheItem(
        val file: File,
        val name: String,
        val isDirectory: Boolean
    )

    data class BackupItem(
        val file: File,
        val name: String,
        val time: String,
        val isDirectory: Boolean
    )

    // ============================================
    
    // ============================================
    data class ScanResult(val worlds: List<WorldItem>, val error: String? = null)

    private val _scanResultChannel = Channel<ScanResult>(Channel.BUFFERED)
    val scanResultChannel = _scanResultChannel

    private val _offerCreateKey = MutableStateFlow<String?>(null)
    val offerCreateKey: StateFlow<String?> = _offerCreateKey.asStateFlow()

    fun clearOfferCreateKey() { _offerCreateKey.value = null }

    private val _currentPath = MutableStateFlow(MainActivity.PATH_STANDARD)
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()
    var pendingSidebarTask: Runnable? = null

    private var useMultiThread = true

    fun setUseMultiThread(use: Boolean) {
        useMultiThread = use
    }

    private val _worldList = MutableStateFlow<List<WorldItem>>(emptyList())
    val worldList: StateFlow<List<WorldItem>> = _worldList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingMessage = MutableStateFlow("")
    val loadingMessage: StateFlow<String> = _loadingMessage.asStateFlow()

    private val _progressMessage = MutableStateFlow("")
    val progressMessage: StateFlow<String> = _progressMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _currentWorldFolder = MutableStateFlow<String?>(null)
    val currentWorldFolder: StateFlow<String?> = _currentWorldFolder.asStateFlow()

    private val _worldNameForTitle = MutableStateFlow<String?>(null)
    val worldNameForTitle: StateFlow<String?> = _worldNameForTitle.asStateFlow()

    private val _worldSeedForDisplay = MutableStateFlow<String?>(null)
    val worldSeedForDisplay: StateFlow<String?> = _worldSeedForDisplay.asStateFlow()

    private val _cacheList = MutableStateFlow<List<CacheItem>>(emptyList())
    val cacheList: StateFlow<List<CacheItem>> = _cacheList.asStateFlow()

    private val _backupList = MutableStateFlow<List<BackupItem>>(emptyList())
    val backupList: StateFlow<List<BackupItem>> = _backupList.asStateFlow()

    fun clearToast() { _toastMessage.value = null }
    fun clearError() { _errorMessage.value = null }

    // ============================================
    
    // ============================================
    var currentWorkingDbPath: String? = null
    var currentWorkingFileOrDir: String? = null
    var lastLoadedWorldFolder: String? = null
    var safTreeUri: android.net.Uri? = null

    /** True once the saved path selection was applied in this process (see MainActivity.restoreSavedPath). */
    var pathRestored = false

    private fun baseDir(context: Context): File {
        val p = _currentPath.value
        return if (p.contains("Android/data") || !p.startsWith("/")) {
            context.getExternalFilesDir(null) ?: context.filesDir
        } else {
            val publicDir = MainActivity.publicDataDir()
            if (!publicDir.exists()) publicDir.mkdirs()
            publicDir
        }
    }

    fun worksDir(context: Context): File {
        val w = File(baseDir(context), "Works")
        if (!w.exists()) w.mkdirs()
        return w
    }

    fun backupsDir(context: Context): File {
        val b = File(baseDir(context), "Backups")
        if (!b.exists()) b.mkdirs()
        return b
    }

    // ============================================
    
    // ============================================
    fun switchPath(newPath: String) {
        _currentPath.value = newPath
    }

    fun setSafPath(uri: android.net.Uri) {
        safTreeUri = uri
        _currentPath.value = "$uri/"
    }

    // ============================================
    
    // ============================================
    fun scanWorlds(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.msg_scanning)
            try {
                val dir = File(_currentPath.value)
                val rawFolders = mutableListOf<String>()

                
                val checkLevel = File(dir, "level.dat")
                val checkDb = File(dir, "db")
                if (checkLevel.exists() && checkDb.exists()) {
                    
                    val realName = getWorldRealNameByDir(dir) ?: "Unknown"
                    _worldList.value = listOf(WorldItem(folderName = dir.name, displayName = realName))
                    _scanResultChannel.trySend(ScanResult(listOf(WorldItem(folderName = dir.name, displayName = realName))))
                    _isLoading.value = false
                    return@launch
                }

                if (dir.exists() && dir.canRead()) {
                    dir.listFiles()?.forEach { if (it.isDirectory) rawFolders.add(it.name) }
                }

                val result = mutableListOf<WorldItem>()
                for (name in rawFolders) {
                    val fullPath = _currentPath.value + name
                    if (File(fullPath, "level.dat").exists() && File(fullPath, "db").exists()) {
                        val realName = getWorldRealName(context, name) ?: "Unknown"
                        result.add(WorldItem(folderName = name, displayName = realName))
                    }
                }

                _worldList.value = result
                _scanResultChannel.trySend(ScanResult(result))
            } catch (e: Exception) {
                AppLogger.error("ScanWorlds", "Scan failed", e)
                _errorMessage.value = e.toString()
                _scanResultChannel.trySend(ScanResult(emptyList(), e.toString()))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun scanWorldsViaSaf(context: Context, treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = NbtTranslator.getString(R.string.msg_scanning)

            try {
                val resolvedPath = SafPathResolver.resolveTreeUriToPath(context, treeUri)

                if (resolvedPath != null && SafPathResolver.isProbablyUsablePath(resolvedPath)) {
                    _currentPath.value = if (resolvedPath.endsWith("/")) resolvedPath else "$resolvedPath/"
                    scanWorlds(context)
                } else {
                    scanWorldsWithDocumentFile(context, treeUri)
                }
            } catch (e: Exception) {
                _errorMessage.value = NbtTranslator.getString(R.string.err_saf_scan_failed, e.message ?: "")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun scanWorldsWithDocumentFile(context: Context, treeUri: Uri) {
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return

        // The user may have picked a world folder itself rather than the folder that holds worlds.
        if (isWorldDoc(treeDoc)) {
            val self = listOf(WorldItem(
                folderName = treeDoc.name ?: "",
                displayName = readWorldNameFromSaf(context, treeDoc) ?: treeDoc.name ?: "Unknown"))
            _worldList.value = self
            _scanResultChannel.trySend(ScanResult(self))
            return
        }

        val worldFolders = treeDoc.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { folder ->
                val hasLevelDat = folder.findFile("level.dat") != null
                val hasDb = folder.findFile("db") != null
                if (hasLevelDat && hasDb) {
                    val displayName = readWorldNameFromSaf(context, folder) ?: folder.name ?: "Unknown"
                    WorldItem(folderName = folder.name ?: "", displayName = displayName)
                } else null
            }

        _worldList.value = worldFolders
        _scanResultChannel.trySend(ScanResult(worldFolders))
    }

    private fun readWorldNameFromSaf(context: Context, worldFolder: DocumentFile): String? {
        try {
            val levelnameFile = worldFolder.findFile("levelname.txt") ?: return null
            context.contentResolver.openInputStream(levelnameFile.uri)?.use { input ->
                return java.io.BufferedReader(java.io.InputStreamReader(input)).readLine()
            }
        } catch (e: Exception) {
            android.util.Log.w("WorldVM", "Failed to read levelname.txt via SAF", e)
        }
        return null
    }

    // ============================================
    
    // ============================================
    private fun getWorldRealName(context: Context, folderName: String): String? =
        getWorldRealNameByDir(File("${_currentPath.value}$folderName"))

    private fun getWorldRealNameByDir(worldDir: File): String? {
        val file = File(worldDir, "levelname.txt")
        if (file.exists() && file.canRead()) {
            try {
                val name = file.bufferedReader().use { it.readLine() }
                if (!name.isNullOrBlank()) return name
            } catch (_: Exception) {}
        }
        return null
    }

    // ============================================
    
    // ============================================
    fun loadLevelDat(context: Context, folder: String) {
        val evm = editorVM ?: return
        evm.saveSession(currentWorkingDbPath)

        // Same folder name at a different location is a different world.
        val target = resolveTarget(folder)
        val isNewWorld = lastLoadedWorldFolder == null || lastLoadedWorldFolder != folder || levelTarget != target
        if (isNewWorld) {
            evm.reset()
            lastLoadedWorldFolder = folder
        } else if (evm.tryRestoreSession("level.dat")) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.msg_loading_level_dat)
            try {
                val destFile = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                if (destFile.exists()) destFile.delete()

                fetchLevelDat(context, target, destFile)
                levelTarget = target

                currentWorkingFileOrDir = destFile.absolutePath

                val json = BedrockParser.parse(destFile.absolutePath)

                evm.setEditingPlayer(false)
                evm.setTargetKey(null)

                evm.setRawNbtData(json)  
                evm.nbtDataCache["level.dat"] = json
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.currentListData = json
                evm.updatePathTitle()

                
                updateWorldInfoFromNbt(json)

                _currentWorldFolder.value = folder
                _isLoading.value = false
                _toastMessage.value = getString(R.string.msg_loaded_level_dat)
            } catch (e: Exception) {
                AppLogger.error("LoadLevelDat", "Load level.dat failed", e)
                _isLoading.value = false
                _errorMessage.value = e.message ?: getString(R.string.msg_load_failed)
            }
        }
    }

    // ============================================
    
    // ============================================
    fun loadPlayerData(context: Context, folder: String) {
        val evm = editorVM ?: return
        evm.saveSession(currentWorkingDbPath)
        if (evm.tryRestoreSession("~local_player")) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.msg_loading_player)
            try {
                val target = resolveTarget(folder)

                val uniqueId = System.currentTimeMillis().toString()
                val workDir = File(worksDir(context), "working_db_$uniqueId")
                val appPrivatePath = workDir.absolutePath
                if (!workDir.exists()) workDir.mkdirs()

                fetchDb(context, target, workDir)
                dbTarget = target

                cleanLevelDbMeta(workDir)

                currentWorkingDbPath = appPrivatePath

                val dbManager = PlayerDbManager(appPrivatePath)
                val data = dbManager.readLocalPlayer()
                dbManager.close()

                val playerDataObj = BedrockParser.parseBytes(data)

                evm.setEditingPlayer(true)
                evm.setTargetKey("~local_player")
                evm.setRawNbtData(playerDataObj)
                evm.nbtDataCache["~local_player"] = playerDataObj
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.updatePathTitle()

                _currentWorldFolder.value = folder
                _isLoading.value = false
                _toastMessage.value = getString(R.string.toast_player_loaded_success)
            } catch (e: Exception) {
                AppLogger.error("LoadPlayer", "Load player failed", e)
                _isLoading.value = false
                _errorMessage.value = e.message
                
                if (e.message?.contains("DB_CORRUPT") == true) {
                    currentWorkingDbPath?.let { dbPath ->
                        _errorMessage.value = "DB_CORRUPT:$dbPath:$folder"
                    }
                }
            }
        }
    }

    // ============================================
    
    // ============================================
    fun loadSpecificKey(context: Context, keyName: String) {
        val evm = editorVM ?: return
        evm.saveSession(currentWorkingDbPath)
        if (evm.tryRestoreSession(keyName)) return
        if (evm.nbtDataCache.containsKey(keyName)) {
            evm.setTargetKey(keyName)
            evm.setEditingPlayer(true)
            evm.setRawNbtData(evm.nbtDataCache[keyName])
            evm.navigationStack.clear()
            evm.pathStack.clear()
            evm.scrollPositionStack.clear()
            evm.updatePathTitle()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                currentWorkingDbPath?.let { File(it, "LOCK").delete() }
                val dbPath = currentWorkingDbPath ?: throw Exception(getString(R.string.text_db_path_is_null))
                val db = PlayerDbManager(dbPath)
                val data: ByteArray
                try {
                    data = db.readSpecificKey(keyName)
                } catch (e: Exception) {
                    db.close()
                    _isLoading.value = false
                    _offerCreateKey.value = keyName
                    return@launch
                }
                db.close()
                val jsonData = BedrockParser.parseBytes(data)

                evm.setTargetKey(keyName)
                evm.setEditingPlayer(true)
                evm.setRawNbtData(jsonData)
                evm.nbtDataCache[keyName] = jsonData
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.updatePathTitle()
                _isLoading.value = false
                _toastMessage.value = getString(R.string.toast_loaded, keyName)
            } catch (e: Exception) {
                AppLogger.error("LoadKey", "Load key $keyName failed", e)
                _isLoading.value = false
                _errorMessage.value = getString(R.string.err_load_failed_with_msg, e.message)
            }
        }
    }

    fun createNewGlobalData(keyName: String?) {
        val evm = editorVM ?: return
        val emptyNbt = JsonObject()
        evm.setTargetKey(keyName)
        evm.setEditingPlayer(true)
        evm.setRawNbtData(emptyNbt)
        evm.navigationStack.clear()
        evm.pathStack.clear()
        evm.scrollPositionStack.clear()
        evm.updatePathTitle()
    }

    // ============================================
    
    // ============================================
    fun saveAndPushBack(context: Context, dataToSave: JsonObject?, folder: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.msg_saving)
            try {
                createBackup(context, folder)
                val evm = editorVM ?: throw Exception(getString(R.string.msg_editorvm_missing))

                if (evm.isEditingPlayer.value) {
                    val dbPath = currentWorkingDbPath ?: throw Exception(getString(R.string.text_db_path_is_null))
                    val oldWorkDir = File(dbPath)
                    if (!oldWorkDir.exists()) throw Exception(getString(R.string.msg_workdir_lost))

                    val uniqueId = System.currentTimeMillis().toString()
                    val newWorkDir = File(worksDir(context), "working_db_$uniqueId")
                    smartCopy(oldWorkDir, newWorkDir)
                    cleanLevelDbMeta(newWorkDir)

                    
                    val currentKey = evm.currentTargetKey.value
                    AppLogger.info("ListDebug", "saveAndPushBack(玩家): currentKey=$currentKey, dataToSave keys=${dataToSave?.keySet()?.joinToString(",") { it } ?: "null"}")
                    if (currentKey != null && dataToSave != null) {
                        evm.nbtDataCache[currentKey] = dataToSave
                        AppLogger.info("ListDebug", "saveAndPushBack(玩家): nbtDataCache[$currentKey] 已更新为 dataToSave")
                    } else {
                        AppLogger.warn("ListDebug", "saveAndPushBack(玩家): currentKey=$currentKey 或 dataToSave=null，未更新缓存!")
                    }

                    
                    val db = PlayerDbManager(newWorkDir.absolutePath)

                    
                    val allKeys = mutableSetOf<String>()
                    allKeys.addAll(evm.nbtDataCache.keys.filterNotNull())

                    try {
                        allKeys.addAll(db.listMapKeys())
                        allKeys.addAll(db.listVillageKeys())
                        allKeys.addAll(db.listPlayerKeys())
                    } catch (_: Exception) {}

                    for (key in allKeys) {
                        val data = evm.nbtDataCache[key]
                        if (data != null) {
                            val bytes = BedrockParser.writeToBytes(data)
                            db.writeSpecificKey(key, bytes)
                            AppLogger.info("ListDebug", "写DB(遍历allKeys): key=$key, bytes=${bytes.size}, keys=${data.keySet().joinToString(",") { it }}")
                        }
                    }

                    db.close()

                    // Write back to where this db was loaded from (not to whatever the path selector shows now).
                    val target = dbTarget?.takeIf { it.folder == folder } ?: resolveTarget(folder)
                    pushDb(context, target, newWorkDir)
                    dbTarget = target

                    deleteRecursive(oldWorkDir)
                    currentWorkingDbPath = newWorkDir.absolutePath
                    _toastMessage.value = getString(R.string.toast_player_saved)
                } else {
                    if (currentWorkingFileOrDir == null) {
                        val f = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                        currentWorkingFileOrDir = f.absolutePath
                    }
                    BedrockParser.write(dataToSave, currentWorkingFileOrDir)

                    val workingFile = File(currentWorkingFileOrDir!!)
                    val target = levelTarget?.takeIf { it.folder == folder } ?: resolveTarget(folder)
                    pushLevelDat(context, target, workingFile)
                    levelTarget = target
                    _toastMessage.value = getString(R.string.toast_level_dat_saved)
                }
                _isLoading.value = false
            } catch (e: Exception) {
                AppLogger.error("Save", "Save failed", e)
                _isLoading.value = false
                _errorMessage.value = getString(R.string.msg_save_failed_with_details, "${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
            }
        }
    }

    private fun createBackup(context: Context, folderName: String) {
        try {
            val backupRoot = File(backupsDir(context), folderName)
            if (!backupRoot.exists()) backupRoot.mkdirs()
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val evm = editorVM ?: return
            if (evm.isEditingPlayer.value) {
                currentWorkingDbPath?.let { dbPath ->
                    val srcDb = File(dbPath)
                    if (srcDb.exists() && srcDb.isDirectory) {
                        copyDirectory(srcDb, File(backupRoot, "db_$ts"))
                    }
                }
            } else {
                val src = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                if (src.exists() && src.isFile) {
                    copyFileNative(src, File(backupRoot, "level_$ts.dat"))
                }
            }
        } catch (e: Exception) {
            Log.e("WorldVM", "Backup failed", e)
        }
    }

    // ============================================
    
    // ============================================
    fun scanCache(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = worksDir(context)
            if (!dir.exists()) dir.mkdirs()
            val files = dir.listFiles()
            val list = mutableListOf<CacheItem>()
            if (files != null) {
                files.sortByDescending { it.lastModified() }
                for (f in files) list.add(CacheItem(f, f.name, f.isDirectory))
            }
            _cacheList.value = list
        }
    }

    fun deleteCacheItem(item: CacheItem) {
        deleteRecursive(item.file)
    }

    fun clearAllCache(context: Context) {
        val dir = worksDir(context)
        dir.listFiles()?.forEach { deleteRecursive(it) }
        currentWorkingDbPath = null
        currentWorkingFileOrDir = null
        lastLoadedWorldFolder = null
        _cacheList.value = emptyList()
    }

    // ============================================
    
    // ============================================
    fun scanBackups(context: Context, folderName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(backupsDir(context), folderName)
            if (!dir.exists()) {
                _backupList.value = emptyList()
                return@launch
            }
            val files = dir.listFiles() ?: emptyArray()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            files.sortByDescending { it.lastModified() }
            _backupList.value = files.map {
                BackupItem(it, it.name, sdf.format(Date(it.lastModified())), it.isDirectory)
            }
        }
    }

    fun restoreBackup(context: Context, backupItem: BackupItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val evm = editorVM ?: return@launch
                if (backupItem.isDirectory) {
                    val uniqueId = System.currentTimeMillis().toString()
                    val restoreDir = File(worksDir(context), "working_db_restore_$uniqueId")
                    deleteRecursive(restoreDir)
                    smartCopy(backupItem.file, restoreDir)
                    File(restoreDir, "LOCK").delete()
                    File(restoreDir, "LOG").delete()
                    File(restoreDir, "LOG.old").delete()
                    currentWorkingDbPath = restoreDir.absolutePath

                    val db = PlayerDbManager(restoreDir.absolutePath)
                    val data = db.readLocalPlayer()
                    db.close()

                    val json = BedrockParser.parseBytes(data)
                    evm.setEditingPlayer(true)
                    evm.setRawNbtData(json)
                    evm.navigationStack.clear()
                    evm.pathStack.clear()
                    evm.scrollPositionStack.clear()
                    evm.updatePathTitle()
                    _toastMessage.value = getString(R.string.toast_player_restored)
                } else {
                    val target = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                    copyFileNative(backupItem.file, target)
                    currentWorkingFileOrDir = target.absolutePath

                    val json = BedrockParser.parse(target.absolutePath)
                    evm.setEditingPlayer(false)
                    evm.setTargetKey(null)
                    evm.setRawNbtData(json)
                    evm.nbtDataCache["level.dat"] = json
                    evm.navigationStack.clear()
                    evm.pathStack.clear()
                    evm.scrollPositionStack.clear()
                    evm.currentListData = json
                    evm.updatePathTitle()
                    updateWorldInfoFromNbt(json)
                    _toastMessage.value = getString(R.string.toast_level_restored)
                }
            } catch (e: Exception) {
                _errorMessage.value = getString(R.string.msg_restore_failed, e.message)
            }
        }
    }

    fun deleteBackup(item: BackupItem) {
        deleteRecursive(item.file)
    }

    fun renameBackup(item: BackupItem, newName: String): Boolean {
        val newFile = File(item.file.parent, newName)
        return item.file.renameTo(newFile)
    }

    // ============================================
    
    // ============================================
    fun cleanUpOldSessions(context: Context) {
        val privateWorks = File(context.getExternalFilesDir(null), "Works")
        cleanDirContent(privateWorks)
        cleanDirContent(File(MainActivity.publicDataDir(), "Works"))
        // Scratch folder left behind by older versions that copied through a bridge directory.
        deleteRecursive(File(MainActivity.publicDataDir(), "Bridge"))
    }

    private fun cleanDirContent(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach {
            if ((it.isDirectory && (it.name.startsWith("working_db_") || it.name.startsWith("db_restore_")))
                || (it.isFile && it.name == MainActivity.LEVEL_DAT_NAME)
            ) deleteRecursive(it)
        }
    }

    // ============================================
    
    // ============================================
    fun updateWorldInfoFromNbt(rootJson: JsonObject?) {
        if (rootJson == null) {
            _worldNameForTitle.value = null
            _worldSeedForDisplay.value = null
            return
        }
        try {
            var nameStr: String? = null
            if (rootJson.has("LevelName")) {
                val obj = rootJson.getAsJsonObject("LevelName")
                if (obj.has("v")) nameStr = obj.get("v").asString
            }
            var seedStr: String? = null
            if (rootJson.has("RandomSeed")) {
                val obj = rootJson.getAsJsonObject("RandomSeed")
                if (obj.has("v")) seedStr = obj.get("v").asString
            }
            _worldNameForTitle.value = nameStr
            _worldSeedForDisplay.value = seedStr
        } catch (_: Exception) {
            _worldNameForTitle.value = null
            _worldSeedForDisplay.value = null
        }
    }

    // ============================================
    
    // ============================================
    fun loadCustomKey(inputStr: String, isHex: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val dbPath = currentWorkingDbPath ?: throw Exception(getString(R.string.text_db_path_is_null))
                val db = PlayerDbManager(dbPath)
                val keyBytes = if (isHex) hexStringToByteArray(inputStr)
                else inputStr.toByteArray(Charsets.UTF_8)
                val data = db.readRawKey(keyBytes)
                db.close()
                val json = BedrockParser.parseBytes(data)

                val evm = editorVM ?: return@launch
                evm.setEditingPlayer(true)
                evm.setTargetKey(inputStr)
                evm.setRawNbtData(json)
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.updatePathTitle()

                _isLoading.value = false
                _toastMessage.value = getString(R.string.toast_loading_successfully)
            } catch (e: Exception) {
                AppLogger.error("LoadCustomKey", "Custom key load failed", e)
                _isLoading.value = false
                _errorMessage.value = getString(R.string.err_load_failed_with_msg, e.message)
            }
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val hex = s.replace(" ", "")
        require(hex.length % 2 == 0) { getString(R.string.msg_the_length_must_be_an_even_number) }
        val data = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            val high = hex[i].digitToIntOrNull(16) ?: throw IllegalArgumentException(getString(R.string.msg_invalid_hex, hex[i]))
            val low = hex[i + 1].digitToIntOrNull(16) ?: throw IllegalArgumentException(getString(R.string.msg_invalid_hex, hex[i + 1]))
            data[i / 2] = ((high shl 4) + low).toByte()
        }
        return data
    }

    // ============================================
    
    // ============================================
    fun checkStoragePermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                val intent = android.content.Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = "package:${context.packageName}".toUri()
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    // ============================================
    
    // ============================================
    private fun copyFile(src: File, dst: File) {
        val `in` = FileInputStream(src)
        val out = FileOutputStream(dst)
        val buf = ByteArray(1024)
        var len: Int
        while ((`in`.read(buf).also { len = it }) > 0) out.write(buf, 0, len)
        `in`.close(); out.close()
    }

    private fun copyFileNative(src: File, dst: File): Boolean {
        return try { copyFile(src, dst); true } catch (_: Exception) { false }
    }

    
    private val levelDbMetaFiles = setOf("LOCK", "LOG", "LOG.old")

    private fun shouldSkipFile(name: String): Boolean {
        return name in levelDbMetaFiles
    }

    private fun cleanLevelDbMeta(dir: File) {
        File(dir, "LOCK").takeIf { it.exists() }?.delete()
        File(dir, "LOG").takeIf { it.exists() }?.delete()
        File(dir, "LOG.old").takeIf { it.exists() }?.delete()
    }

    private fun copyDirectory(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists()) target.mkdirs()
            source.list()?.forEach {
                if (shouldSkipFile(it)) return@forEach
                copyDirectory(File(source, it), File(target, it))
            }
        } else copyFile(source, target)
    }

    private fun safCopyDirectory(context: Context, srcDoc: DocumentFile, dstDir: File) {
        if (!dstDir.exists()) dstDir.mkdirs()
        for (child in srcDoc.listFiles()) {
            val name = child.name ?: continue
            if (shouldSkipFile(name)) continue
            val dstFile = File(dstDir, name)
            if (child.isDirectory) {
                safCopyDirectory(context, child, dstFile)
            } else {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    java.io.FileOutputStream(dstFile).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun deleteRecursive(f: File?) {
        if (f == null || !f.exists()) return
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }

    
    private fun smartCopy(src: File, dst: File) {
        if (useMultiThread && src.isDirectory) {
            copyDirectoryParallel(src, dst)
        } else {
            copyDirectory(src, dst)
        }
    }

    private fun copyDirectoryParallel(source: File, target: File) {
        if (!target.exists()) target.mkdirs()

        val files = source.listFiles() ?: return
        val totalFiles = files.size
        if (totalFiles == 0) return

        
        if (totalFiles < 10) {
            files.forEach { copyDirectory(File(source, it.name), File(target, it.name)) }
            return
        }

        val cores = Runtime.getRuntime().availableProcessors()
        val threadCount = min(cores + 1, 8)
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(totalFiles)
        val errorRef = AtomicReference<Throwable?>()

        var completed = 0
        val totalSize = files.sumOf { it.length() }
        var copiedSize = 0L

        files.forEach { file ->
            if (shouldSkipFile(file.name)) {
                latch.countDown()
                return@forEach
            }
            executor.submit {
                try {
                    if (errorRef.get() != null) return@submit

                    val srcFile = File(source, file.name)
                    val dstFile = File(target, file.name)

                    if (srcFile.isDirectory) {
                        copyDirectoryParallel(srcFile, dstFile)
                    } else {
                        copyFile(srcFile, dstFile)
                    }

                    synchronized(this) {
                        completed++
                        copiedSize += file.length()
                        val progress = (copiedSize * 100 / maxOf(totalSize, 1)).toInt()
                        _progressMessage.value = getString(R.string.copying_progress, completed, totalFiles, progress)
                    }
                } catch (e: Throwable) {
                    errorRef.set(e)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()
        errorRef.get()?.let { throw it }
    }

    fun ensureNoMedia(dir: File) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val noMedia = File(dir, ".nomedia")
            if (!noMedia.exists()) noMedia.createNewFile()
        } catch (_: Exception) {}
    }

    // ============================================
    
    // ============================================
    fun tryRepairDb(dbPath: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                PlayerDbManager.tryRepair(dbPath)
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (e: Exception) {
                AppLogger.error("RepairDB", "DB repair failed", e)
                _errorMessage.value = getString(R.string.toast_repair_failed, e.message)
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    // ============================================
    
    // ============================================
    /**
     * Where a world was read from. Captured when the world is loaded and reused by Save, so writing
     * back never depends on what the path selector shows at that moment (the selection can change,
     * and used to be reset to the default whenever the process was recreated).
     *
     * [dir]  file-system path of the world folder (trailing '/'), null when only SAF can reach it
     * [tree] SAF tree the user granted; used when [dir] is null or turns out not to be writable
     *        (e.g. SD cards on Android 7, which block direct writes outside the app's own folders)
     */
    data class WorldTarget(val folder: String, val dir: String?, val tree: Uri?) {
        fun encode(): String =
            listOf(folder, dir ?: "", tree?.toString() ?: "").joinToString("|") { Uri.encode(it) }

        companion object {
            fun decode(s: String?): WorldTarget? {
                if (s.isNullOrEmpty()) return null
                val p = s.split("|").map { Uri.decode(it) }
                if (p.size != 3 || p[0].isEmpty()) return null
                return WorldTarget(p[0], p[1].ifEmpty { null }, p[2].ifEmpty { null }?.let { Uri.parse(it) })
            }
        }
    }

    private var levelTarget: WorldTarget? = null
    private var dbTarget: WorldTarget? = null

    /** String forms so MainActivity can keep them across process death via the saved-instance Bundle. */
    var levelTargetSpec: String?
        get() = levelTarget?.encode()
        set(v) { levelTarget = WorldTarget.decode(v) }
    var dbTargetSpec: String?
        get() = dbTarget?.encode()
        set(v) { dbTarget = WorldTarget.decode(v) }

    private fun resolveTarget(folder: String): WorldTarget {
        val tree = safTreeUri
        // SAF-only selection: setSafPath() stored a content:// string and no real path could be resolved.
        val safOnly = tree != null && !_currentPath.value.startsWith("/")
        return WorldTarget(folder, if (safOnly) null else resolveWorldPath(folder), tree)
    }

    private fun isWorldDoc(d: DocumentFile): Boolean =
        d.findFile("level.dat") != null && d.findFile("db") != null

    private fun findWorldDoc(context: Context, tree: Uri, folder: String): DocumentFile {
        val root = DocumentFile.fromTreeUri(context, tree) ?: throw Exception("Invalid tree URI")
        if (root.name == folder && isWorldDoc(root)) return root   // tree is the world itself
        return root.findFile(folder) ?: throw Exception("World folder not found: $folder")
    }

    /** Real write probe: File.canWrite() is unreliable on emulated/SD mounts. */
    private fun canWriteDir(dir: File): Boolean = try {
        (dir.exists() || dir.mkdirs()) && File(dir, ".nbt_write_probe").let { probe ->
            val ok = probe.createNewFile() || probe.exists()
            probe.delete()
            ok
        }
    } catch (_: Exception) { false }

    private fun fetchLevelDat(context: Context, t: WorldTarget, dest: File) {
        t.dir?.let { if (copyFileNative(File(it, "level.dat"), dest)) return }
        val tree = t.tree ?: throw Exception("Failed to copy level.dat")
        val doc = findWorldDoc(context, tree, t.folder).findFile("level.dat")
            ?: throw Exception("level.dat not found")
        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out) }
        } ?: throw Exception("Failed to open level.dat")
    }

    private fun fetchDb(context: Context, t: WorldTarget, workDir: File) {
        val dir = t.dir
        if (dir != null) {
            val src = File(dir, "db")
            if (src.exists() && src.canRead() && src.listFiles() != null) {
                try { File(src, "LOCK").delete() } catch (_: Exception) {}
                smartCopy(src, workDir)
                return
            }
            if (t.tree == null) throw Exception(getString(R.string.err_storage_permission_denied))
        }
        val tree = t.tree ?: throw Exception(getString(R.string.err_storage_permission_denied))
        val dbDoc = findWorldDoc(context, tree, t.folder).findFile("db")
            ?: throw Exception("db folder not found")
        safCopyDirectory(context, dbDoc, workDir)
    }

    private fun pushLevelDat(context: Context, t: WorldTarget, src: File) {
        val dir = t.dir
        if (dir != null) {
            if (copyFileNative(src, File(dir, "level.dat"))) return
            if (t.tree == null) throw Exception(getString(R.string.err_write_denied_use_saf, dir))
        }
        val tree = t.tree ?: throw Exception(getString(R.string.msg_write_to_game_dir_failed))
        val world = findWorldDoc(context, tree, t.folder)
        val doc = world.findFile("level.dat") ?: world.createFile("application/octet-stream", "level.dat")
            ?: throw Exception(getString(R.string.msg_write_to_game_dir_failed))
        context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: throw Exception(getString(R.string.msg_write_to_game_dir_failed))
    }

    private fun pushDb(context: Context, t: WorldTarget, srcDb: File) {
        val dir = t.dir
        if (dir != null) {
            val dbDir = File(dir, "db")
            if (canWriteDir(dbDir)) { smartCopy(srcDb, dbDir); return }
            if (t.tree == null) throw Exception(getString(R.string.err_write_denied_use_saf, dbDir.absolutePath))
        }
        val tree = t.tree ?: throw Exception(getString(R.string.msg_write_to_game_dir_failed))
        val world = findWorldDoc(context, tree, t.folder)
        val dbDoc = world.findFile("db") ?: world.createDirectory("db")
            ?: throw Exception(getString(R.string.msg_write_to_game_dir_failed))
        pushDirToSaf(context, srcDb, dbDoc)
    }

    /** Copies [src] into the SAF directory [dst], overwriting files in place. */
    private fun pushDirToSaf(context: Context, src: File, dst: DocumentFile) {
        val existing = dst.listFiles().associateBy { it.name }   // one provider query per directory
        for (f in src.listFiles() ?: return) {
            if (shouldSkipFile(f.name)) continue
            val cur = existing[f.name]
            if (f.isDirectory) {
                val sub = cur?.takeIf { it.isDirectory } ?: dst.createDirectory(f.name)
                    ?: throw Exception("Cannot create folder ${f.name}")
                pushDirToSaf(context, f, sub)
                continue
            }
            // LevelDB table files never change once written, so an equal-size copy is already current.
            // SAF I/O is slow and a world's db can hold hundreds of them.
            val immutable = f.name.endsWith(".ldb") || f.name.endsWith(".sst")
            if (immutable && cur != null && cur.isFile && cur.length() == f.length()) continue
            val out = cur ?: dst.createFile("application/octet-stream", f.name)
                ?: throw Exception("Cannot create file ${f.name}")
            context.contentResolver.openOutputStream(out.uri, "wt")?.use { o ->
                f.inputStream().use { it.copyTo(o) }
            } ?: throw Exception("Cannot open ${f.name} for writing")
        }
    }

    private fun resolveWorldPath(folder: String): String {
        val base = _currentPath.value
        val baseFile = File(base)
        
        if (baseFile.name == folder) {
            return if (base.endsWith("/")) base else "$base/"
        }
        return if (base.endsWith("/")) "$base$folder/" else "$base/$folder/"
    }

    fun reloadPlayerFromCurrentDb() {
        val dbPath = currentWorkingDbPath ?: return
        val evm = editorVM ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.msg_scanning)
            try {
                val db = PlayerDbManager(dbPath)
                val data = db.readLocalPlayer()
                db.close()

                val playerDataObj = BedrockParser.parseBytes(data)
                evm.setEditingPlayer(true)
                evm.setTargetKey("~local_player")
                evm.setRawNbtData(playerDataObj)
                evm.nbtDataCache["~local_player"] = playerDataObj
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.updatePathTitle()
                _isLoading.value = false
                _toastMessage.value = getString(R.string.toast_player_loaded_success)
            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = e.message
            }
        }
    }
}