package yhjmew.minecraft.nbteditor

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class CrashHandler private constructor() : Thread.UncaughtExceptionHandler {
    private var mContext: Context? = null
    private var mDefaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        mContext = context.applicationContext
        
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        if (!handleException(ex) && mDefaultHandler != null) {
            
            mDefaultHandler!!.uncaughtException(thread, ex)
        } else {
            try {
                
                Thread.sleep(3000)
            } catch (e: InterruptedException) {
                Log.e(TAG, "error : ", e)
            }
            
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }

    
    private fun handleException(ex: Throwable?): Boolean {
        if (ex == null) return false

        
        object : Thread() {
            override fun run() {
                Looper.prepare()
                val text = mContext!!.getString(R.string.toast_crash_collapse)

                Toast.makeText(mContext, text, Toast.LENGTH_LONG).show()
                Looper.loop()
            }
        }.start()

        
        val deviceInfo = collectDeviceInfo(mContext!!)

        
        saveCrashInfo2File(ex, deviceInfo)

        return true
    }

    
    private fun collectDeviceInfo(ctx: Context): String {
        val sb = StringBuilder()
        try {
            val pm = ctx.packageManager
            val pi = pm.getPackageInfo(ctx.packageName, PackageManager.GET_ACTIVITIES)
            if (pi != null) {
                val versionName = pi.versionName ?: "null"
                val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                    pi.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    pi.versionCode.toString()
                }
                sb.append("App Version: ").append(versionName).append(" (").append(versionCode)
                    .append(")\n")
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Error collecting info", e)
        }

        sb.append("OS Version: ").append(Build.VERSION.RELEASE).append("_")
            .append(Build.VERSION.SDK_INT).append("\n")
        sb.append("Vendor: ").append(Build.MANUFACTURER).append("\n")
        sb.append("Model: ").append(Build.MODEL).append("\n")
        val abis = Build.SUPPORTED_ABIS
        if (abis != null && abis.isNotEmpty()) {
            sb.append("CPU ABI: ").append(abis.joinToString(", ")).append("\n")
        } else {
            sb.append("CPU ABI: unknown\n")
        }

        return sb.toString()
    }

    
    
    private fun saveCrashInfo2File(ex: Throwable, deviceInfo: String?) {
        val sb = StringBuilder()
        sb.append("====== CRASH LOG ======\n")
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val time = format.format(Date())
        sb.append("Time: ").append(time).append("\n")
        sb.append(deviceInfo)
        sb.append("\n====== STACK TRACE ======\n")

        val writer: Writer = StringWriter()
        val printWriter = PrintWriter(writer)
        ex.printStackTrace(printWriter)
        var cause = ex.cause
        while (cause != null) {
            cause.printStackTrace(printWriter)
            cause = cause.cause
        }
        printWriter.close()
        val result = writer.toString()
        sb.append(result)
        sb.append("\n=======================\n")

        
        val logContent = sb.toString()
        val fileName =
            "crash-" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".log"

        
        
        try {
            val privateDir = File(mContext!!.getExternalFilesDir(null), "CrashLogs")
            if (!privateDir.exists()) privateDir.mkdirs()

            val privateFile = File(privateDir, fileName)
            val fos = FileOutputStream(privateFile)
            fos.write(logContent.toByteArray())
            fos.close()
            Log.i(TAG, "Private Log saved: " + privateFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save private log", e)
        }

        
        
        try {
            val publicDir = File(MainActivity.publicDataDir(), "Crash_Logs")
            if (!publicDir.exists()) publicDir.mkdirs()

            val publicFile = File(publicDir, fileName)
            val fos = FileOutputStream(publicFile)
            fos.write(logContent.toByteArray())
            fos.close()
            Log.i(TAG, "Public Log saved: " + publicFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save public log (Permission denied?)", e)
        }
    }

    companion object {
        private const val TAG = "CrashHandler"

        @SuppressLint("StaticFieldLeak")
        var instance: CrashHandler? = null
            get() {
                if (field == null) {
                    field = CrashHandler()
                }
                return field
            }
            private set
    }
}