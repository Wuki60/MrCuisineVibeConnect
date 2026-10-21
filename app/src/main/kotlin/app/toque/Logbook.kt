package app.toque

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Journal texte borné (journal.txt, 256 Ko max), lisible dans Réglages > Journal. */
class Logbook(context: Context) {
    private val dir: File = context.filesDir
    private val file = File(dir, "journal.txt")
    private val old = File(dir, "journal.1.txt")
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)

    @Synchronized
    fun write(tag: String, line: String) {
        try {
            if (file.length() > 256 * 1024) {
                old.delete()
                file.renameTo(old)
            }
            file.appendText("${fmt.format(Date())} [$tag] $line\n")
        } catch (_: Throwable) {
        }
    }

    fun crash(tag: String, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        write(tag, "PLANTAGE : $sw")
    }

    fun read(maxChars: Int = 60_000): String {
        val text = (if (old.exists()) old.readText() else "") + (if (file.exists()) file.readText() else "")
        return if (text.length > maxChars) text.substring(text.length - maxChars) else text
    }

    fun clear() {
        file.delete()
        old.delete()
    }
}
