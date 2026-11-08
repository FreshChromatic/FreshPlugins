package github.freshchromatic.freshlib.config.configurate.serializer;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnumSerializerTest {

    @Test
    void serializesAndDeserializesIndependentlyOfDefaultLocale() throws Exception {
        Locale originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            EnumSerializer<Sample> serializer = new EnumSerializer<>(Sample.class);
            var node = BasicConfigurationNode.root();

            node.set("mined-block");
            assertEquals(Sample.MINED_BLOCK, serializer.deserialize(Sample.class, node));

            serializer.serialize(Sample.class, Sample.MINED_BLOCK, node);
            assertEquals("mined-block", node.getString());
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    private enum Sample {
        MINED_BLOCK
    }
}
