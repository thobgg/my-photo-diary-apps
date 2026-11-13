package de.bgghome.mpd.api

import de.bgghome.mpd.shared.res.*

import de.bgghome.mpd.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.DateFormat
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import de.bgghome.mpd.state.Texte
import de.bgghome.mpd.login
import de.bgghome.mpd.logout
import de.bgghome.mpd.undoDeleteAlbum

/**
 * HTTP-Fehler mit dem "detail"-Text des Backends (lokalisierte Meldung).
 *
 * `code` ist der HTTP-Status, `errorCode` das maschinenlesbare `code`-Feld
 * aus dem Fehlerkoerper — bisher nur `"must_change_password"` (403), siehe
 * API_CHANGES.md vom 04.09.2026. Die beiden heissen im Backend gleich, hier
 * bewusst nicht: sonst verwechselt man 403 mit dem Grund fuer die 403.
 */
class MpdHttpException(
    val code: Int,
    val detail: String,
    val errorCode: String? = null,
) : IOException(detail) {
    /**
     * Die Route gibt es auf diesem Server gar nicht: FastAPIs eigene 404
     * mit dem nackten englischen „Not Found". Eigene 404 des Servers tragen
     * immer einen uebersetzten Satz. Passiert, wenn ein Plus-Modul nicht
     * installiert ist (reiner Core, 19.09.2026) — dann ist es gesperrt,
     * nicht kaputt.
     */
    val routeMissing: Boolean get() = code == 404 && detail == "Not Found"
}

/** Plus-Module mit eigenem Router (Server: core/module_keys.py MODULE_KEYS).
 *  memories gehoert seit Server 20.09.2026 zum Kern. */
internal val PLUS_MODULE_KEYS = setOf("tours", "trails", "search", "stats")

/**
 * Zu welchem Plus-Modul gehoert dieser Pfad? Das Segment nach `api`, ohne
 * Anhang (`memories-thumb` → memories); ein Unterpfad vor `/api` (Reverse-
 * Proxy) stoert nicht. `null` fuer alles, was zum Kern gehoert.
 */
internal fun moduleKeyForPath(encodedPath: String): String? {
    val segs = encodedPath.split('/').filter { it.isNotEmpty() }
    val i = segs.indexOf("api")
    if (i < 0 || i + 1 >= segs.size) return null
    return segs[i + 1].substringBefore('-').takeIf { it in PLUS_MODULE_KEYS }
}

/** Backend-Kennung fuer „dieses Konto muss erst sein Passwort wechseln". */
const val ERR_MUST_CHANGE_PASSWORD = "must_change_password"

/** Anzeige-Daten fuer die Vertrauensabfrage beim ersten Kontakt. */
data class CertInfo(
    val sha256: String,          // Hex ohne Trennzeichen, fuer den Vergleich
    val fingerprintDisplay: String, // AB:CD:… fuer die Anzeige
    val subject: String,
    val validUntil: String,
)

/**
 * `<baseUrl>/api/<segments…>?<query>` — der eine Adressbauer der API.
 * Jedes Segment wird einzeln kodiert (Schraegstrich, Umlaut, Raute im
 * Albumnamen bleiben ein Segment), ein Unterpfad in der Basisadresse
 * (Reverse-Proxy) bleibt erhalten, Query-Werte mit null fallen weg.
 * Paketweit sichtbar, damit der Test ihn ohne Android erreicht.
 */
internal fun apiUrl(
    baseUrl: String,
    segments: List<String>,
    query: Map<String, String?> = emptyMap(),
): HttpUrl =
    (baseUrl.toHttpUrlOrNull() ?: throw IOException(Texte.t(Res.string.api_err_invalid_address)))
        .newBuilder()
        .addPathSegment("api")
        .apply { segments.forEach { addPathSegment(it) } }
        .apply { query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) } }
        .build()

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

/**
 * Duenner Client um die MPD-REST-API. Fuehrt den mpd_session-Cookie
 * (persistent ueber SettingsStore) und akzeptiert neben den System-CAs genau
 * ein vom Nutzer ausdruecklich bestaetigtes Zertifikat (TOFU) — kein
 * pauschales Abschalten der Pruefung.
 */
class MpdApi(private val settings: SettingsStore) {

    internal val json = Json { ignoreUnknownKeys = true }

