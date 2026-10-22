package dev.supermessage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where thumbnails are decoded. A decode reads a file and must stay off the
 * main thread; previews and tests provide `Dispatchers.Unconfined` so a frame
 * is taken after the picture is there, not before.
 */
internal val LocalThumbnailDispatcher = compositionLocalOf<CoroutineDispatcher> { Dispatchers.IO }

/**
 * A small decoded copy of the image at [path], drawn cropped to its box (iOS
 * `ComposerView.swift` `Thumbnail`). Downsampled while decoding: a library
 * photo is twelve megapixels, and decoding all of it for a 44dp square is
 * 48 MB for a picture the size of a stamp.
 *
 * Draws nothing until the decode lands, and nothing if it cannot (the caller
 * puts an icon behind it), and [tag] is on the picture itself, so a test that
 * finds it has found a decoded image.
 */
@Composable
internal fun Thumbnail(path: String, size: Dp, tag: String, modifier: Modifier = Modifier) {
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    val dispatcher = LocalThumbnailDispatcher.current
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path, pixels) {
        bitmap = withContext(dispatcher) { decodeThumbnail(path, pixels) }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.testTag(tag),
        )
    }
}

/**
 * Decodes [path] at the smallest power-of-two reduction that still has at
 * least [pixels] on its short side, rotated upright by its EXIF orientation.
 * `null` when the file is not an image this device can decode.
 */
internal fun decodeThumbnail(path: String, pixels: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= pixels && bounds.outHeight / (sample * 2) >= pixels) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        decoded?.let { upright(it, path) }
    }
} catch (e: Exception) {
    null
}

private fun upright(bitmap: Bitmap, path: String): Bitmap {
    val degrees = try {
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (e: Exception) {
        0f
    }
    if (degrees == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
