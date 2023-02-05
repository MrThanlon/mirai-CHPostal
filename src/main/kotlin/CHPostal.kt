package xyz.ch34k.chpostal

import kotlinx.coroutines.*
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.event.*
import net.mamoe.mirai.event.events.*
import net.mamoe.mirai.utils.info
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.contact.MemberPermission
import net.mamoe.mirai.message.data.PlainText

object GroupBinding : AutoSavePluginData("GroupBinding") {
    val bindings: MutableMap<Long, MutableSet<Long>> by value()
}

object Config : AutoSavePluginConfig("Config") {
    // 欢迎消息
    val greetingMessage: String by value("欢迎使用CH邮局，我是自动手记人偶薇尔莉特，竭诚为您服务")
    // 设置成功信息
    val successMessage: String by value("设置成功，薇尔莉特将竭诚为您服务")
    // 错误提示
    val warningMessage: String by value("设置错误，请认真阅读文档：https://github.com/MrThanlon/mirai-CHPostal#readme")
    // 防止存在多个同步机器人时引发消息风暴，用于识别群中的机器人，目前未使用
    val preventMessageStorm: String by value("\n——CH Postal")
}

// TODO: 跨Bot群聊处理，权限控制
object CHPostal : KotlinPlugin(
        JvmPluginDescription(
            id = "xyz.ch34k.chpostal",
            name = "CH Postal",
            version = "0.1.0",
        ) {
            author("hzy")
        }
) {
    override fun onEnable() {
        logger.info { "Violet Evergarden is at your service." }
        Config.reload()
        GroupBinding.reload()
        // 登录
        globalEventChannel().subscribeAlways(
            BotOnlineEvent::class,
            CoroutineExceptionHandler { _, throwable ->
                logger.error(throwable)
            },
            priority = EventPriority.HIGH,
        ) call@{
            it.bot
        }
        // 离线
        globalEventChannel().subscribeAlways(
            BotOfflineEvent::class,
            CoroutineExceptionHandler { _, throwable ->
                logger.error(throwable)
            },
            priority = EventPriority.HIGH,
        ) call@{
            it.bot
        }
        // 群消息，转发
        globalEventChannel().subscribeAlways(
            GroupMessageEvent::class,
            CoroutineExceptionHandler { _, throwable ->
                logger.error(throwable)
            },
            priority = EventPriority.NORMAL,
        ) call@{
            // FIXME: 判断是否需要转发，使用preventMessageStorm
            if (Bot.getInstanceOrNull(sender.id) != null) {
                return@call
            }
            // 转发到指定群聊
            for (g in GroupBinding.bindings[group.id] ?: return@call) {
                if (g == group.id) {
                    continue
                }
                CHPostal.launch {
                    val group = bot.getGroup(g) ?: return@launch
                    group.sendMessage(PlainText("$senderName:\n") + message)
                }
            }
        }
        // 私聊消息，修改群绑定信息
        globalEventChannel().subscribeAlways(
            FriendMessageEvent::class,
            CoroutineExceptionHandler { _, throwable ->
                logger.error(throwable)
            },
            priority = EventPriority.NORMAL,
        ) call@{
            val m = message.contentToString()
            if (m == "?") {
                // TODO: 查询绑定
                return@call
            }
            // FIXME: 获取要绑定的群号
            val groups = m.split("\n").map {
                it.toLongOrNull() ?: 0
            }
            if (groups.size != 2) {
                sender.sendMessage(Config.warningMessage)
                return@call
            }
            // 确定发送者是两个群的管理员
            val p1 = bot.getGroup(groups[0])?.get(sender.id)?.permission
            val p2 = bot.getGroup(groups[1])?.get(sender.id)?.permission
            if (!((p1 == MemberPermission.ADMINISTRATOR ||
                p1 == MemberPermission.OWNER) &&
                (p2 == MemberPermission.ADMINISTRATOR ||
                p2 == MemberPermission.OWNER))) {
                sender.sendMessage(Config.warningMessage)
                return@call
            }
            // 绑定或解绑，查询两个群是否已经绑定
            if (GroupBinding.bindings[groups[0]]?.contains(groups[1]) == true) {
                // 已绑定，将g2从g1的组中分离出来
                GroupBinding.bindings.remove(groups[1])
                GroupBinding.bindings[groups[0]]!!.remove(groups[1])
            } else {
                // 未绑定，将g2加入到g1的组
                if (GroupBinding.bindings.contains(groups[1])) {
                    // 脱离原来的组
                    GroupBinding.bindings[groups[1]]!!.remove(groups[1])
                }
                val s = GroupBinding.bindings[groups[0]] ?: mutableSetOf(groups[0])
                s.add(groups[1])
                GroupBinding.bindings[groups[0]] = s
                GroupBinding.bindings[groups[1]] = s
            }
            GroupBinding.save()
            sender.sendMessage(Config.successMessage)
        }
        // 自动加群
        globalEventChannel().subscribeAlways(
            BotInvitedJoinGroupRequestEvent::class,
            CoroutineExceptionHandler { _, throwable ->
                logger.error(throwable)
            },
            priority = EventPriority.MONITOR,
        ) call@{
            it.accept()
        }
        // 被踢，修改绑定信息
        globalEventChannel().subscribeAlways(
            BotLeaveEvent::class,
            CoroutineExceptionHandler { _, throwable ->
                logger.error(throwable)
            },
            priority = EventPriority.MONITOR,
        ) call@{
            GroupBinding.bindings[it.groupId]?.remove(it.groupId)
            GroupBinding.bindings.remove(it.groupId)
            GroupBinding.save()
        }
    }
}