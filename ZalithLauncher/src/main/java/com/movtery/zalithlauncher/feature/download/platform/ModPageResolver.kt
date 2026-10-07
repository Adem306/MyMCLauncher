package com.movtery.zalithlauncher.feature.download.platform

import com.movtery.zalithlauncher.feature.download.enums.Classify
import com.movtery.zalithlauncher.feature.download.enums.Platform
import com.movtery.zalithlauncher.feature.download.item.InfoItem
import com.movtery.zalithlauncher.feature.download.platform.curseforge.CurseForgeCommonUtils
import com.movtery.zalithlauncher.feature.download.platform.modrinth.ModrinthCommonUtils
import com.movtery.zalithlauncher.ui.subassembly.filelist.ModIconStore
import java.io.File

/**
 * Resolves a local Minecraft mod JAR to its exact project page
 * on Modrinth or CurseForge.
 */
object ModPageResolver {

    fun resolve(file: File, platform: Platform): InfoItem? {
        if (!file.isFile || !ModIconStore.isModJar(file)) {
            return null
        }

        return when (platform) {
            Platform.MODRINTH -> resolveModrinth(file)
            Platform.CURSEFORGE -> resolveCurseForge(file)
        }
    }

    private fun resolveModrinth(file: File): InfoItem? {
        val projectId = ModIconStore.findModrinthProjectId(file)
            ?: return null

        return ModrinthCommonUtils.getInfo(
            Platform.MODRINTH.helper.api,
            Classify.MOD,
            projectId
        )
    }

    private fun resolveCurseForge(file: File): InfoItem? {
        val modId = ModIconStore.findCurseForgeModId(file)
            ?: return null

        val data = CurseForgeCommonUtils.searchModFromID(
            Platform.CURSEFORGE.helper.api,
            modId.toString()
        ) ?: return null

        return CurseForgeCommonUtils.getInfoItem(
            data,
            Classify.MOD
        )
    }
}
