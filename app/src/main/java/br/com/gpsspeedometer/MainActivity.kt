package br.com.gpsspeedometer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
    private var lastLocation: Location? = null
    private val savedHazards = mutableListOf<RoadHazard>()
    private val worker = Executors.newSingleThreadExecutor()
    private val netExecutor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        speedView = SpeedDisplay(this)
        setContentView(speedView)
        loadSavedHazards()
        var downX = 0f
        var downY = 0f
        speedView.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) { downX = ev.x; downY = ev.y }
            false
        }
        speedView.setOnClickListener {
            if (speedView.inMenuZone(downX, downY)) showMenu()
        }
        speedView.setOnLongClickListener {
            saveRadarHere()
            true
        }

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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun parseHazards(raw: String): List<RoadHazard> {
        val out = mutableListOf<RoadHazard>()
        for (line in raw.split("\n")) {
            val p = line.trim().split(",")
            if (p.size < 2) continue
            val lat = p[0].trim().toDoubleOrNull() ?: continue
            val lon = p[1].trim().toDoubleOrNull() ?: continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val bearing = p.getOrNull(2)?.trim()?.toFloatOrNull()
            out.add(RoadHazard("saved", lat, lon, bearing))
        }
        return out
    }

    private fun serializeHazards(): String =
        savedHazards.joinToString("\n") { "${it.lat},${it.lon},${it.bearing ?: ""}" }

    private fun persistHazards() {
        getSharedPreferences("radares", Context.MODE_PRIVATE).edit()
            .putString("lista", serializeHazards()).apply()
    }

    private fun loadSavedHazards() {
        val raw = getSharedPreferences("radares", Context.MODE_PRIVATE).getString("lista", "") ?: ""
        savedHazards.clear()
        savedHazards.addAll(parseHazards(raw))
    }

    private fun isNearSaved(lat: Double, lon: Double): Boolean {
        val pt = Location("x").apply { latitude = lat; longitude = lon }
        return savedHazards.any {
            val other = Location("y").apply { latitude = it.lat; longitude = it.lon }
            other.distanceTo(pt) < 40f
        }
    }

    private fun saveRadarHere() {
        val loc = lastLocation
        if (loc == null) { speak("Sem sinal de GPS"); return }
        val bearing = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 2f) loc.bearing else null
        if (!isNearSaved(loc.latitude, loc.longitude)) {
            savedHazards.add(RoadHazard("saved", loc.latitude, loc.longitude, bearing))
            persistHazards()
        }
        speak("Radar salvo")
    }

    private fun showMenu() {
        val items = arrayOf(
            "Salvar radar aqui",
            "Exportar radares (compartilhar)",
            "Importar radares (texto copiado)",
            "Apagar todos os radares"
        )
        AlertDialog.Builder(this)
            .setTitle("Radares salvos: ${savedHazards.size}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveRadarHere()
                    1 -> exportRadars()
                    2 -> importRadars()
                    3 -> confirmClear()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun exportRadars() {
        if (savedHazards.isEmpty()) {
            Toast.makeText(this, "Nenhum radar salvo", Toast.LENGTH_LONG).show()
            return
        }
        val text = "GPSSPEED\n" + serializeHazards()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("radares", text))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Exportar radares"))
    }

    private fun importRadars() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0)
            clip.getItemAt(0).coerceToText(this).toString() else ""
        var added = 0
        for (h in parseHazards(text)) {
            if (!isNearSaved(h.lat, h.lon)) {
                savedHazards.add(h)
                added++
            }
        }
        if (added > 0) persistHazards()
        Toast.makeText(
            this,
            if (added > 0) "$added radares importados" else "Nada para importar. Copie o texto exportado primeiro.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Apagar todos os radares salvos?")
            .setPositiveButton("Apagar") { _, _ ->
                savedHazards.clear()
                persistHazards()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
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

    private val overpassUrls = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )

    private fun postStatus(text: String) {
        mainHandler.post { speedView.setStatus(text) }
    }

    private fun fetchOverpass(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        var lastError = "sem resposta"
        for (base in overpassUrls) {
            try {
                val connection = URL("$base?data=$encoded").openConnection() as HttpURLConnection
                connection.connectTimeout = 6000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "GPSSpeedometer/1.0 (Android; uso pessoal)")
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                if (code == 200) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                    return body
                }
                lastError = "HTTP $code"
                connection.disconnect()
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName
            }
        }
        throw Exception(lastError)
    }

    private fun wayDistance(item: JSONObject, from: Location): Float {
        val geom = item.optJSONArray("geometry") ?: return Float.MAX_VALUE / 2
        var best = Float.MAX_VALUE / 2
        val pt = Location("p")
        var prevLat = Double.NaN
        var prevLon = Double.NaN
        for (j in 0 until geom.length()) {
            val g = geom.optJSONObject(j) ?: continue
            val glat = g.optDouble("lat")
            val glon = g.optDouble("lon")
            if (glat.isNaN() || glon.isNaN()) continue
            pt.latitude = glat; pt.longitude = glon
            best = minOf(best, from.distanceTo(pt))
            if (!prevLat.isNaN()) {
                // ponto médio do trecho, para vias com poucos vértices
                pt.latitude = (glat + prevLat) / 2.0; pt.longitude = (glon + prevLon) / 2.0
                best = minOf(best, from.distanceTo(pt))
            }
            prevLat = glat; prevLon = glon
        }
        return best
    }

    private fun queryRoadData(location: Location) {
        postStatus("Mapa: consultando...")
        worker.execute {
            try {
                val lat = location.latitude
                val lon = location.longitude
                val query = """
                    [out:json][timeout:12];
                    (
                      way(around:100,$lat,$lon)["highway"]["maxspeed"];
                      way(around:100,$lat,$lon)["highway"]["maxspeed:forward"];
                      way(around:100,$lat,$lon)["highway"]["maxspeed:backward"];
                      node(around:650,$lat,$lon)["highway"="speed_camera"];
                      node(around:650,$lat,$lon)["enforcement"="traffic_signals"];
                      node(around:650,$lat,$lon)["enforcement"~"maxspeed|average_speed"];
                      node(around:650,$lat,$lon)["traffic_calming"];
                    );
                    out geom tags;
                """.trimIndent()
                val future = netExecutor.submit<String> { fetchOverpass(query) }
                val body = try {
                    future.get(25, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    future.cancel(true)
                    throw Exception("sem resposta em 25 s (sem internet?)")
                } catch (e: ExecutionException) {
                    throw Exception(e.cause?.message ?: "falha de rede")
                }
                val elements = JSONObject(body).optJSONArray("elements")
                    ?: throw Exception("resposta sem dados")
                var nearestLimit: Int? = null
                var nearestDistance = Float.MAX_VALUE
                var waysSeen = 0
                val foundHazards = mutableListOf<RoadHazard>()

                for (i in 0 until elements.length()) {
                    val item = elements.optJSONObject(i) ?: continue
                    val tags = item.optJSONObject("tags") ?: JSONObject()
                    val type = item.optString("type")

                    if (type == "way") {
                        val raw = tags.optString("maxspeed")
                            .ifBlank { tags.optString("maxspeed:forward") }
                            .ifBlank { tags.optString("maxspeed:backward") }
                        if (raw.isBlank()) continue
                        val parsed = parseSpeedLimit(raw) ?: continue
                        waysSeen++
                        val distance = wayDistance(item, location)
                        if (distance < nearestDistance) {
                            nearestLimit = parsed
                            nearestDistance = distance
                        }
                    } else if (type == "node") {
                        val latValue = item.optDouble("lat", Double.NaN)
                        val lonValue = item.optDouble("lon", Double.NaN)
                        if (latValue.isNaN() || lonValue.isNaN()) continue
                        val highway = tags.optString("highway")
                        val enforcement = tags.optString("enforcement")
                        val calming = tags.optString("traffic_calming")
                        when {
                            highway == "speed_camera" || enforcement == "maxspeed" || enforcement == "average_speed" ->
                                foundHazards.add(RoadHazard("camera", latValue, lonValue))
                            enforcement == "traffic_signals" ->
                                foundHazards.add(RoadHazard("signal_camera", latValue, lonValue))
                            calming.isNotBlank() && calming != "no" ->
                                foundHazards.add(RoadHazard("bump", latValue, lonValue))
                        }
                    }
                }

                val finalLimit = nearestLimit
                mainHandler.post {
                    if (finalLimit != null) {
                        speedView.setLimit(finalLimit.toString())
                        speedView.setStatus("Mapa: OK, limite $finalLimit ($waysSeen vias)")
                        if (lastLimit != finalLimit) {
                            lastLimit = finalLimit
                            speak("Limite de velocidade, $finalLimit quil\u00f4metros por hora")
                        }
                    } else {
                        speedView.setStatus("Mapa: OK, mas sem limite cadastrado aqui")
                    }
                    hazards = foundHazards
                }
            } catch (e: Exception) {
                mainHandler.post {
                    lastQueryLocation = null // tenta de novo no pr\u00f3ximo ciclo
                    speedView.setStatus("Mapa: erro ${e.message}")
                }
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
        for (hazard in hazards + savedHazards) {
            val point = Location("hazard").apply { latitude = hazard.lat; longitude = hazard.lon }
            val distance = location.distanceTo(point)
            if (distance !in 20f..450f) continue
            val delta = abs(((location.bearingTo(point) - location.bearing + 540f) % 360f) - 180f)
            if (delta > 75f) continue
            val saved = hazard.bearing
            if (saved != null) {
                val diff = abs(((location.bearing - saved + 540f) % 360f) - 180f)
                if (diff > 60f) continue
            }
            val key = "${hazard.kind}:${hazard.lat}:${hazard.lon}"
            if (now - (alerted[key] ?: 0L) < 120_000L) continue
            alerted[key] = now
            when (hazard.kind) {
                "camera", "saved" -> speak("Radar na via")
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
        netExecutor.shutdownNow()
        super.onDestroy()
    }

    private data class RoadHazard(val kind: String, val lat: Double, val lon: Double, val bearing: Float? = null)

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
        private val menuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(80, 80, 80)
            textAlign = Paint.Align.CENTER
        }
        private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(110, 110, 110)
            textAlign = Paint.Align.LEFT
        }
        private var statusText = ""
        private var limitText = ""
        private var speedText = ""

        init { setBackgroundColor(Color.BLACK) }
        fun setLimit(value: String) { limitText = value; postInvalidateOnAnimation() }
        fun setSpeed(value: String) { speedText = value; postInvalidateOnAnimation() }

        fun setStatus(value: String) { statusText = value; postInvalidateOnAnimation() }

        fun inMenuZone(x: Float, y: Float): Boolean =
            x in (width * 0.4f)..(width * 0.6f) && y > height * 0.88f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (h > w) {
                // Vertical: limite em cima, velocidade embaixo
                drawHugeNumber(canvas, limitText, 0f, w, 0f, h / 2f, leftPaint)
                drawHugeNumber(canvas, speedText, 0f, w, h / 2f, h, rightPaint)
            } else {
                // Horizontal: limite à esquerda, velocidade à direita
                drawHugeNumber(canvas, limitText, 0f, w / 2f, 0f, h, leftPaint)
                drawHugeNumber(canvas, speedText, w / 2f, w, 0f, h, rightPaint)
            }
            menuPaint.textSize = h * 0.045f
            canvas.drawText("•••", w / 2f, h * 0.97f, menuPaint)
            statusPaint.textSize = h * 0.035f
            canvas.drawText(statusText, w * 0.015f, h * 0.985f, statusPaint)
        }

        private fun drawHugeNumber(
            canvas: Canvas, value: String,
            left: Float, right: Float, top: Float, bottom: Float, paint: Paint
        ) {
            if (value.isEmpty()) return
            val availableWidth = (right - left) * 0.94f
            val availableHeight = (bottom - top) * 0.90f
            paint.textSize = 1000f
            val widthAt1000 = paint.measureText(value).coerceAtLeast(1f)
            val heightAt1000 = paint.fontMetrics.descent - paint.fontMetrics.ascent
            paint.textSize = 1000f * minOf(availableWidth / widthAt1000, availableHeight / heightAt1000)
            val x = (left + right) / 2f
            val y = (top + bottom) / 2f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(value, x, y, paint)
        }
    }
}
