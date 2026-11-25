package com.group41.kotlinapp.cache

import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.disk.DiskCache
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.size.Scale
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Prefetches thumbnails for My Activities through the app's shared Coil loader.
 *
 * A batch checks disk before downloading. Coil keeps the downloaded bytes on disk
 * for later sessions and can reuse the decoded thumbnail in memory during this one.
 * Each prefetcher instance limits concurrent requests across all its batches.
 *
 * @param context Context used to convert the thumbnail size from dp to pixels.
 * @param imageLoader The dedicated thumbnail loader also used by My Activities.
 */
class ActivityThumbnailPrefetcher(context: Context, private val imageLoader: ImageLoader) {
    // Use the application context so the prefetcher can outlive the screen.
    private val appContext = context.applicationContext

    // Concurrent calls on this instance share the same three permits.
    //Semaphore gives the prefetcher x permits. If x requests are already running,
    // The x+1th coroutine suspends without blocking a thread
    private val requests = Semaphore(3)

    /**
     * Trims URLs, discards blank and duplicate entries, and waits for the batch.
     * Each image can fail independently. Canceling the caller cancels the batch.
     *
     * @param imageUrls URLs of the thumbnails to prepare.
     */
    suspend fun prefetchThumbnails(imageUrls: List<String>): Unit = withContext(Dispatchers.IO) {
        val urls = imageUrls.map(String::trim).filter(String::isNotEmpty).distinct()
        val sizePx = (ACTIVITY_THUMBNAIL_SIZE_DP * appContext.resources.displayMetrics.density)
            .roundToInt()

        // Keep disk checks off the main thread and isolate failures between child coroutines.
        supervisorScope {
            for (url in urls) {
                launch {
                    // Waiting for a permit suspends this coroutine. withPermit releases
                    // it when the request finishes, fails, or is canceled.
                    requests.withPermit {
                        try {
                            prefetch(url, sizePx)
                        } catch (e: CancellationException) {
                            // Preserve cancellation so this child exits as canceled.
                            throw e
                        } catch (_: Exception) {
                            Log.w(TAG, "Could not prefetch an activity thumbnail")
                        }
                    }
                }
            }
        }
    }

    /**
     * Reuses an existing disk entry or asks Coil to download the image and cache it.
     *
     * @param url Trimmed image URL, also used as the memory and disk cache key.
     * @param sizePx Requested thumbnail width and height in pixels.
     */
    private suspend fun prefetch(url: String, sizePx: Int) {
        val request = activityThumbnailRequest(appContext, url)
            .size(sizePx)
            .scale(Scale.FILL)
            // A memory hit would skip the fetch and leave the disk cache empty.
            .memoryCachePolicy(CachePolicy.WRITE_ONLY)
            .build()

        // Coil checks disk before going to the network and revalidates stale entries
        // when required by the response headers.
        // Coil reports ordinary download failures, such as HTTP 404, as ErrorResult.
        val result = imageLoader.execute(request)
        if (result is ErrorResult) {
            val failure = result.throwable
            if (failure is CancellationException) throw failure
            Log.w(TAG, "Could not prefetch an activity thumbnail")
        }
    }

    private companion object {
        const val TAG = "ThumbnailPrefetcher"
    }
}

/** Thumbnail size in dp shared by prefetching and My Activities. */
internal const val ACTIVITY_THUMBNAIL_SIZE_DP = 56

/**
 * Builds the loader shared by thumbnail prefetching and display. The bounded disk
 * cache is separate from other images and persists across app restarts.
 */
internal fun createActivityThumbnailLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context.applicationContext)
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.applicationContext.noBackupFilesDir, "activity_thumbnail_cache"))
                .maxSizeBytes(25L * 1024 * 1024)
                .build()
        }
        // Activity photos may have server restrictions, so let Coil honor their headers.
        .respectCacheHeaders(true)
        .crossfade(true)
        .build()

/** Gives display and prefetch requests the same cache identity for a thumbnail URL. */
internal fun activityThumbnailRequest(context: Context, url: String): ImageRequest.Builder =
    ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey(url)
        .diskCacheKey(url)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
