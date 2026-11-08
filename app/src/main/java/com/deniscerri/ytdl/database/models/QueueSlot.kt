package com.deniscerri.ytdl.database.models

/** Id y orden de cola de una descarga (solo lectura: se usa para reordenar escribiendo lo mínimo). */
data class QueueSlot(
    val id: Long,
    val queueOrder: Int
)
