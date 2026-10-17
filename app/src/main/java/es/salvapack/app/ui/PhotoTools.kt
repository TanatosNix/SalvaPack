package es.salvapack.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The small JPEG is shared with the product, with no public photo URLs. */
suspend fun readProductPhoto(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "No se pudo abrir la imagen." }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1440) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("No se pudo abrir la imagen.")
    val orientation = resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
        }
    }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    val scale = minOf(1f, 720f / maxOf(rotated.width, rotated.height))
    val small = Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt().coerceAtLeast(1), (rotated.height * scale).toInt().coerceAtLeast(1), true)
    val output = ByteArrayOutputStream()
    var quality = 85
    do {
        output.reset()
        small.compress(Bitmap.CompressFormat.JPEG, quality, output)
        quality -= 10
    } while (output.size() > 240_000 && quality >= 25)
    require(output.size() <= 240_000) { "La foto es demasiado grande. Prueba otra imagen." }
    val result = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    if (small !== rotated && small !== bitmap) small.recycle()
    if (rotated !== bitmap) rotated.recycle()
    bitmap.recycle()
    result
}
