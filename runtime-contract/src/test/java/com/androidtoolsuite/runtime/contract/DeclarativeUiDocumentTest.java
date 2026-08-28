package com.androidtoolsuite.runtime.contract;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DeclarativeUiDocumentTest {
    @Test
    public void parsesAndValidatesDeclaredCapabilityActions() throws Exception {
        DeclarativeUiDocument document = DeclarativeUiDocument.parse(fixture());
        RuntimePluginManifest manifest = RuntimePluginManifest.parse(manifest("app"));

        document.validateAgainst(manifest);

        assertEquals("column", document.body.type);
        assertEquals(1, document.queries.size());
        assertEquals(1, document.actions.size());
        assertEquals("app.getSession", document.actions.get(0).method);
        assertEquals("icon", document.body.children.get(0).children.get(0).children.get(0).type);
    }

    @Test
    public void rejectsCapabilityNotDeclaredByManifest() throws Exception {
        DeclarativeUiDocument document = DeclarativeUiDocument.parse(fixture());
        RuntimePluginManifest manifest = RuntimePluginManifest.parse(manifest("storage"));

        assertThrows(ContractException.class, () -> document.validateAgainst(manifest));
    }

    @Test
    public void rejectsUnknownActionReference() throws Exception {
        String invalid = fixture().replace("\"action\": \"refresh\"", "\"action\": \"missing\"");
        assertThrows(ContractException.class, () -> DeclarativeUiDocument.parse(invalid));
    }

    @Test
    public void parsesWebViewAsDeclarativeRenderer() throws Exception {
        DeclarativeUiDocument document = DeclarativeUiDocument.parse(
                "{\"formatVersion\":1,\"body\":{\"type\":\"webview\",\"entry\":\"web/index.html\"}}"
        );

        assertTrue(document.isWebView());
        assertEquals("web/index.html", document.webEntry());
    }

    @Test
    public void rejectsWebViewMixedWithHostActions() {
        String invalid = "{\"formatVersion\":1,\"actions\":[{\"id\":\"refresh\","
                + "\"capability\":\"app\",\"method\":\"app.getSession\",\"payload\":{}}],"
                + "\"body\":{\"type\":\"webview\",\"entry\":\"web/index.html\"}}";

        assertThrows(ContractException.class, () -> DeclarativeUiDocument.parse(invalid));
    }

    private static String fixture() throws Exception {
        try (InputStream input = DeclarativeUiDocumentTest.class.getClassLoader()
                .getResourceAsStream("fixtures/declarative-ui-valid.json")) {
            if (input == null) throw new IllegalStateException("Missing fixture");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String manifest(String capability) {
        return "{"
                + "\"format\":\"ats-plugin\",\"formatVersion\":3,"
                + "\"plugin\":{\"id\":\"sample.declarative\",\"title\":\"Sample\","
                + "\"description\":\"Sample declarative plugin\",\"version\":\"1.0.0\","
                + "\"versionCode\":1,\"minHostVersionCode\":1,\"publisher\":\"sample.publisher\"},"
                + "\"platforms\":[\"android\"],"
                + "\"runtime\":{\"ui\":[{\"id\":\"main\",\"type\":\"declarative\","
                + "\"entry\":\"ui/main.json\"}],\"background\":[],\"providers\":[]},"
                + "\"requires\":{\"plugins\":[],\"capabilities\":[{\"id\":\"" + capability
                + "\",\"version\":\"^1.0.0\",\"optional\":false,\"scopes\":{}}]},"
                + "\"provides\":{\"capabilities\":[]},"
                + "\"contributes\":{\"tools\":[{\"id\":\"main\",\"uiEntry\":\"main\"}],"
                + "\"homeWidgets\":[]},\"datasets\":[],\"tasks\":[]} ";
    }
}
