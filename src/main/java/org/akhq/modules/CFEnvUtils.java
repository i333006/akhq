package org.akhq.modules;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Objects;

public class CFEnvUtils {
    public static final JsonObject VCAP_SERVICES = loadServices();

    private static JsonObject loadServices() {
        final String env = System.getenv("VCAP_SERVICES");
        return Objects.isNull(env) ? new JsonObject() : new JsonObject(env);
    }

    private static JsonObject getKafkaService() {
        JsonArray s = VCAP_SERVICES.getJsonArray("kafka", new JsonArray());
        return s.isEmpty() ? null : s.getJsonObject(0);
    }

    private static String getBootstrapServers(String def, String type) {
        return Objects.isNull(getKafkaService()) ? def
                : getKafkaService().getJsonObject("credentials").getJsonObject("cluster").getString(type);
    }

    public static String getBootstrapServers_Default(String def) {
        return getBootstrapServers(def, "brokers");
    }

    public static String getBootstrapServers_Plain(String def) {
        return getBootstrapServers(def, "brokers.plain");
    }

    public static String getBootstrapServers_AuthSSL(String def) {
        return getBootstrapServers(def, "brokers.auth_ssl");
    }

    public static String getRootCertUrl(String def) {
        return Objects.isNull(getKafkaService()) ? def
                : getKafkaService().getJsonObject("credentials").getJsonObject("urls").getString("ca_cert");
    }

    public static String getTokenUrl(String def) {
        return Objects.isNull(getKafkaService()) ? def
                : getKafkaService().getJsonObject("credentials").getJsonObject("urls").getString("token");
    }

    public static String getUsername(String def) {
        return Objects.isNull(getKafkaService()) ? def
                : getKafkaService().getJsonObject("credentials").getString("username");
    }

    public static String getPassword(String def) {
        return Objects.isNull(getKafkaService()) ? def
                : getKafkaService().getJsonObject("credentials").getString("password");
    }
}
