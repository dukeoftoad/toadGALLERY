package com.toadgallery.editor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runner seam over a FAKE (r42, decision 7; widened to the two-job
 * seam in r70): the coordinator validates before the runner sees
 * anything, enforces single-flight, forwards typed progress in order and
 * delivers exactly one terminal result; cancellation reaches the runner
 * and maps to the Canceled result the runner emits. Only the thin media3
 * implementation in the edit app touches Transformer.
 */
class VideoExportRunnerTest {

    /** The fake: records what it was handed, drives scripted events. */
    private class FakeRunner : VideoExportRunner {
        var startedJob: VideoExportJob? = null
        var cancelCount = 0
        var listener: VideoExportListener? = null

        override fun start(job: VideoExportJob, listener: VideoExportListener) {
            startedJob = job
            this.listener = listener
        }

        override fun cancel() {
            cancelCount++
        }
    }

    /** A listener that records every event in order. */
    private class RecordingListener : VideoExportListener {
        val events = mutableListOf<String>()

        override fun onProgress(progress: VideoExportProgress) {
            events.add("progress:${progress.percentHundredths}:${progress.stage}")
        }

        override fun onResult(result: VideoExportResult) {
            events.add("result:$result")
        }
    }

    private fun validPlan() = VideoEditPlan(
        durationMs = 10_000,
        trim = TimeRange(0, 10_000),
        speedSegments = listOf(SpeedSegment(2_000, 4_000, 0.5)),
        audioTrackPresent = true,
    )

    private fun request(plan: VideoEditPlan = validPlan()) = VideoExportRequest(
        plan = plan,
        sourceUri = "content://media/external/video/media/7",
        workingDirPath = "/cache",
    )

    /** A valid two-clip composition job (the r70 half of the seam). */
    private fun clipRequest(mute: Boolean = false) = ClipExportRequest(
        timeline = ClipTimeline(
            listOf(
                ClipTimelineEntry(
                    sourceUri = "content://media/external/video/media/7",
                    durationMs = 10_000,
                    audioTrackPresent = true,
                    trim = TimeRange(0, 4_000),
                ),
                ClipTimelineEntry(
                    sourceUri = "content://media/external/video/media/9",
                    durationMs = 6_000,
                    audioTrackPresent = false,
                    trim = TimeRange(1_000, 6_000),
                    speedFactor = 0.5,
                ),
            ),
        ),
        mute = mute,
        workingDirPath = "/cache",
    )

    // ---- the plan validation gate runs BEFORE the runner ----

    @Test
    fun `an invalid plan is refused and the runner never starts`() {
        val fake = FakeRunner()
        val coordinator = VideoExportCoordinator(fake)
        val invalidPlan = validPlan().copy(
            trim = TimeRange(0, 20_000), // past the duration
        )
        val outcome = coordinator.submit(request(invalidPlan), RecordingListener())
        assertTrue(outcome is VideoExportCoordinator.SubmitOutcome.Refused)
        assertTrue(
            (outcome as VideoExportCoordinator.SubmitOutcome.Refused).reason.contains("past the clip duration"),
        )
        assertNull("the runner must never see an invalid plan", fake.startedJob)
    }

    @Test
    fun `a valid plan starts the runner with the request verbatim`() {
        val fake = FakeRunner()
        val coordinator = VideoExportCoordinator(fake)
        val request = request()
        val outcome = coordinator.submit(request, RecordingListener())
        assertEquals(VideoExportCoordinator.SubmitOutcome.Started, outcome)
        assertEquals(request, fake.startedJob)
    }

    // ---- the r70 composition jobs ride the same rules ----

    @Test
    fun `a valid composition job starts the runner and is remembered as the last job`() {
        val fake = FakeRunner()
        val coordinator = VideoExportCoordinator(fake)
        val job = clipRequest()
        val outcome = coordinator.submit(job, RecordingListener())
        assertEquals(VideoExportCoordinator.SubmitOutcome.Started, outcome)
        assertEquals(job, fake.startedJob)
        assertEquals(job, coordinator.lastJob)
    }

    @Test
    fun `an invalid composition job is refused and the runner never starts`() {
        val fake = FakeRunner()
        val coordinator = VideoExportCoordinator(fake)
        val invalid = clipRequest().copy(
            volume = 5f, // past the +6 dB ceiling
        )
        val outcome = coordinator.submit(invalid, RecordingListener())
        assertTrue(outcome is VideoExportCoordinator.SubmitOutcome.Refused)
        assertTrue(
            (outcome as VideoExportCoordinator.SubmitOutcome.Refused).reason.contains("volume"),
        )
        assertNull("the runner must never see an invalid composition", fake.startedJob)
    }

