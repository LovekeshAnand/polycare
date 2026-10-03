package org.polycare.app.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.content.Context
import com.paddle.ocr.model.OCRResult
import org.polycare.common.PolyCareConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.EventLog.Level
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Two script's worth of text out of one photo (M0: "ML Kit reads a sample MCP card, English +
 * Devanagari"). MCP cards, lab reports and prescriptions mix a printed English form with
 * handwritten Hindi notes, and a script-specific recognizer reads its own script far better than
 * a general one reads either — so both run over the whole image and are shown as separate
 * results, rather than guessing which recognizer "wins" per line (that fusion, plus turning
 * these lines into actual household-record fields, is M3's job, once households exist to fill).
 *
 * Both models are ML Kit's on-device recognizers. The Latin model ships inside the app; the
 * Devanagari model is downloaded once via Google Play services the first time it is used on a
 * phone (a real online moment on an otherwise offline feature) and is then cached on-device —
 * see CLAUDE.md / STATUS.md for this caveat.
 */
@Singleton
class OcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: EventLog,
) {

    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    private val paddleMutex = Mutex()
    private var latinPaddle: PaddleOCR? = null
    private var devanagariPaddle: PaddleOCR? = null

    data class Result(val latinText: String, val devanagariText: String, val ms: Long, val engine: String)

    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): Result {
        val started = System.nanoTime()
        if (hasPaddleModels()) {
            runCatching { recognizeWithPaddle(bitmap, rotationDegrees) }.onSuccess { (latin, devanagari) ->
                val ms = (System.nanoTime() - started) / 1_000_000
                events.record(Category.MODEL, "PaddleOCR PP-OCRv5 ran", mapOf("latinChars" to latin.length, "devanagariChars" to devanagari.length, "ms" to ms))
                return Result(latin, devanagari, ms, "PaddleOCR PP-OCRv5")
            }.onFailure {
                events.record(Category.MODEL, "PaddleOCR failed; falling back to ML Kit", mapOf("error" to it.javaClass.simpleName), Level.WARN)
            }
        }
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        val latinText = runCatching { latin.process(image).await().text }.getOrElse {
            events.record(Category.MODEL, "OCR (Latin) failed", mapOf("error" to it.javaClass.simpleName), Level.ERROR)
            ""
        }
        val devanagariText = runCatching { devanagari.process(image).await().text }.getOrElse {
            events.record(Category.MODEL, "OCR (Devanagari) failed", mapOf("error" to it.javaClass.simpleName), Level.ERROR)
            ""
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        events.record(
            Category.MODEL, "OCR ran",
            mapOf("latinChars" to latinText.length, "devanagariChars" to devanagariText.length, "ms" to ms),
        )
        return Result(latinText, devanagariText, ms, "ML Kit fallback")
    }

    private fun hasPaddleModels(): Boolean = runCatching {
        context.assets.open("models/ocr/det/inference.onnx").close()
        context.assets.open("models/ocr/latin/inference.onnx").close()
        context.assets.open("models/ocr/latin/inference.yml").close()
        context.assets.open("models/ocr/devanagari/inference.onnx").close()
        context.assets.open("models/ocr/devanagari/inference.yml").close()
    }.isSuccess

    private suspend fun recognizeWithPaddle(bitmap: Bitmap, rotationDegrees: Int): Pair<String, String> {
        check(OpenCVUtils.init(context)) { "OpenCV native runtime did not initialize" }
        var rotated = rotate(bitmap, rotationDegrees)
        try {
            return paddleMutex.withLock {
                val config = PaddleOCRConfig(
                    detLimitSideLen = PolyCareConfig.Ocr.detectorMaxSidePx, detLimitType = "max",
                    recScoreThresh = 0.0f, recBatchSize = 1,
                )
                suspend fun recognize(script: String, current: PaddleOCR?, image: Bitmap): Pair<List<OCRResult>, PaddleOCR> {
                    val engine = current ?: PaddleOCR.create(
                        context = context,
                        config = config,
                        engineConfig = EngineConfig(numThreads = 4),
                        detModelAssetPath = "models/ocr/det/inference.onnx",
                        recModelAssetPath = "models/ocr/$script/inference.onnx",
                        recConfigAssetPath = "models/ocr/$script/inference.yml",
                    )
                    return engine.recognize(image).results to engine
                }
                var (latinLines, loadedLatin) = recognize("latin", latinPaddle, rotated)
                latinPaddle = loadedLatin
                // A photo is often taken sideways. If most text boxes are tall, try the other two turns
                // with the fast Latin pass and keep whichever orientation the engine reads best.
                if (looksSideways(latinLines)) {
                    var best = rotated to latinLines
                    for (turn in listOf(90, 270)) {
                        val candidate = rotate(rotated, turn)
                        val (lines, engine) = recognize("latin", latinPaddle, candidate)
                        latinPaddle = engine
                        if (readability(lines) > readability(best.second)) {
                            if (best.first !== rotated) best.first.recycle()
                            best = candidate to lines
                        } else {
                            candidate.recycle()
                        }
                    }
                    if (best.first !== rotated) {
                        if (rotated !== bitmap) rotated.recycle()
                        rotated = best.first
                    }
                    latinLines = best.second
                    events.record(Category.MODEL, "OCR photo was sideways; re-read upright", mapOf("lines" to latinLines.size))
                }
                val (devanagariLines, loadedDevanagari) = recognize("devanagari", devanagariPaddle, rotated)
                devanagariPaddle = loadedDevanagari
                val (latinKept, devanagariKept) = keepBestScriptPerLine(latinLines, devanagariLines)
                // One pass over both scripts: "Name :" (Latin) and its Hindi value share a row.
                val rows = RowBuilder.rows((latinKept + devanagariKept).map(::toBox))
                val (hindiRows, englishRows) = rows.partition { DevanagariTransliterator.containsDevanagari(it) }
                englishRows.joinToString("\n") to hindiRows.joinToString("\n")
            }
        } finally {
            if (rotated !== bitmap) rotated.recycle()
        }
    }

    /**
     * Both recognizers read every detected line, so each script's output is full of garbage for the
     * other script's lines. The detector finds the same boxes for both, so match lines by position
     * and keep each one only in the script that read it with more confidence; drop weak lines.
     */
    private fun keepBestScriptPerLine(latin: List<OCRResult>, devanagari: List<OCRResult>): Pair<List<OCRResult>, List<OCRResult>> {
        val keepLatin = BooleanArray(latin.size) { true }
        val keepDeva = BooleanArray(devanagari.size) { true }
        val taken = BooleanArray(devanagari.size)
        for ((i, l) in latin.withIndex()) {
            val j = devanagari.indices.firstOrNull { !taken[it] && sameLine(l, devanagari[it]) } ?: continue
            taken[j] = true
            if (l.confidence >= devanagari[j].confidence) keepDeva[j] = false else keepLatin[i] = false
        }
        val min = PolyCareConfig.Ocr.minLineConfidence
        // The Devanagari model also reads Latin letters (and sometimes beats the Latin model on them),
        // so file each winning line by the script it actually contains, in reading order.
        val winners = (latin.filterIndexed { i, r -> keepLatin[i] && r.confidence >= min } +
            devanagari.filterIndexed { j, r -> keepDeva[j] && r.confidence >= min })
            .sortedWith(compareBy({ r -> r.box.points.minOf { it.y } }, { r -> r.box.points.minOf { it.x } }))
        return winners.partition { r -> r.text.none { it in 'ऀ'..'ॿ' } }
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap =
        if (degrees % 360 == 0) bitmap else Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true,
        )

    /** True when most readable text boxes are taller than wide: the photo was taken sideways. */
    private fun looksSideways(lines: List<OCRResult>): Boolean {
        val boxes = lines.filter { it.text.length >= 3 && it.confidence >= PolyCareConfig.Ocr.minLineConfidence }.map(::toBox)
        if (boxes.size < 3) return false
        return boxes.count { (it.maxY - it.minY) > 1.3f * (it.maxX - it.minX) } * 2 > boxes.size
    }

    /** How well the engine read an orientation: confidence-weighted count of letters and digits. */
    private fun readability(lines: List<OCRResult>): Float =
        lines.sumOf { (it.confidence * it.text.count(Char::isLetterOrDigit)).toDouble() }.toFloat()

    private fun toBox(r: OCRResult): TextBox {
        val p = r.box.points
        // Slope of the box's longer edge: how far the handwriting is tilted from level.
        val e1x = p[1].x - p[0].x; val e1y = p[1].y - p[0].y
        val e2x = p[2].x - p[1].x; val e2y = p[2].y - p[1].y
        val (dx, dy) = if (e1x * e1x + e1y * e1y >= e2x * e2x + e2y * e2y) e1x to e1y else e2x to e2y
        var angle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
        if (angle > 90f) angle -= 180f
        if (angle < -90f) angle += 180f
        return TextBox(r.text, p.minOf { it.x }, p.maxOf { it.x }, p.minOf { it.y }, p.maxOf { it.y }, slopeDeg = angle)
    }

    private fun sameLine(a: OCRResult, b: OCRResult): Boolean {
        fun bounds(r: OCRResult) = RectF(
            r.box.points.minOf { it.x }, r.box.points.minOf { it.y }, r.box.points.maxOf { it.x }, r.box.points.maxOf { it.y },
        )
        val ra = bounds(a)
        val rb = bounds(b)
        return ra.contains(rb.centerX(), rb.centerY()) || rb.contains(ra.centerX(), ra.centerY())
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
