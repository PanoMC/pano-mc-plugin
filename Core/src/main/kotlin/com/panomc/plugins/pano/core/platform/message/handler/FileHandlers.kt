package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.files.FileAgent
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.FileRequestMessage

/**
 * The ten `FILE_*` requests, one handler class each (AGENT.md 2.4.4 / 2.4.17 C).
 *
 * They exist as separate classes for one reason: an inbound push is routed by the handler's own
 * name, so `FileListHandler` *is* the `FILE_LIST` route. They share one message shape and one
 * [FileAgent], which is where all the actual work and all the safety rules live - a handler that
 * did more than name an operation would be a handler that could get that operation's sandboxing
 * subtly different from its nine siblings.
 *
 * `PlatformMessageHandler` reads its message type off its direct superclass, so each of these
 * extends it directly rather than a shared base of their own.
 */
class FileListHandler(private val agent: FileAgent) : PlatformMessageHandler<FileRequestMessage>() {
    override suspend fun handle(response: FileRequestMessage) = agent.handle(getHandlerName(), response)
}

class FileReadHandler(private val agent: FileAgent) : PlatformMessageHandler<FileRequestMessage>() {
    override suspend fun handle(response: FileRequestMessage) = agent.handle(getHandlerName(), response)
}

class FileWriteHandler(private val agent: FileAgent) : PlatformMessageHandler<FileRequestMessage>() {
    override suspend fun handle(response: FileRequestMessage) = agent.handle(getHandlerName(), response)
}

class FileMkdirHandler(private val agent: FileAgent) : PlatformMessageHandler<FileRequestMessage>() {
    override suspend fun handle(response: FileRequestMessage) = agent.handle(getHandlerName(), response)
}

class FileDeleteHandler(private val agent: FileAgent) : PlatformMessageHandler<FileRequestMessage>() {
    override suspend fun handle(response: FileRequestMessage) = agent.handle(getHandlerName(), response)
}

class FileRenameHandler(private val agent: FileAgent) : PlatformMessageHandler<FileRequestMessage>() {
    override suspend fun handle(response: FileRequestMessage) = agent.handle(getHandlerName(), response)
}

class FileArchiveHandler(private val agent: FileAgent) : PlatformMessageHandler<FileRequestMessage>() {
    override suspend fun handle(response: FileRequestMessage) = agent.handle(getHandlerName(), response)
}

class FileUnarchiveHandler(private val agent: FileAgent) : PlatformMessageHandler<FileRequestMessage>() {
    override suspend fun handle(response: FileRequestMessage) = agent.handle(getHandlerName(), response)
}

class FileChmodHandler(private val agent: FileAgent) : PlatformMessageHandler<FileRequestMessage>() {
    override suspend fun handle(response: FileRequestMessage) = agent.handle(getHandlerName(), response)
}

class FileHashesHandler(private val agent: FileAgent) : PlatformMessageHandler<FileRequestMessage>() {
    override suspend fun handle(response: FileRequestMessage) = agent.handle(getHandlerName(), response)
}
