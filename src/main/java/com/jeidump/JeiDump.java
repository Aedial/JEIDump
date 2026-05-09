package com.jeidump;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.jeidump.command.CommandDumpJei;
import com.jeidump.config.JeiDumpConfig;

/**
 * Entry point for JEI Dump.
 *
 * Provides a client-side {@code /dumpjei} command that exports every JEI recipe (vanilla + plugins)
 * along with its rendered icons and full layout image into a self-contained static HTML+JS site
 * placed under {@code <gameDir>/jeidump/}.
 *
 * The mod itself only adds a command and a JEI plugin to capture {@code IJeiRuntime}; all heavy
 * lifting happens lazily when the command runs.
 */
@Mod(modid = Tags.MODID,
    name = Tags.MODNAME,
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.12.2,1.13)",
    dependencies = "required-after:jei@[4.16.0,);",
    clientSideOnly = true,
    guiFactory = "com.jeidump.config.JeiDumpGuiFactory")
public class JeiDump {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        JeiDumpConfig.init(event.getSuggestedConfigurationFile());
        MinecraftForge.EVENT_BUS.register(new JeiDumpConfig());
    }

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void init(FMLInitializationEvent event) {
        // Client commands are registered with Forge's ClientCommandHandler; they work on /dumpjei in singleplayer
        // and on multiplayer servers without needing op or any server-side counterpart.
        if (Minecraft.getMinecraft() != null) {
            ClientCommandHandler.instance.registerCommand(new CommandDumpJei());
        }
    }
}
