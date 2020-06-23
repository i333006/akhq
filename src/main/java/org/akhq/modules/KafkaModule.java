package org.akhq.modules;

import com.google.common.collect.ImmutableMap;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.RestService;
import io.confluent.kafka.schemaregistry.client.security.basicauth.BasicAuthCredentialProvider;
import io.confluent.kafka.schemaregistry.client.security.basicauth.BasicAuthCredentialProviderFactory;
import io.confluent.kafka.schemaregistry.client.security.basicauth.UserInfoCredentialProvider;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.codehaus.httpcache4j.uri.URIBuilder;
import org.akhq.configs.AbstractProperties;
import org.akhq.configs.Connection;
import org.akhq.configs.Default;
import org.sourcelab.kafka.connect.apiclient.Configuration;
import org.sourcelab.kafka.connect.apiclient.KafkaConnectClient;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.stream.Collectors;

@Singleton
@Slf4j
public class KafkaModule {
    @Inject
    private List<Connection> connections;

    @Inject
    private List<Default> defaults;

    private String username = CFEnvUtils.getUsername("");

    private String password = CFEnvUtils.getPassword("");

    private String tokenUrl = CFEnvUtils.getTokenUrl("");

    private String caCert = CFEnvUtils.getRootCertUrl("");

    private String bootstrapServers = CFEnvUtils.getBootstrapServers_AuthSSL("");

    private String trustStorePass = String.valueOf(UUID.randomUUID());

    private String token;

    public List<String> getClustersList() {
        return this.connections
                .stream()
                .map(r -> r.getName().split("\\.")[0])
                .distinct()
                .collect(Collectors.toList());
    }

    private Connection getConnection(String cluster) {
        return this.connections
                .stream()
                .filter(r -> r.getName().equals(cluster))
                .findFirst()
                .get();
    }

    private Properties getDefaultsProperties(List<? extends AbstractProperties> current, String type) {
        Properties properties = new Properties();

        current
                .stream()
                .filter(r -> r.getName().equals(type))
                .forEach(r -> r.getProperties()
                        .forEach(properties::put)
                );

        return properties;
    }

    private Properties getConsumerProperties(String clusterId) {
        Properties props = new Properties();
        props.putAll(this.getKafKaProperties());
        props.putAll(this.getDefaultsProperties(this.defaults, "consumer"));
        props.putAll(this.getDefaultsProperties(this.connections, clusterId));

        return props;
    }

    private Properties getProducerProperties(String clusterId) {
        Properties props = new Properties();
        props.putAll(this.getKafKaProperties());
        props.putAll(this.getDefaultsProperties(this.defaults, "producer"));
        props.putAll(this.getDefaultsProperties(this.connections, clusterId));

        return props;
    }

    private Properties getAdminProperties(String clusterId) {
        Properties props = new Properties();
        props.putAll(this.getKafKaProperties());
        props.putAll(this.getDefaultsProperties(this.defaults, "admin"));
        props.putAll(this.getDefaultsProperties(this.connections, clusterId));

        return props;
    }

    private Properties getKafKaProperties() {
        Properties properties = new Properties();
        if(username.isEmpty() || tokenUrl.isEmpty() || password.isEmpty()) {
            return properties;
        }
        properties.put(SaslConfigs.SASL_JAAS_CONFIG, this.getJaasString());
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers );

