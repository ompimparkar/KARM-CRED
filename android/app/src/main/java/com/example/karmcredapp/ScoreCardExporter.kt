package com.example.karmcredapp

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Share-as-image — the Android equivalent of the web dashboard's
 * "Download PDF" (print stylesheet) button. Renders a 1080×1420 dark score
 * card (brand, id + cohort, big band-coloured number, summary, key metrics,
 * footer) to a PNG in cacheDir, then fires the system share sheet through
 * the FileProvider declared in the manifest.
 */
object ScoreCardExporter {

    fun share(activity: Activity, data: TrustScoreResponse) {
        try {
            val bitmap = render(activity, data)
            val dir = File(activity.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "karm_cred_score_${data.userId}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()

            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(
                    Intent.EXTRA_TEXT,
                    "My KARM CRED TrustScore: ${data.predictedTrustScore} " +
                        "(${bandLabel(data.predictedTrustScore)}). " +
                        "Where your daily hustle builds your credit."
                )
            }
            activity.startActivity(Intent.createChooser(send, "Share score card"))
            Motion.fadeTo(activity)
        } catch (e: Exception) {
            Toast.makeText(
                activity, "Could not create score card: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun bandLabel(score: Int): String = when {
        score >= 700 -> "Good"
        score >= 500 -> "Building"
        else -> "At risk"
    }

    // ------------------------------------------------------------ renderer

    private fun render(activity: Activity, data: TrustScoreResponse): Bitmap {
        val w = 1080
        val h = 1420
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val score = data.predictedTrustScore
        val bandColor = when {
            score >= 700 -> Color.parseColor("#4ade80")
            score >= 500 -> Color.parseColor("#facc15")
            else -> Color.parseColor("#f87171")
        }

        val bg = Paint().apply { color = Color.parseColor("#0f172a") }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38bdf8")
            textSize = 54f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("KARM CRED", 72f, 128f, brand)

        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94a3b8")
            textSize = 30f
        }
        canvas.drawText("Trust Score Report", 72f, 176f, sub)

        val divider = Paint().apply {
            color = Color.parseColor("#334155")
            strokeWidth = 2f
        }
        canvas.drawLine(72f, 216f, w - 72f, 216f, divider)

        val idPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#e2e8f0")
            textSize = 44f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(data.userId, 72f, 292f, idPaint)

        val cohortText = listOfNotNull(
            data.cohort?.gig_type, data.cohort?.city, data.cohort?.vehicle_class
        ).joinToString(" · ").ifBlank { "cohort n/a" }
        val cohortPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94a3b8")
            textSize = 28f
        }
        canvas.drawText(cohortText, 72f, 340f, cohortPaint)

        val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bandColor
            textSize = 240f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(score.toString(), w / 2f, 620f, scorePaint)

        val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bandColor
            textSize = 34f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(
            "TRUST BAND: ${bandLabel(score).uppercase()}", w / 2f, 676f, capPaint
        )

        val summaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#cbd5e1")
            textSize = 36f
        }
        val summary = StaticLayout.Builder
            .obtain(data.summary.orEmpty(), 0, data.summary.orEmpty().length,
                summaryPaint, w - 144)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.25f)
            .setMaxLines(5)
            .build()
        canvas.save()
        canvas.translate(72f, 760f)
        summary.draw(canvas)
        canvas.restore()

        // key metrics strip
        val metricLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748b")
            textSize = 26f
        }
        val metricValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38bdf8")
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
        }
        var mx = 72f
        val metrics = listOf(
            "Floor" to UiUtils.fmtFeature(data.features?.income_floor_ratio, "ratio"),
            "Trend" to UiUtils.fmtFeature(data.features?.income_trend_slope, "signed_num"),
            "Rating" to UiUtils.fmtFeature(data.features?.avg_platform_rating, "rating"),
            "Complete" to UiUtils.fmtFeature(data.features?.data_completeness, "ratio")
        )
        for ((label, value) in metrics) {
            canvas.drawText(label, mx, 1180f, metricLabel)
            canvas.drawText(value, mx, 1224f, metricValue)
            mx += 250f
        }

        canvas.drawLine(72f, 1276f, w - 72f, 1276f, divider)

        val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date())
        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748b")
            textSize = 26f
        }
        canvas.drawText("$date  ·  KARM CRED", 72f, 1330f, footer)
        val tagline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#475569")
            textSize = 24f
        }
        canvas.drawText(
            "Where your daily hustle builds your credit.",
            72f, 1372f, tagline
        )

        return bmp
    }
}
