package com.benasafrique.areascopemapper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.benasafrique.areascopemapper.databinding.ActivityMainBinding
import com.benasafrique.areascopemapper.databinding.DialogAreaBinding
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.IArchiveFile
import org.osmdroid.tileprovider.modules.MBTilesFileArchive
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
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
    private lateinit var accuracyBubble: TextView
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
        // --- CREATE ACCURACY BUBBLE ---
        // Initialize the class property
        // In onCreate() after binding is ready
        accuracyBubble = TextView(this).apply {
            text = "Accuracy: m"
            setPadding(25, 15, 25, 15)
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 25f
                setColor(Color.parseColor("#F44336")) // red with corners
            }
        }

// Position it top-right, below compass
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = 20
            topMargin = 150
        }

// Remove old parent if exists, then add
        (accuracyBubble.parent as? ViewGroup)?.removeView(accuracyBubble)
        binding.rootLayout.addView(accuracyBubble, params)


        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        map = binding.mapView
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(true)
// Initialize mapEventsOverlay first
        mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                // Add marker logic
                return true
            }
            override fun longPressHelper(p: GeoPoint?) = false
        })

        // Initialize compass
        compass = CompassOverlay(this, InternalCompassOrientationProvider(this), map)
        compass.enableCompass()

        // Setup map
        setupOfflineMap()

        // Adjust viewport, markers, etc.
        adjustMapViewport()
        setupMapTapListener()

        requestLocationPermission()
        // Initialize the switch
        switchMappingMode = binding.switchMappingMode
        txtMode = binding.txtMode
        updateModeText(mappingMode)

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
        //binding.btnSave.setOnClickListener { showSaveDialog() }
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
        // Load osmdroid configuration
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))

        // Use online OSM tiles
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setUseDataConnection(true) // allow downloading tiles when online

        // Optional: limit cache size (50 MB here)
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 50L * 1024L * 1024L

        // Set min/max zoom to prevent empty tiles
        map.minZoomLevel = 5.0
        map.maxZoomLevel = 19.0

        // Enable multi-touch and built-in zoom controls
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(true)

        // Clear overlays and re-add essential overlays
        map.overlays.clear()
        map.overlays.add(compass)
        map.overlays.add(mapEventsOverlay)

        // Set default zoom and center (change coordinates if needed)
        map.controller.setZoom(5.0)
        map.controller.setCenter(GeoPoint(0.0, 0.0))

        // Optional: notify user
        Snackbar.make(binding.rootLayout, "Online map with offline caching enabled", Snackbar.LENGTH_LONG).show()
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
                // Get map tile limits
                val tileSource = map.tileProvider.tileSource
                val clampedZoom = 15.0.coerceIn(tileSource.minimumZoomLevel.toDouble(), tileSource.maximumZoomLevel.toDouble())

                map.controller.setZoom(clampedZoom)
                map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))

                map.controller.setCenter(p)
            }
        }

        // Follow user as they walk
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                lastKnownLocation = loc

                // --- UPDATE ACCURACY BUBBLE --- //
                if (::accuracyBubble.isInitialized) {
                    val acc = loc.accuracy
                    accuracyBubble.text = "Accuracy: ${acc.toInt()} m"

                    val bg = accuracyBubble.background as? GradientDrawable
                    bg?.setColor(
                        when {
                            acc <= 3 -> Color.parseColor("#4CAF50") // Green
                            acc <= 8 -> Color.parseColor("#FFC107") // Amber
                            else -> Color.parseColor("#F44336")     // Red
                        }
                    )
                }


                if (mappingMode == MappingMode.WALKING) {
                    // auto-center map on user
                    map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                    // Get map tile limits
                    val tileSource = map.tileProvider.tileSource
                    val clampedZoom = 15.0.coerceIn(tileSource.minimumZoomLevel.toDouble(), tileSource.maximumZoomLevel.toDouble())

                    map.controller.setZoom(clampedZoom)
                    map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))

                }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        fusedLocation.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }
    // -------------------- --------------------
    private fun createNumberedMarkerIcon(number: Int): Bitmap {
        // Load the default osmdroid marker icon
        val base = ResourcesCompat.getDrawable(
            resources,
            org.osmdroid.library.R.drawable.marker_default,
            null
        )!!

        val width = base.intrinsicWidth
        val height = base.intrinsicHeight

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Draw base marker
        base.setBounds(0, 0, width, height)
        base.draw(canvas)

        // Draw number in a white circle (for visibility)
        val circlePaint = Paint().apply {
            color = Color.BLACK   // black circle
            isAntiAlias = true
        }

        val circleX = width / 2f
        val circleY = height * 0.33f   // upper third of marker
        val radius = width * 0.22f

        canvas.drawCircle(circleX, circleY, radius, circlePaint)

        // Draw number on top
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = width * 0.32f   // scale automatically
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
        }

        val textY = circleY - ((paint.descent() + paint.ascent()) / 2)

        canvas.drawText(number.toString(), circleX, textY, paint)

        return bmp
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

            // Filter location based on accuracy (less than 6 meters)
            if (loc.accuracy > 5.0) {
                Snackbar.make(
                    binding.rootLayout,
                    "GPS accuracy too low (${loc.accuracy.toInt()}m). For better area mapping, We only save points if accuracy is less than 5m.",
                    Snackbar.LENGTH_LONG
                ).show()
                return@addOnSuccessListener
            }

            val p = LatLng(loc.latitude, loc.longitude)

            // Prevent duplicate points by checking proximity to last point
            if (markers.isNotEmpty() && isCloseToLastPoint(p)) {
                Snackbar.make(binding.rootLayout, "Point is too close to the last one", Snackbar.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

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
    // Helper function to check if a point is too close to the last point
    private fun isCloseToLastPoint(p: LatLng): Boolean {
        val lastPoint = markers.last()
        val distance = FloatArray(1)
        Location.distanceBetween(lastPoint.latitude, lastPoint.longitude, p.latitude, p.longitude, distance)
        return distance[0] < 3.0 // 3 meters threshold to prevent overlapping
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
        markers.forEachIndexed { index, point ->
            val m = Marker(map)

            m.position = GeoPoint(point.latitude, point.longitude)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

            // Numbered marker icon
            m.icon = BitmapDrawable(resources, createNumberedMarkerIcon(index + 1))

            // Disable tooltip & click
            m.infoWindow = null
            m.setOnMarkerClickListener { _, _ -> true }

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

        // Add 10% padding
        val latMargin = (north - south) * 0.1
        val lonMargin = (east - west) * 0.1
        val bbox = BoundingBox(north + latMargin, east + lonMargin, south - latMargin, west - lonMargin)

        // Zoom to bounding box without animation
        map.zoomToBoundingBox(bbox, false)

        // Clamp zoom within tile source limits to prevent empty tiles
        val tileSource = map.tileProvider.tileSource
        val minZoom = tileSource.minimumZoomLevel.toDouble().coerceAtLeast(map.minZoomLevel)
        val maxZoom = tileSource.maximumZoomLevel.toDouble().coerceAtMost(map.maxZoomLevel)
        val zoom = map.zoomLevelDouble.coerceIn(minZoom, maxZoom)

        // Apply zoom and center
        map.controller.setZoom(zoom)
        val centerLat = (north + south) / 2
        val centerLon = (east + west) / 2
        map.controller.setCenter(GeoPoint(centerLat, centerLon))
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

        // Round off values to 1 decimal place
        val meters = String.format("%.1f", area.first)
        val hectares = String.format("%.1f", area.third)
        val acres = String.format("%.1f", area.second)
        val feet = String.format("%.1f", area.fourth)
        val yards = String.format("%.1f", area.fifth)

        // Set the rounded values to the TextViews
        dBind.txtMeters.text = "$meters m²"
        dBind.txtHectares.text = "$hectares Ha"
        dBind.txtAcres.text = "$acres Acres"
        dBind.txtFeet.text = "$feet ft²"
        dBind.txtYards.text = "$yards yd²"

        dBind.btnOk.setOnClickListener { dialog.dismiss() }
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


