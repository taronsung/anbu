package app.anbu.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.anbu.car.AppController
import app.anbu.car.Screen

@Composable
fun AnbuRoot(controller: AppController) {
    val ui = controller.ui
    BoxWithConstraints(Modifier.fillMaxSize()) {
        AnbuTheme(maxWidth, maxHeight) {
            val c = LocalAnbuColors.current
            val pad = if (LocalWide.current) 44.dp else 30.dp
            Box(
                Modifier
                    .fillMaxSize()
                    .background(c.paper)
                    .padding(pad)
            ) {
                when (ui.screen) {
                    Screen.CONSENT -> ConsentScreen(ui, controller)
                    Screen.HOME -> HomeScreen(ui, controller)
                    Screen.DRIVING -> DrivingScreen(ui, controller)
                    Screen.ARRIVAL -> ArrivalScreen(ui, controller)
                    Screen.CHECK_IN -> CheckInScreen(ui, controller)
                    Screen.DIARY -> DiaryScreen(ui, controller)
                    Screen.SETTINGS -> SettingsScreen(ui, controller)
                    Screen.PLACES -> PlacesScreen(ui, controller)
                    Screen.DELETE -> DeleteScreen(ui, controller)
                    Screen.STATUS -> StatusScreen(ui, controller)
                }
                // 한 줄 안내는 화면 위쪽에 띄워 아래쪽 버튼을 가리지 않게 한다.
                // 주행 화면은 같은 내용을 화면 안에 이미 보여 주므로 띄우지 않는다.
                val banner = ui.banner
                if (banner != null && ui.screen != Screen.CONSENT && ui.screen != Screen.DRIVING) {
                    // 오른쪽 위의 돌아가기·설정 버튼은 가리지 않는다.
                    Banner(banner, Modifier.align(Alignment.TopStart).fillMaxWidth(if (LocalWide.current) 0.72f else 0.62f))
                }
            }
        }
    }
}
