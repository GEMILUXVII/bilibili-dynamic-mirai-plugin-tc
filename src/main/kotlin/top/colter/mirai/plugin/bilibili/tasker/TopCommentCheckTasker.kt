package top.colter.mirai.plugin.bilibili.tasker

import java.time.Instant
import kotlinx.coroutines.launch
import top.colter.mirai.plugin.bilibili.BiliBiliDynamic
import top.colter.mirai.plugin.bilibili.BiliConfig
import top.colter.mirai.plugin.bilibili.api.getComment
import top.colter.mirai.plugin.bilibili.api.getUserNewDynamic
import top.colter.mirai.plugin.bilibili.data.DynamicItem
import top.colter.mirai.plugin.bilibili.data.DynamicMessage
import top.colter.mirai.plugin.bilibili.data.DynamicType
import top.colter.mirai.plugin.bilibili.data.UpperComment
import top.colter.mirai.plugin.bilibili.draw.makeDrawTopComment
import top.colter.mirai.plugin.bilibili.service.dynamic
import top.colter.mirai.plugin.bilibili.utils.cachePath
import top.colter.mirai.plugin.bilibili.utils.logger

object TopCommentCheckTasker : BiliCheckTasker("TopCommentCheckTasker") {
  // 使用独立的置顶评论检测间隔，默认5秒，比动态检测更快
  override var interval = BiliConfig.checkConfig.topCommentInterval

  // 错误计数器，用于检测风控
  private var errorCount = 0
  private var lastErrorTime = 0L
  private const val ERROR_THRESHOLD = 3 // 连续错误阈值
  private const val BACKOFF_SECONDS = 60 // 触发风控后等待时间

  override suspend fun main() {
    val users = dynamic.keys.filter { it != 0L }
    if (users.isEmpty()) return

    val enabledUsers = users.filter { uid -> dynamic[uid]?.isCheckTop == true }

    if (enabledUsers.isEmpty()) {
      logger.debug("[TopComment] 没有启用置顶评论检测的用户（使用 /bili topComment <用户> 开启）")
      return
    }

    logger.debug("[TopComment] 开始检测置顶评论，共 ${enabledUsers.size}/${users.size} 个用户已启用")

    enabledUsers.forEach { uid ->
      val subData = dynamic[uid] ?: return@forEach
      logger.debug("[TopComment] 检测用户 ${subData.name}(${uid}) 的置顶评论")
      launch { checkTopComment(uid) }
    }
  }

