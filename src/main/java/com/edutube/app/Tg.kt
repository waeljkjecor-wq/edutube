package com.edutube.app

import android.content.Context
import android.os.Build
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateClosing
import dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailAddress
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitOtherDeviceConfirmation
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitRegistration
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypePrivate
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputDocument
import dev.g000sha256.tdl.dto.InputFileLocal
import dev.g000sha256.tdl.dto.InputMessageContent
import dev.g000sha256.tdl.dto.InputMessageDocument
import dev.g000sha256.tdl.dto.InputMessagePhoto
import dev.g000sha256.tdl.dto.InputMessageText
import dev.g000sha256.tdl.dto.InputMessageVideo
import dev.g000sha256.tdl.dto.InputPhoto
import dev.g000sha256.tdl.dto.InputVideo
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.Proxy
import dev.g000sha256.tdl.dto.ProxyTypeMtproto
import dev.g000sha256.tdl.dto.ProxyTypeSocks5
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.MessageAudio
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessagePhoto
import dev.g000sha256.tdl.dto.MessageText
import dev.g000sha256.tdl.dto.MessageVideo
import dev.g000sha256.tdl.dto.MessageVoiceNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File as JFile

sealed class TgState {
    object Off : TgState()
    object Loading : TgState()
    object WaitPhone : TgState()
    data class WaitCode(val phone: String) : TgState()
    data class WaitPassword(val hint: String) : TgState()
    object Ready : TgState()
    data class Error(val message: String) : TgState()
}

class TgError(message: String) : Exception(message)

fun <T> TdlResult<T>.orNull(): T? = when (this) {
    is TdlResult.Success -> result
    is TdlResult.Failure -> null
}

fun <T> TdlResult<T>.ok(): T = when (this) {
    is TdlResult.Success -> result
    is TdlResult.Failure -> throw TgError(message)
}

/** معلومات مختصرة عن مجموعة أو قناة. */
data class TgChatInfo(
    val id: Long,
    val title: String,
    val kind: String,
    val last: String,
    val lastDate: Int,
    val unread: Int
)

enum class TgKind { TEXT, PHOTO, DOCUMENT, VIDEO, VOICE, AUDIO, OTHER }

/** رسالة مبسّطة للعرض. */
data class TgItem(
    val id: Long,
    val date: Int,
    val kind: TgKind,
    val text: String,
    val fileId: Int,
    val fileName: String,
    val size: Long,
    val thumbFileId: Int,
    val duration: Int,
    val isPdf: Boolean,
    val outgoing: Boolean = false
)

/** اتصال واحد بتيليجرام لكل التطبيق. */
object Tg {
    private var client: TdlClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<TgState>(TgState.Off)
    val state: StateFlow<TgState> = _state
    private val _newMessages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val newMessages: SharedFlow<Message> = _newMessages
    private var app: Context? = null
    @Volatile
    private var paramsSent = false

    fun isConfigured(ctx: Context): Boolean {
        val s = Store(ctx)
        return s.tgApiId > 0 && s.tgApiHash.isNotBlank()
    }

    val isReady: Boolean get() = _state.value is TgState.Ready

    @Synchronized
    fun start(ctx: Context) {
        val appCtx = ctx.applicationContext
        app = appCtx
        if (client != null) return
        if (!isConfigured(appCtx)) {
            _state.value = TgState.Off
            return
        }
        _state.value = TgState.Loading
        paramsSent = false
        val c = TdlClient.create()
        client = c
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            c.authorizationStateUpdates.collect { onAuth(c, it.authorizationState) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            c.newMessageUpdates.collect { _newMessages.emit(it.message) }
        }
        scope.launch {
            c.setLogVerbosityLevel(newVerbosityLevel = 1)
            c.getAuthorizationState().orNull()?.let { onAuth(c, it) }
        }
    }

    /** يعيد الاتصال (مثلاً بعد تغيير api_id). */
    fun restart(ctx: Context) {
        app = ctx.applicationContext
        val c = synchronized(this) { client }
        if (c == null) {
            start(ctx)
        } else {
            scope.launch { c.close() }
        }
    }

