package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.mvvm.safeAsync
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.clear
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.openBrowser
import com.lagradost.cloudstream3.utils.AppDebug
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.ImageLoader.buildImageLoader
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.PrintStream
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class ExceptionHandler(
    val errorFile: File,
    val onError: (() -> Unit)
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        // 1. Guard against FinalizerWatchdogDaemon TimeoutException (e.g. Amazon Fire TV / low-end Android 7.1 devices)
        if (thread.name == "FinalizerWatchdogDaemon" && (error is TimeoutException || error.cause is TimeoutException)) {
            return
        }

        // 2. Guard against WindowManager.BadTokenException (e.g. extension dialogs showing on dead activities)
        if (error is WindowManager.BadTokenException || error.cause is WindowManager.BadTokenException) {
            return
        }

        try {
            val threadId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                thread.threadId()
            } else {
                @Suppress("DEPRECATION")
                thread.id
            }

            PrintStream(errorFile).use { ps ->
                ps.println("Currently loading extension: ${PluginManager.currentlyLoading ?: "none"}")
                ps.println("Fatal exception on thread ${thread.name} ($threadId)")
                error.printStackTrace(ps)
            }
            try {
                com.lagradost.cloudstream3.utils.TelegramLogger.sendCrashLog(
                    "Fatal Exception",
                    "Thread: ${thread.name}\nExtension: ${PluginManager.currentlyLoading ?: "none"}\n" + error.stackTraceToString()
                )
            } catch (_: Throwable) {
            }
        } catch (_: FileNotFoundException) {
        }
        try {
            onError()
        } catch (_: Exception) {
        }
        exitProcess(1)
    }
}

class CloudStreamApp : Application(), SingletonImageLoader.Factory {

    private fun stopFinalizerWatchdogDaemon() {
        try {
            val clazz = Class.forName("java.lang.Daemons\$FinalizerWatchdogDaemon")
            val field = clazz.getDeclaredField("INSTANCE")
            field.isAccessible = true
            val watchdog = field.get(null)
            val stopMethod = clazz.superclass?.getDeclaredMethod("stop") ?: clazz.getDeclaredMethod("stop")
            stopMethod.isAccessible = true
            stopMethod.invoke(watchdog)
        } catch (_: Throwable) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        stopFinalizerWatchdogDaemon()

        // Track foreground activity
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                currentActivity = WeakReference(activity)
                setContext(activity)
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivity?.get() == activity) {
                    currentActivity = null
                    setContext(context)
                }
            }
        })

        // Main Looper Crash Shield
        // Catches non-fatal WindowManager.BadTokenException from background WebViews / extension dialogs
        // without killing the main looper or terminating the app.
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                } catch (t: Throwable) {
                    if (t is WindowManager.BadTokenException || t.cause is WindowManager.BadTokenException) {
                        logError(t)
                        continue
                    }
                    Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(Thread.currentThread(), t)
                }
            }
        }

        ExceptionHandler(filesDir.resolve("last_error")) {
            val intent = context!!.packageManager.getLaunchIntentForPackage(context!!.packageName)
            startActivity(Intent.makeRestartActivityTask(intent!!.component))
        }.also {
            exceptionHandler = it
            Thread.setDefaultUncaughtExceptionHandler(it)
        }

        AppDebug.isDebug = BuildConfig.DEBUG
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = base
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Coil module will be initialized globally when first loadImage() is invoked.
        return buildImageLoader(applicationContext)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            val imageLoader = SingletonImageLoader.get(this)
            when (level) {
                TRIM_MEMORY_RUNNING_CRITICAL,
                TRIM_MEMORY_COMPLETE -> {
                    imageLoader.memoryCache?.clear()
                    com.lagradost.cloudstream3.ui.home.ParentItemAdapter.sharedPool.clear()
                    com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter.sharedPool.clear()
                    com.lagradost.cloudstream3.ui.search.SearchAdapter.sharedPool.clear()
                    System.gc()
                }
                TRIM_MEMORY_RUNNING_LOW,
                TRIM_MEMORY_RUNNING_MODERATE,
                TRIM_MEMORY_MODERATE,
                TRIM_MEMORY_UI_HIDDEN -> {
                    val max = imageLoader.memoryCache?.maxSize ?: 0
                    if (max > 0) {
                        imageLoader.memoryCache?.trimToSize(max / 2)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            SingletonImageLoader.get(this).memoryCache?.clear()
            com.lagradost.cloudstream3.ui.home.ParentItemAdapter.sharedPool.clear()
            com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter.sharedPool.clear()
            com.lagradost.cloudstream3.ui.search.SearchAdapter.sharedPool.clear()
            System.gc()
        } catch (_: Throwable) {
        }
    }

    companion object {
        var exceptionHandler: ExceptionHandler? = null
        var currentActivity: WeakReference<Activity>? = null

        /** Use to get Activity from Context. */
        tailrec fun Context.getActivity(): Activity? {
            val foreground = currentActivity?.get()
            if (foreground != null && !foreground.isFinishing && !foreground.isDestroyed) {
                return foreground
            }
            return when (this) {
                is Activity -> this
                is ContextWrapper -> baseContext.getActivity()
                else -> null
            }
        }

        private var _context: WeakReference<Context>? = null
        var context
            get() = _context?.get()
            private set(value) {
                _context = WeakReference(value)
                setContext(value)
            }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
            return context?.getKey(path, valueType)
        }

        fun <T : Any> setKeyClass(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun removeKeys(folder: String): Int? {
            return context?.removeKeys(folder)
        }

        fun <T> setKey(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            context?.setKey(folder, path, value)
        }

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? {
            return context?.getKey(path, defVal)
        }

        inline fun <reified T : Any> getKey(path: String): T? {
            return context?.getKey(path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String): T? {
            return context?.getKey(folder, path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? {
            return context?.getKey(folder, path, defVal)
        }

        fun getKeys(folder: String): List<String>? {
            return context?.getKeys(folder)
        }

        fun removeKey(folder: String, path: String) {
            context?.removeKey(folder, path)
        }

        fun removeKey(path: String) {
            context?.removeKey(path)
        }

        /** If fallbackWebView is true and a fragment is supplied then it will open a WebView with the URL if the browser fails. */
        fun openBrowser(url: String, fallbackWebView: Boolean = false, fragment: Fragment? = null) {
            context?.openBrowser(url, fallbackWebView, fragment)
        }

        /** Will fall back to WebView if in TV or emulator layout. */
        fun openBrowser(url: String, activity: Activity?) {
            openBrowser(
                url,
                isLayout(TV or EMULATOR),
                (activity as? FragmentActivity)?.supportFragmentManager?.fragments?.lastOrNull()
            )
        }
    }
}
