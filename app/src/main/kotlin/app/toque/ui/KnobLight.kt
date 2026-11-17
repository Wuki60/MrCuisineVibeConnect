package app.toque.ui

import app.toque.core.machine.RootShell
import java.util.concurrent.Executors

/**
 * Lumière de la molette pendant la veille : une veilleuse blanche, faible et fixe.
 *
 * L'anneau a trois LED, vues par Android sous /sys/class/leds : « red » (rouge), « green » (vert)
 * et « blue », qui allume en fait du blanc (SKMC 1200 C3 ; pas de LED bleue). Une intensité fixe
 * est tenue sans défaut ; des changements fréquents (une respiration) rendent l'anneau irrégulier
 * au bout d'un cycle, d'où une veilleuse plutôt qu'une respiration.
 *
 * L'état de l'anneau est noté avant la veille (une seule fois, même si la veille est relancée) et
 * remis au réveil.
 */
object KnobLight {
    private const val LEDS = "/sys/class/leds"
    private const val SAVED = "/data/local/tmp/toque-knob-light.saved"
    const val NIGHT_LEVEL = 3

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-molette").apply { isDaemon = true } }
    @Volatile private var dimmed = false

    fun start() {
        if (dimmed) return
        dimmed = true
        worker.execute {
            RootShell.run(
                """
                L=$LEDS
                [ -f $SAVED ] || echo "${'$'}(cat ${'$'}L/red/brightness) ${'$'}(cat ${'$'}L/green/brightness) ${'$'}(cat ${'$'}L/blue/brightness)" > $SAVED
                echo 0 > ${'$'}L/red/brightness; echo 0 > ${'$'}L/green/brightness; echo $NIGHT_LEVEL > ${'$'}L/blue/brightness
                """.trimIndent(),
                8_000,
            )
        }
    }

    fun stop() {
        if (!dimmed) return
        dimmed = false
        worker.execute {
            RootShell.run(
                """
                L=$LEDS
                if [ -f $SAVED ]; then
                  read R G W < $SAVED
                  echo ${'$'}R > ${'$'}L/red/brightness; echo ${'$'}G > ${'$'}L/green/brightness; echo ${'$'}W > ${'$'}L/blue/brightness
                  rm -f $SAVED
                fi
                true
                """.trimIndent(),
                8_000,
            )
        }
    }
}
