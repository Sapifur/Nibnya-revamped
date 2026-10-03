package yhjmew.minecraft.nbteditor.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import yhjmew.minecraft.nbteditor.R
import yhjmew.minecraft.nbteditor.AppLogger
import yhjmew.minecraft.nbteditor.BedrockParser
import yhjmew.minecraft.nbteditor.MainActivity
import yhjmew.minecraft.nbteditor.NbtAdapter
import yhjmew.minecraft.nbteditor.NbtTranslator.getString
import yhjmew.minecraft.nbteditor.NbtTreeAdapter
import java.io.File
import java.util.Stack

class EditorViewModel : ViewModel() {

    // ============================================
    
    // ============================================

    internal val _nbtData = MutableStateFlow<JsonObject?>(null)
    val nbtData: StateFlow<JsonObject?> = _nbtData.asStateFlow()

    private val _pathTitle = MutableStateFlow("")
    val pathTitle: StateFlow<String> = _pathTitle.asStateFlow()

    private val _isTreeMode = MutableStateFlow(false)
    val isTreeMode: StateFlow<Boolean> = _isTreeMode.asStateFlow()

    private val _isEditingPlayer = MutableStateFlow(false)
    val isEditingPlayer: StateFlow<Boolean> = _isEditingPlayer.asStateFlow()

    private val _currentTargetKey = MutableStateFlow<String?>("~local_player")
    val currentTargetKey: StateFlow<String?> = _currentTargetKey.asStateFlow()

    private val _viewMode = MutableStateFlow(0)
    val viewMode: StateFlow<Int> = _viewMode.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    
    fun clearToast() { _toastMessage.value = null }

    // ============================================
    
    // ============================================
    val navigationStack = Stack<JsonObject?>()
    val pathStack = Stack<String?>()
    val scrollPositionStack = Stack<Int?>()

    var currentListData: JsonObject? = null
    var lastTreeClickPosition = -1

    
    data class FakeMapInfo(
        val parent: JsonObject,      
        val listKey: String,        
        var fakeMap: JsonObject     
    )
    private val fakeMapStack = Stack<FakeMapInfo>()

    // ============================================
    
    // ============================================
    val sessionCacheMap: MutableMap<String?, EditorSession?> = HashMap()
    val nbtDataCache = HashMap<String?, JsonObject?>()

    // ============================================
    // Nav
    // ============================================
    fun enterFolder(key: String?, content: JsonObject?, isListMode: Boolean, currentPos: Int) {
        scrollPositionStack.push(currentPos)
        navigationStack.push(_nbtData.value)

        
        if (isListMode && content != null) {
            val parent = navigationStack.lastElement() ?: return
            fakeMapStack.push(FakeMapInfo(parent, key ?: "", content))
            AppLogger.info("ListDebug", "enterFolder 进入 List: key=$key, fakeMapSize=${content.size()}, fakeMapStack=${fakeMapStack.size}")
        } else {
            AppLogger.info("ListDebug", "enterFolder 进入: key=$key, isListMode=$isListMode, contentSize=${content?.size() ?: -1}")
        }

        pathStack.push(key)
        _nbtData.value = content
        updatePathTitle()
    }

    fun goBack(): Boolean {
        if (navigationStack.isEmpty()) return false
        
        if (fakeMapStack.isNotEmpty() && fakeMapStack.peek().fakeMap === _nbtData.value) {
            AppLogger.info("ListDebug", "goBack 离开 List 层，先 sync 再 pop: key=${fakeMapStack.peek().listKey}")
            syncOneFakeMap(fakeMapStack.peek())
            fakeMapStack.pop()
        }
        val parent = navigationStack.pop()
        pathStack.pop()
        _nbtData.value = parent
        updatePathTitle()
        return true
    }

    fun goBackScrollPosition(): Int? {
        return if (!scrollPositionStack.isEmpty()) scrollPositionStack.pop() else null
    }

    // ============================================
    
    // ============================================
    fun updatePathTitle() {
        val titleText: String
        if (_isTreeMode.value) {
            titleText = if (_isEditingPlayer.value) {
                if (_currentTargetKey.value != null && _currentTargetKey.value != "~local_player") {
                    getString(R.string.emoji_tree) + _currentTargetKey.value
                } else {
                    getString(R.string.emoji_tree_player_data)
                }
            } else {
                getString(R.string.emoji_tree_world_data)
            }
        } else {
            if (pathStack.isEmpty()) {
                titleText = if (_isEditingPlayer.value) {
                    if (_currentTargetKey.value != null && _currentTargetKey.value != "~local_player") {
                        _currentTargetKey.value ?: ""
                    } else {
                        getString(R.string.path_current_player)
                    }
                } else {
                    getString(R.string.path_current_level)
                }
            } else {
                val sb = StringBuilder(getString(R.string.path))
                for (p in pathStack) sb.append(p).append("/")
                titleText = sb.toString()
            }
        }
        _pathTitle.value = titleText
    }

    // ============================================
    
