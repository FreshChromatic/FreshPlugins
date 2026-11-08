package github.freshchromatic.freshlib.config.configurate;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ConfigMissingOptionDetector {

    List<ConfigNodePath> find(ConfigurationNode defaults, ConfigurationNode loaded, Class<?> configType) {
        return find(defaults, loaded, ConfigNodePath.root(), configType);
    }

    private List<ConfigNodePath> find(ConfigurationNode defaults, ConfigurationNode loaded, ConfigNodePath prefix,
        Class<?> configType) {
        List<ConfigNodePath> missing = new ArrayList<>();
        if (configType == null) {
            return missing;
        }

        Map<String, Field> fieldsByDashedName = fieldsByDashedName(configType);
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : defaults.childrenMap().entrySet()) {
            String nodeKey = entry.getKey().toString();
            ConfigNodePath fullPath = prefix.child(entry.getKey());
            Field field = fieldsByDashedName.get(nodeKey);
            if (field == null) {
                continue;
            }

            ConfigurationNode loadedChild = loaded.node(nodeKey);
            if (loadedChild.virtual()) {
                missing.add(fullPath);
                continue;
            }
            if (!entry.getValue().isMap()) {
                continue;
            }

            Class<?> fieldType = field.getType();
            if (fieldType.isAnnotationPresent(ConfigSerializable.class)) {
                missing.addAll(find(entry.getValue(), loadedChild, fullPath, fieldType));
            } else if (Map.class.isAssignableFrom(fieldType)) {
                findConfigSerializableMapValues(entry.getValue(), loadedChild, fullPath, field, missing);
            }
        }
        return missing;
    }

    private void findConfigSerializableMapValues(ConfigurationNode defaults, ConfigurationNode loaded,
        ConfigNodePath prefix, Field field, List<ConfigNodePath> missing) {
        Type genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return;
        }

        Type valueType = parameterizedType.getActualTypeArguments()[1];
        if (!(valueType instanceof Class<?> valueClass) || !valueClass.isAnnotationPresent(ConfigSerializable.class)) {
            return;
        }

        for (Map.Entry<Object, ? extends ConfigurationNode> mapEntry : defaults.childrenMap().entrySet()) {
            ConfigurationNode loadedMapEntry = loaded.node(mapEntry.getKey());
            if (!loadedMapEntry.virtual()) {
                missing.addAll(find(mapEntry.getValue(), loadedMapEntry, prefix.child(mapEntry.getKey()), valueClass));
            }
        }
    }

    private Map<String, Field> fieldsByDashedName(Class<?> configType) {
        Map<String, Field> fields = new HashMap<>();
        Class<?> current = configType;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                }
                String dashedName = ObjectMapperFactoryProvider.getNamingScheme().coerce(field.getName());
                fields.putIfAbsent(dashedName, field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }
}
