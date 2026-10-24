package net.somewhatcity.boiler.core.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.somewhatcity.boiler.core.BoilerPlugin;
import org.bukkit.entity.Player;

import static com.mojang.brigadier.arguments.StringArgumentType.*;

/** Private dialog responses are bound to a player and a fresh per-form nonce. */
public final class DialogCommand {
    public static LiteralArgumentBuilder<CommandSourceStack> build(BoilerPlugin plugin) {
        return Commands.literal("dialog")
                .requires(source -> source.getSender() instanceof Player && source.getExecutor() == source.getSender())
                .then(Commands.argument("token", word())
                        .then(Commands.literal("cancel").executes(context -> {
                            plugin.smartTvInputs().respond((Player) context.getSource().getSender(),getString(context,"token"),"",true);
                            return 1;
                        }))
                        .then(Commands.literal("submit").then(Commands.argument("value",greedyString()).executes(context -> {
                            String value;
                            try { value = decodeText(getString(context,"value")); }
                            catch (IllegalArgumentException invalid) { return 0; }
                            plugin.smartTvInputs().respond((Player) context.getSource().getSender(),getString(context,"token"),value,false);
                            return 1;
                        }))));
    }

    /** Text macros escape both quote kinds for SNBT; Brigadier's string parser rejects escaped apostrophes. */
    public static String decodeText(String encoded) {
        if (encoded.length() < 2 || encoded.charAt(0) != '"' || encoded.charAt(encoded.length()-1) != '"') throw new IllegalArgumentException("Invalid dialog text");
        StringBuilder result = new StringBuilder();
        for (int index=1;index<encoded.length()-1;index++) {
            char next = encoded.charAt(index);
            if (next == '\\') {
                if (++index >= encoded.length()-1) throw new IllegalArgumentException("Invalid escape");
                next = encoded.charAt(index);
                if (next != '\\' && next != '\'' && next != '"') throw new IllegalArgumentException("Invalid escape");
            } else if (next == '"') throw new IllegalArgumentException("Unescaped quote");
            result.append(next);
        }
        if (result.length() > 4096) throw new IllegalArgumentException("Dialog text too long");
        return result.toString();
    }
}
