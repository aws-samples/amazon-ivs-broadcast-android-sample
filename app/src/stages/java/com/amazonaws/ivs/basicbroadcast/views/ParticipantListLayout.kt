package com.amazonaws.ivs.basicbroadcast.views

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.sqrt

class ParticipantListLayout(context: Context, attrs: AttributeSet) : ViewGroup(context, attrs) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val childCount = childCount
        mColumnCount = calculateDesiredColumnCount(childCount)

        val widthSize = MeasureSpec.getSize(widthSpec)
        val heightSize = MeasureSpec.getSize(heightSpec)

        if (childCount > 0) {
            val rowCount = (childCount + mColumnCount - 1) / mColumnCount
            val firstRowColumnCount = calculateFirstRowColumnCount(childCount)

            var widthSizeRemaining = widthSize
            var widthCountRemaining = firstRowColumnCount

            var heightSizeRemaining = heightSize
            var heightCountRemaining = rowCount

            for (i in 0 until childCount) {
                // Calculate cell size every time to avoid rounding errors when our width
                // is not an multiple of column count, same for height
                val widthCellSize = widthSizeRemaining / widthCountRemaining
                val heightCellSize = heightSizeRemaining / heightCountRemaining

                val child = getChildAt(i)
                child.measure(
                    MeasureSpec.makeMeasureSpec(widthCellSize, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(heightCellSize, MeasureSpec.EXACTLY)
                )

                if ((i + 1 - firstRowColumnCount + mColumnCount).mod(mColumnCount) == 0) {
                    // Next row
                    widthSizeRemaining = widthSize
                    widthCountRemaining = mColumnCount

                    heightSizeRemaining -= heightCellSize
                    heightCountRemaining -= 1
                } else {
                    // Next column
                    widthSizeRemaining -= widthCellSize
                    widthCountRemaining -= 1
                }
            }
        }

        setMeasuredDimension(widthSize, heightSize)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val childCount = childCount
        if (childCount > 0) {
            val firstRowColumnCount = calculateFirstRowColumnCount(childCount)

            var x = 0
            var y = 0

            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val mw = child.measuredWidth
                val mh = child.measuredHeight

                child.layout(x, y, x + mw, y + mh)

                if ((i + 1 - firstRowColumnCount + mColumnCount).mod(mColumnCount) == 0) {
                    // Next row
                    x = 0
                    y += mh
                } else {
                    // Next column
                    x += mw
                }
            }
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
    }

    private fun calculateFirstRowColumnCount(childCount: Int): Int {
        val m = childCount.mod(mColumnCount)
        if (m == 0) {
            return mColumnCount
        }

        // If last row is going to be incomplete, push its cells to first row where publish is
        return m
    }

    private fun calculateDesiredColumnCount(childCount: Int): Int {
        if (childCount <= 1) {
            return 1
        }

        val defaultColumnCount = ceil(sqrt(childCount.toDouble()) - 0.001).toInt()

        when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> {
                return if (childCount <= 2) {
                    1
                } else if (childCount <= 4) {
                    2
                } else if (childCount <= 6) {
                    2
                } else {
                    defaultColumnCount
                }
            }
            Configuration.ORIENTATION_LANDSCAPE -> {
                return if (childCount <= 3) {
                    childCount
                } else if (childCount <= 6) {
                    3
                } else {
                    defaultColumnCount
                }
            }
            else -> {
                // Unknown or square
                return defaultColumnCount
            }
        }
    }

    private var mColumnCount = 1
}
