package me.zhanghai.android.files.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.isFinished
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.SkyFilesLogger

class EditSmbServerViewModel : ViewModel() {
    private val _connectState = MutableStateFlow<ActionState<SmbServer, Unit>>(ActionState.Ready())
    val connectState = _connectState.asStateFlow()

    fun connect(server: SmbServer) {
        viewModelScope.launch {
            check(_connectState.value.isReady)
            _connectState.value = ActionState.Running(server)
            _connectState.value = try {
                runInterruptible(Dispatchers.IO) {
                    SmbServerAuthenticator.addTransientServer(server)
                    try {
                        val path = server.path
                        try {
                            path.fileSystem.use {
                                path.newDirectoryStream().use { stream ->
                                    stream.toList()
                                }
                            }
                            ActionState.Success(server, Unit)
                        } catch (e: Exception) {
                            var isEofOrTransportOrAccess = false
                            var curr: Throwable? = e
                            while (curr != null) {
                                val className = curr.javaClass.name
                                val msg = curr.message.orEmpty()
                                if (curr is java.io.EOFException || className.contains("EOFException") 
                                    || className.contains("TransportException")
                                    || msg.contains("STATUS_ACCESS_DENIED")
                                    || msg.contains("STATUS_NOT_SUPPORTED")) {
                                    isEofOrTransportOrAccess = true
                                    break
                                }
                                curr = curr.cause
                            }

                            if (server.relativePath.isEmpty() && isEofOrTransportOrAccess) {
                                SkyFilesLogger.i("SkyFiles", "Root share enumeration failed. Starting direct-share discovery.")
                                val fallbackShares = listOf(
                                    "USB_Storage", "Volume1", "sda1", "share", "public",
                                    "USB1", "USB2", "Storage", "Volume", "disk1", "HDD"
                                )
                                var successfulShare: String? = null
                                for (shareName in fallbackShares) {
                                    SkyFilesLogger.i("SkyFiles", "TRY SHARE: $shareName")
                                    try {
                                        val probeServer = SmbServer(
                                            server.id,
                                            server.customName,
                                            server.authority,
                                            server.password,
                                            shareName
                                        )
                                        val probePath = probeServer.path
                                        probePath.fileSystem.use {
                                            probePath.newDirectoryStream().use { stream ->
                                                stream.toList()
                                            }
                                        }
                                        SkyFilesLogger.i("SkyFiles", "SUCCESS")
                                        successfulShare = shareName
                                        break
                                    } catch (ex: Exception) {
                                        SkyFilesLogger.i("SkyFiles", "FAILED")
                                    }
                                }
                                if (successfulShare != null) {
                                    SkyFilesLogger.i("SkyFiles", "AUTO CONNECT:\nsmb://${server.authority.host}/$successfulShare")
                                    val newServer = SmbServer(
                                        server.id,
                                        server.customName,
                                        server.authority,
                                        server.password,
                                        successfulShare
                                    )
                                    ActionState.Success(newServer, Unit)
                                } else {
                                    throw e
                                }
                            } else {
                                throw e
                            }
                        }
                    } finally {
                        SmbServerAuthenticator.removeTransientServer(server)
                    }
                }
            } catch (e: Exception) {
                ActionState.Error(server, e)
            }
        }
    }

    fun finishConnecting() {
        viewModelScope.launch {
            check(_connectState.value.isFinished)
            _connectState.value = ActionState.Ready()
        }
    }
}
