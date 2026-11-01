package com.zale.vpn

import android.Manifest
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
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.zale.vpn.api.Urls
import java.io.File

class MainActivity : Activity() {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var pages: FrameLayout
    private lateinit var netPage: ScrollView
    private lateinit var chatPage: LinearLayout
    private lateinit var settingsPage: ScrollView
    private lateinit var tabNet: TextView
    private lateinit var tabChat: TextView
    private lateinit var tabSettings: TextView

    // экран сети
    private lateinit var orb: OrbView
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var ipText: TextView
    private lateinit var modeCard: LinearLayout
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
    private lateinit var connectBar: LinearLayout
    private lateinit var connectBtn: Button
    private lateinit var launch: LaunchView
    private var pulse: ValueAnimator? = null

    // чат
    private lateinit var chatScroll: ScrollView
    private lateinit var chatList: LinearLayout
    private lateinit var chatEmpty: TextView
    private lateinit var chatInputRow: LinearLayout
    private lateinit var chatInput: EditText

    private var selectedMode = Mode.P2P
    private var pendingVpn: (() -> Unit)? = null
    private var pendingPerm: (() -> Unit)? = null
    private var unread = 0
    private var tabIndex = 0
    private var lastPhase = Phase.IDLE

    private var settingsP2P: TextView? = null
    private var settingsWG: TextView? = null
    private var settingsModeDesc: TextView? = null
    private val bgChecks = ArrayList<TextView>()
    private val fontChecks = ArrayList<TextView>()
    private val bubbles = HashMap<String, Bubble>()
    private var versionTaps = 0

    /** Вспомогательные ссылки на элементы пузыря чата для обновления прогресса. */
    private class Bubble(
        val root: LinearLayout,
        val status: TextView,
        val bar: ProgressBar,
        val image: ImageView?
    )

    companion object {
        private var splashShown = false
        private const val VERSION = "1.2 Lolipop"
        private const val REQ_FILE = 4711
    }

