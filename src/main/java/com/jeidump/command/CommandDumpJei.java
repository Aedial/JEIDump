package com.jeidump.command;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.ingredients.IIngredientRegistry;

import com.jeidump.JeiDump;
import com.jeidump.config.JeiDumpConfig;
import com.jeidump.dump.Dumper;
import com.jeidump.jei.JeiDumpPlugin;

/**
 * Client-side {@code /dumpjei [folder] [recipesPerTick]} command.
 * <p>
 * The actual rendering MUST happen on the client thread (GL context), but doing the whole dump
 * inside one tick freezes the window long enough that the OS marks it Not Responding (and may
 * kill it on big modpacks). To avoid that, the command spawns a {@link Dumper} state machine and
 * registers a {@link TickEvent.ClientTickEvent} handler that processes a small fixed number of
 * recipes per tick. The Minecraft main loop continues to pump events between ticks, so the OS
 * keeps the window alive and the user can still ALT-TAB.
 */
public class CommandDumpJei extends CommandBase {

    /** Single in-flight job. We refuse to start a second one while this is non-null. */
    @Nullable
    private static DumpRunner active;

    @Override
    public String getName() {
        return "dumpjei";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "jeidump.command.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        // Client-side command: no permission required.
        return 0;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public void execute(@Nullable MinecraftServer server, ICommandSender sender, String[] args) {
        if (active != null) {
            info(sender, "jeidump.command.in_progress", active.job.getResult().recipeCount);
            return;
        }

        IJeiRuntime runtime = JeiDumpPlugin.getRuntime();
        IIngredientRegistry ingredientRegistry = JeiDumpPlugin.getIngredientRegistry();
        if (runtime == null || ingredientRegistry == null) {
            sender.sendMessage(new TextComponentTranslation("jeidump.command.no_runtime"));
            return;
        }

        // Resolve output folder: <gameDir>/jeidump/<folder>; default folder is a UTC timestamp.
        String folderName = args.length > 0 ? sanitize(args[0]) : new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        // Optional second arg: budget per tick. Clamp to a sane range to avoid /dumpjei x 0 stalling forever.
        int budget = JeiDumpConfig.defaultRecipesPerTick;
        if (args.length > 1) {
            try {
                budget = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException nfe) {
                info(sender, "jeidump.command.invalid_budget", args[1], JeiDumpConfig.defaultRecipesPerTick);
            }
        }

        File gameDir = Minecraft.getMinecraft().gameDir;
        File outDir = new File(new File(gameDir, "jeidump"), folderName);

        sender.sendMessage(new TextComponentTranslation("jeidump.command.start", outDir.getAbsolutePath()));
        info(sender, "jeidump.command.budget", budget);

        Dumper job = Dumper.create(runtime, ingredientRegistry, outDir, sender);
        try {
            job.setup();
        } catch (Throwable t) {
            JeiDump.LOGGER.error("JEI dump setup failed", t);
            sender.sendMessage(new TextComponentTranslation("jeidump.command.error", String.valueOf(t.getMessage())));
            return;
        }
        active = new DumpRunner(job, sender, outDir, budget);
        MinecraftForge.EVENT_BUS.register(active);
    }

    /**
     * Forge tick listener that drives one {@link Dumper} job. Self-unregisters when the job
     * finishes (or throws). Lives in CommandDumpJei because the only thing that constructs it is
     * this command, and the only thing it needs from the outside world is the chat sender.
     */
    public static class DumpRunner {
        public final Dumper job;
        private final ICommandSender chatSender;
        private final File outDir;
        private final int recipeBudget;
        private final int backgroundSplitBudget;
        private boolean finished;

        DumpRunner(Dumper job, ICommandSender chatSender, File outDir, int budget) {
            this.job = job;
            this.chatSender = chatSender;
            this.outDir = outDir;
            this.recipeBudget = budget;
            this.backgroundSplitBudget = Math.max(1, JeiDumpConfig.backgroundSplitImagesPerTick);
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            // Only run once per tick; END phase ensures the world has just finished its tick and
            // any GUI screens have settled, which is the safest moment to bind a fresh framebuffer.
            if (event.phase != TickEvent.Phase.END || finished) return;

            boolean more;
            try {
                more = job.step(recipeBudget, backgroundSplitBudget);
            } catch (Throwable t) {
                JeiDump.LOGGER.error("JEI dump step failed", t);
                chatSender.sendMessage(new TextComponentTranslation("jeidump.command.error", String.valueOf(t.getMessage())));
                stop();
                return;
            }
            if (more) return;

            try {
                Dumper.Result r = job.finish();
                chatSender.sendMessage(new TextComponentTranslation("jeidump.command.done",
                    r.categoryCount, r.recipeCount, r.iconCount, outDir.getAbsolutePath()));
            } catch (Throwable t) {
                JeiDump.LOGGER.error("JEI dump finalize failed", t);
                chatSender.sendMessage(new TextComponentTranslation("jeidump.command.error", String.valueOf(t.getMessage())));
            }
            stop();
        }

        private void stop() {
            finished = true;
            MinecraftForge.EVENT_BUS.unregister(this);
            active = null;
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable net.minecraft.util.math.BlockPos pos) {
        return Collections.emptyList();
    }

    /**
     * Restrict folder names to a safe subset to avoid path traversal or weird filesystem chars.
     */
    private static String sanitize(String s) {
        String cleaned = s.replaceAll("[^A-Za-z0-9_.-]", "_");

        return cleaned.isEmpty() ? "dump" : cleaned;
    }

    /** Helper used by the dumper to push a progress line back to the chat. */
    public static void progress(ICommandSender sender, int done, int total, String label) {
        sender.sendMessage(new TextComponentTranslation("jeidump.command.progress", done, total, label));
    }

    /** Helper for translated status lines that are not tied to a dedicated call site type. */
    public static void info(ICommandSender sender, String key, Object... args) {
        sender.sendMessage(new TextComponentTranslation(key, args));
    }
}
