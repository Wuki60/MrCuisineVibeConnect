package app.toque

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.keyboard.KeyboardJob
import app.toque.keyboard.Keyboards
import app.toque.machine.MachineService
import app.toque.ui.MainActivity
import app.toque.ui.ReturnBubble
import app.toque.ui.Ui
import app.toque.ui.screens.SettingsScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.File

/**
 * Réglages › Langue et heure, groupe « Clavier » : les claviers installés sur le robot, celui d'Android 6 d'abord.
 * Chacun ouvre ses propres réglages, tels quels, sans le bouton « Retour à Toque » (ils ont leur flèche de
 * retour). S'il y en a plusieurs, « Utiliser » fait de l'un d'eux le clavier du robot, par root. Toque n'installe
 * aucun clavier. Les essais n'ont pas root : ils remplacent ses commandes ([Keyboards.shell]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi", application = ScreenshotApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardChoiceTest {
    private val outDir = File(System.getProperty("toque.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private lateinit var activity: MainActivity
    private var activityController: ActivityController<MainActivity>? = null
    private var serviceController: ServiceController<MachineService>? = null

    private val stock = InputMethodInfo("com.android.inputmethod.latin", "com.android.inputmethod.latin.LatinIME",
        "Clavier Android (AOSP)", "com.android.inputmethod.latin.settings.SettingsActivity")
    private val heli = InputMethodInfo("helium314.keyboard", "helium314.keyboard.latin.LatinIME",
        "HeliBoard", "helium314.keyboard.settings.SettingsActivity")

    /** Les commandes root que Toque a lancées, dans l'ordre. */
    private val commands = ArrayList<String>()
    private var root = true
    /** Fermée, elle retient le changement de clavier : de quoi voir la page pendant qu'il se fait. */
    private var gate: java.util.concurrent.CountDownLatch? = null
    private val realShell = Keyboards.shell

    private val app get() = RuntimeEnvironment.getApplication() as ScreenshotApp
    private val pm get() = shadowOf(app.packageManager)
    private val imm get() = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    @Before fun fakes() {
        Keyboards.shell = { command, _ -> shell(command) }
    }

    @After fun tearDown() {
        // Une tâche lancée par l'essai va au bout avant le suivant : il n'y en a qu'une à la fois.
        val end = System.currentTimeMillis() + 10_000
        while (KeyboardJob.running && System.currentTimeMillis() < end) idle()
        KeyboardJob.onChange = null
        Keyboards.shell = realShell
        ShadowSettings.setCanDrawOverlays(false)
        I18n.select("fr")
        try { activityController?.pause()?.stop()?.destroy() } catch (_: Throwable) {}
        try { serviceController?.destroy() } catch (_: Throwable) {}
        activityController = null
        serviceController = null
        idle(2)
    }

    /** Le robot vu par les commandes root : `ime` change le clavier d'Android. */
    private fun shell(command: String): RootShell.Result {
        synchronized(commands) { commands += command }
        if (!root) return RootShell.Result(1, "", "su: permission denied")
        return when {
            command == "id -u" -> RootShell.Result(0, "0\n", "")
            command.startsWith("ime enable ") -> {
                gate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                val id = Regex("ime set '([^']+)'").find(command)!!.groupValues[1]
                Settings.Secure.putString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, id)
                RootShell.Result(0, "Input method $id selected\n", "")
            }
            else -> RootShell.Result(0, "", "")
        }
    }

    private fun ran(): List<String> = synchronized(commands) { commands.toList() }

    private fun installed(k: InputMethodInfo, system: Boolean) {
        pm.installPackage(PackageInfo().apply {
            packageName = k.packageName
            applicationInfo = ApplicationInfo().apply {
                packageName = k.packageName
                flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            }
        })
        pm.addOrUpdateActivity(ActivityInfo().apply {
            packageName = k.packageName
            name = k.settingsActivity
            exported = true
        })
        // Son écran de réglages se déclare ouvrable (action MAIN), comme ceux du clavier d'Android et de HeliBoard.
        pm.addIntentFilterForActivity(ComponentName(k.packageName, k.settingsActivity), android.content.IntentFilter(Intent.ACTION_MAIN))
    }

    private fun keyboards(vararg list: InputMethodInfo) {
        shadowOf(imm).setInputMethodInfoList(list.toList())
        shadowOf(imm).setEnabledInputMethodInfoList(list.toList())
    }

    private fun using(k: InputMethodInfo) {
        Settings.Secure.putString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, k.id)
    }

    private val usedNow get() = Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    private fun idle(rounds: Int = 1) {
        repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            assertTrue("attente trop longue : $what", System.currentTimeMillis() < end)
            idle()
            Thread.sleep(30)
        }
    }

    private fun layout() {
        idle(2)
        val root = activity.window.decorView
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1024, 600)
            root.viewTreeObserver.dispatchOnGlobalLayout()
            idle()
        }
    }

    private fun shot(name: String) {
        layout()
        val bmp = Bitmap.createBitmap(1024, 600, Bitmap.Config.ARGB_8888)
        activity.window.decorView.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun start() {
        val service = Robolectric.buildService(MachineService::class.java).create().also { serviceController = it }.get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MachineService::class.java),
            service.onBind(Intent(app, MachineService::class.java)))
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { activityController = it }.get()
        waitFor("base simulée prête") { app.machine.snapshot.ready }
    }

    private val window get() = activity.window.decorView

    private fun texts(v: View, out: MutableList<TextView> = ArrayList()): List<TextView> {
        if (v is TextView) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun shown(v: View?): Boolean {
        var x: View? = v ?: return false
        while (x != null) {
            if (x.visibility != View.VISIBLE) return false
            x = x.parent as? View
        }
        return true
    }

    /** Les textes exactement égaux à [text] affichés dans [root], de haut en bas. */
    private fun seen(text: String, root: View = window): List<TextView> = texts(root).filter { it.text.toString() == text && shown(it) }

    private fun onScreen(text: String, root: View = window) = seen(text, root).isNotEmpty()

    /** Appuie sur le bouton qui porte [text] : le seul à l'écran, ou celui de rang [index]. */
    private fun click(text: String, index: Int = -1, root: View = window) {
        val found = seen(text, root)
        if (index < 0) assertEquals("« $text » à l'écran", 1, found.size) else assertTrue("« $text » n° $index absent", found.size > index)
        var v: View = found[if (index < 0) 0 else index]
        while (!v.isClickable) v = v.parent as View
        assertTrue("« $text » ne répond pas", v.isEnabled)
        v.performClick()
        idle(3)
    }

    private fun assertNothingCut(v: View, where: String) {
        if (v.visibility != View.VISIBLE) return
        val parent = v.parent as? View
        if (parent != null && parent !is ScrollView && parent !is HorizontalScrollView) {
            assertTrue("$where : ${describe(v)} dépasse à droite (${v.right} > ${parent.width})", v.right <= parent.width)
            assertTrue("$where : ${describe(v)} dépasse en bas (${v.bottom} > ${parent.height})", v.bottom <= parent.height)
        }
        if (v is TextView && v !is EditText && v.text.isNotEmpty()) {
            val l = v.layout
            assertNotNull("$where : ${describe(v)} sans mise en page", l)
            assertTrue("$where : ${describe(v)} coupé", l.height <= v.height - v.paddingTop - v.paddingBottom)
            for (line in 0 until l.lineCount) assertEquals("$where : ${describe(v)} abrégé", 0, l.getEllipsisCount(line))
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) assertNothingCut(v.getChildAt(i), where)
    }

    private fun describe(v: View) = if (v is TextView) "« ${v.text} »" else v.javaClass.simpleName

    /** [t] est sur la ligne du clavier [name] : juste sous son nom. */
    private fun underTheNameOf(t: TextView, name: String): Boolean =
        texts(t.parent as View).any { it.text.toString() == name }

    /** Les services que Toque a lancés depuis le dernier appel. */
    private fun startedServices(): List<String> {
        val out = ArrayList<String>()
        while (true) out += shadowOf(app).nextStartedService?.component?.className ?: break
        return out
    }

    /** Ouvre Réglages › Langue et heure. */
    private fun openLanguageAndTime(): SettingsScreen {
        val settings = SettingsScreen(activity)
        activity.push(settings)
        idle(3)
        click("Langue et heure")
        layout()
        return settings
    }

    private fun top(v: View): Int = IntArray(2).also { v.getLocationOnScreen(it) }[1]

    /** La ligne du clavier [name] : son nom à gauche, ses boutons à droite. */
    private fun rowOf(name: String, root: View): View = seen(name, root).single().parent.parent as View

    /** Les deux claviers sont listés, celui du robot d'abord ; « Utiliser » change le clavier d'Android et la ligne le dit. */
    @Test fun theKeyboardIsChosenInLanguageAndTime() {
        start()
        installed(stock, system = true)
        installed(heli, system = false)
        keyboards(heli, stock)
        using(heli)

        val settings = openLanguageAndTime()
        val pane = settings.pane
        assertTrue("le groupe « Clavier »", onScreen("Clavier", pane))
        assertTrue("le clavier du robot en premier", top(seen("Clavier Android (AOSP)", pane).single()) < top(seen("HeliBoard", pane).single()))
        assertTrue("entre la langue et l'heure", top(seen("Langue · Language", pane).single()) < top(seen("Clavier", pane).single()) &&
            top(seen("HeliBoard", pane).single()) < top(seen("Date et heure", pane).single()))
        assertTrue("« Utilisé » sur le clavier en service", seen("Utilisé", rowOf("HeliBoard", pane)).size == 1)
        assertTrue("« Utiliser » sur l'autre", seen("Utiliser", rowOf("Clavier Android (AOSP)", pane)).size == 1)
        assertEquals(1, seen("Utilisé", pane).size)
        assertEquals(1, seen("Utiliser", pane).size)
        assertEquals("chaque clavier ouvre ses réglages", 2, seen("Réglages", pane).size)
        assertFalse("Langue et heure tient à l'écran sans défiler", pane.canScrollVertically(1))
        assertNothingCut(settings.view, "Langue et heure")
        for (b in listOf("Utiliser", "Réglages")) for (t in seen(b, pane)) {
            var v: View = t
            while (!v.isClickable) v = v.parent as View
            assertTrue("« $b » : ${v.height} px", v.height >= Ui.u(48))
        }
        assertTrue("rien en root pour afficher la page : ${ran()}", ran().isEmpty())
        shot("56b-reglages-langue-et-heure-clavier")

        // « Utiliser » : pendant le changement, la ligne de ce clavier le dit et son bouton attend.
        gate = java.util.concurrent.CountDownLatch(1)
        click("Utiliser")
        val changing = seen("Changement de clavier…", pane)
        assertEquals("dit une fois", 1, changing.size)
        assertTrue("sur la ligne du clavier choisi", underTheNameOf(changing.single(), "Clavier Android (AOSP)"))
        assertEquals(Ui.ACCENT_INK, changing.single().currentTextColor)
        var use: View = seen("Utiliser", pane).single()
        while (!use.isClickable) use = use.parent as View
        assertFalse("le bouton attend", use.isEnabled)
        layout()
        assertNothingCut(settings.view, "Langue et heure, changement en cours")
        shot("56c-reglages-clavier-changement")
        assertEquals("pas encore changé", heli.id, usedNow)
        gate!!.countDown()
        // Android change de clavier, la page et un bandeau le disent.
        waitFor("le clavier d'origine en service") { usedNow == stock.id && onScreen("Clavier du robot : Clavier Android (AOSP)") }
        assertFalse(onScreen("Changement de clavier…", pane))
        assertEquals(ran().toString(), listOf("id -u", "ime enable '${stock.id}'; ime set '${stock.id}'"), ran())
        layout()
        assertTrue("« Utilisé » est passé sur le clavier du robot", seen("Utilisé", rowOf("Clavier Android (AOSP)", pane)).size == 1)
        assertTrue(seen("Utiliser", rowOf("HeliBoard", pane)).size == 1)
        assertEquals(1, seen("Utilisé", pane).size)
        shot("56d-reglages-clavier-d-origine")
    }

    /** Sans root, rien ne change et la ligne du clavier demandé dit pourquoi ; son bouton répond de nouveau. */
    @Test fun withoutRootTheKeyboardStaysAndItsRowSaysWhy() {
        start()
        installed(stock, system = true)
        installed(heli, system = false)
        keyboards(stock, heli)
        using(heli)
        root = false
        val settings = openLanguageAndTime()
        val pane = settings.pane
        click("Utiliser")
        val why = "Cette action demande l'accès root (Magisk)."
        waitFor("le refus affiché") { onScreen(why, pane) }
        assertEquals(Ui.WARN, seen(why, pane).single().currentTextColor)
        assertTrue("sur la ligne du clavier demandé", underTheNameOf(seen(why, pane).single(), "Clavier Android (AOSP)"))
        assertEquals(heli.id, usedNow)
        assertFalse(ran().any { it.startsWith("ime ") })
        assertTrue("« Utilisé » reste où il était", seen("Utilisé", rowOf("HeliBoard", pane)).size == 1)
        layout()
        assertNothingCut(settings.view, "Langue et heure, sans root")
        shot("56e-reglages-clavier-sans-root")
        // Le bouton répond de nouveau.
        click("Utiliser")
        waitFor("un second essai") { ran().count { it == "id -u" } == 2 }
        // Les réglages rouverts repartent sans le message.
        waitFor("fin du second essai") { !KeyboardJob.running }
        activity.back()
        idle(2)
        val again = openLanguageAndTime()
        assertFalse(onScreen(why, again.pane))
    }

    /**
     * « Réglages » ouvre l'écran du clavier, qui a sa propre flèche de retour et se referme sur Toque : il
     * s'ouvre tel quel, sans le bouton « Retour à Toque » (tant que ce bouton est affiché, Android 6 refuse les
     * demandes de permission des autres applis) et sans rien demander en root.
     */
    @Test fun aKeyboardsSettingsOpenFromItsRow() {
        start()
        installed(stock, system = true)
        installed(heli, system = false)
        keyboards(stock, heli)
        using(heli)
        // Le droit d'afficher par-dessus les autres applis, même accordé, ne sert pas ici.
        ShadowSettings.setCanDrawOverlays(true)
        val settings = openLanguageAndTime()
        while (shadowOf(activity).nextStartedActivity != null) { /* rien d'ouvert jusqu'ici ne compte */ }
        startedServices()
        for ((name, screen) in listOf(
            "Clavier Android (AOSP)" to ComponentName(stock.packageName, stock.settingsActivity),
            "HeliBoard" to ComponentName(heli.packageName, heli.settingsActivity),
        )) {
            click("Réglages", root = rowOf(name, settings.pane))
            idle(3)
            assertEquals(screen, shadowOf(activity).nextStartedActivity?.component)
            assertFalse(app.prefs.returnBubble)
            assertFalse("pas de bouton « Retour à Toque »", ReturnBubble::class.java.name in startedServices())
            assertTrue("rien en root : ${ran()}", ran().isEmpty())
        }
    }

    /** Un seul clavier, celui du robot : rien à choisir, ses réglages restent à un appui. */
    @Test fun aRobotWithItsOwnKeyboardOnlyOffersItsSettings() {
        start()
        installed(stock, system = true)
        keyboards(stock)
        using(stock)
        val settings = openLanguageAndTime()
        val pane = settings.pane
        assertTrue(onScreen("Clavier", pane))
        assertTrue(onScreen("Clavier Android (AOSP)", pane))
        assertFalse(onScreen("Utilisé", pane))
        assertFalse(onScreen("Utiliser", pane))
        assertEquals(1, seen("Réglages", pane).size)
        assertFalse(pane.canScrollVertically(1))
        assertNothingCut(settings.view, "Langue et heure, un seul clavier")
        shot("56f-reglages-clavier-seul")
        while (shadowOf(activity).nextStartedActivity != null) { /* rien d'ouvert jusqu'ici ne compte */ }
        click("Réglages", root = pane)
        idle(3)
        assertEquals(ComponentName(stock.packageName, stock.settingsActivity), shadowOf(activity).nextStartedActivity?.component)
        assertTrue("rien en root : ${ran()}", ran().isEmpty())
    }

    /** Android ne liste aucun clavier (les autres essais) : la page reste comme avant, sans groupe « Clavier ». */
    @Test fun withNoKeyboardListedThePageHasNoKeyboardGroup() {
        start()
        keyboards()
        val settings = openLanguageAndTime()
        assertFalse(onScreen("Clavier", settings.pane))
        assertFalse(onScreen("Réglages", settings.pane))
        assertTrue(onScreen("Date et heure", settings.pane))
    }

    /** Dans les autres langues, aux mots plus longs : la page tient, rien n'est coupé. */
    @Test fun theKeyboardGroupInOtherLanguages() {
        start()
        installed(stock, system = true)
        installed(heli, system = false)
        keyboards(stock, heli)
        using(heli)
        for (lang in listOf("de", "uk", "es", "ca", "en", "pl")) {
            I18n.select(lang)
            val settings = SettingsScreen(activity)
            activity.push(settings)
            idle(3)
            click(tr("Langue et heure"))
            layout()
            assertEquals("$lang : deux claviers, deux « Réglages »", 2, seen(tr("Réglages"), settings.pane).size)
            assertNothingCut(settings.view, "Langue et heure ($lang)")
            assertFalse("Langue et heure ($lang) tient à l'écran sans défiler", settings.pane.canScrollVertically(1))
            if (lang == "de") shot("56g-reglages-clavier-allemand")
            activity.back()
            idle(2)
        }
    }
}
