package app.toque.ui

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import app.toque.core.i18n.trKey
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sons synthétisés (aucun fichier audio) : bip de pesée et mélodie de fin de recette.
 *
 * Joués sur le flux « musique » : sur le robot, c'est le plus fort par défaut (14/15 contre 8/15
 * pour les notifications), pour que la fin de recette s'entende depuis l'autre bout de la cuisine.
 */
object Sounds {
    private const val RATE = 22_050
    private val player = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-son").apply { isDaemon = true } }

    /** Une note : fréquence (Hz), durée (ms), volume (0..1). Fréquence 0 = silence. */
    private class Note(val hz: Double, val ms: Int, val gain: Double = 1.0, val decay: Double = 3.0, val bell: Boolean = false)

    /** Sons proposés pour « quantité à peser atteinte » (code enregistré → libellé à traduire). */
    val WEIGH_SOUNDS = listOf(
        "ding" to trKey("Ding (cloche douce)"),
        "gong" to trKey("Gong (deux notes descendantes)"),
        "beep" to trKey("Bip de balance (long et grave)"),
        "blip" to trKey("Double bip aigu"),
    )

    /** Quantité à peser atteinte. */
    fun weighReached(kind: String) = play(
        when (kind) {
            "gong" -> listOf(Note(784.0, 450, 0.9, decay = 2.5), Note(523.3, 900, 1.0, decay = 2.5))
            "beep" -> listOf(Note(988.0, 400, 0.7, decay = 0.4))
            "blip" -> listOf(Note(1760.0, 90, 0.7), Note(0.0, 60), Note(1760.0, 90, 0.7))
            // Une note de cloche, longue et ronde : un « ding » de minuteur.
            else -> listOf(Note(659.3, 1100, 1.0, decay = 3.5, bell = true))
        },
    )

    /** Fin de recette : arpège montant joué deux fois, fort. */
    fun recipeDone() {
        val c6 = 1046.5; val e6 = 1318.5; val g6 = 1568.0; val c7 = 2093.0
        val phrase = listOf(Note(c6, 160), Note(e6, 160), Note(g6, 160), Note(c7, 420), Note(0.0, 260))
        play(phrase + phrase)
    }

    private fun play(notes: List<Note>) {
        player.execute {
            val pcm = render(notes)
            val track = try {
                @Suppress("DEPRECATION")
                AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, pcm.size * 2, AudioTrack.MODE_STATIC)
            } catch (_: RuntimeException) {
                return@execute
            }
            try {
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(notes.sumOf { it.ms }.toLong() + 100)
            } catch (_: Exception) {
            } finally {
                try { track.stop() } catch (_: IllegalStateException) {}
                track.release()
            }
        }
    }

    /** Sinus avec une attaque de 5 ms et une décroissance douce (son de « cloche »). */
    private fun render(notes: List<Note>): ShortArray {
        val out = ShortArray(notes.sumOf { it.ms } * RATE / 1000)
        var pos = 0
        for (n in notes) {
            val len = n.ms * RATE / 1000
            if (n.hz > 0) {
                val attack = RATE * 5 / 1000
                for (i in 0 until len) {
                    val t = i.toDouble() / RATE
                    val release = RATE * 20 / 1000
                    val env = (if (i < attack) i.toDouble() / attack else 1.0) * exp(-n.decay * i / len) *
                        (if (i > len - release) (len - i).toDouble() / release else 1.0)
                    val v = if (n.bell) {
                        // Cloche : partiels inharmoniques qui s'éteignent plus vite que la fondamentale.
                        val k = exp(-4.0 * i / len)
                        sin(2 * PI * n.hz * t) * 0.65 + sin(2 * PI * n.hz * 2.76 * t) * 0.25 * k + sin(2 * PI * n.hz * 5.4 * t) * 0.10 * k * k
                    } else {
                        // Fondamentale + une harmonique légère : plus présent sur un petit haut-parleur.
                        sin(2 * PI * n.hz * t) * 0.8 + sin(4 * PI * n.hz * t) * 0.2
                    }
                    out[pos + i] = (v * env * n.gain * 32_000).toInt().coerceIn(-32_767, 32_767).toShort()
                }
            }
            pos += len
        }
        return out
    }
}
