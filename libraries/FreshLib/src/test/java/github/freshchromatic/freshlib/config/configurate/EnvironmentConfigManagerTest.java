package github.freshchromatic.freshlib.config.configurate;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentConfigManagerTest {

    @Test
    @SuppressWarnings("unchecked")
    void profilesCanBeAddedAndReset() throws Exception {
        Field profilesField = EnvironmentConfigManager.class.getDeclaredField("ACTIVE_PROFILES");
        Field variablesField = EnvironmentConfigManager.class.getDeclaredField("environmentVariables");
        Field environmentField = EnvironmentConfigManager.class.getDeclaredField("currentEnvironment");
        profilesField.setAccessible(true);
        variablesField.setAccessible(true);
        environmentField.setAccessible(true);
        var profiles = (List<String>) profilesField.get(null);
        var variables = (Map<String, String>) variablesField.get(null);
        var previousProfiles = new ArrayList<>(profiles);
        var previousVariables = new HashMap<>(variables);
        var previousEnvironment = environmentField.get(null);
        try {
            EnvironmentConfigManager.addActiveProfile("Custom");
            EnvironmentConfigManager.addActiveProfile("CUSTOM");
            assertTrue(EnvironmentConfigManager.isProfileActive("custom"));
            assertEquals(1, EnvironmentConfigManager.getActiveProfiles().stream().filter("custom"::equals).count());

            EnvironmentConfigManager.reset();
            assertEquals(List.of("default"), EnvironmentConfigManager.getActiveProfiles());
            assertEquals(EnvironmentConfigManager.Environment.PROD, EnvironmentConfigManager.getEnvironment());
            assertTrue(EnvironmentConfigManager.isProfileActive("production"));
            assertFalse(EnvironmentConfigManager.isProfileActive("not-enabled"));
            EnvironmentConfigManager.addActiveProfile("prod");
            assertFalse(EnvironmentConfigManager.isProfileActive("not-enabled"));
        } finally {
            profiles.clear();
            profiles.addAll(previousProfiles);
            variables.clear();
            variables.putAll(previousVariables);
            environmentField.set(null, previousEnvironment);
        }
    }
}
