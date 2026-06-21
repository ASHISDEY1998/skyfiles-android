/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileIdFullDirectoryInformation
import com.hierynomus.msfscc.fileinformation.FileSettableInformation
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CompletionFilter
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.messages.SMB2ChangeNotifyResponse
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.ProgressListener
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.Directory
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.PipeShare
import com.hierynomus.smbj.share.PrinterShare
import com.hierynomus.smbj.share.Share
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java8.nio.channels.SeekableByteChannel
import jcifs.context.SingletonContext
import me.zhanghai.android.files.provider.common.CloseableIterator
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.util.closeSafe
import me.zhanghai.android.files.util.enumSetOf
import me.zhanghai.android.files.util.hasBits
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.UnknownHostException
import android.util.Log
import me.zhanghai.android.files.util.SkyFilesLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Future

object Client {
    @Volatile
    lateinit var authenticator: Authenticator

    private val client = SMBClient()

    private val sessions = mutableMapOf<Authority, Session>()

    private val directoryFileInformationCache =
        Collections.synchronizedMap(WeakHashMap<Path, FileInformation>())

    @Throws(ClientException::class)
    fun openByteChannel(
        path: Path,
        desiredAccess: Set<AccessMask>,
        fileAttributes: Set<FileAttributes>,
        shareAccess: Set<SMB2ShareAccess>,
        createDisposition: SMB2CreateDisposition,
        createOptions: Set<SMB2CreateOptions>,
        isAppend: Boolean
    ): SeekableByteChannel {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val file = try {
            share.openFile(
                sharePath.path, desiredAccess, fileAttributes, shareAccess, createDisposition,
                createOptions
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        return FileByteChannel(file, isAppend)
    }

    @Throws(ClientException::class)
    fun openDirectoryIterator(path: Path): CloseableIterator<Path> {
        val session = getSession(path.authority)
        val sharePath = path.sharePath
        if (sharePath == null) {
            SmbDiagnosticsTracker.shareEnumeration = "PENDING"
            val transport = try {
                SMBTransportFactories.SRVSVC.getTransport(session)
            } catch (e: Exception) {
                SkyFilesLogger.e("SkyFiles", "SHARE ENUMERATION FAILED")
                logExceptionChain("SHARE ENUMERATION FAILED DETAILS", e)
                SmbDiagnosticsTracker.shareEnumeration = "FAIL"
                SmbDiagnosticsTracker.logSummary()
                throw ClientException(e)
            }
            val serverService = ServerService(transport)
            val netShareInfos = try {
                serverService.shares1
            } catch (e: Exception) {
                SkyFilesLogger.e("SkyFiles", "SHARE ENUMERATION FAILED")
                logExceptionChain("SHARE ENUMERATION FAILED DETAILS", e)
                SmbDiagnosticsTracker.shareEnumeration = "FAIL"
                SmbDiagnosticsTracker.logSummary()
                throw ClientException(e)
            }
            val sharePaths = netShareInfos.mapNotNull {
                if (!(it.type.hasBits(ShareTypes.STYPE_PRINTQ.value)
                        || it.type.hasBits(ShareTypes.STYPE_DEVICE.value)
                        || it.type.hasBits(ShareTypes.STYPE_IPC.value))) {
                    path.resolve(it.netName)
                } else {
                    null
                }
            }
            SmbDiagnosticsTracker.shareEnumeration = "PASS"
            SmbDiagnosticsTracker.logSummary()
            return object : CloseableIterator<Path>, Iterator<Path> by sharePaths.iterator() {
                override fun close() {}
            }
        } else {
            val share = getDiskShare(session, sharePath.name)
            val directory = try {
                share.openDirectory(
                    sharePath.path, enumSetOf(
                        AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES,
                        AccessMask.FILE_READ_EA
                    ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null
                )
            } catch (e: SMBRuntimeException) {
                throw ClientException(e)
            }
            val directoryIterator = directory.iterator(FileIdFullDirectoryInformation::class.java)
                .asSequence()
                .filter { fileInformation ->
                    !fileInformation.fileName.let { it == "." || it == ".." }
                }
                .map { fileInformation ->
                    path.resolve(fileInformation.fileName).also {
                        directoryFileInformationCache[it] = fileInformation.toFileInformation()
                    }
                }
                .iterator()
            return object : CloseableIterator<Path>, Iterator<Path> by directoryIterator,
                Closeable by directory {}
        }
    }

    // @see https://gitlab.com/samba-team/devel/samba/-/blob/master/source3/libsmb/cli_smb2_fnum.c
    // cli_smb2_mkdir_send
    @Throws(ClientException::class)
    fun createDirectory(path: Path, fileAttributes: Set<FileAttributes>? = null) {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val directory = try {
            share.openDirectory(
                sharePath.path,
                enumSetOf(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_EA),
                enumSetOf(FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                    .apply { fileAttributes?.let { addAll(it) } }, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_CREATE,
                enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        try {
            directory.close()
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
    }

    // @see https://gitlab.com/samba-team/devel/samba/-/blob/master/source3/libsmb/clisymlink.c
    //      cli_symlink_send
    @Throws(ClientException::class)
    fun createSymbolicLink(
        path: Path,
        reparseData: SymbolicLinkReparseData,
        fileAttributes: Set<FileAttributes>? = null
    ) {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val diskEntry = try {
            share.open(
                sharePath.path, enumSetOf(
                    AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_WRITE_ATTRIBUTES,
                    AccessMask.FILE_READ_EA, AccessMask.FILE_WRITE_EA, AccessMask.DELETE,
                    AccessMask.SYNCHRONIZE
                ), enumSetOf<FileAttributes>().apply {
                    fileAttributes?.let { addAll(it) }
                    this -= FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT
                    if (isEmpty()) {
                        this += FileAttributes.FILE_ATTRIBUTE_NORMAL
                    }
                }, null, SMB2CreateDisposition.FILE_CREATE, enumSetOf(
                    SMB2CreateOptions.FILE_NON_DIRECTORY_FILE,
                    SMB2CreateOptions.FILE_OPEN_REPARSE_POINT
                )
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        try {
            diskEntry.use {
                var successful = false
                try {
                    it.setSymbolicLinkReparseData(reparseData)
                    successful = true
                } finally {
                    if (!successful) {
                        try {
                            it.deleteOnClose()
                        } catch (e: SMBRuntimeException) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
    }

    // @see https://gitlab.com/samba-team/devel/samba/-/blob/master/source3/libsmb/clifile.c
    //      cli_smb2_hardlink_send
    @Throws(ClientException::class)
    fun createLink(path: Path, link: Path, openReparsePoint: Boolean) {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val linkSharePath = link.sharePath
            ?: throw ClientException("$link does not have a share path")
        if (link.authority != path.authority || linkSharePath.name != sharePath.name) {
            throw ClientException(
                SMBApiException(
                    NtStatus.STATUS_NOT_SAME_DEVICE.value, SMB2MessageCommandCode.SMB2_SET_INFO,
                    null
                )
            )
        }
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val diskEntry = try {
            share.open(
                sharePath.path,
                enumSetOf(AccessMask.FILE_WRITE_ATTRIBUTES, AccessMask.FILE_WRITE_EA),
                null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
                // CreateHardLink doesn't work for directories.
                enumSetOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE).apply {
                    if (openReparsePoint) {
                        this += SMB2CreateOptions.FILE_OPEN_REPARSE_POINT
                    }
                }
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        try {
            diskEntry.use { it.createHardlink(linkSharePath.path, false) }
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
    }

    @Throws(ClientException::class)
    fun delete(path: Path) {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val diskEntry = try {
            share.open(
                sharePath.path, enumSetOf(AccessMask.DELETE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, enumSetOf(
                    SMB2CreateOptions.FILE_DELETE_ON_CLOSE,
                    SMB2CreateOptions.FILE_OPEN_REPARSE_POINT
                )
            )
        } catch (e: SMBRuntimeException) {
            if (e is SMBApiException && e.status == NtStatus.STATUS_DELETE_PENDING) {
                return
            }
            throw ClientException(e)
        }
        try {
            diskEntry.close()
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        directoryFileInformationCache -= path
    }

    // @see https://gitlab.com/samba-team/devel/samba/-/blob/master/source3/libsmb/clisymlink.c
    //      cli_readlink_send
    @Throws(ClientException::class)
    fun readSymbolicLink(path: Path): SymbolicLinkReparseData {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val diskEntry = try {
            share.open(
                sharePath.path,
                enumSetOf(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_EA), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
                enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        return try {
            diskEntry.use { it.getSymbolicLinkReparseData() }
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
    }

    // @see https://docs.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/cd0162e4-7650-4293-8a2a-d696923203ef
    @Throws(ClientException::class)
    fun copyFile(
        source: Path,
        target: Path,
        copyAttributes: Boolean,
        openReparsePoint: Boolean,
        intervalMillis: Long,
        listener: ((Long) -> Unit)?
    ) {
        val sourceSharePath = source.sharePath
            ?: throw ClientException("$source does not have a share path")
        val targetSharePath = target.sharePath
            ?: throw ClientException("$target does not have a share path")
        val sourceSession = getSession(source.authority)
        val sourceShare = getDiskShare(sourceSession, sourceSharePath.name)
        val targetSession = getSession(target.authority)
        val targetShare = getDiskShare(targetSession, targetSharePath.name)
        val sourceFile = try {
            sourceShare.openFile(
                sourceSharePath.path, enumSetOf(
                    AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES,
                    AccessMask.FILE_READ_EA
                ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
                if (openReparsePoint) {
                    enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
                } else {
                    null
                }
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        try {
            sourceFile.use {
                val attributesToCopy = if (copyAttributes) {
                    val sourceAttributes = try {
                        sourceFile.getFileInformation(FileBasicInformation::class.java)
                    } catch (e: SMBRuntimeException) {
                        throw ClientException(e)
                    }.fileAttributes
                    EnumWithValue.EnumUtils.toEnumSet(sourceAttributes, FileAttributes::class.java)
                } else {
                    enumSetOf(FileAttributes.FILE_ATTRIBUTE_NORMAL)
                }
                val targetFile = try {
                    targetShare.openFile(
                        targetSharePath.path, enumSetOf(
                        AccessMask.FILE_WRITE_DATA, AccessMask.FILE_WRITE_ATTRIBUTES,
                        AccessMask.FILE_WRITE_EA, AccessMask.DELETE
                    ), attributesToCopy, SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_CREATE,
                        enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
                    )
                } catch (e: SMBRuntimeException) {
                    throw ClientException(e)
                }
                targetFile.use {
                    var successful = false
                    try {
                        if (sourceSession == targetSession) {
                            val length = try {
                                sourceFile.getFileInformation(FileStandardInformation::class.java)
                            } catch (e: SMBRuntimeException) {
                                throw ClientException(e)
                            }.endOfFile
                            val progressListener = listener?.let {
                                var lastCopiedSize = 0L
                                ProgressListener { copiedSize, _ ->
                                    it(copiedSize - lastCopiedSize)
                                    lastCopiedSize = copiedSize
                                }
                            }
                            try {
                                sourceFile.serverCopy(0, targetFile, 0, length, progressListener)
                            } catch (e: SMBRuntimeException) {
                                throw ClientException(e)
                            }
                        } else {
                            val sourceInputStream = FileByteChannel(sourceFile, false)
                                .newInputStream()
                            val targetOutputStream = FileByteChannel(targetFile, false)
                                .newOutputStream()
                            sourceInputStream.copyTo(targetOutputStream, intervalMillis, listener)
                        }
                        successful = true
                    } finally {
                        if (!successful) {
                            try {
                                targetFile.deleteOnClose()
                            } catch (e: SMBRuntimeException) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
    }

    // @see https://gitlab.com/samba-team/devel/samba/-/blob/master/source3/libsmb/cli_smb2_fnum.c
    //      cli_smb2_rename
    @Throws(ClientException::class)
    fun rename(path: Path, newPath: Path) {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val newSharePath = newPath.sharePath
            ?: throw ClientException("$newPath does not have a share path")
        if (newPath.authority != path.authority || newSharePath.name != sharePath.name) {
            throw ClientException(
                SMBApiException(
                    NtStatus.STATUS_NOT_SAME_DEVICE.value, SMB2MessageCommandCode.SMB2_SET_INFO,
                    null
                )
            )
        }
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val diskEntry = try {
            share.open(
                sharePath.path, enumSetOf(AccessMask.DELETE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        try {
            diskEntry.use { it.rename(newSharePath.path, true) }
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        directoryFileInformationCache -= path
        directoryFileInformationCache -= newPath
    }

    @Throws(ClientException::class)
    fun getPathInformation(path: Path, openReparsePoint: Boolean): PathInformation {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        if (sharePath.path.isEmpty()) {
            return when (val share = getShare(session, sharePath.name)) {
                is DiskShare -> {
                    val shareInfo = try {
                        share.shareInformation
                    } catch (e: SMBRuntimeException) {
                        e.printStackTrace()
                        null
                    }
                    ShareInformation(ShareType.DISK, shareInfo)
                    // Don't close the disk share, because it might still be in use, or might become
                    // in use shortly. All shares are automatically closed when the session is
                    // closed anyway.
                }
                is PipeShare -> ShareInformation(ShareType.PIPE, null).also { share.closeSafe() }
                is PrinterShare -> ShareInformation(ShareType.PRINTER, null)
                    .also { share.closeSafe() }
                else -> throw AssertionError(share)
            }
        } else {
            synchronized(directoryFileInformationCache) {
                directoryFileInformationCache[path]?.let {
                    if (openReparsePoint || !it.fileAttributes.hasBits(
                            FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value
                        )) {
                        return it.also { directoryFileInformationCache -= path }
                    }
                }
            }
            val share = getDiskShare(session, sharePath.name)
            val diskEntry = try {
                share.open(
                    sharePath.path,
                    enumSetOf(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_EA),
                    null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
                    if (openReparsePoint) {
                        enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
                    } else {
                        null
                    }
                )
            } catch (e: SMBRuntimeException) {
                throw ClientException(e)
            }
            val fileAllInformation = try {
                diskEntry.use { it.fileInformation }
            } catch (e: SMBRuntimeException) {
                throw ClientException(e)
            }
            return fileAllInformation.toFileInformation()
        }
    }

    @Throws(ClientException::class)
    fun setFileInformation(
        path: Path,
        openReparsePoint: Boolean,
        fileInformation: FileSettableInformation
    ) {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val diskEntry = try {
            share.open(
                sharePath.path,
                enumSetOf(AccessMask.FILE_WRITE_ATTRIBUTES, AccessMask.FILE_WRITE_EA),
                null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
                if (openReparsePoint) {
                    enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
                } else {
                    null
                }
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        try {
            diskEntry.use { it.setFileInformation(fileInformation) }
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        directoryFileInformationCache -= path
    }

    @Throws(ClientException::class)
    fun checkAccess(path: Path, desiredAccess: Set<AccessMask>, openReparsePoint: Boolean) {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val diskEntry = try {
            share.open(
                sharePath.path, desiredAccess, null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, if (openReparsePoint) {
                    enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
                } else {
                    null
                }
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        try {
            diskEntry.close()
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
    }

    // @see https://docs.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/05869c32-39f0-4726-afc9-671b76ae5ca7
    @Throws(ClientException::class)
    fun openDirectoryForChangeNotification(path: Path): Directory {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        return try {
            share.openDirectory(
                sharePath.path, enumSetOf(AccessMask.FILE_LIST_DIRECTORY), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
    }

    @Throws(ClientException::class)
    fun requestDirectoryChangeNotification(
        directory: Directory,
        completionFilter: Set<SMB2CompletionFilter>
    ): Future<SMB2ChangeNotifyResponse> {
        return try {
            directory.watchAsync(completionFilter, false)
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
    }

    object SmbDiagnosticsTracker {
        var host: String = ""
        var dns: String = "N/A"
        var tcp: String = "N/A"
        var negotiation: String = "N/A"
        var authentication: String = "N/A"
        var shareEnumeration: String = "N/A"
        var shareOpen: String = "N/A"
        
        var failureStage: String = "NONE"
        var exceptionDetails: String = "NONE"
        
        var smbjVersion: String = "0.11.5"
        var negotiatedDialect: String = "N/A"
        var signingRequired: String = "N/A"
        var signingEnabled: String = "N/A"
        var serverName: String = "N/A"
        var serverOS: String = "UNKNOWN"
        var serverAppearsSmb1Only: String = "false"

        fun reset(hostName: String) {
            host = hostName
            dns = "N/A"
            tcp = "N/A"
            negotiation = "N/A"
            authentication = "N/A"
            shareEnumeration = "N/A"
            shareOpen = "N/A"
            failureStage = "NONE"
            exceptionDetails = "NONE"
            negotiatedDialect = "N/A"
            signingRequired = "N/A"
            signingEnabled = "N/A"
            serverName = "N/A"
            serverOS = "UNKNOWN"
            serverAppearsSmb1Only = "false"
        }

        fun logSummary() {
            if (failureStage == "NONE") {
                if (dns == "FAIL") failureStage = "DNS"
                else if (tcp == "FAIL") failureStage = "TCP"
                else if (negotiation == "FAIL") failureStage = "NEGOTIATION"
                else if (authentication == "FAIL") failureStage = "AUTH"
                else if (shareEnumeration == "FAIL") failureStage = "ENUMERATION"
                else if (shareOpen == "FAIL") failureStage = "SHARE_OPEN"
            }
            
            val summary = """
                ===== SMB DIAGNOSTICS =====
                Server=$host
                IP=${if (dns == "PASS") "RESOLVED" else "N/A"}
                DNS=$dns
                TCP=$tcp
                NEGOTIATION=$negotiation
                AUTH=$authentication
                ENUMERATION=$shareEnumeration
                SHARE_OPEN=$shareOpen
                SMBJ_VERSION=$smbjVersion
                NEGOTIATED_DIALECT=$negotiatedDialect
                SUPPORTED_DIALECTS=SMB_2_0_2, SMB_2_1, SMB_3_0, SMB_3_0_2, SMB_3_1_1
                SERVER_NAME=$serverName
                SERVER_OS=$serverOS
                SIGNING_REQUIRED=$signingRequired
                SIGNING_ENABLED=$signingEnabled
                SERVER_APPEARS_SMB1_ONLY=$serverAppearsSmb1Only
                FIRST_FAILURE_STAGE=$failureStage
                FULL_EXCEPTION_CHAIN=$exceptionDetails
                ==========================
            """.trimIndent()
            SkyFilesLogger.i("SkyFiles", summary)
        }
    }

    fun logExceptionChain(msg: String, throwable: Throwable) {
        val detailMsg = buildString {
            append("$msg\n")
            append("Throwable class: ${throwable.javaClass.name}\n")
            append("Throwable message: ${throwable.message}\n")
            var cause: Throwable? = throwable.cause
            var depth = 1
            while (cause != null) {
                append("Throwable cause $depth: ${cause.javaClass.name} - ${cause.message}\n")
                cause = cause.cause
                depth++
            }
            append("Stack Trace:\n${Log.getStackTraceString(throwable)}")
        }
        SkyFilesLogger.e("SkyFiles", detailMsg)
    }

    @Throws(ClientException::class)
    private fun getSession(authority: Authority): Session {
        synchronized(sessions) {
            var session = sessions[authority]
            if (session != null) {
                val connection = session.connection
                if (connection.isConnected) {
                    return session
                } else {
                    session.closeSafe()
                    connection.closeSafe()
                    sessions -= authority
                }
            }
            val password = authenticator.getPassword(authority)
                ?: throw ClientException("No password found for $authority")

            val authType = when {
                AuthenticationContext.guest().let {
                    authority.username == it.username && authority.domain == it.domain
                            && password == it.password.concatToString()
                } -> "GUEST"
                AuthenticationContext.anonymous().let {
                    authority.username == it.username && authority.domain == it.domain
                            && password == it.password.concatToString()
                } -> "ANONYMOUS"
                else -> "PASSWORD"
            }

            SmbDiagnosticsTracker.reset(authority.host)
            try {
                // Phase 7: Automatic TP-Link Detection
                val hostUpper = authority.host.uppercase()
                val autoCompat = hostUpper.contains("NAS") || hostUpper.contains("TP-LINK") || hostUpper.contains("ARCHER")

                val hostAddress = resolveHostName(authority.host)
                
                // Try connection profiles
                var connection: com.hierynomus.smbj.connection.Connection? = null
                var activeClient = client
                var lastEx: Exception? = null
                var successfulProfile = "NONE"

                val profilesToTry = if (autoCompat) {
                    listOf("PROFILE_B", "PROFILE_C", "PROFILE_A")
                } else {
                    listOf("PROFILE_A", "PROFILE_B", "PROFILE_C")
                }

                for (profile in profilesToTry) {
                    try {
                        val config = when (profile) {
                            "PROFILE_A" -> com.hierynomus.smbj.SmbConfig.builder().build()
                            "PROFILE_B" -> com.hierynomus.smbj.SmbConfig.builder()
                                .withDialects(com.hierynomus.mssmb2.SMB2Dialect.SMB_2_1, com.hierynomus.mssmb2.SMB2Dialect.SMB_2_0_2)
                                .withSigningRequired(false)
                                .withTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .withSoTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            "PROFILE_C" -> com.hierynomus.smbj.SmbConfig.builder()
                                .withDialects(com.hierynomus.mssmb2.SMB2Dialect.SMB_2_0_2)
                                .withSigningRequired(false)
                                .withTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .withSoTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            else -> com.hierynomus.smbj.SmbConfig.builder().build()
                        }
                        val probeClient = com.hierynomus.smbj.SMBClient(config)
                        val conn = probeClient.connect(hostAddress, authority.port)
                        SmbDiagnosticsTracker.tcp = "PASS"
                        connection = conn
                        activeClient = probeClient
                        successfulProfile = profile
                        break
                    } catch (e: Exception) {
                        lastEx = e
                        
                        var isEofOrTransport = false
                        var curr: Throwable? = e
                        while (curr != null) {
                            val className = curr.javaClass.name
                            if (curr is java.io.EOFException || className.contains("EOFException") || className.contains("TransportException")) {
                                isEofOrTransport = true
                                break
                            }
                            curr = curr.cause
                        }
                        if (isEofOrTransport) {
                            SmbDiagnosticsTracker.tcp = "PASS"
                            SmbDiagnosticsTracker.negotiation = "FAIL"
                            if (e.message?.contains("SMB1") == true || e.message?.contains("not supported") == true || isEofOrTransport) {
                                SmbDiagnosticsTracker.serverAppearsSmb1Only = "true"
                            }
                        } else {
                            SmbDiagnosticsTracker.tcp = "FAIL"
                        }
                    }
                }

                if (connection == null) {
                    val ex = lastEx ?: IOException("All SMB connection profiles failed")
                    SkyFilesLogger.e("SkyFiles", "ALL SMB PROFILES FAILED")
                    SmbDiagnosticsTracker.failureStage = if (SmbDiagnosticsTracker.tcp == "FAIL") "TCP" else "NEGOTIATION"
                    SmbDiagnosticsTracker.exceptionDetails = "${ex.javaClass.name}: ${ex.message}"
                    SmbDiagnosticsTracker.logSummary()
                    throw ClientException(ex)
                }

                SmbDiagnosticsTracker.negotiation = "PASS"
                val dialect = try {
                    val d = connection.connectionContext.negotiatedProtocol.dialect
                    SmbDiagnosticsTracker.negotiatedDialect = d.toString()
                    d
                } catch (e: Exception) {
                    throw ClientException(e)
                }

                val authenticationContext =
                    AuthenticationContext(authority.username, password.toCharArray(), authority.domain)
                
                SmbDiagnosticsTracker.authentication = "PENDING"
                session = try {
                    val sess = connection.authenticate(authenticationContext)
                    
                    val context = connection.connectionContext
                    val serverName = context.serverName ?: "UNKNOWN"
                    val serverOS = "UNKNOWN"
                    
                    val secMode = try {
                        val field = context.javaClass.getDeclaredField("serverSecurityMode")
                        field.isAccessible = true
                        field.getInt(context)
                    } catch (e: Exception) {
                        0
                    }
                    
                    val signingEnabled = (secMode and 0x01) != 0
                    val signingRequired = (secMode and 0x02) != 0
                    
                    SmbDiagnosticsTracker.serverName = serverName
                    SmbDiagnosticsTracker.signingRequired = signingRequired.toString()
                    SmbDiagnosticsTracker.signingEnabled = signingEnabled.toString()
                    SmbDiagnosticsTracker.authentication = "PASS"
                    sess
                } catch (e: SMBRuntimeException) {
                    val statusString = (e as? SMBApiException)?.status?.name ?: e.message ?: "UNKNOWN"
                    SkyFilesLogger.e("SkyFiles", "AUTH FAILED\nstatus code=$statusString\nexception class=${e.javaClass.name}\nmessage=${e.message}")
                    logExceptionChain("AUTH FAILED DETAILS", e)
                    SmbDiagnosticsTracker.authentication = "FAIL"
                    SmbDiagnosticsTracker.failureStage = "AUTH"
                    SmbDiagnosticsTracker.exceptionDetails = "${e.javaClass.name}: ${e.message}"
                    SmbDiagnosticsTracker.logSummary()
                    connection.closeSafe()
                    throw ClientException(e)
                }!!
                sessions[authority] = session
                return session
            } catch (e: Throwable) {
                throw e
            }
        }
    }

    @Throws(ClientException::class)
    private fun resolveHostName(hostName: String): String {
        SmbDiagnosticsTracker.dns = "PENDING"
        val nameServiceClient = SingletonContext.getInstance().nameServiceClient
        val addresses = try {
            nameServiceClient.getAllByName(hostName, false).mapNotNull { it.toInetAddress() }
        } catch (e: UnknownHostException) {
            SkyFilesLogger.e("SkyFiles", "DNS FAILED\nexception=${e.javaClass.name}: ${e.message}")
            SmbDiagnosticsTracker.dns = "FAIL"
            SmbDiagnosticsTracker.logSummary()
            throw ClientException(e)
        }
        if (addresses.isEmpty()) {
            val ex = UnknownHostException(hostName)
            SkyFilesLogger.e("SkyFiles", "DNS FAILED\nexception=${ex.javaClass.name}: ${ex.message}")
            SmbDiagnosticsTracker.dns = "FAIL"
            SmbDiagnosticsTracker.logSummary()
            throw ClientException(ex)
        }
        val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
        val resolved = address.hostAddress!!
        SmbDiagnosticsTracker.dns = "PASS"
        return resolved
    }

    @Throws(ClientException::class)
    private fun getShare(session: Session, shareName: String): Share {
        SmbDiagnosticsTracker.shareOpen = "PENDING"
        return try {
            val share = session.connectShare(shareName)
            SmbDiagnosticsTracker.shareOpen = "PASS"
            SmbDiagnosticsTracker.logSummary()
            share
        } catch (e: SMBRuntimeException) {
            SkyFilesLogger.e("SkyFiles", "SHARE OPEN FAILED")
            logExceptionChain("SHARE OPEN FAILED DETAILS", e)
            SmbDiagnosticsTracker.shareOpen = "FAIL"
            SmbDiagnosticsTracker.logSummary()
            throw ClientException(e)
        }
    }

    @Throws(ClientException::class)
    private fun getDiskShare(session: Session, shareName: String): DiskShare =
        getShare(session, shareName) as? DiskShare
            ?: throw ClientException("$shareName is not a DiskShare")

    interface Path {
        val authority: Authority
        val sharePath: SharePath?
        fun resolve(other: String): Path

        data class SharePath(
            val name: String,
            val path: String
        )
    }
}
