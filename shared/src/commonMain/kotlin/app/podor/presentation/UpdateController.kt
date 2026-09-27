package app.podor.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.podor.data.UpdateException
import app.podor.data.UpdateSource
import app.podor.domain.*
import kotlinx.coroutines.*

class UpdateController(
    private val source: UpdateSource,
    private val scope: CoroutineScope,
    val currentVersion: String,
) {
    var state by mutableStateOf(UpdateState())
        private set

    private var job: Job? = null

    fun check() {
        if (job?.isActive == true) return
        state = UpdateState(UpdatePhase.Checking)
        job = scope.launch {
            try {
                val release = source.latest()
                release.validate()
                val newer = AppVersion.parse(release.version) > AppVersion.parse(currentVersion)
                state =
                    UpdateState(if (newer) UpdatePhase.Available else UpdatePhase.Current, release)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                state = UpdateState(UpdatePhase.Failed, problem = failure.problem())
            }
        }
    }

    fun download() {
        if (job?.isActive == true) return
        val release = state.release ?: return
        state = UpdateState(UpdatePhase.Downloading, release)
        job = scope.launch {
            try {
                val installer =
                    source.download(release) { received ->
                        withContext(scope.coroutineContext.minusKey(Job)) {
                            state = state.copy(received = received)
                        }
                    }
                state = UpdateState(UpdatePhase.Downloaded, release, release.size, installer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                state = UpdateState(UpdatePhase.Failed, release, problem = failure.problem())
            }
        }
    }

    fun cancel() {
        val previous = job ?: return
        previous.cancel()
        job = scope.launch {
            previous.join()
            state =
                if (state.release == null) UpdateState()
                else UpdateState(UpdatePhase.Available, state.release)
        }
    }

    fun reveal() {
        if (job?.isActive == true) return
        val installer = state.installer ?: return
        job = scope.launch {
            try {
                source.reveal(installer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state = state.copy(problem = UpdateProblem.Storage)
            }
        }
    }

    fun close() {
        job?.cancel()
    }

    private fun Exception.problem(): UpdateProblem =
        (this as? UpdateException)?.problem ?: UpdateProblem.Manifest
}