    private fun onAuth(c: TdlClient, s: AuthorizationState) {
        when (s) {
            is AuthorizationStateWaitTdlibParameters -> scope.launch { sendParams(c) }
            is AuthorizationStateWaitPhoneNumber -> _state.value = TgState.WaitPhone
            is AuthorizationStateWaitCode -> _state.value = TgState.WaitCode(s.codeInfo.phoneNumber)
            is AuthorizationStateWaitPassword -> _state.value = TgState.WaitPassword(s.passwordHint)
            is AuthorizationStateReady -> {
                _state.value = TgState.Ready
                scope.launch { c.loadChats(chatList = ChatListMain(), limit = 100) }
            }
            is AuthorizationStateWaitRegistration ->
                _state.value = TgState.Error("هذا الرقم ليس له حساب تيليجرام")
            is AuthorizationStateWaitOtherDeviceConfirmation,
            is AuthorizationStateWaitEmailAddress,
            is AuthorizationStateWaitEmailCode ->
                _state.value = TgState.Error("طريقة الدخول هذه غير مدعومة. ألغِ الربط وحاول مرة أخرى.")
            is AuthorizationStateLoggingOut, is AuthorizationStateClosing -> _state.value = TgState.Loading
            is AuthorizationStateClosed -> {
                synchronized(this) { if (client === c) client = null }
                paramsSent = false
                val ctx = app
                if (ctx != null) start(ctx) else _state.value = TgState.Off
            }
            else -> {}
        }
    }

    private suspend fun sendParams(c: TdlClient) {
        if (paramsSent) return
        paramsSent = true
        val ctx = app ?: return
        val store = Store(ctx)
        val r = c.setTdlibParameters(
            useTestDc = false,
            databaseDirectory = JFile(ctx.filesDir, "tdlib").absolutePath,
            filesDirectory = JFile(ctx.filesDir, "tg_files").absolutePath,
            databaseEncryptionKey = ByteArray(0),
            useFileDatabase = true,
            useChatInfoDatabase = true,
            useMessageDatabase = true,
            useSecretChats = false,
            apiId = store.tgApiId,
            apiHash = store.tgApiHash,
            systemLanguageCode = "ar",
            deviceModel = (Build.MANUFACTURER + " " + Build.MODEL).trim(),
            systemVersion = "Android " + Build.VERSION.RELEASE,
            applicationVersion = "دروسي 2.0"
        )
        if (r is TdlResult.Failure) {
            _state.value = TgState.Error("قيم api_id أو api_hash غير صحيحة: ${r.message}")
        } else {
            applyProxy(c)
        }
    }

    // ---------- البروكسي ----------

    /** يقرأ رابط بروكسي تيليجرام: tg://proxy?server=..&port=..&secret=.. أو tg://socks?server=..&port=..&user=..&pass=.. */
    fun parseProxy(link: String): Proxy? {
        val l = link.trim()
        if (l.isEmpty()) return null
        val u = runCatching { android.net.Uri.parse(l) }.getOrNull() ?: return null
        val server = u.getQueryParameter("server")?.trim().orEmpty()
        val port = u.getQueryParameter("port")?.trim()?.toIntOrNull() ?: return null
        if (server.isEmpty()) return null
        val secret = u.getQueryParameter("secret")?.trim()
        return if (!secret.isNullOrEmpty()) {
            Proxy(server = server, port = port, type = ProxyTypeMtproto(secret = secret))
        } else {
            Proxy(
                server = server, port = port,
                type = ProxyTypeSocks5(username = u.getQueryParameter("user") ?: "", password = u.getQueryParameter("pass") ?: "")
            )
        }
    }

    private suspend fun applyProxy(c: TdlClient) {
        val ctx = app ?: return
        c.getProxies().orNull()?.proxies?.forEach { c.removeProxy(proxyId = it.id) }
        val p = parseProxy(Store(ctx).tgProxy)
        if (p != null) c.addProxy(proxy = p, enable = true, comment = "دروسي") else c.disableProxy()
    }

    /** يطبّق البروكسي المحفوظ فوراً (بعد تغييره من لوحة المشرف). */
    fun reapplyProxy() {
        val c = synchronized(this) { client } ?: return
        scope.launch { applyProxy(c) }
    }

    /** يختبر البروكسي؛ يعيد null عند النجاح أو رسالة الخطأ. */
    suspend fun testProxy(link: String): String? {
        val p = parseProxy(link) ?: return "الرابط غير صحيح"
        val c = synchronized(this) { client } ?: return "احفظ مفاتيح تيليجرام أولاً"
        val r = c.testProxy(proxy = p, dcId = 2, timeout = 10.0)
        return if (r is TdlResult.Failure) r.message else null
    }

    private fun cl(): TdlClient = synchronized(this) { client } ?: throw TgError("تيليجرام غير متصل")

