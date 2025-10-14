package com.panomc.plugins.pano.core.schedule

import com.panomc.plugins.pano.core.helper.PanoPluginMain

class ScheduleManager(
    private val panoPluginMain: PanoPluginMain
) {
    private val scheduleTasks = mutableListOf<() -> Unit>()

    fun schedule(task: () -> Unit) {
        scheduleTasks.add(task)
        panoPluginMain.registerSchedule(task)
    }

    fun stopSchedule(task: () -> Unit) {
        scheduleTasks.remove(task)
        panoPluginMain.stopSchedule(task)
    }

    fun disable() {
        panoPluginMain.unregisterSchedules(scheduleTasks)
        scheduleTasks.clear()
    }
}