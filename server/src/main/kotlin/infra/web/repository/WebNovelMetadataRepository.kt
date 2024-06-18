package infra.web.repository

import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates.*
import com.mongodb.client.result.UpdateResult
import infra.*
import infra.common.Page
import infra.oplog.WebNovelTocMergeHistory
import infra.web.*
import infra.web.datasource.WebNovelEsDataSource
import infra.web.datasource.WebNovelHttpDataSource
import infra.web.datasource.providers.Hameln
import infra.web.datasource.providers.RemoteNovelListItem
import infra.web.datasource.providers.Syosetu
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.Clock
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.util.*
import kotlin.time.Duration.Companion.minutes

class WebNovelMetadataRepository(
    private val provider: WebNovelHttpDataSource,
    mongo: MongoClient,
    private val es: WebNovelEsDataSource,
    private val redis: RedisClient,
) {
    private val webNovelMetadataCollection =
        mongo.database.getCollection<WebNovelMetadata>(
            MongoCollectionNames.WEB_NOVEL,
        )
    private val tocMergeHistoryCollection =
        mongo.database.getCollection<WebNovelTocMergeHistory>(
            MongoCollectionNames.TOC_MERGE_HISTORY,
        )
    private val userFavoredWebCollection =
        mongo.database.getCollection<WebNovelFavoriteDbModel>(
            MongoCollectionNames.WEB_FAVORITE,
        )

    private fun byId(providerId: String, novelId: String): Bson =
        and(
            eq(WebNovelMetadata::providerId.field(), providerId),
            eq(WebNovelMetadata::novelId.field(), novelId),
        )

    suspend fun listRank(
        providerId: String,
        options: Map<String, String>,
    ): Result<Page<WebNovelMetadataListItem>> {
        return provider
            .listRank(providerId, options)
            .map { rank ->
                rank.map { remote ->
                    val local = webNovelMetadataCollection
                        .find(byId(providerId, remote.novelId))
                        .firstOrNull()
                    remote.toOutline(providerId, local)
                }
            }
    }

    suspend fun search(
        userQuery: String?,
        filterProvider: List<String>,
        filterType: WebNovelFilter.Type,
        filterLevel: WebNovelFilter.Level,
        filterTranslate: WebNovelFilter.Translate,
        filterSort: WebNovelFilter.Sort,
        page: Int,
        pageSize: Int,
    ): Page<WebNovelMetadataListItem> {
        val (items, total) = es.searchNovel(
            userQuery = userQuery,
            filterProvider = filterProvider,
            filterType = filterType,
            filterLevel = filterLevel,
            filterTranslate = filterTranslate,
            filterSort = filterSort,
            page = page,
            pageSize = pageSize
        )
        return Page(
            items = items.map { (providerId, novelId) ->
                webNovelMetadataCollection
                    .find(byId(providerId, novelId))
                    .firstOrNull()!!
                    .toOutline()
            },
            total = total,
            pageSize = pageSize,
        )
    }

    suspend fun get(
        providerId: String,
        novelId: String,
    ): WebNovelMetadata? {
        return webNovelMetadataCollection
            .find(byId(providerId, novelId))
            .firstOrNull()
    }

    private suspend fun getRemote(
        providerId: String,
        novelId: String,
    ): Result<WebNovelMetadata> {
        return provider
            .getMetadata(providerId, novelId)
            .map { remote ->
                WebNovelMetadata(
                    id = ObjectId(),
                    providerId = providerId,
                    novelId = novelId,
                    titleJp = remote.title,
                    authors = remote.authors.map { WebNovelAuthor(it.name, it.link) },
                    type = remote.type,
                    keywords = remote.keywords,
                    attentions = remote.attentions,
                    points = remote.points,
                    totalCharacters = remote.totalCharacters,
                    introductionJp = remote.introduction,
                    toc = remote.toc.map { WebNovelTocItem(it.title, null, it.chapterId, it.createAt) },
                )
            }
    }

    suspend fun getNovelAndSave(
        providerId: String,
        novelId: String,
        expiredMinutes: Int = 20 * 60,
    ): Result<WebNovelMetadata> {
        val local = get(providerId, novelId)

        // 不在数据库中
        if (local == null) {
            return getRemote(providerId, novelId)
                .onSuccess {
                    webNovelMetadataCollection
                        .insertOne(it)
                    es.syncNovel(it)
                }
        }

        // 在数据库中，暂停更新
        if (local.pauseUpdate) {
            return Result.success(local)
        }

        // 在数据库中，没有过期
        val sinceLastSync = Clock.System.now() - local.syncAt
        if (sinceLastSync <= expiredMinutes.minutes) {
            return Result.success(local)
        }

        // 在数据库中，过期，合并
        val remoteNovel = getRemote(providerId, novelId)
            .getOrElse {
                // 无法更新，大概率小说被删了
                return Result.success(local)
            }
        val merged = mergeNovel(
            providerId = providerId,
            novelId = novelId,
            local = local,
            remote = remoteNovel,
        )
        return Result.success(merged)
    }

    private suspend fun mergeNovel(
        providerId: String,
        novelId: String,
        local: WebNovelMetadata,
        remote: WebNovelMetadata,
    ): WebNovelMetadata {
        val merged = mergeToc(
            remoteToc = remote.toc,
            localToc = local.toc,
            isIdUnstable = isProviderIdUnstable(providerId)
        )
        if (merged.reviewReason != null) {
            tocMergeHistoryCollection
                .insertOne(
                    WebNovelTocMergeHistory(
                        id = ObjectId(),
                        providerId = providerId,
                        novelId = novelId,
                        tocOld = local.toc,
                        tocNew = remote.toc,
                        reason = merged.reviewReason,
                    )
                )
        }

        val now = Clock.System.now()
        val list = mutableListOf(
            set(WebNovelMetadata::titleJp.field(), remote.titleJp),
            set(WebNovelMetadata::type.field(), remote.type),
            set(WebNovelMetadata::attentions.field(), remote.attentions),
            set(WebNovelMetadata::keywords.field(), remote.keywords),
            set(WebNovelMetadata::points.field(), remote.points),
            set(WebNovelMetadata::totalCharacters.field(), remote.totalCharacters),
            set(WebNovelMetadata::introductionJp.field(), remote.introductionJp),
            set(WebNovelMetadata::toc.field(), merged.toc),
            set(WebNovelMetadata::syncAt.field(), now),
        )
        if (merged.hasChanged) {
            list.add(set(WebNovelMetadata::changeAt.field(), now))
            list.add(set(WebNovelMetadata::updateAt.field(), now))
        }

        val novel = webNovelMetadataCollection
            .findOneAndUpdate(
                byId(providerId, novelId),
                combine(list),
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
            )!!
        es.syncNovel(novel)
        if (merged.hasChanged) {
            userFavoredWebCollection.updateMany(
                eq(WebNovelFavoriteDbModel::novelId.field(), novel.id),
                set(WebNovelFavoriteDbModel::updateAt.field(), novel.updateAt),
            )
        }
        return novel
    }

    suspend fun increaseVisited(
        userIdOrIp: String,
        providerId: String,
        novelId: String,
    ) = redis.withRateLimit("web-visited:${userIdOrIp}:${providerId}:${novelId}") {
        val novel = webNovelMetadataCollection
            .findOneAndUpdate(
                byId(providerId, novelId),
                inc(WebNovelMetadata::visited.field(), 1),
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
            ) ?: return
        es.syncVisited(novel)
    }

    suspend fun updateTranslation(
        providerId: String,
        novelId: String,
        titleZh: String?,
        introductionZh: String?,
        tocZh: Map<Int, String?>,
    ) {
        val list = mutableListOf(
            set(WebNovelMetadata::titleZh.field(), titleZh),
            set(WebNovelMetadata::introductionZh.field(), introductionZh),
        )
        tocZh.forEach { (index, itemTitleZh) ->
            list.add(
                set(
                    WebNovelMetadata::toc.field() + ".${index}." + WebNovelTocItem::titleZh.field(),
                    itemTitleZh,
                )
            )
        }
        list.add(set(WebNovelMetadata::changeAt.field(), Clock.System.now()))

        webNovelMetadataCollection
            .findOneAndUpdate(
                byId(providerId, novelId),
                combine(list),
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
            )
            ?.also { es.syncNovel(it) }
    }

    suspend fun updateGlossary(
        providerId: String,
        novelId: String,
        glossary: Map<String, String>,
    ) {
        webNovelMetadataCollection
            .updateOne(
                byId(providerId, novelId),
                combine(
                    set(WebNovelMetadata::glossaryUuid.field(), UUID.randomUUID().toString()),
                    set(WebNovelMetadata::glossary.field(), glossary),
                ),
            )
    }

    suspend fun updateWenkuId(
        providerId: String,
        novelId: String,
        wenkuId: String?,
    ): UpdateResult {
        return webNovelMetadataCollection
            .updateOne(
                byId(providerId, novelId),
                set(WebNovelMetadata::wenkuId.field(), wenkuId),
            )
    }
}

