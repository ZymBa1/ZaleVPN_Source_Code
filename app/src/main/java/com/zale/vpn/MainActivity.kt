package com.zale.vpn

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var pages: FrameLayout
    private lateinit var netPage: ScrollView
    private lateinit var chatPage: LinearLayout
    private lateinit var tabNet: TextView
    private lateinit var tabChat: TextView

    // экран сети
    private lateinit var orb: OrbView
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var ipText: TextView
    private lateinit var modeCard: LinearLayout
    private lateinit var modeP2P: TextView
    private lateinit var modeWG: TextView
    private lateinit var modeDesc: TextView
    private lateinit var nickInput: EditText
    private lateinit var actionP2P: LinearLayout
    private lateinit var actionWG: LinearLayout
    private lateinit var codeInput: EditText
    private lateinit var codeCard: LinearLayout
    private lateinit var codeText: TextView
    private lateinit var peersCard: LinearLayout
    private lateinit var peersList: LinearLayout
    private lateinit var slotsCard: LinearLayout
    private lateinit var slotsList: LinearLayout
    private lateinit var disconnectBtn: Button

    // чат
    private lateinit var chatScroll: ScrollView
    private lateinit var chatList: LinearLayout
    private lateinit var chatEmpty: TextView
    private lateinit var chatInputRow: LinearLayout
    private lateinit var chatInput: EditText

    private var selectedMode = Mode.P2P
    private var pending: (() -> Unit)? = null
    private var unread = 0
    private var onChatTab = false
    private var lastPhase = Phase.IDLE

    companion object {
        private var splashShown = false
    }

    // ---------- помощники ----------
    private fun dp(v: Int) = T.dp(this, v)

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun label(t: String, size: Float, color: Int = T.TEXT, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun caption(t: String): TextView = label(t, 11f, T.MUTED, true).apply { letterSpacing = 0.12f }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = T.glass(this@MainActivity, 22)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    private fun btn(t: String, bg: Drawable, color: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = t
            isAllCaps = false
            textSize = 16f
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
            background = T.ripple(bg)
            stateListAnimator = null
            minHeight = dp(54)
            minimumHeight = dp(54)
            T.pressable(this)
            setOnClickListener { onClick() }
        }

    private fun smallBtn(t: String, onClick: () -> Unit): Button =
        btn(t, T.brand(this, 12), Color.WHITE, onClick).apply {
            textSize = 13f
            minHeight = dp(40)
            minimumHeight = dp(40)
            minWidth = dp(64)
            minimumWidth = dp(64)
            setPadding(dp(14), 0, dp(14), 0)
        }

    private fun field(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(T.MUTED)
        setTextColor(T.TEXT)
        textSize = 17f
        setSingleLine(true)
        background = T.solid(this@MainActivity, 14, T.alpha(Color.WHITE, 18))
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun show(v: View, visible: Boolean) {
        if (visible) {
            if (v.visibility != View.VISIBLE) {
                v.visibility = View.VISIBLE
                v.alpha = 0f
                v.translationY = dp(14).toFloat()
                v.animate().alpha(1f).translationY(0f).setDuration(320).start()
            }
        } else {
            v.visibility = View.GONE
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ---------- создание экрана ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = T.BG1
        window.navigationBarColor = T.BG2
        prefs = getSharedPreferences("zale", MODE_PRIVATE)

        root = FrameLayout(this).apply { background = T.bgGradient() }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pages = FrameLayout(this)
        buildNetPage()
        buildChatPage()
        pages.addView(netPage, FrameLayout.LayoutParams(MATCH, MATCH))
        pages.addView(chatPage, FrameLayout.LayoutParams(MATCH, MATCH))
        chatPage.visibility = View.GONE
        content.addView(pages, LinearLayout.LayoutParams(MATCH, 0, 1f))
        content.addView(buildTabBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        Net.onState = { s -> runOnUiThread { render(s) } }
        Chat.onMessage = { m ->
            runOnUiThread {
                addBubble(m)
                if (!onChatTab) {
                    unread++
                    updateTabs()
                }
            }
        }
        for (m in Chat.history) addBubble(m)
        updateModeUI()
        render(Net.current)

        if (!splashShown && savedInstanceState == null) {
            splashShown = true
            startSplash()
        } else {
            Thread {
                try { Net.init(applicationContext) } catch (e: Throwable) {}
            }.start()
        }
    }

    override fun onDestroy() {
        Net.onState = {}
        Chat.onMessage = {}
        super.onDestroy()
    }

    // ---------- загрузочный экран ----------
    private fun startSplash() {
        val splash = SplashView(this)
        root.addView(splash, FrameLayout.LayoutParams(MATCH, MATCH))
        val steps = listOf("ядро WireGuard", "сетевой модуль", "P2P и STUN", "чат", "интерфейс")
        Thread {
            try { Net.init(applicationContext) } catch (e: Throwable) {}
        }.start()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200
            addUpdateListener {
                val p = it.animatedFraction
                splash.progress = p
                splash.stage = steps[minOf((p * steps.size).toInt(), steps.size - 1)] + "…"
                splash.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    splash.animate().alpha(0f).setDuration(500).withEndAction { root.removeView(splash) }.start()
                }
            })
            start()
        }
    }

    // ---------- экран «Сеть» ----------
    private fun buildNetPage() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        // шапка
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = label("Z", 22f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = T.brand(this@MainActivity, 24)
        }
        head.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("ZaleVPN", 26f, T.TEXT, true))
        titles.addView(label("by Xake Team", 12f, T.MUTED))
        head.addView(titles, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(14) })
        col.addView(head, lp())

        // шар статуса
        orb = OrbView(this)
        col.addView(orb, LinearLayout.LayoutParams(dp(210), dp(210)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })
        statusTitle = label("", 22f, T.TEXT, true).apply { gravity = Gravity.CENTER }
        col.addView(statusTitle, lp())
        statusSub = label("", 14f, T.MUTED).apply { gravity = Gravity.CENTER }
        col.addView(statusSub, lp(4))

        val chip = card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { copy(ipText.text.toString(), "IP скопирован") }
        }
        chip.addView(caption("ВАШ ВИРТУАЛЬНЫЙ IP"))
        ipText = label("—", 28f, T.CYAN, true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        chip.addView(ipText, lp(2))
        col.addView(chip, lp(18))

        // выбор режима
        modeCard = card()
        modeCard.addView(caption("ТИП СЕТИ"))
        val seg = LinearLayout(this).apply {
            background = T.solid(this@MainActivity, 16, T.alpha(Color.WHITE, 14))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        modeP2P = label("⚡ P2P", 15f, T.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { selectedMode = Mode.P2P; updateModeUI() }
        }
        modeWG = label("🛡 WireGuard", 15f, T.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { selectedMode = Mode.WG; updateModeUI() }
        }
        seg.addView(modeP2P, LinearLayout.LayoutParams(0, WRAP, 1f))
        seg.addView(modeWG, LinearLayout.LayoutParams(0, WRAP, 1f))
        modeCard.addView(seg, lp(10))
        modeDesc = label("", 13f, T.MUTED)
        modeCard.addView(modeDesc, lp(10))
        modeCard.addView(caption("ВАШ НИК В ЧАТЕ"), lp(16))
        nickInput = field("Ник").apply {
            setText(prefs.getString("nick", null) ?: ("Игрок" + (100..999).random()))
        }
        modeCard.addView(nickInput, lp(8))
        col.addView(modeCard, lp(18))

        // действия P2P
        actionP2P = card()
        actionP2P.addView(btn("Создать P2P-сеть", T.brand(this, 16), Color.WHITE) {
            Chat.nick = nick()
            requestVpn { Net.startP2P(applicationContext, null) }
        }, lp())
        actionP2P.addView(label("или войдите по коду друга", 13f, T.MUTED).apply { gravity = Gravity.CENTER }, lp(16))
        codeInput = field("Код приглашения").apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            typeface = Typeface.MONOSPACE
        }
        actionP2P.addView(codeInput, lp(10))
        actionP2P.addView(btn("Подключиться", T.outline(this, 16, T.CYAN), T.CYAN) {
            val code = codeInput.text.toString().trim().uppercase()
            if (code.length < 4) {
                toast("Введите код приглашения")
            } else {
                Chat.nick = nick()
                requestVpn { Net.startP2P(applicationContext, code) }
            }
        }, lp(10))
        col.addView(actionP2P, lp(14))

        // действия WireGuard
        actionWG = card()
        actionWG.addView(btn("Создать WireGuard-сеть", T.brand(this, 16), Color.WHITE) {
            Chat.nick = nick()
            requestVpn { Net.startWG(applicationContext) }
        }, lp())
        actionWG.addView(
            label("Приложение создаст 5 QR-кодов. Друг сканирует QR в официальном приложении WireGuard и подключается к вам.", 13f, T.MUTED),
            lp(12)
        )
        col.addView(actionWG, lp(14))

        // код приглашения (P2P)
        codeCard = card()
        codeCard.addView(caption("КОД ПРИГЛАШЕНИЯ"))
        codeText = label("", 34f, T.CYAN, true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        codeCard.addView(codeText, lp(6))
        val br = LinearLayout(this)
        br.addView(
            btn("Копировать", T.outline(this, 16, T.CARD_STROKE), T.TEXT) { copy(codeText.text.toString(), "Код скопирован") },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
        )
        br.addView(
            btn("Поделиться", T.brand(this, 16), Color.WHITE) { share(codeText.text.toString()) },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        codeCard.addView(br, lp(14))
        col.addView(codeCard, lp(14))

        // участники (P2P)
        peersCard = card()
        peersCard.addView(caption("УЧАСТНИКИ"))
        peersList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        peersCard.addView(peersList, lp(8))
        peersCard.addView(label("Нажмите на адрес, чтобы скопировать", 12f, T.MUTED), lp(8))
        col.addView(peersCard, lp(14))

        // друзья (WireGuard)
        slotsCard = card()
        slotsCard.addView(caption("ДРУЗЬЯ · QR ДЛЯ WIREGUARD"))
        slotsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        slotsCard.addView(slotsList, lp(6))
        slotsCard.addView(
            label("Друг подключается в приложении WireGuard и заходит в игру по вашему IP 10.60.0.1. Друзья видят только вас, а не друг друга.", 12f, T.MUTED),
            lp(10)
        )
        col.addView(slotsCard, lp(14))

        disconnectBtn = btn("Отключиться", T.outline(this, 16, T.RED), T.RED) { Net.stop() }
        col.addView(disconnectBtn, lp(14))

        col.addView(label("Xake Team · бета-версия", 12f, T.MUTED).apply { gravity = Gravity.CENTER }, lp(26))

        netPage = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(col)
        }
    }

    private fun updateModeUI() {
        val on = selectedMode == Mode.P2P
        modeP2P.background = if (on) T.brand(this, 13) else null
        modeWG.background = if (!on) T.brand(this, 13) else null
        modeP2P.setTextColor(if (on) Color.WHITE else T.MUTED)
        modeWG.setTextColor(if (!on) Color.WHITE else T.MUTED)
        modeDesc.text = if (on) {
            "Все участники с приложением ZaleVPN. Подключение по коду, напрямую друг к другу, есть чат."
        } else {
            "Ваш телефон становится сервером. Друзья подключаются через приложение WireGuard (iPhone, ПК, Android) по QR-коду."
        }
        render(Net.current)
    }

    // ---------- отрисовка состояния ----------
    private fun render(s: NetState) {
        if (s.phase == Phase.CONNECTING && lastPhase != Phase.CONNECTING) chatList.removeAllViews()
        lastPhase = s.phase

        val active = s.phase == Phase.CONNECTING || s.phase == Phase.CONNECTED
        val p2p = s.phase == Phase.CONNECTED && s.mode == Mode.P2P
        val wg = s.phase == Phase.CONNECTED && s.mode == Mode.WG

        orb.phase = s.phase
        statusTitle.text = when (s.phase) {
            Phase.IDLE -> "Не подключено"
            Phase.CONNECTING -> "Подключение…"
            Phase.CONNECTED -> "Подключено"
            Phase.ERROR -> "Ошибка"
        }
        statusSub.text = when (s.phase) {
            Phase.IDLE -> "Выберите тип сети и создайте её"
            Phase.CONNECTED ->
                if (s.mode == Mode.P2P) "В игре подключайтесь по виртуальному IP" else "Друзья входят по QR-коду через WireGuard"
            else -> s.message
        }
        ipText.text = if (s.myIp.isEmpty()) "—" else s.myIp

        show(modeCard, !active)
        show(actionP2P, !active && selectedMode == Mode.P2P)
        show(actionWG, !active && selectedMode == Mode.WG)

        show(codeCard, p2p)
        codeText.text = s.code
        show(peersCard, p2p)
        peersList.removeAllViews()
        if (s.peers.isEmpty()) {
            peersList.addView(label("Пока никого. Отправьте код друзьям.", 14f, T.MUTED))
        } else {
            for (ip in s.peers) {
                val suffix = if (ip == "10.50.0.1") "  · создатель" else ""
                peersList.addView(label("●  $ip$suffix", 17f, T.TEXT).apply {
                    typeface = Typeface.MONOSPACE
                    setPadding(0, dp(6), 0, dp(6))
                    setOnClickListener { copy(ip, "IP скопирован") }
                })
            }
        }

        show(slotsCard, wg)
        if (slotsList.tag != s.slots) {
            slotsList.removeAllViews()
            for (sl in s.slots) slotsList.addView(slotRow(sl))
            slotsList.tag = s.slots
        }

        show(disconnectBtn, active)

        // чат
        chatEmpty.text = if (wg) "Чат работает в P2P-режиме. В WireGuard-режиме друзья подключаются через приложение WireGuard."
        else "Подключитесь к P2P-сети, чтобы писать друзьям"
        chatEmpty.visibility = if (p2p) View.GONE else View.VISIBLE
        chatInputRow.visibility = if (p2p) View.VISIBLE else View.GONE

        if (s.phase == Phase.CONNECTED) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun slotRow(s: Slot): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(label("Друг ${s.index}", 16f, T.TEXT, true))
        info.addView(label(s.ip, 13f, T.MUTED))
        row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(smallBtn("QR") { showQr(s) })
        row.addView(
            smallBtn("Конфиг") { copy(s.config, "Конфиг скопирован") },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
        )
        return row
    }

    private fun showQr(s: Slot) {
        val iv = ImageView(this).apply {
            setImageBitmap(Qr.bitmap(s.config))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Друг ${s.index}: отсканируйте в WireGuard")
            .setView(iv)
            .setPositiveButton("Готово", null)
            .setNeutralButton("Копировать конфиг") { _, _ -> copy(s.config, "Конфиг скопирован") }
            .show()
    }

    // ---------- чат ----------
    private fun buildChatPage() {
        chatPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(8))
        }
        chatPage.addView(label("Чат сети", 26f, T.TEXT, true), lp())
        chatPage.addView(label("Сообщения идут напрямую между участниками", 13f, T.MUTED), lp(2))

        val frame = FrameLayout(this)
        chatList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(chatList)
        }
        chatEmpty = label("", 15f, T.MUTED).apply { gravity = Gravity.CENTER }
        frame.addView(chatScroll, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(chatEmpty, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            leftMargin = dp(24)
            rightMargin = dp(24)
        })
        chatPage.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })

        chatInputRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        chatInput = field("Сообщение…").apply {
            setSingleLine(false)
            maxLines = 4
        }
        chatInputRow.addView(chatInput, LinearLayout.LayoutParams(0, WRAP, 1f))
        val send = label("➤", 20f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = T.brand(this@MainActivity, 26)
            T.pressable(this)
            setOnClickListener { sendChat() }
        }
        chatInputRow.addView(send, LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(8) })
        chatPage.addView(chatInputRow, lp(8))
    }

    private fun sendChat() {
        val t = chatInput.text.toString().trim()
        if (t.isEmpty()) return
        Chat.send(t)
        chatInput.setText("")
    }

    private fun addBubble(m: ChatMsg) {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = if (m.mine) T.brand(this@MainActivity, 18) else T.glass(this@MainActivity, 18)
        }
        if (!m.mine) bubble.addView(label(m.name, 12f, T.CYAN, true))
        bubble.addView(label(m.text, 16f, T.TEXT))
        val params = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = if (m.mine) Gravity.END else Gravity.START
            topMargin = dp(8)
            if (m.mine) leftMargin = dp(48) else rightMargin = dp(48)
        }
        chatList.addView(bubble, params)
        bubble.alpha = 0f
        bubble.translationY = dp(12).toFloat()
        bubble.animate().alpha(1f).translationY(0f).setDuration(220).start()
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- вкладки ----------
    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(T.alpha(Color.BLACK, 90))
        }
        tabNet = label("🌐 Сеть", 15f, T.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { selectTab(false) }
        }
        tabChat = label("💬 Чат", 15f, T.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { selectTab(true) }
        }
        bar.addView(tabNet, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        bar.addView(tabChat, LinearLayout.LayoutParams(0, WRAP, 1f))
        updateTabs()
        return bar
    }

    private fun selectTab(chat: Boolean) {
        onChatTab = chat
        if (chat) unread = 0
        netPage.visibility = if (chat) View.GONE else View.VISIBLE
        chatPage.visibility = if (chat) View.VISIBLE else View.GONE
        updateTabs()
        if (chat) chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateTabs() {
        tabNet.background = if (!onChatTab) T.brand(this, 16) else null
        tabChat.background = if (onChatTab) T.brand(this, 16) else null
        tabNet.setTextColor(if (!onChatTab) Color.WHITE else T.MUTED)
        tabChat.setTextColor(if (onChatTab) Color.WHITE else T.MUTED)
        tabChat.text = if (unread > 0) "💬 Чат ($unread)" else "💬 Чат"
    }

    // ---------- действия ----------
    private fun nick(): String {
        val n = nickInput.text.toString().trim().ifEmpty { "Игрок" }.take(16)
        prefs.edit().putString("nick", n).apply()
        return n
    }

    private fun requestVpn(action: () -> Unit) {
        val i = VpnService.prepare(this)
        if (i != null) {
            pending = action
            startActivityForResult(i, 1)
        } else {
            action()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1) {
            val a = pending
            pending = null
            if (resultCode == RESULT_OK && a != null) a() else toast("Нужно разрешить VPN-подключение")
        }
    }

    private fun copy(t: String, msg: String) {
        if (t.isEmpty() || t == "—") return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ZaleVPN", t))
        toast(msg)
    }

    private fun share(code: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Заходи в мою сеть в ZaleVPN! Код приглашения: $code")
        }
        startActivity(Intent.createChooser(i, "Поделиться кодом"))
    }
}
