package com.curtinengineering.mxtelemetry.car

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.lifecycleScope

/**
 * Android Auto Screen implementation registering SurfaceCallback to receive the car's hardware surface,
 * providing a minimal 1-item ActionStrip ("MFD") required by NavigationTemplate,
 * and rendering our digital cluster HUD onto the car's display safely.
 */
class ClusterScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    init {
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onGetTemplate(): Template {
        val cycleMfdAction = Action.Builder()
            .setTitle("MFD")
            .setOnClickListener {
                CarClusterRenderer.toggleMfdPage()
                invalidate()
            }
            .build()

        val actionStrip = ActionStrip.Builder()
            .addAction(cycleMfdAction)
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (surfaceContainer.surface == null || !surfaceContainer.surface!!.isValid) return
        CarClusterRenderer.startRendering(surfaceContainer, lifecycleScope, carContext)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        CarClusterRenderer.stopRendering()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        // Handle visible surface bounds safely
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        // Handle stable area insets safely
    }
}
