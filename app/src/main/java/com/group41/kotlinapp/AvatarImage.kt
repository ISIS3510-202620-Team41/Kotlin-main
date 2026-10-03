package com.group41.kotlinapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.transform.CircleCropTransformation
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.UserDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Error con un mensaje ya listo para mostrar al usuario */
class AvatarException(message: String) : Exception(message)

/**
 * Prepara la foto de perfil antes de subirla.
 *
 * Se reduce y se reencoda como JPEG en el teléfono: una foto de cámara pesa
 * varios MB (el backend acepta hasta 5 MB) y puede venir en HEIC, que el
 * backend no acepta. El backend la vuelve a reducir a 512 px y le quita el EXIF.
 */
object AvatarImage {

    private const val MAX_SIDE = 1024
    private const val JPEG_QUALITY = 85
    private const val MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024

    private val downloader = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Imagen elegida de la galería. Llamar fuera del hilo principal. */
    fun fromUri(context: Context, uri: Uri): ByteArray {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder aplica la rotación del EXIF: sin esto las fotos de
            // cámara en vertical se verían acostadas.
            decodeScaled(ImageDecoder.createSource(context.contentResolver, uri))
        } else {
            decodeLegacy { context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes() } }
        } ?: throw AvatarException("No se pudo leer la imagen")
        return toJpeg(bitmap)
    }

    /** Imagen pegada como URL: se descarga y se sube como archivo. */
    fun fromUrl(url: String): ByteArray {
        val request = try {
            Request.Builder().url(url.trim()).build()
        } catch (e: IllegalArgumentException) {
            throw AvatarException("Esa URL no es válida")
        }

        val bytes = try {
            downloader.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw AvatarException("No se pudo descargar la imagen (${response.code})")
                val body = response.body ?: throw AvatarException("La URL no devolvió nada")
                val type = body.contentType()
                if (type != null && type.type != "image") throw AvatarException("Esa URL no es una imagen")
                if (body.contentLength() > MAX_DOWNLOAD_BYTES) throw AvatarException("La imagen es demasiado grande")
                body.bytes()
            }
        } catch (e: IOException) {
            // Incluye el bloqueo de http:// sin cifrar por network_security_config
            throw AvatarException(
                if (url.trim().startsWith("http://")) "Usa una URL que empiece por https://"
                else "No se pudo descargar la imagen"
            )
        }

        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            runCatching { decodeScaled(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) }.getOrNull()
        } else {
            decodeLegacy { bytes }
        } ?: throw AvatarException("Esa URL no es una imagen")
        return toJpeg(bitmap)
    }

    /** Parte multipart "file" que espera POST /api/users/me/avatar */
    fun toPart(jpeg: ByteArray): MultipartBody.Part =
        MultipartBody.Part.createFormData(
            "file", "avatar.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())
        )

    private fun decodeScaled(source: ImageDecoder.Source): Bitmap? =
        runCatching {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val scale = MAX_SIDE.toFloat() / max(info.size.width, info.size.height)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
                // Los bitmaps de hardware no se pueden dibujar en un Canvas
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()

    /** Android 7–8: BitmapFactory no corrige la rotación del EXIF. */
    private fun decodeLegacy(readBytes: () -> ByteArray?): Bitmap? {
        val bytes = readBytes() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** JPEG no tiene transparencia: un PNG transparente se aplana sobre blanco. */
    private fun toJpeg(bitmap: Bitmap): ByteArray {
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return ByteArrayOutputStream().use { out ->
            flat.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }
}

/** Iniciales del nombre: "Juan García" -> "JG" */
fun initialsOf(name: String) = name.trim().split(Regex("\\s+"))
    .filter { it.isNotEmpty() }
    .take(2)
    .joinToString("") { it.first().uppercase() }
    .ifEmpty { "?" }

/**
 * Muestra la foto en círculo sobre las iniciales. Sin foto, o si no carga,
 * quedan visibles las iniciales.
 */
fun bindAvatar(image: ImageView, initials: TextView, user: UserDto) {
    initials.text = initialsOf(user.name)
    val url = ApiClient.mediaUrl(user.avatarUrl)
    if (url == null) {
        image.visibility = View.GONE
        image.setImageDrawable(null)
        return
    }
    image.visibility = View.VISIBLE
    image.load(url) {
        crossfade(true)
        transformations(CircleCropTransformation())
        listener(onError = { _, _ -> image.visibility = View.GONE })
    }
}