private fun RemoteNovelListItem.toOutline(
    providerId: String,
    novel: WebNovelMetadata?,
) =
    WebNovelMetadataListItem(
        providerId = providerId,
        novelId = novelId,
        titleJp = title,
        titleZh = novel?.titleZh,
        type = null,
        attentions = attentions,
        keywords = keywords,
        total = novel?.toc?.count { it.chapterId != null }?.toLong() ?: 0,
        jp = novel?.jp ?: 0,
        baidu = novel?.baidu ?: 0,
        youdao = novel?.youdao ?: 0,
        gpt = novel?.gpt ?: 0,
        sakura = novel?.sakura ?: 0,
        extra = extra,
        updateAt = novel?.updateAt,
    )

fun WebNovelMetadata.toOutline() =
    WebNovelMetadataListItem(
        providerId = providerId,
        novelId = novelId,
        titleJp = titleJp,
        titleZh = titleZh,
        type = type,
        attentions = attentions,
        keywords = keywords,
        total = toc.count { it.chapterId != null }.toLong(),
        jp = jp,
        baidu = baidu,
        youdao = youdao,
        gpt = gpt,
        sakura = sakura,
        extra = null,
        updateAt = updateAt,
    )

fun isProviderIdUnstable(providerId: String): Boolean {
    return providerId == Syosetu.id || providerId == Hameln.id
}

