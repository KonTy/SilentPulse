package com.silentpulse.messenger.feature.assistant

import android.content.Intent
import android.os.Bundle
import com.bluelinelabs.conductor.ChangeHandlerFrameLayout
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkThemedActivity
import dagger.android.AndroidInjection

class AssistantActivity : QkThemedActivity() {

    private val container: ChangeHandlerFrameLayout get() = findViewById(R.id.container)
    private lateinit var router: Router

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.container_activity)

        router = Conductor.attachRouter(this, container, savedInstanceState)
        if (!router.hasRootController()) {
            router.setRoot(RouterTransaction.with(AssistantController()))
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        router.onActivityResult(requestCode, resultCode, data)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!router.handleBack()) super.onBackPressed()
    }
}
