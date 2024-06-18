package infra

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import org.bson.*
import org.bson.codecs.EncoderContext
import org.bson.codecs.configuration.CodecRegistries
import org.bson.codecs.configuration.CodecRegistry
import org.bson.codecs.kotlinx.BsonDecoder
import org.bson.codecs.kotlinx.BsonEncoder
import org.bson.codecs.kotlinx.KotlinSerializerCodecProvider
import org.bson.codecs.kotlinx.defaultSerializersModule
import org.bson.conversions.Bson
import kotlin.reflect.KProperty
import kotlin.reflect.full.findAnnotation
import com.mongodb.kotlin.client.coroutine.MongoClient as KMongoClient

class MongoClient(host: String, port: Int?) {
    private object KInstantSerializer : KSerializer<Instant> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("KInstantSerializer", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Instant) {
            encoder as BsonEncoder
            encoder.encodeBsonValue(BsonDateTime(value.toEpochMilliseconds()))
        }

        override fun deserialize(decoder: Decoder): Instant {
            decoder as BsonDecoder
            return when (val a = decoder.decodeBsonValue()) {
                is BsonString -> Instant.parse(a.value) // 暂时兼容旧的数据格式
                is BsonDateTime -> Instant.fromEpochMilliseconds(a.value)
                else -> throw SerializationException("Unsupported reading date")
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun getAddonCodecRegistry() =
        CodecRegistries.fromProviders(
            KotlinSerializerCodecProvider(
                serializersModule = defaultSerializersModule + SerializersModule {
                    contextual(Instant::class, KInstantSerializer)
                },
            )
        )

    private val client = KMongoClient.create(
        MongoClientSettings
            .builder()
            .codecRegistry(
                CodecRegistries.fromRegistries(
                    getAddonCodecRegistry(),
                    MongoClientSettings.getDefaultCodecRegistry(),
                )
            )
            .applyConnectionString(
                ConnectionString(
                    "mongodb://${host}:${port ?: 27017}"
                ),
            )
            .build()
    )
    val database = client.getDatabase("main")
}

object MongoCollectionNames {
    const val ARTICLE = "article"
    const val COMMENT = "comment-alt"
    const val OPERATION_HISTORY = "operation-history"
    const val SAKURA_WEB_INCORRECT_CASE = "sakura-incorrect-case"
    const val USER = "user"

    const val WEB_NOVEL = "metadata"
    const val WEB_FAVORITE = "web-favorite"
    const val WEB_READ_HISTORY = "web-read-history"

    const val WENKU_NOVEL = "wenku-metadata"
    const val WENKU_FAVORITE = "wenku-favorite"

    // will deprecate
    const val WEB_CHAPTER = "episode"
    const val TOC_MERGE_HISTORY = "toc-merge-history"
}

// MongoDb Util
inline fun <reified T> KProperty<T>.field(): String =
    findAnnotation<SerialName>()?.value ?: name

inline fun <reified T> KProperty<T>.fieldPath(): String =
    "$" + field()

inline fun <reified R : Any> MongoCollection<*>.aggregate(vararg bson: Bson) =
    aggregate<R>(bson.asList())

fun arrayElemAt(path: String, index: Int = 0): Bson =
    Document("\$arrayElemAt", listOf("\$" + path, index))

private class CondExpression<IfExp : Any, ThenExp : Any, ElseExp : Any>(
    val booleanExpression: IfExp?,
    val thenExpression: ThenExp?,
    val elseExpression: ElseExp?,
) : Bson {

    override fun <TDocument> toBsonDocument(
        documentClass: Class<TDocument>,
        codecRegistry: CodecRegistry,
    ): BsonDocument {
        val writer = BsonDocumentWriter(BsonDocument())

        writer.writeStartDocument()
        writer.writeName("\$cond")
        writer.writeStartArray()
        encodeValue(writer, booleanExpression, codecRegistry)
        encodeValue(writer, thenExpression, codecRegistry)
        encodeValue(writer, elseExpression, codecRegistry)
        writer.writeEndArray()
        writer.writeEndDocument()

        return writer.document
    }

    private fun <TItem : Any> encodeValue(writer: BsonDocumentWriter, value: TItem?, codecRegistry: CodecRegistry) {
        when (value) {
            null -> writer.writeNull()
            is Bson -> @Suppress("UNCHECKED_CAST")
            (codecRegistry.get(BsonDocument::class.java) as org.bson.codecs.Encoder<Any>).encode(
                writer,
                value.toBsonDocument(BsonDocument::class.java, codecRegistry),
                EncoderContext.builder().build()
            )

            else -> @Suppress("UNCHECKED_CAST")
            (codecRegistry.get<TItem>(value::class.java as Class<TItem>) as org.bson.codecs.Encoder<TItem>).encode(
                writer,
                value,
                EncoderContext.builder().build()
            )
        }
    }
}

fun <IfExp : Any, ThenExp : Any, ElseExp : Any> cond(
    booleanExpression: IfExp,
    thenExpression: ThenExp,
    elseExpression: ElseExp,
): Bson = CondExpression(booleanExpression, thenExpression, elseExpression)

//            // Common
//            articleCollection.ensureIndex(
//                Article::updateAt,
//                indexOptions = IndexOptions().partialFilterExpression(
//                    Filters.eq(Article::pinned.path(), true)
//                )
//            )
//            articleCollection.ensureIndex(
//                Article::pinned,
//                Article::updateAt,
//            )
//            commentCollection.ensureIndex(
//                Comment::site,
//                Comment::parent,
//                Comment::id,
//            )
//
//            operationHistoryCollection.ensureIndex(
//                OperationHistoryModel::createAt,
//            )
//
//            // User
//            userCollection.ensureUniqueIndex(User::email)
//            userCollection.ensureUniqueIndex(User::username)
//
//            userFavoredWebCollection.ensureUniqueIndex(
//                UserFavoredWebNovelModel::userId,
//                UserFavoredWebNovelModel::novelId,
//            )
//            userFavoredWebCollection.ensureIndex(
//                UserFavoredWebNovelModel::userId,
//                UserFavoredWebNovelModel::createAt,
//            )
//            userFavoredWebCollection.ensureIndex(
//                UserFavoredWebNovelModel::userId,
//                UserFavoredWebNovelModel::updateAt,
//            )
//
//            userReadHistoryWebCollection.ensureUniqueIndex(
//                UserReadHistoryWebModel::userId,
//                UserReadHistoryWebModel::novelId,
//            )
//            userReadHistoryWebCollection.ensureIndex(
//                UserReadHistoryWebModel::userId,
//                UserReadHistoryWebModel::createAt,
//            )
//            userReadHistoryWebCollection.ensureIndex(
//                UserReadHistoryWebModel::createAt,
//                indexOptions = IndexOptions().expireAfter(100, TimeUnit.DAYS),
//            )
//
//            userFavoredWenkuCollection.ensureUniqueIndex(
//                UserFavoredWenkuNovelModel::userId,
//                UserFavoredWenkuNovelModel::novelId,
//            )
//            userFavoredWenkuCollection.ensureIndex(
//                UserFavoredWenkuNovelModel::userId,
//                UserFavoredWenkuNovelModel::createAt,
//            )
//            userFavoredWenkuCollection.ensureIndex(
//                UserFavoredWenkuNovelModel::userId,
//                UserFavoredWenkuNovelModel::updateAt,
//            )
//
//            // Web novel
//            webNovelMetadataCollection.ensureUniqueIndex(
//                WebNovelMetadata::providerId,
//                WebNovelMetadata::novelId,
//            )
//            webNovelChapterCollection.ensureUniqueIndex(
//                WebNovelChapter::providerId,
//                WebNovelChapter::novelId,
//                WebNovelChapter::chapterId,
//            )