    // ---------- помощники ----------
    private fun dp(v: Int) = T.dp(this, v)

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun label(t: String, size: Float, color: Int = T.TEXT, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            typeface = T.face(bold)
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
            typeface = T.face(true)
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
        typeface = T.face(false)
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
        prefs = getSharedPreferences("zale", MODE_PRIVATE)
        T.init(this)
        T.setPalette(prefs.getInt("bg", 0))
        T.setFont(prefs.getInt("font", 0))
        selectedMode = if (prefs.getString("mode", Mode.P2P.name) == Mode.WG.name) Mode.WG else Mode.P2P

        window.statusBarColor = T.BG1
        window.navigationBarColor = T.BG2

        root = FrameLayout(this).apply { background = T.bgGradient() }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pages = FrameLayout(this)
        buildNetPage()
        buildChatPage()
        buildSettingsPage()
        pages.addView(netPage, FrameLayout.LayoutParams(MATCH, MATCH))
        pages.addView(chatPage, FrameLayout.LayoutParams(MATCH, MATCH))
        pages.addView(settingsPage, FrameLayout.LayoutParams(MATCH, MATCH))
        chatPage.visibility = View.GONE
        settingsPage.visibility = View.GONE
        content.addView(pages, LinearLayout.LayoutParams(MATCH, 0, 1f))
        content.addView(buildConnectBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        content.addView(buildTabBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        launch = LaunchView(this).apply {
            visibility = View.GONE
            alpha = 0f
            isClickable = true
            setOnClickListener { Net.stop() }
        }
        root.addView(launch, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        Net.onState = { s -> runOnUiThread { render(s) } }
        Chat.app = applicationContext
        Chat.onMessage = { m ->
            runOnUiThread {
                addBubble(m)
                if (tabIndex != 1) {
                    unread++
                    updateTabs()
                }
            }
        }
        Chat.onUpdate = { m -> runOnUiThread { updateBubble(m) } }
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
            maybeFirstPolicy()
        }
    }

    override fun onDestroy() {
        Net.onState = {}
        Chat.onMessage = {}
        Chat.onUpdate = {}
        pulse?.cancel()
        pulse = null
        super.onDestroy()
    }

    // ---------- загрузочный экран ----------
    private fun startSplash() {
        val splash = SplashView(this)
        root.addView(splash, FrameLayout.LayoutParams(MATCH, MATCH))
        val steps = listOf("ядро WireGuard", "сетевой модуль", "P2P и STUN", "чат", "шрифты", "интерфейс")
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
                    splash.animate().alpha(0f).setDuration(500)
                        .withEndAction {
                            root.removeView(splash)
                            maybeFirstPolicy()
                        }
                        .start()
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

        // логотип в левом верхнем углу
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(LogoView(this), LinearLayout.LayoutParams(dp(46), dp(46)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("ZaleVPN", 20f, T.TEXT, true))
        titles.addView(label("by Xake Team · $VERSION", 11f, T.MUTED))
        head.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
        col.addView(head, lp(6))

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
            tag = "mono"
        }
        chip.addView(ipText, lp(2))
        col.addView(chip, lp(18))

        // ник для чата (тип сети теперь выбирается только в настройках)
        modeCard = card()
        modeCard.addView(caption("ВАШ НИК В ЧАТЕ"))
        nickInput = field("Ник").apply {
            setText(prefs.getString("nick", null) ?: ("Игрок" + (100..999).random()))
        }
        modeCard.addView(nickInput, lp(8))
        col.addView(modeCard, lp(18))

        // действия P2P
        actionP2P = card()
        actionP2P.addView(btn("Создать P2P-сеть", T.brand(this, 16), Color.WHITE) {
            connectP2P(null)
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
                connectP2P(code)
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
            tag = "mono"
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

        col.addView(label("Xake Team · $VERSION", 12f, T.MUTED).apply { gravity = Gravity.CENTER }, lp(26))

        netPage = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(col)
        }
    }

    private fun updateModeUI() {
        val on = selectedMode == Mode.P2P
        settingsP2P?.background = if (on) T.brand(this, 13) else null
        settingsWG?.background = if (!on) T.brand(this, 13) else null
        settingsP2P?.setTextColor(if (on) Color.WHITE else T.MUTED)
        settingsWG?.setTextColor(if (!on) Color.WHITE else T.MUTED)

        prefs.edit().putString("mode", selectedMode.name).apply()
        settingsModeDesc?.text = if (on) {
            "P2P: все участники с приложением ZaleVPN. Подключение по коду, напрямую друг к другу, есть чат."
        } else {
            "WireGuard: ваш телефон становится сервером. Друзья подключаются через приложение WireGuard (iPhone, ПК, Android) по QR-коду или коду конфигурации."
        }
        render(Net.current)
    }

    // ---------- отрисовка состояния ----------
    private fun render(s: NetState) {
        if (isFinishing || isDestroyed) return
        val prev = lastPhase
        if (s.phase == Phase.CONNECTING && prev != Phase.CONNECTING) {
            chatList.removeAllViews()
            bubbles.clear()
        }
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
            Phase.IDLE -> "Нажмите «Включить», чтобы создать сеть"
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

        styleConnect(s.phase)
        if (s.phase == Phase.CONNECTING) {
            launch.show(s.message)
            startPulse()
        } else {
            if (prev == Phase.CONNECTING) launch.hide()
            stopPulse()
        }

        // чат
        chatEmpty.text = when {
            wg -> "Чат работает в P2P-режиме. В WireGuard-режиме друзья подключаются через приложение WireGuard."
            !p2p -> "Подключитесь к P2P-сети, чтобы писать друзьям"
            else -> "Пока пусто. Напишите первым или прикрепите фото 📎"
        }
        chatEmpty.visibility = if (p2p && chatList.childCount > 0) View.GONE else View.VISIBLE
        chatInputRow.visibility = if (p2p) View.VISIBLE else View.GONE

        if (s.phase == Phase.CONNECTED) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------- нижняя кнопка «Включить» и анимация ----------
    private fun buildConnectBar(): View {
        connectBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(10))
            setBackgroundColor(T.alpha(Color.BLACK, 70))
        }
        connectBtn = btn("ВКЛЮЧИТЬ", T.brand(this, 28), Color.WHITE) { onConnectClick() }.apply {
            textSize = 18f
            letterSpacing = 0.1f
            minHeight = dp(62)
            minimumHeight = dp(62)
        }
        connectBar.addView(connectBtn, LinearLayout.LayoutParams(MATCH, WRAP))
        return connectBar
    }

    private fun styleConnect(phase: Phase) {
        when (phase) {
            Phase.CONNECTED -> {
                connectBtn.text = "ОТКЛЮЧИТЬ"
                connectBtn.setTextColor(T.RED)
                connectBtn.background = T.ripple(T.outline(this, 28, T.RED))
            }
            Phase.CONNECTING -> {
                connectBtn.text = "ПОДКЛЮЧЕНИЕ…"
                connectBtn.setTextColor(Color.WHITE)
                connectBtn.background = T.ripple(T.brand(this, 28))
            }
            else -> {
                connectBtn.text = "ВКЛЮЧИТЬ"
                connectBtn.setTextColor(Color.WHITE)
                connectBtn.background = T.ripple(T.brand(this, 28))
            }
        }
    }

    private fun startPulse() {
        if (pulse != null) return
        pulse = ValueAnimator.ofFloat(0.97f, 1.03f).apply {
            duration = 750
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                val v = it.animatedValue as Float
                connectBtn.scaleX = v
                connectBtn.scaleY = v
            }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        connectBtn.scaleX = 1f
        connectBtn.scaleY = 1f
    }

    private fun onConnectClick() {
        when (Net.current.phase) {
            Phase.CONNECTED, Phase.CONNECTING -> Net.stop()
            else -> {
                if (selectedMode == Mode.P2P) {
                    val code = codeInput.text.toString().trim().uppercase()
                    connectP2P(if (code.length >= 4) code else null)
                } else {
                    Chat.nick = nick()
                    requestVpn { Net.startWG(applicationContext) }
                }
            }
        }
    }

    private fun connectP2P(code: String?) {
        Chat.nick = nick()
        Privacy.show(this) {
            requestVpn { Net.startP2P(applicationContext, code) }
        }
    }

    private fun maybeFirstPolicy() {
        if (isFinishing || isDestroyed) return
        if (prefs.getBoolean("policyAccepted", false)) return
        Privacy.show(this) {
            prefs.edit().putBoolean("policyAccepted", true).apply()
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
            smallBtn("Код") { showConfig(s) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
        )
        return row
    }

    private fun showConfig(s: Slot) {
        val tv = TextView(this).apply {
            text = s.config
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(T.TEXT)
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("Код конфигурации · Друг ${s.index}")
            .setView(scroll)
            .setPositiveButton("Копировать") { _, _ -> copy(s.config, "Код скопирован") }
            .setNeutralButton("QR-код") { _, _ -> showQr(s) }
            .setNegativeButton("Закрыть", null)
            .show()
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
        chatPage.addView(label("Сообщения, фото и файлы идут напрямую между участниками", 13f, T.MUTED), lp(2))

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
        val attach = label("📎", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = T.outline(this@MainActivity, 24, T.CYAN)
            T.pressable(this)
            setOnClickListener { pickFile() }
        }
        chatInputRow.addView(attach, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
        })
        val send = label("➤", 20f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = T.brand(this@MainActivity, 26)
            T.pressable(this)
            setOnClickListener { sendChat() }
        }
        chatInputRow.addView(send, LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(0) })
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

        val status = label("", 12f, T.MUTED)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(T.CYAN)
            backgroundTintList = ColorStateList.valueOf(T.alpha(Color.WHITE, 36))
            visibility = View.GONE
        }
        var image: ImageView? = null

        when (m.kind) {
            Chat.KIND_IMAGE -> {
                val iv = ImageView(this).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    maxWidth = dp(220)
                    maxHeight = dp(280)
                    setOnClickListener { if (m.path.isNotEmpty()) showPhoto(m.path) }
                }
                image = iv
                bubble.addView(iv, LinearLayout.LayoutParams(WRAP, WRAP))
                bubble.addView(status, lp(4))
                bubble.addView(bar, LinearLayout.LayoutParams(dp(180), WRAP).apply { topMargin = dp(6) })
            }
            Chat.KIND_FILE -> {
                val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                head.addView(label("📎", 18f, T.CYAN))
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(label(m.text, 15f, T.TEXT, true))
                info.addView(status)
                head.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
                bubble.addView(head)
                bubble.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
                bubble.isClickable = true
                bubble.setOnClickListener { openFile(m) }
            }
            else -> bubble.addView(label(m.text, 16f, T.TEXT))
        }

        val params = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = if (m.mine) Gravity.END else Gravity.START
            topMargin = dp(8)
            if (m.mine) leftMargin = dp(48) else rightMargin = dp(48)
        }
        chatList.addView(bubble, params)
        chatEmpty.visibility = View.GONE
        bubbles[m.id] = Bubble(bubble, status, bar, image)
        if (m.kind != Chat.KIND_TEXT) renderMedia(m, status, bar, image)

