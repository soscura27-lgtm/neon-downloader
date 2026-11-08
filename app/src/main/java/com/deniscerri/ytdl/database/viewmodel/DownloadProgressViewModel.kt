package com.deniscerri.ytdl.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.repository.DownloadRepository
import com.deniscerri.ytdl.util.WorkerEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nivel de descarga global: un solo porcentaje para "todo lo que se está bajando ahora".
 *
 * Un "lote" empieza cuando aparece algo activo o en cola y termina cuando ya no queda nada pendiente.
 * El porcentaje es (descargadas + con error + avance parcial de las activas) / total del lote.
 * Sirve igual para un vídeo suelto que para una playlist o un canal entero de cientos de vídeos.
 *
 * Fuentes: los ids activos / en cola / en pausa de la base de datos, y los eventos de avance que
 * publica el worker de descargas (WorkerEventBus), que son el mismo dato que ve la tarjeta de cada descarga.
 */
class DownloadProgressViewModel(application: Application) : AndroidViewModel(application) {

    data class Overall(
        /** 0..100; no baja mientras el lote no cambie de tamaño. */
        val percent: Int,
        /** Terminadas con éxito. */
        val finished: Int,
        /** Terminadas con error. */
        val failed: Int,
        /** Todo el lote: terminadas + con error + las que siguen pendientes. */
        val total: Int,
        val active: Int,
        val waiting: Int,
        val paused: Int,
        /** El lote ya terminó (se enseña unos segundos y se oculta). */
        val done: Boolean
    )

    private class Snapshot(
        val active: List<Long>,
        val queued: List<Long>,
        val paused: List<Long>
    )

    private enum class Outcome { SUCCESS, FAILED, DROP, PENDING }

    private val dao = DBManager.getInstance(application).downloadDao

    private val _overall = MutableStateFlow<Overall?>(null)

    /** null = no hay nada que mostrar. */
    val overall: StateFlow<Overall?> = _overall.asStateFlow()

    private val lock = Any()

    // Todo lo siguiente solo se toca dentro de synchronized(lock)
    private var running = false
    private var finished = 0
    private var failed = 0

    /** Ids pendientes en la última lectura (sirve para detectar los que acaban de salir de la cola). */
    private var known: Set<Long> = emptySet()
    private var activeIds: Set<Long> = emptySet()
    private var waitingCount = 0
    private var pausedCount = 0
    private var pendingCount = 0
    private val everActive = HashSet<Long>()
    private val progress = HashMap<Long, Int>()
    private var floor = 0
    private var floorTotal = -1
    private var hideJob: Job? = null

    private val refresh = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        // 1) El avance de cada descarga activa
        viewModelScope.launch(Dispatchers.Default) {
            WorkerEventBus.events.collect { event ->
                synchronized(lock) {
                    // los eventos de ids que ya no están pendientes son tardíos: se ignoran
                    if (running && known.contains(event.downloadItemID)) {
                        progress[event.downloadItemID] = event.progress.coerceIn(0, 100)
                        everActive.add(event.downloadItemID)
                    }
                }
                refresh.tryEmit(Unit)
            }
        }

        // 2) Qué hay activo, en espera y en pausa
        viewModelScope.launch(Dispatchers.Default) {
            combine(
                dao.getActiveDownloadIdsFlow().distinctUntilChanged(),
                dao.getQueuedDownloadIdsFlow().distinctUntilChanged(),
                dao.getPausedDownloadIdsFlow().distinctUntilChanged()
            ) { active, queued, paused -> Snapshot(active, queued, paused) }
                .collect { onStatus(it) }
        }