    /**
     * Wird gerufen, sobald irgendein Aufruf mit `code: "must_change_password"`
     * abgelehnt wird. Das ViewModel haengt sich hier ein und schaltet auf den
     * Wechsel-Bildschirm — sonst saehe der Nutzer nur eine 403-Meldung, und
     * zwar bei jeder einzelnen Aktion.
     */
    var onMustChangePassword: (() -> Unit)? = null

    /**
     * Wird gerufen, wenn ein Plus-Modul-Endpunkt auf diesem Server fehlt
     * ([MpdHttpException.routeMissing]) — mit dem Modul-Key. Rueckfall fuer
     * den Fall, dass whoami.modules nicht stimmt: das ViewModel sperrt das
     * Modul dann fuer den Rest der Sitzung.
     */
    var onModuleMissing: ((String) -> Unit)? = null

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val session = cookies.firstOrNull { it.name == "mpd_session" } ?: return
            if (session.expiresAt <= System.currentTimeMillis()) {
                settings.clearSession() // delete_cookie beim Logout
            } else {
                settings.saveSession(session.value, session.expiresAt)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val (value, expiresAt) = settings.session() ?: return emptyList()
            if (expiresAt <= System.currentTimeMillis()) return emptyList()
            return listOf(
                Cookie.Builder()
                    .name("mpd_session").value(value)
                    .domain(url.host).path("/")
                    .build()
            )
        }
    }

    private val systemTrust: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        factory.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    // Liest settings.trustedCertSha256 bei jeder Pruefung neu, damit ein
    // frisch bestaetigtes Zertifikat ohne Client-Neubau greift.
    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            systemTrust.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                systemTrust.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                if (sha256Hex(chain[0].encoded) != settings.trustedCertSha256) throw e
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrust.acceptedIssuers
    }

    internal val client: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            // Verbindungsaufbau: 15 s statt der 10 s Voreinstellung — ueber
            // DDNS und Mobilfunk ist der erste Handshake gern langsamer als
            // im LAN. Lesen bleibt bei 10 s; wer laenger braucht, nimmt
            // patientClient oder longRunClient.
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            // Bearer-Header, sobald ein Token da ist — gewinnt serverseitig
            // vor dem Cookie; ohne Token traegt der CookieJar die Sitzung.
            .addInterceptor { chain ->
                // Die Sprache der APP mitschicken — der Server lokalisiert
                // seine Fehlertexte danach (de/en, i18n-Vertrag vom 03.09.).
                // Seit dem 08.09.2026 spricht die App selbst Deutsch und
                // Englisch (res/values, res/values-en); Locale.getDefault()
                // ist die App-Sprache, auch wenn sie ab Android 13 je App in
                // den Systemeinstellungen gesetzt wurde. Alles andere faellt
                // auf Deutsch zurueck, wie die App selbst.
                val lang = java.util.Locale.getDefault().language
                    .takeIf { it == "de" || it == "en" } ?: "de"
                val token = settings.bearerToken()
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .header("Accept-Language", lang)
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request().newBuilder()
                        .header("Accept-Language", lang)
                        .build()
                }
                chain.proceed(request)
            }
            .sslSocketFactory(ssl.socketFactory, trustManager)
            // Selbstsignierte Zertifikate tragen selten den LAN-Hostnamen —
            // das bestaetigte Zertifikat gilt deshalb auch als Hostnachweis.
            .hostnameVerifier { host, sslSession ->
                defaultVerifier.verify(host, sslSession) || run {
                    val cert = sslSession.peerCertificates.firstOrNull() as? X509Certificate
                    cert != null && sha256Hex(cert.encoded) == settings.trustedCertSha256
                }
            }
            .build()
    }

    // -- Anfrage-Bausteine ----------------------------------------------------
    //
    // Bis 08.09.2026 stand an 48 Endpunkten derselbe Block
    // `baseUrl.toHttpUrlOrNull() ?: throw …` + newBuilder + addPathSegment,
    // und 17 weitere bauten die Adresse per String-Konkatenation
    // ("$baseUrl/api/…") — ohne Kodierung, ohne Pruefung. Zwei Regeln fuer
    // dieselbe Frage. Jetzt: EIN Adressbauer (apiUrl, mit Test) und vier
    // Kurzformen fuer die Methoden, die run()/fail() umschliessen. Ein neuer
    // Endpunkt kostet damit drei Zeilen.

    private val jsonType = "application/json".toMediaType()
    internal fun jsonBody(obj: JsonObject) = obj.toString().toRequestBody(jsonType)
    internal fun jsonBody(text: String) = text.toRequestBody(jsonType)
    internal fun emptyBody() = ByteArray(0).toRequestBody()

    /** `/api/<segments…>?query` — jedes Segment einzeln kodiert. */
    internal fun api(
        baseUrl: String, vararg segments: String, query: Map<String, String?> = emptyMap(),
    ): HttpUrl = apiUrl(baseUrl, segments.toList(), query)

    internal fun get(url: HttpUrl, http: OkHttpClient = client): String =
        run(Request.Builder().url(url).build(), http)
    internal fun post(url: HttpUrl, body: RequestBody = emptyBody(), http: OkHttpClient = client): String =
        run(Request.Builder().url(url).post(body).build(), http)
    internal fun put(url: HttpUrl, body: RequestBody): String =
        run(Request.Builder().url(url).put(body).build())
    internal fun patch(url: HttpUrl, body: RequestBody): String =
        run(Request.Builder().url(url).patch(body).build())
    internal fun delete(url: HttpUrl, body: RequestBody? = null): String =
        run(Request.Builder().url(url).delete(body).build())

    internal fun <T> decode(serializer: KSerializer<T>, text: String): T =
        json.decodeFromString(serializer, text)

    // -- Endpunkte ----------------------------------------------------------

    suspend fun login(baseUrl: String, username: String, password: String): Unit =
        withContext(Dispatchers.IO) {
            val body = jsonBody(
                json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password)),
            )
            val response = decode(LoginResponse.serializer(), post(api(baseUrl, "login"), body))
            response.token?.let { token ->
                settings.saveBearer(token, System.currentTimeMillis() + response.expiresIn * 1000)
            }
        }

    /**
     * Auth-Header fuer externe HTTP-Stacks (ExoPlayer): Bearer bevorzugt,
     * sonst der Sitzungs-Cookie.
     */
    fun authHeaders(): Map<String, String> {
        settings.bearerToken()?.let { return mapOf("Authorization" to "Bearer $it") }
        settings.session()?.let { (value, expiresAt) ->
            if (expiresAt > System.currentTimeMillis()) return mapOf("Cookie" to "mpd_session=$value")
        }
        return emptyMap()
    }

    suspend fun whoami(baseUrl: String): WhoAmI = withContext(Dispatchers.IO) {
        decode(WhoAmI.serializer(), get(api(baseUrl, "whoami")))
    }

    /**
     * Passwort wechseln. Steht das `must_change_password`-Flag, ist das einer
     * der wenigen erlaubten Aufrufe und loescht das Flag; sonst das normale
     * „Passwort aendern" aus den Einstellungen (jedem Konto offen).
     *
     * 401 altes Passwort falsch · 400 kuerzer als 12 Zeichen oder identisch
     * zum alten · 403 Demo-Konto · 429 Ratelimit (zaehlt auf die IP).
     */
    suspend fun changePassword(baseUrl: String, oldPassword: String, newPassword: String) =
        withContext(Dispatchers.IO) {
            val body = jsonBody(
                json.encodeToString(
                    ChangePasswordRequest.serializer(),
                    ChangePasswordRequest(oldPassword, newPassword),
                ),
            )
            post(api(baseUrl, "account", "change-password"), body)
            Unit
        }

    suspend fun albums(baseUrl: String): List<AlbumSummary> = withContext(Dispatchers.IO) {
        decode(AlbumsResponse.serializer(), get(api(baseUrl, "albums"))).albums
    }

    suspend fun album(baseUrl: String, space: String, name: String): AlbumEnvelope =
        withContext(Dispatchers.IO) {
            val raw = json.parseToJsonElement(get(api(baseUrl, "album", space, name))).jsonObject
            AlbumEnvelope(json.decodeFromJsonElement(AlbumContent.serializer(), raw), raw)
        }

    /**
     * POST /api/album/{space}/{album}/update — Body ist das ganze album-Objekt
     * (wie saveAlbumData in album-save.js). Der Server bereinigt die Elemente
     * selbst per Whitelist (angereicherte Felder wie thumbnails/original
     * fliegen dort raus) und setzt version + meta.modified.
     *
     * [baseModified] = meta.modified der Fassung, auf der die Aenderungen
     * beruhen. Wenn gesetzt, geht der Header X-MPD-Base-Modified mit —
     * der Server (Konfliktschutz, Auftrag 02.09.2026) antwortet 409, falls
     * das Album inzwischen anders modifiziert wurde. Ohne Header (oder auf
     * altem Server) gilt Last-Write-Wins wie im Web. Rueckgabe: Antworttext,
     * daraus liest der Aufrufer das neue modified.
     */
    suspend fun updateAlbum(
        baseUrl: String, space: String, name: String, body: JsonObject,
        baseModified: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(api(baseUrl, "album", space, name, "update"))
            .post(jsonBody(body))
            .apply { baseModified?.let { header("X-MPD-Base-Modified", it) } }
            .build()
        run(request)
    }

    /** GET …/backups — die .mpd_backups des Albums, neueste zuerst. */
    suspend fun listBackups(baseUrl: String, space: String, name: String): List<BackupEntry> =
        withContext(Dispatchers.IO) {
            decode(
                BackupsResponse.serializer(),
                get(api(baseUrl, "album", space, name, "backups")),
            ).backups
        }

    /** POST …/restore — Stand zurueckholen; der Server sichert vorher den
     *  aktuellen Stand (current_backup). */
    suspend fun restoreBackup(baseUrl: String, space: String, name: String, backupFilename: String) {
        withContext(Dispatchers.IO) {
            post(
                api(
                    baseUrl, "album", space, name, "restore",
                    query = mapOf("backup_filename" to backupFilename),
                ),
            )
        }
    }

    /** DELETE /api/album/… — loescht NUR album.json (Overlay-Modell); liefert
     *  den Backup-Dateinamen fuers Rueckgaengig. */
    suspend fun deleteAlbum(baseUrl: String, space: String, name: String): String? =
        withContext(Dispatchers.IO) {
            val text = delete(api(baseUrl, "album", space, name))
            json.parseToJsonElement(text).jsonObject["backup"]?.jsonPrimitive?.contentOrNull
        }

    /** POST …/undo-delete — geloeschtes Album aus dem Backup zurueckholen. */
    suspend fun undoDeleteAlbum(baseUrl: String, space: String, name: String, backupFilename: String) {
        withContext(Dispatchers.IO) {
            post(
                api(
                    baseUrl, "album", space, name, "undo-delete",
                    query = mapOf("backup_filename" to backupFilename),
                ),
            )
        }
    }

    /** Coil laedt Thumbnails ueber denselben Client (Cookie + Zertifikatslogik). */
    fun httpClient(): OkHttpClient = client

    // Zwei geduldigere Fassungen desselben Clients (gleicher Pool, gleiche
    // Cookies, gleiches Zertifikat) — bis 08.09.2026 baute jeder der neun
    // Aufrufer, der laenger warten musste, per newBuilder() seine eigene
    // Instanz je Aufruf. Wer 60 s braucht (Live-Scan, SMTP, Heatmap), nimmt
    // patientClient; wer Minuten braucht (Archiv, Downloads, Loeschen einer
    // Quelle), longRunClient.
    internal val patientClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    }
    internal val longRunClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(15, java.util.concurrent.TimeUnit.MINUTES).build()
    }

    /** Textdatei (z.B. GPX) mit Sitzungs-Cookie laden. */
    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        run(Request.Builder().url(url).build())
    }

    /**
     * Nur den ANFANG einer Textdatei holen, per HTTP-Range.
     *
     * Fuer die Beschriftung einer Textdatei im Album braucht die App deren
     * erste nicht-leere Zeile (16.09.2026: „Der Titel soll auch
     * nicht der Dateiname sein, nur Fallback, sondern erste Zeile txt oder
     * manuell"). Den ganzen Text dafuer zu laden widerspraeche der Regel,
     * erst beim Aufklappen zu holen — ein paar hundert Byte tun das nicht.
     *
     * Beantwortet der Server kein 206, kommt die ganze Datei; dann schneidet
     * der Aufrufer selbst. Das ist der Preis dafuer, ohne Vertragsaenderung
     * auszukommen — schoener waere die erste Zeile im Element, das waere
     * ein Abruf statt zehn bei einem Album voller Textdateien.
     */
    suspend fun fetchTextHead(url: String, bytes: Int = 512): String =
        withContext(Dispatchers.IO) {
            val text = run(
                Request.Builder().url(url)
                    .header("Range", "bytes=0-${bytes - 1}")
                    .build(),
            )
            if (text.length > bytes) text.take(bytes) else text
        }

    /**
     * Laedt eine Datei (das Foto-Original) in den App-Cache und liefert sie
     * zurueck. Seit dem Server-ETag (01.09.2026, sha1(pfad+mtime_ns) mit
     * Cache-Control: no-cache) wird ein vorhandener Cache-Treffer per
     * If-None-Match revalidiert — 304 kostet im LAN fast nichts, und nach
     * einem Foto-Edit kommt sofort die frische Datei. Ohne gemerkten ETag
     * (Altbestand) wird neu geladen.
     *
     * @param suffix Dateiendung mit Punkt, die die Cache-Datei tragen soll.
     *   Klingt nach Kosmetik, ist keine: Der Name aus dem SHA-256 hatte gar
     *   keine Endung, und `FileProvider.getType()` leitet den MIME-Typ genau
     *   daraus ab. Eine so weitergegebene Datei meldete sich der
     *   empfangenden App als `application/octet-stream` — manche glauben
     *   dem Typ im Intent, manche fragen den Resolver, und die zweiten
     *   bekamen ein Bild als Dokument angeboten (07.09.2026).
     */
    suspend fun downloadToCache(
        dir: java.io.File,
        url: String,
        suffix: String = "",
    ): java.io.File =
        withContext(Dispatchers.IO) {
            val name = de.bgghome.mpd.util.sha256Hex(url.toByteArray()) + suffix
            val target = java.io.File(dir, name)
            val etagFile = java.io.File(dir, "$name.etag")
            val cachedEtag = etagFile.takeIf { target.length() > 0 && it.isFile }
                ?.readText()?.takeIf { it.isNotBlank() }

            val requestBuilder = Request.Builder().url(url)
            cachedEtag?.let { requestBuilder.header("If-None-Match", it) }

            dir.mkdirs()
            val tmp = java.io.File(dir, "$name.part")
            // Ein Abbruch mitten im Download (Netz weg, Lightbox zu, Prozess
            // tot) liess bis 08.09.2026 die halbe .part-Datei liegen — bei
            // Originalen zweistellige Megabyte, und niemand raeumte sie je
            // weg. Jetzt: bei jedem Fehler loeschen, und ein Rest aus einem
            // frueheren Lauf wird beim Start ohnehin ueberschrieben.
            try {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.code == 304) return@withContext target
                    // Kein fail(): der Koerper ist hier die Nutzlast (Datei-Stream),
                    // ihn zu lesen wuerde den Download verbrauchen.
                    if (!response.isSuccessful) {
                        throw MpdHttpException(response.code, "HTTP ${response.code}")
                    }
                    tmp.outputStream().use { out ->
                        response.body?.byteStream()?.copyTo(out)
                            ?: throw IOException(Texte.t(Res.string.api_err_empty_response))
                    }
                    response.header("ETag")?.let { etagFile.writeText(it) } ?: etagFile.delete()
                }
                if (!tmp.renameTo(target)) throw IOException(Texte.t(Res.string.api_err_cache_unwritable))
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
            target
        }

    suspend fun logout(baseUrl: String): Unit = withContext(Dispatchers.IO) {
        try {
            post(api(baseUrl, "logout"))
        } finally {
            settings.clearSession() // lokal immer abmelden, auch wenn der Server nicht erreichbar ist
        }
    }

    /**
     * Datei vom Server in einen Ausgabestrom schieben — fuer die Mitnahme
     * (Archiv, Export). Bewusst NICHT ueber den DownloadManager: der kennt
     * unseren Zertifikatsspeicher nicht und muesste den Bearer-Token
     * mitbekommen. Liefert die Zahl der Bytes.
     */
    suspend fun downloadStream(url: String, sink: java.io.OutputStream): Long =
        withContext(Dispatchers.IO) {
            longRunClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                // Kein fail(): Streaming-Download, der Koerper ist die Nutzlast.
                if (!response.isSuccessful) {
                    throw MpdHttpException(response.code, "HTTP ${response.code}")
                }
                sink.use { out ->
                    response.body?.byteStream()?.copyTo(out) ?: throw IOException(Texte.t(Res.string.api_err_empty_response))
                }
            }
        }

    /**
     * Wertet einen Fehlerkoerper aus und wirft. Einzige Stelle, an der eine
     * `MpdHttpException` entsteht — dadurch greift auch die Meldung an
     * [onMustChangePassword] fuer *jeden* Aufruf der App, nicht nur fuer
     * Login und whoami.
     */
    internal fun fail(status: Int, text: String, request: Request): Nothing {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val field = { name: String ->
            runCatching { obj?.get(name)?.jsonPrimitive?.content }.getOrNull()
        }
        val errorCode = field("code")
        if (errorCode == ERR_MUST_CHANGE_PASSWORD) onMustChangePassword?.invoke()
        val e = MpdHttpException(status, field("detail") ?: "HTTP $status", errorCode)
        if (e.routeMissing) {
            moduleKeyForPath(request.url.encodedPath)?.let { onModuleMissing?.invoke(it) }
        }
        throw e
    }

    /** Wie run(), gibt aber den Status mit — fuer 202-Vertraege
     *  (Suchindex/Stats-Scan laufen gerade). */
    internal fun runWithCode(request: Request, http: OkHttpClient = client): Pair<Int, String> {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) fail(response.code, text, request)
            return response.code to text
        }
    }

    internal fun run(request: Request, http: OkHttpClient = client): String {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) fail(response.code, text, request)
            return text
        }
    }

    // -- Zertifikat fuer die Vertrauensabfrage holen ------------------------

    /**
     * Baut nur den TLS-Handshake auf (es werden keine Daten gesendet) und
     * liefert das Server-Zertifikat zur Anzeige in der Vertrauensabfrage.
     */
    suspend fun fetchServerCert(baseUrl: String): CertInfo = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrlOrNull()
            ?: throw IOException(Texte.t(Res.string.api_err_invalid_address_x, baseUrl))
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        (ssl.socketFactory.createSocket(url.host, url.port) as SSLSocket).use { socket ->
            socket.soTimeout = 10_000
            socket.startHandshake()
            val cert = socket.session.peerCertificates.first() as X509Certificate
            val hex = sha256Hex(cert.encoded)
            CertInfo(
                sha256 = hex,
                fingerprintDisplay = hex.chunked(2).joinToString(":"),
                subject = cert.subjectX500Principal.name,
                validUntil = DateFormat.getDateInstance().format(cert.notAfter),
            )
        }
    }

    companion object {
        /** Zertifikats-Fingerprint: GROSS, wie gespeichert und angezeigt. */
        private fun sha256Hex(data: ByteArray): String =
            de.bgghome.mpd.util.sha256Hex(data, uppercase = true)

        private fun encodedUrl(vararg segments: String): HttpUrl =
            HttpUrl.Builder().scheme("http").host("x")
                .apply { segments.forEach { addPathSegment(it) } }
                .build()

        /** Relativer Thumbnail-Pfad, URL-codiert wie routers/albums.py. */
        fun thumbnailPath(space: String, album: String, file: String, size: String): String {
            val url = encodedUrl("api", "thumbnail", space, album, file)
            return "${url.encodedPath}?size=$size"
        }

        /** Relativer Original-Download-Pfad wie routers/albums.py. */
        fun originalPath(space: String, album: String, file: String): String =
            encodedUrl("api", "photo", space, album, file, "download").encodedPath

        /** FFmpeg-Video-Thumb — /api/thumbnail liefert fuer Videos 415. */
        fun videoThumbPath(space: String, album: String, file: String): String =
            encodedUrl("api", "video-thumb", space, album, file).encodedPath

        /**
         * PDF im Album. Frueher per URLEncoder zusammengeklebt — der kodiert
         * fuers Formular, aus Leerzeichen wurde "+", das der Server im Pfad
         * woertlich nimmt: jedes Album mit Leerzeichen im Namen lief ins 404.
         */
        fun documentPath(space: String, album: String, file: String): String =
            encodedUrl("api", "document", space, album, file).encodedPath
    }
}
