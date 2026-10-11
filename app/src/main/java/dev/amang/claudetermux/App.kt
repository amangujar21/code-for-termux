package dev.amang.claudetermux

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.amang.claudetermux.bridge.BridgeClient
import dev.amang.claudetermux.bridge.Endpoint
import dev.amang.claudetermux.data.Settings
import dev.amang.claudetermux.service.TurnTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: Settings
        private set
    lateinit var bridge: BridgeClient
        private set
    lateinit var turns: TurnTracker
        private set

    /** True while any activity is visible; used to decide whether a notification is worth posting. */
    var inForeground = false
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        bridge = BridgeClient(scope)
        turns = TurnTracker(this)

        scope.launch {
            settings.flow
                .map { Endpoint(it.host, it.port, it.token) }
                .distinctUntilChanged()
                .collect { if (it.token.isNotEmpty()) bridge.connect(it) }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                started++
                inForeground = true
                turns.clearNotifications()
            }
            override fun onActivityStopped(activity: Activity) {
                started--
                inForeground = started > 0
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
