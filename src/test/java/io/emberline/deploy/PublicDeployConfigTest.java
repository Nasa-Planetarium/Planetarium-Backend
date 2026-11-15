package io.emberline.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.yaml.snakeyaml.Yaml;

/**
 * pl-pub items 1, 3, 4, 5 as a parsed-config check (no nginx binary in the build): the pl-web ConfigMap of
 * deploy/k8s/planetarium.yaml is parsed into nginx directives, the tunnel listener's method check and location
 * matching (exact, then regex in order, then longest prefix) are evaluated for each public and each blocked path, the
 * header handling of both listeners is read from the effective server context, and every Service is checked for the
 * tunnel port.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublicDeployConfigTest {

    static final Path MANIFEST = Path.of("deploy/k8s/planetarium.yaml");
    static final Path TUNNEL_MANIFEST = Path.of("deploy/k8s/planetarium-tunnel.yaml");

    List<Map<String, Object>> docs;
    Directive lan;
    Directive tunnel;
    Directive http;

    @BeforeAll
    void parse() throws IOException {
        docs = load(MANIFEST);
        Map<String, Object> data = data(configMap("pl-web-conf"));
        Map<String, String> files = Map.of("/etc/nginx/conf.d/emberline-proxy.inc", (String) data.get("emberline-proxy.inc"),
                "/etc/nginx/release/data-version.map", (String) data(configMap("pl-web-release")).get("data-version.map"));
        http = Nginx.parse((String) data.get("default.conf"), files);
        List<Directive> servers = http.all("server");
        assertThat(servers).hasSize(2);
        lan = servers.stream().filter(s -> s.first("listen").orElseThrow().args.get(0).equals("8080")).findFirst().orElseThrow();
        tunnel = servers.stream().filter(s -> s.first("listen").orElseThrow().args.get(0).equals("8090")).findFirst().orElseThrow();
    }

    // ---- item 5: allowlist on the tunnel listener ----

    static final List<String> PUBLIC = List.of(
            "/api/v1/version",
            "/api/v1/aois",
            "/api/v1/aois/KR",
            "/api/v1/aois/ADM1_ATA+00?",
            "/api/v1/aois/KR/boundary",
            "/api/v1/aois/KR/calendar",
            "/api/v1/aois/KR/months/2025-03/comparison",
            "/api/v1/aois/KR/summary",
            "/api/v1/summaries",
            "/api/v1/hotspots",
            "/api/v1/cells",
            "/api/v1/satellite-status",
            "/api/v1/methods/current",
            "/api/v1/methods/v1-m2v-2026-11-14",
            "/api/v1/firms/wms-tiles",
            // v1.2 layers (contract 272aedb); 404 from the app until their work items land
            "/api/v1/occurrence/grid",
            "/api/v1/occurrence/aois",
            "/api/v1/aois/SGG_31140/occurrence",
            "/api/v1/fire-events",
            "/api/v1/fire-events/KR_20001102_0001",
            "/api/v1/aois/KR/fwi",
            "/api/v1/spread-nowcast",
            "/api/v1/aois/ADM0_ARG/fire-regime",
            "/api/v1/cells/frp");

    static final List<String> BLOCKED = List.of(
            "/", "/index.html",
            "/actuator", "/actuator/health", "/actuator/prometheus",
            "/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui", "/swagger-ui/index.html", "/swagger", "/openapi.json",
            "/api/v1/openapi.json", "/openapi", "/api/v1/openapi",
            "/api/v1/summary-queries",
            "/admin", "/api/admin", "/api/v1/admin", "/ingest", "/api/v1/ingest", "/api/v1/load", "/api/v1/releases",
            "/api/aoi", "/api/summary", "/api/export.csv", "/api/firms/wms", "/api/cells", "/api/hotspots",
            "/api/v2/aois", "/api/v1", "/api/v1/", "/api/v1/aois/KR/months/2025-3/comparison", "/api/v1/aois/KR/other",
            "/api/v1/firms/wms-tiles/x", "/api/v1/cells/x",
            "/api/v1/occurrence", "/api/occurrence", "/api/events", "/api/fwi", "/api/v1/fire-events/KR_1",
            "/api/v1/cells/frp/x");

    @Test
    void eachPublicEndpointIsProxiedForGetAndHead() {
        for (String path : PUBLIC) {
            assertThat(Nginx.outcome(tunnel, "GET", path)).as("GET %s", path).isEqualTo("proxy http://pl-app:8080");
            assertThat(Nginx.outcome(tunnel, "HEAD", path)).as("HEAD %s", path).isEqualTo("proxy http://pl-app:8080");
        }
        // every GET path of the contract is in the list (and nothing else is proxied)
        assertThat(PUBLIC.stream().map(p -> p.replaceAll("^/api/v1/aois/[^/]+", "/api/v1/aois/{aoiId}")
                .replaceAll("months/[0-9-]+/", "months/{yearMonth}/").replaceAll("methods/v1.*", "methods/{methodVersion}")
                .replaceAll("fire-events/[^/]+$", "fire-events/{eventId}"))
                .distinct().sorted().toList()).isEqualTo(contractGetPaths());
    }

    @Test
    void eachBlockedPathIs404() {
        for (String path : BLOCKED) {
            assertThat(Nginx.outcome(tunnel, "GET", path)).as("GET %s", path).isEqualTo("return 404");
        }
    }

    @Test
    void otherMethodsAre405() {
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE")) {
            assertThat(Nginx.outcome(tunnel, method, "/api/v1/aois")).as(method).isEqualTo("return 405");
            assertThat(Nginx.outcome(tunnel, method, "/api/v1/summary-queries")).as(method).isEqualTo("return 405");
            assertThat(Nginx.outcome(tunnel, method, "/actuator")).as(method).isEqualTo("return 405");
        }
    }

    // ---- item 3: the one public POST ----

    @Test
    void postIsProxiedOnlyForExactlyTheForecastPath() {
        assertThat(Nginx.outcome(tunnel, "POST", "/api/v1/forecast")).isEqualTo("proxy http://pl-app:8080");
        for (String path : List.of("/api/v1/forecast/", "/api/v1/forecasts", "/api/v1/forecast/x", "/api/forecast",
                "/api/v1/summary-queries", "/api/v1/aois", "/api/v1/cells/frp", "/actuator", "/")) {
            assertThat(Nginx.outcome(tunnel, "POST", path)).as("POST %s", path).isEqualTo("return 405");
        }
        for (String method : List.of("PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "GET", "HEAD")) {
            assertThat(Nginx.outcome(tunnel, method, "/api/v1/forecast")).as("%s /api/v1/forecast", method).isEqualTo("return 405");
        }
        Directive forecast = tunnel.all("location").stream().filter(l -> l.args.equals(List.of("=", "/api/v1/forecast")))
                .findFirst().orElseThrow();
        // = emberline.limits.forecast-body-bytes (131072); every other tunnel request keeps 1k
        assertThat(forecast.first("client_max_body_size").orElseThrow().args).containsExactly("128k");
        assertThat(tunnel.first("client_max_body_size").orElseThrow().args).containsExactly("1k");
        assertThat(http.deep("proxy_cache_methods").get(0).args).containsExactly("GET", "HEAD");
    }

    @Test
    void managementPortIsProxiedByNeitherListener() {
        for (Directive server : List.of(lan, tunnel)) {
            for (Directive pass : server.deep("proxy_pass")) {
                assertThat(pass.args.get(0)).isEqualTo("http://pl-app:8080");
            }
        }
        assertThat(Nginx.outcome(lan, "GET", "/api/v1/aois")).isEqualTo("proxy http://pl-app:8080");
        assertThat(Nginx.outcome(lan, "GET", "/")).startsWith("static");
    }

    // ---- item 1: client IP ----

    @Test
    void lanListenerClearsTheFourClientHeadersAndSendsRemoteAddr() {
        Map<String, String> headers = proxyHeaders(lan);
        assertThat(headers).containsEntry("X-Client-IP", "").containsEntry("CF-Connecting-IP", "")
                .containsEntry("X-Real-IP", "").containsEntry("X-Forwarded-For", "$emberline_client");
        assertThat(lan.first("set").orElseThrow().args).containsExactly("$emberline_client", "$remote_addr");
        // no location overrides the server-level headers (proxy_set_header is not merged into a location that sets any)
        assertThat(lan.all("location").stream().flatMap(l -> l.deep("proxy_set_header").stream())).isEmpty();
    }

    @Test
    void tunnelListenerMapsXClientIpWhenItIsOneIpLiteral() {
        assertThat(tunnel.first("set").orElseThrow().args).containsExactly("$emberline_client", "$emberline_tunnel_client");
        Map<String, String> headers = proxyHeaders(tunnel);
        assertThat(headers).containsEntry("X-Forwarded-For", "$emberline_client").containsEntry("X-Client-IP", "")
                .containsEntry("CF-Connecting-IP", "").containsEntry("X-Real-IP", "");
        assertThat(tunnel.all("location").stream().flatMap(l -> l.deep("proxy_set_header").stream())).isEmpty();
        Directive map = http.all("map").stream().filter(m -> m.args.equals(List.of("$http_x_client_ip", "$emberline_tunnel_client")))
                .findFirst().orElseThrow();
        for (String ip : List.of("203.0.113.7", "0.0.0.0", "255.255.255.255", "2001:db8::1", "::1", "2001:db8:0:0:0:0:0:1", "fe80::")) {
            assertThat(Nginx.map(map, ip)).as(ip).isEqualTo("$http_x_client_ip");
        }
        for (String bad : List.of("", "256.1.1.1", "1.2.3", "1.2.3.4, 5.6.7.8", "203.0.113.7 ", "evil.example", "2001:db8::1::2",
                "12345::1", "1.2.3.4\r\nX: y", "[2001:db8::1]")) {
            assertThat(Nginx.map(map, bad)).as("'%s'", bad).isEqualTo("$remote_addr");
        }
    }

    @Test
    void tunnelPortIsInNoNodePortOrLoadBalancerService() throws IOException {
        List<Map<String, Object>> all = new ArrayList<>(docs);
        if (Files.exists(TUNNEL_MANIFEST)) {
            all.addAll(load(TUNNEL_MANIFEST));
        }
        boolean tunnelService = false;
        for (Map<String, Object> d : all) {
            if (!"Service".equals(d.get("kind"))) {
                continue;
            }
            Map<String, Object> spec = map(d.get("spec"));
            String type = (String) spec.getOrDefault("type", "ClusterIP");
            for (Object p : (List<?>) spec.get("ports")) {
                Map<String, Object> port = map(p);
                boolean is8090 = "8090".equals(String.valueOf(port.get("port"))) || "8090".equals(String.valueOf(port.get("targetPort")))
                        || "tunnel".equals(String.valueOf(port.get("targetPort")));
                if (is8090) {
                    tunnelService = true;
                    assertThat(type).as(name(d)).isEqualTo("ClusterIP");
                    assertThat(port).as(name(d)).doesNotContainKey("nodePort");
                }
            }
        }
        assertThat(tunnelService).isTrue();
        // ServiceLB is disabled on the host (DEVIATIONS PUB-LAN): no node-port Service at all; LAN stays the port-forward to pl-web.
        for (Map<String, Object> d : all) {
            if ("Service".equals(d.get("kind"))) {
                assertThat(map(d.get("spec")).get("type")).as(name(d)).isIn(null, "ClusterIP");
            }
        }
        Map<String, Object> lanService = all.stream()
                .filter(d -> "Service".equals(d.get("kind")) && "pl-web".equals(name(d))).findFirst().orElseThrow();
        assertThat(map(((List<?>) map(lanService.get("spec")).get("ports")).get(0)))
                .containsEntry("port", 8080).containsEntry("targetPort", 8080);
    }

    @Test
    void networkPolicyAdmits8090OnlyFromTheConnector() throws IOException {
        Map<String, Object> np = docs.stream().filter(d -> "NetworkPolicy".equals(d.get("kind"))).findFirst().orElseThrow();
        Map<String, Object> spec = map(np.get("spec"));
        assertThat(map(map(spec.get("podSelector")).get("matchLabels"))).containsEntry("app", "pl-web");
        String connectorLabel = null;
        for (Object r : (List<?>) spec.get("ingress")) {
            Map<String, Object> rule = map(r);
            List<?> ports = (List<?>) rule.get("ports");
            boolean has8090 = ports.stream().anyMatch(p -> Integer.valueOf(8090).equals(map(p).get("port")));
            if (has8090) {
                List<?> from = (List<?>) rule.get("from");
                assertThat(from).hasSize(1);
                connectorLabel = (String) map(map(map(from.get(0)).get("podSelector")).get("matchLabels")).get("app");
            }
        }
        assertThat(connectorLabel).isEqualTo("emberline-tunnel");
        Map<String, Object> deployment = load(TUNNEL_MANIFEST).stream().filter(d -> "Deployment".equals(d.get("kind"))).findFirst().orElseThrow();
        Map<String, Object> labels = map(map(map(map(deployment.get("spec")).get("template")).get("metadata")).get("labels"));
        assertThat(labels).containsEntry("app", connectorLabel);
    }

    // ---- item 3: proxy cache ----

    @Test
    void proxyCacheKeysOnDataVersionWithLockAndStaleWhileUpdating() {
        for (Directive server : List.of(lan, tunnel)) {
            assertThat(server.first("proxy_cache").orElseThrow().args).containsExactly("emberline");
            assertThat(server.first("proxy_cache_key").orElseThrow().args.get(0)).startsWith("$emberline_data_version|");
            assertThat(server.first("proxy_cache_lock").orElseThrow().args).containsExactly("on");
            assertThat(server.first("proxy_cache_use_stale").orElseThrow().args).contains("updating");
            assertThat(server.first("proxy_no_cache").orElseThrow().args).contains("$emberline_skip_store");
        }
        Directive dv = http.all("map").stream().filter(m -> m.args.get(1).equals("$emberline_data_version")).findFirst().orElseThrow();
        assertThat(dv.first("default").orElseThrow().args).containsExactly("none");
        // a response is stored only under the data_version it carries (or none: tiles)
        Directive skip = http.all("map").stream().filter(m -> m.args.get(1).equals("$emberline_skip_store")).findFirst().orElseThrow();
        assertThat(Nginx.map(skip, "v11#abc|v11#abc")).isEqualTo("0");
        assertThat(Nginx.map(skip, "|v11#abc")).isEqualTo("0");
        assertThat(Nginx.map(skip, "v12#def|v11#abc")).isEqualTo("1");
        assertThat(Nginx.map(skip, "v11#abc|v11#abcd")).isEqualTo("1");
    }

    @Test
    void deployScriptRewritesTheDataVersionMapAndRestartsPlWeb() throws IOException {
        String sh = Files.readString(Path.of("deploy/deploy.sh"));
        assertThat(sh).contains("pl-web-release").contains("data-version.map").contains("rollout restart deployment/pl-web")
                .contains("/api/v1/version");
    }

    // ---- helpers ----

    static Map<String, String> proxyHeaders(Directive server) {
        Map<String, String> h = new java.util.LinkedHashMap<>();
        for (Directive d : server.all("proxy_set_header")) {
            h.put(d.args.get(0), d.args.get(1));
        }
        return h;
    }

    List<String> contractGetPaths() {
        try (Reader r = Files.newBufferedReader(Path.of("contract/openapi.yaml"))) {
            Map<String, Object> doc = new Yaml().load(r);
            List<String> paths = new ArrayList<>();
            map(doc.get("paths")).forEach((p, ops) -> {
                if (map(ops).containsKey("get")) {
                    paths.add("/api/v1" + p);
                }
            });
            return paths.stream().sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, Object> configMap(String name) {
        return docs.stream().filter(d -> "ConfigMap".equals(d.get("kind")) && name.equals(name(d))).findFirst().orElseThrow();
    }

    static Map<String, Object> data(Map<String, Object> cm) {
        return map(cm.get("data"));
    }

    static String name(Map<String, Object> doc) {
        return (String) map(doc.get("metadata")).get("name");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> load(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file)) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : new Yaml().loadAll(r)) {
                if (o != null) {
                    out.add((Map<String, Object>) o);
                }
            }
            return out;
        }
    }

    /** One nginx directive: name, arguments (quotes removed) and the block's directives (null for a simple one). */
    record Directive(String name, List<String> args, List<Directive> block) {

        List<Directive> all(String n) {
            return block == null ? List.of() : block.stream().filter(d -> d.name.equals(n)).toList();
        }

        Optional<Directive> first(String n) {
            return all(n).stream().findFirst();
        }

        List<Directive> deep(String n) {
            List<Directive> out = new ArrayList<>();
            if (block != null) {
                for (Directive d : block) {
                    if (d.name.equals(n)) {
                        out.add(d);
                    }
                    out.addAll(d.deep(n));
                }
            }
            return out;
        }
    }

    /** Minimal nginx config reader and request evaluator (only what the pl-web config uses). */
    static final class Nginx {

        /** The last parsed http context: {@code if ($map_variable)} conditions are evaluated against its maps. */
        static volatile Directive maps = new Directive("http", List.of(), List.of());

        static Directive parse(String text, Map<String, String> includes) {
            List<String> tokens = tokens(text);
            int[] pos = {0};
            Directive http = new Directive("http", List.of(), block(tokens, pos, includes));
            if (!http.all("map").isEmpty()) {
                maps = http;
            }
            return http;
        }

        private static List<Directive> block(List<String> t, int[] pos, Map<String, String> includes) {
            List<Directive> out = new ArrayList<>();
            while (pos[0] < t.size()) {
                String name = t.get(pos[0]++);
                if (name.equals("}")) {
                    return out;
                }
                List<String> args = new ArrayList<>();
                while (!t.get(pos[0]).equals(";") && !t.get(pos[0]).equals("{")) {
                    args.add(t.get(pos[0]++));
                }
                if (t.get(pos[0]++).equals("{")) {
                    out.add(new Directive(name, args, block(t, pos, includes)));
                } else if (name.equals("include")) {
                    String included = includes.get(args.get(0));
                    assertThat(included).as("include %s", args.get(0)).isNotNull();
                    out.addAll(parse(included, includes).block());
                } else {
                    out.add(new Directive(name, args, null));
                }
            }
            return out;
        }

        private static List<String> tokens(String s) {
            List<String> out = new ArrayList<>();
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '#') {
                    while (i < s.length() && s.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '{' || c == '}' || c == ';') {
                    out.add(String.valueOf(c));
                    i++;
                } else if (c == '"' || c == '\'') {
                    int end = s.indexOf(c, i + 1);
                    out.add(s.substring(i + 1, end));
                    i = end + 1;
                } else {
                    int j = i;
                    int depth = 0;
                    while (j < s.length()) {
                        char d = s.charAt(j);
                        if (d == '(') {
                            depth++;
                        } else if (d == ')') {
                            depth--;
                        } else if (depth == 0 && (Character.isWhitespace(d) || d == ';' || d == '{' || d == '}')) {
                            break;
                        }
                        j++;
                    }
                    out.add(s.substring(i, j));
                    i = j;
                }
            }
            return out;
        }

        /** Value of a {@code map} for a source string (exact keys first, then "~" regexes in order, then default — as nginx). */
        static String map(Directive map, String source) {
            String fallback = null;
            for (Directive e : map.block()) {
                if (e.name.equals("default")) {
                    fallback = e.args.get(0);
                } else if (!e.name.startsWith("~") && e.name.equals(source)) {
                    return e.args.get(0);
                }
            }
            for (Directive e : map.block()) {
                if (e.name.startsWith("~") && pcre(e.name.substring(1)).matcher(source).find()) {
                    return e.args.get(0);
                }
            }
            return fallback;
        }

        private static Pattern pcre(String regex) {
            // Java writes the named backreference \k<dv> the same way; nothing to translate for this config
            return Pattern.compile(regex);
        }

        /** "return N", "proxy <target>" or "static" for a request on one server. */
        static String outcome(Directive server, String method, String uri) {
            for (Directive d : server.block()) {
                if (d.name.equals("if")) {
                    String cond = String.join(" ", d.args);
                    var m = Pattern.compile("^\\(\\$request_method !~ (.+)\\)$").matcher(cond);
                    var v = Pattern.compile("^\\((\\$[a-z0-9_]+)\\)$").matcher(cond);
                    boolean refuse;
                    if (m.matches()) {
                        refuse = !Pattern.compile(m.group(1)).matcher(method).find();
                    } else {
                        // if ($var): true unless empty or "0"; $var is a map over $request_method and $uri
                        assertThat(v.matches()).as("only method checks are used: %s", cond).isTrue();
                        Directive map = maps.all("map").stream().filter(x -> x.args.get(1).equals(v.group(1))).findFirst().orElseThrow();
                        String source = map.args.get(0).replace("$request_method", method).replace("$uri", uri);
                        assertThat(source).as("map source of %s uses only $request_method and $uri", v.group(1)).doesNotContain("$");
                        String value = map(map, source);
                        refuse = value != null && !value.isEmpty() && !value.equals("0");
                    }
                    if (refuse) {
                        return "return " + d.first("return").orElseThrow().args.get(0);
                    }
                }
            }
            Directive loc = location(server, uri);
            if (loc == null) {
                return "return 404";
            }
            Optional<Directive> ret = loc.first("return");
            if (ret.isPresent()) {
                return "return " + ret.get().args.get(0);
            }
            Optional<Directive> pass = loc.first("proxy_pass");
            return pass.map(p -> "proxy " + p.args.get(0)).orElse("static " + loc.args);
        }

        private static Directive location(Directive server, String uri) {
            Directive prefix = null;
            for (Directive l : server.all("location")) {
                if (l.args.get(0).equals("=") && l.args.get(1).equals(uri)) {
                    return l;
                }
                if (l.args.size() == 1 && uri.startsWith(l.args.get(0))
                        && (prefix == null || l.args.get(0).length() > prefix.args.get(0).length())) {
                    prefix = l;
                }
            }
            for (Directive l : server.all("location")) {
                if (l.args.get(0).equals("~") && Pattern.compile(l.args.get(1)).matcher(uri).find()) {
                    return l;
                }
                assertThat(l.args.get(0)).as("no case-insensitive or ^~ locations").isNotIn("~*", "^~");
            }
            return prefix;
        }
    }
}
