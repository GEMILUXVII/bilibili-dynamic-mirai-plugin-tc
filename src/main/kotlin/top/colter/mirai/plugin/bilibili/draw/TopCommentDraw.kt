package top.colter.mirai.plugin.bilibili.draw

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import top.colter.mirai.plugin.bilibili.data.DynamicItem
import top.colter.mirai.plugin.bilibili.data.UpperComment
import top.colter.mirai.plugin.bilibili.utils.CacheType
import top.colter.mirai.plugin.bilibili.utils.FontUtils
import top.colter.mirai.plugin.bilibili.utils.cacheImage
import top.colter.mirai.plugin.bilibili.utils.getOrDownloadImage

/** 绘制置顶评论卡片（带背景） 参考 DynamicDraw.kt 的设计风格 */
suspend fun DynamicItem.makeDrawTopComment(comment: UpperComment, colors: List<Int>): String {
  val cardImage = drawTopCommentCard(comment, this)
  val img = makeCardBg(cardImage.height, colors) { it.drawImage(cardImage, 0f, 0f) }
  return cacheImage(img, "${comment.rpid}_comment.png", CacheType.DRAW_DYNAMIC)
}

/** 绘制评论内容，支持B站表情包 */
private suspend fun drawCommentContent(comment: UpperComment, textWidth: Float): Image {
  val message = comment.content.message
  val emotes = comment.content.emote

  // 如果没有表情，直接绘制文本
  if (emotes.isNullOrEmpty()) {
    val contentParagraphStyle =
            ParagraphStyle().apply {
              maxLinesCount = 15
              ellipsis = "..."
              alignment = Alignment.LEFT
              textStyle = contentTextStyle
            }
    val paragraph =
            ParagraphBuilder(contentParagraphStyle, FontUtils.fonts)
                    .addText(message)
                    .build()
                    .layout(textWidth)

    return Surface.makeRasterN32Premul(textWidth.toInt(), paragraph.height.toInt())
            .apply { canvas.apply { paragraph.paint(this, 0f, 0f) } }
            .makeImageSnapshot()
  }

  // 有表情的情况，需要手动绘制
  val lineHeight = font.size + quality.lineSpace
  val emojiSize = font.size * 1.2f // 表情稍大一些

  // 分割文本，找出表情位置
  var remainingText = message
  val segments = mutableListOf<Pair<String, String?>>() // (text, emoteUrl?)

  emotes.forEach { (emoteText, emote) ->
    val parts = remainingText.split(emoteText, limit = 2)
    if (parts.size == 2) {
      if (parts[0].isNotEmpty()) {
        segments.add(Pair(parts[0], null))
      }
      segments.add(Pair(emoteText, emote.url))
      remainingText = parts[1]
    }
  }
  if (remainingText.isNotEmpty()) {
    segments.add(Pair(remainingText, null))
  }

  // 计算实际需要的高度
  var currentX = 0f
  var currentY = emojiSize

  segments.forEach { (text, url) ->
    if (url != null) {
      if (currentX + emojiSize > textWidth) {
        currentX = 0f
        currentY += lineHeight
      }
      currentX += emojiSize
    } else {
      val textLine = TextLine.make(text, font)
      if (currentX + textLine.width > textWidth) {
        currentX = 0f
        currentY += lineHeight
      }
      currentX += textLine.width
    }
  }
  val estimatedHeight = currentY + lineHeight

  // 创建画布并绘制
  val surface = Surface.makeRasterN32Premul(textWidth.toInt(), estimatedHeight.toInt())
  val canvas = surface.canvas

  currentX = 0f
  currentY = emojiSize

  segments.forEach { (text, url) ->
    if (url != null) {
      // 绘制表情
      if (currentX + emojiSize > textWidth) {
        currentX = 0f
        currentY += lineHeight
      }

      getOrDownloadImage(url, CacheType.EMOJI)?.let { img ->
        val srcRect = Rect.makeXYWH(0f, 0f, img.width.toFloat(), img.height.toFloat())
        val tarRect = Rect.makeXYWH(currentX, currentY - emojiSize, emojiSize, emojiSize)
        canvas.drawImageRect(
                img,
                srcRect,
                tarRect,
                FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST),
                null,
                true
        )
      }
      currentX += emojiSize
    } else {
      // 绘制文本
      val textLine = TextLine.make(text, font)
      if (currentX + textLine.width > textWidth) {
        currentX = 0f
        currentY += lineHeight
      }
      canvas.drawTextLine(
              textLine,
              currentX,
              currentY,
              Paint().apply { color = theme.contentColor }
      )
      currentX += textLine.width
    }
  }

  return surface.makeImageSnapshot()
}

