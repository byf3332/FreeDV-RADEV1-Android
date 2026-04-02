package com.byf3332.radexcvr.cat

import android.content.Context

data class Ft8CnRigProfile(
    val modelName: String,
    val civAddress: Int,
    val baudRate: Int,
    val instructionSet: Int
) {
    val key: String = "$modelName|$civAddress|$baudRate|$instructionSet"
}

object Ft8CnRigCatalog {
    fun load(context: Context): List<Ft8CnRigProfile> {
        return context.assets.open("rigaddress.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") && it.contains(",") }
                .mapNotNull { line ->
                    val parts = line.split(",")
                    if (parts.size < 4) return@mapNotNull null
                    val civ = parts[1].trim().toIntOrNull(16) ?: return@mapNotNull null
                    val baud = parts[2].trim().toIntOrNull() ?: return@mapNotNull null
                    val instruction = parts[3].trim().toIntOrNull() ?: return@mapNotNull null
                    Ft8CnRigProfile(
                        modelName = parts[0].trim(),
                        civAddress = civ,
                        baudRate = baud,
                        instructionSet = instruction
                    )
                }
                .toList()
        }
    }
}
