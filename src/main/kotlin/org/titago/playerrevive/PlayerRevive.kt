package org.titago.playerrevive

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.entity.Pose
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Added CommandExecutor and TabCompleter interfaces
class PlayerRevive : JavaPlugin(), Listener, CommandExecutor, TabCompleter {

    private val downedPlayers = ConcurrentHashMap<UUID, DownedSession>()
    private lateinit var messagesConfig: YamlConfiguration

    // Global switch for the plugin functionality
    private var isSystemEnabled = true

    // Cached settings from config
    private var settingDownedTime = 20
    private var settingCrawlSpeed = 0.03
    private var settingReviveRadius = 3.0
    private var settingReviveTime = 4.0
    private var settingHpAfterRevive = 6.0

    override fun onEnable() {
        // Save and load configuration files
        saveDefaultConfig()
        loadMessages()
        loadSettings()

        // Register commands
        getCommand("playerrevive")?.setExecutor(this)
        getCommand("playerrevive")?.tabCompleter = this

        server.pluginManager.registerEvents(this, this)
        // Changed enable message as requested
        logger.info("PlayerRevive enabled!")
    }

    override fun onDisable() {
        // Cleanup all active sessions to prevent ghosts/bugs
        downedPlayers.values.forEach { it.cleanup() }
        downedPlayers.clear()
    }

