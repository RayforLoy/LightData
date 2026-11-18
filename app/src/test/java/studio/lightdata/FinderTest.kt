package studio.lightdata

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class FinderTest {
    private val id = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun yaw(a: Double): FloatArray {
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
    }

    private fun gray(v: Int) = -0x1000000 or ((v and 255) * 0x010101)

    private val field = Field(0.70, 0.6)
    private val target = Field(1.0, 0.4)

    private fun render(view: Field, yaw: Double, ev: Double = 0.0, bias: Int = 0): Frame {
        val width = 288
        val height = (width / view.aspect).roundToInt()
        val colors =
            IntArray(width * height) { i ->
                val u = ((i % width - bias + 0.5) * 2 / width - 1) * view.x
                val v = (1 - (i / width + 0.5) * 2 / height) * view.y
                // Infinite textured wall at world Z=-1, camera rotated about its optical center.
                val z = cos(yaw) + sin(yaw) * u
                val x = (cos(yaw) * u - sin(yaw)) / z
                val y = v / z
                val value =
                    128 +
                        30 * sin(36 * x + 4 * y) +
                        24 * cos(55 * y + 12 * x) +
                        18 * sin(17 * x * y)
                gray((value * 2.0.pow(-ev / 2.2)).roundToInt().coerceIn(0, 255))
            }
        return Frame(width, height, colors, ev, "rear")
    }

    @Test
    fun sensorMountAndDisplayRotationsAreInverses() {
        val f = Frame(3, 2, intArrayOf(1, 2, 3, 4, 5, 6), 0.0, "rear")
        assertArrayEquals(intArrayOf(4, 1, 5, 2, 6, 3), upright(f, 90).pixels)
        assertArrayEquals(intArrayOf(6, 5, 4, 3, 2, 1), upright(f, 180).pixels)
        assertArrayEquals(intArrayOf(3, 6, 2, 5, 1, 4), upright(f, 270).pixels)
        assertArrayEquals(f.pixels, upright(upright(f, 90), 270).pixels)
        assertSame(f, upright(f, 0))
        val landscape = displayAxes(id, 1)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f), landscape, 0f)
        assertArrayEquals(id, displayAxes(landscape, 3), 0f)
        assertArrayEquals(displayAxes(id, 2), displayAxes(landscape, 1), 0f)
    }

    private var clock = 1000L
    private fun ScanSession.tick(frame: Frame, field: Field, sensor: FloatArray?): Frame {
        clock += 100
        return capture(field,sensor,clock) { frame }
    }
    private fun ScanSession.settle(frame: Frame, field: Field, sensor: FloatArray?) {
        repeat(5) { tick(frame,field,sensor) }
    }

    @Test
    fun overlappingFramesContinueTheScanWithoutAnyRotationSensor() {
        val initial = render(field, 0.0)
        val session = ScanSession(initial, target, null, defaultResponse)
        session.settle(initial, field, null)
        val center = session.guide(0)
        assertEquals(1, center.captures)
        assertNotNull(center.next)
        // Each exposure overlaps the previous one. No shutter or sensor event is required.
        val angles = (1..12).map { it * 0.04 } + (11 downTo -12).map { it * 0.04 }
        for (angle in angles) session.settle(render(field, angle), field, null)
        val result = session.guide(0)
        assertTrue("captures " + result.captures, result.captures > 3)
        assertEquals(1.0, result.coverage, 0.0)
        assertNull(result.next)
    }

    @Test
    fun texturelessFrameRequestsOverlapAndDoesNotEraseAcquiredCoverage() {
        val initial = render(field, 0.0)
        val session = ScanSession(initial, target, null, defaultResponse)
        session.settle(initial, field, null)
        val before = session.guide(0)
        session.settle(
            initial.copy(pixels = IntArray(initial.pixels.size) { gray(62) }),
            field,
            null,
        )
        val after = session.guide(90)
        assertTrue(after.needsOverlap)
        assertEquals(before.coverage, after.coverage, 0.0)
        assertEquals(before.next!!.turned(90), after.next)
        session.settle(render(field, 0.045), field, null)
        assertFalse(session.guide(0).needsOverlap)
    }

    @Test
    fun movingForegroundOnStationaryTripodDoesNotExpandTheAtlas() {
        val initial = render(field, 0.0)
        val session = ScanSession(initial, target, id, defaultResponse)
        session.settle(initial, field, id)
        val before = session.guide(0)
        for (t in 1..8) {
            val rgb = initial.pixels.copyOf()
            for (y in 0 until initial.h/3) for (x in 0 until initial.w/3)
                rgb[y*initial.w+x] = gray(if ((x/3 + y/3+t)%2 == 1) 255 else 0)
            session.settle(initial.copy(pixels = rgb),field,id)
        }
        val after = session.guide(0)
        assertEquals(before.coverage,after.coverage,0.0)
        assertEquals(before.captures,after.captures)
    }

    @Test
    fun resumedSensorWithNewWorldReferenceKeepsRegisteredCoverage() {
        val first = render(field, 0.0)
        val session = ScanSession(first, target, id, defaultResponse)
        session.settle(first,field,id)
        val last = render(field,.16)
        session.settle(last,field,yaw(.16))
        val before = session.guide(0)
        session.resumeTracking()
        session.settle(last,field,yaw(2.0))
        val after = session.guide(0)
        assertEquals(before.coverage, after.coverage,0.0)
        assertEquals(before.aim, after.aim)
        assertEquals(before.captures, after.captures)
        session.settle(render(field,.30),field,yaw(2.14))
        assertTrue(session.guide(0).coverage > before.coverage)
    }

    @Test
    fun rotationAndDisplayRotationPreserveTheSameScan() {
        val initial = render(field, 0.0)
        val session = ScanSession(initial, target, id, defaultResponse)
        session.settle(initial, field, id)
        val first = session.guide(0)
        for (i in 1..6) session.settle(render(field, i * 0.04), field, yaw(i * 0.04))
        val upright = session.guide(0)
        val rotated = session.guide(90)
        assertTrue(upright.captures > first.captures)
        assertTrue(upright.coverage > first.coverage)
        assertEquals(upright.coverage, rotated.coverage, 0.0)
        assertEquals(upright.aim.turned(90), rotated.aim)
        assertEquals(upright.next?.turned(90), rotated.next)
    }

    @Test
    fun initialFramingFollowsTheProjectedRayAndKeepsItsStartingTilt() {
        val frame = render(field,0.0)
        val tilted = yaw(.2)
        val session = ScanSession(frame,target,tilted,defaultResponse)
        session.tick(frame,field,tilted)
        val first = session.guide(0)
        assertEquals(FinderPoint(.5,.5),first.startMark!!.center)
        session.tick(frame,field,yaw(.5))
        val guide = session.guide(0)
        val mark = guide.startMark!!
        assertEquals(.5 + tan(.3)/(2*target.x),mark.center.x,1e-6)
        assertEquals(.5,mark.center.y,1e-6)
        assertEquals(first.startPitchRadians,guide.startPitchRadians)
        val turned = session.guide(90).startMark!!
        assertEquals(mark.center.turned(90),turned.center)
        assertEquals(0.0,turned.right.x,1e-6)
        assertTrue(turned.right.y > 0)
        session.tick(frame,field,yaw(PI))
        assertNull(session.guide(0).startMark)
        session.resumeTracking()
        session.tick(frame,field,tilted)
        assertEquals(first.startPitchRadians,session.guide(0).startPitchRadians)
    }

    @Test
    fun missingFieldIsTransparentAndDimensionsHonorTheRequestedGate() {
        val f = Frame(8, 6, IntArray(48) { gray(it + 30) }, 0.0, "rear")
        val src = Field(4.0, 3.0)
        val small = crop(f, src, Field(2.0, 1.0))
        assertArrayEquals(
            intArrayOf(18, 19, 20, 21, 26, 27, 28, 29).map { gray(it + 30) }.toIntArray(),
            small.pixels,
        )
        val wide = crop(f, src, Field(8.0, 4.0))
        assertEquals(8, wide.w)
        assertEquals(4, wide.h)
        assertEquals(0, wide.sample(0.03, 0.5))
        assertNotEquals(0, wide.sample(0.5, 0.5))
        assertSame(f, crop(f, src, src))
    }

    @Test
    fun coverageCountsAllPixelsAndNeverTreatsMissingAsBlack() {
        val first = render(field, 0.0)
        val m = Mosaic(target, id, first)
        m.add(first, field, id)
        val partial = m.view(id)
        assertEquals(0, partial.sample(0.01, 0.5))
        assertTrue(m.coverage in 0.58..0.64)
        assertTrue(m.grid().any { !it })
        val initialCoverage = m.coverage
        m.add(first.copy(pixels = IntArray(first.w * first.h)), field, yaw(0.45))
        m.add(first.copy(id = "different"), field, yaw(-0.45))
        assertEquals(initialCoverage, m.coverage, 0.0)
        assertFalse(m.ready)
    }

    @Test
    fun darkBlueExposureGainIsAppliedInLinearLight() {
        val first = Frame(32, 24, IntArray(32 * 24) { 0xff000020.toInt() }, 0.0, "rear")
        for (lut in listOf(defaultResponse, defaultResponse.copyOf())) {
            val mosaic = Mosaic(field, id, first, lut)
            mosaic.add(first.copy(cameraEv = 3.0), field, id)
            val rgb = mosaic.view(id).sample(.5,.5)
            assertEquals(95, rgb and 255)
            assertEquals(0, rgb and 0xffff00)
            assertEquals(22, luma(rgb))
        }
    }

    @Test
    fun compensatedScanAndReframingAgreeWithAnIndependentCamera() {
        val first = render(field, 0.0)
        val m = Mosaic(target, id, first)
        m.add(first, field, id)
        val previous = m.coverage
        for (angle in listOf(0.24, 0.43, -0.24, -0.43)) {
            val source = render(field, angle, ev = 1.0, bias = 3)
            m.add(source, field, yaw(angle))
        }
        assertTrue(m.coverage > previous)
        assertEquals(1.0, m.coverage, 0.0)
        assertTrue(m.ready)
        assertTrue(m.grid().all { it })
        for (angle in listOf(-0.035, 0.0, 0.035)) {
            val result = m.view(yaw(angle))
            val expected = render(target, angle)
            assertEquals(target.aspect, result.w.toDouble() / result.h, 0.015)
            assertFalse(result.pixels.any { it == 0 })
            assertEquals(0.0, result.cameraEv, 0.0)
            val differences =
                (1..19).flatMap { x ->
                    (1..9).map { y ->
                        abs(
                            luma(result.sample(x / 20.0, y / 10.0)) -
                                luma(expected.sample(x / 20.0, y / 10.0))
                        )
                    }
                }
            assertTrue("Mean residual " + differences.average(), differences.average() < 4.0)
        }
        val far = m.view(yaw(0.7))
        val farther = m.view(yaw(1.2))
        assertTrue(far.pixels.any { it == 0 } && far.pixels.any { it != 0 })
        assertTrue(farther.pixels.count { it == 0 } > far.pixels.count { it == 0 })
        assertTrue(m.view(yaw(Math.PI)).pixels.all { it == 0 })
        assertTrue(m.homeDirection(yaw(1.2))!!.x > 0)
        assertTrue(m.homeDirection(yaw(-1.2))!!.x < 0)
        assertNull(m.homeDirection(id))
    }

    @Test fun overlapCompensatesWhiteBalanceAndResidualExposureInNewCoverage() {
        val center = render(field,0.0)
        val base = 0xff708090.toInt()
        val warm = 0xff96796e.toInt()
        val first = center.copy(pixels=IntArray(center.w*center.h) { base })
        val second = first.copy(pixels=IntArray(first.pixels.size) { warm })
        val m = Mosaic(target,id,first)
        m.add(first,field,id)
        assertEquals(0,m.view(id).sample(.09,.5))
        m.add(second,field,yaw(.43))
        val corrected = m.view(id).sample(.09,.5)
        for (shift in listOf(16,8,0)) {
            assertEquals((base ushr shift and 255).toDouble(),
                (corrected ushr shift and 255).toDouble(),1.0)
        }
    }

    @Test fun rejectedMovingKeyframeCanRetryAtEdgeBeforeStationaryTimeout() {
        var calls = 0
        val aligner = ScanAligner { _,_,_,_,_ -> if (++calls == 1) null else ScanWarp() }
        val first = render(field,0.0)
        val session = ScanSession(first,target,id,defaultResponse,aligner)
        fun update(t: Long, angle: Double) {
            session.capture(field,yaw(angle),t,null,1.0) { render(field,angle) }
        }
        update(1000,0.0)
        update(1050,.03)
        update(1100,.10)
        assertEquals(1,calls)
        update(1170,.10)
        assertEquals(1,calls)
        update(1220,.10)
        assertEquals(2,session.guide(0).captures)
    }

    @Test fun stationaryFallbackRejectsInconsistentStructureBeforeWritingAnyPixels() {
        val first = render(field,0.0)
        val m = Mosaic(target,id,first)
        m.add(first,field,id)
        val before = m.view(id).pixels
        val flipped = render(field,.32).let { it.copy(pixels=it.pixels.reversedArray()) }
        assertFalse(m.add(flipped,field,yaw(.32)))
        assertArrayEquals(before,m.view(id).pixels)
        assertTrue(m.add(render(field,.32),field,yaw(.32)))
    }

    @Test fun localGainReducesTopBottomBrightnessErrorInNewCoverage() {
        val c = render(field,0.0)
        val first = c.copy(pixels=IntArray(c.pixels.size) { gray(140) })
        val second = c.copy(pixels=IntArray(c.pixels.size) { gray(120+40*(it/c.w)/c.h) })
        val m = Mosaic(target,id,first)
        m.add(first,field,id)
        m.add(second,field,yaw(.43))
        val view = m.view(id)
        for (y in listOf(.2,.8)) assertEquals(140.0,(view.sample(.09,y) and 255).toDouble(),5.0)
    }

    @Test fun featherBoundsTheSeamWhenClippedOverlapCannotEstimateGain() {
        val c = render(field,0.0)
        val first = c.copy(pixels=IntArray(c.pixels.size) { gray(248) })
        val second = c.copy(pixels=IntArray(c.pixels.size) { gray(170) })
        val m = Mosaic(target,id,first)
        m.add(first,field,id)
        m.add(second,field,yaw(.43))
        val f = m.view(id)
        val codes = (f.w/12..f.w/2).map { f.pixels[(f.h/2)*f.w+it] and 255 }
        assertTrue(codes.first() == 170 && codes.last() == 248)
        assertTrue("seam step "+codes.zipWithNext { a,b -> abs(a-b) }.max(),
            codes.zipWithNext { a,b -> abs(a-b) }.max() < 12)
    }

    @Test fun movingCameraUpdatesAimWithoutDecodingOrWritingUntilSettled() {
        val first = render(field,0.0)
        val session = ScanSession(first,target,id,defaultResponse)
        session.settle(first,field,id)
        val center = session.guide(0)
        for (i in 1..8) {
            clock += 70
            session.capture(field,yaw(i*.04),clock) { error("decoded a moving frame") }
        }
        val moving = session.guide(0)
        assertNotEquals(center.aim,moving.aim)
        assertEquals(center.coverage,moving.coverage,0.0)
        val next = render(field,.32)
        session.settle(next,field,yaw(.32))
        val parked = session.guide(0)
        assertEquals(center.captures+1,parked.captures)
        assertTrue(parked.coverage>center.coverage)
        repeat(8) {
            clock += 100
            session.capture(field,yaw(.32),clock) { error("duplicate stationary exposure") }
        }
    }
}
