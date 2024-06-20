package api

import api.model.WebNovelOutlineDto
import api.model.asDto
import api.plugins.authenticateDb
import api.plugins.user
import infra.common.Page
import infra.user.User
import infra.web.repository.WebNovelReadHistoryRepository
import infra.web.repository.WebNovelMetadataRepository
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.put
import io.ktor.server.routing.*
import org.bson.types.ObjectId
import org.koin.ktor.ext.inject

@Resource("/user/read-history")
private class UserReadHistoryWebRes {
    @Resource("")
    class List(
        val parent: UserReadHistoryWebRes,
        val page: Int,
        val pageSize: Int,
    )

    @Resource("/{providerId}/{novelId}")
    class Novel(
        val parent: UserReadHistoryWebRes,
        val providerId: String,
        val novelId: String,
    )
}

fun Route.routeUserReadHistoryWeb() {
    val service by inject<UserReadHistoryWebApi>()

    authenticateDb {
        get<UserReadHistoryWebRes.List> { loc ->
            val user = call.user()
            call.tryRespond {
                service.listReadHistory(
                    user = user,
                    page = loc.page,
                    pageSize = loc.pageSize,
                )
            }
        }
        put<UserReadHistoryWebRes.Novel> { loc ->
            val user = call.user()
            val chapterId = call.receive<String>()
            call.tryRespond {
                service.updateReadHistory(
                    user = user,
                    providerId = loc.providerId,
                    novelId = loc.novelId,
                    chapterId = chapterId,
                )
            }
        }
        delete<UserReadHistoryWebRes.Novel> { loc ->
            val user = call.user()
            call.tryRespond {
                service.deleteReadHistory(
                    user = user,
                    providerId = loc.providerId,
                    novelId = loc.novelId,
                )
            }
        }
    }
}

class UserReadHistoryWebApi(
    private val historyRepo: WebNovelReadHistoryRepository,
    private val metadataRepo: WebNovelMetadataRepository,
) {
    suspend fun listReadHistory(
        user: User,
        page: Int,
        pageSize: Int,
    ): Page<WebNovelOutlineDto> {
        validatePageNumber(page)
        validatePageSize(pageSize)
        return historyRepo
            .listReaderHistory(
                userId = user.id,
                page = page,
                pageSize = pageSize,
            )
            .map { it.asDto() }
    }

    suspend fun updateReadHistory(
        user: User,
        providerId: String,
        novelId: String,
        chapterId: String,
    ) {
        val novel = metadataRepo.get(providerId, novelId)
            ?: throwNotFound("小说不存在")
        historyRepo.updateReadHistory(
            userId = ObjectId(user.id),
            novelId = novel.id,
            chapterId = chapterId,
        )
    }

    suspend fun deleteReadHistory(
        user: User,
        providerId: String,
        novelId: String,
    ) {
        val novel = metadataRepo.get(providerId, novelId)
            ?: throwNotFound("小说不存在")
        historyRepo.deleteReadHistory(
            userId = ObjectId(user.id),
            novelId = novel.id,
        )
    }
}