  private suspend fun checkTopComment(uid: Long) {
    val subData = dynamic[uid] ?: return
    logger.debug("[TopComment] 获取 ${subData.name}(${uid}) 的动态列表")

    try {
      val dynamicList = client.getUserNewDynamic(uid, true)
      logger.debug("[TopComment] ${subData.name} 共有 ${dynamicList?.items?.size ?: 0} 条动态")

      // 请求成功，重置错误计数
      errorCount = 0

      val topDynamic = dynamicList?.items?.firstOrNull { it.modules.moduleTag?.text == "置顶" }

      if (topDynamic != null) {
        logger.debug("[TopComment] 找到置顶动态: ${topDynamic.did}, 类型: ${topDynamic.type}")

        try {
          // 检查置顶动态是否换了
          val currentTopDynamicId = topDynamic.idStr ?: topDynamic.did
          val isTopDynamicChanged =
                  subData.lastTopDynamic.isNotEmpty() &&
                          subData.lastTopDynamic != currentTopDynamicId
          val isFirstTimeEver = subData.lastTopDynamic.isEmpty()

          if (isTopDynamicChanged) {
            logger.info(
                    "[TopComment] ${subData.name} 的置顶动态已更换: ${subData.lastTopDynamic} -> $currentTopDynamicId"
            )
            subData.lastTopDynamic = currentTopDynamicId
            subData.lastTopComment = 0 // 重置置顶评论记录
          } else if (isFirstTimeEver) {
            logger.info("[TopComment] 首次检测到 ${subData.name} 有置顶动态: $currentTopDynamicId")
            subData.lastTopDynamic = currentTopDynamicId
          }

          // 使用动态自带的评论区信息
          val commentId = topDynamic.basic.commentIdStr
          val commentType = topDynamic.basic.commentType
          logger.debug("[TopComment] 评论区 ID: $commentId, Type: $commentType")

          val comment = client.getComment(commentId, commentType)
          // 从新的API结构中获取置顶评论
          val topReply = comment?.upper?.top ?: comment?.top
          val topComment = topReply?.let { UpperComment.fromReply(it) }

          if (topComment != null) {
            logger.info(
                    "[TopComment] 找到置顶评论 rpid=${topComment.rpid}, " +
                            "上次置顶动态=${subData.lastTopDynamic}, " +
                            "当前置顶动态=$currentTopDynamicId, " +
                            "上次评论=${subData.lastTopComment}, " +
                            "动态更换=$isTopDynamicChanged, " +
                            "首次检测=$isFirstTimeEver"
            )

            // 详细的条件判断日志
            logger.debug(
                    "[TopComment] 条件判断: " +
                            "isFirstTimeEver=$isFirstTimeEver, " +
                            "lastTopComment=${subData.lastTopComment}, " +
                            "currentComment=${topComment.rpid}, " +
                            "评论是否变化=${subData.lastTopComment != topComment.rpid}"
            )

            when {
              // 真正的首次检测（插件刚启动，从未检测过任何置顶）
              isFirstTimeEver && subData.lastTopComment == 0L -> {
                subData.lastTopComment = topComment.rpid
                logger.info("[TopComment] 首次检测，记录 ${subData.name} 的置顶评论: ${topComment.rpid}，不推送")
              }
              // 置顶评论 ID 发生变化（包括动态更换或评论更新）
              subData.lastTopComment != 0L && subData.lastTopComment != topComment.rpid -> {
                val oldRpid = subData.lastTopComment
                subData.lastTopComment = topComment.rpid

                if (isTopDynamicChanged) {
                  logger.info(
                          "[TopComment] ✅ 检测到 ${subData.name} 更换置顶动态且有新置顶评论: $oldRpid -> ${topComment.rpid}，准备推送"
                  )
                } else {
                  logger.info(
                          "[TopComment] ✅ 检测到 ${subData.name} 的置顶评论更新: $oldRpid -> ${topComment.rpid}，准备推送"
                  )
                }

                pushTopComment(topComment, topDynamic, subData.name, subData.contacts)
              }
              // 置顶动态刚更换，评论记录已被重置为0，推送新动态的置顶评论
              isTopDynamicChanged && subData.lastTopComment == 0L -> {
                subData.lastTopComment = topComment.rpid
                logger.info(
                        "[TopComment] ✅ 检测到 ${subData.name} 更换置顶动态，发现新置顶评论: ${topComment.rpid}，准备推送"
                )
                pushTopComment(topComment, topDynamic, subData.name, subData.contacts)
              }
              // 上次记录为0但不是首次检测（之前动态没评论，现在有评论了）
              !isFirstTimeEver && subData.lastTopComment == 0L -> {
                subData.lastTopComment = topComment.rpid
                logger.info("[TopComment] ✅ 检测到 ${subData.name} 新增置顶评论: ${topComment.rpid}，准备推送")
                pushTopComment(topComment, topDynamic, subData.name, subData.contacts)
              }
              // 置顶评论未变化
              else -> {
                logger.debug("[TopComment] ${subData.name} 的置顶评论未更新 (rpid=${topComment.rpid})")
              }
            }
          } else {
            logger.debug("[TopComment] ${subData.name} 的置顶动态没有置顶评论")
            if (subData.lastTopComment != 0L) {
              logger.info("[TopComment] ${subData.name} 的置顶评论已被取消")
              subData.lastTopComment = 0
            }
          }
        } catch (e: Exception) {
          logger.warning("[TopComment] 获取 ${subData.name} 的置顶动态评论失败: ${e.message}")
        }
      } else {
        logger.debug("[TopComment] ${subData.name} 没有置顶动态")
        if (subData.lastTopDynamic.isNotEmpty()) {
          logger.info("[TopComment] ${subData.name} 的置顶动态已被取消")
          subData.lastTopDynamic = ""
          subData.lastTopComment = 0
        }
      }
    } catch (e: Exception) {
      val errorMsg = e.message ?: ""

      // 检测是否是风控错误 (-352)
      if (errorMsg.contains("CODE: -352")) {
        errorCount++
        val currentTime = System.currentTimeMillis()

        // 如果距离上次错误时间超过1分钟，重置计数
        if (currentTime - lastErrorTime > 60000) {
          errorCount = 1
        }
        lastErrorTime = currentTime

        if (errorCount >= ERROR_THRESHOLD) {
          logger.warning(
                  "[TopComment] ⚠️ 检测到B站风控(-352)，已连续出错 $errorCount 次！" +
                          "将暂停 $BACKOFF_SECONDS 秒后自动恢复。" +
                          "建议增加 topCommentInterval 配置值(当前${BiliConfig.checkConfig.topCommentInterval}秒)"
          )
          // 临时增加间隔
          kotlinx.coroutines.delay(BACKOFF_SECONDS * 1000L)
          errorCount = 0 // 重置计数
        } else {
          logger.warning(
                  "[TopComment] 检测 ${subData.name} 时触发风控(-352)，" +
                          "错误次数: $errorCount/$ERROR_THRESHOLD"
          )
        }
      } else {
        logger.error("[TopComment] 检测 ${subData.name} 的置顶评论时出错", e)
      }
    }
  }

