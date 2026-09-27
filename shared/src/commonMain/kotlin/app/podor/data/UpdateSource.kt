package app.podor.data

import app.podor.domain.AppRelease
import app.podor.domain.UpdateProblem

class UpdateException(val problem: UpdateProblem) : Exception(problem.label)

interface UpdateSource {
    suspend fun latest(): AppRelease
    suspend fun download(release: AppRelease, progress: suspend (Long) -> Unit): String
    suspend fun reveal(installer: String)
}