        // 3) Publicar el resultado como mucho unas 4 veces por segundo
        viewModelScope.launch(Dispatchers.Default) {
            refresh.collect {
                publish()
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    private suspend fun onStatus(snapshot: Snapshot) {
        val pending = HashSet<Long>(snapshot.active.size + snapshot.queued.size + snapshot.paused.size)
        pending.addAll(snapshot.active)
        pending.addAll(snapshot.queued)
        pending.addAll(snapshot.paused)

        // Ids que estaban pendientes y ya no: terminaron, fallaron, se cancelaron o se quitaron de la cola
        val gone: List<Pair<Long, Boolean>> = synchronized(lock) {
            if (!running && pending.isNotEmpty()) startBatchLocked()
            if (running) everActive.addAll(snapshot.active)
            val leaving = if (running) known.filter { !pending.contains(it) } else emptyList()
            activeIds = snapshot.active.toSet()
            waitingCount = snapshot.queued.size
            pausedCount = snapshot.paused.size
            pendingCount = pending.size
            known = pending
            leaving.map { it to everActive.contains(it) }
        }

        val outcomes: List<Pair<Long, Outcome>> = if (gone.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { gone.map { (id, wasActive) -> id to resolve(id, wasActive) } }
        }

        synchronized(lock) {
            var stillPending = false
            for ((id, outcome) in outcomes) {
                when (outcome) {
                    Outcome.SUCCESS -> finished++
                    Outcome.FAILED -> failed++
                    Outcome.DROP -> Unit
                    // pasó por un estado intermedio (por ejemplo de "en cola" a "activa"): reaparece en la siguiente lectura
                    Outcome.PENDING -> stillPending = true
                }
                if (outcome != Outcome.PENDING) {
                    progress.remove(id)
                    everActive.remove(id)
                }
            }
            if (running && pendingCount == 0 && !stillPending) finishBatchLocked()
        }
        refresh.tryEmit(Unit)
    }

    /**
     * ¿Qué pasó con una descarga que ya no está activa ni en cola?
     *  - su fila ya no existe: si llegó a estar activa, terminó bien (el worker borra la fila al acabar);
     *    si nunca estuvo activa, el usuario la quitó de la cola
     *  - estado Error: falló
     *  - sigue pendiente: solo cambió de estado mientras se leía
     *  - cualquier otro estado (cancelada, programada...): no cuenta para el lote
     */
    private fun resolve(id: Long, wasActive: Boolean): Outcome {
        val status = try {
            dao.checkStatus(id)
        } catch (e: Exception) {
            return Outcome.DROP
        }
        return when (status) {
            null -> if (wasActive) Outcome.SUCCESS else Outcome.DROP
            DownloadRepository.Status.Error -> Outcome.FAILED
            DownloadRepository.Status.Active,
            DownloadRepository.Status.Queued,
            DownloadRepository.Status.QueuedPaused,
            DownloadRepository.Status.Paused -> Outcome.PENDING
            else -> Outcome.DROP
        }
    }

    private fun startBatchLocked() {
        running = true
        finished = 0
        failed = 0
        everActive.clear()
        progress.clear()
        floor = 0
        floorTotal = -1
        hideJob?.cancel()
        hideJob = null
    }

    private fun finishBatchLocked() {
        running = false
        known = emptySet()
        activeIds = emptySet()
        progress.clear()
        everActive.clear()
        hideJob?.cancel()
        hideJob = viewModelScope.launch(Dispatchers.Default) {
            delay(DONE_VISIBLE_MS)
            synchronized(lock) {
                if (!running) {
                    finished = 0
                    failed = 0
                    _overall.value = null
                }
            }
        }
    }

    private fun publish() {
        synchronized(lock) {
            val total = finished + failed + pendingCount
            if (total <= 0 || (!running && finished + failed == 0)) {
                _overall.value = null
                return
            }

            var percent: Int
            if (running) {
                val partial = activeIds.sumOf { progress[it] ?: 0 } / 100.0
                percent = (((finished + failed) + partial) * 100.0 / total).toInt().coerceIn(0, 100)
                // el porcentaje no retrocede mientras el lote sea del mismo tamaño
                // (yt-dlp vuelve a empezar de 0 al pasar del vídeo al audio)
                if (total != floorTotal) {
                    floorTotal = total
                    floor = percent
                } else if (percent < floor) {
                    percent = floor
                } else {
                    floor = percent
                }
            } else {
                percent = 100
            }

            _overall.value = Overall(
                percent = percent,
                finished = finished,
                failed = failed,
                total = total,
                active = activeIds.size,
                waiting = waitingCount,
                paused = pausedCount,
                done = !running
            )
        }
    }

    companion object {
        private const val PUBLISH_INTERVAL_MS = 250L
        private const val DONE_VISIBLE_MS = 8000L
    }
}
