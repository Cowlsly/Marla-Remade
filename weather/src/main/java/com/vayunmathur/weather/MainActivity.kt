package com.vayunmathur.weather

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.widgets.updateWidgetPreviews
import com.vayunmathur.weather.data.WeatherRepository
import com.vayunmathur.weather.platform.WeatherViewModel
import com.vayunmathur.weather.platform.WeatherViewModelFactory
import com.vayunmathur.weather.widget.glance.WeatherGlanceWidgetReceiver

class MainActivity : ComponentActivity() {
    private val viewModel: WeatherViewModel by viewModels {
        WeatherViewModelFactory(application, WeatherRepository.get(application))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FIRST_PARTY covers api.vayunmathur.com + api.open-meteo.com (ISRG) + data.vayunmathur.com
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        updateWidgetPreviews(WeatherGlanceWidgetReceiver::class)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                OfflineAware {
                    Navigation(viewModel)
                }
            }
        }
    }
}
