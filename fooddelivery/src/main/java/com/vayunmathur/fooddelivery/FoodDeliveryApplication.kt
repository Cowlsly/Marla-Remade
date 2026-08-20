package com.vayunmathur.fooddelivery

import android.app.Application
import com.vayunmathur.fooddelivery.platform.AppInit

class FoodDeliveryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppInit.start(this)
    }
}
