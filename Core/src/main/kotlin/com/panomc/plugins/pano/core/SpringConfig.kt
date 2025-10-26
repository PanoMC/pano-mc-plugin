package com.panomc.plugins.pano.core

import com.panomc.plugins.pano.core.command.CommandManager
import com.panomc.plugins.pano.core.config.ConfigManager
import com.panomc.plugins.pano.core.event.EventManager
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.mcping.MinecraftStatusClient
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.schedule.ScheduleManager
import io.vertx.core.Vertx
import io.vertx.core.http.WebSocketClient
import io.vertx.ext.web.client.WebClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.*
import java.util.logging.Logger

@Configuration
//@ComponentScan("com.panomc.plugins.pano") due to spigot & bungeecord componentscan doesn't work :(
open class SpringConfig {
    companion object {
        private lateinit var vertx: Vertx
        private lateinit var panoPluginMain: PanoPluginMain

        internal fun setDefaults(vertx: Vertx, panoPluginMain: PanoPluginMain) {
            this.vertx = vertx
            this.panoPluginMain = panoPluginMain
        }
    }

    @Autowired
    private lateinit var applicationContext: AnnotationConfigApplicationContext

    @Bean
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun vertx() = vertx

    @Bean
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun logger(): Logger = panoPluginMain.getPanoLogger()

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun configManager() =
        ConfigManager(vertx, panoPluginMain.getPanoLogger(), panoPluginMain.getDataFolder())

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun commandManager(platformManager: PlatformManager, scheduleManager: ScheduleManager) =
        CommandManager(panoPluginMain.getPanoLogger(), panoPluginMain, platformManager)

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun scheduleManager() = ScheduleManager(panoPluginMain)

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun eventManager(platformManager: PlatformManager) = EventManager(panoPluginMain, platformManager)

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun platformManager(
        scheduleManager: ScheduleManager,
        configManager: ConfigManager,
        webClient: WebClient,
        webSocketClient: WebSocketClient,
        minecraftStatusClient: MinecraftStatusClient
    ) = PlatformManager(
        vertx,
        panoPluginMain.getPanoLogger(),
        configManager,
        webClient,
        webSocketClient,
        minecraftStatusClient,
        panoPluginMain.getServerData(),
        panoPluginMain
    )

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun provideWebClient(): WebClient = WebClient.create(vertx)

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun provideWebsocketClient(): WebSocketClient = vertx.createWebSocketClient()

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun provideMinecraftStatusClient(): MinecraftStatusClient = MinecraftStatusClient(vertx)


}