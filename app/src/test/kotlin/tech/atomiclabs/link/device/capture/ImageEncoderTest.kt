package tech.atomiclabs.link.device.capture

import android.graphics.Bitmap
import io.mockk.*
import java.io.OutputStream
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class ImageEncoderTest {
    @Test fun pngAndJpegKeepFullDimensionsUseRequestedQualityAndRecycleTheirBitmap() = runTest {
        mockkStatic(Bitmap::class)
        try {
            val image = CapturedImage(2, 2, intArrayOf(-1, 0, 1, 2))
            val bitmap = mockk<Bitmap>(relaxed = true)
            every { Bitmap.createBitmap(image.argb, 2, 2, Bitmap.Config.ARGB_8888) } returns bitmap
            every { bitmap.compress(any<Bitmap.CompressFormat>(), any(), any()) } answers {
                thirdArg<OutputStream>().write(byteArrayOf(1, 2, 3)); true
            }
            val encoder = ImageEncoder(FakeLogger(), StandardTestDispatcher(testScheduler))
            for ((format, quality) in listOf(ImageFormat.PNG to 100, ImageFormat.JPEG to 90)) {
                val result = encoder.encode(image, format, quality) as PortResult.Success
                assertEquals(2, result.value.width); assertEquals(2, result.value.height)
                assertEquals(format, result.value.format)
                assertArrayEquals(byteArrayOf(1, 2, 3), result.value.bytes)
                verify { bitmap.compress(if (format == ImageFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                    quality, any()) }
            }
            verify(exactly = 2) { bitmap.recycle() }
        } finally { unmockkStatic(Bitmap::class) }
    }

    @Test fun refusedOrThrowingCompressionAndInvalidPixelsReturnLoggedFailuresWithoutHidingCauses() = runTest {
        mockkStatic(Bitmap::class)
        try {
            val image = CapturedImage(2, 2, IntArray(4))
            val bitmap = mockk<Bitmap>(relaxed = true)
            every { Bitmap.createBitmap(image.argb, 2, 2, Bitmap.Config.ARGB_8888) } returns bitmap
            every { bitmap.compress(any<Bitmap.CompressFormat>(), any(), any()) } returns false
            val logger = FakeLogger()
            val encoder = ImageEncoder(logger, StandardTestDispatcher(testScheduler))
            assertTrue(encoder.encode(image, ImageFormat.PNG, 100) is PortResult.Failure)
            val cause = IllegalStateException("private-image-data")
            every { bitmap.compress(any<Bitmap.CompressFormat>(), any(), any()) } throws cause
            val failed = encoder.encode(image, ImageFormat.JPEG, 90) as PortResult.Failure
            assertTrue(generateSequence(failed.failure.cause) { it.cause }.any { it === cause })
            assertEquals(FailureKind.INVALID_DATA,
                (encoder.encode(CapturedImage(2, 2, IntArray(1)), ImageFormat.PNG, 100) as PortResult.Failure).failure.kind)
            verify(exactly = 2) { bitmap.recycle() }
            assertEquals(3, logger.entries.size)
            assertFalse(logger.entries.any { it.cause!!.stackTraceToString().contains("private-image-data") })
        } finally { unmockkStatic(Bitmap::class) }
    }
}