/** 绘制置顶评论卡片主体 */
private suspend fun drawTopCommentCard(comment: UpperComment, dynamic: DynamicItem): Image {
  val padding = quality.cardPadding.toFloat()
  val avatarSize = quality.faceSize.toFloat()
  val verifyIconSize = quality.verifyIconSize.toFloat()

  // 用户名样式
  val nameParagraphStyle =
          ParagraphStyle().apply {
            maxLinesCount = 1
            ellipsis = "..."
            alignment = Alignment.LEFT
            textStyle = titleTextStyle
          }

  // 底部信息样式
  val infoParagraphStyle =
          ParagraphStyle().apply {
            maxLinesCount = 1
            ellipsis = "..."
            alignment = Alignment.LEFT
            textStyle = descTextStyle
          }

  // 构建段落
  val nameParagraph =
          ParagraphBuilder(nameParagraphStyle, FontUtils.fonts)
                  .addText(comment.member.name)
                  .build()
                  .layout(cardContentRect.width - avatarSize - padding * 3)

  // 使用新的表情处理函数绘制评论内容
  val contentImage = drawCommentContent(comment, cardContentRect.width - padding * 2)

  val authorName = dynamic.modules.moduleAuthor.name
  val infoText = "来自 ${authorName} 的置顶动态 · ${dynamic.modules.moduleAuthor.pubTs}"
  val infoParagraph =
          ParagraphBuilder(infoParagraphStyle, FontUtils.fonts)
                  .addText(infoText)
                  .build()
                  .layout(cardContentRect.width - padding * 2)

  // 计算高度
  val userSectionHeight = avatarSize.coerceAtLeast(nameParagraph.height + padding * 2)
  val contentSectionHeight = contentImage.height + padding * 2
  val infoSectionHeight = infoParagraph.height + padding * 2

  val totalHeight =
          (padding * 2 +
                          quality.badgeHeight +
                          userSectionHeight +
                          contentSectionHeight +
                          infoSectionHeight)
                  .toInt()

  // 创建画布（带边距）
  val surface =
          Surface.makeRasterN32Premul(
                  (cardRect.width + quality.cardMargin * 2).toInt(),
                  totalHeight + quality.cardMargin * 2
          )
  val canvas = surface.canvas

  // 主卡片区域
  val rrect =
          RRect.makeComplexXYWH(
                  quality.cardMargin.toFloat(),
                  quality.badgeHeight + quality.cardMargin.toFloat(),
                  cardRect.width,
                  (totalHeight - quality.badgeHeight).toFloat(),
                  cardBadgeArc
          )

  // 绘制阴影
  canvas.drawRectShadowAntiAlias(rrect.inflate(1f), theme.cardShadow)

  // 绘制左侧徽章 - 置顶评论标识
  val svg = loadSVG("icon/BILIBILI_LOGO.svg")
  canvas.drawBadge(
          "置顶评论",
          font,
          theme.mainLeftBadge.fontColor,
          theme.mainLeftBadge.bgColor,
          rrect,
          Position.TOP_LEFT,
          svg.makeImage(quality.contentFontSize, quality.contentFontSize)
  )

  // 绘制右侧徽章 - 显示动态ID而不是评论ID
  canvas.drawBadge(
          dynamic.idStr ?: dynamic.did,
          font,
          theme.mainRightBadge.fontColor,
          theme.mainRightBadge.bgColor,
          rrect,
          Position.TOP_RIGHT
  )

  // 绘制卡片背景
  canvas.drawCard(rrect)

  var currentY = quality.cardMargin + quality.badgeHeight + padding

  // 绘制头像（使用项目标准方式）
  canvas.save()
  canvas.translate(quality.cardMargin.toFloat(), 0f)

  val faceImg = getOrDownloadImage(comment.member.avatar, CacheType.USER)

  val hasPendant = false // 评论者没有挂件

  var tarFaceRect =
          RRect.makeXYWH(
                  padding * 1.8f,
                  currentY + padding * 0.2f,
                  avatarSize,
                  avatarSize,
                  avatarSize / 2
          )

  tarFaceRect = tarFaceRect.inflate(quality.noPendantFaceInflate) as RRect
  canvas.drawCircle(
          tarFaceRect.left + tarFaceRect.width / 2,
          tarFaceRect.top + tarFaceRect.width / 2,
          tarFaceRect.width / 2 + quality.noPendantFaceInflate / 2,
          Paint().apply { color = theme.faceOutlineColor }
  )

  faceImg?.let { canvas.drawImageRRect(it, tarFaceRect) }

  canvas.restore()

  // 绘制用户名和认证图标
  val textX = quality.cardMargin + padding * 2 + avatarSize + quality.noPendantFaceInflate * 2
  nameParagraph.paint(canvas, textX, currentY + padding)

  currentY += userSectionHeight + padding

  // 绘制评论内容（支持表情）
  canvas.drawImage(contentImage, quality.cardMargin + padding, currentY)

  currentY += contentSectionHeight

  // 绘制底部分隔线
  canvas.drawLine(
          quality.cardMargin + padding,
          currentY - padding / 2,
          quality.cardMargin + cardRect.width - padding,
          currentY - padding / 2,
          Paint().apply {
            color = theme.descColor
            alpha = 50
            strokeWidth = 1f
          }
  )

  // 绘制底部信息
  infoParagraph.paint(canvas, quality.cardMargin + padding, currentY)

  return surface.makeImageSnapshot()
}
