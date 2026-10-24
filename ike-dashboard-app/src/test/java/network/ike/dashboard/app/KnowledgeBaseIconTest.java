package network.ike.dashboard.app;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A knowledge base without an icon is told apart by its monogram: initials from its name, in a colour
 * that is the same for the name on every run and every machine.
 */
class KnowledgeBaseIconTest {

    @Test
    void initialsComeFromTheWordsThatTellTheKnowledgeBaseApart() {
        assertThat(KnowledgeBaseIcon.initials("pizza-kb")).isEqualTo("Pi");
        assertThat(KnowledgeBaseIcon.initials("solor-kb")).isEqualTo("So");
        assertThat(KnowledgeBaseIcon.initials("solor-gudid-kb")).isEqualTo("SG");
        assertThat(KnowledgeBaseIcon.initials("solor-gudid-nuva-kb")).isEqualTo("SGN");
        assertThat(KnowledgeBaseIcon.initials("snomedct-loinc-kb")).isEqualTo("SL");
        assertThat(KnowledgeBaseIcon.initials("DeX")).as("split where case changes").isEqualTo("DX");
        assertThat(KnowledgeBaseIcon.initials("kb")).as("nothing else to go on").isEqualTo("Kb");
        assertThat(KnowledgeBaseIcon.initials("--")).isEqualTo("?");
    }

    @Test
    void theColourIsStableForAName() {
        // The hue is CRC-32 of the lower-case name, modulo 360: a fixed value, not a per-run hash.
        assertThat(KnowledgeBaseIcon.color("pizza-kb").getHue()).isCloseTo(19, org.assertj.core.data.Offset.offset(0.5));
        assertThat(KnowledgeBaseIcon.color("solor-kb").getHue()).isCloseTo(191, org.assertj.core.data.Offset.offset(0.5));
        assertThat(KnowledgeBaseIcon.color("DeX")).as("case is ignored").isEqualTo(KnowledgeBaseIcon.color("dex"));
        assertThat(KnowledgeBaseIcon.color("pizza-kb")).isEqualTo(KnowledgeBaseIcon.color("pizza-kb"))
                .isNotEqualTo(KnowledgeBaseIcon.color("solor-kb"));
        Color pizza = KnowledgeBaseIcon.color("pizza-kb");
        assertThat(pizza.getBrightness()).as("dark enough for white initials").isLessThan(0.7);
    }
}