    // ============================================
    fun saveSession(dbPath: String?) {
        val data = _nbtData.value ?: return
        val sessionKey = if (_isEditingPlayer.value) _currentTargetKey.value else "level.dat"
        val session = EditorSession(
            data, navigationStack, pathStack, scrollPositionStack,
            _isEditingPlayer.value, dbPath, _currentTargetKey.value
        )
        sessionCacheMap[sessionKey] = session
    }

    fun tryRestoreSession(sessionKey: String?): Boolean {
        if (sessionCacheMap.containsKey(sessionKey)) {
            val session = sessionCacheMap[sessionKey] ?: return false
            _nbtData.value = session.data
            navigationStack.clear(); navigationStack.addAll(session.navStack)
            pathStack.clear(); pathStack.addAll(session.pathStack)
            scrollPositionStack.clear(); scrollPositionStack.addAll(session.scrollStack)
            _isEditingPlayer.value = session.isPlayerMode
            _currentTargetKey.value = session.targetKey
            updatePathTitle()
            return true
        }
        return false
    }

    fun reset() {
        _nbtData.value = null
        _isEditingPlayer.value = false
        _currentTargetKey.value = "~local_player"
        _isTreeMode.value = false
        navigationStack.clear()
        pathStack.clear()
        scrollPositionStack.clear()
        fakeMapStack.clear()
        nbtDataCache.clear()
        sessionCacheMap.clear()
        currentListData = null
        _pathTitle.value = ""
    }

    // ============================================
    
    // ============================================
    fun setViewMode(mode: Int) { _viewMode.value = mode }
    fun toggleTreeMode() { _isTreeMode.value = !_isTreeMode.value }
    fun setEditingPlayer(editing: Boolean) { _isEditingPlayer.value = editing }
    fun setTargetKey(key: String?) { _currentTargetKey.value = key }
    internal fun setRawNbtData(json: JsonObject?) {
        _nbtData.value = json
    }
    fun getRootData(): JsonObject? {
        AppLogger.info("ListDebug", "getRootData 前: fakeMapStack=${fakeMapStack.size}, navStack=${navigationStack.size}, pathStack=${pathStack.size}")
        syncListFakeMapToSource()  
        val result = if (navigationStack.isEmpty()) {
            _nbtData.value
        } else {
            navigationStack.firstElement()
        }
        AppLogger.info("ListDebug", "getRootData 后: 根数据 keys=${result?.keySet()?.joinToString(",") ?: "null"}")
        return result
    }

    
    fun rebuildFakeMapFromSource() {
        AppLogger.info("ListDebug", "rebuildFakeMapFromSource 调用: fakeMapStack=${fakeMapStack.size}")
        if (fakeMapStack.isEmpty()) { AppLogger.warn("ListDebug", "rebuild: fakeMapStack 为空，直接返回（可能丢失！）"); return }
        val info = fakeMapStack.peek()
        val listEl = info.parent.get(info.listKey) ?: run { AppLogger.warn("ListDebug", "rebuild: parent.get(${info.listKey}) 为 null"); return }
        if (!listEl.isJsonObject) { AppLogger.warn("ListDebug", "rebuild: listEl 不是 JsonObject"); return }
        val listObj = listEl.asJsonObject
        if (listObj.get("t")?.asInt != 9) { AppLogger.warn("ListDebug", "rebuild: t != 9, t=${listObj.get("t")}"); return }
        if (listObj.get("v") == null || !listObj.get("v")!!.isJsonArray) { AppLogger.warn("ListDebug", "rebuild: v 不是数组"); return }
        val newFakeMap = convertListToMap(listObj)
        info.fakeMap = newFakeMap          
        _nbtData.value = newFakeMap
        AppLogger.info("ListDebug", "rebuild 完成: 源数组 size=${listObj.get("v")!!.asJsonArray.size()}, 新假Map size=${newFakeMap.size()}")
    }

    // ============================================
    
    // ============================================

    fun wrapRawItem(rawItem: JsonElement?): JsonObject {
        if (rawItem == null) {
            val empty = JsonObject()
            empty.addProperty("t", 10)
            empty.add("v", JsonObject())
            return empty
        }
        if (rawItem.isJsonObject) {
            val obj = rawItem.asJsonObject
            if (obj.has("t")) return obj
            val wrapped = JsonObject()
            wrapped.addProperty("t", 10)
            wrapped.add("v", obj)
            return wrapped
        }
        val wrapped = JsonObject()
        wrapped.addProperty("t", 8)
        wrapped.addProperty("v", rawItem.toString())
        return wrapped
    }

