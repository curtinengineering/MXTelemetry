package com.curtinengineering.miataobdlogger.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * Android Auto session lifecycle manager creating the main projection screen.
 */
class ClusterCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return ClusterScreen(carContext)
    }
}
