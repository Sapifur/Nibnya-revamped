package yhjmew.minecraft.nbteditor

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.io.File

object SafPathResolver {

    /** Try to resolve a SAF tree URI to a real filesystem path.
     *  Returns null when that is not possible (use DocumentFile API instead). */
    fun resolveTreeUriToPath(context: Context, treeUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            when {
                docId.startsWith("primary:") ->
                    MainActivity.storageRoot() + docId.removePrefix("primary:")
                docId.contains(":") -> {
                    val parts = docId.split(":")
                    if (parts.size == 2) "/storage/${parts[0]}/${parts[1]}" else null
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /** Returns true when the path can be reached via the plain File API. */
    fun isProbablyUsablePath(path: String): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && path.contains("/Android/data/")) return false
        if (Build.VERSION.SDK_INT >= 30 && path.contains("/Android/obb/")) return false
        val file = File(path)
        return file.exists() && file.canRead()
    }

    /** Best-effort guess of the Minecraft worlds root from a SAF tree URI. */
    fun guessWorldRootFromUri(treeUri: Uri): String? {
        val uriStr = treeUri.toString()
        return when {
            uriStr.contains("com.mojang.minecraftpe") ->
                MainActivity.storageRoot() +
                        "Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftWorlds/"
            uriStr.contains("primary:") -> {
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                if (docId.startsWith("primary:"))
                    MainActivity.storageRoot() + docId.removePrefix("primary:")
                else null
            }
            else -> null
        }
    }
}
