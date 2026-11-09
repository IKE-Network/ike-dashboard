package network.ike.dashboard.app;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A checkpoint release's notes, read into members and commits, under the Komet versions its installers carry. */
class KometNotesTest {

    static final String RELEASES = """
            [{"name": "Checkpoint main-20261108-191310", "tag_name": "checkpoint/main-20261108-191310", "draft": false,
              "html_url": "https://github.com/IKE-Network/ike-komet-wsr/releases/tag/checkpoint/main-20261108-191310",
              "published_at": "2026-11-08T19:32:13Z",
              "body": "**Checkpoint — a pinned working state for testing.** Members are\\npinned at the commits below.\\n\\n## What's changed since `checkpoint/main-20261108-170132`\\n\\n### tinkar-core — [`c062a49` → `5d4f103`](https://github.com/ikmdev/tinkar-core/compare/c062a49...5d4f103)\\n- [`c371628`](https://github.com/ikmdev/tinkar-core/commit/c37162890a4f) Give a STAMP its module's kind\\n- [`5d4f103`](https://github.com/ikmdev/tinkar-core/commit/5d4f1038b07a) Store a stamped document commit's narration in its git repository\\n\\n### komet-desktop — [`5284c71` → `59dc0bc`](https://github.com/ikmdev/komet-desktop/compare/5284c71...59dc0bc)\\n- [`59dc0bc`](https://github.com/ikmdev/komet-desktop/commit/59dc0bcc32ac) Ask a running caret only for more pulses than rest\\n",
              "assets": [{"name": "Komet.Desktop.2026-11-08.1917s-26.1108.1917-osx-aarch64.pkg"},
                         {"name": "Komet.Desktop.2026-11-08.1929s-26.11.1929-windows-x86_64.msi"}]}]
            """;

    @Test
    void aCheckpointsNotesAreItsMembersAndCommitsUnderEachVersionItCarries() {
        Map<String, KometNotes> notes = KometNotes.read(RELEASES);

        assertThat(notes).containsOnlyKeys("26.1108.1917", "26.11.1929");
        KometNotes mac = notes.get("26.1108.1917");
        assertThat(mac.title()).isEqualTo("Checkpoint main-20261108-191310");
        assertThat(mac.members()).extracting(KometNotes.Member::name).containsExactly("tinkar-core", "komet-desktop");
        assertThat(mac.members().getFirst().compare())
                .contains(URI.create("https://github.com/ikmdev/tinkar-core/compare/c062a49...5d4f103"));
        assertThat(mac.members().getFirst().commits()).extracting(KometNotes.Commit::sha, KometNotes.Commit::subject)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("c371628", "Give a STAMP its module's kind"),
                        org.assertj.core.groups.Tuple.tuple("5d4f103",
                                "Store a stamped document commit's narration in its git repository"));
        assertThat(mac.members().getLast().commits().getFirst().url())
                .isEqualTo(URI.create("https://github.com/ikmdev/komet-desktop/commit/59dc0bcc32ac"));
    }
}
