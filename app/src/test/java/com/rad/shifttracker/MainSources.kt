package com.rad.shifttracker

import java.io.File

/** Koren izvornog koda aplikacije, bilo da testovi rade iz modula `app` ili iz korena projekta. */
internal fun mainSourceRoot(): File = listOf(
    File("src/main/java/com/rad/shifttracker"),
    File("app/src/main/java/com/rad/shifttracker")
).first { it.isDirectory }

/** Izvorni fajl aplikacije po imenu, u bilo kom paketu (imena fajlova su jedinstvena). */
internal fun mainSourceFile(fileName: String): File {
    val matches = mainSourceRoot().walkTopDown().filter { it.isFile && it.name == fileName }.toList()
    return matches.singleOrNull() ?: error("Izvorni fajl $fileName: nađeno ${matches.size}")
}

internal fun mainSource(fileName: String): String = mainSourceFile(fileName).readText()