    // --- Command Handling ---

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("playerrevive.admin")) {
            sender.sendMessage(getMsg("messages.no-permission"))
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage(getMsg("messages.unknown-command"))
            return true
        }

        when (args[0].lowercase()) {
            "reload" -> {
                loadSettings()
                loadMessages()
                sender.sendMessage(getMsg("messages.reload-success"))
            }
            "toggle" -> {
                isSystemEnabled = !isSystemEnabled
                if (isSystemEnabled) {
                    sender.sendMessage(getMsg("messages.toggle-on"))
                } else {
                    sender.sendMessage(getMsg("messages.toggle-off"))
                    downedPlayers.values.forEach { it.cleanup() }
                    downedPlayers.clear()
                }
            }
            else -> {
                sender.sendMessage(getMsg("messages.unknown-command"))
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String>? {
        if (args.size == 1) {
            return mutableListOf("reload", "toggle")
        }
        return null
    }

    // --- Configuration Loading ---

    private fun loadMessages() {
        val file = File(dataFolder, "messages.yml")
        if (!file.exists()) {
            saveResource("messages.yml", false)
        }
        messagesConfig = YamlConfiguration.loadConfiguration(file)
    }

    private fun loadSettings() {
        reloadConfig()
        settingDownedTime = config.getInt("settings.downed-time-seconds", 20)
        settingCrawlSpeed = config.getDouble("settings.crawl-speed", 0.03)
        settingReviveRadius = config.getDouble("settings.revive-radius", 3.0)
        settingReviveTime = config.getDouble("settings.revive-time-seconds", 4.0)
        settingHpAfterRevive = config.getDouble("settings.hp-after-revive", 6.0)
    }

    // Helper function to handle color codes (&a, &c, etc.)
    private fun getMsg(path: String, placeholder: String = "", value: String = ""): Component {
        var text = messagesConfig.getString(path) ?: return Component.text(path)
        if (placeholder.isNotEmpty()) {
            text = text.replace(placeholder, value)
        }
        // Use LegacyComponentSerializer for '&' color support
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text)
    }

    // --- Game Logic ---

    inner class DownedSession(
        val player: Player,
        var timeLeft: Int = settingDownedTime,
        var reviveProgress: Double = 0.0,
        var displayEntity: TextDisplay? = null,
        var task: ScheduledTask? = null,
        val reviveBar: BossBar = BossBar.bossBar(
            getMsg("bossbar.reviving"),
            0.0f,
            BossBar.Color.GREEN,
            BossBar.Overlay.PROGRESS
        )
    ) {
        @Suppress("DEPRECATION") // Suppress warning for Attribute.valueOf
        fun cleanup() {
            displayEntity?.remove()
            task?.cancel()

            // Reset movement speed to default.
            try {
                player.getAttribute(Attribute.valueOf("GENERIC_MOVEMENT_SPEED"))?.baseValue = 0.1
            } catch (e: Exception) {
                player.getAttribute(Attribute.valueOf("MOVEMENT_SPEED"))?.baseValue = 0.1
            }

            // Reset player state
            player.isSwimming = false
            player.isSprinting = false
            player.pose = Pose.STANDING
            player.isSneaking = false

            // Fix: Explicitly check if viewer is a Player to ensure hideBossBar works
            for (viewer in reviveBar.viewers()) {
                if (viewer is Player) {
                    viewer.hideBossBar(reviveBar)
                }
            }
        }
    }

    @EventHandler
    fun onDamage(event: EntityDamageEvent) {
        // If system is disabled via command, ignore everything
        if (!isSystemEnabled) return

        val player = event.entity as? Player ?: return

        // If player is already downed, cancel incoming damage
        if (downedPlayers.containsKey(player.uniqueId)) {
            event.isCancelled = true
            return
        }

        // Check for fatal damage
        if (player.health - event.finalDamage <= 0) {
            event.isCancelled = true

            // Immediate death for Void or Lava damage
            if (event.cause == EntityDamageEvent.DamageCause.VOID || event.cause == EntityDamageEvent.DamageCause.LAVA) {
                event.isCancelled = false
                return
            }

            startDownedState(player)
        }
    }

    @Suppress("DEPRECATION") // Suppress warning for Attribute.valueOf
    private fun startDownedState(player: Player) {
        player.health = 1.0
        player.foodLevel = 6

        // Apply crawling speed from config
        try {
            player.getAttribute(Attribute.valueOf("GENERIC_MOVEMENT_SPEED"))?.baseValue = settingCrawlSpeed
        } catch (e: Exception) {
            player.getAttribute(Attribute.valueOf("MOVEMENT_SPEED"))?.baseValue = settingCrawlSpeed
        }

        val location = player.location.clone().add(0.0, 1.0, 0.0)

        // Spawn TextDisplay using Folia scheduler
        player.scheduler.run(this, { _ ->
            val display = player.world.spawn(location, TextDisplay::class.java) { e ->
                // Use explicit method call for Component text
                e.text(getMsg("hologram.text", "{time}", settingDownedTime.toString()))
                e.billboard = Display.Billboard.CENTER
                e.backgroundColor = org.bukkit.Color.fromARGB(100, 0, 0, 0)
                player.addPassenger(e)
            }

            val session = DownedSession(player, displayEntity = display)
            downedPlayers[player.uniqueId] = session

            // Start tick loop (1 tick period for smooth sneak detection)
            session.task = player.scheduler.runAtFixedRate(this, { _ ->
                tickDownedPlayer(player, session)
            }, {
                // Cleanup on task error
                downedPlayers.remove(player.uniqueId)
            }, 1L, 1L)

        }, null)
    }

    private fun tickDownedPlayer(player: Player, session: DownedSession) {
        // FIX: Force swimming state property AND pose to prevent glitching/standing up
        if (!player.isSwimming) {
            player.isSwimming = true
        }
        if (player.pose != Pose.SWIMMING) {
            player.pose = Pose.SWIMMING
        }
        // Disable sprinting to prevent animation glitches
        player.isSprinting = false

        // Timer logic (runs every second / 20 ticks)
        val currentTick = Bukkit.getCurrentTick()
        if (currentTick % 20 == 0) {
            session.timeLeft--
            // Update hologram text
            session.displayEntity?.text(
                getMsg("hologram.text", "{time}", session.timeLeft.toString())
            )

            // Kill player if time runs out
            if (session.timeLeft <= 0) {
                killPlayer(player, session)
                return
            }
        }

        // Revive logic
        var isBeingRevived = false
        val nearbyEntities = player.getNearbyEntities(settingReviveRadius, 2.0, settingReviveRadius)

        for (entity in nearbyEntities) {
            // Check for valid players nearby (excluding downed players and spectators)
            if (entity is Player && !downedPlayers.containsKey(entity.uniqueId) && entity.gameMode != GameMode.SPECTATOR) {
                if (entity.isSneaking) {
                    isBeingRevived = true
                    entity.showBossBar(session.reviveBar)
                } else {
                    entity.hideBossBar(session.reviveBar)
                }
            }
        }

        if (isBeingRevived) {
            // Calculate progress increment per tick based on configured time
            // Formula: 1.0 (total) / (seconds * 20 ticks)
            val progressPerTick = 1.0 / (settingReviveTime * 20.0)
            session.reviveProgress += progressPerTick
            session.reviveBar.progress(session.reviveProgress.coerceIn(0.0, 1.0).toFloat())

            if (session.reviveProgress >= 1.0) {
                revivePlayer(player, session)
            }
        } else {
            // Decay progress if shift is released
            session.reviveProgress = (session.reviveProgress - 0.02).coerceAtLeast(0.0)
            session.reviveBar.progress(session.reviveProgress.toFloat())
        }
    }

    private fun revivePlayer(player: Player, session: DownedSession) {
        session.cleanup()
        downedPlayers.remove(player.uniqueId)

        player.health = settingHpAfterRevive
        player.sendMessage(getMsg("messages.revived"))
        player.sendActionBar(getMsg("messages.survived-actionbar"))
    }

    private fun killPlayer(player: Player, session: DownedSession) {
        session.cleanup()
        downedPlayers.remove(player.uniqueId)
        player.health = 0.0 // Actual death
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        if (downedPlayers.containsKey(uuid)) {
            // If player quits while downed, kill them to prevent abuse
            downedPlayers[uuid]?.cleanup()
            downedPlayers.remove(uuid)
            event.player.health = 0.0
        }
    }
}