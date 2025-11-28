package com.benasafrique.areascopemapper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.benasafrique.areascopemapper.databinding.ActivityMainBinding
import com.benasafrique.areascopemapper.databinding.DialogAreaBinding
import com.benasafrique.areascopemapper.databinding.DialogSavePolygonBinding
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.gson.Gson
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.IArchiveFile
import org.osmdroid.tileprovider.modules.MBTilesFileArchive
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.tan

data class Quintuple<A, B, C, D, E>(
    val first: A, val second: B, val third: C, val fourth: D, val fifth: E
)

data class SavedPolygon(val name: String, val points: List<LatLng>)
data class LatLng(val latitude: Double, val longitude: Double)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var map: MapView
    private lateinit var compass: CompassOverlay

    private val markers = mutableListOf<LatLng>()
    private var polygonOverlay: Polygon? = null
    private var lastArea: Quintuple<Double, Double, Double, Double, Double>? = null

    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var lastKnownLocation: Location? = null
    private var locationCallback: LocationCallback? = null
    private var mappingMode = MappingMode.MANUAL

    enum class MappingMode { WALKING, MANUAL }

    private lateinit var mapEventsOverlay: MapEventsOverlay
    private lateinit var switchMappingMode: SwitchMaterial
    private lateinit var txtMode: TextView

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Edge-to-edge setup
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Edge-to-edge setup: prevent the decor from fitting system windows
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Apply system bars padding to the root layout (automatically adjusts for system bars)
        binding.root.applySystemBarsPadding()
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        map = binding.mapView
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(true)

        setupOfflineMap()
        setupMapTapListener()

        compass = CompassOverlay(this, InternalCompassOrientationProvider(this), map)
        compass.enableCompass()
        map.overlays.add(compass)

        requestLocationPermission()
        // Initialize the switch
        switchMappingMode = binding.switchMappingMode
        txtMode = binding.txtMode
        updateModeText(mappingMode) // initialize text

        switchMappingMode.setOnCheckedChangeListener { _, isChecked ->
            mappingMode = if (isChecked) MappingMode.WALKING else MappingMode.MANUAL
            updateModeText(mappingMode)
            Snackbar.make(binding.rootLayout,
                if (isChecked) "Walking mode ON" else "Tap mode ON",
                Snackbar.LENGTH_SHORT
            ).show()
        }
        binding.btnCapturePoint.setOnClickListener { capturePoint() }
        binding.btnUndo.setOnClickListener { undoPoint() }
        binding.btnFinish.setOnClickListener { finishPolygon() }
        //binding.switchMappingMode.setOnClickListener { toggleMappingMode() }
        binding.btnSave.setOnClickListener { showSaveDialog() }
        binding.btnExportCsv.setOnClickListener { exportLastAreaCsv() }
        binding.btnExportPdf.setOnClickListener { exportLastAreaGeoJson() }
    }
    private fun updateModeText(mode: MappingMode) {
        txtMode.text = when (mode) {
            MappingMode.WALKING -> "Mode: Walking"
            MappingMode.MANUAL -> "Mode: Tap"
        }
    }

    // -------------------- OFFLINE MAP --------------------
    private fun setupOfflineMap() {
        val mbTilesFile = File(filesDir, "mytiles.mbtiles")

        if (mbTilesFile.exists()) {
            initMBTilesMap(mbTilesFile)
        } else {
            // fallback to online map if file missing
            map.setTileSource(TileSourceFactory.MAPNIK)
            Snackbar.make(binding.rootLayout, "Offline MBTiles not found, using online map", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun initMBTilesMap(mbTilesFile: File) {
        val tileSource = TileSourceFactory.MAPNIK
        val archive: IArchiveFile = MBTilesFileArchive.getDatabaseFileArchive(mbTilesFile)
        val receiver = SimpleRegisterReceiver(this)

        val archiveProvider = MapTileFileArchiveProvider(
            receiver,          // IRegisterReceiver
            tileSource,        // ITileSource
            arrayOf(archive)   // Array<IArchiveFile>
        )

        val tileProvider = MapTileProviderArray(tileSource, null, arrayOf(archiveProvider))
        val tileOverlay = TilesOverlay(tileProvider, this)

        map.overlays.add(tileOverlay)
    }

    private fun toggleMappingMode() {
        mappingMode = if (mappingMode == MappingMode.MANUAL) MappingMode.WALKING else MappingMode.MANUAL
        val msg = if (mappingMode == MappingMode.WALKING) "Walking mode ON" else "Tap mode ON"
        Snackbar.make(binding.rootLayout, msg, Snackbar.LENGTH_SHORT).show()
    }
    private fun downloadMBTiles(url: String, destFile: File, onComplete: (Boolean) -> Unit) {
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    onComplete(false)
                    return@Thread
                }
                destFile.outputStream().use { fileOut ->
                    connection.inputStream.use { input ->
                        input.copyTo(fileOut)
                    }
                }
                onComplete(true)
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete(false)
            }
        }.start()
    }

    // -------------------- LOCATION --------------------
    private fun requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        } else enableMyLocation()
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        fusedLocation.lastLocation.addOnSuccessListener {
            lastKnownLocation = it
            it?.let { loc ->
                val p = GeoPoint(loc.latitude, loc.longitude)
                map.controller.setZoom(18.0)
                map.controller.setCenter(p)
            }
        }

        // Follow user as they walk
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                lastKnownLocation = loc

                if (mappingMode == MappingMode.WALKING) {
                    // auto-center map on user
                    map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                    map.controller.setZoom(19.0) // auto zoom to walking
                }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        fusedLocation.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    // -------------------- CAPTURE POINT --------------------
    private fun capturePoint() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission()
            return
        }

        fusedLocation.lastLocation.addOnSuccessListener { loc ->
            if (loc == null) {
                Snackbar.make(binding.rootLayout, "No GPS Fix", Snackbar.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            val p = LatLng(loc.latitude, loc.longitude)
            markers.add(p)

            // Update polygon overlay dynamically
            if (markers.size >= 3) {
                val geoPoints = markers.map { GeoPoint(it.latitude, it.longitude) }.toMutableList()

                // Close the polygon visually by adding first point at the end
                geoPoints.add(GeoPoint(markers.first().latitude, markers.first().longitude))

                polygonOverlay = Polygon().apply {
                    points = geoPoints
                    strokeColor = Color.BLUE
                    fillColor = 0x330099FF
                }
            }

            drawEverything()
            Snackbar.make(binding.rootLayout, "Point Captured", Snackbar.LENGTH_SHORT).show()
        }
    }
    private fun undoPoint() {
        if (markers.isNotEmpty()) {
            markers.removeAt(markers.size - 1)

            // If polygon exists, recompute area or remove if less than 3 points
            if (markers.size < 3) {
                polygonOverlay = null
                lastArea = null
            } else if (polygonOverlay != null) {
                polygonOverlay?.points = markers.map { GeoPoint(it.latitude, it.longitude) }
                lastArea = computePolygonArea(markers)
            }

            drawEverything()
        }
    }

    // -------------------- DRAW MAP -------------------
    private fun setupMapTapListener() {
        mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (mappingMode == MappingMode.MANUAL && p != null) {
                    val point = LatLng(p.latitude, p.longitude)
                    markers.add(point)
                    drawEverything() // auto-join polyline immediately
                    Snackbar.make(binding.rootLayout, "Point Captured", Snackbar.LENGTH_SHORT).show()
                }
                return true
            }

            override fun longPressHelper(p: GeoPoint?) = false
        })
        map.overlays.add(mapEventsOverlay)
    }

    private fun drawEverything() {
        map.overlays.clear()
        map.overlays.add(compass)
        map.overlays.add(mapEventsOverlay)

        // Draw markers
        markers.forEach {
            val m = Marker(map)
            m.position = GeoPoint(it.latitude, it.longitude)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            map.overlays.add(m)
        }

        // Auto-join polyline
        if (markers.size >= 2) {
            val polyline = Polyline().apply {
                setPoints(markers.map { GeoPoint(it.latitude, it.longitude) })
                color = Color.BLUE
                width = 5f
            }
            map.overlays.add(polyline)
        }

        // Auto-close polygon if at least 3 points
        if (markers.size >= 3) {
            polygonOverlay = Polygon().apply {
                points = markers.map { GeoPoint(it.latitude, it.longitude) }
                strokeColor = Color.BLUE
                fillColor = 0x330099FF
            }
            map.overlays.add(polygonOverlay)
            lastArea = computePolygonArea(markers)
        }

        adjustMapViewport()
        map.invalidate()
    }
    private fun adjustMapViewport() {
        if (markers.isEmpty()) return
        val lats = markers.map { it.latitude }
        val lons = markers.map { it.longitude }
        val north = lats.maxOrNull()!!
        val south = lats.minOrNull()!!
        val east = lons.maxOrNull()!!
        val west = lons.minOrNull()!!
        map.zoomToBoundingBox(BoundingBox(north, east, south, west), true)
    }

    // -------------------- AREA CALCULATION --------------------
    private fun latLonToMeters(lat: Double, lon: Double): Pair<Double, Double> {
        val R = 6378137.0
        val x = Math.toRadians(lon) * R
        val y = ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2)) * R
        return Pair(x, y)
    }

    private fun computePolygonArea(points: List<LatLng>): Quintuple<Double, Double, Double, Double, Double> {
        if (points.size < 3) return Quintuple(0.0, 0.0, 0.0, 0.0, 0.0)
        val xy = points.map { latLonToMeters(it.latitude, it.longitude) }
        var area = 0.0
        for (i in 1 until xy.size) {
            val (x0, y0) = xy[i - 1]
            val (x1, y1) = xy[i]
            area += (x0 * y1 - y0 * x1) / 2
        }
        val sqMeters = abs(area)
        val acres = sqMeters * 0.00024711
        val hectares = sqMeters / 10000
        val sqFeet = sqMeters * 10.7639
        val sqYards = sqMeters * 1.19599
        return Quintuple(sqMeters, acres, hectares, sqFeet, sqYards)
    }

    private fun finishPolygon() {
        if (markers.size < 3) {
            Snackbar.make(binding.rootLayout, "Add at least 3 points", Snackbar.LENGTH_SHORT).show()
            return
        }
        val geoPoints = markers.map { GeoPoint(it.latitude, it.longitude) }
        polygonOverlay = Polygon().apply {
            points = geoPoints
            strokeColor = Color.BLUE
            fillColor = 0x330099FF
        }
        lastArea = computePolygonArea(markers)
        drawEverything()
        showAreaDialog(lastArea!!)
    }

    private fun showAreaDialog(area: Quintuple<Double, Double, Double, Double, Double>) {
        val dialog = Dialog(this)
        val dBind = DialogAreaBinding.inflate(layoutInflater)
        dialog.setContentView(dBind.root)
        dBind.txtMeters.text = "${area.first} m²"
        dBind.txtHectares.text = "${area.third} Ha"
        dBind.txtAcres.text = "${area.second} Acres"
        dBind.txtFeet.text = "${area.fourth} ft²"
        dBind.txtYards.text = "${area.fifth} yd²"
        dBind.btnOk.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // -------------------- SAVE / LOAD --------------------
    private fun showSaveDialog() {
        val dialog = Dialog(this)
        val b = DialogSavePolygonBinding.inflate(layoutInflater)
        dialog.setContentView(b.root)
        b.btnSavePolygon.setOnClickListener {
            val name = b.inputName.text.toString().trim()
            if (name.isNotEmpty()) {
                File(filesDir, "$name.json").writeText(Gson().toJson(SavedPolygon(name, markers)))
                Snackbar.make(binding.rootLayout, "Polygon Saved", Snackbar.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
    // -------------------- EXPORT --------------------
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportLastAreaCsv() {
        if (lastArea == null || markers.isEmpty()) {
            Snackbar.make(binding.rootLayout, "No polygon data to export", Snackbar.LENGTH_SHORT).show()
            return
        }

        val filename = "polygon_export_${System.currentTimeMillis()}.csv"
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                out.writer().use { writer ->
                    writer.appendLine("Point Type,Lat,Lon,Altitude(m),Accuracy(m)")

                    // Export all points (individual points)
                    markers.forEach { point ->
                        val alt = lastKnownLocation?.altitude ?: 0.0
                        val acc = lastKnownLocation?.accuracy ?: 0f
                        writer.appendLine("Point,${point.latitude},${point.longitude},$alt,$acc")
                    }

                    // Export the Polygon as WKT
                    val polygonWkt = convertPointsToWkt(markers)
                    writer.appendLine("Polygon,WKT,$polygonWkt")

                    // Export area info
                    writer.appendLine()
                    writer.appendLine("Area (m²),${lastArea!!.first}")
                    writer.appendLine("Acres,${lastArea!!.second}")
                    writer.appendLine("Hectares,${lastArea!!.third}")
                    writer.appendLine("Sq Feet,${lastArea!!.fourth}")
                    writer.appendLine("Sq Yards,${lastArea!!.fifth}")
                }
            }
            Snackbar.make(binding.rootLayout, "CSV exported to Downloads", Snackbar.LENGTH_LONG).show()

            // Now share the CSV file
            shareFile(uri, "text/csv", "Export Polygon CSV")
        } else {
            Snackbar.make(binding.rootLayout, "Failed to create CSV file", Snackbar.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportLastAreaGeoJson() {
        if (markers.isEmpty()) {
            Snackbar.make(binding.rootLayout, "No points to export", Snackbar.LENGTH_SHORT).show()
            return
        }

        val filename = "polygon_${System.currentTimeMillis()}.geojson"
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/geo+json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                out.writer().use { writer ->
                    // GeoJSON Structure
                    writer.appendLine("{")
                    writer.appendLine("""  "type": "FeatureCollection",""")
                    writer.appendLine("""  "features": [""")

                    // Export the points as "Point" features
                    markers.forEach { point ->
                        writer.appendLine("""    {""")
                        writer.appendLine("""      "type": "Feature",""")
                        writer.appendLine("""      "geometry": {""")
                        writer.appendLine("""        "type": "Point",""")
                        writer.appendLine("""        "coordinates": [${point.longitude}, ${point.latitude}]""")
                        writer.appendLine("""      }""")
                        writer.appendLine("""    }, """)
                    }

                    // Export the polygon as a "Polygon" feature
                    writer.appendLine("""    {""")
                    writer.appendLine("""      "type": "Feature",""")
                    writer.appendLine("""      "geometry": {""")
                    writer.appendLine("""        "type": "Polygon",""")
                    writer.appendLine("""        "coordinates": [""")

                    // Close the polygon by adding the first point again at the end
                    val polygonPoints = if (markers.size >= 3) {
                        markers + markers.first() // Closing the polygon
                    } else markers
                    val coords = polygonPoints.joinToString(separator = ",") {
                        "[${it.longitude},${it.latitude}]"
                    }
                    writer.appendLine("""          [$coords]""")
                    writer.appendLine("""        ]""")
                    writer.appendLine("""      },""")

                    // Include the polygon as WKT in a custom property
                    val polygonWkt = convertPointsToWkt(markers)
                    writer.appendLine("""      "properties": {""")
                    writer.appendLine("""        "polygonWkt": "$polygonWkt"""")
                    writer.appendLine("""      }""")

                    writer.appendLine("""    }""")
                    writer.appendLine("  ]")
                    writer.appendLine("}")
                }
            }
            Snackbar.make(binding.rootLayout, "GeoJSON exported to Downloads", Snackbar.LENGTH_LONG).show()

            // Now share the GeoJSON file
            shareFile(uri, "application/geo+json", "Export Polygon GeoJSON")
        } else {
            Snackbar.make(binding.rootLayout, "Failed to create GeoJSON file", Snackbar.LENGTH_SHORT).show()
        }
    }

    // Helper function to share the file via email or other apps
    private fun shareFile(uri: Uri, mimeType: String, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType // Correctly set the MIME type
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }

        val chooserIntent = Intent.createChooser(intent, "Share File")
        startActivity(chooserIntent)
    }

    // Function to convert the list of LatLng points to WKT (Well-Known Text) format
    private fun convertPointsToWkt(markers: List<LatLng>): String {
        // Ensure there are at least 3 points to form a valid polygon
        if (markers.size < 3) {
            return ""
        }

        // Create a list of coordinates formatted as "longitude latitude"
        val coordinates = markers.joinToString(separator = ", ") {
            "${it.longitude} ${it.latitude}"
        }

        // Close the polygon by adding the first point at the end
        val closedCoordinates = coordinates + ", ${markers.first().longitude} ${markers.first().latitude}"

        // Return the final WKT string
        return "POLYGON (($closedCoordinates))"
    }

    // -------------------- PERMISSIONS --------------------
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enableMyLocation()
        } else {
            Snackbar.make(binding.rootLayout, "Permission denied", Snackbar.LENGTH_SHORT).show()
        }
    }

    // -------------------- LIFECYCLE --------------------
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
    override fun onDestroy() {
        super.onDestroy()
        map.onDetach()
        locationCallback?.let { fusedLocation.removeLocationUpdates(it) }
    }
}


