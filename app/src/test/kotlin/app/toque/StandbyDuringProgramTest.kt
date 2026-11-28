package app.toque

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import app.toque.core.i18n.I18n
import app.toque.core.machine.JobState
import app.toque.machine.MachineService
import app.toque.ui.MainActivity
import app.toque.ui.Standby
import app.toque.ui.screens.ManualScreen
import app.toque.ui.screens.SettingsScreen
import app.toque.ui.screens.StandbyScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import java.io.File
import java.time.Duration

/**
 * L'écran pendant un programme. Sans rien régler, un programme qui tourne garde l'écran allumé en plein, comme
 * toujours. Au choix (Réglages › Écran › Veille de l'écran), sans geste : l'écran baisse d'abord sa luminosité,
 * sans rien cacher (un appui agit tout de suite) ; puis il passe au noir comme en veille, avec l'étape et son
 * décompte à la place de l'heure. Une pause, la fin ou un geste rallument.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w1024dp-h600dp-land-mdpi", application = ScreenshotApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StandbyDuringProgramTest {
    private val outDir = File(System.getProperty("toque.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private lateinit var activity: MainActivity
    private var activityController: ActivityController<MainActivity>? = null
    private var serviceController: ServiceController<MachineService>? = null

    private val app get() = RuntimeEnvironment.getApplication() as ScreenshotApp

    @After fun tearDown() {
        try { app.machine.stop() } catch (_: Throwable) {}
        try { activityController?.pause()?.stop()?.destroy() } catch (_: Throwable) {}
        try { serviceController?.destroy() } catch (_: Throwable) {}
        idle(2)
        I18n.select("fr")
    }

    private fun idle(rounds: Int = 1) {
        repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Le temps passe (horloge des essais) : la veille le compte. */
    private fun pass(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        idle()
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            assertTrue("attente trop longue : $what", System.currentTimeMillis() < end)
            idle()
            Thread.sleep(30)
        }
    }

    private fun start() {
        val service = Robolectric.buildService(MachineService::class.java).create().also { serviceController = it }.get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MachineService::class.java),
            service.onBind(Intent(app, MachineService::class.java)))
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { activityController = it }.get()
        waitFor("base simulée prête") { app.machine.snapshot.ready }
        pass(1_000)
    }

    private val decor: View get() = activity.window.decorView

    private fun texts(v: View, out: MutableList<TextView> = ArrayList()): List<TextView> {
        if (v is TextView) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun shown(v: View): Boolean {
        var x: View? = v
        while (x != null) {
            if (x.visibility != View.VISIBLE) return false
            x = x.parent as? View
        }
        return true
    }

    private fun seen(text: String, root: View = decor) = texts(root).filter { it.text.toString() == text && shown(it) }

    private fun click(text: String, root: View = decor) {
        var v: View = seen(text, root).single()
        while (!v.isClickable) v = v.parent as View
        v.performClick()
        idle(2)
    }

    private fun touch() {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 300f, 0)
        val up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, 10f, 300f, 0)
        activity.dispatchTouchEvent(down)
        activity.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        idle(2)
    }

    private fun shot(name: String) {
        idle(2)
        decor.measure(View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1024, 600)
        val bmp = Bitmap.createBitmap(1024, 600, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val brightness get() = activity.window.attributes.screenBrightness
    private val full = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    /** Une cuisson manuelle lancée sur la base simulée : elle tourne (une minute en temps réel, bien plus que l'essai). */
    private fun startCooking(): ManualScreen {
        val manual = ManualScreen(activity)
        activity.push(manual)
        idle(3)
        assertTrue(manual.onKnobPress())
        waitFor("en cours") { app.machine.snapshot.state == JobState.RUNNING }
        idle(3)
        return manual
    }

    /** Les grands chiffres de l'écran de veille : l'heure au repos, le décompte pendant un programme. */
    private fun bigDigits(): TextView = texts(decor).filter { shown(it) && it.textSize >= 140f }.single()

    @Test fun byDefaultARunningProgramKeepsTheScreenFullyOn() {
        start()
        app.prefs.standbyMinutes = 2
        assertEquals(0, app.prefs.jobDimMinutes)
        assertEquals(0, app.prefs.jobStandbyMinutes)
        startCooking()
        pass(40 * 60_000L)
        assertFalse("un programme tourne : pas de veille", activity.asleep)
        assertEquals(full, brightness, 0.001f)
    }

    /** D'abord la luminosité baisse, rien n'est caché, et un appui agit tout de suite. */
    @Test fun theScreenDimsDuringAProgramAndATouchStillActs() {
        start()
        app.prefs.standbyMinutes = 30
        app.prefs.jobDimMinutes = 2
        app.prefs.jobDimBrightness = 20
        val manual = startCooking()
        pass(60_000L)
        assertEquals("pas avant le délai", full, brightness, 0.001f)
        pass(90_000L)
        assertEquals("luminosité réduite", 0.20f, brightness, 0.001f)
        assertFalse("l'écran reste affiché", activity.asleep)
        assertEquals(JobState.RUNNING, app.machine.snapshot.state)
        shot("42-programme-luminosite-reduite")

        // Un appui sur Stop, dans la barre du bas : il arrête tout de suite, et la luminosité revient.
        val stop = texts(decor).first { shown(it) && it.text.toString() == "En cours" }.let { state ->
            var bar: View = state
            while (bar.parent is View && bar !is app.toque.ui.MachineBar) bar = bar.parent as View
            var found: View? = null
            fun walk(v: View) {
                if (found == null && v.hasOnClickListeners() && shown(v)) {
                    val xy = IntArray(2); v.getLocationOnScreen(xy)
                    if (xy[0] > 880) found = v
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(bar)
            found!!
        }
        val xy = IntArray(2)
        stop.getLocationOnScreen(xy)
        val t = SystemClock.uptimeMillis()
        for (e in listOf(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, xy[0] + stop.width / 2f, xy[1] + stop.height / 2f, 0),
            MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, xy[0] + stop.width / 2f, xy[1] + stop.height / 2f, 0))) {
            activity.dispatchTouchEvent(e)
            e.recycle()
        }
        idle(2)
        assertEquals("un geste rétablit la luminosité", full, brightness, 0.001f)
        waitFor("arrêté du premier appui") { app.machine.snapshot.state == JobState.ABORTED }
        assertNotNull(manual)
    }

    /** Puis l'écran passe au noir comme en veille : l'étape et son décompte à la place de l'heure. */
    @Test fun theStandbyScreenOfAProgramShowsItsStepAndCountdownInsteadOfTheClock() {
        start()
        app.prefs.standbyMinutes = 30
        app.prefs.jobDimMinutes = 1
        app.prefs.jobDimBrightness = 35
        app.prefs.jobStandbyMinutes = 5
        startCooking()
        pass(90_000L)
        assertEquals(0.35f, brightness, 0.001f)
        assertFalse(activity.asleep)
        pass(4 * 60_000L)
        assertTrue("écran de veille pendant le programme", activity.asleep)
        assertEquals(JobState.RUNNING, app.machine.snapshot.state)
        assertEquals("au plus bas, comme la veille", Standby.level(0), brightness, 0.001f)
        // En grand, le décompte et non l'heure ; au-dessus, l'étape (ici le nom du programme).
        val digits = bigDigits()
        assertTrue("décompte : « ${digits.text} »", Regex("\\d\\d:\\d\\d").matches(digits.text))
        assertFalse("l'heure n'est plus affichée", digits.text.toString() == MainActivity.now())
        val step = seen("Cuisson manuelle").filter { it.parent === digits.parent }.single()
        assertTrue("l'étape au-dessus du décompte", step.bottom <= digits.top)
        assertEquals(1, seen("Touchez l'écran pour reprendre").size)
        shot("42b-veille-pendant-un-programme")

        // Le premier geste ne fait que rallumer : le programme tourne toujours.
        touch()
        assertFalse(activity.asleep)
        assertEquals(full, brightness, 0.001f)
        assertEquals(JobState.RUNNING, app.machine.snapshot.state)

        // De nouveau au noir ; une pause rallume d'elle-même, et l'écran reste alors allumé.
        pass(6 * 60_000L)
        assertTrue(activity.asleep)
        app.machine.pause { }
        waitFor("en pause") { app.machine.snapshot.state == JobState.PAUSED }
        idle(3)
        assertFalse("une pause rallume l'écran", activity.asleep)
        assertEquals(full, brightness, 0.001f)
        pass(20 * 60_000L)
        assertFalse("en pause, l'écran reste allumé", activity.asleep)
        assertEquals(full, brightness, 0.001f)
    }

    @Test fun theEndOfTheProgramTurnsTheScreenBackOn() {
        start()
        app.prefs.standbyMinutes = 30
        app.prefs.jobStandbyMinutes = 2
        startCooking()
        pass(150_000L)
        assertTrue("écran de veille sans passer par la luminosité réduite", activity.asleep)
        app.machine.stop()
        waitFor("arrêté") { app.machine.snapshot.state == JobState.ABORTED }
        idle(3)
        assertFalse("la fin du programme rallume l'écran", activity.asleep)
        assertEquals(full, brightness, 0.001f)
    }

    /** Au repos, rien ne change : la veille montre l'heure. */
    @Test fun atRestTheStandbyStillShowsTheClock() {
        start()
        app.prefs.standbyMinutes = 2
        app.prefs.jobDimMinutes = 1
        app.prefs.jobStandbyMinutes = 2
        pass(90_000L)
        assertEquals("pas de luminosité réduite au repos", full, brightness, 0.001f)
        pass(60_000L)
        assertTrue(activity.asleep)
        assertEquals(MainActivity.now(), bigDigits().text.toString())
        assertTrue(seen("Cuisson manuelle").isEmpty())
    }

    /**
     * Réglages › Écran › Veille de l'écran : deux blocs encadrés, « Au repos » et « Pendant un programme » ; le
     * même réglage y porte le même nom, avec en quelques mots ce qu'on verra. La page entière tient à l'écran.
     */
    @Test fun theStandbySettingsHaveTheirOwnScreen() {
        start()
        val settings = SettingsScreen(activity)
        activity.push(settings)
        idle(3)
        click("Écran")
        // Sous le lien, ce qui est réglé : une situation par ligne.
        assertEquals(1, seen("Au repos : après 10 min\nPendant un programme : jamais", settings.view).size)
        click("Veille de l'écran")
        val page = activity.current as StandbyScreen
        assertEquals("Veille de l'écran", page.title)

        fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        // La page se redessine à chaque choix : un bloc se cherche au moment de s'en servir.
        fun page() = activity.current as StandbyScreen
        fun block(title: String) = all(page().view).single { it.contentDescription?.toString() == title }
        fun rowOf(title: String, label: String) = seen(label, block(title)).single().parent.parent as View
        for (title in listOf("Au repos", "Pendant un programme")) {
            assertEquals("titre du bloc « $title »", 1, seen(title, block(title)).size)
            assertEquals("« $title » : la mise en veille", 1, seen("Mise en veille après", block(title)).size)
        }
        assertTrue("« Au repos » au-dessus de « Pendant un programme »", block("Au repos").bottom <= block("Pendant un programme").top)
        assertEquals(1, seen("Écran noir avec l'heure.", block("Au repos")).size)
        assertEquals(1, seen("Écran noir avec l'étape et le temps restant.", block("Pendant un programme")).size)
        assertEquals(1, seen("L'écran reste affiché, moins lumineux.", block("Pendant un programme")).size)
        // Tant que la luminosité ne baisse pas, son niveau n'est pas proposé.
        assertTrue(seen("Luminosité réduite", page.view).isEmpty())
        // Les paragraphes d'avant ont laissé la place à une phrase par réglage et une ligne au bas de la page.
        val sentences = texts(page.view).filter { shown(it) && it.text.length > 60 }
        assertEquals(sentences.joinToString { it.text }, 1, sentences.size)
        assertTrue("au bas de la page", sentences.single().top >= block("Pendant un programme").bottom)
        shot("57b-reglages-veille-de-l-ecran-au-depart")

        click("5 min", rowOf("Au repos", "Mise en veille après"))
        assertEquals(5, app.prefs.standbyMinutes)
        assertEquals("le choix au repos ne touche pas celui d'un programme", 0, app.prefs.jobStandbyMinutes)
        click("2 min", rowOf("Pendant un programme", "Baisser la luminosité après"))
        assertEquals(2, app.prefs.jobDimMinutes)
        // Une luminosité choisie se montre un instant, puis l'écran reprend la sienne.
        click("35 %", rowOf("Pendant un programme", "Luminosité réduite"))
        assertEquals(35, app.prefs.jobDimBrightness)
        assertEquals(0.35f, brightness, 0.001f)
        pass(StandbyScreen.PREVIEW_MS + 100)
        assertEquals(full, brightness, 0.001f)
        // Pas encore de mise en veille pendant un programme : les réglages disent la luminosité qui baisse.
        activity.back()
        idle(3)
        assertEquals(1, seen("Au repos : après 5 min\nPendant un programme : luminosité réduite", settings.view).size)
        shot("50c-reglages-ecran-veille")
        click("Veille de l'écran")
        click("15 min", rowOf("Pendant un programme", "Mise en veille après"))
        assertEquals(15, app.prefs.jobStandbyMinutes)
        assertEquals(5, app.prefs.standbyMinutes)
        // Les pastilles ont toutes la même largeur : alignées à droite, leurs colonnes tombent les unes sous les autres.
        val pills = all(page().view).filter { it is TextView && it.isClickable && shown(it) }
        assertEquals("quatre réglages : 6 + 5 + 5 + 5 choix", 21, pills.size)
        assertEquals(pills.map { (it as TextView).text to it.width }.toString(), 1, pills.map { it.width }.distinct().size)
        val rights = pills.groupBy { v -> IntArray(2).also { v.getLocationOnScreen(it) }[1] }.values.map { line -> line.map { v -> IntArray(2).also { v.getLocationOnScreen(it) }[0] + v.width }.sorted() }
        assertEquals(4, rights.size)
        for (line in rights) assertEquals("colonnes alignées", rights.first().takeLast(5), line.takeLast(5))
        shot("57-reglages-veille-de-l-ecran")

        // La page tient à l'écran, rien n'est coupé, y compris dans les langues aux mots longs.
        for (lang in listOf("fr", "de", "uk", "es", "ca", "en")) {
            I18n.select(lang)
            val p = StandbyScreen(activity)
            activity.push(p)
            idle(3)
            decor.measure(View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 1024, 600)
            assertFalse("$lang : la page défile", p.view.canScrollVertically(1))
            for (t in texts(p.view)) if (shown(t) && t.text.isNotEmpty()) {
                assertNotNull("$lang : « ${t.text} » sans mise en page", t.layout)
                assertTrue("$lang : « ${t.text} » dépasse à droite", t.right <= (t.parent as View).width)
                for (line in 0 until t.layout.lineCount) assertEquals("$lang : « ${t.text} » abrégé", 0, t.layout.getEllipsisCount(line))
            }
            if (lang == "de") shot("57c-reglages-veille-allemand")
            activity.back()
            idle(2)
        }
        I18n.select("fr")

        // De retour dans les réglages : la page Écran dit ce qui est réglé.
        activity.back()
        idle(3)
        assertEquals(1, seen("Au repos : après 5 min\nPendant un programme : après 15 min", settings.view).size)
    }
}
