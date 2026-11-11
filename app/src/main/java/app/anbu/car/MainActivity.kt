package app.anbu.car

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import app.anbu.car.gleo.GleoActions
import app.anbu.car.ui.AnbuRoot

class AnbuApp : Application() {
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = AppController(this)
        controller.start()
    }
}

/**
 * 화면은 하나다. Gleo AI가 보내는 세 가지 액션도 이 액티비티가 받는다.
 * 가로 창과 세로 창을 모두 지원하고, 창 크기가 바뀌어도 다시 만들지 않는다.
 */
class MainActivity : ComponentActivity() {

    private val controller: AppController get() = (application as AnbuApp).controller

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            controller.onPermissionsChanged()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller.requestPermissions = { list -> permissions.launch(list.toTypedArray()) }
        setContent { AnbuRoot(controller) }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 설정 앱에서 권한을 바꾸고 돌아온 경우를 반영한다.
        controller.onPermissionsChanged()
    }

    override fun onDestroy() {
        controller.requestPermissions = null
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // 시연 환경에서 릴레이 주소를 바꿀 때: adb shell am start -n app.anbu.car/.MainActivity --es relay_url http://...
        intent.getStringExtra(EXTRA_RELAY_URL)?.takeIf { it.startsWith("http") }?.let { controller.setRelayUrl(it) }

        val action = intent.action ?: return
        if (action in GleoActions.ALL) {
            val reply = controller.handleGleoAction(action, intent.getStringExtra(GleoActions.EXTRA_PARAM))
            GleoActions.respond(this, reply)
        }
    }

    private companion object {
        const val EXTRA_RELAY_URL = "relay_url"
    }
}
