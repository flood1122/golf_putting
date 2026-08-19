package com.example.golf_putting.core.vision

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object PerspectiveTransformer {

    fun transform(src: Mat, points4: List<Point>, targetWidth: Int, targetHeight: Int): Mat {
        if (points4.size != 4) return src

        val dstPoints = listOf(
            Point(0.0, 0.0),
            Point(targetWidth.toDouble(), 0.0),
            Point(0.0, targetHeight.toDouble()),
            Point(targetWidth.toDouble(), targetHeight.toDouble())
        )

        // Pure Kotlin 방식으로 Perspective Matrix(3x3) 계산
        val perspectiveMatrix = calculatePerspectiveMatrix(points4, dstPoints)

        val result = Mat()
        Imgproc.warpPerspective(
            src,
            result,
            perspectiveMatrix,
            Size(targetWidth.toDouble(), targetHeight.toDouble())
        )

        perspectiveMatrix.release()
        return result
    }

    /**
     * OpenCV JNI 컴파일 에러 방지를 위해 4점 매핑 원근 변환 3x3 행렬 직접 연산
     */
    private fun calculatePerspectiveMatrix(src: List<Point>, dst: List<Point>): Mat {
        val a = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)

        for (i in 0 until 4) {
            val sx = src[i].x
            val sy = src[i].y
            val dx = dst[i].x
            val dy = dst[i].y

            a[i * 2] = doubleArrayOf(sx, sy, 1.0, 0.0, 0.0, 0.0, -dx * sx, -dx * sy)
            b[i * 2] = dx

            a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, sx, sy, 1.0, -dy * sx, -dy * sy)
            b[i * 2 + 1] = dy
        }

        val h = solveLinearSystem(a, b)

        val matrix = Mat(3, 3, CvType.CV_64FC1)
        matrix.put(
            0, 0,
            h[0], h[1], h[2],
            h[3], h[4], h[5],
            h[6], h[7], 1.0
        )
        return matrix
    }

    private fun solveLinearSystem(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = 8
        for (i in 0 until n) {
            var maxRow = i
            for (k in i + 1 until n) {
                if (Math.abs(a[k][i]) > Math.abs(a[maxRow][i])) {
                    maxRow = k
                }
            }

            val tempA = a[i]
            a[i] = a[maxRow]
            a[maxRow] = tempA

            val tempB = b[i]
            b[i] = b[maxRow]
            b[maxRow] = tempB

            val pivot = a[i][i]
            for (j in i until n) {
                a[i][j] /= pivot
            }
            b[i] /= pivot

            for (k in 0 until n) {
                if (k != i) {
                    val factor = a[k][i]
                    for (j in i until n) {
                        a[k][j] -= factor * a[i][j]
                    }
                    b[k] -= factor * b[i]
                }
            }
        }
        return b
    }
}