data class MergedResult(
    val toc: List<WebNovelTocItem>,
    val hasChanged: Boolean,
    val reviewReason: String?,
)

fun mergeToc(
    remoteToc: List<WebNovelTocItem>,
    localToc: List<WebNovelTocItem>,
    isIdUnstable: Boolean,
): MergedResult {
    return if (isIdUnstable) {
        mergeTocUnstable(remoteToc, localToc)
    } else {
        mergeTocStable(remoteToc, localToc)
    }
}

private fun mergeTocUnstable(
    remoteToc: List<WebNovelTocItem>,
    localToc: List<WebNovelTocItem>,
): MergedResult {
    val remoteIdToTitle = remoteToc.mapNotNull {
        if (it.chapterId == null) null
        else it.chapterId to it.titleJp
    }.toMap()
    val localIdToTitle = localToc.mapNotNull {
        if (it.chapterId == null) null
        else it.chapterId to it.titleJp
    }.toMap()

    if (remoteIdToTitle.size < localIdToTitle.size) {
        return MergedResult(
            simpleMergeToc(remoteToc, localToc),
            true,
            "有未知章节被删了"
        )
    } else {
        val hasEpisodeTitleChanged = localIdToTitle.any { (eid, localTitle) ->
            val remoteTitle = remoteIdToTitle[eid]
            remoteTitle != localTitle
        }
        return MergedResult(
            simpleMergeToc(remoteToc, localToc),
            remoteIdToTitle.size != localIdToTitle.size,
            if (hasEpisodeTitleChanged) "有章节标题变化" else null
        )
    }
}

private fun mergeTocStable(
    remoteToc: List<WebNovelTocItem>,
    localToc: List<WebNovelTocItem>,
): MergedResult {
    val remoteEpIds = remoteToc.mapNotNull { it.chapterId }
    val localEpIds = localToc.mapNotNull { it.chapterId }
    val noEpDeleted = remoteEpIds.containsAll(localEpIds)
    val noEpAdded = localEpIds.containsAll(remoteEpIds)
    return MergedResult(
        simpleMergeToc(remoteToc, localToc),
        !(noEpAdded && noEpDeleted),
        if (noEpDeleted) null else "有章节被删了"
    )
}

private fun simpleMergeToc(
    remoteToc: List<WebNovelTocItem>,
    localToc: List<WebNovelTocItem>,
): List<WebNovelTocItem> {
    return remoteToc.map { itemNew ->
        val itemOld = localToc.find { it.titleJp == itemNew.titleJp }
        if (itemOld?.titleZh == null) {
            itemNew
        } else {
            itemNew.copy(titleZh = itemOld.titleZh)
        }
    }
}
