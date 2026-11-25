package com.group41.kotlinapp

import android.graphics.Bitmap
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.SuccessResult
import com.group41.kotlinapp.cache.ActivityThumbnailPrefetcher
import com.group41.kotlinapp.cache.activityThumbnailRequest
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.CipherSuite
import okhttp3.Handshake
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityThumbnailPrefetcherTest {
    @Test
    fun prefetchStoresImageOnDiskAndSkipsAlreadyCachedUrl() = runBlocking {
        verifyPrefetch("public, max-age=3600", expectDiskHit = true)
    }

    @Test
    fun prefetchHonorsNoStore() = runBlocking {
        verifyPrefetch("no-store", expectDiskHit = false)
    }

    @OptIn(ExperimentalCoilApi::class)
    private suspend fun verifyPrefetch(cacheControl: String, expectDiskHit: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val url = "https://example.test/${UUID.randomUUID()}.png"
        val requests = AtomicInteger()
        val png = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }

        val loader = (context as App).activityThumbnailLoader.newBuilder()
            .memoryCache { MemoryCache.Builder(context).build() }
            .networkObserverEnabled(false)
            .okHttpClient {
                OkHttpClient.Builder().addInterceptor { chain ->
                    requests.incrementAndGet()
                    val now = System.currentTimeMillis()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .handshake(Handshake.get(
                            TlsVersion.TLS_1_3,
                            CipherSuite.TLS_AES_128_GCM_SHA256,
                            emptyList(),
                            emptyList()
                        ))
                        .sentRequestAtMillis(now)
                        .receivedResponseAtMillis(now)
                        .code(200)
                        .message("OK")
                        .header("Cache-Control", cacheControl)
                        .body(png.toResponseBody("image/png".toMediaType()))
                        .build()
                }.build()
            }
            .build()

        try {
            val prefetcher = ActivityThumbnailPrefetcher(context, loader)
            prefetcher.prefetchThumbnails(listOf("", "  ", url, " $url "))
            loader.memoryCache?.clear()
            prefetcher.prefetchThumbnails(listOf(url))

            if (expectDiskHit) {
                loader.memoryCache?.clear()
                val result = loader.execute(
                    activityThumbnailRequest(context, url)
                        .networkCachePolicy(CachePolicy.DISABLED)
                        .build()
                ) as SuccessResult
                assertEquals(DataSource.DISK, result.dataSource)
                assertEquals(1, requests.get())
            } else {
                withContext(Dispatchers.IO) {
                    loader.diskCache?.openSnapshot(url).use { assertNull(it) }
                }
                assertEquals(2, requests.get())
            }
        } finally {
            loader.shutdown()
            withContext(Dispatchers.IO) { loader.diskCache?.remove(url) }
        }
    }
}
