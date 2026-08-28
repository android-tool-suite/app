package com.androidtoolsuite.runtime.contract;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PackagePathPolicyTest {
    @Test
    public void acceptsPortableWebPath() throws Exception {
        assertEquals("web/assets/app-1.js", PackagePathPolicy.validateFilePath("web/assets/app-1.js"));
    }

    @Test
    public void rejectsTraversalAbsoluteAndBackslash() {
        assertThrows(ContractException.class, () -> PackagePathPolicy.validateFilePath("web/../secret"));
        assertThrows(ContractException.class, () -> PackagePathPolicy.validateFilePath("/web/index.html"));
        assertThrows(ContractException.class, () -> PackagePathPolicy.validateFilePath("web\\index.html"));
    }

    @Test
    public void rejectsCaseFoldedDuplicatesForPortablePackages() throws Exception {
        Set<String> exact = new HashSet<>();
        Set<String> folded = new HashSet<>();
        PackagePathPolicy.addUnique(exact, folded, "web/App.js");

        assertThrows(
                ContractException.class,
                () -> PackagePathPolicy.addUnique(exact, folded, "web/app.js")
        );
    }
}
