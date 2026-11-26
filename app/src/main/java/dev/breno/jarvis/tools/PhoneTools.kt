package dev.breno.jarvis.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import dev.breno.jarvis.agent.JarvisTool
import dev.breno.jarvis.agent.Schemas
import dev.breno.jarvis.agent.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Ferramentas que rodam no próprio celular. */
object PhoneTools {
    fun all(context: Context): List<JarvisTool> = listOf(
        SetTimerTool(context.applicationContext),
        SetAlarmTool(context.applicationContext),
        FlashlightTool(context.applicationContext),
    )
}

private suspend fun startClockIntent(context: Context, intent: Intent, success: String): ToolResult =
    withContext(Dispatchers.Main) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult.ok(success)
        } catch (e: ActivityNotFoundException) {
            ToolResult.error("Nenhum app de relógio neste celular aceita esse pedido.")
        } catch (e: SecurityException) {
            ToolResult.error("Sem permissão para usar o app de relógio.")
        }
    }

class SetTimerTool(private val context: Context) : JarvisTool {
    override val name = "phone_set_timer"
    override val label = "Criando timer"
    override val description =
        "Cria um timer (contagem regressiva) no app de relógio do celular. Use para pedidos como " +
            "'me avisa em 10 minutos' ou 'timer de 30 segundos'."
    override val inputSchema = Schemas.obj(
        "seconds" to Schemas.integer("Duração total em segundos (1 a 86400)."),
        "label" to Schemas.string("Nome curto do timer, ou string vazia."),
    )

    override suspend fun execute(input: JSONObject): ToolResult {
        val seconds = input.optInt("seconds", 0)
        if (seconds !in 1..86_400) return ToolResult.error("Duração inválida: $seconds segundos.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        input.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return startClockIntent(context, intent, "Timer de $seconds segundos criado.")
    }
}

class SetAlarmTool(private val context: Context) : JarvisTool {
    override val name = "phone_set_alarm"
    override val label = "Criando alarme"
    override val description =
        "Cria um alarme no app de relógio do celular para a próxima ocorrência do horário informado " +
            "(formato 24h, horário local)."
    override val inputSchema = Schemas.obj(
        "hour" to Schemas.integer("Hora, 0 a 23."),
        "minute" to Schemas.integer("Minuto, 0 a 59."),
        "label" to Schemas.string("Nome curto do alarme, ou string vazia."),
    )

    override suspend fun execute(input: JSONObject): ToolResult {
        val hour = input.optInt("hour", -1)
        val minute = input.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return ToolResult.error("Horário inválido: $hour:$minute.")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        input.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return startClockIntent(context, intent, "Alarme criado para %02d:%02d.".format(hour, minute))
    }
}

class FlashlightTool(private val context: Context) : JarvisTool {
    override val name = "phone_flashlight"
    override val label = "Mexendo na lanterna"
    override val description = "Liga ou desliga a lanterna (flash da câmera) do celular."
    override val inputSchema = Schemas.obj(
        "on" to Schemas.boolean("true para ligar, false para desligar."),
    )

    override suspend fun execute(input: JSONObject): ToolResult {
        val on = input.optBoolean("on", true)
        val camera = context.getSystemService(CameraManager::class.java)
            ?: return ToolResult.error("Câmera indisponível.")
        val id = camera.cameraIdList.firstOrNull { id ->
            camera.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult.error("Este celular não tem flash.")
        camera.setTorchMode(id, on)
        return ToolResult.ok(if (on) "Lanterna ligada." else "Lanterna desligada.")
    }
}
