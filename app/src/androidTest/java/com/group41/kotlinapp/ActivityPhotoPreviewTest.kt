package com.group41.kotlinapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.Coil
import coil.decode.DataSource
import coil.request.SuccessResult
import coil.result
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityPhotoPreviewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun repeatLoadUsesMemoryAndRetakeUsesNewCapture() = runBlocking {
        val original = capture(Color.RED)
        val retake = capture(Color.BLUE)
        val loader = Coil.imageLoader(context)
        try {
            val first = loader.execute(ActivityPhotoPreview.request(context, original).build())
                as SuccessResult
            val second = loadConfirmationPreview(original)
            val replacement = loader.execute(ActivityPhotoPreview.request(context, retake).build())
                as SuccessResult

            assertEquals(DataSource.DISK, first.dataSource)
            assertEquals(DataSource.MEMORY_CACHE, second.dataSource)
            assertEquals(first.memoryCacheKey, second.memoryCacheKey)
            assertEquals(DataSource.DISK, replacement.dataSource)
            assertNotEquals(first.memoryCacheKey, replacement.memoryCacheKey)
            assertTrue(original.isFile)
            assertTrue(retake.isFile)
        } finally {
            original.delete()
            retake.delete()
        }
    }

    private fun loadConfirmationPreview(photo: File): SuccessResult {
        val intent = Intent(context, CreateActivityStep3Activity::class.java)
            .putExtra(CreateActivityStep1Activity.EXTRA_PHOTO_PATH, photo.absolutePath)
        ActivityScenario.launch<CreateActivityStep3Activity>(intent).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                var result: SuccessResult? = null
                scenario.onActivity { activity ->
                    val preview = activity.findViewById<ImageView>(R.id.iv_activity_photo)
                    preview.result?.let {
                        assertEquals(View.VISIBLE, preview.visibility)
                        result = it as SuccessResult
                    }
                }
                result?.let { return it }
                SystemClock.sleep(25)
            }
        }
        error("Step 3 did not display the captured photograph")
    }

    @Test
    fun missingCaptureClearsPreviewAndCancelsReplacement() {
        val photo = capture(Color.RED)
        val missing = ActivityPhotoStorage.newPhotoFile(context)
        val intent = Intent(context, CreateActivityStep3Activity::class.java)
            .putExtra(CreateActivityStep1Activity.EXTRA_PHOTO_PATH, missing.absolutePath)
        try {
            ActivityScenario.launch<CreateActivityStep3Activity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    val preview = activity.findViewById<ImageView>(R.id.iv_activity_photo)
                    assertEquals(View.GONE, preview.visibility)
                    preview.setImageResource(android.R.drawable.ic_menu_camera)
                    ActivityPhotoPreview.show(preview, photo)
                    ActivityPhotoPreview.show(preview, missing)
                }
                scenario.onActivity { activity ->
                    val preview = activity.findViewById<ImageView>(R.id.iv_activity_photo)
                    assertEquals(View.GONE, preview.visibility)
                    assertNull(preview.drawable)
                }
            }
        } finally {
            photo.delete()
        }
    }

    private fun capture(color: Int): File {
        val file = ActivityPhotoStorage.newPhotoFile(context)
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
