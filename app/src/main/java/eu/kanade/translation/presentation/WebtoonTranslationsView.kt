package eu.kanade.translation.presentation

import android.content.Context
import android.util.AttributeSet
import android.util.DisplayMetrics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.toFontFamily
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import logcat.logcat
import kotlin.math.abs
import kotlin.math.max


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
        val blocks = smartMergeBlocks(translation.blocks,50.0,30.0,30.0)
        blocks.forEach { block ->
            logcat {"BLock $block" }
            SmartTranslationBlock(
                block=block,
                scaleFactor=scaleFactor
            )
        }
    }

    @Composable
    fun SmartTranslationBlock(
        block: TranslationBlock, scaleFactor: Float) {
        val padX = block.symWidth * 2
        val padY = block.symHeight
        val xPx = pxToDp(max((block.x - padX / 2) * scaleFactor,0.0f))
        val yPx = pxToDp(max((block.y - padY / 2) * scaleFactor,0.0f))
        val width = pxToDp( (block.width + padX) * scaleFactor)
        val height = pxToDp((block.height + padY) * scaleFactor)
        val isVertical = block.angle > 85
        Box (
            modifier = Modifier
                .offset(xPx, yPx)
                .size(width, height)
        ) {
            val density = LocalDensity.current
            val fontSize = remember { mutableStateOf(16.sp) }
            SubcomposeLayout  { constraints ->
                val maxWidthPx = with(density) { width.roundToPx() }
                val maxHeightPx = with(density) { height.roundToPx() }

                // Binary search for optimal font size
                var low = 1
                var high = 100  // Initial upper bound
                var bestSize = low

                while (low <= high) {
                    val mid =( (low + high) / 2)
                    val textLayoutResult = subcompose(mid.sp) {
                        Text(
                            text = block.translation,
                            fontSize = mid.sp,
                            fontFamily = fontFamily,
                            color = Color.Black,
                            overflow = TextOverflow.Visible,
                            textAlign = TextAlign.Center,
                            maxLines = Int.MAX_VALUE,
                            softWrap = true,
                            modifier = Modifier.width(width)
                                .rotate(if (isVertical) 0f else block.angle)
                                .align(Alignment.Center)
                                .background(color = Color.Red)
                        )
                    }[0].measure(Constraints(maxWidth = maxWidthPx))

                    if (textLayoutResult.height <= maxHeightPx) {
                        bestSize = mid
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }
                fontSize.value = bestSize.sp

                // Measure final layout
                val textPlaceable = subcompose(Unit) {
                    Text(
                        text = block.translation,
                        fontSize = fontSize.value,
                        fontFamily = fontFamily,
                        color = Color.Black,
                        softWrap = true,
                        overflow = TextOverflow.Visible,
                        textAlign = TextAlign.Center,
                        maxLines = Int.MAX_VALUE,
                        modifier = Modifier.width(width).rotate(if (isVertical) 0f else block.angle)
                            .align(Alignment.Center)
                            .background(color = Color.Red)
                    )
                }[0].measure(constraints)

                layout(textPlaceable.width, textPlaceable.height) {
                    textPlaceable.place(0, 0)
                }
            }
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

    private fun smartMergeBlocks(
        blocks: List<TranslationBlock>,
        padWidthX: Double,
        padX: Double,
        padY: Double
    ): List<TranslationBlock> {
        if (blocks.isEmpty()) return emptyList()

        val merged = mutableListOf<TranslationBlock>()
        // Start with the first rectangle as our current merged candidate.
        var current = blocks[0]

        // Iterate over the rest of the rectangles.
        for (i in 1 until blocks.size) {
            val next = blocks[i]
            if (shouldMerge(current, next, padWidthX, padX, padY)) {
                // Merge current and next rectangle into a new bounding rectangle.
//                logcat { "Merged $current : $next : ${merge(current, next)}" }
                current = merge(current, next)
            } else {
                // They are not mergeable, so add the current one to the result list.
                merged.add(current)
                current = next
            }
        }
        // Add the final candidate.
        merged.add(current)
        return merged
    }
    private fun shouldMerge(
        a: TranslationBlock,
        b: TranslationBlock,
        padWidthX: Double,
        padX: Double,
        padY: Double
    ): Boolean {
        // Condition 1: b.width is smaller than a.width OR their width difference is minimal.
        val condition1 = (b.width < a.width) || (abs(a.width - b.width) < padWidthX)

        // Condition 2: The x coordinates are close enough.
        val condition2 = abs(a.x - b.x) < padX

        // Condition 3: The vertical gap between a’s bottom and b’s top is small.
        val condition3 = (b.y - (a.y + a.height)) < padY
        return condition1 && condition2 && condition3
    }
    fun merge(a: TranslationBlock, b: TranslationBlock): TranslationBlock {
        val newX = kotlin.math.min(a.x, b.x)
        val newY = a.y
        val newWidth = kotlin.math.max(a.x + a.width, b.x + b.width) -newX
        val newHeight = kotlin.math.max(a.y + a.height, b.y + b.height)-newY
        return TranslationBlock(a.text+" "+b.text,a.translation+" "+b.translation,newWidth,newHeight,newX,newY,a.symHeight,a.symWidth,a.angle)
    }

}
