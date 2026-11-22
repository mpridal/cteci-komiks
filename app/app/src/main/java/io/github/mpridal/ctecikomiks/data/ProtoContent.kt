package io.github.mpridal.ctecikomiks.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.mpridal.ctecikomiks.core.ReadWord
import org.json.JSONArray
import org.json.JSONObject

/** Text panelu (vyprávění nebo bublina) a jeho slova. */
data class TextBlock(val kind: String, val speaker: String?, val firstWord: Int, val words: List<ReadWord>)

/** Značka spouštěná přečtením slova. */
data class Cue(val id: String, val type: String, val layer: String?, val action: String?)

/** Jedna čtecí stránka prototypu A = jeden panel kapitoly. */
data class ProtoPage(
    val chapterTitle: String,
    val panelId: String,
    val image: Bitmap?,
    val blocks: List<TextBlock>,
    val cues: Map<String, Cue>,
) {
    val words: List<ReadWord> = blocks.flatMap { it.words }

    /** Gramatika pro Vosk: slova, slabiky a náhrady stránky + [unk]. */
    fun grammar(): List<String> = io.github.mpridal.ctecikomiks.core.grammarFor(words)
}

object ProtoContent {
    fun load(context: Context): List<ProtoPage> {
        val assets = context.assets
        val index = JSONObject(assets.open("proto/pages.json").bufferedReader().readText())
        val chapters = mutableMapOf<String, JSONObject>()
        val pages = mutableListOf<ProtoPage>()
        val list = index.getJSONArray("pages")
        for (i in 0 until list.length()) {
            val ref = list.getJSONObject(i)
            val chId = ref.getString("chapter")
            val chapter = chapters.getOrPut(chId) {
                JSONObject(assets.open("proto/$chId/chapter.json").bufferedReader().readText())
            }
            val panel = findPanel(chapter, ref.getString("panel")) ?: continue
            val image = runCatching {
                assets.open("proto/$chId/${panel.getString("id")}.png").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            pages += parsePanel(chapter.getString("title"), panel, image)
        }
        return pages
    }

    private fun findPanel(chapter: JSONObject, id: String): JSONObject? {
        val pages = chapter.getJSONArray("pages")
        for (p in 0 until pages.length()) {
            val panels = pages.getJSONObject(p).getJSONArray("panels")
            for (k in 0 until panels.length()) {
                val panel = panels.getJSONObject(k)
                if (panel.getString("id") == id) return panel
            }
        }
        return null
    }

    fun parsePanel(title: String, panel: JSONObject, image: Bitmap?): ProtoPage {
        val blocks = mutableListOf<TextBlock>()
        var first = 0
        val texts = panel.getJSONArray("texts")
        // vyprávění první, pak bubliny (pořadí čtení)
        val ordered = (0 until texts.length()).map { texts.getJSONObject(it) }
            .sortedBy { if (it.getString("kind") == "narration") 0 else 1 }
        for (t in ordered) {
            val words = t.getJSONArray("words").let { arr -> (0 until arr.length()).map { parseWord(arr.getJSONObject(it)) } }
            blocks += TextBlock(t.getString("kind"), t.optString("speaker").ifEmpty { null }, first, words)
            first += words.size
        }
        val cues = mutableMapOf<String, Cue>()
        panel.optJSONArray("cues")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                cues[c.getString("id")] = Cue(c.getString("id"), c.getString("type"),
                    c.optString("layer").ifEmpty { null }, c.optString("action").ifEmpty { null })
            }
        }
        return ProtoPage(title, panel.getString("id"), image, blocks, cues)
    }

    private fun parseWord(o: JSONObject): ReadWord {
        val asr = o.optJSONObject("asr")
        return ReadWord(
            text = o.getString("w"),
            syllables = o.getJSONArray("syl").strings(),
            practice = o.optJSONArray("practice")?.strings() ?: emptyList(),
            cues = o.optJSONArray("cues")?.strings() ?: emptyList(),
            asr = asr?.optJSONArray("alt")?.strings() ?: emptyList(),
            inVocabulary = asr?.optBoolean("oov", false) != true,
        )
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