    // ---------- تسجيل الدخول ----------

    suspend fun sendPhone(phone: String) {
        cl().setAuthenticationPhoneNumber(phoneNumber = phone).ok()
    }

    suspend fun sendCode(code: String) {
        cl().checkAuthenticationCode(code = code).ok()
    }

    suspend fun sendPassword(password: String) {
        cl().checkAuthenticationPassword(password = password).ok()
    }

    suspend fun logOut() {
        cl().logOut().ok()
    }

    // ---------- المحادثات ----------

    fun preview(m: Message?): String {
        if (m == null) return ""
        val t = when (val ct = m.content) {
            is MessageText -> ct.text.text
            is MessagePhoto -> "صورة " + ct.caption.text
            is MessageDocument -> "ملف: " + ct.document.fileName
            is MessageVideo -> "فيديو " + ct.caption.text
            is MessageVoiceNote -> "رسالة صوتية"
            is MessageAudio -> "ملف صوتي"
            is MessageAnimation -> "صورة متحركة"
            else -> "رسالة"
        }
        return t.replace('\n', ' ').trim()
    }

    private fun info(ch: Chat): TgChatInfo {
        val kind = when (val t = ch.type) {
            is ChatTypeSupergroup -> if (t.isChannel) "قناة" else "مجموعة"
            is ChatTypeBasicGroup -> "مجموعة"
            is ChatTypePrivate -> "محادثة خاصة"
            else -> "محادثة"
        }
        return TgChatInfo(ch.id, ch.title, kind, preview(ch.lastMessage), ch.lastMessage?.date ?: 0, ch.unreadCount)
    }

    /** كل المجموعات والقنوات والمحادثات الخاصة في حساب الطالب (للمشرف فقط). */
    suspend fun groupsAndChannels(): List<TgChatInfo> {
        val c = cl()
        for (i in 0 until 10) {
            if (c.loadChats(chatList = ChatListMain(), limit = 100) is TdlResult.Failure) break
        }
        val ids = c.getChats(chatList = ChatListMain(), limit = 1000).ok().chatIds
        val out = mutableListOf<TgChatInfo>()
        for (id in ids) {
            val ch = c.getChat(chatId = id).orNull() ?: continue
            val t = ch.type
            // محادثة «Telegram» الرسمية (777000) فيها رموز الدخول، فلا تُعرض أبداً
            val allowedType = t is ChatTypeSupergroup || t is ChatTypeBasicGroup ||
                (t is ChatTypePrivate && t.userId != 777000L)
            if (allowedType) out.add(info(ch))
        }
        return out
    }

    suspend fun chatInfo(id: Long): TgChatInfo? = cl().getChat(chatId = id).orNull()?.let { info(it) }

    /** رسائل أقدم من [fromId] (0 = الأحدث)، مرتبة من الأحدث للأقدم. */
    suspend fun history(chatId: Long, fromId: Long, limit: Int = 40): List<Message> {
        val c = cl()
        val out = LinkedHashMap<Long, Message>()
        var from = fromId
        var tries = 0
        while (out.size < limit && tries < 6) {
            tries++
            val r = c.getChatHistory(
                chatId = chatId, fromMessageId = from, offset = 0,
                limit = limit - out.size, onlyLocal = false
            ).ok()
            val ms = r.messages.filterNotNull().filter { it.id != from || fromId == 0L }
            if (ms.isEmpty()) break
            ms.forEach { out[it.id] = it }
            from = ms.last().id
        }
        return out.values.toList()
    }

    /** هل تسمح تيليجرام نفسها للطالب بالكتابة في هذه المحادثة؟ */
    suspend fun canWrite(chatId: Long): Boolean {
        val ch = cl().getChat(chatId = chatId).orNull() ?: return false
        return when (val t = ch.type) {
            is ChatTypePrivate -> true
            is ChatTypeSupergroup -> !t.isChannel && ch.permissions.canSendBasicMessages
            is ChatTypeBasicGroup -> ch.permissions.canSendBasicMessages
            else -> false
        }
    }

    private suspend fun send(chatId: Long, content: InputMessageContent) {
        cl().sendMessage(chatId = chatId, inputMessageContent = content).ok()
    }

    suspend fun sendText(chatId: Long, text: String) {
        send(chatId, InputMessageText(text = FormattedText(text, emptyArray()), linkPreviewOptions = null, clearDraft = true))
    }

