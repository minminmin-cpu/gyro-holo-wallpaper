package com.example.holowallpaper.wallpaper

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** TYPE_GAME_ROTATION_VECTOR -> 相对四元数 -> 阻尼后的 uView。
 *  与 web/src/math.js 的四元数函数逐行对应。
 *
 *  动效分两部分：
 *  1) 传感器姿态（倾斜手机时的视差）
 *  2) 常驻自动巡游（~15s 周期）。手机静置时传感器仍会持续上报几乎不变的值，
 *     如果只在「传感器安静」时才巡游，就永远轮不到它，壁纸看起来是静态的。
 *     因此巡游改为始终叠加，传感器缺失或静置时它就是唯一的动效来源。 */
class AttitudeSource : SensorEventListener {

    private var baseQuat: FloatArray? = null
    private val sensorTarget = FloatArray(2)
    private val current = FloatArray(2)

    val view = floatArrayOf(0f, 0f, 1f)
    var moving: Boolean = false
        private set
    var trailX: Float = 0f
        private set
    var trailY: Float = 0f
        private set
    var trailAmount: Float = 0f
        private set

    companion object {
        private const val MAX_VIEW = 0.78f
        private const val TAU = 0.11f
        private const val CRUISE_X = 0.38f
        private const val CRUISE_Y = 0.26f

        fun conjugate(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])

        fun multiply(a: FloatArray, b: FloatArray): FloatArray {
            val (ax, ay, az, aw) = a
            val (bx, by, bz, bw) = b
            return floatArrayOf(
                aw * bx + ax * bw + ay * bz - az * by,
                aw * by - ax * bz + ay * bw + az * bx,
                aw * bz + ax * by - ay * bx + az * bw,
                aw * bw - ax * bx - ay * by - az * bz,
            )
        }

        fun rotate(v: FloatArray, q: FloatArray): FloatArray {
            val (qx, qy, qz, qw) = q
            val tx = 2f * (qy * v[2] - qz * v[1])
            val ty = 2f * (qz * v[0] - qx * v[2])
            val tz = 2f * (qx * v[1] - qy * v[0])
            return floatArrayOf(
                v[0] + qw * tx + qy * tz - qz * ty,
                v[1] + qw * ty + qz * tx - qx * tz,
                v[2] + qw * tz + qx * ty - qy * tx,
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
        val q = floatArrayOf(event.values[0], event.values[1], event.values[2], event.values[3])
        val base = baseQuat ?: q.copyOf().also { baseQuat = it }
        // rel = inverse(base) * q；再取共轭作用于局部 +Z，
        // 对齐 RuiC 的 camera.position.applyMatrix4(root.matrixWorld.invert())。
        val rel = conjugate(multiply(conjugate(base), q))
        val v = rotate(floatArrayOf(0f, 0f, 1f), rel)
        val k = max(abs(v[2]), 0.5f)
        sensorTarget[0] = (v[0] / k).coerceIn(-1f, 1f) * MAX_VIEW
        sensorTarget[1] = (v[1] / k).coerceIn(-1f, 1f) * MAX_VIEW
    }

    /** 每帧调用：目标 = 传感器姿态 + 常驻巡游，再阻尼跟随。 */
    fun step(dt: Float, elapsed: Float): Boolean {
        val cruiseX = sin(elapsed * 0.42f) * CRUISE_X * MAX_VIEW
        val cruiseY = sin(elapsed * 0.53f + 1.1f) * CRUISE_Y * MAX_VIEW
        val tx = (sensorTarget[0] + cruiseX).coerceIn(-MAX_VIEW, MAX_VIEW)
        val ty = (sensorTarget[1] + cruiseY).coerceIn(-MAX_VIEW, MAX_VIEW)

        val a = 1f - exp(-dt / TAU)
        val dx = tx - current[0]
        val dy = ty - current[1]
        current[0] += dx * a
        current[1] += dy * a
        moving = abs(dx) + abs(dy) > 0.0008f

        val len = sqrt(current[0] * current[0] + current[1] * current[1] + 1f)
        view[0] = current[0] / len
        view[1] = current[1] / len
        view[2] = 1f / len

        trailX = (dx * 12f).coerceIn(-1f, 1f)
        trailY = (dy * 12f).coerceIn(-1f, 1f)
        trailAmount = if (moving) (abs(dx) + abs(dy)) * 8f else 0f
        return moving
    }

    fun calibrate() {
        baseQuat = null
        current[0] = 0f
        current[1] = 0f
    }
}