        bubble.alpha = 0f
        bubble.translationY = dp(12).toFloat()
        bubble.animate().alpha(1f).translationY(0f).setDuration(220).start()
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateBubble(m: ChatMsg) {
        if (m.kind == Chat.KIND_TEXT) return
        val b = bubbles[m.id] ?: return
        renderMedia(m, b.status, b.bar, b.image)
    }

    private fun renderMedia(m: ChatMsg, status: TextView, bar: ProgressBar, image: ImageView?) {
        when (m.state) {
            Chat.STATE_FAILED -> {
                bar.visibility = View.GONE
                status.setTextColor(T.RED)
                status.text = "Не удалось передать файл"
            }
            Chat.STATE_ACTIVE -> {
                val p = m.progress.coerceIn(0, 100)
                bar.visibility = View.VISIBLE
                bar.progress = p
                status.setTextColor(T.MUTED)
                status.text = if (m.mine) "Отправка… $p%" else "Загрузка… $p%"
            }
            else -> {
                bar.visibility = View.GONE
                status.setTextColor(T.MUTED)
                status.text = "${m.text} · ${formatSize(m.size)}"
                if (image != null && m.path.isNotEmpty() && image.drawable == null) {
                    Thread {
                        val bmp = decodeScaled(m.path)
                        if (bmp != null) runOnUiThread { image.setImageBitmap(bmp) }
                    }.start()
                }
            }
        }
    }

