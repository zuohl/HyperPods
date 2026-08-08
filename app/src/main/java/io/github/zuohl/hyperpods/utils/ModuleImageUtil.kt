package io.github.zuohl.hyperpods.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.Log
import io.github.zuohl.hyperpods.BuildConfig

/**
 * Loads module-owned images from the module's `assets/` across host processes.
 *
 * The hook layer runs inside host processes (com.android.bluetooth / com.milink.service /
 * com.xiaomi.bluetooth) and reads the module APK via [Context.createPackageContext]. Looking
 * images up by resource *name* ([android.content.res.Resources.getIdentifier]) breaks on the
 * release build because the LSPosed `resopt` plugin collapses resource names (`res/0z.png` …),
 * so name lookups return 0. Resource *IDs* (R constants) survive resopt, but reading from
 * assets is immune to BOTH name-collapsing AND resource-ID shift across module updates, and
 * matches the existing `DeviceModelRegistry` assets pattern.
 */
object ModuleImageUtil {
    private const val TAG = "HyperPods-ModuleImg"

    /** Load a bitmap from the module assets by [assetPath] (e.g. `"img_left.png"`). */
    fun bitmap(context: Context, assetPath: String): Bitmap? {
        val ctx = moduleContext(context) ?: return null
        return runCatching {
            ctx.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        }.onFailure { Log.w(TAG, "load bitmap $assetPath failed", it) }.getOrNull()
    }

    /** Load a module drawable by its compiled R constant [resId] (IDs survive resopt). */
    fun drawable(context: Context, resId: Int): Drawable? {
        val ctx = moduleContext(context) ?: return null
        return runCatching { ctx.getDrawable(resId) }
            .onFailure { Log.w(TAG, "load drawable #$resId failed", it) }.getOrNull()
    }

    private fun moduleContext(context: Context): Context? =
        if (context.packageName == BuildConfig.APPLICATION_ID) context
        else runCatching {
            context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
        }.onFailure { Log.w(TAG, "createPackageContext failed", it) }.getOrNull()
}
