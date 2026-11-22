package io.github.mpridal.ctecikomiks

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import io.github.mpridal.ctecikomiks.ui.ProtoScreen
import io.github.mpridal.ctecikomiks.ui.ProtoViewModel

class MainActivity : ComponentActivity() {
    private val vm: ProtoViewModel by viewModels()

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onMicPermission(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as CteciApp
        setContent { MaterialTheme { ProtoScreen(vm, app) } }
        app.updater.checkOnStart()
        app.uploader.uploadPending()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.onMicPermission(true)
        } else {
            // V MVP se povolení žádá v rodičovské sekci; prototyp se zeptá rovnou.
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
