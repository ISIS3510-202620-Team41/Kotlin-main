package com.group41.kotlinapp

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.annotation.MainThread
import coil.Coil
import coil.dispose
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import java.io.File
import kotlin.math.roundToInt

/** Displays local camera captures with a shared, memory-cached decode across creation steps. */
internal object ActivityPhotoPreview {
    /**
     * Replaces the view's previous request. Coil binds the new request to the activity lifecycle
     * through the ImageView target.
     */
    @MainThread
    fun show(view: ImageView, photo: File?) {
        view.dispose()
        view.setImageDrawable(null)
        view.visibility = View.GONE
        if (photo == null || !photo.isFile || photo.length() == 0L) return

        val request = request(view.context, photo)
            .target(view)
            .listener(onSuccess = { _, result ->
                view.visibility = View.VISIBLE
                if (BuildConfig.DEBUG) {
                    Log.d("ActivityPhotoPreview", "${view.context.javaClass.simpleName}: ${result.dataSource}")
                }
            })
            .build()
        Coil.imageLoader(view.context).enqueue(request)
    }

    /** Builds the same decode request for both 160dp previews regardless of their layout timing. */
    internal fun request(context: Context, photo: File): ImageRequest.Builder {
        val resources = context.resources
        val density = resources.displayMetrics.density
        val width = ((resources.configuration.screenWidthDp - 40) * density)
            .roundToInt()
            .coerceAtLeast(1)
        val height = (160 * density).roundToInt()

        return ImageRequest.Builder(context)
            .data(photo)
            // Each capture has a new UUID path, so retakes never reuse the previous bitmap.
            .memoryCacheKey("activity-photo-preview:${photo.absolutePath}")
            .memoryCachePolicy(CachePolicy.ENABLED)
            // The original is already stored locally for the upload queue.
            .diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .size(width, height)
            .scale(Scale.FILL)
    }
}
