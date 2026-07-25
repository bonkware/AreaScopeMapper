package com.benasafrique.areascopemapper

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.benasafrique.areascopemapper.databinding.ActivityMainBinding
import com.benasafrique.areascopemapper.databinding.DialogAreaBinding
import com.benasafrique.areascopemapper.databinding.DialogLoadPolygonBinding
import com.benasafrique.areascopemapper.databinding.DialogSavePolygonBinding
import com.benasafrique.areascopemapper.databinding.DialogSettingsBinding
import com.benasafrique.areascopemapper.databinding.ItemSavedPolygonBinding
import com.benasafrique.areascopemapper.databinding.DialogLayersBinding
import com.benasafrique.areascopemapper.databinding.DialogPointsBinding
import com.benasafrique.areascopemapper.databinding.DialogExportBinding
import com.benasafrique.areascopemapper.databinding.ItemLayerBinding
import com.benasafrique.areascopemapper.databinding.ItemPointBinding
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.opencsv.CSVReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.bonuspack.kml.KmlDocument
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.views.overlay.compass.IOrientationProvider
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.sin

data class Quintuple<A, B, C, D, E>(
    val first: A, val second: B, val third: C, val fourth: D, val fifth: E
)

data class LatLng(val latitude: Double, val longitude: Double, val name: String? = null)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var map: MapView
    private lateinit var compass: CompassOverlay
    private lateinit var locationOverlay: MyLocationNewOverlay
    private var currentDialogCompass: ImageView? = null

    private val markers = mutableListOf<LatLng>()
    private val importedPoints = mutableListOf<LatLng>()
    private var polygonOverlay: Polygon? = null
    private var lastArea: Quintuple<Double, Double, Double, Double, Double>? = null

    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var lastKnownLocation: Location? = null
    private var locationCallback: LocationCallback? = null
    private var mappingMode = MappingMode.MANUAL

    enum class MappingMode { WALKING, MANUAL }

    private lateinit var mapEventsOverlay: MapEventsOverlay
    private var destinationMarker: Marker? = null

    private val importedLayers = mutableMapOf<String, Overlay>()
    private val importLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleImportFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge setup
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Handle system bar insets using spacers
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topInsetSpacer.updateLayoutParams { height = systemBars.top }
            binding.bottomInsetSpacer.updateLayoutParams { height = systemBars.bottom }
            insets
        }

        // Initialize Accuracy Bubble with modern rounded corners
        binding.cardAccuracy.setCardBackgroundColor(Color.RED)


        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        map = binding.mapView
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(true)

        // Initialize mapEventsOverlay
        initMapEventsOverlay()

        // Initialize compass
        compass = NavCompassOverlay(this, InternalCompassOrientationProvider(this), map)
        compass.enableCompass()
        compass.setCompassCenter(36f, 150f) // Move it down a bit to avoid overlap with Title

        // Initialize Location Overlay
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        locationOverlay.enableMyLocation()
        locationOverlay.isDrawAccuracyEnabled = true

        // Setup map
        setupOfflineMap()

        loadImportedPoints()

        // Adjust viewport, markers, etc.
        adjustMapViewport()

        requestLocationPermission()
        
        updateModeText(mappingMode)

        binding.btnCapturePoint.setOnClickListener { capturePoint() }
        binding.btnUndo.setOnClickListener { undoPoint() }
        binding.btnFinish.setOnClickListener { finishPolygon() }
        binding.btnSave.setOnClickListener { showSaveDialog() }
        binding.btnSaved.setOnClickListener { showSavedPolygonsDialog() }
        binding.btnExport.setOnClickListener { showExportDialog() }
        binding.btnImport.setOnClickListener { importLauncher.launch("*/*") }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnPoints.setOnClickListener { showPointsDialog() }
        binding.btnStopNav.setOnClickListener { stopNavigation() }

        // Update btnFinish text initially if needed, though it's set in XML
        // binding.btnFinish.setText(R.string.btn_area)
    }

    private fun showSettingsDialog() {
        val dBind = DialogSettingsBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dBind.root)
            .setOnDismissListener { currentDialogCompass = null }
            .create()

        if (targetPoint != null) {
            dBind.layoutDialogCompass.visibility = android.view.View.VISIBLE
            currentDialogCompass = dBind.imgDialogCompass
            updateGuidanceUI() // Force initial rotation
        }

        // Sync initial state
        if (mappingMode == MappingMode.WALKING) {
            dBind.toggleMappingMode.check(R.id.btnModeWalking)
        } else {
            dBind.toggleMappingMode.check(R.id.btnModeManual)
        }

        dBind.toggleMappingMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                mappingMode = if (checkedId == R.id.btnModeWalking) MappingMode.WALKING else MappingMode.MANUAL
                updateModeText(mappingMode)
                showSnackbar(
                    if (mappingMode == MappingMode.WALKING) getString(R.string.walking_mode_on) else getString(R.string.tap_mode_on)
                )
            }
        }

        dBind.btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showExportDialog() {
        val dBind = DialogExportBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dBind.root)
            .create()

        dBind.btnExportCsv.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportLastAreaCsv()
            } else {
                showSnackbar("Export requires Android 10+")
            }
            dialog.dismiss()
        }
        dBind.btnExportGeoJson.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportLastAreaGeoJson()
            } else {
                showSnackbar("Export requires Android 10+")
            }
            dialog.dismiss()
        }
        dBind.btnExportGpx.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportLastAreaGpx()
            } else {
                showSnackbar("Export requires Android 10+")
            }
            dialog.dismiss()
        }
        dBind.btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
    private fun updateModeText(mode: MappingMode) {
        binding.txtMode.text = when (mode) {
            MappingMode.WALKING -> getString(R.string.mode_walking)
            MappingMode.MANUAL -> getString(R.string.mode_tap)
        }
    }

    private fun showSnackbar(message: String, length: Int = Snackbar.LENGTH_SHORT) {
        Snackbar.make(binding.root, message, length)
            .setAnchorView(binding.cardBottomControls)
            .show()
    }

    // -------------------- OFFLINE MAP --------------------
    private fun setupOfflineMap() {
        // Load osmdroid configuration
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))

        // Use online OSM tiles - tiles viewed once will be cached automatically
        map.setTileSource(TileSourceFactory.MAPNIK)
        
        // Increase cache size to 500 MB for better offline experience
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 500L * 1024L * 1024L
        Configuration.getInstance().tileFileSystemCacheTrimBytes = 450L * 1024L * 1024L

        // Set min/max zoom to prevent empty tiles
        map.minZoomLevel = 2.0
        map.maxZoomLevel = 20.0

        // Enable multi-touch and built-in zoom controls
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(false) // Hide default +/- buttons for a cleaner UI

        // Clear overlays and re-add essential overlays
        map.overlays.clear()
        map.overlays.add(locationOverlay)
        map.overlays.add(compass)
        map.overlays.add(mapEventsOverlay)
        destinationMarker?.let { map.overlays.add(it) }
        guidanceOverlay?.let { map.overlays.add(it) }
        polygonOverlay?.let { map.overlays.add(it) }
        importedLayers.values.forEach { map.overlays.add(it) }

        // Set default zoom and center (change coordinates if needed)
        map.controller.setZoom(5.0)
        map.controller.setCenter(GeoPoint(0.0, 0.0))

        // Optional: notify user
        showSnackbar(getString(R.string.map_ready_caching), Snackbar.LENGTH_LONG)
    }
    // -------------------- LOCATION --------------------
    private fun requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        } else enableMyLocation()
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        val locationManager = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        if (!locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.gps_disabled_title)
                .setMessage(R.string.gps_disabled_message)
                .setPositiveButton(R.string.enable) { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        fusedLocation.lastLocation.addOnSuccessListener {
            lastKnownLocation = it
            // Automatically adjust viewport once location is acquired
            adjustMapViewport()
        }

        // Follow user as they walk
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val isFirstFix = lastKnownLocation == null
                lastKnownLocation = loc

                // --- UPDATE ACCURACY BUBBLE --- //
                val acc = loc.accuracy
                binding.accuracyBubble.text = getString(R.string.accuracy_format, acc.toInt())

                val color = when {
                    acc <= 3 -> Color.parseColor("#4CAF50") // Green
                    acc <= 8 -> Color.parseColor("#FFC107") // Amber
                    else -> Color.parseColor("#F44336") // Red
                }
                binding.cardAccuracy.setCardBackgroundColor(color)

                if (isFirstFix) {
                    adjustMapViewport()
                }

                if (mappingMode == MappingMode.WALKING && targetPoint == null) {
                    // auto-center map on user
                    val tileSource = map.tileProvider.tileSource
                    val clampedZoom = 15.0.coerceIn(tileSource.minimumZoomLevel.toDouble(), tileSource.maximumZoomLevel.toDouble())

                    map.controller.setZoom(clampedZoom)
                    map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                }
                if (targetPoint != null) {
                    updateGuidanceUI()
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
                showSnackbar(getString(R.string.no_gps_fix))
                return@addOnSuccessListener
            }

            // Filter location based on accuracy (less than 6 meters)
            if (loc.accuracy > 5.0) {
                showSnackbar(
                    getString(R.string.low_accuracy_warning, loc.accuracy.toInt()),
                    Snackbar.LENGTH_LONG
                )
                return@addOnSuccessListener
            }

            val p = LatLng(loc.latitude, loc.longitude)

            // Prevent duplicate points by checking proximity to last point
            if (markers.isNotEmpty() && isCloseToLastPoint(p)) {
                showSnackbar(getString(R.string.point_too_close))
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
                    outlinePaint.color = Color.BLUE
                    fillPaint.color = 0x330099FF
                }
            }

            drawEverything()
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
    private fun initMapEventsOverlay() {
        mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (mappingMode == MappingMode.MANUAL && p != null) {
                    val point = LatLng(p.latitude, p.longitude)
                    markers.add(point)
                    drawEverything()
                }
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) {
                    startGuidingTo(p, "Point")
                }
                return true
            }
        })
    }



    private fun stopNavigation() {
        targetPoint = null
        guidanceOverlay?.let { map.overlays.remove(it) }
        destinationMarker?.let { map.overlays.remove(it) }
        guidanceOverlay = null
        destinationMarker = null
        binding.cardInstruction.visibility = android.view.View.GONE
        
        // Restore following if in walking mode
        if (mappingMode == MappingMode.WALKING) {
            locationOverlay.enableFollowLocation()
        }
        
        map.invalidate()
        showSnackbar("Navigation stopped")
    }

    private fun handleImportFile(uri: Uri) {
        val mimeType = contentResolver.getType(uri)?.lowercase()
        val fileName = getFileName(uri)
        val extension = fileName?.substringAfterLast(".", "")?.lowercase()

        val type = when {
            mimeType?.contains("csv") == true || extension == "csv" || mimeType == "text/comma-separated-values" -> "csv"
            mimeType?.contains("gpx") == true || extension == "gpx" -> "gpx"
            mimeType?.contains("json") == true || extension == "geojson" || extension == "json" -> "geojson"
            else -> null
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                when (type) {
                    "csv" -> importCsv(uri)
                    "gpx" -> importGpx(uri)
                    "geojson" -> importGeoJson(uri)
                    else -> {
                        if (tryImportAsCsvFallback(uri)) return@launch
                        
                        withContext(Dispatchers.Main) { 
                            showSnackbar("${getString(R.string.unsupported_format)} (Mime: $mimeType, Ext: $extension)") 
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showSnackbar("${getString(R.string.import_failed)}: ${e.message}") }
            }
        }
    }

    private suspend fun tryImportAsCsvFallback(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = inputStream.bufferedReader()
                val firstLine = reader.readLine()?.lowercase() ?: ""
                if (firstLine.contains("lat") && (firstLine.contains("lon") || firstLine.contains("lng"))) {
                    importCsv(uri)
                    true
                } else false
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    result = it.getString(it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME))
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private suspend fun importCsv(uri: Uri) {
        contentResolver.openInputStream(uri)?.use { inputStream ->
            val reader = CSVReader(InputStreamReader(inputStream))
            val lines = reader.readAll()
            val newPoints = mutableListOf<LatLng>()
            
            if (lines.isNotEmpty()) {
                val header = lines[0].map { it.lowercase() }
                val latIdx = header.indexOfFirst { it.contains("lat") }
                val lonIdx = header.indexOfFirst { it.contains("lon") || it.contains("lng") }
                val nameIdx = header.indexOfFirst { it.contains("name") || it.contains("title") || it.contains("id") }

                if (latIdx != -1 && lonIdx != -1) {
                    for (i in 1 until lines.size) {
                        val row = lines[i]
                        if (row.size > maxOf(latIdx, lonIdx)) {
                            val lat = row[latIdx].toDoubleOrNull()
                            val lon = row[lonIdx].toDoubleOrNull()
                            val name = if (nameIdx != -1 && row.size > nameIdx) row[nameIdx] else null
                            if (lat != null && lon != null) {
                                newPoints.add(LatLng(lat, lon, name))
                            }
                        }
                    }
                }
            }
            
            withContext(Dispatchers.Main) {
                if (newPoints.isNotEmpty()) {
                    importedPoints.addAll(newPoints)
                    saveImportedPoints()
                    drawEverything()
                    showSnackbar(getString(R.string.import_success))
                } else {
                    showSnackbar("No valid coordinates found in CSV")
                }
            }
        }
    }

    private suspend fun importGpx(uri: Uri) {
        val newPoints = mutableListOf<LatLng>()
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(inputStream, null)
                
                var eventType = parser.eventType
                var currentName: String? = null
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val tagName = parser.name
                        if (tagName == "trkpt" || tagName == "wpt" || tagName == "rtept") {
                            val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            if (lat != null && lon != null) {
                                // Look for name in next tags
                                var nextType = parser.next()
                                while (nextType != XmlPullParser.END_TAG || parser.name != tagName) {
                                    if (nextType == XmlPullParser.START_TAG && parser.name == "name") {
                                        currentName = parser.nextText()
                                    }
                                    nextType = parser.next()
                                }
                                newPoints.add(LatLng(lat, lon, currentName))
                                currentName = null
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
            
            withContext(Dispatchers.Main) {
                if (newPoints.isNotEmpty()) {
                    importedPoints.addAll(newPoints)
                    saveImportedPoints()
                    drawEverything()
                    showSnackbar(getString(R.string.import_success))
                } else {
                    showSnackbar("No valid points found in GPX")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                showSnackbar("GPX Import failed: ${e.message}")
            }
        }
    }

    private fun saveImportedPoints() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val json = Gson().toJson(importedPoints)
        prefs.edit().putString("imported_points", json).apply()
    }

    private fun loadImportedPoints() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val json = prefs.getString("imported_points", null)
        if (json != null) {
            val type = object : TypeToken<MutableList<LatLng>>() {}.type
            val points: MutableList<LatLng> = Gson().fromJson(json, type)
            importedPoints.clear()
            importedPoints.addAll(points)
            drawEverything()
        }
    }

    private suspend fun importGeoJson(uri: Uri) {
        val kmlDocument = KmlDocument()
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val jsonString = inputStream.bufferedReader().use { it.readText() }
                if (kmlDocument.parseGeoJSON(jsonString)) {
                    val fileName = uri.path?.substringAfterLast("/") ?: "Imported Layer ${importedLayers.size + 1}"
                    withContext(Dispatchers.Main) {
                        val overlay = kmlDocument.mKmlRoot.buildOverlay(map, null, null, kmlDocument)
                        importedLayers[fileName] = overlay
                        map.overlays.add(overlay)
                        map.invalidate()
                        showSnackbar(getString(R.string.import_success))
                    }
                } else {
                    withContext(Dispatchers.Main) { showSnackbar("Failed to parse GeoJSON") }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) { showSnackbar("GeoJSON Import failed: ${e.message}") }
        }
    }

    private fun showLayersDialog() {
        if (importedLayers.isEmpty()) {
            showSnackbar("No imported layers to manage")
            return
        }

        val dBind = DialogLayersBinding.inflate(layoutInflater)
        
        if (targetPoint != null) {
            dBind.layoutDialogCompass.visibility = android.view.View.VISIBLE
            currentDialogCompass = dBind.imgDialogCompass
            updateGuidanceUI()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dBind.root)
            .setOnDismissListener { currentDialogCompass = null }
            .create()

        val layerNames = importedLayers.keys.toList()
        dBind.recyclerLayers.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        dBind.recyclerLayers.adapter = LayersAdapter(layerNames)

        dBind.btnClearAll.setOnClickListener {
            importedLayers.values.forEach { map.overlays.remove(it) }
            importedLayers.clear()
            map.invalidate()
            dialog.dismiss()
            showSnackbar(getString(R.string.layers_cleared))
        }

        dBind.btnOk.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    inner class LayersAdapter(private val names: List<String>) : androidx.recyclerview.widget.RecyclerView.Adapter<LayersAdapter.ViewHolder>() {
        inner class ViewHolder(val b: ItemLayerBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) = ViewHolder(ItemLayerBinding.inflate(layoutInflater, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val name = names[position]
            val layer = importedLayers[name]!!
            holder.b.checkLayer.text = name
            holder.b.checkLayer.isChecked = map.overlays.contains(layer)
            holder.b.checkLayer.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    if (!map.overlays.contains(layer)) map.overlays.add(layer)
                } else {
                    map.overlays.remove(layer)
                }
                map.invalidate()
            }
        }
        override fun getItemCount() = names.size
    }

    private fun showPointsDialog() {
        val displayList = mutableListOf<Pair<LatLng, Boolean>>()
        markers.forEach { displayList.add(it to true) }
        importedPoints.forEach { displayList.add(it to false) }

        if (displayList.isEmpty()) {
            showSnackbar(getString(R.string.no_points_available))
            return
        }

        val dBind = DialogPointsBinding.inflate(layoutInflater)
        
        if (targetPoint != null) {
            dBind.layoutDialogCompass.visibility = android.view.View.VISIBLE
            currentDialogCompass = dBind.imgDialogCompass
            updateGuidanceUI()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dBind.root)
            .setOnDismissListener { currentDialogCompass = null }
            .create()

        dBind.recyclerPoints.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        dBind.recyclerPoints.adapter = PointsAdapter(displayList) { dialog.dismiss() }

        dBind.btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    inner class PointsAdapter(
        private val list: MutableList<Pair<LatLng, Boolean>>,
        private val onAction: () -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<PointsAdapter.ViewHolder>() {
        inner class ViewHolder(val b: ItemPointBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) = ViewHolder(ItemPointBinding.inflate(layoutInflater, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (point, isMeasurement) = list[position]
            val type = if (isMeasurement) "M" else "I"
            val name = point.name ?: "$type-Point ${position + 1}"
            
            holder.b.txtPointName.text = name
            holder.b.txtPointCoords.text = String.format("%.6f, %.6f", point.latitude, point.longitude)
            
            holder.itemView.setOnClickListener {
                map.controller.animateTo(GeoPoint(point.latitude, point.longitude))
                map.controller.setZoom(18.0)
                onAction()
            }
            
            holder.b.btnNav.setOnClickListener {
                startGuidingTo(GeoPoint(point.latitude, point.longitude), name)
                onAction()
            }
            
            holder.b.btnDelete.setOnClickListener {
                if (isMeasurement) markers.remove(point)
                else {
                    importedPoints.remove(point)
                    saveImportedPoints()
                }
                list.removeAt(position)
                notifyItemRemoved(position)
                drawEverything()
                if (list.isEmpty()) onAction()
            }
        }
        override fun getItemCount() = list.size
    }

    private var targetPoint: GeoPoint? = null
    private var guidanceOverlay: Polyline? = null

    private fun startGuidingTo(point: GeoPoint, name: String) {
        targetPoint = point
        locationOverlay.disableFollowLocation()
        
        binding.cardInstruction.visibility = android.view.View.VISIBLE
        
        if (guidanceOverlay == null) {
            guidanceOverlay = Polyline(map).apply {
                outlinePaint.color = Color.RED
                outlinePaint.strokeWidth = 8f
                outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 20f), 0f)
            }
        }

        destinationMarker?.let { map.overlays.remove(it) }
        destinationMarker = Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = name
        }
        
        updateGuidanceUI()
    }

    private fun updateGuidanceUI() {
        val target = targetPoint ?: return
        // Strictly use lastKnownLocation for guidance originating from current GPS
        val current = lastKnownLocation ?: return
        
        val currentGeo = GeoPoint(current.latitude, current.longitude)
        
        if (guidanceOverlay == null) {
            guidanceOverlay = Polyline(map).apply {
                outlinePaint.color = Color.RED
                outlinePaint.strokeWidth = 8f
                outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 20f), 0f)
            }
        }
        
        guidanceOverlay?.setPoints(listOf(currentGeo, target))
        
        val results = FloatArray(1)
        Location.distanceBetween(current.latitude, current.longitude, target.latitude, target.longitude, results)
        val distance = results[0]
        
        val bearing = currentGeo.bearingTo(target)
        val directionStr = getDirectionText(bearing.toFloat())

        // Standard orientation update logic
        updateGuidanceRotation(compass.orientation)

        binding.txtNextInstruction.text = getString(R.string.guiding_to_with_bearing, destinationMarker?.title ?: "Target", directionStr)
        binding.txtDistanceTime.text = getString(R.string.nav_dist_time_format, distance / 1000, (distance / 50).toInt()) 
        
        // 3m Arrival Threshold
        if (distance < 3) {
            showSnackbar(getString(R.string.arrived_at_target))
            stopNavigation()
        }
        
        drawEverything()
    }

    private fun updateGuidanceRotation(deviceOrientation: Float) {
        val target = targetPoint ?: return
        val current = lastKnownLocation ?: return
        
        val results = FloatArray(1)
        Location.distanceBetween(current.latitude, current.longitude, target.latitude, target.longitude, results)
        val distance = results[0]
        
        val bearing = GeoPoint(current.latitude, current.longitude).bearingTo(target)
        
        // 10m North-up transition for last-meter guidance
        val finalRotation = if (distance < 10) {
            -deviceOrientation
        } else {
            bearing.toFloat() - deviceOrientation
        }

        binding.imgNavCompass.rotation = finalRotation
        currentDialogCompass?.rotation = finalRotation
    }

    private fun getDirectionText(bearing: Float): String {
        val b = if (bearing < 0) bearing + 360 else bearing
        return when {
            b >= 337.5 || b < 22.5 -> "North"
            b < 67.5 -> "North-East"
            b < 112.5 -> "East"
            b < 157.5 -> "South-East"
            b < 202.5 -> "South"
            b < 247.5 -> "South-West"
            b < 292.5 -> "West"
            else -> "North-West"
        }
    }



    private fun drawEverything() {
        map.overlays.clear()
        map.overlays.add(compass)
        map.overlays.add(mapEventsOverlay)
        
        // Re-add active imported layers
        importedLayers.values.forEach { layer ->
            if (!map.overlays.contains(layer)) {
                map.overlays.add(layer)
            }
        }

        // Draw measurement markers (these form the polygon)
        markers.forEachIndexed { index, point ->
            val m = Marker(map)
            m.position = GeoPoint(point.latitude, point.longitude)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            m.icon = BitmapDrawable(resources, createNumberedMarkerIcon(index + 1))
            m.title = point.name ?: "Point ${index + 1}"
            m.snippet = "${point.latitude}, ${point.longitude}"
            m.setOnMarkerClickListener { marker, _ ->
                showMarkerOptions(marker)
                true
            }
            map.overlays.add(m)
        }

        // Draw imported points (these stay as individual points)
        importedPoints.forEachIndexed { index, point ->
            val m = Marker(map)
            m.position = GeoPoint(point.latitude, point.longitude)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            // Use a different color or icon for imported points
            m.icon = ResourcesCompat.getDrawable(resources, org.osmdroid.library.R.drawable.marker_default, null)
            m.title = point.name ?: "Imported ${index + 1}"
            m.snippet = "${point.latitude}, ${point.longitude}"
            m.setOnMarkerClickListener { marker, _ ->
                showMarkerOptions(marker)
                true
            }
            map.overlays.add(m)
        }

        // Auto-join polyline for measurement markers
        if (markers.size >= 2) {
            val polyline = Polyline().apply {
                setPoints(markers.map { GeoPoint(it.latitude, it.longitude) })
                color = Color.BLUE
                width = 5f
            }
            map.overlays.add(polyline)
        }

        // Auto-close polygon if at least 3 measurement points
        if (markers.size >= 3) {
            polygonOverlay = Polygon().apply {
                points = markers.map { GeoPoint(it.latitude, it.longitude) }
                outlinePaint.color = Color.BLUE
                fillPaint.color = 0x330099FF
            }
            map.overlays.add(polygonOverlay)
            lastArea = computePolygonArea(markers)
        }

        // Add navigation/guidance overlays on top
        guidanceOverlay?.let { map.overlays.add(it) }
        destinationMarker?.let { map.overlays.add(it) }

        // My location on top
        if (!map.overlays.contains(locationOverlay)) {
            map.overlays.add(locationOverlay)
        }

        adjustMapViewport()
        map.invalidate()
    }

    private fun showMarkerOptions(marker: Marker) {
        val options = arrayOf("Guide to (Compass)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Navigate to ${marker.title}")
            .setItems(options) { _, _ ->
                startGuidingTo(marker.position, marker.title)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun adjustMapViewport() {
        val pointsToInclude = mutableListOf<GeoPoint>()
        
        if (targetPoint != null) {
            // Focusing on navigation/guidance: include user and destination
            lastKnownLocation?.let { pointsToInclude.add(GeoPoint(it.latitude, it.longitude)) }
            targetPoint?.let { pointsToInclude.add(it) }
        } else {
            // Regular view: include all markers and imported points
            pointsToInclude.addAll(markers.map { GeoPoint(it.latitude, it.longitude) })
            pointsToInclude.addAll(importedPoints.map { GeoPoint(it.latitude, it.longitude) })
            
            // Fallback: If no markers or imported points exist, zoom to current location
            if (pointsToInclude.isEmpty()) {
                lastKnownLocation?.let { pointsToInclude.add(GeoPoint(it.latitude, it.longitude)) }
            }
        }

        if (pointsToInclude.isEmpty()) return

        val lats = pointsToInclude.map { it.latitude }
        val lons = pointsToInclude.map { it.longitude }
        val north = lats.maxOrNull()!!
        val south = lats.minOrNull()!!
        val east = lons.maxOrNull()!!
        val west = lons.minOrNull()!!

        // Add 20% padding for better visibility
        val latMargin = if (north == south) 0.001 else (north - south) * 0.2
        val lonMargin = if (east == west) 0.001 else (east - west) * 0.2
        val bbox = BoundingBox(north + latMargin, east + lonMargin, south - latMargin, west - lonMargin)

        map.zoomToBoundingBox(bbox, true)
    }
    // -------------------- AREA CALCULATION --------------------

    private fun computePolygonArea(points: List<LatLng>): Quintuple<Double, Double, Double, Double, Double> {
        if (points.size < 3) return Quintuple(0.0, 0.0, 0.0, 0.0, 0.0)

        // WGS84 ellipsoid parameters
        val a = 6378137.0       // semi-major axis in meters
        val f = 1 / 298.257223563
        val b = a * (1 - f)     // semi-minor axis

        var total = 0.0
        val n = points.size

        fun rad(deg: Double) = Math.toRadians(deg)

        for (i in 0 until n) {
            val p1 = points[i]
            val p2 = points[(i + 1) % n]

            val lat1 = rad(p1.latitude)
            val lon1 = rad(p1.longitude)
            val lat2 = rad(p2.latitude)
            val lon2 = rad(p2.longitude)

            // Use approximate formula for small segments (sufficient for most practical areas)
            val deltaLon = lon2 - lon1
            total += deltaLon * (2 + sin(lat1) + sin(lat2))
        }

        val area = abs(total * a * b / 2.0) // area in square meters

        val acres = area * 0.00024711
        val hectares = area / 10000
        val sqFeet = area * 10.7639
        val sqYards = area * 1.19599

        return Quintuple(area, acres, hectares, sqFeet, sqYards)
    }
    private fun finishPolygon() {
        if (markers.size < 3) {
            showSnackbar(getString(R.string.error_min_points))
            return
        }
        val geoPoints = markers.map { GeoPoint(it.latitude, it.longitude) }
        polygonOverlay = Polygon().apply {
            points = geoPoints
            outlinePaint.color = Color.BLUE
            fillPaint.color = 0x330099FF
        }
        lastArea = computePolygonArea(markers)
        drawEverything()
        showAreaDialog(lastArea!!)
    }

    private fun showAreaDialog(area: Quintuple<Double, Double, Double, Double, Double>) {
        val dBind = DialogAreaBinding.inflate(layoutInflater)

        // Round off values to 1 decimal place
        val meters = String.format("%.1f", area.first)
        val hectares = String.format("%.1f", area.third)
        val acres = String.format("%.1f", area.second)
        val feet = String.format("%.1f", area.fourth)
        val yards = String.format("%.1f", area.fifth)

        // Set the rounded values to the TextViews
        dBind.txtMeters.text = getString(R.string.format_meters, meters)
        dBind.txtHectares.text = getString(R.string.format_hectares, hectares)
        dBind.txtAcres.text = getString(R.string.format_acres, acres)
        dBind.txtFeet.text = getString(R.string.format_feet, feet)
        dBind.txtYards.text = getString(R.string.format_yards, yards)

        if (targetPoint != null) {
            dBind.layoutDialogCompass.visibility = android.view.View.VISIBLE
            currentDialogCompass = dBind.imgDialogCompass
            updateGuidanceUI()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dBind.root)
            .setOnDismissListener { currentDialogCompass = null }
            .create()

        dBind.btnOk.setOnClickListener { dialog.dismiss() }
        dBind.btnSave.setOnClickListener {
            dialog.dismiss()
            showSaveDialog()
        }
        dialog.show()
    }

    private fun showSaveDialog() {
        val dBind = DialogSavePolygonBinding.inflate(layoutInflater)
        
        if (targetPoint != null) {
            dBind.layoutDialogCompass.visibility = android.view.View.VISIBLE
            currentDialogCompass = dBind.imgDialogCompass
            updateGuidanceUI()
        }

        MaterialAlertDialogBuilder(this)
            .setView(dBind.root)
            .setOnDismissListener { currentDialogCompass = null }
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val name = dBind.inputName.text.toString().ifEmpty { "Polygon ${System.currentTimeMillis()}" }
                savePolygonLocally(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSavedPolygonsDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val gson = Gson()
        val json = prefs.getString("saved_polygons", null)
        val type = object : TypeToken<MutableList<SavedPolygon>>() {}.type
        val savedPolygons: MutableList<SavedPolygon> = if (json != null) {
            gson.fromJson(json, type)
        } else {
            mutableListOf()
        }

        val dBind = DialogLoadPolygonBinding.inflate(layoutInflater)
        
        if (targetPoint != null) {
            dBind.layoutDialogCompass.visibility = android.view.View.VISIBLE
            currentDialogCompass = dBind.imgDialogCompass
            updateGuidanceUI()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dBind.root)
            .setOnDismissListener { currentDialogCompass = null }
            .create()

        if (savedPolygons.isEmpty()) {
            dBind.txtNoSaved.visibility = android.view.View.VISIBLE
            dBind.recyclerSavedPolygons.visibility = android.view.View.GONE
        } else {
            dBind.txtNoSaved.visibility = android.view.View.GONE
            dBind.recyclerSavedPolygons.visibility = android.view.View.VISIBLE
            dBind.recyclerSavedPolygons.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
            dBind.recyclerSavedPolygons.adapter = PolygonAdapter(savedPolygons) { selectedPolygon ->
                loadSavedPolygon(selectedPolygon)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun loadSavedPolygon(saved: SavedPolygon) {
        markers.clear()
        markers.addAll(saved.points)
        lastArea = Quintuple(saved.areaM2, saved.areaM2 * 0.00024711, saved.areaM2 / 10000, saved.areaM2 * 10.7639, saved.areaM2 * 1.19599)
        drawEverything()
        showSnackbar(getString(R.string.loaded_format, saved.name))
    }

    private fun savePolygonLocally(name: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val gson = Gson()
        val json = prefs.getString("saved_polygons", null)
        val type = object : TypeToken<MutableList<SavedPolygon>>() {}.type
        val savedPolygons: MutableList<SavedPolygon> = if (json != null) {
            gson.fromJson(json, type)
        } else {
            mutableListOf()
        }

        val newPolygon = SavedPolygon(
            name = name,
            points = markers.toList(),
            areaM2 = lastArea?.first ?: 0.0,
            timestamp = System.currentTimeMillis()
        )
        savedPolygons.add(newPolygon)

        prefs.edit().putString("saved_polygons", gson.toJson(savedPolygons)).apply()
        showSnackbar(getString(R.string.polygon_saved))
    }

    inner class PolygonAdapter(
        private val list: MutableList<SavedPolygon>,
        private val onSelect: (SavedPolygon) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<PolygonAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemSavedPolygonBinding) : 
            androidx.recyclerview.widget.RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val b = ItemSavedPolygonBinding.inflate(
                android.view.LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.itemBinding.txtName.text = item.name
            val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.timestamp))
            holder.itemBinding.txtDetails.text = String.format("%.1f m² • %s", item.areaM2, date)
            
            holder.itemView.setOnClickListener { onSelect(item) }
            holder.itemBinding.btnDelete.setOnClickListener {
                list.removeAt(position)
                notifyItemRemoved(position)
                notifyItemRangeChanged(position, list.size)
                
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                prefs.edit().putString("saved_polygons", Gson().toJson(list)).apply()
            }
        }

        override fun getItemCount() = list.size
    }

    data class SavedPolygon(
        val name: String,
        val points: List<LatLng>,
        val areaM2: Double,
        val timestamp: Long
    )
    // -------------------- EXPORT --------------------
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportLastAreaCsv() {
        if (lastArea == null || markers.isEmpty()) {
            showSnackbar(getString(R.string.no_data_to_export))
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
            showSnackbar(getString(R.string.csv_exported), Snackbar.LENGTH_LONG)

            // Now share the CSV file
            shareFile(uri, "text/csv", "Export Polygon CSV")
        } else {
            showSnackbar(getString(R.string.failed_csv))
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportLastAreaGeoJson() {
        if (markers.isEmpty()) {
            showSnackbar(getString(R.string.no_points_to_export))
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
            showSnackbar(getString(R.string.geojson_exported), Snackbar.LENGTH_LONG)

            // Now share the GeoJSON file
            shareFile(uri, "application/geo+json", "Export Polygon GeoJSON")
        } else {
            showSnackbar(getString(R.string.failed_geojson))
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportLastAreaGpx() {
        if (markers.isEmpty()) {
            showSnackbar(getString(R.string.no_data_to_export))
            return
        }

        val filename = "polygon_export_${System.currentTimeMillis()}.gpx"
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                out.writer().use { writer ->
                    writer.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                    writer.appendLine("""<gpx version="1.1" creator="LandScope" xmlns="http://www.topografix.com/GPX/1/1">""")
                    writer.appendLine("""  <trk>""")
                    writer.appendLine("""    <name>Polygon Boundary</name>""")
                    writer.appendLine("""    <trkseg>""")
                    markers.forEach { pt ->
                        writer.appendLine("""      <trkpt lat="${pt.latitude}" lon="${pt.longitude}"></trkpt>""")
                    }
                    // Close the polygon by repeating the first point
                    val first = markers.first()
                    writer.appendLine("""      <trkpt lat="${first.latitude}" lon="${first.longitude}"></trkpt>""")
                    writer.appendLine("""    </trkseg>""")
                    writer.appendLine("""  </trk>""")
                    writer.appendLine("""</gpx>""")
                }
            }
            showSnackbar("GPX exported to Downloads", Snackbar.LENGTH_LONG)
            shareFile(uri, "application/gpx+xml", "Export Polygon GPX")
        } else {
            showSnackbar("Failed to create GPX file")
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
            showSnackbar(getString(R.string.permission_denied))
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

    private inner class NavCompassOverlay(context: android.content.Context, provider: IOrientationProvider, mapView: MapView) 
        : CompassOverlay(context, provider, mapView) {
        override fun onOrientationChanged(orientation: Float, orientationProvider: IOrientationProvider?) {
            super.onOrientationChanged(orientation, orientationProvider)
            runOnUiThread {
                updateGuidanceRotation(orientation)
            }
        }
    }
}


