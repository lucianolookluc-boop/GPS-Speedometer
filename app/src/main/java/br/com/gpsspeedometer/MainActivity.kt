package br.com.gpsspeedometer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var speedView: SpeedDisplay
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastLimit: Int? = null
    private var lastQueryLocation: Location? = null
    private var lastQueryAt = 0L
    private var hazards: List<RoadHazard> = emptyList()
    private val alerted = mutableMapOf<String, Long>()
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        speedView = SpeedDisplay(this)
        setContentView(speedView)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("pt", "BR"))
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (hasLocationPermission()) startGps()
        else requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            100
        )
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startGps() {
        if (!hasLocationPermission()) return
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
            } else {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1500L, 0f, this, Looper.getMainLooper())
            }
        } catch (_: SecurityException) { }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startGps()
    }

    override fun onLocationChanged(location: Location) {
        val kmh = if (location.hasSpeed()) (location.speed * 3.6f).coerceAtLeast(0f) else 0f
        speedView.setSpeed(kmh.toInt().toString())

        val previous = lastQueryLocation
        val movedEnough = previous == null || previous.distanceTo(location) >= 120f
        val timeEnough = System.currentTimeMillis() - lastQueryAt >= 15_000L
        if (movedEnough && timeEnough) {
            lastQueryLocation = Location(location)
            lastQueryAt = System.currentTimeMillis()
            queryRoadData(location)
        }
        checkHazards(location)
    }

    private fun queryRoadData(location: Location) {
        worker.execute {
            try {
                val lat = location.latitude
                val lon = location.longitude
                val query = """
                    [out:json][timeout:12];
                    (
                      way(around:100,$lat,$lon)["highway"]["maxspeed"];
                      node(around:650,$lat,$lon)["highway"="speed_camera"];
                      node(around:650,$lat,$lon)["enforcement"="traffic_signals"];
                      node(around:650,$lat,$lon)["traffic_calming"];
                    );
                    out center tags;
                """.trimIndent()
                val encoded = URLEncoder.encode(query, "UTF-8")
                val connection = URL("https://overpass-api.de/api/interpreter?data=$encoded")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 15000
                connection.requestMethod = "GET"
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val elements = JSONObject(body).optJSONArray("elements") ?: return@execute
                var nearestLimit: Int? = null
                var nearestDistance = Float.MAX_VALUE
                val foundHazards = mutableListOf<RoadHazard>()

                for (i in 0 until elements.length()) {
                    val item = elements.optJSONObject(i) ?: continue
                    val tags = item.optJSONObject("tags") ?: JSONObject()
                    val type = item.optString("type")
                    val center = item.optJSONObject("center")
                    val latValue: Double? = if (item.has("lat")) item.optDouble("lat") else center?.optDouble("lat")
                    val lonValue: Double? = if (item.has("lon")) item.optDouble("lon") else center?.optDouble("lon")
                    if (latValue == null || lonValue == null || latValue.isNaN() || lonValue.isNaN()) continue

                    val point = Location("osm").apply { latitude = latValue; longitude = lonValue }
                    val distance = location.distanceTo(point)

                    if (type == "way" && tags.has("maxspeed") && distance < nearestDistance) {
                        val parsed = parseSpeedLimit(tags.optString("maxspeed"))
                        if (parsed != null) {
                            nearestLimit = parsed
                            nearestDistance = distance
                        }
                    }

                    if (type == "node") {
                        val highway = tags.optString("highway")
                        val enforcement = tags.optString("enforcement")
                        val calming = tags.optString("traffic_calming")
                        when {
                            highway == "speed_camera" -> foundHazards.add(RoadHazard("camera", latValue, lonValue))
                            enforcement == "traffic_signals" -> foundHazards.add(RoadHazard("signal_camera", latValue, lonValue))
                            calming.isNotBlank() && calming != "no" -> foundHazards.add(RoadHazard("bump", latValue, lonValue))
                        }
                    }
                }

                mainHandler.post {
                    nearestLimit?.let { limit ->
                        speedView.setLimit(limit.toString())
                        if (lastLimit != limit) {
                            lastLimit = limit
                            speak("Limite de velocidade, $limit quilômetros por hora")
                        }
                    }
                    hazards = foundHazards
                }
            } catch (_: Exception) {
                // Sem dados: conserva a última leitura; não inventa limite nem alerta.
            }
        }
    }

    private fun parseSpeedLimit(raw: String): Int? {
        val normalized = raw.trim().lowercase(Locale.ROOT)
        if (normalized.contains("mph")) return null
        return Regex("[0-9]+").find(normalized)?.value?.toIntOrNull()?.takeIf { it in 10..160 }
    }

    private fun checkHazards(location: Location) {
        if (!location.hasBearing()) return
        val now = System.currentTimeMillis()
        for (hazard in hazards) {
            val point = Location("hazard").apply { latitude = hazard.lat; longitude = hazard.lon }
            val distance = location.distanceTo(point)
            if (distance !in 20f..450f) continue
            val delta = abs(((location.bearingTo(point) - location.bearing + 540f) % 360f) - 180f)
            if (delta > 75f) continue
            val key = "${hazard.kind}:${hazard.lat}:${hazard.lon}"
            if (now - (alerted[key] ?: 0L) < 120_000L) continue
            alerted[key] = now
            when (hazard.kind) {
                "camera" -> speak("Radar na via")
                "signal_camera" -> speak("Radar de semáforo na via")
                "bump" -> speak("Lombada na via")
            }
        }
    }

    private fun speak(message: String) {
        if (ttsReady) tts?.speak(message, TextToSpeech.QUEUE_ADD, null, "gps_${System.currentTimeMillis()}")
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onDestroy() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) { }
        tts?.stop()
        tts?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }

    private data class RoadHazard(val kind: String, val lat: Double, val lon: Double)

    private class SpeedDisplay(context: Context) : View(context) {
        private val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(57, 255, 20)
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isSubpixelText = true
        }
        private val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 145, 255)
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isSubpixelText = true
        }
        private var limitText = ""
        private var speedText = ""

        init { setBackgroundColor(Color.BLACK) }
        fun setLimit(value: String) { limitText = value; postInvalidateOnAnimation() }
        fun setSpeed(value: String) { speedText = value; postInvalidateOnAnimation() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val half = width / 2f
            drawHugeNumber(canvas, limitText, 0f, half, leftPaint)
            drawHugeNumber(canvas, speedText, half, width.toFloat(), rightPaint)
        }

        private fun drawHugeNumber(canvas: Canvas, value: String, left: Float, right: Float, paint: Paint) {
            if (value.isEmpty()) return
            val availableWidth = (right - left) * 0.94f
            val availableHeight = height * 0.90f
            paint.textSize = 1000f
            val widthAt1000 = paint.measureText(value).coerceAtLeast(1f)
            val heightAt1000 = paint.fontMetrics.descent - paint.fontMetrics.ascent
            paint.textSize = 1000f * minOf(availableWidth / widthAt1000, availableHeight / heightAt1000)
            val x = (left + right) / 2f
            val y = height / 2f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(value, x, y, paint)
        }
    }
}