    @Test
    fun `a composition job and a plan job share the single-flight rule`() {
        val fake = FakeRunner()
        val coordinator = VideoExportCoordinator(fake)
        coordinator.submit(clipRequest(), RecordingListener())
        val second = coordinator.submit(request(), RecordingListener())
        assertTrue(second is VideoExportCoordinator.SubmitOutcome.Refused)
        assertTrue(
            (second as VideoExportCoordinator.SubmitOutcome.Refused).reason.contains("already running"),
        )
    }

    // ---- single flight ----

    @Test
    fun `a second submit while running is refused`() {
        val fake = FakeRunner()
        val coordinator = VideoExportCoordinator(fake)
        coordinator.submit(request(), RecordingListener())
        val second = coordinator.submit(request(), RecordingListener())
        assertTrue(second is VideoExportCoordinator.SubmitOutcome.Refused)
        assertTrue(
            (second as VideoExportCoordinator.SubmitOutcome.Refused).reason.contains("already running"),
        )
        assertEquals("the runner was started exactly once", 1, fake.startedRequests())
    }

    @Test
    fun `a submit after a terminal result is accepted again`() {
        val fake = FakeRunner()
        val coordinator = VideoExportCoordinator(fake)
        val first = RecordingListener()
        coordinator.submit(request(), first)
        fake.listener!!.onResult(
            VideoExportResult.Success("/cache/render.mp4", 10_000),
        )
        val second = coordinator.submit(request(), RecordingListener())
        assertEquals(VideoExportCoordinator.SubmitOutcome.Started, second)
    }

    // ---- progress and the exactly-once result ----

    @Test
    fun `progress forwards in order and the result arrives exactly once`() {
        val fake = FakeRunner()
        val coordinator = VideoExportCoordinator(fake)
        val listener = RecordingListener()
        coordinator.submit(request(), listener)
        fake.listener!!.onProgress(VideoExportProgress(0))
        fake.listener!!.onProgress(VideoExportProgress(2_500))
        fake.listener!!.onProgress(VideoExportProgress(10_000))
        fake.listener!!.onResult(VideoExportResult.Success("/cache/render.mp4", 14_000))
        // a late duplicate result after the terminal one is dropped
        fake.listener!!.onResult(VideoExportResult.Failure("late"))
        assertEquals(
            listOf(
                "progress:0:RENDERING",
                "progress:2500:RENDERING",
                "progress:10000:RENDERING",
                "result:Success(renderedFilePath=/cache/render.mp4, outputDurationMs=14000)",
            ),
            listener.events,
        )
    }

    @Test
    fun `progress after the terminal result is dropped`() {
        val fake = FakeRunner()
        val coordinator = VideoExportCoordinator(fake)
        val listener = RecordingListener()
        coordinator.submit(request(), listener)
        fake.listener!!.onResult(VideoExportResult.Canceled)
        fake.listener!!.onProgress(VideoExportProgress(5_000))
        assertEquals(listOf("result:Canceled"), listener.events)
    }

    // ---- cancellation ----

    @Test
    fun `cancel reaches the runner and the runner's canceled result forwards`() {
        val fake = FakeRunner()
        val coordinator = VideoExportCoordinator(fake)
        val listener = RecordingListener()
        coordinator.submit(request(), listener)
        coordinator.cancel()
        assertEquals(1, fake.cancelCount)
        fake.listener!!.onResult(VideoExportResult.Canceled)
        assertEquals(listOf("result:Canceled"), listener.events)
    }

    @Test
    fun `cancel when idle is a no-op`() {
        val fake = FakeRunner()
        val coordinator = VideoExportCoordinator(fake)
        coordinator.cancel()
        assertEquals(0, fake.cancelCount)
    }

    // ---- the typed results carry the honest facts ----

    @Test
    fun `a failure carries the runner's honest message`() {
        val fake = FakeRunner()
        val coordinator = VideoExportCoordinator(fake)
        val listener = RecordingListener()
        coordinator.submit(request(), listener)
        fake.listener!!.onResult(VideoExportResult.Failure("the encoder refused this clip"))
        assertTrue(listener.events.single().contains("the encoder refused this clip"))
    }

    private fun FakeRunner.startedRequests(): Int = if (startedJob != null) 1 else 0
}
