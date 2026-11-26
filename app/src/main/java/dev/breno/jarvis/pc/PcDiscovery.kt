package dev.breno.jarvis.pc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/**
 * Acha o agente do PC na rede local sem precisar saber o IP: manda um
 * broadcast UDP e o jarvis_agent.py responde com a porta HTTP. O IP vem do
 * próprio pacote de resposta. O token NUNCA trafega aqui.
 */
object PcDiscovery {
    const val PORT = 8766
    private const val MAGIC = "JARVIS_DISCOVER_V1"

    data class Found(val url: String, val name: String)

    suspend fun discover(timeoutMs: Long = 1500): List<Found> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, Found>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 250
            val payload = MAGIC.toByteArray()
            val targets = (broadcastAddresses() + InetAddress.getByName("255.255.255.255")).distinct()
            for (target in targets) {
                runCatching { socket.send(DatagramPacket(payload, payload.size, target, PORT)) }
            }

            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                coroutineContext.ensureActive()
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                    if (json.optString("service") != "jarvis") continue
                    val host = packet.address.hostAddress ?: continue
                    val url = "http://$host:${json.optInt("port", 8765)}"
                    found[url] = Found(url, json.optString("name").ifBlank { host })
                } catch (_: SocketTimeoutException) {
                    // continua esperando até o prazo
                } catch (_: Exception) {
                    // pacote estranho: ignora
                }
            }
        }
        found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())
}
