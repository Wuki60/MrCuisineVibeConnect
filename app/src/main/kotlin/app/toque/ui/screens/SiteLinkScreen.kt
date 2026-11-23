package app.toque.ui.screens

import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.site.ToqueSite
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Relier ce robot à un compte toqueapp.org : avec le code affiché sur le site (le plus simple), ou
 * avec l'e-mail et le mot de passe (plus le code de double authentification si le compte en a une).
 * Le mot de passe n'est pas gardé : le site rend une clé propre à ce robot.
 */
class SiteLinkScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Compte Toque")
    private lateinit var code: EditText
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var otp: EditText
    private lateinit var otpBox: LinearLayout
    private lateinit var codeBtn: View
    private lateinit var loginBtn: View
    private lateinit var codeMsg: TextView
    private lateinit var loginMsg: TextView
    private var working = false

    override fun build(): View {
        val col = Ui.vertical(c)
        col.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(24))
        col.addView(Ui.text(c, tr("Le compte est facultatif. Relié, le robot reçoit vos favoris du site et vous pouvez publier vos recettes depuis le robot."),
            17f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }, Ui.lp(b = 14))
        val columns = Ui.horizontal(c, Gravity.TOP)
        col.addView(columns)

        // ---- Avec un code
        val left = Ui.card(c, 24f)
        columns.addView(left, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(18) })
        left.addView(Ui.text(c, tr("Avec un code (le plus simple)"), 21f, Ui.TEXT, Ui.MEDIUM))
        left.addView(Ui.text(c, tr("Sur toqueapp.org : Profil › Mes robots › « Afficher un code ». Tapez-le ici : il sert une fois, pendant 10 minutes."),
            16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }, Ui.lp(t = 8))
        code = field("", "ABC234", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(8))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(28f))
            letterSpacing = 0.25f
            gravity = Gravity.CENTER
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> withCode(); true }
        }
        left.addView(code, Ui.lp(Ui.MATCH, Ui.u(68), t = 14))
        codeBtn = Ui.button(c, tr("Relier"), Ui.Style.PRIMARY, Icon.Kind.LINK, height = 58) { withCode() }
        left.addView(codeBtn, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))
        codeMsg = Ui.text(c, "", 16f, Ui.WARN).apply { visibility = View.GONE }
        left.addView(codeMsg, Ui.lp(t = 10))

        // ---- Avec l'e-mail et le mot de passe
        val right = Ui.card(c, 24f)
        columns.addView(right, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        right.addView(Ui.text(c, tr("Avec votre e-mail et votre mot de passe"), 21f, Ui.TEXT, Ui.MEDIUM))
        right.addView(Ui.text(c, tr("Le mot de passe n'est pas gardé sur le robot."), 16f, Ui.TEXT2), Ui.lp(t = 8))
        email = field("", tr("E-mail"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).apply {
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NEXT
        }
        right.addView(email, Ui.lp(Ui.MATCH, Ui.u(58), t = 12))
        password = field("", tr("Mot de passe"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> withPassword(); true }
        }
        right.addView(password, Ui.lp(Ui.MATCH, Ui.u(58), t = 10))
        // Seulement quand le site le demande (compte à double authentification).
        otpBox = Ui.vertical(c).apply { visibility = View.GONE }
        otpBox.addView(Ui.text(c, tr("Code à 6 chiffres de votre appli d'authentification"), 16f, Ui.TEXT2))
        otp = field("", "123456", InputType.TYPE_CLASS_NUMBER).apply {
            filters = arrayOf(InputFilter.LengthFilter(6))
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> withPassword(); true }
        }
        otpBox.addView(otp, Ui.lp(Ui.MATCH, Ui.u(58), t = 6))
        right.addView(otpBox, Ui.lp(t = 10))
        loginBtn = Ui.button(c, tr("Relier"), Ui.Style.PRIMARY, Icon.Kind.LINK, height = 58) { withPassword() }
        right.addView(loginBtn, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))
        loginMsg = Ui.text(c, "", 16f, Ui.WARN).apply { visibility = View.GONE }
        right.addView(loginMsg, Ui.lp(t = 10))

        col.addView(Ui.text(c, tr("Pas encore de compte ? Créez-le sur toqueapp.org depuis un téléphone ou un ordinateur."), 15f, Ui.TEXT3),
            Ui.lp(t = 14))
        return Ui.scroll(c, col)
    }

    private fun field(value: String, hint: String, type: Int) = EditText(c).apply {
        setText(value)
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(19f))
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.TEXT3)
        setSingleLine()
        inputType = type
        // Un champ mot de passe passe en police à chasse fixe : on garde celle de l'appli.
        typeface = Ui.REGULAR
        background = Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f)
        setPadding(Ui.u(14), 0, Ui.u(14), 0)
    }

    private fun busy(on: Boolean) {
        working = on
        Ui.setButtonEnabled(codeBtn, !on)
        Ui.setButtonEnabled(loginBtn, !on)
    }

    private fun show(t: TextView, message: String) {
        t.text = message
        t.visibility = View.VISIBLE
    }

    private fun withCode() {
        if (working) return
        val v = code.text.toString().filter { it.isLetterOrDigit() }
        if (v.length != 6) { show(codeMsg, tr("Le code a 6 caractères.")); return }
        a.hideKeyboard()
        busy(true)
        codeMsg.visibility = View.GONE
        a.app.site.linkWithCode(v) { r -> done(r, codeMsg, withCode = true) }
    }

    private fun withPassword() {
        if (working) return
        val mail = email.text.toString().trim()
        val pass = password.text.toString()
        if (mail.isEmpty() || pass.isEmpty()) { show(loginMsg, tr("Tapez votre e-mail et votre mot de passe.")); return }
        a.hideKeyboard()
        busy(true)
        loginMsg.visibility = View.GONE
        a.app.site.linkWithPassword(mail, pass, otp.text.toString()) { r -> done(r, loginMsg, withCode = false) }
    }

    private fun done(r: ToqueSite.Link, msg: TextView, withCode: Boolean) {
        busy(false)
        when (r) {
            is ToqueSite.Link.Ok -> {
                password.setText("")
                a.toast(tr("Robot relié au compte « %s »", r.username), Ui.OK)
                a.back()
            }
            is ToqueSite.Link.OtpRequired -> {
                otpBox.visibility = View.VISIBLE
                show(loginMsg, tr("Ce compte a la double authentification : tapez aussi le code."))
                otp.requestFocus()
            }
            // Les messages du site sont en français : ceux qu'on connaît sont traduits ici.
            is ToqueSite.Link.Failed -> show(msg, when (r.code) {
                "limited" -> tr("Trop d'essais. Attendez un quart d'heure avant de réessayer.")
                "invalid" -> if (withCode) tr("Code inconnu ou expiré. Affichez-en un nouveau dans votre profil sur le site.")
                    else tr("E-mail ou mot de passe incorrect.")
                "unverified" -> tr("Confirmez d'abord votre adresse e-mail avec le lien reçu.")
                "otp_invalid" -> tr("Code incorrect ou déjà utilisé.")
                else -> r.message
            })
        }
    }

    override fun onHide() {
        // Le mot de passe ne reste pas dans l'écran une fois quitté.
        if (::password.isInitialized) password.setText("")
    }
}
