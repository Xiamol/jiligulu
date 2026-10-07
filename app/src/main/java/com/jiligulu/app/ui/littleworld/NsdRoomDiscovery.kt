package com.jiligulu.app.ui.littleworld

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Build
import android.os.Looper
import java.net.Inet4Address
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NearbyGameKind(val tag: String, val port: Int) { XIANGQI("xq", 49761), GOMOKU("go", 49762) }
data class NearbyGameRoom(val id: String, val name: String, val address: String, val port: Int, val avatarId: String = "aru")
data class NearbyRoomsState(val rooms: List<NearbyGameRoom> = emptyList(), val searching: Boolean = false, val error: String? = null,
    val localId:String="", val diagnostic: String? = null) {
    val autoCandidate:NearbyGameRoom? get()=rooms.filter {it.id<localId && localId.isNotBlank()}.minByOrNull {it.id}
}

/** A short-lived foreground presence. No scans, multicast locks, or reconnect work survive stop(). */
class NsdRoomDiscovery(context: Context, private val gameKind: NearbyGameKind, private val playerName: String,
    private val avatarId: String = "aru") {
    private val appContext = context.applicationContext
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private var selfId = UUID.randomUUID().toString().replace("-", "").take(12)
    val localId:String get()=mutable.value.localId
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
    private var startupAttempts = 0
    private var retryTask: Runnable? = null

    fun start() {
        if (discovery != null || retryTask != null) return
        startupAttempts = 0
        selfId=UUID.randomUUID().toString().replace("-", "").take(12)
        startAttempt()
    }

    private fun startAttempt() {
        val token = ++generation
        mutable.value = NearbyRoomsState(searching = true,localId=selfId)
        val info = NsdServiceInfo().apply {
            serviceName = "Gulu-${gameKind.tag}-$selfId"
            serviceType = TYPE
            port = gameKind.port
            setAttribute("version", "4"); setAttribute("kind", gameKind.tag); setAttribute("id", selfId)
            setAttribute("name", playerName.filter { !it.isISOControl() }.trim().take(16).ifEmpty { "阿噜的朋友" })
            setAttribute("avatar", RoomRoundRules.avatar(avatarId))
        }
        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                val listener = this
                main.post { if (token != generation) runCatching { manager.unregisterService(listener) } }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = post(token) {
                retryStartup(token)
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = registrationListener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener) }
            .onFailure { retryStartup(token, it is SecurityException) }
        if (token != generation) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                val listener = this
                main.post { if (token != generation) runCatching { manager.stopServiceDiscovery(listener) } }
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = post(token) {
                retryStartup(token)
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
            .onFailure { retryStartup(token, it is SecurityException) }
    }

    /** Retry only startup failures, never scan ports or turn an isolated emulator NAT into a LAN. */
    private fun retryStartup(token: Int, permissionDenied: Boolean = false) {
        if (token != generation || retryTask != null) return
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registration?.let { runCatching { manager.unregisterService(it) } }
        discovery=null; registration=null; resolving=false
        waiting.clear(); found.clear(); resolved.clear(); resolveAttempts.clear()
        val nextToken=++generation
        if (permissionDenied || startupAttempts >= STARTUP_DELAYS.size) {
            val (hint, detail)=startupDiagnostic(permissionDenied)
            mutable.value=mutable.value.copy(searching=false,rooms=emptyList(),error=hint,diagnostic=detail)
            return
        }
        val wait=STARTUP_DELAYS[startupAttempts++]
        mutable.value=mutable.value.copy(searching=true,error=null,diagnostic=null,rooms=emptyList())
        retryTask=Runnable {
            if (nextToken==generation) { retryTask=null; startAttempt() }
        }.also { main.postDelayed(it,wait) }
    }

    private fun startupDiagnostic(permissionDenied: Boolean): Pair<String,String> {
        if (permissionDenied) return "请开启本地网络权限" to "在手机设置中允许叽里咕噜访问本地网络，再重新搜索。"
        val emulated=listOf(Build.FINGERPRINT,Build.MODEL,Build.HARDWARE).any { value ->
            listOf("generic","emulator","ranchu","goldfish","mumu","nemu").any { value.contains(it,true) }
        }
        if (emulated) return "模拟器需桥接到真实局域网" to
            "模拟器显示的虚拟 Wi-Fi 可能只是隔离的 NAT。请启用模拟器桥接网络，或用两台手机连接同一 Wi-Fi；无法桥接时可创建互联网房间。"
        val wifi=runCatching {
            val connectivity=appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        }.getOrNull()
        if (wifi==false) return "请先连接同一 Wi-Fi" to "附近对局需要双方处于同一局域网。手机流量请使用创建房间。"
        return "附近搜索暂未启动，点一下重试" to "已有限重试，仍未收到系统搜索启动回调。请检查本地网络权限或更换共同 Wi-Fi；也可使用互联网房间。"
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
                        id.matches(Regex("[a-f0-9]{12}")) && attr("version") == "4" && attr("kind") == gameKind.tag &&
                        serviceInfo.serviceName.matches(Regex("Gulu-${gameKind.tag}-$id(?: \\(\\d+\\))?")) &&
                        serviceInfo.port == gameKind.port && address is Inet4Address && address.isSiteLocalAddress &&
                        !address.isLoopbackAddress && !address.isAnyLocalAddress) {
                        val name = attr("name")?.filter { !it.isISOControl() }?.trim()?.take(20)?.ifEmpty { null }
                            ?: "阿噜的朋友"
                        resolved[id] = NearbyGameRoom(id, name, address.hostAddress ?: "", serviceInfo.port,RoomRoundRules.avatar(attr("avatar").orEmpty()))
                        publish()
                    }
                    resolveNext(token)
                }
            })
        }.onFailure { resolving = false; found.remove(service.serviceName); resolveNext(token) }
    }

    fun stop() {
        generation++
        retryTask?.let(main::removeCallbacks); retryTask=null; startupAttempts=0
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registration?.let { runCatching { manager.unregisterService(it) } }
        discovery = null; registration = null; resolving = false
        waiting.clear(); found.clear(); resolved.clear(); resolveAttempts.clear()
        mutable.value = NearbyRoomsState()
    }

    private fun post(token: Int, action: () -> Unit) { main.post { if (token == generation) action() } }
    private fun publish() { mutable.value = mutable.value.copy(rooms = resolved.values.sortedWith(compareBy({ it.name }, { it.id }))) }
    companion object {
        private const val TYPE = "_gulu-game._tcp."
        private val STARTUP_DELAYS=longArrayOf(400,1_000,2_000)
    }
}
