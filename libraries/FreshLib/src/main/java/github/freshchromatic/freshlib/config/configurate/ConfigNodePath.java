package github.freshchromatic.freshlib.config.configurate;

import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

record ConfigNodePath(List<Object> segments) {

    ConfigNodePath {
        segments = List.copyOf(segments);
    }

    static ConfigNodePath root() {
        return new ConfigNodePath(List.of());
    }

    ConfigNodePath child(Object segment) {
        List<Object> childSegments = new ArrayList<>(segments);
        childSegments.add(segment);
        return new ConfigNodePath(childSegments);
    }

    void copyDefaultTo(ConfigurationNode target, ConfigurationNode defaults) throws ConfigurateException {
        Object[] path = segments.toArray();
        target.node(path).set(defaults.node(path));
    }

    @Override
    public String toString() {
        return segments.stream().map(String::valueOf).collect(Collectors.joining("."));
    }
}
