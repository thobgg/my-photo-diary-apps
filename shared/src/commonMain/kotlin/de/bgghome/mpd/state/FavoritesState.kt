package de.bgghome.mpd.state

import de.bgghome.mpd.api.Photo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.MpdHttpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException

/*
 * Favoriten (App-Extra 02.09.2026): source_album-Referenzen im Album
 * „favoriten" des jeweiligen Space — kein Serverumbau, das Album erscheint
 * als normales Album in der Uebersicht und ist spaeter das Best-of-Futter
 * fuers Fotobuch. Aus dem ViewModel herausgeloest (08.09.2026,
 * Struktur-Audit Stufe 2, Block 3): haengt nur an der API und am
 * gemeinsamen Update-Body (state/AlbumUpdate.kt).
 */
class FavoritesState(
    private val api: MpdApi,
    private val baseUrl: () -> String,
    private val scope: CoroutineScope,
) {
    private val sets = mutableStateMapOf<String, Set<String>>()
    var error by mutableStateOf<String?>(null)

    private fun key(album: String, file: String) = "$album/$file"

    fun isFavorite(p: Photo): Boolean =
        sets[p.space]?.contains(key(p.album, p.file)) == true

    fun ensureLoaded(space: String) {
        if (sets.containsKey(space)) return
        sets[space] = emptySet()
        scope.launch {
            runCatching {
                val env = api.album(baseUrl(), space, "favoriten")
                sets[space] = env.raw["elements"]?.jsonArray
                    ?.map { it.jsonObject }
                    ?.filter {
                        it["type"]?.jsonPrimitive?.contentOrNull == "photo" &&
                            it["file"]?.jsonPrimitive?.contentOrNull != null
                    }
                    ?.map {
                        key(
                            it["source_album"]?.jsonPrimitive?.contentOrNull ?: "favoriten",
                            it["file"]!!.jsonPrimitive.content,
                        )
                    }
                    ?.toSet() ?: emptySet()
            }
        }
    }

    /**
     * Tipps laufen nacheinander, nie nebeneinander. Zwei schnelle Tipps
     * (Stern, Stern weg) luden bis 08.09.2026 beide denselben Stand, der
     * erste speicherte, der zweite trug die alte Basis — und bekam vom
     * Konfliktschutz zu Recht 409, mit einer Meldung, die niemand lesen
     * will („Was soll das?"). Mit dem Riegel sieht der zweite
     * Tipp den Stand des ersten.
     */
    private val gate = Mutex()

    /** Stern in der Lightbox: Referenz ins favoriten-Album rein/raus.
     *  Beim ersten Mal wird das Album per create-empty angelegt. */
    fun toggle(p: Photo) {
        scope.launch {
            gate.withLock {
                try {
                    toggleOnce(p)
                } catch (e: MpdHttpException) {
                    if (e.code == 409) {
                        // Jemand anderes hat gerade gespeichert (zweites
                        // Geraet, Web). Der Wunsch „Stern rein/raus" gilt
                        // trotzdem: frischen Stand holen und einmal still
                        // wiederholen. Erst wenn auch das scheitert, sagen.
                        try {
                            toggleOnce(p)
                        } catch (e2: MpdHttpException) {
                            error = e2.detail
                        } catch (e2: IOException) {
                            error = serverUnreachable(e2)
                        }
                    } else error = e.detail
                } catch (e: IOException) {
                    error = serverUnreachable(e)
                }
            }
        }
    }

    /** Ein Durchlauf: laden, umschalten, speichern mit Konflikt-Basis. */
    private suspend fun toggleOnce(p: Photo) {
        val env = try {
            api.album(baseUrl(), p.space, "favoriten")
        } catch (e: MpdHttpException) {
            if (e.code == 404) {
                api.createEmptyAlbum(baseUrl(), p.space, "favoriten")
                api.album(baseUrl(), p.space, "favoriten")
            } else throw e
        }
        val elements = env.raw["elements"]?.jsonArray
            ?.map { it.jsonObject } ?: emptyList()
        val without = elements.filterNot { e ->
            e["type"]?.jsonPrimitive?.contentOrNull == "photo" &&
                e["file"]?.jsonPrimitive?.contentOrNull == p.file &&
                (e["source_album"]?.jsonPrimitive?.contentOrNull
                    ?: "favoriten") == p.album
        }
        val adding = without.size == elements.size
        val newElements = if (adding) {
            val nextId = (elements.mapNotNull {
                it["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            }.maxOrNull() ?: 0) + 1
            elements + buildJsonObject {
                put("id", JsonPrimitive("%04d".format(nextId)))
                put("type", JsonPrimitive("photo"))
                put("file", JsonPrimitive(p.file))
                if (p.album != "favoriten") {
                    put("source_album", JsonPrimitive(p.album))
                }
            }
        } else without
        // create-empty setzt title = Ordnername — einmalig aufhuebschen
        val meta = env.raw["meta"]?.jsonObject ?: buildJsonObject {}
        val fixedMeta =
            if (meta["title"]?.jsonPrimitive?.contentOrNull == "favoriten") {
                JsonObject(meta + ("title" to JsonPrimitive("Favoriten")))
            } else meta
        // Wie der Autosave: gefilterter Body und Konflikt-Basis. Zwei
        // Geraete, die kurz nacheinander sternen, ueberschreiben sich
        // sonst gegenseitig.
        api.updateAlbum(
            baseUrl(), p.space, "favoriten",
            updateBody(fixedMeta, newElements),
            baseModified = modifiedOf(env.raw),
        )
        val k = key(p.album, p.file)
        sets[p.space] = (sets[p.space] ?: emptySet())
            .let { if (adding) it + k else it - k }
    }
}
