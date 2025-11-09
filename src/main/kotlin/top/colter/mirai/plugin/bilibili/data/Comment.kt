package top.colter.mirai.plugin.bilibili.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// B站评论API的返回结构
@Serializable
data class Comment(
        val page: Page? = null,
        val config: Config? = null,
        val replies: List<Reply>? = null,
        @SerialName("top_replies") val topReplies: List<Reply>? = null,
        val upper: Upper? = null,
        val top: Reply? = null
) {
  @Serializable
  data class Page(val num: Int = 0, val size: Int = 0, val count: Int = 0, val acount: Int = 0)

  @Serializable
  data class Config(
          val showtopic: Int = 0,
          @SerialName("show_up_flag") val showUpFlag: Boolean = false,
          @SerialName("read_only") val readOnly: Boolean = false
  )

  @Serializable
  data class Upper(val mid: Long = 0, val top: Reply? = null, val vote: Reply? = null)
}

// 评论数据结构（保持向后兼容，用于返回给调用方）
@Serializable
data class CommentData(
        val upper: UpperComment? = null,
        val top: UpperComment? = null,
)

// 单条评论
@Serializable
data class Reply(
        val rpid: Long,
        val oid: Long,
        val type: Int,
        val mid: Long,
        val member: Member,
        val content: Content,
        @SerialName("reply_control") val replyControl: ReplyControl? = null
) {
  @Serializable
  data class Member(
          val mid: String = "",
          @SerialName("uname") val uname: String = "",
          val avatar: String = ""
  )

  @Serializable
  data class Content(
          val message: String = "",
          val emote: Map<String, Emote>? = null, // B站表情包
          val pictures: List<Picture>? = null // 评论中的图片
  )

  @Serializable
  data class Picture(
          @SerialName("img_src") val imgSrc: String = "",
          @SerialName("img_width") val imgWidth: Int = 0,
          @SerialName("img_height") val imgHeight: Int = 0
  )

  @Serializable
  data class Emote(
          val id: Long = 0,
          val text: String = "",
          val url: String = "",
          @SerialName("package_id") val packageId: Long = 0,
          val type: Int = 0
  )

  @Serializable data class ReplyControl(@SerialName("is_up_top") val isUpTop: Boolean? = null)
}

// 用于绘图和推送的简化结构
@Serializable
data class UpperComment(val rpid: Long, val member: Member, val content: Content) {
  @Serializable
  data class Member(val mid: String, @SerialName("uname") val name: String, val avatar: String)

  @Serializable
  data class Content(
          val message: String,
          val emote: Map<String, Emote>? = null, // B站表情包
          val pictures: List<String>? = null // 图片URL列表(简化后的)
  )

  @Serializable data class Emote(val text: String, val url: String)

  companion object {
    // 从Reply转换为UpperComment
    fun fromReply(reply: Reply): UpperComment {
      // 转换表情包数据
      val emoteMap =
              reply.content.emote?.mapValues { (_, emote) ->
                Emote(text = emote.text, url = emote.url)
              }

      // 提取图片URL
      val pictures = reply.content.pictures?.map { it.imgSrc }

      return UpperComment(
              rpid = reply.rpid,
              member =
                      Member(
                              mid = reply.member.mid,
                              name = reply.member.uname,
                              avatar = reply.member.avatar
                      ),
              content =
                      Content(
                              message = reply.content.message,
                              emote = emoteMap,
                              pictures = pictures
                      )
      )
    }
  }
}
