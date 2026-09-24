package info.eworldq8.soor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import info.eworldq8.soor.scan.ScanViewModel
import info.eworldq8.soor.ui.SoorApp
import info.eworldq8.soor.ui.Theme

class MainActivity : ComponentActivity() {
    private val vm: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Theme.navy,
                    background = Theme.bg,
                    surface = Theme.panel
                )
            ) {
                SoorApp(vm)
            }
        }
    }
}
