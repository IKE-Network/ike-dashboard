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

/**
 * Maven repository access with the JDK alone, for the IKE Dashboard. Copied on 2026-10-23 from
 * Komet's {@code artifact-resolution} module ({@code dev.ikm.komet.pluginresolver} in
 * {@code ikmdev/komet}, Apache 2.0, copyright Integrated Knowledge Management), whose file headers
 * the copied classes keep. Changed in the copy: the package; {@link
 * network.ike.dashboard.download.DownloadTask} in place of Tinkar's {@code TrackingCallable}; the
 * Komet-preferences stores and the Komet plugin stager left out; the tuning properties renamed from
 * {@code komet.download.*} to {@code ike.download.*}.
 */
module network.ike.dashboard.download {
    exports network.ike.dashboard.download;

    requires java.net.http;
    requires java.xml;
    requires org.slf4j;

    // Test-only (com.sun.net.httpserver.HttpServer fixtures in *Test classes): the tests compile
    // and run against this same module descriptor, as they did in Komet.
    requires jdk.httpserver;
}
