package ca.uhn.fhir.jpa.starter.common;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import com.apicatalog.jsonld.loader.FileLoader;

import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException;

public class util {
    public static String loadResource(String fileName) {
        try {
            InputStream in = FileLoader.class.getClassLoader().getResourceAsStream(fileName);
            InputStreamReader isr = new InputStreamReader(in, StandardCharsets.UTF_8);
            
            BufferedReader br = new BufferedReader(isr);
            String text = br.lines()
                    .collect(Collectors.joining("\n"));
            
            isr.close();
            br.close();
            return text;
        } catch (Exception e) {
            throw new UnclassifiedServerFailureException(500, "Could not load server initialization resources");
        }
    }
}
