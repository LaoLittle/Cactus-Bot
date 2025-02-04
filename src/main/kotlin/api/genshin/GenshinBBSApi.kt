package icu.dnddl.plugin.genshin.api.genshin

import icu.dnddl.plugin.genshin.CactusData
import icu.dnddl.plugin.genshin.api.Action.GENSHIN_SIGN
import icu.dnddl.plugin.genshin.api.TAKUMI_API
import icu.dnddl.plugin.genshin.api.WEB_STATIC
import icu.dnddl.plugin.genshin.api.buildUrlParameters
import icu.dnddl.plugin.genshin.api.genshin.GenshinBBSApi.GenshinServer.CN_GF01
import icu.dnddl.plugin.genshin.api.genshin.GenshinBBSApi.GenshinServer.CN_QD01
import icu.dnddl.plugin.genshin.api.genshin.data.*
import icu.dnddl.plugin.genshin.api.internal.*
import icu.dnddl.plugin.genshin.util.Json
import icu.dnddl.plugin.genshin.util.cacheFolder
import icu.dnddl.plugin.genshin.util.decode
import icu.dnddl.plugin.genshin.util.randomUUID
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

object GenshinBBSApi {
    private const val GAME_RECORD = "$TAKUMI_API/game_record/app"

    /**
     * ```
     * ?role_id=&server=
     * ```
     *
     * ```
     * ?dailyNote&server=
     * ```
     */
    private const val GENSHIN_GAME_RECORD = "$GAME_RECORD/genshin/api"
    private const val SIGN_API = "$TAKUMI_API/event/bbs_sign_reward"
    private const val GACHA_INFO = "$WEB_STATIC/hk4e/gacha_info"

    suspend fun getPlayerInfo(
        uid: Long,
        cookies: String = CactusData.cookie,
        uuid: String = randomUUID
    ): GenshinRecord {
        val params = buildUrlParameters {
            "role_id" sets uid
            "server" sets getServerFromUID(uid)
        }

        val response = getBBS(
            url = "$GENSHIN_GAME_RECORD/index?$params",
            cookies,
            uuid
        )

        return response.getOrThrow().data.decode()
    }

    suspend fun getGachaInfo(
        server: GenshinServer,
        cookies: String = "",
        uuid: String = randomUUID,
        useCache: Boolean = true
    ): List<GachaInfo> {
        val cacheFile = cacheFolder.resolve("gacha_info_$server.json")
        if (useCache && cacheFile.isFile)
            return Json.decodeFromString(Json.serializersModule.serializer(), cacheFile.readText())

        val response = getBBS("$GACHA_INFO/$server/gacha/list.json", cookies, uuid)

        return response.getOrThrow().data["list"]!!.decode()
    }

    suspend fun getDailyNote(
        uid: Long,
        cookies: String,
        uuid: String = randomUUID
    ): DailyNote {
        val params = buildUrlParameters {
            "role_id" sets uid
            "server" sets getServerFromUID(uid)
        }

        val response = getBBS(
            url = "$GENSHIN_GAME_RECORD/dailyNote?$params",
            cookies,
            uuid
        )

        return response.getOrThrow().data.decode()
    }

    suspend fun getSpiralAbyss(
        uid: Long,
        cookies: String,
        uuid: String = randomUUID,
        period: Boolean = false,
    ): MiHoYoBBSResponse {
        val params = buildUrlParameters {
            "role_id" sets uid
            "schedule_type" sets if (!period) 1 else 2
            "server" sets getServerFromUID(uid)
        }

        val response = getBBS(
            url = "$GENSHIN_GAME_RECORD/spiralAbyss?$params",
            cookies,
            uuid
        )

        return response
    }

    /**
     * returns a JsonObject
     * not [MiHoYoBBSResponse]
     */
    suspend fun getGachaDetail(
        server: GenshinServer,
        cookies: String = "",
        uuid: String = randomUUID,
        gachaId: String,
        useCache: Boolean = true
    ): GachaDetail { // todo: 解析
        val cacheFile = cacheFolder.resolve("gacha_info_${server}_$gachaId.json")
        if (useCache && cacheFile.isFile)
            return Json.decodeFromString(GachaDetail.serializer(), cacheFile.readText())

        val url = "$GACHA_INFO/$server/$gachaId/zh-cn.json"

        return Json.decodeFromString(GachaDetail.serializer(), client.get(url) {
            setHeaders(url, "", cookies, uuid)
        })
    }

    private suspend fun getSignInfo(
        uid: Long,
        region: GenshinServer,
        cookies: String,
        uuid: String = randomUUID
    ): SignInfo {
        val params = buildUrlParameters {
            "act_id" sets GENSHIN_SIGN
            "region" sets region
            "uid" sets uid
        }

        val response = getBBS(
            "$SIGN_API/info?$params",
            cookies,
            uuid
        )

        return response.getOrThrow().data.decode()
    }

    suspend fun getAwards(): Award {
        val params = buildUrlParameters {
            "act_id" sets GENSHIN_SIGN
        }

        return getBBS("$SIGN_API/home?$params").getOrThrow().data.decode()
    }

    private const val SIGN_REFERRER =
        "https://webstatic.mihoyo.com/bbs/event/signin-ys/index.html?bbs_auth_required=true&act_id=$GENSHIN_SIGN&utm_source=bbs&utm_medium=mys&utm_campaign=icon"

    private suspend fun signGenshin(
        genshinUID: Long,
        region: GenshinServer,
        cookies: String,
        uuid: String = randomUUID
    ): MiHoYoBBSResponse {
        val appVersion = "2.10.2"
        val response = postBBS(
            url = "$SIGN_API/sign",
            cookies,
            uuid,
            header = {
                set("x-rpc-app_version", appVersion)
                set("DS", getSignDS())
                set(HttpHeaders.Referrer, SIGN_REFERRER)
            },
        ) {
            userAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 14_0_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) miHoYoBBS/$appVersion")
            contentType(ContentType.Application.Json)
            body = buildJsonObject {
                put("act_id", GENSHIN_SIGN)
                put("region", region.toString())
                put("uid", genshinUID)
            }.toString()
        }

        return response.getOrThrow()
    }

    suspend fun signGenshinWithAward(
        genshinUID: Long,
        region: GenshinServer,
        cookies: String,
        uuid: String = randomUUID
    ): SignResponse {
        val signInfo = getSignInfo(genshinUID, region, cookies, uuid)
        val response = if (!signInfo.isSign) signGenshin(genshinUID, region, cookies, uuid) else null
        return SignResponse(response, signInfo, CactusData.awards[signInfo.totalSignDay])
    }

    suspend fun getGameRecordCard(uid: Long): MiHoYoBBSResponse { //todo 解析
        val url = "$GAME_RECORD/card/wapi/getGameRecordCard?uid=$uid"

        return getBBS(url)
    }

    fun getServerFromUID(uid: Long) = if (uid < 500000000) CN_GF01
    else CN_QD01

    @Serializable
    enum class GenshinServer {
        /**
         * 官服
         * */
        @SerialName("cn_gf01")
        CN_GF01,


        /**
         * 渠道服
         * */
        @SerialName("cn_qd01")
        CN_QD01;

        override fun toString(): String = name.lowercase()
    }
}
