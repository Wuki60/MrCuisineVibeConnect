package app.toque

import android.content.Context
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Autorités racines récentes absentes d'Android 6, ajoutées à celles du système.
 *
 * Les APK des versions GitHub sont servis par release-assets.githubusercontent.com, dont le
 * certificat (Let's Encrypt) remonte à « ISRG Root X1 », arrivée seulement dans Android 7.1.1 :
 * sur le robot, le téléchargement échouait (« Trust anchor for certification path not found »).
 *
 * assets/certs contient aussi les racines vers lesquelles les sites utilisés basculent ou
 * basculeront, toutes absentes d'Android 6 : ISRG Root X2 (Let's Encrypt, ECDSA), Sectigo
 * Public Server Authentication Root E46 et R46 (GitHub, aujourd'hui encore contre-signées par
 * USERTrust) et GTS Root R1 à R4 (Google). Copiées du magasin de racines de macOS ; une racine
 * de plus = un fichier PEM de plus dans ce dossier.
 *
 * Le système reste consulté en premier ; les racines embarquées ne servent que s'il refuse.
 */
object ExtraRoots {
    private const val DIR = "certs"
    @Volatile private var cached: SSLSocketFactory? = null

    fun socketFactory(context: Context): SSLSocketFactory =
        cached ?: synchronized(this) { cached ?: build(context.applicationContext).also { cached = it } }

    private fun build(context: Context): SSLSocketFactory {
        val system = trustManager(null)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val cf = CertificateFactory.getInstance("X.509")
        context.assets.list(DIR).orEmpty().forEach { name ->
            context.assets.open("$DIR/$name").use { input ->
                cf.generateCertificates(input).forEachIndexed { i, cert -> store.setCertificateEntry("$name-$i", cert) }
            }
        }
        val extra = trustManager(store)
        val combined = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                system.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    try {
                        extra.checkServerTrusted(chain, authType)
                    } catch (_: CertificateException) {
                        throw e
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + extra.acceptedIssuers
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(combined), null) }.socketFactory
    }

    private fun trustManager(store: KeyStore?): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(store)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
