/*
 * Copyright 2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 *
 * Adapted from ReFra's panoramaviewer (https://github.com/IacobIonut01/ReFra).
 */

package tomato.gallery.panorama

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

internal object SphereGeometry {
    data class Mesh(
        val vertices: FloatBuffer,
        val texCoords: FloatBuffer,
        val indices: ShortBuffer,
        val indexCount: Int
    )

    fun createSphere(
        radius: Float = 1f,
        latSegments: Int = 64,
        lonSegments: Int = 64
    ): Mesh {
        val vertexCount = (latSegments + 1) * (lonSegments + 1)
        val vertices = allocateFloatBuffer(vertexCount * 3)
        val texCoords = allocateFloatBuffer(vertexCount * 2)

        for (lat in 0..latSegments) {
            val theta = Math.PI * lat / latSegments
            val sinTheta = sin(theta).toFloat()
            val cosTheta = cos(theta).toFloat()

            for (lon in 0..lonSegments) {
                val phi = 2.0 * Math.PI * lon / lonSegments
                val sinPhi = sin(phi).toFloat()
                val cosPhi = cos(phi).toFloat()

                vertices.put(radius * sinTheta * cosPhi)
                vertices.put(radius * cosTheta)
                vertices.put(radius * sinTheta * sinPhi)

                texCoords.put(lon.toFloat() / lonSegments)
                texCoords.put(lat.toFloat() / latSegments)
            }
        }

        val indexCount = latSegments * lonSegments * 6
        val indices = allocateShortBuffer(indexCount)
        for (lat in 0 until latSegments) {
            for (lon in 0 until lonSegments) {
                val first = (lat * (lonSegments + 1) + lon).toShort()
                val second = (first + lonSegments + 1).toShort()
                indices.put(first)
                indices.put(second)
                indices.put((first + 1).toShort())
                indices.put(second)
                indices.put((second + 1).toShort())
                indices.put((first + 1).toShort())
            }
        }

        vertices.position(0)
        texCoords.position(0)
        indices.position(0)
        return Mesh(vertices, texCoords, indices, indexCount)
    }

    fun createCylinder(
        radius: Float = 1f,
        height: Float = 1f,
        arcDegrees: Float = 360f,
        segments: Int = 128
    ): Mesh {
        val vertexCount = (segments + 1) * 2
        val vertices = allocateFloatBuffer(vertexCount * 3)
        val texCoords = allocateFloatBuffer(vertexCount * 2)
        val halfHeight = height / 2f
        val arcRad = Math.toRadians(arcDegrees.toDouble())
        val startAngle = (3.0 * Math.PI / 2.0) - arcRad / 2.0

        for (i in 0..segments) {
            val t = i.toDouble() / segments
            val angle = startAngle + arcRad * t
            val x = radius * cos(angle).toFloat()
            val z = radius * sin(angle).toFloat()
            val u = t.toFloat()

            vertices.put(x)
            vertices.put(-halfHeight)
            vertices.put(z)
            texCoords.put(u)
            texCoords.put(1f)

            vertices.put(x)
            vertices.put(halfHeight)
            vertices.put(z)
            texCoords.put(u)
            texCoords.put(0f)
        }

        val indexCount = segments * 6
        val indices = allocateShortBuffer(indexCount)
        for (i in 0 until segments) {
            val bl = (i * 2).toShort()
            val tl = (i * 2 + 1).toShort()
            val br = (i * 2 + 2).toShort()
            val tr = (i * 2 + 3).toShort()
            indices.put(bl)
            indices.put(br)
            indices.put(tl)
            indices.put(tl)
            indices.put(br)
            indices.put(tr)
        }

        vertices.position(0)
        texCoords.position(0)
        indices.position(0)
        return Mesh(vertices, texCoords, indices, indexCount)
    }

    private fun allocateFloatBuffer(capacity: Int): FloatBuffer =
        ByteBuffer.allocateDirect(capacity * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun allocateShortBuffer(capacity: Int): ShortBuffer =
        ByteBuffer.allocateDirect(capacity * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
}
