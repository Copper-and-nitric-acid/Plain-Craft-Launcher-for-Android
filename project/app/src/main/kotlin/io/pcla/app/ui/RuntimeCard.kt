package io.pcla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pcla.app.RuntimePack
import io.pcla.core.launch.JavaPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First piece of the JVM layer: unpack the bundled tar.xz into an executable java.
 * This page does not launch the game; it only proves the packaged runtime can be unpacked and executed on this phone.
 */
@Composable
internal fun RuntimeCard(neededMajor: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember {
        mutableStateOf(JavaPolicy.bundledMajors().associateWith { RuntimePack.isInstalled(context, it) })
    }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    PclCard("JVM 运行时") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PclField("当前版本需要", "Java $neededMajor")
            JavaPolicy.bundledMajors().forEach { major ->
                val ok = installed[major] == true
                PclField("JRE $major", if (ok) "已解包，可直接 exec" else "随包安装（首次使用时解包）")
            }
            PclButton(
                text = if (busy) "解包中…" else "解包 Java $neededMajor",
                enabled = !busy,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { RuntimePack.ensure(context, neededMajor) }
                        }
                        installed = JavaPolicy.bundledMajors().associateWith {
                            RuntimePack.isInstalled(context, it)
                        }
                        busy = false
                        message = result.fold(
                            onSuccess = { "已解包：${it.javaBin.absolutePath}" },
                            onFailure = { "${it.javaClass.simpleName}: ${it.message}" },
                        )
                    }
                },
            )
            message?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
