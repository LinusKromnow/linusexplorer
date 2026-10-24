package io.github.linuskromnow.linusexplorer.tasks

import android.content.Context
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.fs.ConflictResolution
import io.github.linuskromnow.linusexplorer.fs.ConflictResolver
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.TransferProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TransferKind(val titleRes: Int) {
    COPY(R.string.transfer_copying),
    MOVE(R.string.transfer_moving),
    COMPRESS(R.string.transfer_compressing),
    EXTRACT(R.string.transfer_extracting),
    OPEN(R.string.transfer_opening),
    ENCRYPT(R.string.transfer_encrypting),
    DECRYPT(R.string.transfer_decrypting),
    BACKUP(R.string.transfer_backing_up),
    RESTORE(R.string.transfer_restoring),
    DELETE(R.string.transfer_deleting),
}

/** A name clash the running task is waiting on; answer it with [TaskManager.answerConflict]. */
class ConflictRequest(
    val source: FileEntry,
    val existing: FileEntry,
    internal val answer: CompletableDeferred<Pair<ConflictResolution, Boolean>>,
) {
    /** Two folders: "Replace" means merge. */
    val bothFolders: Boolean get() = source.isDirectory && existing.isDirectory
}

data class TransferState(
    val kind: TransferKind,
    val progress: TransferProgress? = null,
    val conflict: ConflictRequest? = null,
)

/** What a running task can use: progress reporting and interactive conflict resolution. */
interface TaskScope {
    val onProgress: (TransferProgress) -> Unit
    val conflicts: ConflictResolver
}

/**
 * Runs one long file operation at a time in an app-wide scope, so it keeps going when the screen
 * is closed. [TransferService] shows it as a notification while it runs.
 */
class TaskManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _current = MutableStateFlow<TransferState?>(null)
    val current: StateFlow<TransferState?> = _current.asStateFlow()
    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    /** Starts [block] and returns true, or returns false if another task is still running. */
    fun start(kind: TransferKind, block: suspend TaskScope.() -> Unit): Boolean {
        if (isBusy) return false
        _current.value = TransferState(kind)
        TransferService.start(context)
        job = scope.launch {
            try {
                Scope().block()
            } finally {
                _current.value = null
            }
        }
        return true
    }

    fun cancel() {
        _current.value?.conflict?.answer?.cancel()
        job?.cancel()
    }

    fun answerConflict(resolution: ConflictResolution, applyToAll: Boolean) {
        _current.value?.conflict?.answer?.complete(resolution to applyToAll)
    }

    private inner class Scope : TaskScope {
        private var forAll: ConflictResolution? = null

        override val onProgress: (TransferProgress) -> Unit = { progress ->
            _current.update { it?.copy(progress = progress) }
        }

        override val conflicts = ConflictResolver { source, existing ->
            forAll ?: run {
                val answer = CompletableDeferred<Pair<ConflictResolution, Boolean>>()
                _current.update { it?.copy(conflict = ConflictRequest(source, existing, answer)) }
                try {
                    val (resolution, applyToAll) = answer.await()
                    if (applyToAll) forAll = resolution
                    resolution
                } finally {
                    _current.update { it?.copy(conflict = null) }
                }
            }
        }
    }
}
