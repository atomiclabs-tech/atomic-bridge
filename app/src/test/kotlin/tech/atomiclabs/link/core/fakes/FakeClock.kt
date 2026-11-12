package tech.atomiclabs.link.core.fakes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import tech.atomiclabs.link.core.port.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class FakeClock(private val scheduler: TestCoroutineScheduler, var originEpochMs: Long = 0) : Clock {
    override fun epochMs(): Long = originEpochMs + scheduler.currentTime
    override fun monotonicMs(): Long = scheduler.currentTime
    override suspend fun sleep(durationMs: Long) { delay(durationMs) }
}