        try {
            properties.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, createTruststoreWithRootCert(trustStorePass));
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
            e.printStackTrace();
        }
        properties.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, trustStorePass);
        return properties;
    }

    private String getJaasString()  {
        final String base = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";";
        String token = "";
        try {
            token = ScpKafkaConfigUtils.getToken(tokenUrl, username, password);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return String.format(base, username, token);
    }

    private String createTruststoreWithRootCert(final String password)
            throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
        final String certUrl = getRootCertUrl("https://kafka-service-broker.cf.sap.hana.ondemand.com/certs/rootCA.crt");
        final File certFile = File.createTempFile("kafkaRootCert", null);
        ScpKafkaConfigUtils.downloadFile(certUrl, certFile);
        return ScpKafkaConfigUtils.createTruststoreWithRootCert(password, certFile.getAbsolutePath());
    }

    private String getRootCertUrl(final String defaultValue) {
        if ((caCert == null) || caCert.isEmpty()) {
            caCert = defaultValue;
        }
        return caCert;
    }

    private Map<String, AdminClient> adminClient = new HashMap<>();

    public AdminClient getAdminClient(String clusterId) {
        if (!this.adminClient.containsKey(clusterId)) {
            this.adminClient.put(clusterId, AdminClient.create(this.getAdminProperties(clusterId)));
        }

        return this.adminClient.get(clusterId);
    }

    public KafkaConsumer<byte[], byte[]> getConsumer(String clusterId) {
        return new KafkaConsumer<>(
                this.getConsumerProperties(clusterId),
                new ByteArrayDeserializer(),
                new ByteArrayDeserializer()
        );
    }

    public KafkaConsumer<byte[], byte[]> getConsumer(String clusterId, Properties properties) {
        Properties props = this.getConsumerProperties(clusterId);
        props.putAll(properties);

        return new KafkaConsumer<>(
                props,
                new ByteArrayDeserializer(),
                new ByteArrayDeserializer()
        );
    }

    private Map<String, KafkaProducer<byte[], byte[]>> producers = new HashMap<>();

    public KafkaProducer<byte[], byte[]> getProducer(String clusterId) {
        if (!this.producers.containsKey(clusterId)) {
            this.producers.put(clusterId, new KafkaProducer<>(
                    this.getProducerProperties(clusterId),
                    new ByteArraySerializer(),
                    new ByteArraySerializer()
            ));
        }

        return this.producers.get(clusterId);
    }


    public RestService getRegistryRestClient(String clusterId) {
        Connection connection = this.getConnection(clusterId);

        if (connection.getSchemaRegistry() != null) {
            RestService restService = new RestService(
                    connection.getSchemaRegistry().getUrl().toString()
            );

            if (connection.getSchemaRegistry().getBasicAuthUsername() != null) {
                BasicAuthCredentialProvider basicAuthCredentialProvider = BasicAuthCredentialProviderFactory
                        .getBasicAuthCredentialProvider(
                                new UserInfoCredentialProvider().alias(),
                                ImmutableMap.of(
                                        "schema.registry.basic.auth.user.info",
                                        connection.getSchemaRegistry().getBasicAuthUsername() + ":" +
                                                connection.getSchemaRegistry().getBasicAuthPassword()
                                )
                        );
                restService.setBasicAuthCredentialProvider(basicAuthCredentialProvider);
            }

            if (connection.getSchemaRegistry().getProperties() != null) {
                restService.configure(connection.getSchemaRegistry().getProperties());
            }
            return restService;
        }
        return null;
    }

    private Map<String, SchemaRegistryClient> registryClient = new HashMap<>();

    public SchemaRegistryClient getRegistryClient(String clusterId) {
        if (!this.registryClient.containsKey(clusterId)) {
            Connection connection = this.getConnection(clusterId);

            SchemaRegistryClient client = new CachedSchemaRegistryClient(
                    this.getRegistryRestClient(clusterId),
                    Integer.MAX_VALUE,
                    connection.getSchemaRegistry() != null ? connection.getSchemaRegistry().getProperties() : null
            );

            this.registryClient.put(clusterId, client);
        }

        return this.registryClient.get(clusterId);
    }

    private Map<String, Map<String, KafkaConnectClient>> connectRestClient = new HashMap<>();

    public Map<String, KafkaConnectClient> getConnectRestClient(String clusterId) {
        if (!this.connectRestClient.containsKey(clusterId)) {
            Connection connection = this.getConnection(clusterId);

            if (connection.getConnect() != null && !connection.getConnect().isEmpty()) {

                Map<String,KafkaConnectClient> mapConnects = new HashMap<>();
                connection.getConnect().forEach(connect -> {

                    URIBuilder uri = URIBuilder.fromString(connect.getUrl().toString());
                    Configuration configuration = new Configuration(uri.toNormalizedURI(false).toString());

                    if (connect.getBasicAuthUsername() != null) {
                        configuration.useBasicAuth(
                                connect.getBasicAuthUsername(),
                                connect.getBasicAuthPassword()
                        );
                    }

                    if (connect.getSslTrustStore() != null) {
                        configuration.useTrustStore(
                                new File(connect.getSslTrustStore()),
                                connect.getSslTrustStorePassword()
                        );
                    }

                    if (connect.getSslKeyStore() != null) {
                        configuration.useKeyStore(
                                new File(connect.getSslKeyStore()),
                                connect.getSslKeyStorePassword()
                        );
                    }
                    mapConnects.put(connect.getName(), new KafkaConnectClient(configuration));
                });
                this.connectRestClient.put(clusterId, mapConnects);
            }
        }

        return this.connectRestClient.get(clusterId);
    }

}