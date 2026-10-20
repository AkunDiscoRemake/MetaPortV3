package com.phonesteam.app.util

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Vetor 3D imutável (metros, espaço OpenVR). */
data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-6f) this else this * (1f / l)
    }
    fun lerp(o: Vec3, t: Float) = this + (o - this) * t
}

/** Quaternion unitário (x, y, z, w). */
data class Quat(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val w: Float = 1f) {

    operator fun times(o: Quat) = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    fun conjugate() = Quat(-x, -y, -z, w)

    fun normalized(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l < 1e-6f) Quat() else Quat(x / l, y / l, z / l, w / l)
    }

    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = u.cross(v) * 2f
        return v + t * w + u.cross(t)
    }

    /** Ângulo de yaw (rotação em torno de +Y) em radianos. */
    fun yaw(): Float {
        val fwd = rotate(Vec3(0f, 0f, -1f))
        return kotlin.math.atan2(-fwd.x, -fwd.z)
    }

    fun slerp(o: Quat, t: Float): Quat {
        var d = x * o.x + y * o.y + z * o.z + w * o.w
        var b = o
        if (d < 0f) { d = -d; b = Quat(-o.x, -o.y, -o.z, -o.w) }
        if (d > 0.9995f) {
            return Quat(x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t, w + (b.w - w) * t).normalized()
        }
        val theta = kotlin.math.acos(d)
        val s = sin(theta)
        val wa = sin((1 - t) * theta) / s
        val wb = sin(t * theta) / s
        return Quat(x * wa + b.x * wb, y * wa + b.y * wb, z * wa + b.z * wb, w * wa + b.w * wb)
    }

    /** Matriz 4x4 coluna-maior (formato OpenGL). */
    fun toMatrix(out: FloatArray = FloatArray(16)): FloatArray {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        out[0] = 1 - 2 * (yy + zz); out[1] = 2 * (xy + wz); out[2] = 2 * (xz - wy); out[3] = 0f
        out[4] = 2 * (xy - wz); out[5] = 1 - 2 * (xx + zz); out[6] = 2 * (yz + wx); out[7] = 0f
        out[8] = 2 * (xz + wy); out[9] = 2 * (yz - wx); out[10] = 1 - 2 * (xx + yy); out[11] = 0f
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
        return out
    }

    companion object {
        fun axisAngle(ax: Float, ay: Float, az: Float, rad: Float): Quat {
            val s = sin(rad / 2)
            return Quat(ax * s, ay * s, az * s, cos(rad / 2))
        }

        /** Constrói a rotação a partir de uma base ortonormal (colunas X, Y, Z). */
        fun fromBasis(xa: Vec3, ya: Vec3, za: Vec3): Quat {
            val m00 = xa.x; val m10 = xa.y; val m20 = xa.z
            val m01 = ya.x; val m11 = ya.y; val m21 = ya.z
            val m02 = za.x; val m12 = za.y; val m22 = za.z
            val tr = m00 + m11 + m22
            return when {
                tr > 0 -> {
                    val s = sqrt(tr + 1f) * 2
                    Quat((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
                }
                m00 > m11 && m00 > m22 -> {
                    val s = sqrt(1f + m00 - m11 - m22) * 2
                    Quat(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
                }
                m11 > m22 -> {
                    val s = sqrt(1f + m11 - m00 - m22) * 2
                    Quat((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
                }
                else -> {
                    val s = sqrt(1f + m22 - m00 - m11) * 2
                    Quat((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
                }
            }.normalized()
        }
    }
}