    private fun decodeScaled(path: String): android.graphics.Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 900 || bounds.outHeight / sample > 900) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }

    private fun showPhoto(path: String) {
        val bmp = decodeScaled(path)
        if (bmp == null) {
            toast("Не удалось открыть фото")
            return
        }
        val iv = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
        }
        val dialog = AlertDialog.Builder(this)
            .setView(iv)
            .setPositiveButton("Закрыть", null)
            .create()
        dialog.show()
        dialog.window?.setLayout(
            MATCH,
            (resources.displayMetrics.heightPixels * 0.8f).toInt()
        )
    }

    private fun openFile(m: ChatMsg) {
        if (m.path.isEmpty()) {
            toast("Файл ещё не получен")
            return
        }
        if (m.kind == Chat.KIND_IMAGE) {
            showPhoto(m.path)
            return
        }
        val file = File(m.path)
        val type = m.mime.ifBlank { "application/octet-stream" }
        val uri = ChatFileProvider.uriFor(file)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: Exception) {
            val share = Intent(Intent.ACTION_SEND)
                .setType(type)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(Intent.createChooser(share, "Открыть файл"))
            } catch (e2: Exception) {
                toast("Нет приложения для этого файла")
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "0 Б"
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> String.format("%.1f КБ", bytes / 1024.0)
        else -> String.format("%.1f МБ", bytes / (1024.0 * 1024.0))
    }

    private fun pickFile() {
        if (Net.current.phase != Phase.CONNECTED || Net.current.mode != Mode.P2P) {
            toast("Фото и файлы можно отправлять в P2P-чате")
            return
        }
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, REQ_FILE)
        } catch (e: Exception) {
            toast("Не удалось открыть проводник")
        }
    }

    // ---------- настройки ----------
    private fun buildSettingsPage() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Настройки", 26f, T.TEXT, true))
        titles.addView(label("ZaleVPN · $VERSION", 12f, T.MUTED))
        head.addView(titles, LinearLayout.LayoutParams(WRAP, WRAP))
        col.addView(head, lp())

        // тип сети
        val mode = card()
        mode.addView(caption("ТИП СЕТИ"))
        val seg = LinearLayout(this).apply {
            background = T.solid(this@MainActivity, 16, T.alpha(Color.WHITE, 14))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val p2p = label("⚡ P2P", 15f, T.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { selectedMode = Mode.P2P; updateModeUI() }
        }
        val wg = label("🛡 WireGuard", 15f, T.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { selectedMode = Mode.WG; updateModeUI() }
        }
        settingsP2P = p2p
        settingsWG = wg
        seg.addView(p2p, LinearLayout.LayoutParams(0, WRAP, 1f))
        seg.addView(wg, LinearLayout.LayoutParams(0, WRAP, 1f))
        mode.addView(seg, lp(10))
        val modeDesc = label("", 12f, T.MUTED)
        settingsModeDesc = modeDesc
        mode.addView(modeDesc, lp(10))
        mode.addView(
            label("Выбор применяется к следующему подключению. Текущее соединение не прерывается.", 12f, T.MUTED),
            lp(6)
        )
        col.addView(mode, lp(18))

        col.addView(buildBgCard(), lp(14))
        col.addView(buildFontCard(), lp(14))
        col.addView(buildExtrasCard(), lp(14))
        col.addView(buildAboutCard(), lp(14))

        // Telegram в самом конце
        val tg = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tg.addView(
            btn("✈ Telegram-канал", T.brand(this, 16), Color.WHITE) { openTelegram() },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        tg.addView(
            label("ZaleVPN\nby Xake_team", 12f, T.MUTED, true).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) }
        )
        col.addView(tg, lp(18))
        col.addView(
            btn("GitHub-репозиторий проекта", T.outline(this, 16, T.CYAN), T.CYAN) { openGithub() },
            lp(10)
        )
        col.addView(
            label("© 2026 Xake Team · $VERSION", 12f, T.MUTED).apply { gravity = Gravity.CENTER },
            lp(22)
        )

        settingsPage = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(col)
        }
    }

    private fun buildBgCard(): LinearLayout {
        val c = card()
        c.addView(caption("ФОН"))
        bgChecks.clear()
        for (i in T.palettes.indices) {
            val p = T.palettes[i]
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
                isClickable = true
                setOnClickListener { setBg(i) }
            }
            row.addView(
                View(this).apply { background = T.swatch(this@MainActivity, i) },
                LinearLayout.LayoutParams(dp(56), dp(34)).apply { rightMargin = dp(14) }
            )
            row.addView(label(p.name, 16f, T.TEXT, true), LinearLayout.LayoutParams(0, WRAP, 1f))
            val check = label(if (i == T.palette) "✓" else "", 18f, T.CYAN, true)
            bgChecks.add(check)
            row.addView(check)
            c.addView(row)
        }
        c.addView(label("Фон применяется сразу и сохраняется.", 12f, T.MUTED), lp(8))
        return c
    }

    private fun buildFontCard(): LinearLayout {
        val c = card()
        c.addView(caption("ШРИФТ"))
        fontChecks.clear()
        for (i in T.fontNames.indices) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                isClickable = true
                setOnClickListener { setFont(i) }
            }
            val preview = label("Aa", 20f, T.CYAN, true).apply {
                typeface = T.faceOf(i, true)
                tag = "preview"
            }
            row.addView(preview, LinearLayout.LayoutParams(dp(46), WRAP))
            row.addView(label(T.fontNames[i], 16f, T.TEXT), LinearLayout.LayoutParams(0, WRAP, 1f))
            val check = label(if (i == T.font) "✓" else "", 18f, T.CYAN, true)
            fontChecks.add(check)
            row.addView(check)
            c.addView(row)
        }
        c.addView(label("Шрифт применяется сразу ко всему интерфейсу.", 12f, T.MUTED), lp(8))
        return c
    }

    private fun buildExtrasCard(): LinearLayout {
        val c = card()
        c.addView(caption("ДОПОЛНИТЕЛЬНО"))
        c.addView(
            btn("Очистить историю чата", T.outline(this, 16, T.CARD_STROKE), T.TEXT) {
                Chat.history.clear()
                chatList.removeAllViews()
                bubbles.clear()
                chatEmpty.visibility = View.VISIBLE
                unread = 0
                updateTabs()
                toast("История чата очищена")
            },
            lp(10)
        )
        c.addView(
            btn("Разрешения и уведомления", T.outline(this, 16, T.CYAN), T.CYAN) { openNotificationSettings() },
            lp(10)
        )
        c.addView(
            btn("Политика конфиденциальности", T.outline(this, 16, T.TEXT), T.TEXT) {
                Privacy.show(this) {}
            },
            lp(10)
        )
        c.addView(
            btn("Поделиться приложением", T.brand(this, 16), Color.WHITE) {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "ZaleVPN $VERSION — виртуальная локальная сеть для игр с друзьями.\nTelegram: ${Urls.TELEGRAM}\nGitHub: ${Urls.GITHUB}"
                    )
                }
                startActivity(Intent.createChooser(i, "Поделиться ZaleVPN"))
            },
            lp(10)
        )
        return c
    }

    private fun buildAboutCard(): LinearLayout {
        val c = card()
        c.addView(caption("О ПРИЛОЖЕНИИ"))
        c.addView(label("ZaleVPN", 22f, T.TEXT, true), lp(6))
        c.addView(label("Версия $VERSION", 14f, T.CYAN, true).apply {
            isClickable = true
            setOnClickListener { onVersionTap() }
        })
        c.addView(label("by Xake_team", 12f, T.MUTED))
        c.addView(
            label("Виртуальная локальная сеть для игр с друзьями: P2P, WireGuard, чат и QR-подключение.", 13f, T.MUTED),
            lp(10)
        )
        return c
    }

    // ---------- пасхалка ----------
    private fun onVersionTap() {
        versionTaps++
        if (versionTaps >= 5) {
            versionTaps = 0
            showEasterEgg()
        } else if (versionTaps >= 3) {
            toast("Ещё ${5 - versionTaps}…")
        }
    }

    private fun showEasterEgg() {
        val code = "XAKE-2026"
        AlertDialog.Builder(this)
            .setTitle("🎉 Пасхалка найдена")
            .setMessage(
                "Xake Team был здесь 👾\n\n" +
                    "Секретный код: $code\n\n" +
                    "ZaleVPN — сеть для своих. Спасибо, что играешь с нами!"
            )
            .setPositiveButton("Круто") { _, _ -> }
            .setNeutralButton("Скопировать код") { _, _ -> copy(code, "Секретный код скопирован") }
            .show()
    }

    private fun setBg(i: Int) {
        T.setPalette(i)
        prefs.edit().putInt("bg", i).apply()
        root.background = T.bgGradient()
        window.statusBarColor = T.BG1
        window.navigationBarColor = T.BG2
        for (k in bgChecks.indices) bgChecks[k].text = if (k == i) "✓" else ""
        toast("Фон: ${T.palettes[i].name}")
    }

    private fun setFont(i: Int) {
        T.setFont(i)
        prefs.edit().putInt("font", i).apply()
        applyFonts(root)
        for (k in fontChecks.indices) fontChecks[k].text = if (k == i) "✓" else ""
        toast("Шрифт: ${T.fontNames[i]}")
    }

    private fun applyFonts(v: View) {
        if (v.tag == "mono" || v.tag == "preview") return
        if (v is TextView) {
            val bold = ((v.typeface?.style ?: 0) and Typeface.BOLD) != 0
            v.typeface = T.face(bold)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) applyFonts(v.getChildAt(i))
        }
    }

    private fun openTelegram() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Urls.TELEGRAM)))
        } catch (e: Exception) {
            toast("Не удалось открыть Telegram")
        }
    }

    private fun openGithub() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Urls.GITHUB)))
        } catch (e: Exception) {
            toast("Не удалось открыть GitHub")
        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            } catch (e2: Exception) {
                toast("Не удалось открыть настройки")
            }
        }
    }

    // ---------- вкладки ----------
    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(T.alpha(Color.BLACK, 90))
        }
        tabNet = label("🌐 Сеть", 14f, T.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(12), dp(4), dp(12))
            setOnClickListener { selectTab(0) }
        }
        tabChat = label("💬 Чат", 14f, T.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(12), dp(4), dp(12))
            setOnClickListener { selectTab(1) }
        }
        tabSettings = label("⚙ Настройки", 14f, T.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(12), dp(4), dp(12))
            setOnClickListener { selectTab(2) }
        }
        bar.addView(tabNet, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(5) })
        bar.addView(tabChat, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(5) })
        bar.addView(tabSettings, LinearLayout.LayoutParams(0, WRAP, 1f))
        updateTabs()
        return bar
    }

    private fun selectTab(index: Int) {
        tabIndex = index.coerceIn(0, 2)
        if (tabIndex == 1) unread = 0
        netPage.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        chatPage.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        settingsPage.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        connectBar.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        updateTabs()
        if (tabIndex == 1) chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateTabs() {
        tabNet.background = if (tabIndex == 0) T.brand(this, 16) else null
        tabChat.background = if (tabIndex == 1) T.brand(this, 16) else null
        tabSettings.background = if (tabIndex == 2) T.brand(this, 16) else null
        tabNet.setTextColor(if (tabIndex == 0) Color.WHITE else T.MUTED)
        tabChat.setTextColor(if (tabIndex == 1) Color.WHITE else T.MUTED)
        tabSettings.setTextColor(if (tabIndex == 2) Color.WHITE else T.MUTED)
        tabChat.text = if (unread > 0) "💬 Чат ($unread)" else "💬 Чат"
    }

    // ---------- действия ----------
    private fun nick(): String {
        val n = nickInput.text.toString().trim().ifEmpty { "Игрок" }.take(16)
        prefs.edit().putString("nick", n).apply()
        return n
    }

    private fun requestVpn(action: () -> Unit) {
        // Android 13+: без разрешения на уведомления foreground-служба работает,
        // но её уведомление не видно в шторке. Спрашиваем один раз.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notifAsked", false)
        ) {
            prefs.edit().putBoolean("notifAsked", true).apply()
            pendingPerm = action
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            return
        }
        prepareVpn(action)
    }

    private fun prepareVpn(action: () -> Unit) {
        val i = VpnService.prepare(this)
        if (i != null) {
            pendingVpn = action
            startActivityForResult(i, 1)
        } else {
            action()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) toast("Без уведомлений статус сети не будет виден в шторке")
            val a = pendingPerm
            pendingPerm = null
            if (a != null) prepareVpn(a)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) {
            val a = pendingVpn
            pendingVpn = null
            if (resultCode == RESULT_OK && a != null) a() else toast("Нужно разрешить VPN-подключение")
        }
        if (requestCode == REQ_FILE && resultCode == RESULT_OK) {
            val clip = data?.clipData
            if (clip != null) {
                for (k in 0 until clip.itemCount) {
                    val uri = clip.getItemAt(k).uri ?: continue
                    Chat.sendFile(uri)
                }
            } else {
                val uri = data?.data ?: return
                Chat.sendFile(uri)
            }
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
