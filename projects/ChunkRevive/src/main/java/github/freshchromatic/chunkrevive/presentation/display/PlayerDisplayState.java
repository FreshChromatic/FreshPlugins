package github.freshchromatic.chunkrevive.presentation.display;

import me.tofaa.entitylib.wrapper.WrapperEntity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

final class PlayerDisplayState {

    final Player                   player;
    final Map<Long, WrapperEntity> displays = new HashMap<>();

    PlayerDisplayState(Player player) {
        this.player = player;
    }
}
