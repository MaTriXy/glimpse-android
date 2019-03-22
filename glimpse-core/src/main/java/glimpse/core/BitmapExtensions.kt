@file:JvmName("BitmapUtils")

package glimpse.core

import android.graphics.*
import android.util.TimingLogger
import glimpse.core.ArrayUtils.generateEmptyTensor
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min


@JvmOverloads
fun Bitmap.crop(
    centerX: Float,
    centerY: Float,
    outWidth: Int,
    outHeight: Int,
    recycled: Bitmap? = null
): Bitmap {
    val scale: Float
    var xTranslation = 0f
    var yTranslation = 0f

    if (width * outHeight > outWidth * height) {
        scale = outHeight.toFloat() / height.toFloat()

        xTranslation = -width * scale * centerX + outWidth / 2f
        xTranslation = max(min(xTranslation, 0f), outWidth - width * scale)
    } else {
        scale = outWidth.toFloat() / width.toFloat()

        yTranslation = -height * scale * centerY + outHeight / 2f
        yTranslation = max(min(yTranslation, 0f), outHeight - height * scale)
    }

    val matrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate(xTranslation, yTranslation)
    }

    val target = recycled ?: Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)

    Canvas(target).drawBitmap(this, matrix, Paint(Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG))

    return target
}

@JvmOverloads
fun Bitmap.debugHeatMap(
    temperature: Float = 0.2f,
    lowerBound: Float = 0.25f
): Bitmap {
    val scaledBitmap = Bitmap.createScaledBitmap(this, 176, 176, false)
    val pixels = IntArray(scaledBitmap.width * scaledBitmap.height)
    scaledBitmap.getPixels(pixels, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)

    // setup tensors
    val output = generateEmptyTensor(1, scaledBitmap.height / 8, scaledBitmap.width / 8, 1)

    val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 176 * 176 * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    pixels.forEach { pixel ->
        inputBuffer.putFloat((pixel shr 16 and 0xFF) / 255f)
        inputBuffer.putFloat((pixel shr 8 and 0xFF) / 255f)
        inputBuffer.putFloat((pixel and 0xFF) / 255f)
    }

    intpreter.runThreadSafe(inputBuffer, output)

    // calculate tempered softmax
    val flattened = output[0].flattened()
    val softmaxed = MathUtils.softMax(flattened, temperature = temperature)
    val reshaped = softmaxed.reshape(output[0].size, output[0][0].size)

    // get averaged center
    val focusArea = MathUtils.getLargestFocusArea(reshaped[0][0], lowerBound = lowerBound)

    return softmaxed
        .let { a ->
            a.map {
                val intensity = 255 * it / (a.max() ?: 1f)
                if (it >= (a.max() ?: 0f) * lowerBound) Pair(intensity, 1)
                else Pair(intensity, 0)
            }
        }
        .let { a ->
            val newBitmap = Bitmap
                .createBitmap(
                    floor(scaledBitmap.width / 8f).toInt(),
                    floor(scaledBitmap.height / 8f).toInt(),
                    Bitmap.Config.ARGB_8888
                )

            a.forEachIndexed { index, (value, focused) ->
                val (pos_x, pos_y) = index % output[0][0].size to floor(1.0 * index / output[0][0].size).toInt()
                val (focus_x, focus_y) = focusArea.x * output[0][0].size to focusArea.y * output[0].size
                val color = if (focus_x.toInt() == pos_x && focus_y.toInt() == pos_y) {
                    Color.rgb(255, 0, 0)
                } else if (focused == 1) {
                    Color.rgb(value.toInt() / 2, value.toInt(), value.toInt() / 2)
                } else {
                    Color.rgb(value.toInt(), value.toInt(), value.toInt())
                }

                newBitmap.setPixel(pos_x, pos_y, color)
            }

            newBitmap
        }.let {
            Bitmap.createScaledBitmap(it, 176, 176, false)
        }
}


@Synchronized
fun Interpreter.runThreadSafe(inputBuffer: ByteBuffer, output: Array<Array<Array<FloatArray>>>) {
    run(inputBuffer, output)
}


@JvmOverloads
fun Bitmap.findCenter(
    temperature: Float = 0.2f,
    lowerBound: Float = 0.25f
): Pair<Float, Float> {
    val timings = TimingLogger("GlimpseDebug", "predict")
    // resize bitmap to make process faster and better
    val scaledBitmap = Bitmap.createScaledBitmap(this, 176, 176, false)
    val pixels = IntArray(scaledBitmap.width * scaledBitmap.height)
    scaledBitmap.getPixels(pixels, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)
    timings.addSplit("scale input")

    // setup tensors
    val output = generateEmptyTensor(1, scaledBitmap.height / 8, scaledBitmap.width / 8, 1)

    val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 176 * 176 * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    pixels.forEach { pixel ->
        inputBuffer.putFloat((pixel shr 16 and 0xFF) / 255f)
        inputBuffer.putFloat((pixel shr 8 and 0xFF) / 255f)
        inputBuffer.putFloat((pixel and 0xFF) / 255f)
    }
    timings.addSplit("prepare input")

    intpreter.runThreadSafe(inputBuffer, output)

    timings.addSplit("inference")

    // calculate tempered softmax
    val flattened = output[0].flattened()
    val softmaxed = MathUtils.softMax(flattened, temperature = temperature)
    val reshaped = softmaxed.reshape(output[0].size, output[0][0].size)
    timings.addSplit("post-process")

    timings.dumpToLog()

    // get averaged center
    return MathUtils.getLargestFocusArea(reshaped[0][0], lowerBound = lowerBound)
}

