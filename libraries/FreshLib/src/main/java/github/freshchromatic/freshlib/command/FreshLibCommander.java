package github.freshchromatic.freshlib.command;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.ForwardingAudience;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

@SuppressWarnings("UnstableApiUsage")
public class FreshLibCommander implements Commander, ForwardingAudience.Single {
    private final CommandSourceStack stack;

    private FreshLibCommander(final CommandSourceStack stack) {
        this.stack = stack;
    }

    @Override
    public Audience audience() {
        final Entity executor = this.stack.getExecutor();
        return executor == null ? this.stack.getSender() : executor;
    }

    @Override
    public boolean hasPermission(final String permission) {
        return this.stack.getSender().hasPermission(permission);
    }

    public CommandSourceStack stack() {
        return this.stack;
    }

    @Override
    public Object commanderId() {
        return this.stack.getSender();
    }

    public static github.freshchromatic.freshlib.command.FreshLibCommander from(final CommandSourceStack stack) {
        if (stack.getSender() instanceof Player) {
            return new github.freshchromatic.freshlib.command.FreshLibCommander.PlayerSender(stack);
        }
        return new github.freshchromatic.freshlib.command.FreshLibCommander(stack);
    }

    public static final class PlayerSender extends github.freshchromatic.freshlib.command.FreshLibCommander implements PlayerCommander {
        private PlayerSender(final CommandSourceStack stack) {
            super(stack);
        }

        @Override
        public Player player() {
            return (Player) this.stack().getSender();
        }

        @Override
        public Object commanderId() {
            return this.player().getUniqueId();
        }
    }
}
