package org.springaicommunity.bench.core.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springaicommunity.bench.core.spec.BenchCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class BenchCaseLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());

    public static BenchCase load(Path yamlPath) throws IOException {
        String yamlContent = Files.readString(yamlPath);
        return OBJECT_MAPPER.readValue(yamlContent, BenchCase.class);
    }
}
