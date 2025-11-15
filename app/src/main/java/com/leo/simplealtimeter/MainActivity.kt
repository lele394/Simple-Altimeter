package com.leo.simplealtimeter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.util.*
import kotlin.collections.LinkedHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity(), SensorEventListener {

    // UI Views
    private lateinit var coordinatesText: TextView
    private lateinit var altitudeText: TextView
    private lateinit var speedText: TextView
    private lateinit var satelliteText: TextView
    private lateinit var compassDial: ImageView
    private lateinit var degreeText: TextView

    // Location Services
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationManager: LocationManager

    // Sensor Manager
    private lateinit var sensorManager: SensorManager
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var currentDegree = 0f

    // Smoothing
    private val bearingHistory = LinkedList<Double>()
    private val HISTORY_SIZE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI Views
        coordinatesText = findViewById(R.id.coordinatesText)
        altitudeText = findViewById(R.id.altitudeText)
        speedText = findViewById(R.id.speedText)
        satelliteText = findViewById(R.id.satelliteText)
        compassDial = findViewById(R.id.compass_dial)
        degreeText = findViewById(R.id.degree_text)

        // Initialize Services
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Check for location permissions
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        } else {
            startLocationUpdates()
        }
    }

    // --- MODIFICATION START: The entire gnssStatusCallback is updated ---
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            super.onSatelliteStatusChanged(status)

            val constellationCounts = LinkedHashMap<String, Int>()
            // Define the order of constellations to be displayed
            val displayOrder = listOf("GPS", "GLO", "GAL", "BEI", "QZSS", "SBAS", "IRNSS")
            displayOrder.forEach { constellation -> constellationCounts[constellation] = 0 }

            var satellitesInFix = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) {
                    satellitesInFix++
                    val constellationName = getConstellationName(status.getConstellationType(i))
                    if (constellationCounts.containsKey(constellationName)) {
                        constellationCounts[constellationName] = constellationCounts.getValue(constellationName) + 1
                    }
                }
            }

            // Update UI on the main thread
            runOnUiThread {
                val spannableBuilder = SpannableStringBuilder()
                spannableBuilder.append("($satellitesInFix IN FIX)\n\n")

                if (satellitesInFix == 0 && status.satelliteCount > 0) {
                    spannableBuilder.append("Searching for signal...")
                } else {
                    for ((constellation, count) in constellationCounts) {
                        val color = when {
                            count > 3 -> R.color.satellite_green
                            count in 1..3 -> R.color.satellite_yellow
                            else -> R.color.satellite_red
                        }

                        val line = "$constellation: $count\n"
                        val start = spannableBuilder.length
                        spannableBuilder.append(line)
                        spannableBuilder.setSpan(
                            ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, color)),
                            start,
                            spannableBuilder.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                satelliteText.text = spannableBuilder
            }
        }
    }
    // --- MODIFICATION END ---

    override fun onResume() {
        super.onResume()
        // Register sensor listeners
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
            sensorManager.registerListener(this, magneticField, SensorManager.SENSOR_DELAY_UI)
        }
        // Register for satellite status updates
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, null)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
        }
        updateOrientationAngles()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateOrientationAngles() {
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
        val orientation = SensorManager.getOrientation(rotationMatrix, orientationAngles)
        val degrees = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0

        bearingHistory.add(degrees)
        if (bearingHistory.size > HISTORY_SIZE) {
            bearingHistory.removeFirst()
        }
        val smoothedDegrees = calculateAverageAngle(bearingHistory)

        val rotateAnimation = RotateAnimation(
            currentDegree, -smoothedDegrees.toFloat(),
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f
        )
        rotateAnimation.duration = 250
        rotateAnimation.fillAfter = true

        compassDial.startAnimation(rotateAnimation)
        currentDegree = -smoothedDegrees.toFloat()
        degreeText.text = "%d°".format(smoothedDegrees.toInt())
    }

    private fun calculateAverageAngle(angles: List<Double>): Double {
        var sumX = 0.0
        var sumY = 0.0
        for (angle in angles) {
            val radians = Math.toRadians(angle)
            sumX += cos(radians)
            sumY += sin(radians)
        }
        val avgX = sumX / angles.size
        val avgY = sumY / angles.size
        val avgRadians = atan2(avgY, avgX)
        val avgDegrees = Math.toDegrees(avgRadians)
        return if (avgDegrees < 0) avgDegrees + 360 else avgDegrees
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(1000L)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { updateLocationUI(it) }
            }
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedClient.requestLocationUpdates(request, callback, mainLooper)
        }
    }

    private fun updateLocationUI(loc: Location) {
        // --- Coordinates Text ---
        val latDir = if (loc.latitude >= 0) "N" else "S"
        val lonDir = if (loc.longitude >= 0) "E" else "W"
        coordinatesText.text = "%.4f° %s, %.4f° %s".format(
            Math.abs(loc.latitude), latDir, Math.abs(loc.longitude), lonDir
        )

        // --- Altitude Text with Multiple Colors ---
        val altitudeBuilder = SpannableStringBuilder()
        altitudeBuilder.append("%d".format(loc.altitude.toInt()))
        val altPrecisionStr = " ±%.1f".format(loc.verticalAccuracyMeters)
        val altStart = altitudeBuilder.length
        altitudeBuilder.append(altPrecisionStr)
        altitudeBuilder.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_grey)),
            altStart, altitudeBuilder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        altitudeBuilder.append(" m")
        altitudeText.text = altitudeBuilder

        // --- Speed Text with Multiple Colors ---
        val speedKmh = loc.speed * 3.6
        val speedAccuracyKmh = loc.speedAccuracyMetersPerSecond * 3.6

        val speedBuilder = SpannableStringBuilder()
        speedBuilder.append("%d".format(speedKmh.toInt()))

        // Only show precision if it's available (not 0.0)
        if (speedAccuracyKmh > 0.0) {
            val speedPrecisionStr = " ±%.1f".format(speedAccuracyKmh)
            val speedStart = speedBuilder.length
            speedBuilder.append(speedPrecisionStr)
            speedBuilder.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_grey)),
                speedStart, speedBuilder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        speedBuilder.append(" km/h")
        speedText.text = speedBuilder
    }

    private fun getConstellationName(constellationType: Int): String {
        return when (constellationType) {
            GnssStatus.CONSTELLATION_GPS -> "GPS"
            GnssStatus.CONSTELLATION_GLONASS -> "GLO"
            GnssStatus.CONSTELLATION_BEIDOU -> "BEI"
            GnssStatus.CONSTELLATION_GALILEO -> "GAL"
            GnssStatus.CONSTELLATION_QZSS -> "QZSS"
            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
            GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
            else -> "UNK"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
    }
}