    fun findOriginalListData(): JsonArray? {
        
        if (fakeMapStack.isNotEmpty()) {
            val info = fakeMapStack.peek()
            AppLogger.info("ListDebug", "findOriginalListData 走 fakeMapStack: listKey=${info.listKey}")
            val listEl = info.parent.get(info.listKey)
            if (listEl != null && listEl.isJsonObject) {
                val v = listEl.asJsonObject.get("v")
                if (v != null && v.isJsonArray) {
                    AppLogger.info("ListDebug", "findOriginalListData 返回数组 size=${v.asJsonArray.size()}")
                    return v.asJsonArray
                }
            }
            AppLogger.warn("ListDebug", "findOriginalListData: fakeMapStack 分支未找到数组")
            return null
        }
        
        AppLogger.info("ListDebug", "findOriginalListData 走兜底 pathStack: pathStack=${pathStack.size}")
        if (pathStack.isEmpty()) return null
        try {
            val root = if (navigationStack.isEmpty()) _nbtData.value else navigationStack.firstElement()
            var current: JsonObject = root ?: return null
            for (i in 0 until pathStack.size - 1) {
                val pathKey = pathStack[i] ?: continue
                val el = current.get(pathKey) ?: return null
                if (el.isJsonObject) {
                    val obj = el.asJsonObject
                    current = if (obj.has("v") && obj.get("v").isJsonObject)
                        obj.getAsJsonObject("v") else obj
                } else return null
            }
            val listKey = pathStack.peek() ?: return null
            val listEl = current.get(listKey) ?: return null
            if (listEl.isJsonObject && listEl.asJsonObject.has("v") &&
                listEl.asJsonObject.get("v").isJsonArray
            ) {
                return listEl.asJsonObject.getAsJsonArray("v")
            }
        } catch (_: Exception) {}
        return null
    }

    fun convertListToMap(listData: JsonObject): JsonObject {
        val fakeMap = JsonObject()
        val arr = listData.get("v").asJsonArray
        val itemType = listData.get("itemType").asInt
        for (i in 0..<arr.size()) {
            val wrapper = JsonObject()
            wrapper.addProperty("t", itemType)
            wrapper.add("v", arr.get(i))
            fakeMap.add(i.toString(), wrapper)
        }
        return fakeMap
    }

    
    fun syncListFakeMapToSource() {
        AppLogger.info("ListDebug", "syncListFakeMapToSource: fakeMapStack=${fakeMapStack.size}")
        
        for (info in fakeMapStack.asReversed()) {
            syncOneFakeMap(info)
        }
    }

    private fun syncOneFakeMap(info: FakeMapInfo) {
        try {
            val listEl = info.parent.get(info.listKey)
            if (listEl == null || !listEl.isJsonObject) {
                AppLogger.warn("ListDebug", "sync: parent.get(${info.listKey}) 为 null 或非 Object")
                return
            }
            val listObj = listEl.asJsonObject
            if (listObj.get("t")?.asInt != 9) {
                AppLogger.warn("ListDebug", "sync: listKey=${info.listKey}, t=${listObj.get("t")} != 9")
                return
            }

            val v = listObj.get("v")
            if (v == null || !v.isJsonArray) {
                AppLogger.warn("ListDebug", "sync: listKey=${info.listKey}, v 不是数组")
                return
            }

            val fakeMap = info.fakeMap
            val sortedKeys = fakeMap.keySet()
                .mapNotNull { it.toIntOrNull() }
                .sorted()

            val newArray = JsonArray()
            for (key in sortedKeys) {
                val wrapper = fakeMap.get(key.toString())
                if (wrapper != null && wrapper.isJsonObject) {
                    val wrappedVal = wrapper.asJsonObject.get("v")
                    if (wrappedVal != null) newArray.add(wrappedVal)
                }
            }
            
            listObj.add("v", newArray)
            AppLogger.info("ListDebug", "sync: listKey=${info.listKey}, 假Map=${fakeMap.size()}项 -> 源数组=${newArray.size()}项")
        } catch (e: Exception) {
            AppLogger.error("ListDebug", "sync 异常: ${e.message}", e)
        }
    }

    fun syncListModeFromPath(path: MutableList<String?>?) {
        navigationStack.clear()
        pathStack.clear()
        scrollPositionStack.clear()
        fakeMapStack.clear()
        currentListData = _nbtData.value
        if (path.isNullOrEmpty()) return

        var currentPtr = _nbtData.value
        try {
            for (key in path) {
                if (!currentPtr!!.has(key)) break
                val itemWrapper = currentPtr.getAsJsonObject(key)
                val type = itemWrapper.get("t").asInt
                navigationStack.push(currentPtr)
                pathStack.push(key)
                scrollPositionStack.push(0)
                val v = itemWrapper.get("v")
                if (type == 10) {
                    currentPtr = v.asJsonObject
                } else if (type == 9) {
                    val fake = convertListToMap(itemWrapper)
                    
                    fakeMapStack.push(FakeMapInfo(currentPtr!!, key ?: "", fake))
                    currentPtr = fake
                } else break
            }
            currentListData = currentPtr
        } catch (_: Exception) {}
    }

    // ============================================
    
    // ============================================
    inner class EditorSession(
        var data: JsonObject?,
        n: Stack<JsonObject?>,
        p: Stack<String?>,
        s: Stack<Int?>,
        var isPlayerMode: Boolean,
        var dbPath: String?,
        var targetKey: String?
    ) {
        var navStack: Stack<JsonObject?> = Stack()
        var pathStack: Stack<String?>
        var scrollStack: Stack<Int?>

        init {
            this.navStack.addAll(n)
            this.pathStack = Stack(); this.pathStack.addAll(p)
            this.scrollStack = Stack(); this.scrollStack.addAll(s)
        }
    }
}