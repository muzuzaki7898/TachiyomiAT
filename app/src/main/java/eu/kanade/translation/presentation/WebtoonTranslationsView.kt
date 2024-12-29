package eu.kanade.translation.presentation

import android.content.Context
import android.graphics.text.LineBreakConfig
import android.util.AttributeSet
import android.util.DisplayMetrics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.toFontFamily
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import androidx.core.view.isVisible
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock


class WebtoonTranslationsView :
    AbstractComposeView {

    private val translation: PageTranslation
    private val font: TranslationFont
    private val fontFamily: FontFamily

    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : super(context, attrs, defStyleAttr) {
        this.translation = PageTranslation.EMPTY
        this.font = TranslationFont.ANIME_ACE
        this.fontFamily = Font(
            resId = font.res,
            weight = FontWeight.Bold,
        ).toFontFamily()
    }

    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
        translation: PageTranslation,
        font: TranslationFont? = null,
    ) : super(context, attrs, defStyleAttr) {
        this.translation = translation
        this.font = font ?: TranslationFont.ANIME_ACE
        this.fontFamily = Font(
            resId = this.font.res,
            weight = FontWeight.Bold,
        ).toFontFamily()
    }

    @Composable
    override fun Content() {
        var size by remember { mutableStateOf(IntSize.Zero) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    size = it
                    if (size == IntSize.Zero) hide()
                    else show()
                },
        ) {
            if (size == IntSize.Zero) return
            val scaleFactor = size.width / translation.imgWidth
            TextBlockBackground(scaleFactor)
            TextBlockContent(scaleFactor)
        }
    }

    @Composable
    fun TextBlockBackground(scaleFactor: Float) {
        translation.blocks.forEach { block ->
            val padX = block.symWidth / 2
            val padY = block.symHeight / 2
            val bgX = (block.x - padX / 2) * scaleFactor
            val bgY = (block.y - padY / 2) * scaleFactor
            val bgWidth = (block.width + padX) * scaleFactor
            val bgHeight = (block.height + padY) * scaleFactor
            val isVertical = block.angle > 85
            Box(
                modifier = Modifier
                    .offset(pxToDp(bgX), pxToDp(bgY))
                    .size(pxToDp(bgWidth), pxToDp(bgHeight))
                    .rotate(if (isVertical) 0f else block.angle)
                    .background(Color.Blue, shape = RoundedCornerShape(4.dp)),
            )
        }
    }

    @Composable
    fun TextBlockContent(scaleFactor: Float) {
        translation.blocks.forEach { block ->
            val padX = block.symWidth * 2
            val padY = block.symHeight
            val xPx = (block.x - padX / 2) * scaleFactor
            val yPx = (block.y - padY / 2) * scaleFactor
            val width = (block.width + padX) * scaleFactor
            val height = (block.height + padY) * scaleFactor
            TextBlock(
                block = block,
                scaleFactor = scaleFactor,
                modifier = Modifier
                    .offset(pxToDp(xPx), pxToDp(yPx))
                    .size(pxToDp(width), pxToDp(height)),
            )
        }
    }

    @Composable
    fun TextBlock(block: TranslationBlock, scaleFactor: Float, modifier: Modifier) {
        val defaultFontSize = pxToSp(block.symWidth * scaleFactor)
        Box(modifier = modifier) {
            val style = LocalTextStyle.current.copy(
                lineBreak = LineBreak(
                    wordBreak = LineBreak.WordBreak.Phrase,
                    strategy = LineBreak.Strategy.Simple,
                    strictness = LineBreak.Strictness.Strict
                ),
            )
            var resizedTextStyle by remember {
                mutableStateOf(style)
            }
            var shouldDraw by remember {
                mutableStateOf(false)
            }
            Text(
                text = block.translation,
                color = Color.Black,
                fontFamily = fontFamily,
                overflow = TextOverflow.Visible,
                textAlign = TextAlign.Center,
                style = resizedTextStyle,
                softWrap = true,
                maxLines = (1 + block.translation.count { it == '\n' }),
                modifier = Modifier
                    .rotate(block.angle)
                    .align(Alignment.Center)
                    .background(color = Color.Red)
                    .drawWithContent {
                        if (shouldDraw) {
                            drawContent()
                        }
                    },

                onTextLayout = { result ->
                    if (result.didOverflowWidth) {
                        if (style.fontSize.isUnspecified) {
                            resizedTextStyle = resizedTextStyle.copy(
                                fontSize = defaultFontSize,
                            )
                        }
                        resizedTextStyle = resizedTextStyle.copy(
                            fontSize = resizedTextStyle.fontSize * 0.90,
                        )
                    } else {
                        shouldDraw = true
                    }
                },
            )
        }
    }

    fun show() {
        isVisible = true
    }

    fun hide() {
        isVisible = false
    }

    private fun pxToSp(px: Float): TextUnit {
        return (px / (context.resources.displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT)).sp
    }

    private fun pxToDp(px: Float): Dp {
        return (px / (context.resources.displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT)).dp
    }
}

