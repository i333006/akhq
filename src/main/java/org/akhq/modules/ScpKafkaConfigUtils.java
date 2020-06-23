

package org.akhq.modules;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class ScpKafkaConfigUtils {

    private static Logger logger = LoggerFactory.getLogger(ScpKafkaConfigUtils.class);

    private ScpKafkaConfigUtils() {
        super();
    }

    private static Certificate loadCert(final String certFile) throws CertificateException, IOException {
        try (InputStream is = new FileInputStream(certFile)) {
            final CertificateFactory cf = CertificateFactory.getInstance("X.509");
            final Certificate cert = cf.generateCertificate(is);
            return cert;
        }
    }

    public static String createTruststoreWithRootCert(final String password, final String certFile)
            throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
        // Create the keystore
        final KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        keystore.load(null, password.toCharArray());

        // Add the certificate
        keystore.setCertificateEntry("KafkaRootCA", loadCert(certFile));

        // Save the new keystore
        final File keystoreFile = File.createTempFile("kafkaTrustStore", null);
        try (FileOutputStream os = new FileOutputStream(keystoreFile)) {
            keystore.store(os, password.toCharArray());
        }
        logger.info(keystoreFile.getAbsolutePath()+ "*************************");
        return keystoreFile.getAbsolutePath();
    }

    public static void downloadFile(final String url, final File output) throws IOException {
        try (ReadableByteChannel in = Channels.newChannel(new URL(url).openStream());
             FileOutputStream fileOutputStream = new FileOutputStream(output);
             FileChannel out = fileOutputStream.getChannel()) {
            out.transferFrom(in, 0, Long.MAX_VALUE);
        }
    }

    public static String getToken(final String tokenUrl, final String user, final String password) throws IOException {
        final String userCredentials = user + ":" + password;
        final String authHeaderValue = "Basic "
                + Base64.getEncoder().encodeToString(userCredentials.getBytes(StandardCharsets.UTF_8));
        final String bodyParams = "grant_type=client_credentials";
        final byte[] postData = bodyParams.getBytes(StandardCharsets.UTF_8);
        final URL url = new URL(tokenUrl);
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty(HttpHeaders.AUTHORIZATION, authHeaderValue);
        conn.setRequestMethod("POST");
        conn.setRequestProperty(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");
        conn.setRequestProperty("charset", "utf-8");
        conn.setRequestProperty(HttpHeaders.CONTENT_LENGTH, "" + postData.length);
        conn.setUseCaches(false);
        conn.setDoInput(true);
        conn.setDoOutput(true);
        try (DataOutputStream os = new DataOutputStream(conn.getOutputStream())) {
            os.write(postData);
        }
        String resp;
        try (DataInputStream is = new DataInputStream(conn.getInputStream());
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8.name()))) {
            resp = br.lines().collect(Collectors.joining("\n"));
        }
        conn.disconnect();
        @SuppressWarnings("unchecked")
        final Map<String, Object> result = new ObjectMapper().readValue(resp, HashMap.class);
        return result.get("access_token").toString();
    }
}
