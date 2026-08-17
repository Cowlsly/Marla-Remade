package com.vayunmathur.maps.car

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GoogleSearchDataSource
import com.vayunmathur.maps.data.google.GoogleSearchResult
import com.vayunmathur.maps.util.NavigationService
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.RouteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.maplibre.spatialk.geojson.Position

/**
 * Android Auto search screen (P12).
 *
 * Voice-first: the Search action launches the **system** [SpeechRecognizer]
 * (the OS routes it to the user-selected recognition service — the same
 * system-STT path P8 uses on the phone via `VoiceSearchButton`), and the final
 * transcript is fed to the existing keyless [GoogleSearchDataSource] (P3). Hits
 * are shown in a [PlaceListNavigationTemplate] (list + map markers); tapping a
 * result routes to it with the existing [OfflineRouter], starts the shared
 * [NavigationSessionManager] + [NavigationService], and returns to the map
 * screen where turn-by-turn takes over.
 */
class CarSearchScreen(carContext: CarContext) : Screen(carContext) {

    private var results: List<GoogleSearchResult> = emptyList()
    private var loading = false
    private var message: String? = null
    private var recognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        message = carContext.getString(R.string.car_voice_hint)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                runCatching { recognizer?.destroy() }
                recognizer = null
                scope.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val builder = PlaceListNavigationTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.BACK)
                    .setTitle(carContext.getString(R.string.car_search_title))
                    .build()
            )
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.car_search_action))
                            .setOnClickListener { requestMicThenListen() }
                            .build()
                    )
                    .build()
            )
        if (loading) {
            builder.setLoading(true)
        } else {
            builder.setItemList(buildItemList())
        }
        return builder.build()
    }

    private fun buildItemList(): ItemList {
        val ib = ItemList.Builder()
        if (results.isEmpty()) {
            ib.setNoItemsMessage(
                message ?: carContext.getString(R.string.car_no_results)
            )
        } else {
            for (r in results) {
                val row = Row.Builder().setTitle(r.name)
                val subtitle = r.address ?: r.category
                if (!subtitle.isNullOrBlank()) row.addText(subtitle)
                row.setMetadata(
                    Metadata.Builder()
                        .setPlace(Place.Builder(CarLocation.create(r.lat, r.lng)).build())
                        .build()
                )
                row.setOnClickListener { startNavigationTo(r) }
                ib.addItem(row.build())
            }
        }
        return ib.build()
    }

    // ----------------------------------------------------------------
    // System STT
    // ----------------------------------------------------------------

    private fun requestMicThenListen() {
        val granted = ContextCompat.checkSelfPermission(
            carContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startListening()
        } else {
            runCatching {
                carContext.requestPermissions(
                    listOf(Manifest.permission.RECORD_AUDIO)
                ) { grantedList, _ ->
                    if (Manifest.permission.RECORD_AUDIO in grantedList) startListening()
                }
            }
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(carContext)) return
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(carContext)
            .also { recognizer = it }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(bundle: Bundle?) {
                bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runSearch(it) }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { sr.startListening(intent) }
    }

    // ----------------------------------------------------------------
    // Search + routing (existing stack)
    // ----------------------------------------------------------------

    private fun runSearch(query: String) {
        val near = lastKnownPosition()
        loading = true
        message = carContext.getString(R.string.car_searching)
        invalidate()
        scope.launch {
            val hits = GoogleSearchDataSource.search(
                query,
                near?.latitude ?: 0.0,
                near?.longitude ?: 0.0,
            )
            results = hits
            loading = false
            message = if (hits.isEmpty()) {
                carContext.getString(R.string.car_no_results)
            } else null
            invalidate()
        }
    }

    private fun startNavigationTo(result: GoogleSearchResult) {
        val userPos = lastKnownPosition()
        if (userPos == null) {
            message = carContext.getString(R.string.car_route_failed)
            invalidate()
            return
        }
        loading = true
        invalidate()
        scope.launch {
            val dest = Position(longitude = result.lng, latitude = result.lat)
            val routeFeature = SpecificFeature.Route(
                listOf(
                    null, // first waypoint = current position (getRouteMulti fills it)
                    SpecificFeature.GenericPlace(
                        name = result.name,
                        phone = null,
                        website = null,
                        openingHours = null,
                        position = dest,
                    ),
                )
            )
            val route = OfflineRouter.getRouteMulti(
                carContext, routeFeature, userPos, RouteService.TravelMode.DRIVE
            )
            if (route == null) {
                loading = false
                message = carContext.getString(R.string.car_route_failed)
                invalidate()
                return@launch
            }
            carContext.startForegroundService(
                Intent(carContext, NavigationService::class.java)
            )
            NavigationSessionManager.init(carContext)
            NavigationSessionManager.start(
                route = route,
                mode = RouteService.TravelMode.DRIVE,
                destination = dest,
                destinationLabel = result.name,
            )
            // Back to the map; NavMapScreen observes the session and shows
            // turn-by-turn.
            screenManager.pop()
        }
    }

    private fun lastKnownPosition(): Position? {
        if (ContextCompat.checkSelfPermission(
                carContext, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = carContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val loc: Location? = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        return loc?.let { Position(longitude = it.longitude, latitude = it.latitude) }
    }
}
