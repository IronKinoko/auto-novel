package api

import api.plugins.authenticateDb
import api.plugins.shouldBeAtLeast
import api.plugins.user
import infra.common.Page
import infra.user.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

@Resource("/user")
private class UserRes {
    @Resource("")
    class List(
        val parent: UserRes,
        val page: Int,
        val pageSize: Int,
        val role: UserRole,
    )

    @Resource("/favored")
    class Favored(val parent: UserRes)
}

fun Route.routeUser() {
    val service by inject<UserApi>()

    authenticateDb {
        get<UserRes.List> { loc ->
            val user = call.user()
            call.tryRespond {
                service.listUser(
                    user = user,
                    page = loc.page,
                    pageSize = loc.pageSize,
                    role = loc.role,
                )
            }
        }
        get<UserRes.Favored> {
            val user = call.user()
            call.tryRespond {
                service.listFavored(
                    user = user,
                )
            }
        }
    }
}

class UserApi(
    private val userRepo: UserRepository,
    private val userFavoredRepo: UserFavoredRepository,
) {
    @Serializable
    data class UserOutlineDto(
        val id: String,
        val email: String,
        val username: String,
        val role: UserRole,
        val createdAt: Long,
    )

    suspend fun listUser(
        user: User,
        page: Int,
        pageSize: Int,
        role: UserRole,
    ): Page<UserOutlineDto> {
        user.shouldBeAtLeast(UserRole.Admin)
        return userRepo.listUser(
            page = page,
            pageSize = pageSize,
            role = role,
        ).map {
            UserOutlineDto(
                id = it.id,
                email = it.email,
                username = it.username,
                role = it.role,
                createdAt = it.createdAt.epochSeconds,
            )
        }
    }

    suspend fun listFavored(user: User): UserFavoredList {
        return userFavoredRepo.getFavoredList(user.id)
            ?: throwNotFound("用户不存在")
    }
}
