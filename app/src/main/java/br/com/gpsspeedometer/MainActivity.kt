
package br.com.gpsspeedometer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

private const val TILE_DEG = 0.02

class MainActivity : Activity(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var speedView: SpeedDisplay
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastLimit: Int? = null
    private var lastSpeakAt = 0L
    private var lastMatchAt = 0L
    private var lastQueryLocation: Location? = null
    private var lastQueryAt = 0L
    private var hazards: List<RoadHazard> = emptyList()
    private val alerted = mutableMapOf<String, Long>()
    @Volatile private var lastLocation: Location? = null
    private val savedHazards = mutableListOf<RoadHazard>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val netExecutor = Executors.newCachedThreadPool()
    private val hazardWorker = Executors.newSingleThreadExecutor()
    private val hazardBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var mapHazards = false

    // Mapa local (limites de velocidade guardados no celular)
    private val tiles: MutableMap<String, List<LimitWay>> = ConcurrentHashMap()
    private val failedAt: MutableMap<String, Long> = ConcurrentHashMap()
    private val bulkQueue = ConcurrentLinkedQueue<String>()
    @Volatile private var bulkTotal = 0
    @Volatile private var bulkDone = 0
    @Volatile private var tileStatus = ""
    @Volatile private var hazardStatus = ""
    @Volatile private var running = true
    private var tileThread: Thread? = null

    private val overpassUrls = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        speedView = SpeedDisplay(this)
        setContentView(speedView)
        loadSavedHazards()
        mapHazards = getSharedPreferences("config", Context.MODE_PRIVATE).getBoolean("radares_mapa", false)

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

        val t = Thread { tileLoop() }
        t.isDaemon = true
        t.start()
        tileThread = t
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
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

    // ---------------- Radares salvos pelo usuário ----------------

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
            "Apagar todos os radares",
            "Radares do mapa: " + (if (mapHazards) "LIGADO (pode atrasar)" else "DESLIGADO"),
            "Baixar mapa ao redor (11 km)",
            "Baixar mapa ao redor (19 km)"
        )
        AlertDialog.Builder(this)
            .setTitle("Radares salvos: ${savedHazards.size}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveRadarHere()
                    1 -> exportRadars()
                    2 -> importRadars()
                    3 -> confirmClear()
                    4 -> {
                        mapHazards = !mapHazards
                        getSharedPreferences("config", Context.MODE_PRIVATE).edit()
                            .putBoolean("radares_mapa", mapHazards).apply()
                        if (!mapHazards) hazards = emptyList()
                        Toast.makeText(
                            this,
                            if (mapHazards) "Radares do mapa ligados" else "Radares do mapa desligados",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    5 -> startBulkDownload(2)
                    6 -> startBulkDownload(4)
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

    // ---------------- GPS ----------------

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        val kmh = if (location.hasSpeed()) (location.speed * 3.6f).coerceAtLeast(0f) else 0f
        speedView.setSpeed(kmh.toInt().toString())

        updateLimit(location)

        val previous = lastQueryLocation
        val movedEnough = previous == null || previous.distanceTo(location) >= 150f
        val timeEnough = System.currentTimeMillis() - lastQueryAt >= 20_000L
        if (movedEnough && timeEnough) {
            lastQueryLocation = Location(location)
            lastQueryAt = System.currentTimeMillis()
            queryHazardsAsync(location)
        }
        checkHazards(location)
    }

    private fun updateLimit(location: Location) {
        val now = System.currentTimeMillis()
        val found = lookupLimit(location)
        if (found != null) {
            lastMatchAt = now
            speedView.setLimit(found.toString())
            if (lastLimit != found) {
                lastLimit = found
                if (now - lastSpeakAt > 8000L) {
                    lastSpeakAt = now
                    speak("Limite de velocidade, $found quilômetros por hora")
                }
            }
        } else if (lastLimit != null && now - lastMatchAt > 15_000L) {
            lastLimit = null
            speedView.setLimit("")
        }
    }

    // ---------------- Mapa local: limites de velocidade ----------------

    private fun tileKey(ix: Int, iy: Int): String = "${ix}_${iy}"

    private fun tileFile(key: String): File {
        val dir = File(filesDir, "tiles")
        dir.mkdirs()
        return File(dir, "$key.t")
    }

    private fun lookupLimit(loc: Location): Int? {
        val lat = loc.latitude
        val lon = loc.longitude
        val ix0 = floor(lon / TILE_DEG).toInt()
        val iy0 = floor(lat / TILE_DEG).toInt()
        val cosLat = cos(Math.toRadians(lat))
        val margin = 0.0005
        var best: Int? = null
        var bestD = 45.0
        for (dx in -1..1) {
            for (dy in -1..1) {
                val list = tiles[tileKey(ix0 + dx, iy0 + dy)] ?: continue
                for (w in list) {
                    if (lat < w.minLat - margin || lat > w.maxLat + margin ||
                        lon < w.minLon - margin || lon > w.maxLon + margin) continue
                    val d = distanceToWay(w, lat, lon, cosLat)
                    if (d < bestD) {
                        bestD = d
                        best = w.limit
                    }
                }
            }
        }
        return best
    }

    private fun distanceToWay(w: LimitWay, lat: Double, lon: Double, cosLat: Double): Double {
        val kx = 111320.0 * cosLat
        val ky = 110540.0
        var best = Double.MAX_VALUE
        for (i in 0 until w.lats.size - 1) {
            val ax = (w.lons[i] - lon) * kx
            val ay = (w.lats[i] - lat) * ky
            val bx = (w.lons[i + 1] - lon) * kx
            val by = (w.lats[i + 1] - lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            var t = if (len2 > 0.0) -(ax * dx + ay * dy) / len2 else 0.0
            if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < best) best = d
        }
        return best
    }

    private fun nextNeededTile(): String? {
        val loc = lastLocation ?: return null
        val ix0 = floor(loc.longitude / TILE_DEG).toInt()
        val iy0 = floor(loc.latitude / TILE_DEG).toInt()
        val now = System.currentTimeMillis()
        val order = listOf(
            Pair(0, 0), Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1),
            Pair(1, 1), Pair(1, -1), Pair(-1, 1), Pair(-1, -1)
        )
        for (o in order) {
            val key = tileKey(ix0 + o.first, iy0 + o.second)
            if (tiles.containsKey(key)) continue
            val f = failedAt[key]
            if (f != null && now - f < 20_000L) continue
            return key
        }
        return null
    }

    private fun evictFar() {
        val loc = lastLocation ?: return
        val ix0 = floor(loc.longitude / TILE_DEG).toInt()
        val iy0 = floor(loc.latitude / TILE_DEG).toInt()
        for (k in tiles.keys.toList()) {
            val p = k.split("_")
            val ix = p.getOrNull(0)?.toIntOrNull() ?: continue
            val iy = p.getOrNull(1)?.toIntOrNull() ?: continue
            if (abs(ix - ix0) > 2 || abs(iy - iy0) > 2) tiles.remove(k)
        }
    }

    private fun tileLoop() {
        while (running) {
            try {
                evictFar()
                val needed = nextNeededTile()
                val key = needed ?: bulkQueue.poll()
                if (key == null) {
                    Thread.sleep(1000)
                    continue
                }
                val isBulk = needed == null
                loadOrDownload(key, !isBulk)
                if (isBulk) {
                    bulkDone++
                    if (bulkDone >= bulkTotal) {
                        bulkTotal = 0
                        bulkDone = 0
                        tileStatus = "regiao baixada"
                    }
                }
                refreshStatus()
                if (!isBulk) {
                    mainHandler.post { lastLocation?.let { updateLimit(it) } }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun loadOrDownload(key: String, keepInMemory: Boolean) {
        val file = tileFile(key)
        try {
            if (file.exists()) {
                if (keepInMemory) tiles[key] = parseTile(file.readText())
                return
            }
            tileStatus = "baixando area..."
            refreshStatus()
            val parts = key.split("_")
            val ix = parts[0].toInt()
            val iy = parts[1].toInt()
            val south = String.format(Locale.US, "%.5f", iy * TILE_DEG)
            val north = String.format(Locale.US, "%.5f", (iy + 1) * TILE_DEG)
            val west = String.format(Locale.US, "%.5f", ix * TILE_DEG)
            val east = String.format(Locale.US, "%.5f", (ix + 1) * TILE_DEG)
            val bbox = "($south,$west,$north,$east)"
            val query = """
                [out:json][timeout:60];
                (
                  way["highway"]["maxspeed"]$bbox;
                  way["highway"]["maxspeed:forward"]$bbox;
                  way["highway"]["maxspeed:backward"]$bbox;
                );
                out body geom;
            """.trimIndent()
            val t0 = System.currentTimeMillis()
            val body = fetchAny(query, 75_000L, 70_000)
            val elements = JSONObject(body).optJSONArray("elements")
                ?: throw Exception("resposta sem dados")
            val text = buildTileText(elements)
            val tmp = File(file.parentFile, "$key.tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
            if (keepInMemory) tiles[key] = parseTile(text)
            failedAt.remove(key)
            tileStatus = "area baixada em ${(System.currentTimeMillis() - t0) / 1000}s"
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            failedAt[key] = System.currentTimeMillis()
            tileStatus = "erro ao baixar: ${e.message}"
        }
    }

    private fun buildTileText(elements: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until elements.length()) {
            val item = elements.optJSONObject(i) ?: continue
            if (item.optString("type") != "way") continue
            val tags = item.optJSONObject("tags") ?: continue
            val raw = tags.optString("maxspeed")
                .ifBlank { tags.optString("maxspeed:forward") }
                .ifBlank { tags.optString("maxspeed:backward") }
            if (raw.isBlank()) continue
            val limit = parseSpeedLimit(raw) ?: continue
            val geom = item.optJSONArray("geometry") ?: continue
            if (geom.length() < 2) continue
            sb.append(limit).append('|')
            var first = true
            for (j in 0 until geom.length()) {
                val g = geom.optJSONObject(j) ?: continue
                if (!first) sb.append(';')
                first = false
                sb.append(String.format(Locale.US, "%.5f", g.optDouble("lat")))
                sb.append(',')
                sb.append(String.format(Locale.US, "%.5f", g.optDouble("lon")))
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun parseTile(text: String): List<LimitWay> {
        val out = ArrayList<LimitWay>()
        for (line in text.split("\n")) {
            if (line.isBlank()) continue
            val bar = line.indexOf('|')
            if (bar <= 0) continue
            val limit = line.substring(0, bar).toIntOrNull() ?: continue
            val pts = line.substring(bar + 1).split(";")
            val lats = DoubleArray(pts.size)
            val lons = DoubleArray(pts.size)
            var n = 0
            for (p in pts) {
                val c = p.indexOf(',')
                if (c <= 0) continue
                val la = p.substring(0, c).toDoubleOrNull() ?: continue
                val lo = p.substring(c + 1).toDoubleOrNull() ?: continue
                lats[n] = la
                lons[n] = lo
                n++
            }
            if (n < 2) continue
            val la2 = lats.copyOf(n)
            val lo2 = lons.copyOf(n)
            out.add(LimitWay(limit, la2, lo2, la2.min(), la2.max(), lo2.min(), lo2.max()))
        }
        return out
    }

    private fun startBulkDownload(radius: Int) {
        val loc = lastLocation
        if (loc == null) {
            Toast.makeText(this, "Aguarde o sinal de GPS", Toast.LENGTH_LONG).show()
            return
        }
        val ix0 = floor(loc.longitude / TILE_DEG).toInt()
        val iy0 = floor(loc.latitude / TILE_DEG).toInt()
        var added = 0
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                val key = tileKey(ix0 + dx, iy0 + dy)
                if (tileFile(key).exists() || bulkQueue.contains(key)) continue
                bulkQueue.add(key)
                added++
            }
        }
        bulkTotal += added
        Toast.makeText(
            this,
            if (added > 0) "Baixando $added areas. Mantenha o app aberto e com internet."
            else "Essa regiao ja esta baixada.",
            Toast.LENGTH_LONG
        ).show()
        refreshStatus()
    }

    private fun refreshStatus() {
        val vias = tiles.values.sumOf { it.size }
        val sb = StringBuilder("Mapa local: ${tiles.size} areas, $vias vias")
        if (bulkTotal > 0) sb.append(" | baixando regiao: $bulkDone/$bulkTotal")
        if (tileStatus.isNotEmpty()) sb.append(" | ").append(tileStatus)
        if (hazardStatus.isNotEmpty()) sb.append(" | ").append(hazardStatus)
        val text = sb.toString()
        mainHandler.post { speedView.setStatus(text) }
    }

    // ---------------- Rede (Overpass) ----------------

    private fun fetchOne(base: String, encoded: String, readMs: Int): String {
        val connection = URL("$base?data=$encoded").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = readMs
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "GPSSpeedometer/1.0 (Android; uso pessoal)")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code != 200) throw Exception("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun probeInternet(): Boolean = try {
        val c = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        val ok = c.responseCode in 200..299
        c.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    // Consulta os servidores ao mesmo tempo e usa o primeiro que responder.
    private fun fetchAny(query: String, deadlineMs: Long = 30_000L, readMs: Int = 25_000): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val errors = ConcurrentHashMap<String, String>()
        val service = ExecutorCompletionService<String>(netExecutor)
        val futures = overpassUrls.map { base ->
            service.submit(Callable<String> {
                try {
                    fetchOne(base, encoded, readMs)
                } catch (e: Exception) {
                    val host = base.substringAfter("://").substringBefore("/")
                    errors[host] = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                    throw e
                }
            })
        }
        val deadline = System.currentTimeMillis() + deadlineMs
        var remaining = futures.size
        try {
            while (remaining > 0) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                val done = service.poll(left, TimeUnit.MILLISECONDS) ?: break
                remaining--
                try { return done.get() } catch (_: Exception) { }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        val internet = if (probeInternet()) "internet OK" else "SEM internet"
        val detail = if (errors.isEmpty()) "sem resposta" else errors.entries.joinToString("; ") { "${it.key}=${it.value}" }
        throw Exception("$internet | $detail")
    }

    private fun runQuery(query: String): JSONArray {
        val body = fetchAny(query)
        return JSONObject(body).optJSONArray("elements") ?: throw Exception("resposta sem dados")
    }

    // ---------------- Radares do mapa (opcional) ----------------

    private fun queryHazardsAsync(location: Location) {
        if (!mapHazards) return
        if (!hazardBusy.compareAndSet(false, true)) return
        val lat = location.latitude
        val lon = location.longitude
        hazardWorker.execute {
            try {
                queryHazards(lat, lon)
            } finally {
                hazardBusy.set(false)
            }
        }
    }

    private fun queryHazards(lat: Double, lon: Double) {
        val t1 = System.currentTimeMillis()
        try {
            val dLat = 650.0 / 111320.0
            val dLon = dLat / Math.cos(Math.toRadians(lat)).coerceAtLeast(0.2)
            val bbox = "(${lat - dLat},${lon - dLon},${lat + dLat},${lon + dLon})"
            val hazardQuery = """
                [out:json][timeout:20];
                (
                  node["highway"="speed_camera"]$bbox;
                  node["enforcement"="maxspeed"]$bbox;
                  node["enforcement"="average_speed"]$bbox;
                  node["enforcement"="traffic_signals"]$bbox;
                  node["traffic_calming"]$bbox;
                );
                out body;
            """.trimIndent()
            val elements = runQuery(hazardQuery)
            val foundHazards = mutableListOf<RoadHazard>()
            for (i in 0 until elements.length()) {
                val item = elements.optJSONObject(i) ?: continue
                if (item.optString("type") != "node") continue
                val tags = item.optJSONObject("tags") ?: continue
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
            hazardStatus = "radares/lombadas no mapa: ${foundHazards.size} (${(System.currentTimeMillis() - t1) / 1000}s)"
            mainHandler.post { hazards = foundHazards }
        } catch (e: Exception) {
            hazardStatus = "radares: erro apos ${(System.currentTimeMillis() - t1) / 1000}s: ${e.message}"
        }
        refreshStatus()
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
        running = false
        tileThread?.interrupt()
        try { locationManager.removeUpdates(this) } catch (_: Exception) { }
        tts?.stop()
        tts?.shutdown()
        netExecutor.shutdownNow()
        hazardWorker.shutdownNow()
        super.onDestroy()
    }

    private data class RoadHazard(val kind: String, val lat: Double, val lon: Double, val bearing: Float? = null)

    private class LimitWay(
        val limit: Int,
        val lats: DoubleArray,
        val lons: DoubleArray,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )

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
        private val statusBgPaint = Paint().apply { color = Color.argb(200, 0, 0, 0) }
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
            statusPaint.textSize = minOf(h, w) * 0.03f
            val lines = mutableListOf<String>()
            var rest = statusText
            while (rest.isNotEmpty() && lines.size < 9) {
                val n = statusPaint.breakText(rest, true, w * 0.97f, null).coerceAtLeast(1)
                lines.add(rest.substring(0, n))
                rest = rest.substring(n)
            }
            val lineH = minOf(h, w) * 0.036f
            val firstY = h * 0.985f - (lines.size - 1) * lineH
            if (lines.isNotEmpty()) {
                canvas.drawRect(0f, firstY - lineH, w, h, statusBgPaint)
            }
            for (i in lines.indices) {
                canvas.drawText(lines[i], w * 0.015f, firstY + i * lineH, statusPaint)
            }
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