    /** kind: photo أو video أو أي شيء آخر = ملف. */
    suspend fun sendFile(chatId: Long, path: String, kind: String) {
        val file = InputFileLocal(path)
        val content: InputMessageContent = when (kind) {
            "photo" -> InputMessagePhoto(
                photo = InputPhoto(photo = file, thumbnail = null, video = null, addedStickerFileIds = IntArray(0), width = 0, height = 0),
                caption = null, showCaptionAboveMedia = false, selfDestructType = null, hasSpoiler = false
            )
            "video" -> InputMessageVideo(
                video = InputVideo(
                    video = file, thumbnail = null, cover = null, startTimestamp = 0, addedStickerFileIds = IntArray(0),
                    duration = 0, width = 0, height = 0, supportsStreaming = true
                ),
                caption = null, showCaptionAboveMedia = false, selfDestructType = null, hasSpoiler = false
            )
            else -> InputMessageDocument(
                document = InputDocument(document = file, thumbnail = null, disableContentTypeDetection = false),
                caption = null
            )
        }
        send(chatId, content)
    }

    suspend fun pinned(chatId: Long): Message? = cl().getChatPinnedMessage(chatId = chatId).orNull()

    suspend fun open(chatId: Long) {
        runCatching { cl().openChat(chatId = chatId) }
    }

    /** يغلق المحادثة في الخلفية (بعد إغلاق الشاشة). */
    fun closeLater(chatId: Long) {
        scope.launch { runCatching { cl().closeChat(chatId = chatId) } }
    }

    suspend fun markRead(chatId: Long, ids: List<Long>) {
        if (ids.isEmpty()) return
        runCatching {
            cl().viewMessages(chatId = chatId, messageIds = ids.toLongArray(), source = null, forceRead = true)
        }
    }

    /** ينزّل الملف ويعيد مساره على الجهاز. */
    suspend fun download(fileId: Int): String? {
        if (fileId == 0) return null
        val f = cl().downloadFile(fileId = fileId, priority = 16, offset = 0L, limit = 0L, synchronous = true).orNull()
            ?: return null
        return if (f.local.isDownloadingCompleted && f.local.path.isNotEmpty()) f.local.path else null
    }

    fun toItem(m: Message): TgItem = baseItem(m).copy(outgoing = m.isOutgoing)

    private fun baseItem(m: Message): TgItem {
        return when (val ct = m.content) {
            is MessageText -> TgItem(m.id, m.date, TgKind.TEXT, ct.text.text, 0, "", 0L, 0, 0, false)
            is MessagePhoto -> {
                val sizes = ct.photo.sizes
                val best = sizes.filter { it.width <= 1280 }.maxByOrNull { it.width } ?: sizes.lastOrNull()
                TgItem(m.id, m.date, TgKind.PHOTO, ct.caption.text, best?.photo?.id ?: 0, "", 0L, 0, 0, false)
            }
            is MessageDocument -> {
                val d = ct.document
                val pdf = d.mimeType.contains("pdf", ignoreCase = true) || d.fileName.endsWith(".pdf", ignoreCase = true)
                TgItem(
                    m.id, m.date, TgKind.DOCUMENT, ct.caption.text, d.document.id, d.fileName,
                    d.document.size, d.thumbnail?.file?.id ?: 0, 0, pdf
                )
            }
            is MessageVideo -> TgItem(
                m.id, m.date, TgKind.VIDEO, ct.caption.text, ct.video.video.id, ct.video.fileName,
                ct.video.video.size, ct.video.thumbnail?.file?.id ?: 0, ct.video.duration, false
            )
            is MessageAnimation -> TgItem(
                m.id, m.date, TgKind.VIDEO, ct.caption.text, ct.animation.animation.id, ct.animation.fileName,
                ct.animation.animation.size, ct.animation.thumbnail?.file?.id ?: 0, ct.animation.duration, false
            )
            is MessageVoiceNote -> TgItem(
                m.id, m.date, TgKind.VOICE, ct.caption.text, ct.voiceNote.voice.id, "",
                ct.voiceNote.voice.size, 0, ct.voiceNote.duration, false
            )
            is MessageAudio -> TgItem(
                m.id, m.date, TgKind.AUDIO, ct.caption.text, ct.audio.audio.id,
                ct.audio.title.ifBlank { ct.audio.fileName }, ct.audio.audio.size, 0, ct.audio.duration, false
            )
            else -> TgItem(m.id, m.date, TgKind.OTHER, "رسالة لا يمكن عرضها هنا", 0, "", 0L, 0, 0, false)
        }
    }
}
