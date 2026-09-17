package com.flyfishxu.kadb

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import okio.sink
import okio.source

fun Kadb.pull(
    dst: DocumentFile,
    remotePath: String,
    context: Context
) {
    val outputStream = context.contentResolver.openOutputStream(dst.uri)
    checkNotNull(outputStream)
    outputStream.use { stream ->
        stream.sink().use { sink ->
            pull(sink, remotePath)
        }
    }
}

fun Kadb.push(
    src: DocumentFile,
    remotePath: String,
    context: Context,
    mode: Int = readMode(src),
    lastModifiedMs: Long = src.lastModified()
) {
    val inputStream = context.contentResolver.openInputStream(src.uri)
    checkNotNull(inputStream)
    inputStream.use { stream ->
        stream.source().use { source ->
            push(source, remotePath, mode, lastModifiedMs)
        }
    }
}

fun Kadb.install(
    src: DocumentFile,
    context: Context
) {
    val inputStream = context.contentResolver.openInputStream(src.uri)
    checkNotNull(inputStream)
    inputStream.use { stream ->
        stream.source().use { source ->
            install(source, src.length())
        }
    }
}

fun Kadb.readMode(file: DocumentFile): Int {
    var mode = 0
    if (file.canRead()) {
        mode = mode or 256
    }
    if (file.canWrite()) {
        mode = mode or 128
    }
    return mode
}