  /**
   * 手动拉取指定用户的置顶评论
   * @param uid 用户UID
   * @param contact 推送目标（群号或QQ号）
   * @return 拉取结果消息
   */
  suspend fun fetchTopComment(uid: Long, contact: String): String {
    val subData = dynamic[uid] ?: return "未订阅该用户"

    logger.info("[TopComment] 手动拉取 ${subData.name}(${uid}) 的置顶评论")

    return try {
      val dynamicList = client.getUserNewDynamic(uid, true)
      val topDynamic = dynamicList?.items?.firstOrNull { it.modules.moduleTag?.text == "置顶" }

      if (topDynamic != null) {
        logger.debug("[TopComment] 找到置顶动态: ${topDynamic.did}, 类型: ${topDynamic.type}")

        try {
          // 使用动态自带的评论区信息
          val commentId = topDynamic.basic.commentIdStr
          val commentType = topDynamic.basic.commentType
          logger.debug("[TopComment] 评论区 ID: $commentId, Type: $commentType")

          val comment = client.getComment(commentId, commentType)
          // 从新的API结构中获取置顶评论
          val topReply = comment?.upper?.top ?: comment?.top
          val topComment = topReply?.let { UpperComment.fromReply(it) }

          if (topComment != null) {
            // 手动拉取时不更新 lastTopComment,只推送
            pushTopComment(topComment, topDynamic, subData.name, setOf(contact))
            "已拉取 ${subData.name} 的置顶评论"
          } else {
            "${subData.name} 的置顶动态下没有置顶评论"
          }
        } catch (e: Exception) {
          logger.warning("[TopComment] 获取置顶动态评论失败: ${e.message}")
          "${subData.name} 的置顶动态暂时无法获取评论 (${e.message})"
        }
      } else {
        "${subData.name} 没有置顶动态"
      }
    } catch (e: Exception) {
      logger.error("[TopComment] 拉取 ${subData.name} 的置顶评论失败", e)
      "拉取失败: ${e.message}"
    }
  }

  private suspend fun pushTopComment(
          comment: UpperComment,
          dynamic: DynamicItem,
          dynamicAuthor: String,
          contacts: Set<String>
  ) {
    try {
      logger.debug("[TopComment] 开始绘制评论卡片: rpid=${comment.rpid}")

      // 使用与动态推送相同的方式，添加渐变背景
      val colors = listOf(0xFFd3edfa.toInt()) // 使用浅蓝色背景
      val imagePath = dynamic.makeDrawTopComment(comment, colors)

      logger.debug("[TopComment] 图片已生成并缓存到: $imagePath")

      // 验证文件是否存在
      val fullPath = cachePath.resolve(imagePath)
      if (!fullPath.toFile().exists()) {
        logger.error("[TopComment] 缓存的图片文件不存在: $fullPath")
        return
      }
      val fileSize = fullPath.toFile().length()
      logger.debug("[TopComment] 验证图片文件存在, 大小: $fileSize 字节")

      // 构造动态链接
      val dynamicId = dynamic.idStr ?: dynamic.did
      val dynamicLink = top.colter.mirai.plugin.bilibili.data.DYNAMIC_LINK(dynamicId)
      logger.debug("[TopComment] 动态链接: $dynamicLink")

      // 提取评论中的图片
      val commentImages = comment.content.pictures
      if (commentImages != null && commentImages.isNotEmpty()) {
        logger.debug("[TopComment] 评论包含 ${commentImages.size} 张图片")
      }

      contacts.forEach {
        val msg =
                DynamicMessage(
                        did = comment.rpid.toString(),
                        mid = comment.member.mid.toLongOrNull() ?: 0L,
                        name = comment.member.name,
                        type = DynamicType.DYNAMIC_TYPE_WORD, // 使用WORD类型，会触发TextOnly等模板
                        time = Instant.now().toString(),
                        timestamp = Instant.now().epochSecond.toInt(),
                        content = comment.content.message,
                        images = commentImages, // 传递评论图片URL
                        links = listOf(DynamicMessage.Link(tag = "查看动态", value = dynamicLink)),
                        drawPath = imagePath,
                        contact = it
                )
        logger.debug("[TopComment] 准备发送消息到 $it, drawPath=$imagePath")
        BiliBiliDynamic.messageChannel.send(msg)
        logger.debug("[TopComment] 消息已发送到channel")
      }
      logger.info("[TopComment] 置顶评论推送成功")
    } catch (e: Exception) {
      logger.error("[TopComment] 推送置顶评论失败", e)
    }
  }
}
