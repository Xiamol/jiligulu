package com.jiligulu.app.ui.littleworld

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Build
import android.os.Looper
import java.net.Inet4Address
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NearbyGameKind(val tag: String, val port: Int) { XIANGQI("xq", 49761), GOMOKU("go", 49762) }
data class NearbyGameRoom(val id: String, val name: String, val address: String, val port: Int)
data class NearbyRoomsState(val rooms: List<NearbyGameRoom> = emptyList(), val searching: Boolean = false, val error: String? = null)

/** A short-lived foreground presence. No scans, multicast locks, or reconnect work survive stop(). */
class NsdRoomDiscovery(context: Context, private val gameKind: NearbyGameKind, private val playerName: String) {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val selfId = UUID.randomUUID().toString().replace("-", "").take(12)
    private val mutable = MutableStateFlow(NearbyRoomsState())
    val state: StateFlow<NearbyRoomsState> = mutable.asStateFlow()
    private var generation = 0
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolved = linkedMapOf<String, NearbyGameRoom>()
    private val waiting = ArrayDeque<NsdServiceInfo>()
    private val found = hashSetOf<String>()
    private val resolveAttempts = hashMapOf<String, Int>()
    private var resolving = false

    fun start() {
        if (discovery != null) return
        val token = ++generation
        mutable.value = NearbyRoomsState(searching = true)
        val info = NsdServiceInfo().apply {
            serviceName = "Gulu-${gameKind.tag}-$selfId"
            serviceType = TYPE
            port = gameKind.port
            setAttribute("version", "1"); setAttribute("kind", gameKind.tag); setAttribute("id", selfId)
            setAttribute("name", playerName.filter { !it.isISOControl() }.trim().take(16).ifEmpty { "阿噜的朋友" })
        }
        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                val listener = this
                main.post { if (token != generation) runCatching { manager.unregisterService(listener) } }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = post(token) {
                mutable.value = mutable.value.copy(error = "附近房间暂时无法显示，试试重新进入或创建互联网房间")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = registrationListener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener) }
            .onFailure { mutable.value = mutable.value.copy(error = "暂时无法显示附近房间，请检查本地网络权限") }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                val listener = this
                main.post { if (token != generation) runCatching { manager.stopServiceDiscovery(listener) } }
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = post(token) {
                mutable.value = mutable.value.copy(searching = false, error = "附近搜索暂时不可用，可重新进入或创建互联网房间")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) = post(token) {
                if (serviceInfo.serviceType != TYPE || !serviceInfo.serviceName.startsWith("Gulu-${gameKind.tag}-") ||
                    serviceInfo.serviceName.startsWith("Gulu-${gameKind.tag}-$selfId") || found.size >= 24 ||
                    !found.add(serviceInfo.serviceName)) return@post
                waiting.addLast(serviceInfo)
                resolveNext(token)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = post(token) {
                found.remove(serviceInfo.serviceName)
                resolveAttempts.remove(serviceInfo.serviceName)
                waiting.removeAll { it.serviceName == serviceInfo.serviceName }
                val id = serviceInfo.serviceName.removePrefix("Gulu-${gameKind.tag}-").substringBefore(" (")
                resolved.remove(id)
                publish()
            }
        }
        discovery = listener
        runCatching { manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { mutable.value = mutable.value.copy(searching = false, error = "请允许本地网络访问后再找附近伙伴") }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(token: Int) {
        if (resolving || waiting.isEmpty() || token != generation) return
        val service = waiting.removeFirst()
        resolving = true
        runCatching {
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = post(token) {
                    resolving = false
                    val attempts = (resolveAttempts[service.serviceName] ?: 0) + 1
                    resolveAttempts[service.serviceName] = attempts
                    // Older NSD implementations permit one resolver globally; a recently stopped
                    // room can still have its previous callback in flight. Retry finitely.
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempts <= 4 && found.contains(service.serviceName)) {
                        main.postDelayed({ if (token == generation && found.contains(service.serviceName)) {
                            waiting.addLast(service); resolveNext(token)
                        } }, 400)
                    } else found.remove(service.serviceName)
                    resolveNext(token)
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) = post(token) {
                    resolving = false
                    val attributes = serviceInfo.attributes
                    fun attr(key: String): String? = attributes[key]?.takeIf { it.size <= 64 }?.toString(Charsets.UTF_8)
                    val id = attr("id")
                    val addresses = if (Build.VERSION.SDK_INT >= 34) serviceInfo.hostAddresses else listOfNotNull(serviceInfo.host)
                    val address = addresses.firstOrNull { it is Inet4Address && it.isSiteLocalAddress &&
                        !it.isLoopbackAddress && !it.isAnyLocalAddress }
                    if (found.contains(service.serviceName) && id != null && id != selfId &&
                        id.matches(Regex("[a-f0-9]{12}")) && attr("version") == "1" && attr("kind") == gameKind.tag &&
                        serviceInfo.serviceName.matches(Regex("Gulu-${gameKind.tag}-$id(?: \\(\\d+\\))?")) &&
                        serviceInfo.port == gameKind.port && address is Inet4Address && address.isSiteLocalAddress &&
                        !address.isLoopbackAddress && !address.isAnyLocalAddress) {
                        val name = attr("name")?.filter { !it.isISOControl() }?.trim()?.take(20)?.ifEmpty { null }
                            ?: "阿噜的朋友"
                        resolved[id] = NearbyGameRoom(id, name, address.hostAddress ?: "", serviceInfo.port)
                        publish()
                    }
                    resolveNext(token)
                }
            })
        }.onFailure { resolving = false; found.remove(service.serviceName); resolveNext(token) }
    }

    fun stop() {
        generation++
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registration?.let { runCatching { manager.unregisterService(it) } }
        discovery = null; registration = null; resolving = false
        waiting.clear(); found.clear(); resolved.clear(); resolveAttempts.clear()
        mutable.value = NearbyRoomsState()
    }

    private fun post(token: Int, action: () -> Unit) { main.post { if (token == generation) action() } }
    private fun publish() { mutable.value = mutable.value.copy(rooms = resolved.values.sortedWith(compareBy({ it.name }, { it.id }))) }
    companion object { private const val TYPE = "_gulu-game._tcp." }
}
