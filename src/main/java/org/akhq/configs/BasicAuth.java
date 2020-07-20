package org.akhq.configs;

import com.google.common.hash.Hashing;
import lombok.Getter;
import org.akhq.modules.CFEnvUtils;

import javax.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Getter
@Singleton
public class BasicAuth {
    String username = CFEnvUtils.getUsername("");
    String password = Hashing.sha256()
                        .hashString(CFEnvUtils.getPassword(""), StandardCharsets.UTF_8)
                        .toString();
    List<String> groups = List.of("admin");

    @SuppressWarnings("UnstableApiUsage")
    public boolean isValidPassword(String password) {
        return this.password.equals(
            Hashing.sha256()
            .hashString(password, StandardCharsets.UTF_8)
            .toString()
        );
    }
}

