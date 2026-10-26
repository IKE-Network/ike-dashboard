/*
 * Copyright © 2026 IKE Network (support@ike.network)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.dashboard.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * On Windows, Java unpacks what the dashboard downloads: every entry lands inside the folder it is
 * unpacked in, whichever separator the zip's tool wrote.
 */
class KometInstallerTest {

    @TempDir
    Path folder;

    @Test
    void aZipIsUnpackedWhicheverSeparatorItsNamesUse() throws IOException {
        Path zip = zip("komet-26.1101.900/bin/launchKomet.bat", "komet-26.1101.900\\conf\\log4j2.xml");

        KometInstaller.unzip(zip, folder.resolve("unpacked"));

        assertThat(folder.resolve("unpacked/komet-26.1101.900/bin/launchKomet.bat")).hasContent("komet-26.1101.900/bin/launchKomet.bat");
        assertThat(folder.resolve("unpacked/komet-26.1101.900/conf/log4j2.xml")).exists();
        assertThat(KometInstallations.imageLauncher(folder.resolve("unpacked/komet-26.1101.900")))
                .contains(folder.resolve("unpacked/komet-26.1101.900/bin/launchKomet.bat"));
    }

    @Test
    void anEntryOutsideTheFolderIsRefused() throws IOException {
        Path zip = zip("komet/bin/launchKomet.bat", "../escaped.txt");

        assertThatThrownBy(() -> KometInstaller.unzip(zip, folder.resolve("unpacked")))
                .hasMessageContaining("names a path outside the folder it is unpacked in: ../escaped.txt");
        assertThat(folder.resolve("escaped.txt")).doesNotExist();
    }

    private Path zip(String... names) throws IOException {
        Path zip = folder.resolve("komet-app.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String name : names) {
                out.putNextEntry(new ZipEntry(name));
                out.write(name.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }
}
