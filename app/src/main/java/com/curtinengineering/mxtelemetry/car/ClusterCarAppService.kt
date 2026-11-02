package com.curtinengineering.mxtelemetry.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Automotive service entry point handling Android Auto connection lifecycles.
 */
class ClusterCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return ClusterCarSession()
    }
}
