package org.laolittle.plugin.genshin.service

import kotlinx.coroutines.delay
import net.mamoe.mirai.Bot
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.utils.verbose
import org.laolittle.plugin.genshin.CactusBot
import org.laolittle.plugin.genshin.CactusData
import org.laolittle.plugin.genshin.database.User
import org.laolittle.plugin.genshin.database.UserSetting
import org.laolittle.plugin.genshin.database.Users
import org.laolittle.plugin.genshin.database.cactusSuspendedTransaction
import org.laolittle.plugin.genshin.util.signGenshin
import kotlin.random.Random

object GenshinSignProver : AbstractCactusTimerService(
    serviceName = "GenshinSign",
) {
    private val logger get() = CactusBot.logger
    private val autoSign: Set<Long>
        get() {
            val foo = mutableSetOf<Long>()
            CactusData.userSetting.forEach { (userId, setting) ->
                if (setting.autoSign) foo.add(userId)
            }
            return foo
        }

    override suspend fun main() {
        cactusSuspendedTransaction {
            User.find { Users.id inList autoSign }.forEach { userData ->
                delay(3_000)
                var subject: Contact? = null
                val pushSubject = (CactusData.userSetting[userData.id.value]
                    ?: UserSetting(pushSubject = userData.id.value)).pushSubject
                for (bot in Bot.instances) {
                    if (bot.isOnline) {
                        subject = bot.getFriend(pushSubject) ?: bot.getGroup(pushSubject) ?: continue
                        break
                    }
                }
                if (userData.data.cookies.isNotBlank()) {
                    logger.verbose { "开始执行用户${userData.id.value} (${userData.genshinUID})的签到" }
                    runCatching {
                        userData.signGenshin()
                    }.onSuccess {
                        subject?.sendMessage("旅行者${userData.genshinUID}签到成功！")
                    }.getOrElse { e ->
                        subject?.sendMessage("签到失败！原因: ${e.message}")
                        //if (e is ApiAccessDeniedException && e.restCode == 0)
                        return@forEach
                    }
                }

                delay(Random.nextLong(10_000, 30_000))
            }
        }

        delay(Random.nextLong(aDay shr 4))
    }
}