package data.provider.providers

import data.provider.*
import io.ktor.client.request.*
import kotlinx.serialization.json.*

class Novelism : BookProvider {
    companion object {
        const val id = "novelism"
    }

    override suspend fun getRank(options: Map<String, String>): List<SBookListItem> {
        return emptyList()
    }

    override suspend fun getMetadata(bookId: String): SBookMetadata {
        val doc = client.get("https://novelism.jp/novel/$bookId").document()

        val title = doc.selectFirst("h1.mb-2 > span")!!.text()

        val author = doc
            .selectFirst("div.mb-1 > a.text-sm")!!
            .let { SBookAuthor(name = it.text(), link = "https://novelism.jp" + it.attr("href")) }

        val introduction = doc.selectFirst("div.my-4 > div.text-sm")!!.text()

        val toc = doc.select("div.table-of-contents > ol > li").map { col ->
            val h3 = col.selectFirst("h3 > span")?.text()
            val li = col.select("ol > li").mapNotNull { el ->
                if (el.selectFirst("button") != null) null
                else SBookTocItem(
                    title = el.selectFirst("div.leading-6")!!.text(),
                    episodeId = el.selectFirst("a")!!.attr("href").removeSuffix("/").substringAfterLast("/"),
                )
            }
            if (h3 == null) li else listOf(SBookTocItem(title = h3)) + li
        }.flatten()

        return SBookMetadata(
            title = title,
            authors = listOf(author),
            introduction = introduction,
            toc = toc,
        )
    }

    override suspend fun getEpisode(bookId: String, episodeId: String): SBookEpisode {
        val doc = client.get("https://novelism.jp/novel/$bookId/article/$episodeId/").document()
        val jsonRaw = doc.select("script")
            .map { it.html() }
            .filter { it.isNotBlank() && "gtm" !in it }
            .maxBy { it.length }
            .let { it.substring(it.indexOf("content:")) }
            .let { it.substring(0, it.indexOf("}]\",") + 2) }
            .removePrefix("content:\"")
            .replace("\\\"", "\"")
        val content = Json.parseToJsonElement(jsonRaw)
            .jsonArray
            .map { it.jsonObject["insert"]!! }
            .joinToString("") { el ->
                (el as? JsonPrimitive)?.content
                    ?: el.jsonObject.let { obj ->
                        obj["ruby"]?.let { ruby ->
                            ruby.jsonObject["rb"]!!.jsonPrimitive.content
                        } ?: obj["block-image"]?.let { img ->
                            "<图片>${img.jsonPrimitive.content}\\n"
                        } ?: "\\n"
                    }
            }
            .split("\\n")
        return SBookEpisode(paragraphs = content)
    }
}