private fun isDriveModeEnabledForAllNotifications(): Boolean {
    return try {
        if (::driveModeService.isInitialized) {
            driveModeService.isDriveModeEnabledForAllNotifications()
        } else {
            Timber.w("DriveModeService not initialized yet")
            false
        }
    } catch (e: Exception) {
        Timber.e(e, "Error checking Drive Mode status")
        false
    }
}
